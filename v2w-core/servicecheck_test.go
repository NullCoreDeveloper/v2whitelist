package core

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/kiktor/v2w-core/common/buf"
	xnet "github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/stat"
)

func mustSelfSignedTLSConfig(t *testing.T) *tls.Config {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		DNSNames:     []string{"gemini.google.com", "example.org"},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{{
			Certificate: [][]byte{der},
			PrivateKey:  priv,
		}},
	}
}

type linkConn struct {
	link *transport.Link
	buf  []byte
}

func (c *linkConn) Read(p []byte) (int, error) {
	for len(c.buf) == 0 {
		mb, err := c.link.Reader.ReadMultiBuffer()
		if err != nil {
			return 0, err
		}
		for _, b := range mb {
			c.buf = append(c.buf, b.Bytes()...)
			b.Release()
		}
	}
	n := copy(p, c.buf)
	c.buf = c.buf[n:]
	return n, nil
}

func (c *linkConn) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		chunk := p
		if len(chunk) > 2048 {
			chunk = chunk[:2048]
		}
		p = p[len(chunk):]
		b := buf.New()
		n, _ := b.Write(chunk)
		if err := c.link.Writer.WriteMultiBuffer(buf.MultiBuffer{b}); err != nil {
			return total, err
		}
		total += n
	}
	return total, nil
}

func (c *linkConn) Close() error {
	if cl, ok := c.link.Writer.(io.Closer); ok {
		return cl.Close()
	}
	return nil
}
func (c *linkConn) LocalAddr() net.Addr              { return &net.TCPAddr{} }
func (c *linkConn) RemoteAddr() net.Addr             { return &net.TCPAddr{} }
func (c *linkConn) SetDeadline(time.Time) error      { return nil }
func (c *linkConn) SetReadDeadline(time.Time) error  { return nil }
func (c *linkConn) SetWriteDeadline(time.Time) error { return nil }

type testMockHTTPHandler struct {
	responder func(host, reqPath string) (status int, headers map[string]string, body string)
	tlsCfg    *tls.Config
	calls     int32
}

func (m *testMockHTTPHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	atomic.AddInt32(&m.calls, 1)

	var conn net.Conn = &linkConn{link: link}
	if m.tlsCfg != nil && target.Port == 443 {
		tConn := tls.Server(conn, m.tlsCfg)
		if err := tConn.HandshakeContext(ctx); err != nil {
			_ = conn.Close()
			return err
		}
		conn = tConn
	}
	defer conn.Close()

	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	status, hdrs, body := m.responder(target.Address.String(), req.URL.Path)
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("HTTP/1.1 %d OK\r\nContent-Length: %d\r\nConnection: close\r\n", status, len(body)))
	for k, v := range hdrs {
		sb.WriteString(fmt.Sprintf("%s: %s\r\n", k, v))
	}
	sb.WriteString("\r\n")
	sb.WriteString(body)

	_, err = conn.Write([]byte(sb.String()))
	return err
}

type testMockDialer struct{}

func (d *testMockDialer) Dial(ctx context.Context, destination xnet.Destination) (stat.Connection, error) {
	return nil, fmt.Errorf("unexpected raw dial in mock")
}
func (d *testMockDialer) DestIpAddress() xnet.IP { return nil }

