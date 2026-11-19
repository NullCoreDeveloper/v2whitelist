//go:build windows

package tunrouter

import (
	"encoding/binary"
	"net/netip"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modIphlpapi              = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetExtendedTcpTable  = modIphlpapi.NewProc("GetExtendedTcpTable")
	procGetExtendedUdpTable  = modIphlpapi.NewProc("GetExtendedUdpTable")
)

const (
	tcpTableOwnerPidAll = 5
	udpTableOwnerPid    = 1
)

type mibTcpRowOwnerPid struct {
	State      uint32
	LocalAddr  uint32
	LocalPort  uint32
	RemoteAddr uint32
	RemotePort uint32
	OwningPid  uint32
}

type mibTcp6RowOwnerPid struct {
	LocalAddr     [16]byte
	LocalScopeId  uint32
	LocalPort     uint32
	RemoteAddr    [16]byte
	RemoteScopeId uint32
	RemotePort    uint32
	State         uint32
	OwningPid     uint32
}

type mibUdpRowOwnerPid struct {
	LocalAddr uint32
	LocalPort uint32
	OwningPid uint32
}

type mibUdp6RowOwnerPid struct {
	LocalAddr    [16]byte
	LocalScopeId uint32
	LocalPort    uint32
	OwningPid    uint32
}

func (r *ProcessResolver) lookupPlatform(proto string, src, _ netip.AddrPort) (ProcessInfo, bool) {
	isV6 := src.Addr().Unmap().Is6()
	var pid uint32
	if proto == "udp" {
		pid = findWindowsUDPPid(isV6, src.Port())
	} else {
		pid = findWindowsTCPPid(isV6, src.Port())
	}
	if pid == 0 {
		return ProcessInfo{}, false
	}
	path := queryWindowsProcessPath(pid)
	name := ""
	if path != "" {
		name = filepath.Base(strings.ReplaceAll(path, "\\", "/"))
	}
	return ProcessInfo{
		PID:  int(pid),
		Name: name,
		Path: path,
	}, true
}

func ntohsPort(raw uint32) uint16 {
	var b [2]byte
	binary.LittleEndian.PutUint16(b[:], uint16(raw))
	return binary.BigEndian.Uint16(b[:])
}

func findWindowsTCPPid(isV6 bool, srcPort uint16) uint32 {
	family := uint32(windows.AF_INET)
	if isV6 {
		family = windows.AF_INET6
	}
	var size uint32
	procGetExtendedTcpTable.Call(0, uintptr(unsafe.Pointer(&size)), 0, uintptr(family), tcpTableOwnerPidAll, 0)
	if size == 0 {
		return 0
	}
	buf := make([]byte, size)
	ret, _, _ := procGetExtendedTcpTable.Call(
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(unsafe.Pointer(&size)),
		0,
		uintptr(family),
		tcpTableOwnerPidAll,
		0,
	)
	if ret != 0 || len(buf) < 4 {
		return 0
	}
	num := binary.LittleEndian.Uint32(buf[:4])
	off := uintptr(4)
	if !isV6 {
		rowSize := unsafe.Sizeof(mibTcpRowOwnerPid{})
		for i := uint32(0); i < num && int(off+rowSize) <= len(buf); i++ {
			row := (*mibTcpRowOwnerPid)(unsafe.Pointer(&buf[off]))
			if ntohsPort(row.LocalPort) == srcPort && row.OwningPid > 0 {
				return row.OwningPid
			}
			off += rowSize
		}
	} else {
		rowSize := unsafe.Sizeof(mibTcp6RowOwnerPid{})
		for i := uint32(0); i < num && int(off+rowSize) <= len(buf); i++ {
			row := (*mibTcp6RowOwnerPid)(unsafe.Pointer(&buf[off]))
			if ntohsPort(row.LocalPort) == srcPort && row.OwningPid > 0 {
				return row.OwningPid
			}
			off += rowSize
		}
	}
	return 0
}

func findWindowsUDPPid(isV6 bool, srcPort uint16) uint32 {
	family := uint32(windows.AF_INET)
	if isV6 {
		family = windows.AF_INET6
	}
	var size uint32
	procGetExtendedUdpTable.Call(0, uintptr(unsafe.Pointer(&size)), 0, uintptr(family), udpTableOwnerPid, 0)
	if size == 0 {
		return 0
	}
	buf := make([]byte, size)
	ret, _, _ := procGetExtendedUdpTable.Call(
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(unsafe.Pointer(&size)),
		0,
		uintptr(family),
		udpTableOwnerPid,
		0,
	)
	if ret != 0 || len(buf) < 4 {
		return 0
	}
	num := binary.LittleEndian.Uint32(buf[:4])
	off := uintptr(4)
	if !isV6 {
		rowSize := unsafe.Sizeof(mibUdpRowOwnerPid{})
		for i := uint32(0); i < num && int(off+rowSize) <= len(buf); i++ {
			row := (*mibUdpRowOwnerPid)(unsafe.Pointer(&buf[off]))
			if ntohsPort(row.LocalPort) == srcPort && row.OwningPid > 0 {
				return row.OwningPid
			}
			off += rowSize
		}
	} else {
		rowSize := unsafe.Sizeof(mibUdp6RowOwnerPid{})
		for i := uint32(0); i < num && int(off+rowSize) <= len(buf); i++ {
			row := (*mibUdp6RowOwnerPid)(unsafe.Pointer(&buf[off]))
			if ntohsPort(row.LocalPort) == srcPort && row.OwningPid > 0 {
				return row.OwningPid
			}
			off += rowSize
		}
	}
	return 0
}

func queryWindowsProcessPath(pid uint32) string {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(h)

	buf := make([]uint16, windows.MAX_PATH*2)
	sz := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(h, 0, &buf[0], &sz); err != nil {
		return ""
	}
	_ = syscall.Errno(0)
	return windows.UTF16ToString(buf[:sz])
}
