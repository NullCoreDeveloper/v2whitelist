package core

import (
	"context"
	"fmt"
	"io"
	"time"
	"bytes"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet"
)

// ProxyHandler is a common interface for stripped down Xray proxy handlers.
type ProxyHandler interface {
	Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target net.Destination) error
}

// ScanResult contains the result of a single node scan.
type ScanResult struct {
	Latency time.Duration
	Error   error
}

// Scanner is a high-concurrency stateless scanner for connectivity testing.
type Scanner struct{}

// NewScanner creates a new Scanner instance.
func NewScanner() *Scanner {
	return &Scanner{}
}

// TestNode performs a stateless connection test using the given protocol handler.
// It bypasses the entire routing/dispatcher infrastructure.
func (s *Scanner) TestNode(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, dest net.Destination) ScanResult {
	start := time.Now()

	upR, upW := io.Pipe()
	downR, downW := io.Pipe()

	link := &transport.Link{
		Reader: buf.NewReader(upR),
		Writer: buf.NewWriter(downW),
	}

	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	errChan := make(chan error, 1)
	go func() {
		errChan <- handler.Process(ctx, link, dialer, dest)
	}()

	// Send an HTTP payload to test connectivity.
	payload := []byte("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nUser-Agent: curl/7.81.0\r\nConnection: close\r\n\r\n")
	go func() {
		upW.Write(payload)
	}()

	// Try to read response with a timeout
	type readResult struct {
		n   int
		err error
	}
	readChan := make(chan readResult, 1)
	resp := make([]byte, 2048)
	
	go func() {
		n, err := downR.Read(resp)
		readChan <- readResult{n, err}
	}()

	var n int
	var err error

	select {
	case <-ctx.Done():
		return ScanResult{Error: fmt.Errorf("timeout")}
	case r := <-readChan:
		n = r.n
		err = r.err
	}

	// If we successfully read something, we consider it a success.
	if err != nil && err != io.EOF {
		return ScanResult{Error: err}
	}
	if n == 0 {
		return ScanResult{Error: fmt.Errorf("empty response")}
	}

	if !bytes.Contains(resp[:n], []byte("204 No Content")) {
		return ScanResult{Error: fmt.Errorf("invalid response: %q", string(resp[:n]))}
	}

	return ScanResult{Latency: time.Since(start)}
}