func TestCheckGeminiLiteLargeBodyAndRedirects(t *testing.T) {
	scanner := NewScanner()
	tlsCfg := mustSelfSignedTLSConfig(t)
	padding := strings.Repeat("x", 800*1024)

	cases := []struct {
		name      string
		responder func(host, reqPath string) (int, map[string]string, string)
		wantErr   string
	}{
		{
			name: "blocked RUS region past 64KB offset",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, padding + `,2,1,200,"RUS",null`
			},
			wantErr: "region RUS blocked",
		},
		{
			name: "allowed NLD region past 64KB offset",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, padding + `,2,1,200,"NLD",null`
			},
		},
		{
			name: "external redirect to support.google.com rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 302, map[string]string{"Location": "https://support.google.com/gemini/answer/13575153"}, ""
			},
			wantErr: "redirect to support.google.com",
		},
		{
			name: "relative redirect inside gemini.google.com followed",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if reqPath == "/app" {
					return 302, map[string]string{"Location": "/app/welcome"}, ""
				}
				return 200, nil, padding + `,2,1,200,"DEU",null`
			},
		},
		{
			name: "relative redirect leading to RUS region rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if reqPath == "/app" {
					return 302, map[string]string{"Location": "/app/welcome"}, ""
				}
				return 200, nil, padding + `,2,1,200,"RUS",null`
			},
			wantErr: "region RUS blocked",
		},
		{
			name: "sorry redirect rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 302, map[string]string{"Location": "https://www.google.com/sorry/index"}, ""
			},
			wantErr: "google bot-protection",
		},
		{
			name: "faq redirect rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 302, map[string]string{"Location": "/faq"}, ""
			},
			wantErr: "redirect to gemini faq",
		},
		{
			name: "missing region marker rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, "<html><body>Gemini landing shell</body></html>"
			},
			wantErr: "gemini region marker missing",
		},
		{
			name: "empty region marker rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, padding + `,2,1,200,"",null`
			},
			wantErr: "gemini region marker missing",
		},
		{
			name: "non-200 inner marker rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, padding + `,2,1,403,"NLD",null`
			},
			wantErr: "gemini marker status 403",
		},
		{
			name: "AFG region blocked",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, padding + `,2,1,200,"AFG",null`
			},
			wantErr: "region AFG blocked",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()

			handler := &testMockHTTPHandler{responder: tc.responder, tlsCfg: tlsCfg}
			_, err := scanner.checkGeminiLite(ctx, handler, &testMockDialer{})
			if tc.wantErr == "" && err != nil {
				t.Fatalf("expected success, got error: %v", err)
			}
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("expected error containing %q, got %v", tc.wantErr, err)
				}
			}
		})
	}
}

func TestCheckServicesParallelThroughChainDialerCtx(t *testing.T) {
	scanner := NewScanner()
	var outerCalled int32

	outerHandler := &testMockHTTPHandler{
		responder: func(host, reqPath string) (int, map[string]string, string) {
			atomic.AddInt32(&outerCalled, 1)
			return 200, nil, "ok"
		},
	}
	chainedDialerHandler := &chainBridgeHandler{}

	parentCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	chainCtx := NewChainDialerCtx(parentCtx, outerHandler, &testMockDialer{})
	services := []ServiceCheckSpec{
		{ID: "custom_1", Name: "Example", URL: "http://example.org/ok", Enabled: true, IsCustom: true},
	}

	lat, err := scanner.CheckServicesParallel(chainCtx, chainedDialerHandler, &testMockDialer{}, services, 2*time.Second)
	if err != nil {
		t.Fatalf("expected chained service check to succeed, got %v", err)
	}
	if lat < 0 {
		t.Fatalf("expected non-negative latency, got %v", lat)
	}
	if atomic.LoadInt32(&outerCalled) != 1 {
		t.Fatalf("expected outerHandler to be invoked once via chainCtx, got %d", outerCalled)
	}
	if atomic.LoadInt32(&chainedDialerHandler.calls) != 1 {
		t.Fatalf("expected exit handler to be invoked once, got %d", chainedDialerHandler.calls)
	}
}

type chainBridgeHandler struct {
	calls int32
}

func (c *chainBridgeHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	atomic.AddInt32(&c.calls, 1)
	ctxDialer := internet.DialerFromContext(ctx)
	if ctxDialer == nil {
		return fmt.Errorf("missing chain dialer")
	}
	conn, err := ctxDialer(ctx, target)
	if err != nil {
		return err
	}
	defer conn.Close()

	local := &linkConn{link: link}
	errCh := make(chan error, 2)
	go func() {
		_, cpErr := io.Copy(conn, local)
		errCh <- cpErr
	}()
	go func() {
		_, cpErr := io.Copy(local, conn)
		_ = local.Close()
		errCh <- cpErr
	}()
	<-errCh
	return nil
}

func TestProbeExitIP(t *testing.T) {
	scanner := NewScanner()
	tlsCfg := mustSelfSignedTLSConfig(t)

	t.Run("parses ip and loc", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()

		handler := &testMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, "fl=12f34\nip=185.10.20.30\nts=1700000000\nloc=de\n"
			},
		}
		info, err := scanner.ProbeExitIP(ctx, handler, &testMockDialer{})
		if err != nil {
			t.Fatalf("expected no error, got %v", err)
		}
		if info.IP != "185.10.20.30" || info.CountryCode != "DE" {
			t.Fatalf("unexpected ExitIPInfo: %+v", info)
		}
	})

	t.Run("non-200 status returns HTTPStatusError", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()

		handler := &testMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 403, nil, "forbidden"
			},
		}
		_, err := scanner.ProbeExitIP(ctx, handler, &testMockDialer{})
		if err == nil || !strings.Contains(err.Error(), "status 403") {
			t.Fatalf("expected status 403 error, got %v", err)
		}
	})

	t.Run("empty trace response returns error", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()

		handler := &testMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				return 200, nil, "fl=12f34\nts=1700000000\n"
			},
		}
		_, err := scanner.ProbeExitIP(ctx, handler, &testMockDialer{})
		if err == nil || !strings.Contains(err.Error(), "empty cloudflare trace response") {
			t.Fatalf("expected empty trace error, got %v", err)
		}
	})
}

