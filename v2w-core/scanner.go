package core

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
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
	return s.probeHTTPInternal(ctx, handler, dialer, rawURL, headers, maxBodyBytes, false, nil)
}

// ProbeHTTPWarm выполняет HTTP/HTTPS пробу и при поддержке keep-alive делает
// короткий прогретый второй запрос по тому же соединению, возвращая минимальный TTFB.
func (s *Scanner) ProbeHTTPWarm(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	rawURL string,
	headers map[string]string,
	maxBodyBytes int64,
) (*HTTPProbeResult, error) {
	return s.probeHTTPInternal(ctx, handler, dialer, rawURL, headers, maxBodyBytes, true, nil)
}

// ProbeHTTPStream выполняет HTTP/HTTPS пробу с потоковым чтением тела ответа и ранней
// остановкой, как только предикат shouldStop вернёт true для накопленного окна текста.
func (s *Scanner) ProbeHTTPStream(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	rawURL string,
	headers map[string]string,
	maxBodyBytes int64,
	shouldStop func(window string) bool,
) (*HTTPProbeResult, error) {
	return s.probeHTTPInternal(ctx, handler, dialer, rawURL, headers, maxBodyBytes, false, shouldStop)
}

func applyProbeHeaders(req *http.Request, headers map[string]string, closeConn bool) {
	if closeConn {
		req.Header.Set("Connection", "close")
	}
	if _, ok := headers["User-Agent"]; !ok {
		req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
	}
	if _, ok := headers["Accept"]; !ok {
		req.Header.Set("Accept", "*/*")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
}

func readStreamWithStop(body io.Reader, maxBodyBytes int64, shouldStop func(window string) bool) (string, error) {
	const chunkSize = 8192
	const maxWindow = 32768
	var reader io.Reader = body
	if maxBodyBytes > 0 {
		reader = io.LimitReader(body, maxBodyBytes)
	}
	buf := make([]byte, chunkSize)
	var out strings.Builder
	for {
		n, err := reader.Read(buf)
		if n > 0 {
			out.Write(buf[:n])
			if shouldStop != nil {
				full := out.String()
				window := full
				if len(window) > maxWindow {
					window = window[len(window)-maxWindow:]
				}
				if shouldStop(window) {
					return full, nil
				}
			}
		}
		if err != nil {
			if err == io.EOF {
				return out.String(), nil
			}
			return out.String(), err
		}
	}
}

func (s *Scanner) probeHTTPInternal(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	rawURL string,
	headers map[string]string,
	maxBodyBytes int64,
	warmPing bool,
	shouldStop func(window string) bool,
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

	var dialed atomic.Bool
	tr := &http.Transport{
		DisableKeepAlives:   !warmPing,
		MaxIdleConns:        1,
		MaxIdleConnsPerHost: 1,
		MaxConnsPerHost:     1,
		DialContext: func(_ context.Context, _, _ string) (net.Conn, error) {
			if !dialed.CompareAndSwap(false, true) {
				return nil, fmt.Errorf("single-conn exhausted")
			}
			return clientConn, nil
		},
	}
	if portNum == 443 || strings.EqualFold(parsed.Scheme, "https") {
		tr.DialTLSContext = func(tlsCtx context.Context, _, _ string) (net.Conn, error) {
			if !dialed.CompareAndSwap(false, true) {
				return nil, fmt.Errorf("single-conn exhausted")
			}
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
	defer tr.CloseIdleConnections()

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
		var tlsDoneAt atomic.Int64
		var wroteReqAt atomic.Int64
		var firstByteAt atomic.Int64

		trace := &httptrace.ClientTrace{
			TLSHandshakeDone: func(_ tls.ConnectionState, err error) {
				if err == nil {
					tlsDoneAt.Store(time.Now().UnixNano())
				}
			},
			WroteRequest: func(info httptrace.WroteRequestInfo) {
				if info.Err == nil {
					wroteReqAt.Store(time.Now().UnixNano())
				}
			},
			GotFirstResponseByte: func() {
				firstByteAt.Store(time.Now().UnixNano())
			},
		}

		req, reqErr := http.NewRequestWithContext(httptrace.WithClientTrace(probeCtx, trace), http.MethodGet, rawURL, nil)
		if reqErr != nil {
			httpDone <- httpOutcome{err: fmt.Errorf("req build: %w", reqErr)}
			return
		}
		applyProbeHeaders(req, headers, !warmPing)

		resp, doErr := client.Do(req)
		if doErr != nil {
			httpDone <- httpOutcome{err: fmt.Errorf("http: %w", doErr)}
			return
		}

		var bodyStr string
		if shouldStop != nil {
			var readErr error
			bodyStr, readErr = readStreamWithStop(resp.Body, maxBodyBytes, shouldStop)
			_ = resp.Body.Close()
			if readErr != nil {
				httpDone <- httpOutcome{err: fmt.Errorf("body read: %w", readErr)}
				return
			}
		} else if maxBodyBytes > 0 {
			limited := io.LimitReader(resp.Body, maxBodyBytes)
			b, readErr := io.ReadAll(limited)
			_ = resp.Body.Close()
			if readErr != nil {
				httpDone <- httpOutcome{err: fmt.Errorf("body read: %w", readErr)}
				return
			}
			bodyStr = string(b)
		} else {
			if warmPing && !resp.Close {
				_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
			}
			_ = resp.Body.Close()
		}

		fb := firstByteAt.Load()
		wr := wroteReqAt.Load()
		td := tlsDoneAt.Load()
		var latency time.Duration
		switch {
		case fb > 0 && wr > 0 && fb >= wr:
			latency = time.Duration(fb - wr)
		case fb > 0 && td > 0 && fb >= td:
			latency = time.Duration(fb - td)
		default:
			latency = time.Since(start)
		}

		// Прогретый второй запрос по тому же keep-alive соединению убирает холодный
		// TLS/Reality handshake и slow-start выброс на первом пакете.
		if warmPing && resp.StatusCode >= 200 && resp.StatusCode < 400 && !resp.Close && probeCtx.Err() == nil {
			warmTimeout := 1500 * time.Millisecond
			if deadline, hasDeadline := probeCtx.Deadline(); hasDeadline {
				rem := time.Until(deadline)
				if rem < 250*time.Millisecond {
					warmTimeout = 0
				} else if rem < warmTimeout {
					warmTimeout = rem
				}
			}
			if warmTimeout > 0 {
				warmCtx, warmCancel := context.WithTimeout(probeCtx, warmTimeout)
				var reused atomic.Bool
				var wroteReqAt2 atomic.Int64
				var firstByteAt2 atomic.Int64
				trace2 := &httptrace.ClientTrace{
					GotConn: func(info httptrace.GotConnInfo) {
						reused.Store(info.Reused)
					},
					WroteRequest: func(info httptrace.WroteRequestInfo) {
						if info.Err == nil {
							wroteReqAt2.Store(time.Now().UnixNano())
						}
					},
					GotFirstResponseByte: func() {
						firstByteAt2.Store(time.Now().UnixNano())
					},
				}
				req2, reqErr2 := http.NewRequestWithContext(httptrace.WithClientTrace(warmCtx, trace2), http.MethodGet, rawURL, nil)
				if reqErr2 == nil {
					applyProbeHeaders(req2, headers, false)
					start2 := time.Now()
					resp2, doErr2 := client.Do(req2)
					if doErr2 == nil {
						_, _ = io.Copy(io.Discard, io.LimitReader(resp2.Body, 4096))
						_ = resp2.Body.Close()
						if resp2.StatusCode >= 200 && resp2.StatusCode < 400 && reused.Load() {
							fb2 := firstByteAt2.Load()
							wr2 := wroteReqAt2.Load()
							var warmLat time.Duration
							if fb2 > 0 && wr2 > 0 && fb2 >= wr2 {
								warmLat = time.Duration(fb2 - wr2)
							} else {
								warmLat = time.Since(start2)
							}
							if warmLat > 0 && (latency <= 0 || warmLat < latency) {
								latency = warmLat
							}
						}
					}
				}
				warmCancel()
			}
		}

		if latency < time.Millisecond {
			latency = time.Millisecond
		}

		httpDone <- httpOutcome{
			res: &HTTPProbeResult{
				StatusCode: resp.StatusCode,
				Location:   resp.Header.Get("Location"),
				Body:       bodyStr,
				Latency:    latency,
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

	res, err := s.ProbeHTTPWarm(testCtx, handler, dialer, targetURL, nil, 0)
	if err != nil {
		return ScanResult{Error: err}
	}
	if res.StatusCode != http.StatusNoContent {
		return ScanResult{Error: &HTTPStatusError{StatusCode: res.StatusCode, WantCode: http.StatusNoContent}}
	}
	return ScanResult{Latency: res.Latency}
}
