package v2wscanner

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	core "github.com/kiktor/v2w-core"
	"github.com/kiktor/v2w-core/common/buf"
	xnet "github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/stat"
)

func TestDefaultScanOptionsAndPriority(t *testing.T) {
	def := DefaultScanOptions()
	if def.TCPConcurrency != 50 {
		t.Fatalf("expected default TCPConcurrency=50, got %d", def.TCPConcurrency)
	}
	if def.VerifyConcurrency != 35 {
		t.Fatalf("expected default VerifyConcurrency=35, got %d", def.VerifyConcurrency)
	}
	if mode := def.EffectiveCheckMode(); mode != "204" {
		t.Fatalf("expected default mode '204', got %q", mode)
	}

	// Endpoint E2E активен + сервисы активны -> приоритет у "endpoint_services" (сервисы проверяются через цепочку).
	withBoth := def
	withBoth.EndpointE2E = true
	withBoth.EndpointURL = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443"
	withBoth.ServiceCheck = true
	withBoth.Services = []core.ServiceCheckSpec{
		{ID: "custom_1", Name: "MySite", URL: "http://example.com/", Enabled: true, IsCustom: true},
	}
	if mode := withBoth.EffectiveCheckMode(); mode != "endpoint_services" {
		t.Fatalf("expected mode 'endpoint_services' when endpoint and services are enabled, got %q", mode)
	}

	// Endpoint задан + сервисы активны, даже при EndpointE2E=false -> всё равно "endpoint_services".
	endpointServicesNoE2E := withBoth
	endpointServicesNoE2E.EndpointE2E = false
	if mode := endpointServicesNoE2E.EffectiveCheckMode(); mode != "endpoint_services" {
		t.Fatalf("expected mode 'endpoint_services' when endpoint is set and services are enabled, got %q", mode)
	}

	// Endpoint E2E активен без сервисов -> режим "endpoint".
	endpointOnly := withBoth
	endpointOnly.ServiceCheck = false
	if mode := endpointOnly.EffectiveCheckMode(); mode != "endpoint" {
		t.Fatalf("expected mode 'endpoint' when services disabled, got %q", mode)
	}

	// Endpoint выключен, сервисы включены -> режим "services".
	servicesOnly := withBoth
	servicesOnly.EndpointURL = ""
	servicesOnly.EndpointE2E = false
	if mode := servicesOnly.EffectiveCheckMode(); mode != "services" {
		t.Fatalf("expected mode 'services', got %q", mode)
	}

	// Сервисы включены, но ни один элемент не активен -> откат на "204".
	noActiveServices := servicesOnly
	noActiveServices.Services[0].Enabled = false
	if mode := noActiveServices.EffectiveCheckMode(); mode != "204" {
		t.Fatalf("expected fallback to '204' when all services disabled, got %q", mode)
	}
}

func TestParseConfigsWithOptionsHeader(t *testing.T) {
	raw := `#v2w-opts:{"tcp_concurrency":50,"verify_concurrency":35,"endpoint_url":"vless://uuid@1.1.1.1:443","endpoint_e2e":false,"service_check":true,"services":[{"id":"discord","name":"Discord","url":"http://discord.com/api/v10/gateway","enabled":true,"isCustom":false}]}
vless://11111111-1111-1111-1111-111111111111@8.8.8.8:443?security=none#Node1
# comment should be ignored
vmess://eyJhZGQiOiI4LjguOC44IiwicG9ydCI6NDQzLCJpZCI6IjExMTExMTExLTExMTEtMTExMS0xMTExLTExMTExMTExMTExMSJ9`

	opts, links, hasHeader := ParseConfigsWithOptions(raw, 20)
	if !hasHeader {
		t.Fatal("expected hasHeader=true")
	}
	if opts.TCPConcurrency != 50 || opts.VerifyConcurrency != 35 {
		t.Fatalf("unexpected concurrencies: tcp=%d verify=%d", opts.TCPConcurrency, opts.VerifyConcurrency)
	}
	if opts.EffectiveCheckMode() != "endpoint_services" {
		t.Fatalf("expected mode 'endpoint_services', got %q", opts.EffectiveCheckMode())
	}
	if len(links) != 2 {
		t.Fatalf("expected 2 supported links, got %d", len(links))
	}

	// Без заголовка берется fallbackConcurrency и дефолт tcp=50.
	plainOpts, plainLinks, hasPlainHeader := ParseConfigsWithOptions("vless://abc@1.2.3.4:443#N", 35)
	if hasPlainHeader {
		t.Fatal("expected hasPlainHeader=false")
	}
	if plainOpts.TCPConcurrency != 50 || plainOpts.VerifyConcurrency != 35 {
		t.Fatalf("unexpected plain concurrencies: tcp=%d verify=%d", plainOpts.TCPConcurrency, plainOpts.VerifyConcurrency)
	}
	if len(plainLinks) != 1 {
		t.Fatalf("expected 1 link, got %d", len(plainLinks))
	}
}

type mockHTTPHandler struct {
	responder func(host, reqPath string) (status int, headers map[string]string, body string)
	calls     int32
}