func TestCheckServicesAllNoFailFastAndKnownLoc(t *testing.T) {
	scanner := NewScanner()
	tlsCfg := mustSelfSignedTLSConfig(t)

	services := []ServiceCheckSpec{
		{ID: "claude", Name: "Claude", URL: "https://claude.ai", Enabled: true, IsCustom: false},
		{ID: "chatgpt", Name: "ChatGPT", URL: "https://chatgpt.com", Enabled: true, IsCustom: false},
		{ID: "custom_fail", Name: "BrokenSvc", URL: "https://example.org/fail", Enabled: true, IsCustom: true},
		{ID: "custom_ok", Name: "OkSvc", URL: "https://example.org/ok", Enabled: true, IsCustom: true},
		{ID: "disabled", Name: "DisabledSvc", URL: "https://example.org/skip", Enabled: false, IsCustom: true},
	}

	t.Run("no fail-fast and reuses known US loc without trace request", func(t *testing.T) {
		var traceCalls int32
		handler := &testMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if strings.Contains(host, "cloudflare.com") {
					atomic.AddInt32(&traceCalls, 1)
				}
				if strings.Contains(host, "anthropic.com") {
					return 401, nil, `{"error":{"type":"authentication_error"}}`
				}
				if strings.Contains(host, "openai.com") {
					return 200, nil, "ok"
				}
				if reqPath == "/fail" {
					return 502, nil, "bad gateway"
				}
				return 200, nil, "ok"
			},
		}

		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		results := scanner.CheckServicesAll(ctx, handler, &testMockDialer{}, services, 2*time.Second, "US")
		if len(results) != 4 {
			t.Fatalf("expected 4 enabled service results, got %d", len(results))
		}
		if atomic.LoadInt32(&traceCalls) != 0 {
			t.Fatalf("expected 0 cloudflare trace calls when knownCFLoc=US, got %d", traceCalls)
		}
		if results[0].ID != "claude" || !results[0].Success {
			t.Fatalf("expected claude success, got %+v", results[0])
		}
		if results[1].ID != "chatgpt" || !results[1].Success {
			t.Fatalf("expected chatgpt success, got %+v", results[1])
		}
		if results[2].ID != "custom_fail" || results[2].Success || results[2].LatencyMs != -1 {
			t.Fatalf("expected custom_fail failure, got %+v", results[2])
		}
		if results[3].ID != "custom_ok" || !results[3].Success {
			t.Fatalf("expected custom_ok success despite previous failure, got %+v", results[3])
		}
	})

	t.Run("known blocked RU loc rejects Claude and ChatGPT immediately", func(t *testing.T) {
		var externalCalls int32
		handler := &testMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if strings.Contains(host, "anthropic.com") || strings.Contains(host, "claude.ai") || strings.Contains(host, "openai.com") || strings.Contains(host, "cloudflare.com") {
					atomic.AddInt32(&externalCalls, 1)
				}
				return 200, nil, "ok"
			},
		}

		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		results := scanner.CheckServicesAll(ctx, handler, &testMockDialer{}, services[:2], 2*time.Second, "RU")
		if len(results) != 2 {
			t.Fatalf("expected 2 results, got %d", len(results))
		}
		if results[0].Success || !strings.Contains(results[0].Error, "region RU blocked") {
			t.Fatalf("expected claude RU block, got %+v", results[0])
		}
		if results[1].Success || !strings.Contains(results[1].Error, "region RU blocked") {
			t.Fatalf("expected chatgpt RU block, got %+v", results[1])
		}
		if atomic.LoadInt32(&externalCalls) != 0 {
			t.Fatalf("expected 0 requests when knownCFLoc=RU, got %d", externalCalls)
		}
	})
}

type testKeepAliveHandler struct {
	calls        int32
	requests     int32
	firstDelay   time.Duration
	secondDelay  time.Duration
	closeOnFirst bool
}

