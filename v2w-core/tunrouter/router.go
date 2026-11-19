package tunrouter

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	tunReadOffset = 16
	defaultMTU    = 1500
	udpIdleTTL    = 60 * time.Second
	dnsIdleTTL    = 15 * time.Second
)

// RouterConfig задаёт параметры запуска встроенного TUN-маршрутизатора v2w-tun.
type RouterConfig struct {
	DeviceName    string      `json:"device_name"`
	MTU           int         `json:"mtu"`
	SocksAddr     string      `json:"socks_addr"`
	SocksUser     string      `json:"socks_user,omitempty"`
	SocksPass     string      `json:"socks_pass,omitempty"`
	PhysicalIface string      `json:"physical_iface,omitempty"`
	DirectDNS     string      `json:"direct_dns,omitempty"`
	Split         SplitConfig `json:"split"`
}

func LoadConfigFile(path string) (RouterConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return RouterConfig{}, err
	}
	var cfg RouterConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return RouterConfig{}, err
	}
	return cfg, nil
}

// Router управляет TUN-интерфейсом, gVisor стеком и маршрутизацией потоков (PROXY vs DIRECT).
type Router struct {
	cfg          RouterConfig
	dev          tun.Device
	ep           *channel.Endpoint
	gstack       *stack.Stack
	policy       *PolicyEngine
	resolver     *ProcessResolver
	socks        *Socks5Client
	direct       *DirectDialer
	udpMgr       *udpNATManager
	ctx          context.Context
	cancel       context.CancelFunc
	closeOnce    sync.Once
	wg           sync.WaitGroup
	actualDevice string
}

// StartRouter создаёт TUN-устройство и запускает обработку пакетов.
func StartRouter(cfg RouterConfig) (*Router, error) {
	if cfg.MTU <= 0 {
		cfg.MTU = defaultMTU
	}
	if strings.TrimSpace(cfg.DeviceName) == "" {
		cfg.DeviceName = "v2w-tun"
	}
	if strings.TrimSpace(cfg.SocksAddr) == "" {
		cfg.SocksAddr = "127.0.0.1:10808"
	}

	dev, err := tun.CreateTUN(cfg.DeviceName, cfg.MTU)
	if err != nil {
		return nil, fmt.Errorf("create tun %q: %w", cfg.DeviceName, err)
	}
	actualName, _ := dev.Name()
	if actualName == "" {
		actualName = cfg.DeviceName
	}

	r, err := newRouterWithDevice(cfg, dev, actualName)
	if err != nil {
		_ = dev.Close()
		return nil, err
	}
	r.startLoops()
	return r, nil
}

func newRouterWithDevice(cfg RouterConfig, dev tun.Device, actualName string) (*Router, error) {
	if cfg.MTU <= 0 {
		cfg.MTU = defaultMTU
	}
	gstack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})

	sack := tcpip.TCPSACKEnabled(true)
	_ = gstack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	modBuf := tcpip.TCPModerateReceiveBufferOption(true)
	_ = gstack.SetTransportProtocolOption(tcp.ProtocolNumber, &modBuf)

	ep := channel.New(1024, uint32(cfg.MTU), "")
	if tcpipErr := gstack.CreateNIC(1, ep); tcpipErr != nil {
		gstack.Close()
		return nil, fmt.Errorf("CreateNIC: %v", tcpipErr)
	}
	_ = gstack.SetPromiscuousMode(1, true)
	_ = gstack.SetSpoofing(1, true)
	gstack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})

	ctx, cancel := context.WithCancel(context.Background())
	r := &Router{
		cfg:          cfg,
		dev:          dev,
		ep:           ep,
		gstack:       gstack,
		policy:       NewPolicyEngine(cfg.Split),
		resolver:     NewProcessResolver(),
		socks:        &Socks5Client{ProxyAddr: cfg.SocksAddr, Username: cfg.SocksUser, Password: cfg.SocksPass},
		direct:       NewDirectDialer(cfg.PhysicalIface, cfg.DirectDNS),
		ctx:          ctx,
		cancel:       cancel,
		actualDevice: actualName,
	}
	r.udpMgr = newUDPNATManager(r)
	r.installForwarders()
	return r, nil
}

func (r *Router) DeviceName() string {
	return r.actualDevice
}

func (r *Router) Close() error {
	var err error
	r.closeOnce.Do(func() {
		r.cancel()
		r.udpMgr.closeAll()
		if r.dev != nil {
			err = r.dev.Close()
		}
		if r.ep != nil {
			r.ep.Close()
		}
		if r.gstack != nil {
			r.gstack.Close()
		}
		r.wg.Wait()
	})
	return err
}

