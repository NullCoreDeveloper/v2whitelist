package v2wscanner

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
	stdnet "net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	core "github.com/kiktor/v2w-core"
	"github.com/kiktor/v2w-core/common/buf"
	xnet "github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
)

func mustTestTLSConfig(t *testing.T) *tls.Config {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		DNSNames:     []string{"www.cloudflare.com", "example.org", "cp.cloudflare.com"},
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

type linkConnAdapter struct {
	link *transport.Link
	buf  []byte
}

func (c *linkConnAdapter) Read(p []byte) (int, error) {
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

func (c *linkConnAdapter) Write(p []byte) (int, error) {
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

func (c *linkConnAdapter) Close() error {
	if cl, ok := c.link.Writer.(io.Closer); ok {
		return cl.Close()
	}
	return nil
}
func (c *linkConnAdapter) LocalAddr() stdnet.Addr              { return &stdnet.TCPAddr{} }
func (c *linkConnAdapter) RemoteAddr() stdnet.Addr             { return &stdnet.TCPAddr{} }
func (c *linkConnAdapter) SetDeadline(time.Time) error      { return nil }
func (c *linkConnAdapter) SetReadDeadline(time.Time) error  { return nil }
func (c *linkConnAdapter) SetWriteDeadline(time.Time) error { return nil }

type tlsMockHTTPHandler struct {
	responder func(host, reqPath string) (status int, headers map[string]string, body string)
	tlsCfg    *tls.Config
	calls     int32
}

func (m *tlsMockHTTPHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	atomic.AddInt32(&m.calls, 1)

	var conn stdnet.Conn = &linkConnAdapter{link: link}
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

type chainExitMockHandler struct {
	calls int32
}

func (c *chainExitMockHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
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

	local := &linkConnAdapter{link: link}
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

func TestInspectNodeServicesMode(t *testing.T) {
	scanner := core.NewScanner()
	tlsCfg := mustTestTLSConfig(t)
	handler := &tlsMockHTTPHandler{
		tlsCfg: tlsCfg,
		responder: func(host, reqPath string) (int, map[string]string, string) {
			if strings.Contains(host, "cloudflare.com") && reqPath == "/cdn-cgi/trace" {
				return 200, nil, "ip=198.51.100.42\nloc=de\n"
			}
			if reqPath == "/ok" {
				return 200, nil, "ok"
			}
			if reqPath == "/fail" {
				return 502, nil, "bad gateway"
			}
			return 200, nil, "ok"
		},
	}

	cand := parsedCandidate{
		link:   "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443#DE",
		stream: &internet.MemoryStreamConfig{SecurityType: "none"},
		dest:   xnet.TCPDestination(xnet.DomainAddress("1.2.3.4"), 443),
	}
	services := []core.ServiceCheckSpec{
		{ID: "svc_ok", Name: "ServiceOK", URL: "http://example.org/ok", Enabled: true, IsCustom: true},
		{ID: "svc_fail", Name: "ServiceFail", URL: "http://example.org/fail", Enabled: true, IsCustom: true},
	}
	opts := DefaultScanOptions()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	res := InspectNode(ctx, scanner, cand, handler, &mockDialer{}, opts, nil, parseTargetDest(opts.DestURL), services)
	if !res.Alive {
		t.Fatalf("expected Alive=true, got error: %s", res.Error)
	}
	if res.ExitIP != "198.51.100.42" || res.CountryCode != "DE" {
		t.Fatalf("unexpected exit info: ip=%q cc=%q", res.ExitIP, res.CountryCode)
	}
	if res.EndpointPassed != nil {
		t.Fatalf("expected EndpointPassed=nil when no endpoint set, got %v", *res.EndpointPassed)
	}
	if len(res.Services) != 2 {
		t.Fatalf("expected 2 service results, got %d", len(res.Services))
	}
	if !res.Services[0].Success || res.Services[1].Success {
		t.Fatalf("unexpected services statuses: %+v", res.Services)
	}
}

func TestInspectNodeEndpointMutuallyExclusive(t *testing.T) {
	scanner := core.NewScanner()
	tlsCfg := mustTestTLSConfig(t)
	cand := parsedCandidate{
		link:   "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443#NL",
		stream: &internet.MemoryStreamConfig{SecurityType: "none"},
		dest:   xnet.TCPDestination(xnet.DomainAddress("1.2.3.4"), 443),
	}
	services := []core.ServiceCheckSpec{
		{ID: "svc_1", Name: "Svc1", URL: "http://example.org/ok", Enabled: true, IsCustom: true},
	}
	opts := DefaultScanOptions()
	opts.EndpointURL = "vless://22222222-2222-2222-2222-222222222222@5.6.7.8:443#Exit"

	t.Run("endpoint passes and services skipped", func(t *testing.T) {
		outerHandler := &tlsMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if strings.Contains(host, "cloudflare.com") && reqPath == "/cdn-cgi/trace" {
					return 200, nil, "ip=203.0.113.9\nloc=nl\n"
				}
				if reqPath == "/generate_204" {
					return 204, nil, ""
				}
				t.Fatalf("unexpected service request to %s%s when EndpointURL is set", host, reqPath)
				return 500, nil, ""
			},
		}
		exitCompiled := &exitNodeCompiled{
			handler: &chainExitMockHandler{},
			dialer:  &mockDialer{},
		}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		res := InspectNode(ctx, scanner, cand, outerHandler, &mockDialer{}, opts, exitCompiled, parseTargetDest(opts.DestURL), services)
		if !res.Alive {
			t.Fatalf("expected Alive=true, got error: %s", res.Error)
		}
		if res.Services != nil {
			t.Fatalf("expected Services=nil when EndpointURL is set, got %+v", res.Services)
		}
		if res.EndpointPassed == nil || !*res.EndpointPassed {
			t.Fatalf("expected EndpointPassed=true, got %v", res.EndpointPassed)
		}
		if res.ExitIP != "203.0.113.9" || res.CountryCode != "NL" {
			t.Fatalf("unexpected exit info: ip=%q cc=%q", res.ExitIP, res.CountryCode)
		}
	})

	t.Run("endpoint fails but outer node still alive with ExitIP", func(t *testing.T) {
		outerHandler := &tlsMockHTTPHandler{
			tlsCfg: tlsCfg,
			responder: func(host, reqPath string) (int, map[string]string, string) {
				if strings.Contains(host, "cloudflare.com") && reqPath == "/cdn-cgi/trace" {
					return 200, nil, "ip=203.0.113.10\nloc=fi\n"
				}
				if reqPath == "/generate_204" {
					return 502, nil, "exit down"
				}
				t.Fatalf("unexpected service request to %s%s", host, reqPath)
				return 500, nil, ""
			},
		}
		exitCompiled := &exitNodeCompiled{
			handler: &chainExitMockHandler{},
			dialer:  &mockDialer{},
		}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		res := InspectNode(ctx, scanner, cand, outerHandler, &mockDialer{}, opts, exitCompiled, parseTargetDest(opts.DestURL), services)
		if !res.Alive {
			t.Fatalf("expected outer node Alive=true even if endpoint failed, got error: %s", res.Error)
		}
		if res.Services != nil {
			t.Fatalf("expected Services=nil, got %+v", res.Services)
		}
		if res.EndpointPassed == nil || *res.EndpointPassed {
			t.Fatalf("expected EndpointPassed=false, got %v", res.EndpointPassed)
		}
		if res.ExitIP != "203.0.113.10" || res.CountryCode != "FI" {
			t.Fatalf("unexpected exit info: ip=%q cc=%q", res.ExitIP, res.CountryCode)
		}
	})
}

func TestInspectNodeFallback204WhenCFTraceFails(t *testing.T) {
	scanner := core.NewScanner()
	tlsCfg := mustTestTLSConfig(t)
	handler := &tlsMockHTTPHandler{
		tlsCfg: tlsCfg,
		responder: func(host, reqPath string) (int, map[string]string, string) {
			if reqPath == "/cdn-cgi/trace" {
				return 403, nil, "blocked"
			}
			if reqPath == "/generate_204" {
				return 204, nil, ""
			}
			return 404, nil, ""
		},
	}

	cand := parsedCandidate{
		link:   "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443#FB",
		stream: &internet.MemoryStreamConfig{SecurityType: "none"},
		dest:   xnet.TCPDestination(xnet.DomainAddress("1.2.3.4"), 443),
	}
	opts := DefaultScanOptions()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	res := InspectNode(ctx, scanner, cand, handler, &mockDialer{}, opts, nil, parseTargetDest(opts.DestURL), nil)
	if !res.Alive {
		t.Fatalf("expected Alive=true via 204 fallback, got error: %s", res.Error)
	}
	if res.ExitIP != "" || res.CountryCode != "" {
		t.Fatalf("expected empty exit info on 204 fallback, got ip=%q cc=%q", res.ExitIP, res.CountryCode)
	}
}

type failCFTraceHandler struct {
	tlsCfg *tls.Config
}

func (f *failCFTraceHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	if strings.Contains(target.Address.String(), "cloudflare.com") && target.Port == 443 {
		if cl, ok := link.Writer.(io.Closer); ok {
			_ = cl.Close()
		}
		return fmt.Errorf("simulated cloudflare trace network failure")
	}
	mock := &tlsMockHTTPHandler{
		tlsCfg: f.tlsCfg,
		responder: func(host, reqPath string) (int, map[string]string, string) {
			if reqPath == "/generate_204" {
				return 204, nil, ""
			}
			return 404, nil, ""
		},
	}
	return mock.Process(ctx, link, dialer, target)
}

func TestInspectNodeFallback204WhenCFTraceNetworkError(t *testing.T) {
	scanner := core.NewScanner()
	tlsCfg := mustTestTLSConfig(t)
	handler := &failCFTraceHandler{tlsCfg: tlsCfg}

	cand := parsedCandidate{
		link:   "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443#FBNet",
		stream: &internet.MemoryStreamConfig{SecurityType: "none"},
		dest:   xnet.TCPDestination(xnet.DomainAddress("1.2.3.4"), 443),
	}
	opts := DefaultScanOptions()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	res := InspectNode(ctx, scanner, cand, handler, &mockDialer{}, opts, nil, parseTargetDest(opts.DestURL), nil)
	if !res.Alive {
		t.Fatalf("expected Alive=true via 204 fallback on network error, got error: %s", res.Error)
	}
}

func TestRunFullScanPipelineReportsInvalidAndComplete(t *testing.T) {
	links := []string{
		"invalid://bad-node",
		"vless://11111111-1111-1111-1111-111111111111@127.0.0.1:1?security=none#ClosedPort",
	}
	opts := DefaultScanOptions()
	opts.TCPConcurrency = 2
	opts.VerifyConcurrency = 2
	opts.TimeoutSec = 2

	var mu sync.Mutex
	var results []FullScanNodeResult
	var gotSuccess, gotFailed int64
	completed := false

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	RunFullScanPipeline(
		ctx,
		links,
		opts,
		func(res FullScanNodeResult) {
			mu.Lock()
			results = append(results, res)
			mu.Unlock()
		},
		func(totalSuccess int64, totalFailed int64) {
			mu.Lock()
			gotSuccess = totalSuccess
			gotFailed = totalFailed
			completed = true
			mu.Unlock()
		},
	)

	if !completed {
		t.Fatal("expected onComplete to be called")
	}
	if gotSuccess != 0 || gotFailed != 2 {
		t.Fatalf("expected success=0 failed=2, got success=%d failed=%d", gotSuccess, gotFailed)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	for _, r := range results {
		if r.Alive || r.Error == "" {
			t.Fatalf("expected dead node with error, got %+v", r)
		}
	}
}
