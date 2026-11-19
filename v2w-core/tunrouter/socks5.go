package tunrouter

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"time"
)

const (
	socksVersion5       = 0x05
	socksAuthNone       = 0x00
	socksAuthUserPass   = 0x02
	socksAuthNoAccept   = 0xFF
	socksCmdConnect     = 0x01
	socksCmdUDPAssoc    = 0x03
	socksAddrIPv4       = 0x01
	socksAddrDomain     = 0x03
	socksAddrIPv6       = 0x04
	socksRepSucceeded   = 0x00
)

// Socks5Client выполняет TCP CONNECT и UDP ASSOCIATE через локальный SOCKS5-прокси Xray.
type Socks5Client struct {
	ProxyAddr string
	Username  string
	Password  string
}

func (c *Socks5Client) dialAndHandshake(ctx context.Context) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", c.ProxyAddr)
	if err != nil {
		return nil, err
	}

	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	} else {
		_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	}

	useAuth := c.Username != "" || c.Password != ""
	var methods []byte
	if useAuth {
		methods = []byte{socksVersion5, 2, socksAuthNone, socksAuthUserPass}
	} else {
		methods = []byte{socksVersion5, 1, socksAuthNone}
	}
	if _, err := conn.Write(methods); err != nil {
		conn.Close()
		return nil, fmt.Errorf("socks5 greeting write: %w", err)
	}

	var reply [2]byte
	if _, err := io.ReadFull(conn, reply[:]); err != nil {
		conn.Close()
		return nil, fmt.Errorf("socks5 greeting read: %w", err)
	}
	if reply[0] != socksVersion5 {
		conn.Close()
		return nil, fmt.Errorf("unexpected socks version: %d", reply[0])
	}

	switch reply[1] {
	case socksAuthNone:
		// ok
	case socksAuthUserPass:
		if !useAuth {
			conn.Close()
			return nil, fmt.Errorf("socks5 server requested auth but no credentials configured")
		}
		if len(c.Username) > 255 || len(c.Password) > 255 {
			conn.Close()
			return nil, fmt.Errorf("socks5 credentials too long")
		}
		authReq := make([]byte, 0, 3+len(c.Username)+len(c.Password))
		authReq = append(authReq, 0x01, byte(len(c.Username)))
		authReq = append(authReq, c.Username...)
		authReq = append(authReq, byte(len(c.Password)))
		authReq = append(authReq, c.Password...)
		if _, err := conn.Write(authReq); err != nil {
			conn.Close()
			return nil, fmt.Errorf("socks5 auth write: %w", err)
		}
		var authReply [2]byte
		if _, err := io.ReadFull(conn, authReply[:]); err != nil {
			conn.Close()
			return nil, fmt.Errorf("socks5 auth read: %w", err)
		}
		if authReply[1] != 0x00 {
			conn.Close()
			return nil, fmt.Errorf("socks5 auth failed: status %d", authReply[1])
		}
	case socksAuthNoAccept:
		conn.Close()
		return nil, fmt.Errorf("socks5 no acceptable auth method")
	default:
		conn.Close()
		return nil, fmt.Errorf("socks5 unsupported auth method: %d", reply[1])
	}

	return conn, nil
}

// DialTCP открывает TCP-туннель через SOCKS5 CONNECT до addrPort.
func (c *Socks5Client) DialTCP(ctx context.Context, addrPort netip.AddrPort) (net.Conn, error) {
	conn, err := c.dialAndHandshake(ctx)
	if err != nil {
		return nil, err
	}

	req := buildSocksAddrHeader([]byte{socksVersion5, socksCmdConnect, 0x00}, addrPort)
	if _, err := conn.Write(req); err != nil {
		conn.Close()
		return nil, fmt.Errorf("socks5 connect write: %w", err)
	}

	if _, err := readSocksReply(conn); err != nil {
		conn.Close()
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// DialUDP открывает UDP ASSOCIATE сессию через SOCKS5.
// Возвращает control TCP-соединение (нужно держать открытым, пока жива UDP-сессия) и UDPConn до relay.
func (c *Socks5Client) DialUDP(ctx context.Context) (net.Conn, *net.UDPConn, error) {
	ctrlConn, err := c.dialAndHandshake(ctx)
	if err != nil {
		return nil, nil, err
	}

	zeroAddr := netip.AddrPortFrom(netip.IPv4Unspecified(), 0)
	req := buildSocksAddrHeader([]byte{socksVersion5, socksCmdUDPAssoc, 0x00}, zeroAddr)
	if _, err := ctrlConn.Write(req); err != nil {
		ctrlConn.Close()
		return nil, nil, fmt.Errorf("socks5 udp associate write: %w", err)
	}

	bndAddr, err := readSocksReply(ctrlConn)
	if err != nil {
		ctrlConn.Close()
		return nil, nil, err
	}
	_ = ctrlConn.SetDeadline(time.Time{})

	relayHost, _, _ := net.SplitHostPort(c.ProxyAddr)
	relayIP := net.ParseIP(relayHost)
	if bndIP := net.ParseIP(bndAddr.Addr().String()); bndIP != nil && !bndIP.IsUnspecified() {
		relayIP = bndIP
	}
	if relayIP == nil {
		relayIP = net.IPv4(127, 0, 0, 1)
	}
	relayUDPAddr := &net.UDPAddr{
		IP:   relayIP,
		Port: int(bndAddr.Port()),
	}

	udpConn, err := net.DialUDP("udp", nil, relayUDPAddr)
	if err != nil {
		ctrlConn.Close()
		return nil, nil, fmt.Errorf("socks5 dial udp relay %s: %w", relayUDPAddr.String(), err)
	}
	return ctrlConn, udpConn, nil
}

func buildSocksAddrHeader(prefix []byte, addrPort netip.AddrPort) []byte {
	addr := addrPort.Addr().Unmap()
	out := make([]byte, 0, len(prefix)+1+16+2)
	out = append(out, prefix...)
	if addr.Is4() {
		out = append(out, socksAddrIPv4)
		b := addr.As4()
		out = append(out, b[:]...)
	} else {
		out = append(out, socksAddrIPv6)
		b := addr.As16()
		out = append(out, b[:]...)
	}
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], addrPort.Port())
	out = append(out, portBuf[:]...)
	return out
}

