package v2wscanner

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	stdnet "net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	core "github.com/kiktor/v2w-core"
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/session"
	"github.com/kiktor/v2w-core/proxy/hysteria"
	"github.com/kiktor/v2w-core/proxy/shadowsocks"
	"github.com/kiktor/v2w-core/proxy/trojan"
	vless_outbound "github.com/kiktor/v2w-core/proxy/vless/outbound"
	vmess_outbound "github.com/kiktor/v2w-core/proxy/vmess/outbound"
	"github.com/kiktor/v2w-core/transport/internet"
)

const OptionsPrefix = "#v2w-opts:"

// ScanOptions задаёт параметры двухступенчатого сканирования в v2w-core.
type ScanOptions struct {
	TCPConcurrency    int                     `json:"tcp_concurrency"`
	VerifyConcurrency int                     `json:"verify_concurrency"`
	TimeoutSec        int                     `json:"timeout_sec"`
	FragTimeoutSec    int                     `json:"frag_timeout_sec"`
	EndpointURL       string                  `json:"endpoint_url"`
	EndpointE2E       bool                    `json:"endpoint_e2e"`
	ServiceCheck      bool                    `json:"service_check"`
	Services          []core.ServiceCheckSpec `json:"services"`
	DestURL           string                  `json:"dest_url"`
	FirstOnly         bool                    `json:"first_only"`
	StreamMode        bool                    `json:"stream_mode"`
}

// DefaultScanOptions возвращает стандартные настройки: 100 потоков TCP pre-check, 35 потоков проверки.
func DefaultScanOptions() ScanOptions {
	return ScanOptions{
		TCPConcurrency:    100,
		VerifyConcurrency: 35,
		TimeoutSec:        6,
		FragTimeoutSec:    4,
		DestURL:           "http://cp.cloudflare.com/generate_204",
	}
}

func (o *ScanOptions) normalize() {
	if o.TCPConcurrency <= 0 {
		o.TCPConcurrency = 100
	}
	if o.VerifyConcurrency <= 0 {
		o.VerifyConcurrency = 35
	}
	if o.TimeoutSec <= 0 {
		o.TimeoutSec = 6
	}
	if o.FragTimeoutSec <= 0 {
		o.FragTimeoutSec = 4
	}
	if strings.TrimSpace(o.DestURL) == "" {
		o.DestURL = "http://cp.cloudflare.com/generate_204"
	}
}

// EnabledServices возвращает только включённые сервисы с непустым URL.
func (o ScanOptions) EnabledServices() []core.ServiceCheckSpec {
	var out []core.ServiceCheckSpec
	for _, svc := range o.Services {
		if svc.Enabled && strings.TrimSpace(svc.URL) != "" {
			out = append(out, svc)
		}
	}
	return out
}

// EffectiveCheckMode определяет режим проверки по приоритету настроек:
// - "endpoint": конечный узел задан и включена E2E-проверка 204 без сервисов (сервисы при конечном узле отключаются)
// - "services": проверка сервисов напрямую через внешний узел (только когда конечный узел не задан)
// - "204": стандартная проверка HTTP 204
func (o ScanOptions) EffectiveCheckMode() string {
	hasEndpoint := strings.TrimSpace(o.EndpointURL) != ""
	if hasEndpoint {
		if o.EndpointE2E {
			return "endpoint"
		}
		return "204"
	}
	hasServices := o.ServiceCheck && len(o.EnabledServices()) > 0
	if hasServices {
		return "services"
	}
	return "204"
}

func serviceCheckTimeout(timeoutSec int) time.Duration {
	perServiceTimeout := time.Duration(timeoutSec)*time.Second - 500*time.Millisecond
	if perServiceTimeout < 3500*time.Millisecond {
		perServiceTimeout = 3500 * time.Millisecond
	}
	return perServiceTimeout
}