func (r *Router) startLoops() {
	// Чтение из TUN-интерфейса -> gVisor
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		batch := r.dev.BatchSize()
		if batch <= 0 {
			batch = 1
		}
		bufSize := tunReadOffset + r.cfg.MTU + 64
		bufs := make([][]byte, batch)
		sizes := make([]int, batch)
		for i := range bufs {
			bufs[i] = make([]byte, bufSize)
		}

		for {
			if r.ctx.Err() != nil {
				return
			}
			n, err := r.dev.Read(bufs, sizes, tunReadOffset)
			if err != nil {
				if r.ctx.Err() != nil {
					return
				}
				log.Printf("[v2w-tun] tun read error: %v", err)
				return
			}
			for i := 0; i < n; i++ {
				sz := sizes[i]
				if sz <= 0 {
					continue
				}
				pktBytes := bufs[i][tunReadOffset : tunReadOffset+sz]
				pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{
					Payload: buffer.MakeWithData(pktBytes),
				})
				switch pktBytes[0] >> 4 {
				case 4:
					r.ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
				case 6:
					r.ep.InjectInbound(header.IPv6ProtocolNumber, pkb)
				}
				pkb.DecRef()
			}
		}
	}()

	// Чтение из gVisor -> запись в TUN-интерфейс
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		batch := r.dev.BatchSize()
		if batch <= 0 {
			batch = 1
		}
		writeBufs := make([][]byte, 1)
		rawBuf := make([]byte, tunReadOffset+r.cfg.MTU+64)
		for {
			pkt := r.ep.ReadContext(r.ctx)
			if pkt == nil {
				return
			}
			view := pkt.ToView()
			pkt.DecRef()
			n, _ := view.Read(rawBuf[tunReadOffset:])
			view.Release()
			if n <= 0 {
				continue
			}
			writeBufs[0] = rawBuf[:tunReadOffset+n]
			if _, err := r.dev.Write(writeBufs, tunReadOffset); err != nil {
				if r.ctx.Err() != nil {
					return
				}
			}
		}
	}()

	// Периодическая сборка неактивных UDP-сессий
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-r.ctx.Done():
				return
			case <-ticker.C:
				r.udpMgr.gc()
			}
		}
	}()
}

func (r *Router) decideRoute(proto string, src, dst netip.AddrPort) (RouteAction, ProcessInfo) {
	if !r.policy.NeedsProcessLookup() {
		return r.policy.Decide(ProcessInfo{}), ProcessInfo{}
	}
	proc, _ := r.resolver.Resolve(proto, src, dst)
	return r.policy.Decide(proc), proc
}

func (r *Router) installForwarders() {
	tcpFwd := tcp.NewForwarder(r.gstack, 0, 65535, func(req *tcp.ForwarderRequest) {
		r.wg.Add(1)
		go func(req *tcp.ForwarderRequest) {
			defer r.wg.Done()
			id := req.ID()
			src := netip.AddrPortFrom(tcpipAddrToNetip(id.RemoteAddress), id.RemotePort)
			dst := netip.AddrPortFrom(tcpipAddrToNetip(id.LocalAddress), id.LocalPort)

			action, _ := r.decideRoute("tcp", src, dst)
			dialCtx, cancel := context.WithTimeout(r.ctx, 10*time.Second)
			var outConn net.Conn
			var dialErr error
			if action == RouteDirect {
				target := dst
				if dst.Port() == 53 {
					target = r.direct.ResolveDirectDNSAddr(dst)
				}
				outConn, dialErr = r.direct.DialTCP(dialCtx, target)
			} else {
				outConn, dialErr = r.socks.DialTCP(dialCtx, dst)
			}
			cancel()

			if dialErr != nil {
				req.Complete(true)
				return
			}

			var wq waiter.Queue
			ep, tcpErr := req.CreateEndpoint(&wq)
			if tcpErr != nil {
				outConn.Close()
				req.Complete(true)
				return
			}
			req.Complete(false)

			opts := ep.SocketOptions()
			opts.SetKeepAlive(true)

			inConn := gonet.NewTCPConn(&wq, ep)
			relayBiDir(r.ctx, inConn, outConn)
		}(req)
	})
	r.gstack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	r.gstack.SetTransportProtocolHandler(udp.ProtocolNumber, func(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
		data := pkt.Data().AsRange().ToSlice()
		if len(data) == 0 {
			return true
		}
		copied := make([]byte, len(data))
		copy(copied, data)
		src := netip.AddrPortFrom(tcpipAddrToNetip(id.RemoteAddress), id.RemotePort)
		dst := netip.AddrPortFrom(tcpipAddrToNetip(id.LocalAddress), id.LocalPort)
		r.udpMgr.handlePacket(src, dst, copied)
		return true
	})
}

