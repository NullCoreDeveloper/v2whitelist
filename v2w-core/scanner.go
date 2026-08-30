package core

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	utls "github.com/refraction-networking/utls"

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

func (c *pipeConn) Read(b []byte) (int, error) {
	n, err := c.r.Read(b)
	return n, err
}
func (c *pipeConn) Write(b []byte) (int, error) {
	n, err := c.w.Write(b)
	return n, err
}
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
		// We use the dest provided by the caller (e.g. cp.cloudflare.com:80)
		err := handler.Process(ctx, link, dialer, dest)
		if err != nil {
			errChan <- err
		}
	}()

	conn := &pipeConn{r: downR, w: upW}
	
	// Fast HTTP ping over the proxy stream.
	// If dest is port 443, we wrap with TLS. If 80, plain HTTP.
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return conn, nil
		},
	}
	
	if dest.Port == 443 {
		tr.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			uConn := utls.UClient(conn, &utls.Config{
				InsecureSkipVerify: true,
				ServerName:         dest.Address.String(),
			}, utls.HelloChrome_120)
			if err := uConn.Handshake(); err != nil {
				return nil, err
			}
			return uConn, nil
		}
	}

	client := &http.Client{Transport: tr, Timeout: 5 * time.Second}

	errChan2 := make(chan error, 1)
	go func() {
		scheme := "http"
		if dest.Port == 443 {
			scheme = "https"
		}
		url := fmt.Sprintf("%s://%s/", scheme, dest.Address.String())
		
		resp, err := client.Get(url)
		if err != nil {
			errChan2 <- fmt.Errorf("http get err: %v", err)
			return
		}
		defer resp.Body.Close()
		
		if resp.StatusCode != http.StatusNoContent {
			errChan2 <- fmt.Errorf("unexpected status (not 204): %v", resp.StatusCode)
			return
		}
		errChan2 <- nil
	}()

	select {
	case <-ctx.Done():
		return ScanResult{Error: fmt.Errorf("timeout (waiting for HTTP response)")}
	case err := <-errChan: // Error from proxy handler
		if err != nil {
			errStr := err.Error()
			if strings.Contains(errStr, "error code 0") {
				// The connection was closed normally by the proxy after our successful request
				// but wait, if errChan hits BEFORE errChan2, it means the proxy closed the connection prematurely!
				// We should NOT return success here unless the HTTP request also succeeded!
				return ScanResult{Error: fmt.Errorf("proxy closed connection prematurely")}
			}
			return ScanResult{Error: fmt.Errorf("proxy error: %v", err)}
		}
	case err := <-errChan2:
		if err != nil {
			// Strict check: any error (timeout, EOF, bad TLS) is a failure!
			return ScanResult{Error: fmt.Errorf("http ping failed: %v", err)}
		}
	}

	return ScanResult{Latency: time.Since(start)}
}
