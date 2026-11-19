//go:build linux

package tunrouter

import (
	"syscall"

	"golang.org/x/sys/unix"
)

func (d *DirectDialer) buildSocketControl() func(network, address string, c syscall.RawConn) error {
	iface, _ := resolveInterfaceByNameOrIP(d.PhysicalIface)
	ifaceName := d.PhysicalIface
	if iface != nil {
		ifaceName = iface.Name
	}
	if ifaceName == "" {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		var sockErr error
		if err := c.Control(func(fd uintptr) {
			sockErr = unix.SetsockoptString(int(fd), unix.SOL_SOCKET, unix.SO_BINDTODEVICE, ifaceName)
		}); err != nil {
			return err
		}
		return sockErr
	}
}