func (m *testKeepAliveHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	atomic.AddInt32(&m.calls, 1)
	conn := &linkConn{link: link}
	defer conn.Close()

	reader := bufio.NewReader(conn)
	for {
		req, err := http.ReadRequest(reader)
		if err != nil {
			return nil
		}
		_ = req.Body.Close()
		n := atomic.AddInt32(&m.requests, 1)
		if n == 1 && m.firstDelay > 0 {
			time.Sleep(m.firstDelay)
		} else if n >= 2 && m.secondDelay > 0 {
			time.Sleep(m.secondDelay)
		}
		body := "ok"
		connHeader := "keep-alive"
		if m.closeOnFirst {
			connHeader = "close"
		}
		resp := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: %s\r\n\r\n%s", len(body), connHeader, body)
		if _, wErr := conn.Write([]byte(resp)); wErr != nil {
			return wErr
		}
		if m.closeOnFirst {
			return nil
		}
	}
}

func TestProbeHTTPWarmKeepAliveAndFallback(t *testing.T) {
	scanner := NewScanner()

	t.Run("reuses keep-alive connection and picks faster warm RTT", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()

		handler := &testKeepAliveHandler{
			firstDelay:  80 * time.Millisecond,
			secondDelay: 5 * time.Millisecond,
		}
		res, err := scanner.ProbeHTTPWarm(ctx, handler, &testMockDialer{}, "http://example.org/generate_204", nil, 0)
		if err != nil {
			t.Fatalf("expected no error, got %v", err)
		}
		if atomic.LoadInt32(&handler.calls) != 1 {
			t.Fatalf("expected single underlying connection (calls=1), got %d", handler.calls)
		}
		if atomic.LoadInt32(&handler.requests) != 2 {
			t.Fatalf("expected 2 requests on keep-alive connection, got %d", handler.requests)
		}
		if res.Latency >= 50*time.Millisecond {
			t.Fatalf("expected warm RTT < 50ms, got %v", res.Latency)
		}
	})

	t.Run("falls back to first latency when server sends Connection close", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()

		handler := &testKeepAliveHandler{
			firstDelay:   25 * time.Millisecond,
			closeOnFirst: true,
		}
		res, err := scanner.ProbeHTTPWarm(ctx, handler, &testMockDialer{}, "http://example.org/generate_204", nil, 0)
		if err != nil {
			t.Fatalf("expected no error, got %v", err)
		}
		if atomic.LoadInt32(&handler.requests) != 1 {
			t.Fatalf("expected 1 request when Connection: close, got %d", handler.requests)
		}
		if res.Latency < 15*time.Millisecond {
			t.Fatalf("expected first request latency >= 15ms, got %v", res.Latency)
		}
	})
}

type testGeminiCookieAndEarlyStopHandler struct {
	tlsCfg      *tls.Config
	gotCookie   string
	tailWritten int64
}

func (m *testGeminiCookieAndEarlyStopHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	var conn net.Conn = &linkConn{link: link}
	if m.tlsCfg != nil && target.Port == 443 {
		tConn := tls.Server(conn, m.tlsCfg)
		if err := tConn.HandshakeContext(ctx); err != nil {
			_ = conn.Close()
			return err
		}
		conn = tConn
	}
	defer conn.Close()

	req, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	m.gotCookie = req.Header.Get("Cookie")
	marker := `,2,1,200,"NLD",null`
	tailSize := 1_500_000
	totalLen := len(marker) + tailSize
	header := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", totalLen, marker)
	if _, err := conn.Write([]byte(header)); err != nil {
		return err
	}
	chunk := make([]byte, 32*1024)
	for written := 0; written < tailSize; {
		n, wErr := conn.Write(chunk)
		atomic.AddInt64(&m.tailWritten, int64(n))
		if wErr != nil {
			return nil
		}
		written += n
	}
	return nil
}

func TestCheckGeminiLiteSendsSOCSCookieAndStopsEarly(t *testing.T) {
	scanner := NewScanner()
	tlsCfg := mustSelfSignedTLSConfig(t)
	handler := &testGeminiCookieAndEarlyStopHandler{tlsCfg: tlsCfg}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	if _, err := scanner.checkGeminiLite(ctx, handler, &testMockDialer{}); err != nil {
		t.Fatalf("expected gemini check to succeed, got %v", err)
	}
	if !strings.Contains(handler.gotCookie, "SOCS=") {
		t.Fatalf("expected SOCS cookie in request, got %q", handler.gotCookie)
	}
	if written := atomic.LoadInt64(&handler.tailWritten); written >= 1_400_000 {
		t.Fatalf("expected early stream stop before reading full 1.5MB tail, wrote %d bytes", written)
	}
}