func readSocksReply(r io.Reader) (netip.AddrPort, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return netip.AddrPort{}, fmt.Errorf("socks5 reply header: %w", err)
	}
	if hdr[0] != socksVersion5 {
		return netip.AddrPort{}, fmt.Errorf("socks5 invalid reply version: %d", hdr[0])
	}
	if hdr[1] != socksRepSucceeded {
		return netip.AddrPort{}, fmt.Errorf("socks5 request failed with code %d", hdr[1])
	}

	var addr netip.Addr
	switch hdr[3] {
	case socksAddrIPv4:
		var ip [4]byte
		if _, err := io.ReadFull(r, ip[:]); err != nil {
			return netip.AddrPort{}, err
		}
		addr = netip.AddrFrom4(ip)
	case socksAddrIPv6:
		var ip [16]byte
		if _, err := io.ReadFull(r, ip[:]); err != nil {
			return netip.AddrPort{}, err
		}
		addr = netip.AddrFrom16(ip)
	case socksAddrDomain:
		var lenBuf [1]byte
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			return netip.AddrPort{}, err
		}
		dom := make([]byte, int(lenBuf[0]))
		if _, err := io.ReadFull(r, dom); err != nil {
			return netip.AddrPort{}, err
		}
		if parsed, parseErr := netip.ParseAddr(string(dom)); parseErr == nil {
			addr = parsed
		}
	default:
		return netip.AddrPort{}, fmt.Errorf("socks5 unknown address type %d", hdr[3])
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(r, portBuf[:]); err != nil {
		return netip.AddrPort{}, err
	}
	port := binary.BigEndian.Uint16(portBuf[:])
	return netip.AddrPortFrom(addr, port), nil
}

// EncodeSocks5UDPPacket упаковывает UDP payload с SOCKS5 UDP-заголовком для отправки на relay.
func EncodeSocks5UDPPacket(dst netip.AddrPort, payload []byte) []byte {
	hdr := buildSocksAddrHeader([]byte{0x00, 0x00, 0x00}, dst)
	pkt := make([]byte, len(hdr)+len(payload))
	copy(pkt, hdr)
	copy(pkt[len(hdr):], payload)
	return pkt
}

// DecodeSocks5UDPPacket разбирает UDP-пакет от SOCKS5 relay и возвращает адрес источника и данные.
func DecodeSocks5UDPPacket(pkt []byte) (netip.AddrPort, []byte, error) {
	if len(pkt) < 4 {
		return netip.AddrPort{}, nil, fmt.Errorf("socks5 udp packet too short")
	}
	if pkt[2] != 0x00 {
		return netip.AddrPort{}, nil, fmt.Errorf("socks5 udp fragmentation not supported")
	}
	offset := 4
	var addr netip.Addr
	switch pkt[3] {
	case socksAddrIPv4:
		if len(pkt) < offset+4+2 {
			return netip.AddrPort{}, nil, fmt.Errorf("short ipv4 socks5 udp packet")
		}
		addr = netip.AddrFrom4([4]byte(pkt[offset : offset+4]))
		offset += 4
	case socksAddrIPv6:
		if len(pkt) < offset+16+2 {
			return netip.AddrPort{}, nil, fmt.Errorf("short ipv6 socks5 udp packet")
		}
		addr = netip.AddrFrom16([16]byte(pkt[offset : offset+16]))
		offset += 16
	case socksAddrDomain:
		if len(pkt) < offset+1 {
			return netip.AddrPort{}, nil, fmt.Errorf("short domain socks5 udp packet")
		}
		dLen := int(pkt[offset])
		offset++
		if len(pkt) < offset+dLen+2 {
			return netip.AddrPort{}, nil, fmt.Errorf("short domain body in socks5 udp packet")
		}
		dom := string(pkt[offset : offset+dLen])
		offset += dLen
		if parsed, err := netip.ParseAddr(dom); err == nil {
			addr = parsed
		} else if ips, err := net.LookupIP(dom); err == nil && len(ips) > 0 {
			if ap, ok := netip.AddrFromSlice(ips[0]); ok {
				addr = ap.Unmap()
			}
		}
	default:
		return netip.AddrPort{}, nil, fmt.Errorf("unsupported socks5 udp atyp %d", pkt[3])
	}
	port := binary.BigEndian.Uint16(pkt[offset : offset+2])
	offset += 2
	return netip.AddrPortFrom(addr, port), pkt[offset:], nil
}

func formatAddrPort(addr netip.AddrPort) string {
	return net.JoinHostPort(addr.Addr().Unmap().String(), strconv.Itoa(int(addr.Port())))
}
