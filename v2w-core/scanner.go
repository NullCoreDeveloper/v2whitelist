package core

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
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

// HTTPProbeResult хранит облегчённый результат HTTP/HTTPS пробы через ProxyHandler.
type HTTPProbeResult struct {
	StatusCode int
	Location   string
	Body       string
	Latency    time.Duration
}

// Scanner is a high-concurrency stateless scanner for connectivity testing.
type Scanner struct{}

// NewScanner creates a new Scanner instance.
func NewScanner() *Scanner {
	return &Scanner{}
}

// NewChainDialerCtx возвращает контекст с DialSystem, туннелирующим подключения к следующему хопу
// через указанный внешний ProxyHandler/Dialer (используется для E2E-проверки конечного узла в памяти).
func NewChainDialerCtx(parentCtx context.Context, outerHandler ProxyHandler, outerDialer internet.Dialer) context.Context {
	fragMode := internet.FragmentModeFromContext(parentCtx)
	return internet.ContextWithDialer(parentCtx, func(dialCtx context.Context, dest xnet.Destination) (net.Conn, error) {
		if outerHandler == nil {
			return nil, fmt.Errorf("nil outer handler")
		}
		clientConn, serverConn := net.Pipe()
		// Внутренний dial внешнего узла не должен повторно попадать в ContextDialer (исключаем рекурсию).
		outerCtx := internet.ContextWithDialer(dialCtx, nil)
		if fragMode != internet.FragmentNone {
			outerCtx = internet.ContextWithFragment(outerCtx, fragMode)
		}
		link := &transport.Link{
			Reader: buf.NewReader(serverConn),
			Writer: buf.NewWriter(serverConn),
		}
		go func() {
			_ = outerHandler.Process(outerCtx, link, outerDialer, dest)
			_ = serverConn.Close()
		}()
		return clientConn, nil
	})
}

// ProbeHTTP выполняет одиночный лёгкий HTTP/HTTPS запрос поверх net.Pipe и ProxyHandler
// без создания локальных портов или процессов Xray.
func (s *Scanner) ProbeHTTP(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	rawURL string,
	headers map[string]string,
	maxBodyBytes int64,
) (*HTTPProbeResult, error) {
	if handler == nil {
		return nil, fmt.Errorf("nil proxy handler")
	}
	parsed, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || parsed.Hostname() == "" {
		return nil, fmt.Errorf("invalid url: %s", rawURL)
	}
	host := parsed.Hostname()
	portNum := 80
	if strings.EqualFold(parsed.Scheme, "https") {
		portNum = 443
	}
	if p := parsed.Port(); p != "" {
		if pv, convErr := strconv.Atoi(p); convErr == nil && pv > 0 && pv <= 65535 {
			portNum = pv
		}
	}

	dest := xnet.TCPDestination(xnet.DomainAddress(host), xnet.Port(portNum))
	start := time.Now()

	probeCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	clientConn, serverConn := net.Pipe()
	defer func() {
		_ = clientConn.Close()
		_ = serverConn.Close()
	}()

	go func() {
		<-probeCtx.Done()
		_ = clientConn.Close()
		_ = serverConn.Close()
	}()

	link := &transport.Link{
		Reader: buf.NewReader(serverConn),
		Writer: buf.NewWriter(serverConn),
	}

	proxyDone := make(chan error, 1)
	go func() {
		err := handler.Process(probeCtx, link, dialer, dest)
		proxyDone <- err
		_ = serverConn.Close()
		_ = clientConn.Close()
	}()

	tr := &http.Transport{
		DisableKeepAlives: true,
		DialContext: func(_ context.Context, _, _ string) (net.Conn, error) {
			return clientConn, nil
		},
	}
	if portNum == 443 || strings.EqualFold(parsed.Scheme, "https") {
		tr.DialTLSContext = func(tlsCtx context.Context, _, _ string) (net.Conn, error) {
			tConn := tls.Client(clientConn, &tls.Config{
				InsecureSkipVerify: true,
				ServerName:         host,
				NextProtos:         []string{"http/1.1"},
			})
			if hsErr := tConn.HandshakeContext(tlsCtx); hsErr != nil {
				return nil, hsErr
			}
			return tConn, nil
		}
	}

	client := &http.Client{
		Transport: tr,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	type httpOutcome struct {
		res *HTTPProbeResult
		err error
	}
	httpDone := make(chan httpOutcome, 1)
	go func() {
		req, reqErr := http.NewRequestWithContext(probeCtx, http.MethodGet, rawURL, nil)
		if reqErr != nil {
			httpDone <- httpOutcome{err: fmt.Errorf("req build: %w", reqErr)}
			return
		}
		req.Header.Set("Connection", "close")
		if _, ok := headers["User-Agent"]; !ok {
			req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
		}
		if _, ok := headers["Accept"]; !ok {
			req.Header.Set("Accept", "*/*")
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}

		resp, doErr := client.Do(req)
		if doErr != nil {
			httpDone <- httpOutcome{err: fmt.Errorf("http: %w", doErr)}
			return
		}
		defer resp.Body.Close()

		var bodyStr string
		if maxBodyBytes > 0 {
			limited := io.LimitReader(resp.Body, maxBodyBytes)
			b, _ := io.ReadAll(limited)
			bodyStr = string(b)
		}
		httpDone <- httpOutcome{
			res: &HTTPProbeResult{
				StatusCode: resp.StatusCode,
				Location:   resp.Header.Get("Location"),
				Body:       bodyStr,
				Latency:    time.Since(start),
			},
		}
	}()

	for {
		select {
		case <-probeCtx.Done():
			return nil, fmt.Errorf("timeout")
		case proxyErr := <-proxyDone:
			if proxyErr != nil {
				return nil, fmt.Errorf("proxy: %w", proxyErr)
			}
			proxyDone = nil
		case out := <-httpDone:
			if out.err != nil {
				return nil, out.err
			}
			return out.res, nil
		}
	}
}

// HTTPStatusError означает, что HTTP-ответ через прокси уже получен, но статус не совпал с ожидаемым.
type HTTPStatusError struct {
	StatusCode int
	WantCode   int
}

func (e *HTTPStatusError) Error() string {
	return fmt.Sprintf("status %d (want %d)", e.StatusCode, e.WantCode)
}

// TestNode performs a full End-to-End connectivity test.
// It establishes the proxy tunnel and verifies a real HTTP 204 response.
func (s *Scanner) TestNode(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, dest xnet.Destination) ScanResult {
	scheme := "http"
	if dest.Port == 443 {
		scheme = "https"
	}
	targetURL := fmt.Sprintf("%s://%s/generate_204", scheme, dest.Address.String())
	if dest.Port != 80 && dest.Port != 443 {
		targetURL = fmt.Sprintf("%s://%s:%d/generate_204", scheme, dest.Address.String(), dest.Port)
	}

	testCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	res, err := s.ProbeHTTP(testCtx, handler, dialer, targetURL, nil, 0)
	if err != nil {
		return ScanResult{Error: err}
	}
	if res.StatusCode != http.StatusNoContent {
		return ScanResult{Error: &HTTPStatusError{StatusCode: res.StatusCode, WantCode: http.StatusNoContent}}
	}
	return ScanResult{Latency: res.Latency}
}
