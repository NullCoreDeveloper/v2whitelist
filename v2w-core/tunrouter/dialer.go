package tunrouter

import (
	"context"
	"net"
	"net/netip"
	"strings"
	"time"
)

// DirectDialer открывает прямые TCP/UDP соединения в обход TUN через физический интерфейс.
type DirectDialer struct {
	PhysicalIface string
	DirectDNS     string
	dialer        *net.Dialer
	listenCfg     *net.ListenConfig
}

func NewDirectDialer(physicalIface string, directDNS string) *DirectDialer {
	dd := &DirectDialer{
		PhysicalIface: strings.TrimSpace(physicalIface),
		DirectDNS:     strings.TrimSpace(directDNS),
	}
	ctrl := dd.buildSocketControl()
	dd.dialer = &net.Dialer{
		Timeout: 8 * time.Second,
		Control: ctrl,
	}
	dd.listenCfg = &net.ListenConfig{
		Control: ctrl,
	}
	return dd
}

// DialTCP открывает прямое TCP-соединение мимо TUN.
func (d *DirectDialer) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	return d.dialer.DialContext(ctx, "tcp", formatAddrPort(dst))
}

// ListenUDP открывает прямой UDP-сокет, привязанный к физическому интерфейсу.
func (d *DirectDialer) ListenUDP(ctx context.Context, isIPv6 bool) (net.PacketConn, error) {
	network := "udp4"
	bindAddr := ":0"
	if isIPv6 {
		network = "udp6"
	}
	return d.listenCfg.ListenPacket(ctx, network, bindAddr)
}

// ResolveDirectDNSAddr возвращает адрес DNS-сервера для direct-приложений.
func (d *DirectDialer) ResolveDirectDNSAddr(origDst netip.AddrPort) netip.AddrPort {
	if d.DirectDNS != "" {
		if ap, err := netip.ParseAddrPort(d.DirectDNS); err == nil {
			return ap
		}
		if addr, err := netip.ParseAddr(d.DirectDNS); err == nil {
			return netip.AddrPortFrom(addr, 53)
		}
	}
	origIP := origDst.Addr().Unmap()
	// Если приложение стучалось в loopback/link-local/виртуальный DNS адаптера TUN, переводим на публичный DNS по физическому каналу
	if origIP.IsLoopback() || origIP.IsLinkLocalUnicast() || origIP.IsPrivate() || strings.HasPrefix(origIP.String(), "198.18.") {
		return netip.AddrPortFrom(netip.MustParseAddr("1.1.1.1"), 53)
	}
	return origDst
}

func resolveInterfaceByNameOrIP(nameOrIP string) (*net.Interface, net.IP) {
	target := strings.TrimSpace(nameOrIP)
	if target == "" {
		return nil, nil
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, nil
	}
	targetIP := net.ParseIP(target)
	for i := range ifaces {
		iface := &ifaces[i]
		if iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if strings.EqualFold(iface.Name, target) {
			return iface, firstIPv4OnIface(iface)
		}
		if targetIP != nil {
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrs {
				var ip net.IP
				switch v := a.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}
				if ip != nil && ip.Equal(targetIP) {
					return iface, ip
				}
			}
		}
	}
	return nil, nil
}

func firstIPv4OnIface(iface *net.Interface) net.IP {
	addrs, err := iface.Addrs()
	if err != nil {
		return nil
	}
	for _, a := range addrs {
		var ip net.IP
		switch v := a.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		}
		if ip != nil && ip.To4() != nil {
			return ip.To4()
		}
	}
	return nil
}
