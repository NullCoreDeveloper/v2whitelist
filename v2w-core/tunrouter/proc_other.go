//go:build !linux && !windows && !darwin

package tunrouter

import "net/netip"

func (r *ProcessResolver) lookupPlatform(_ string, _, _ netip.AddrPort) (ProcessInfo, bool) {
	return ProcessInfo{}, false
}
