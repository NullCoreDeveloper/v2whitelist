package core

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"time"

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

type pipeConn struct {
	r io.Reader
	w io.Writer
}

func (c *pipeConn) Read(b []byte) (int, error)         { return c.r.Read(b) }
func (c *pipeConn) Write(b []byte) (int, error)        { return c.w.Write(b) }
func (c *pipeConn) Close() error                       { return nil }
func (c *pipeConn) LocalAddr() net.Addr                { return &net.TCPAddr{} }
func (c *pipeConn) RemoteAddr() net.Addr               { return &net.TCPAddr{} }
func (c *pipeConn) SetDeadline(t time.Time) error      { return nil }
func (c *pipeConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *pipeConn) SetWriteDeadline(t time.Time) error { return nil }

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

	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	errChan := make(chan error, 1)
	go func() {
		// IMPORTANT: The destination here is the TARGET website we want the proxy to connect to!
		// It MUST NOT be the proxy server's IP, otherwise it creates a loopback!
		targetDest := net.TCPDestination(net.ParseAddress("cp.cloudflare.com"), net.Port(443))
		err := handler.Process(ctx, link, dialer, targetDest)
		if err != nil {
			errChan <- err
		}
	}()

	// Perform a full HTTPS GET request using net/http, exactly like V2RayNG
	conn := &pipeConn{r: downR, w: upW}
	
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return conn, nil
		},
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
			ServerName:         "cp.cloudflare.com",
		},
	}
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}

	errChan2 := make(chan error, 1)
	go func() {
		resp, err := client.Get("https://cp.cloudflare.com/generate_204")
		if err != nil {
			errChan2 <- fmt.Errorf("http get err: %v", err)
			return
		}
		defer resp.Body.Close()
		errChan2 <- nil
	}()

	select {
	case <-ctx.Done():
		return ScanResult{Error: fmt.Errorf("timeout (waiting for HTTP response)")}
	case err := <-errChan: // Error from proxy handler
		if err != nil {
			return ScanResult{Error: fmt.Errorf("proxy error: %v", err)}
		}
	case err := <-errChan2:
		if err != nil {
			return ScanResult{Error: fmt.Errorf("http ping failed: %v", err)}
		}
	}

	return ScanResult{Latency: time.Since(start)}
}
