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
				if reqPath == "/" || reqPath == "" {
					return 302, map[string]string{"Location": "/app"}, ""
				}
				return 200, nil, padding + `,2,1,200,"DEU",null`
			},
		},
		{
			name: "relative redirect leading to RUS region rejected",
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if reqPath == "/" || reqPath == "" {
					return 302, map[string]string{"Location": "/app"}, ""
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
