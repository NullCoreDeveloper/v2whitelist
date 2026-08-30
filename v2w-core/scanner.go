package core

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"time"

	"github.com/kiktor/v2w-core/common/buf"
	xnet "github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
)

// ProxyHandler is a common interface for stripped down Xray proxy handlers.
type ProxyHandler interface {
	Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error
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

// TestNode performs a full End-to-End connectivity test.
// It establishes the proxy tunnel and verifies a real HTTP 204 response from
// connectivitycheck.gstatic.com (port 80, plain HTTP) through the proxy.
//
// Design notes:
//   - net.Pipe() is used instead of two io.Pipe()s to avoid a deadlock
//     that occurs on WebSocket/plain-TCP transports where the proxy writer
//     blocks waiting for the HTTP client reader (and vice versa).
//   - The proxy handler runs in its own goroutine and sends its error to
//     proxyDone. If the proxy fails before the HTTP request completes, we
//     return a "proxy" error immediately instead of waiting for the timeout.
func (s *Scanner) TestNode(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, dest xnet.Destination) ScanResult {
	start := time.Now()

	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	// clientConn — used by the HTTP client.
	// serverConn — used by the proxy handler (xray transport.Link).
	// Data written to one end is readable from the other.
	clientConn, serverConn := net.Pipe()

	// Ensure both ends are closed when the context expires.
	go func() {
		<-ctx.Done()
		clientConn.Close()
		serverConn.Close()
	}()

	link := &transport.Link{
		Reader: buf.NewReader(serverConn),
		Writer: buf.NewWriter(serverConn),
	}

	proxyDone := make(chan error, 1)
	go func() {
		err := handler.Process(ctx, link, dialer, dest)
		proxyDone <- err
		// Close server side so the HTTP client unblocks if proxy finished early.
		serverConn.Close()
		clientConn.Close()
	}()

	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return clientConn, nil
		},
	}

	// Only wrap with TLS if the destination uses port 443.
	if dest.Port == 443 {
		tr.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			tConn := tls.Client(clientConn, &tls.Config{
				InsecureSkipVerify: true,
				ServerName:         dest.Address.String(),
				// Force HTTP/1.1 to avoid ALPN h2 negotiation issues inside the pipe.
				NextProtos: []string{"http/1.1"},
			})
			if err := tConn.HandshakeContext(ctx); err != nil {
				return nil, err
			}
			return tConn, nil
		}
	}

	client := &http.Client{Transport: tr, Timeout: 10 * time.Second}

	httpDone := make(chan error, 1)
	go func() {
		scheme := "http"
		if dest.Port == 443 {
			scheme = "https"
		}
		urlStr := fmt.Sprintf("%s://%s/generate_204", scheme, dest.Address.String())

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, urlStr, nil)
		if err != nil {
			httpDone <- fmt.Errorf("req build: %v", err)
			return
		}

		resp, err := client.Do(req)
		if err != nil {
			httpDone <- fmt.Errorf("http: %v", err)
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusNoContent {
			httpDone <- fmt.Errorf("status %d (want 204)", resp.StatusCode)
			return
		}
		httpDone <- nil
	}()

	for {
		select {
		case <-ctx.Done():
			return ScanResult{Error: fmt.Errorf("timeout")}

		case proxyErr := <-proxyDone:
			if proxyErr != nil {
				return ScanResult{Error: fmt.Errorf("proxy: %v", proxyErr)}
			}
			// Proxy finished normally (e.g. after HTTP request completed).
			// Drain httpDone or wait for context — don't return success prematurely.
			proxyDone = nil // disable this case to avoid infinite loop

		case err := <-httpDone:
			if err != nil {
				return ScanResult{Error: err}
			}
			return ScanResult{Latency: time.Since(start)}
		}
	}
}