func (m *mockHTTPHandler) Process(ctx context.Context, link *transport.Link, dialer internet.Dialer, target xnet.Destination) error {
	atomic.AddInt32(&m.calls, 1)

	// Связываем transport.Link с HTTP-сервером в памяти через goroutine.
	pr, pw := io.Pipe()
	go func() {
		defer pw.Close()
		for {
			mb, err := link.Reader.ReadMultiBuffer()
			if err != nil {
				return
			}
			for _, b := range mb {
				_, _ = pw.Write(b.Bytes())
				b.Release()
			}
		}
	}()

	req, err := http.ReadRequest(bufio.NewReader(pr))
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

	_ = pr.Close()
	payload := []byte(sb.String())
	b := buf.New()
	_, _ = b.Write(payload)
	if err := link.Writer.WriteMultiBuffer(buf.MultiBuffer{b}); err != nil {
		return err
	}
	if c, ok := link.Writer.(io.Closer); ok {
		_ = c.Close()
	}
	return nil
}

type mockDialer struct{}

func (d *mockDialer) Dial(ctx context.Context, destination xnet.Destination) (stat.Connection, error) {
	return nil, fmt.Errorf("unexpected raw dial in mock")
}
func (d *mockDialer) DestIpAddress() xnet.IP { return nil }

func TestCheckServicesParallelAndCustomService(t *testing.T) {
	scanner := core.NewScanner()

	okHandler := &mockHTTPHandler{
		responder: func(host, reqPath string) (int, map[string]string, string) {
			if strings.Contains(host, "cloudflare.com") {
				return 200, nil, "fl=1\nloc=US\n"
			}
			return 200, nil, "ok"
		},
	}

	services := []core.ServiceCheckSpec{
		{ID: "custom_cf", Name: "WebHealth", URL: "http://cloudflare.com/health", Enabled: true, IsCustom: true},
		{ID: "custom_1", Name: "CustomAPI", URL: "http://example.org/health", Enabled: true, IsCustom: true},
		{ID: "disabled_1", Name: "DisabledSvc", URL: "http://disabled.org/health", Enabled: false, IsCustom: true},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	lat, err := scanner.CheckServicesParallel(ctx, okHandler, &mockDialer{}, services, 2*time.Second)
	if err != nil {
		t.Fatalf("expected all services ok, got error: %v", err)
	}
	if lat < 0 {
		t.Fatalf("expected non-negative latency, got %v", lat)
	}
	if atomic.LoadInt32(&okHandler.calls) != 2 {
		t.Fatalf("expected 2 parallel service calls, got %d", okHandler.calls)
	}

	// Один из сервисов возвращает 502 -> fail-fast должен вернуть ошибку с именем сервиса.
	failHandler := &mockHTTPHandler{
		responder: func(host, reqPath string) (int, map[string]string, string) {
			if strings.Contains(host, "example.org") {
				return 502, nil, "bad gateway"
			}
			return 200, nil, "fl=1\nloc=DE\n"
		},
	}
	_, failErr := scanner.CheckServicesParallel(ctx, failHandler, &mockDialer{}, services, 2*time.Second)
	if failErr == nil || !strings.Contains(failErr.Error(), "CustomAPI") {
		t.Fatalf("expected fail-fast error mentioning CustomAPI, got %v", failErr)
	}
}

func TestNewChainDialerCtxAvoidsRecursion(t *testing.T) {
	var outerCalls int32
	outerHandler := &mockHTTPHandler{
		responder: func(host, reqPath string) (int, map[string]string, string) {
			atomic.AddInt32(&outerCalls, 1)
			return 204, nil, ""
		},
	}

	parentCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	chainedCtx := core.NewChainDialerCtx(parentCtx, outerHandler, &mockDialer{})
	ctxDialer := internet.DialerFromContext(chainedCtx)
	if ctxDialer == nil {
		t.Fatal("expected ContextDialer to be installed")
	}

	// Эмулируем внутренний dial через DialSystem -> должен дернуться ContextDialer -> outerHandler.Process.
	conn, err := internet.DialSystem(chainedCtx, xnet.TCPDestination(xnet.DomainAddress("cp.cloudflare.com"), 80), nil)
	if err != nil {
		t.Fatalf("DialSystem through chain failed: %v", err)
	}
	defer conn.Close()

	_, _ = conn.Write([]byte("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\n\r\n"))
	respBytes, _ := io.ReadAll(conn)
	if !strings.Contains(string(respBytes), "204") {
		t.Fatalf("expected 204 response from chained outer handler, got %q", string(respBytes))
	}
	if atomic.LoadInt32(&outerCalls) != 1 {
		t.Fatalf("expected exactly 1 call to outerHandler, got %d", outerCalls)
	}
}

func TestShouldRetryWithFragmentSkipsHTTPStatusErrors(t *testing.T) {
	tlsCand := parsedCandidate{
		stream: &internet.MemoryStreamConfig{SecurityType: "tls"},
		dest:   xnet.TCPDestination(xnet.DomainAddress("example.com"), 443),
	}
	if shouldRetryWithFragment(&core.HTTPStatusError{StatusCode: 301, WantCode: 204}, tlsCand) {
		t.Fatal("expected HTTPStatusError not to trigger TLS fragmentation retries")
	}
	if !shouldRetryWithFragment(fmt.Errorf("tls handshake timeout"), tlsCand) {
		t.Fatal("expected TLS handshake error on TCP+TLS node to allow fragmentation retry")
	}
	udpCand := parsedCandidate{
		stream: &internet.MemoryStreamConfig{SecurityType: "tls"},
		dest:   xnet.UDPDestination(xnet.DomainAddress("example.com"), 443),
	}
	if shouldRetryWithFragment(fmt.Errorf("timeout"), udpCand) {
		t.Fatal("expected UDP candidate not to trigger TCP/TLS fragmentation retry")
	}
}
