//go:build linux

package tunrouter

import (
	"bufio"
	"encoding/binary"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

const (
	sockDiagByFamily = 20
	inetDiagReqSize  = 56
	inetDiagMsgSize  = 72
)

func (r *ProcessResolver) lookupPlatform(proto string, src, dst netip.AddrPort) (ProcessInfo, bool) {
	srcAddr := src.Addr().Unmap()
	family := uint8(unix.AF_INET)
	if srcAddr.Is6() {
		family = unix.AF_INET6
	}
	ipProto := uint8(unix.IPPROTO_TCP)
	if proto == "udp" {
		ipProto = unix.IPPROTO_UDP
	}

	inode := findInodeNetlink(family, ipProto, srcAddr, src.Port(), dst.Addr().Unmap(), dst.Port())
	if inode == 0 {
		inode = findInodeProcFS(proto, srcAddr.Is6(), src.Port())
	}
	if inode == 0 {
		return ProcessInfo{}, false
	}
	return r.lookupProcessByInode(inode)
}

func findInodeNetlink(family, proto uint8, srcIP netip.Addr, srcPort uint16, dstIP netip.Addr, dstPort uint16) uint32 {
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.NETLINK_SOCK_DIAG)
	if err != nil {
		return 0
	}
	defer unix.Close(fd)

	_ = unix.SetsockoptTimeval(fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Sec: 0, Usec: 200000})
	if err := unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return 0
	}

	// Формируем nlmsghdr (16) + inet_diag_req_v2 (56)
	totalLen := unix.NLMSG_HDRLEN + inetDiagReqSize
	req := make([]byte, totalLen)
	binary.LittleEndian.PutUint32(req[0:4], uint32(totalLen))
	binary.LittleEndian.PutUint16(req[4:6], sockDiagByFamily)
	binary.LittleEndian.PutUint16(req[6:8], unix.NLM_F_REQUEST|unix.NLM_F_DUMP)
	binary.LittleEndian.PutUint32(req[8:12], 1)

	body := req[unix.NLMSG_HDRLEN:]
	body[0] = family
	body[1] = proto
	binary.LittleEndian.PutUint32(body[4:8], 0xffffffff)
	binary.BigEndian.PutUint16(body[8:10], srcPort)
	// Для UDP не фиксируем dport/dst при запросе, чтобы найти несвязанные (unconnected) сокеты
	if proto == unix.IPPROTO_TCP && dstPort > 0 {
		binary.BigEndian.PutUint16(body[10:12], dstPort)
	}
	// idiag_cookie = INET_DIAG_NOCOOKIE (0xffffffffffffffff)
	for i := 48; i < 56; i++ {
		body[i] = 0xff
	}

	sa := &unix.SockaddrNetlink{Family: unix.AF_NETLINK}
	if err := unix.Sendto(fd, req, 0, sa); err != nil {
		return 0
	}

	buf := make([]byte, 32768)
	var fallbackInode uint32
	for {
		n, _, err := unix.Recvfrom(fd, buf, 0)
		if err != nil || n <= 0 {
			break
		}
		msgs, err := syscall.ParseNetlinkMessage(buf[:n])
		if err != nil {
			break
		}
		for _, m := range msgs {
			if m.Header.Type == syscall.NLMSG_DONE {
				return fallbackInode
			}
			if m.Header.Type == syscall.NLMSG_ERROR {
				return fallbackInode
			}
			if len(m.Data) < inetDiagMsgSize {
				continue
			}
			msgSport := binary.BigEndian.Uint16(m.Data[4:6])
			if msgSport != srcPort {
				continue
			}
			inode := binary.LittleEndian.Uint32(m.Data[68:72])
			if inode == 0 {
				continue
			}
			msgDport := binary.BigEndian.Uint16(m.Data[6:8])
			if dstPort > 0 && msgDport == dstPort {
				return inode
			}
			if fallbackInode == 0 {
				fallbackInode = inode
			}
		}
	}
	return fallbackInode
}

func findInodeProcFS(proto string, isV6 bool, srcPort uint16) uint32 {
	path := "/proc/net/" + proto
	if isV6 {
		path += "6"
	}
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	// Пропускаем заголовок
	if !scanner.Scan() {
		return 0
	}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			continue
		}
		colon := strings.LastIndexByte(fields[1], ':')
		if colon == -1 {
			continue
		}
		p, err := strconv.ParseUint(fields[1][colon+1:], 16, 16)
		if err != nil || uint16(p) != srcPort {
			continue
		}
		inode, err := strconv.ParseUint(fields[9], 10, 32)
		if err == nil && inode > 0 {
			return uint32(inode)
		}
	}
	return 0
}

func (r *ProcessResolver) lookupProcessByInode(inode uint32) (ProcessInfo, bool) {
	now := time.Now()
	r.mu.RLock()
	if ent, ok := r.inodes[inode]; ok && now.Before(ent.expiresAt) {
		r.mu.RUnlock()
		return ent.info, true
	}
	r.mu.RUnlock()

	procDir, err := os.Open("/proc")
	if err != nil {
		return ProcessInfo{}, false
	}
	defer procDir.Close()

	entries, err := procDir.Readdirnames(-1)
	if err != nil {
		return ProcessInfo{}, false
	}

	targetLink := "socket:[" + strconv.FormatUint(uint64(inode), 10) + "]"
	for _, name := range entries {
		pid, err := strconv.Atoi(name)
		if err != nil || pid <= 0 {
			continue
		}
		fdPath := "/proc/" + name + "/fd"
		fdDir, err := os.Open(fdPath)
		if err != nil {
			continue
		}
		fdNames, err := fdDir.Readdirnames(-1)
		fdDir.Close()
		if err != nil {
			continue
		}
		matched := false
		for _, fn := range fdNames {
			link, err := os.Readlink(fdPath + "/" + fn)
			if err == nil && link == targetLink {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}

		exePath, _ := os.Readlink("/proc/" + name + "/exe")
		baseName := ""
		if exePath != "" {
			baseName = filepath.Base(exePath)
		} else if comm, err := os.ReadFile("/proc/" + name + "/comm"); err == nil {
			baseName = strings.TrimSpace(string(comm))
		}
		info := ProcessInfo{
			PID:  pid,
			Name: baseName,
			Path: exePath,
		}
		r.mu.Lock()
		if len(r.inodes) > 8192 {
			for k, v := range r.inodes {
				if now.After(v.expiresAt) {
					delete(r.inodes, k)
				}
			}
		}
		r.inodes[inode] = inodeCacheEntry{info: info, expiresAt: now.Add(15 * time.Second)}
		r.mu.Unlock()
		return info, true
	}
	_ = unsafe.Sizeof(0)
	return ProcessInfo{}, false
}
