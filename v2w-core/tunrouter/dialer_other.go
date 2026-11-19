//go:build !linux && !windows && !darwin

package tunrouter

import "syscall"

func (d *DirectDialer) buildSocketControl() func(network, address string, c syscall.RawConn) error {
	return nil
}
