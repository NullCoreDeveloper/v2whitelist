//go:build darwin

package tunrouter

import (
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

const darwinIPv6BoundIf = 125

func (d *DirectDialer) buildSocketControl() func(network, address string, c syscall.RawConn) error {
	iface, _ := resolveInterfaceByNameOrIP(d.PhysicalIface)
	if iface == nil || iface.Index <= 0 {
		return nil
	}
	ifIdx := iface.Index
	return func(network, address string, c syscall.RawConn) error {
		var sockErr error
		if err := c.Control(func(fd uintptr) {
			if strings.HasSuffix(network, "6") {
				sockErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, darwinIPv6BoundIf, ifIdx)
			} else {
				sockErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_BOUND_IF, ifIdx)
			}
		}); err != nil {
			return err
		}
		return sockErr
	}
}
