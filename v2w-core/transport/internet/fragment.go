package internet

import (
	"context"
	"encoding/binary"
	stdnet "net"
	"sync"
	"time"

	"github.com/kiktor/v2w-core/common/net"
)

type FragmentMode int

const (
	FragmentNone FragmentMode = iota
	FragmentTCP               // TCP segment splitting (split at ~100 bytes, interval 12ms)
	FragmentRecord            // TLS record layer splitting (split into 2 TLS records at ~64 bytes, interval 12ms)
)

type fragmentContextKey struct{}

func ContextWithFragment(ctx context.Context, mode FragmentMode) context.Context {
	return context.WithValue(ctx, fragmentContextKey{}, mode)
}

func FragmentModeFromContext(ctx context.Context) FragmentMode {
	if mode, ok := ctx.Value(fragmentContextKey{}).(FragmentMode); ok {
		return mode
	}
	return FragmentNone
}

type FragmentConn struct {
	stdnet.Conn
	mode           FragmentMode
	firstWriteMu   sync.Mutex
	firstWriteDone bool
	splitSize      int
	interval       time.Duration
}

func NewFragmentConn(conn net.Conn, mode FragmentMode) net.Conn {
	return &FragmentConn{
		Conn:      conn,
		mode:      mode,
		splitSize: 100,
		interval:  12 * time.Millisecond,
	}
}

func isClientHello(b []byte) bool {
	// TLS Record: 0x16 (Handshake), Major 0x03, Minor [0x00-0x04], length (2 bytes), HandshakeType 0x01 (ClientHello)
	return len(b) >= 9 && b[0] == 0x16 && b[1] == 0x03 && b[5] == 0x01
}

func (fc *FragmentConn) Write(b []byte) (int, error) {
	fc.firstWriteMu.Lock()
	if fc.firstWriteDone || fc.mode == FragmentNone || !isClientHello(b) {
		fc.firstWriteMu.Unlock()
		return fc.Conn.Write(b)
	}
	fc.firstWriteDone = true
	fc.firstWriteMu.Unlock()

	// Disable Nagle to ensure TCP packets are sent immediately without waiting
	if tcpConn, ok := fc.Conn.(*stdnet.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}

	switch fc.mode {
	case FragmentTCP:
		// Split ClientHello into two TCP segments: b[:split] and b[split:]
		split := fc.splitSize
		if split <= 0 || split >= len(b) {
			split = 100
		}
		if split >= len(b) {
			return fc.Conn.Write(b)
		}

		n1, err := fc.Conn.Write(b[:split])
		if err != nil {
			return n1, err
		}

		time.Sleep(fc.interval)

		n2, err := fc.Conn.Write(b[split:])
		return n1 + n2, err

	case FragmentRecord:
		// Split into two RFC-compliant TLS records
		if len(b) <= 9 {
			return fc.Conn.Write(b)
		}
		recLen := int(binary.BigEndian.Uint16(b[3:5]))
		handshakePayload := b[5:]
		if len(handshakePayload) < recLen || len(handshakePayload) < 80 {
			// Malformed or too short to split, fallback to TCP split
			split := 80
			if split >= len(b) {
				return fc.Conn.Write(b)
			}
			n1, err := fc.Conn.Write(b[:split])
			if err != nil {
				return n1, err
			}
			time.Sleep(fc.interval)
			n2, err := fc.Conn.Write(b[split:])
			return n1 + n2, err
		}

		// Split point in handshake payload (64 bytes: header + random + start of cipher suites, NO SNI)
		split := 64

		// Record 1: 5 bytes header + split bytes payload
		rec1 := make([]byte, 5+split)
		rec1[0] = 0x16
		rec1[1] = b[1]
		rec1[2] = b[2]
		binary.BigEndian.PutUint16(rec1[3:5], uint16(split))
		copy(rec1[5:], handshakePayload[:split])

		// Record 2: 5 bytes header + remaining bytes payload
		remLen := len(handshakePayload) - split
		rec2 := make([]byte, 5+remLen)
		rec2[0] = 0x16
		rec2[1] = b[1]
		rec2[2] = b[2]
		binary.BigEndian.PutUint16(rec2[3:5], uint16(remLen))
		copy(rec2[5:], handshakePayload[split:])

		n1, err := fc.Conn.Write(rec1)
		if err != nil {
			return n1, err
		}

		time.Sleep(fc.interval)

		n2, err := fc.Conn.Write(rec2)
		if err != nil {
			return n1 + n2, err
		}
		return len(b), nil

	default:
		return fc.Conn.Write(b)
	}
}