func relayBiDir(ctx context.Context, a, b net.Conn) {
	defer a.Close()
	defer b.Close()

	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)

	select {
	case <-done:
	case <-ctx.Done():
	}
	_ = a.Close()
	_ = b.Close()
	<-done
}

type udpSessionKey struct {
	src    netip.AddrPort
	direct bool
}

type udpSession struct {
	key        udpSessionKey
	router     *Router
	directConn net.PacketConn
	socksCtrl  net.Conn
	socksUDP   *net.UDPConn
	lastActive atomic.Int64
	isDNSOnly  bool
	dnsMu      sync.Mutex
	dnsOrigDst netip.AddrPort
	dnsSentTo  netip.AddrPort
	closeOnce  sync.Once
	closed     atomic.Bool
}

type udpNATManager struct {
	router   *Router
	mu       sync.Mutex
	sessions map[udpSessionKey]*udpSession
}

func newUDPNATManager(r *Router) *udpNATManager {
	return &udpNATManager{
		router:   r,
		sessions: make(map[udpSessionKey]*udpSession),
	}
}

func (m *udpNATManager) handlePacket(src, dst netip.AddrPort, payload []byte) {
	action, _ := m.router.decideRoute("udp", src, dst)
	isDirect := action == RouteDirect
	key := udpSessionKey{src: src, direct: isDirect}

	m.mu.Lock()
	sess, ok := m.sessions[key]
	if !ok {
		var err error
		sess, err = m.createSession(key, dst)
		if err != nil {
			m.mu.Unlock()
			return
		}
		m.sessions[key] = sess
	}
	m.mu.Unlock()

	sess.touch()
	sess.send(dst, payload)
}

func (m *udpNATManager) createSession(key udpSessionKey, firstDst netip.AddrPort) (*udpSession, error) {
	s := &udpSession{
		key:       key,
		router:    m.router,
		isDNSOnly: firstDst.Port() == 53,
	}
	s.touch()

	if key.direct {
		pc, err := m.router.direct.ListenUDP(m.router.ctx, key.src.Addr().Is6())
		if err != nil {
			return nil, err
		}
		s.directConn = pc
		m.router.wg.Add(1)
		go s.readDirectLoop()
	} else {
		ctrl, uc, err := m.router.socks.DialUDP(m.router.ctx)
		if err != nil {
			return nil, err
		}
		s.socksCtrl = ctrl
		s.socksUDP = uc
		m.router.wg.Add(2)
		go s.watchSocksCtrl()
		go s.readSocksUDPLoop()
	}
	return s, nil
}

func (s *udpSession) touch() {
	s.lastActive.Store(time.Now().UnixNano())
}

func (s *udpSession) close() {
	s.closeOnce.Do(func() {
		s.closed.Store(true)
		if s.directConn != nil {
			_ = s.directConn.Close()
		}
		if s.socksUDP != nil {
			_ = s.socksUDP.Close()
		}
		if s.socksCtrl != nil {
			_ = s.socksCtrl.Close()
		}
	})
}

func (s *udpSession) send(dst netip.AddrPort, payload []byte) {
	if s.closed.Load() {
		return
	}
	if s.key.direct {
		sendDst := dst
		if dst.Port() == 53 {
			sendDst = s.router.direct.ResolveDirectDNSAddr(dst)
			s.dnsMu.Lock()
			s.dnsOrigDst = dst
			s.dnsSentTo = sendDst
			s.dnsMu.Unlock()
		}
		_, _ = s.directConn.WriteTo(payload, net.UDPAddrFromAddrPort(sendDst))
		return
	}

	pkt := EncodeSocks5UDPPacket(dst, payload)
	_, _ = s.socksUDP.Write(pkt)
}

func (s *udpSession) readDirectLoop() {
	defer s.router.wg.Done()
	defer s.router.udpMgr.removeSession(s)
	buf := make([]byte, 65535)
	for {
		n, remoteAddr, err := s.directConn.ReadFrom(buf)
		if err != nil {
			return
		}
		s.touch()
		ua, ok := remoteAddr.(*net.UDPAddr)
		if !ok {
			continue
		}
		ipAddr, ok := netip.AddrFromSlice(ua.IP)
		if !ok {
			continue
		}
		from := netip.AddrPortFrom(ipAddr.Unmap(), uint16(ua.Port))
		s.dnsMu.Lock()
		if s.dnsOrigDst.IsValid() && from == s.dnsSentTo {
			from = s.dnsOrigDst
		}
		s.dnsMu.Unlock()
		_ = s.router.writeRawUDPPacket(buf[:n], from, s.key.src)
	}
}

