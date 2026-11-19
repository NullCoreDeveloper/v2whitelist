//go:build windows

package tunrouter

import (
	"encoding/binary"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	winIPUnicastIf   = 31
	winIPv6UnicastIf = 31
)

func (d *DirectDialer) buildSocketControl() func(network, address string, c syscall.RawConn) error {
	iface, _ := resolveInterfaceByNameOrIP(d.PhysicalIface)
	if iface == nil || iface.Index <= 0 {
		return nil
	}
	ifIdx := uint32(iface.Index)
	var beIdx [4]byte
	binary.BigEndian.PutUint32(beIdx[:], ifIdx)
	v4OptVal := *(*int32)(unsafe.Pointer(&beIdx[0]))

	return func(network, address string, c syscall.RawConn) error {
		var sockErr error
		if err := c.Control(func(fd uintptr) {
			h := windows.Handle(fd)
			if strings.HasSuffix(network, "6") {
				sockErr = windows.SetsockoptInt(h, windows.IPPROTO_IPV6, winIPv6UnicastIf, int(ifIdx))
			} else {
				sockErr = windows.SetsockoptInt(h, windows.IPPROTO_IP, winIPUnicastIf, int(v4OptVal))
			}
		}); err != nil {
			return err
		}
		return sockErr
	}
}
