package core

import (
	"context"
	"errors"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

// Scanner is a high-concurrency stateless scanner for connectivity testing.
type Scanner struct {
}

// NewScanner creates a new Scanner instance.
func NewScanner() *Scanner {
	return &Scanner{}
}

// TestNode performs a stateless connection test using the given protocol and network settings.
// This skips the entire routing/dispatcher infrastructure.
func (s *Scanner) TestNode(ctx context.Context, dest net.Destination, dialer internet.Dialer) error {
	// A basic TCP/UDP handshake test could go here.
	// We delegate dialing to the protocol client (vless, vmess, trojan, etc).
	// E.g., conn, err := dialer.Dial(ctx, dest)
	// conn.Write(...), conn.Read(...)
	return errors.New("not implemented")
}