// ParseConfigsWithOptions извлекает опциональный JSON-заголовок #v2w-opts:{...} и список поддерживаемых ссылок.
func ParseConfigsWithOptions(rawConfigs string, fallbackConcurrency int) (ScanOptions, []string, bool) {
	opts := DefaultScanOptions()
	if fallbackConcurrency > 0 {
		opts.VerifyConcurrency = fallbackConcurrency
	}
	hasHeader := false

	normalized := strings.ReplaceAll(rawConfigs, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var links []string

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, OptionsPrefix) && !hasHeader {
			jsonPart := strings.TrimSpace(strings.TrimPrefix(line, OptionsPrefix))
			if jsonPart != "" {
				var parsed ScanOptions
				if err := json.Unmarshal([]byte(jsonPart), &parsed); err == nil {
					if parsed.TCPConcurrency > 0 {
						opts.TCPConcurrency = parsed.TCPConcurrency
					}
					if parsed.VerifyConcurrency > 0 {
						opts.VerifyConcurrency = parsed.VerifyConcurrency
					}
					if parsed.TimeoutSec > 0 {
						opts.TimeoutSec = parsed.TimeoutSec
					}
					if parsed.FragTimeoutSec > 0 {
						opts.FragTimeoutSec = parsed.FragTimeoutSec
					}
					if strings.TrimSpace(parsed.DestURL) != "" {
						opts.DestURL = parsed.DestURL
					}
					opts.EndpointURL = parsed.EndpointURL
					opts.EndpointE2E = parsed.EndpointE2E
					opts.ServiceCheck = parsed.ServiceCheck
					opts.Services = parsed.Services
					opts.FirstOnly = parsed.FirstOnly
					opts.StreamMode = parsed.StreamMode
					hasHeader = true
				}
			}
			continue
		}
		if IsSupportedNodeLink(line) {
			links = append(links, line)
		}
	}

	opts.normalize()
	return opts, links, hasHeader
}

// IsSupportedNodeLink проверяет, поддерживается ли схема конфигурации движком v2w-core.
func IsSupportedNodeLink(line string) bool {
	return strings.HasPrefix(line, "vless://") ||
		strings.HasPrefix(line, "vmess://") ||
		strings.HasPrefix(line, "hysteria2://") ||
		strings.HasPrefix(line, "hy2://") ||
		strings.HasPrefix(line, "trojan://") ||
		strings.HasPrefix(line, "ss://")
}

// BuildProxyHandler создаёт облегчённый ProxyHandler для распарсенной конфигурации.
func BuildProxyHandler(config any, stream *internet.MemoryStreamConfig) (core.ProxyHandler, error) {
	switch cfg := config.(type) {
	case *vless_outbound.Config:
		return vless_outbound.New(context.Background(), cfg)
	case *vmess_outbound.Config:
		return vmess_outbound.New(context.Background(), cfg)
	case *hysteria.ClientConfig:
		return hysteria.NewClient(session.ContextWithStreamSettings(context.Background(), stream), cfg)
	case *trojan.ClientConfig:
		return trojan.NewClient(context.Background(), cfg)
	case *shadowsocks.ClientConfig:
		return shadowsocks.NewClient(context.Background(), cfg)
	default:
		return nil, fmt.Errorf("unsupported proxy config type %T", config)
	}
}

func parseTargetDest(destURL string) net.Destination {
	destHost := "cp.cloudflare.com"
	destPort := 80
	if parsedDest, err := url.Parse(strings.TrimSpace(destURL)); err == nil && parsedDest.Hostname() != "" {
		destHost = parsedDest.Hostname()
		if parsedDest.Port() != "" {
			if p, convErr := strconv.Atoi(parsedDest.Port()); convErr == nil {
				destPort = p
			}
		} else if strings.EqualFold(parsedDest.Scheme, "https") {
			destPort = 443
		}
	}
	return net.TCPDestination(net.DomainAddress(destHost), net.Port(destPort))
}

func appendQueryParam(linkStr, key, val string) string {
	u, err := url.Parse(linkStr)
	if err != nil {
		return linkStr
	}
	q := u.Query()
	q.Set(key, val)
	u.RawQuery = q.Encode()
	return u.String()
}

type parsedCandidate struct {
	link   string
	config any
	stream *internet.MemoryStreamConfig
	dest   net.Destination
}

// ScanEventCallbacks описывает потоковые события пайплайна сканирования.
type ScanEventCallbacks struct {
	OnSuccess  func(link string, delayMs int64, mode string, fragMode string)
	OnFailed   func(link string, reason string)
	OnComplete func(totalSuccess int64, totalFailed int64, fragRevived int64)
}