func (s *udpSession) watchSocksCtrl() {
	defer s.router.wg.Done()
	defer s.router.udpMgr.removeSession(s)
	var tmp [64]byte
	for {
		if _, err := s.socksCtrl.Read(tmp[:]); err != nil {
			return
		}
	}
}

func (s *udpSession) readSocksUDPLoop() {
	defer s.router.wg.Done()
	defer s.router.udpMgr.removeSession(s)
	buf := make([]byte, 65535)
	for {
		n, err := s.socksUDP.Read(buf)
		if err != nil {
			return
		}
		s.touch()
		from, payload, decErr := DecodeSocks5UDPPacket(buf[:n])
		if decErr != nil || !from.IsValid() {
			continue
		}
		_ = s.router.writeRawUDPPacket(payload, from, s.key.src)
	}
}

func (m *udpNATManager) removeSession(s *udpSession) {
	s.close()
	m.mu.Lock()
	if cur, ok := m.sessions[s.key]; ok && cur == s {
		delete(m.sessions, s.key)
	}
	m.mu.Unlock()
}

func (m *udpNATManager) gc() {
	now := time.Now().UnixNano()
	var stale []*udpSession
	m.mu.Lock()
	for k, s := range m.sessions {
		ttl := udpIdleTTL
		if s.isDNSOnly {
			ttl = dnsIdleTTL
		}
		if now-s.lastActive.Load() > ttl.Nanoseconds() {
			stale = append(stale, s)
			delete(m.sessions, k)
		}
	}
	m.mu.Unlock()
	for _, s := range stale {
		s.close()
	}
}

func (m *udpNATManager) closeAll() {
	m.mu.Lock()
	all := make([]*udpSession, 0, len(m.sessions))
	for k, s := range m.sessions {
		all = append(all, s)
		delete(m.sessions, k)
	}
	m.mu.Unlock()
	for _, s := range all {
		s.close()
	}
}

func (r *Router) writeRawUDPPacket(payload []byte, src, dst netip.AddrPort) error {
	srcAddr := src.Addr().Unmap()
	dstAddr := dst.Addr().Unmap()
	isIPv4 := dstAddr.Is4() && srcAddr.Is4()
	udpLen := header.UDPMinimumSize + len(payload)

	ipHdrSize := header.IPv6MinimumSize
	ipProto := header.IPv6ProtocolNumber
	if isIPv4 {
		ipHdrSize = header.IPv4MinimumSize
		ipProto = header.IPv4ProtocolNumber
	}

	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		ReserveHeaderBytes: ipHdrSize + header.UDPMinimumSize,
		Payload:            buffer.MakeWithData(payload),
	})
	defer pkt.DecRef()

	srcTCPIP := tcpip.AddrFromSlice(srcAddr.AsSlice())
	dstTCPIP := tcpip.AddrFromSlice(dstAddr.AsSlice())

	udpHdr := header.UDP(pkt.TransportHeader().Push(header.UDPMinimumSize))
	udpHdr.Encode(&header.UDPFields{
		SrcPort: src.Port(),
		DstPort: dst.Port(),
		Length:  uint16(udpLen),
	})
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, srcTCPIP, dstTCPIP, uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))

	if isIPv4 {
		ipHdr := header.IPv4(pkt.NetworkHeader().Push(header.IPv4MinimumSize))
		ipHdr.Encode(&header.IPv4Fields{
			TotalLength: uint16(header.IPv4MinimumSize + udpLen),
			TTL:         64,
			Protocol:    uint8(header.UDPProtocolNumber),
			SrcAddr:     srcTCPIP,
			DstAddr:     dstTCPIP,
		})
		ipHdr.SetChecksum(^ipHdr.CalculateChecksum())
	} else {
		ipHdr := header.IPv6(pkt.NetworkHeader().Push(header.IPv6MinimumSize))
		ipHdr.Encode(&header.IPv6Fields{
			PayloadLength:     uint16(udpLen),
			TransportProtocol: header.UDPProtocolNumber,
			HopLimit:          64,
			SrcAddr:           srcTCPIP,
			DstAddr:           dstTCPIP,
		})
	}

	if err := r.gstack.WriteRawPacket(1, ipProto, buffer.MakeWithView(pkt.ToView())); err != nil {
		return fmt.Errorf("write raw udp: %v", err)
	}
	return nil
}

func tcpipAddrToNetip(a tcpip.Address) netip.Addr {
	addr, ok := netip.AddrFromSlice(a.AsSlice())
	if !ok {
		return netip.Addr{}
	}
	return addr.Unmap()
}