type exitNodeCompiled struct {
	handler core.ProxyHandler
	dialer  internet.Dialer
}

// shouldRetryWithFragment возвращает true только при транспортной/TLS ошибке внешнего узла,
// когда повтор с фрагментацией ClientHello имеет смысл (исключая случаи, где HTTP-ответ уже получен).
func shouldRetryWithFragment(err error, cand parsedCandidate) bool {
	if err == nil || cand.stream == nil || cand.dest.Network != net.Network_TCP {
		return false
	}
	var httpStatusErr *core.HTTPStatusError
	if errors.As(err, &httpStatusErr) {
		return false
	}
	return cand.stream.SecurityType == "tls" || cand.stream.SecurityType == "reality"
}

// RunScanPipeline запускает двухступенчатый асинхронный пайплайн:
// 1) Пул быстрого TCP pre-check (по умолчанию 50 воркеров).
// 2) Пул проверки профилей (по умолчанию 35 воркеров): Exit Node E2E > Сервисы > HTTP 204 (+ TLS-фрагментация).
func RunScanPipeline(ctx context.Context, links []string, opts ScanOptions, cb ScanEventCallbacks) {
	opts.normalize()
	if len(links) == 0 {
		if cb.OnComplete != nil {
			cb.OnComplete(0, 0, 0)
		}
		return
	}

	mode := opts.EffectiveCheckMode()
	targetDest := parseTargetDest(opts.DestURL)
	enabledServices := opts.EnabledServices()

	var exitCompiled *exitNodeCompiled
	if mode == "endpoint" {
		exitCfg, exitStream, _, err := ParseNodeURL(strings.TrimSpace(opts.EndpointURL))
		if err == nil {
			if exitHandler, hErr := BuildProxyHandler(exitCfg, exitStream); hErr == nil && exitHandler != nil {
				exitCompiled = &exitNodeCompiled{
					handler: exitHandler,
					dialer:  &customDialer{streamSettings: exitStream},
				}
			}
		}
		if exitCompiled == nil {
			// Если URI конечного узла не распарсился, безопасно откатываемся на обычную проверку 204/сервисов
			if opts.ServiceCheck && len(enabledServices) > 0 {
				mode = "services"
			} else {
				mode = "204"
			}
		}
	}

	coreScanner := core.NewScanner()

	var (
		successCount int64
		failCount    int64
		fragRevived  int64
	)

	// Этап 1: Фиксированный пул TCP pre-check (50 потоков по умолчанию)
	rawChan := make(chan string, len(links))
	for _, l := range links {
		rawChan <- l
	}
	close(rawChan)

	aliveChan := make(chan parsedCandidate, len(links))
	var precheckWg sync.WaitGroup
	precheckWorkers := opts.TCPConcurrency
	if precheckWorkers > len(links) {
		precheckWorkers = len(links)
	}

	for i := 0; i < precheckWorkers; i++ {
		precheckWg.Add(1)
		go func() {
			defer precheckWg.Done()
			for linkStr := range rawChan {
				if ctx.Err() != nil {
					return
				}
				cfg, stream, dest, err := ParseNodeURL(linkStr)
				if err != nil {
					atomic.AddInt64(&failCount, 1)
					if cb.OnFailed != nil {
						cb.OnFailed(linkStr, err.Error())
					}
					continue
				}

				if dest.Network == net.Network_TCP {
					targetAddr := stdnet.JoinHostPort(dest.Address.String(), dest.Port.String())
					tcpDialer := stdnet.Dialer{Timeout: 1800 * time.Millisecond}
					network := "tcp"
					if dest.Address.Family().IsDomain() {
						network = "tcp4"
					}
					conn, tcpErr := tcpDialer.DialContext(ctx, network, targetAddr)
					if tcpErr != nil {
						atomic.AddInt64(&failCount, 1)
						if cb.OnFailed != nil {
							cb.OnFailed(linkStr, tcpErr.Error())
						}
						continue
					}
					_ = conn.Close()
				}

				select {
				case aliveChan <- parsedCandidate{link: linkStr, config: cfg, stream: stream, dest: dest}:
				case <-ctx.Done():
					return
				}
			}
		}()
	}

	go func() {
		precheckWg.Wait()
		close(aliveChan)
	}()

	// Этап 2: Фиксированный пул асинхронных проверок профилей (35 потоков по умолчанию)
	verifyNode := func(baseCtx context.Context, cand parsedCandidate, outerHandler core.ProxyHandler, outerDialer internet.Dialer) (int64, error) {
		startT := time.Now()
		switch mode {
		case "endpoint":
			chainCtx := core.NewChainDialerCtx(baseCtx, outerHandler, outerDialer)
			res := coreScanner.TestNode(chainCtx, exitCompiled.handler, exitCompiled.dialer, targetDest)
			if res.Error != nil {
				return 0, res.Error
			}
			return time.Since(startT).Milliseconds(), nil

		case "services":
			avgLat, err := coreScanner.CheckServicesParallel(baseCtx, outerHandler, outerDialer, enabledServices, serviceCheckTimeout(opts.TimeoutSec))
			if err != nil {
				return 0, err
			}
			ms := avgLat.Milliseconds()
			if ms <= 0 {
				ms = time.Since(startT).Milliseconds()
			}
			return ms, nil

		default:
			res := coreScanner.TestNode(baseCtx, outerHandler, outerDialer, targetDest)
			if res.Error != nil {
				return 0, res.Error
			}
			return time.Since(startT).Milliseconds(), nil
		}
	}

	var verifyWg sync.WaitGroup
	verifyWorkers := opts.VerifyConcurrency
	if verifyWorkers > len(links) {
		verifyWorkers = len(links)
	}

	for i := 0; i < verifyWorkers; i++ {
		verifyWg.Add(1)
		go func() {
			defer verifyWg.Done()
			for cand := range aliveChan {
				if ctx.Err() != nil {
					return
				}
				handler, hErr := BuildProxyHandler(cand.config, cand.stream)
				if hErr != nil || handler == nil {
					atomic.AddInt64(&failCount, 1)
					if cb.OnFailed != nil && hErr != nil {
						cb.OnFailed(cand.link, hErr.Error())
					}
					continue
				}
				dialer := &customDialer{streamSettings: cand.stream}

				nodeCtx, cancelNode := context.WithTimeout(ctx, time.Duration(opts.TimeoutSec)*time.Second)
				delayMs, err := verifyNode(nodeCtx, cand, handler, dialer)
				cancelNode()

				if err == nil {
					atomic.AddInt64(&successCount, 1)
					if cb.OnSuccess != nil {
						cb.OnSuccess(cand.link, delayMs, mode, "")
					}
					continue
				}

				// Fallback на фрагментацию ClientHello при TLS/Reality ошибке внешнего узла
				revived := false
				if shouldRetryWithFragment(err, cand) && ctx.Err() == nil {
					for _, fragStep := range []struct {
						mode internet.FragmentMode
						tag  string
					}{
						{internet.FragmentTCP, "tcp"},
						{internet.FragmentRecord, "record"},
					} {
						fragCtx, cancelFrag := context.WithTimeout(ctx, time.Duration(opts.FragTimeoutSec)*time.Second)
						fragCtx = internet.ContextWithFragment(fragCtx, fragStep.mode)
						fDelay, fErr := verifyNode(fragCtx, cand, handler, dialer)
						cancelFrag()
						if fErr == nil {
							atomic.AddInt64(&successCount, 1)
							atomic.AddInt64(&fragRevived, 1)
							revivedLink := tagLinkWithFragment(cand.link, fragStep.tag)
							if cb.OnSuccess != nil {
								cb.OnSuccess(revivedLink, fDelay, mode, fragStep.tag)
							}
							revived = true
							break
						}
					}
				}
				if !revived {
					atomic.AddInt64(&failCount, 1)
					if cb.OnFailed != nil {
						cb.OnFailed(cand.link, err.Error())
					}
				}
			}
		}()
	}

	verifyWg.Wait()
	if cb.OnComplete != nil {
		cb.OnComplete(atomic.LoadInt64(&successCount), atomic.LoadInt64(&failCount), atomic.LoadInt64(&fragRevived))
	}
}
