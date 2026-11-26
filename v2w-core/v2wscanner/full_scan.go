package v2wscanner

import (
	"context"
	"encoding/json"
	stdnet "net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	core "github.com/kiktor/v2w-core"
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/reality"
	"github.com/kiktor/v2w-core/transport/internet/tls"
)

// V2WFullScanCallback — gomobile-совместимый интерфейс потоковой передачи результатов полного сканирования.
type V2WFullScanCallback interface {
	OnServerResult(resultJson string)
	OnScanComplete(totalSuccess int64, totalFailed int64)
}

// FullScanNodeResult описывает полный снимок диагностики одного сервера.
type FullScanNodeResult struct {
	Link           string                     `json:"link"`
	Alive          bool                       `json:"alive"`
	DelayMs        int64                      `json:"delay_ms"`
	ExitIP         string                     `json:"exit_ip,omitempty"`
	CountryCode    string                     `json:"country_code,omitempty"`
	FragMode       string                     `json:"frag_mode,omitempty"`
	EndpointPassed *bool                      `json:"endpoint_passed,omitempty"`
	Services       []core.SingleServiceResult `json:"services,omitempty"`
	Error          string                     `json:"error,omitempty"`
}

var (
	fullScanCancel context.CancelFunc
	fullScanMutex  sync.Mutex
)

// StopV2WFullScan отменяет активное полное сканирование, не затрагивая быстрый SmartConnect.
func StopV2WFullScan() {
	fullScanMutex.Lock()
	defer fullScanMutex.Unlock()
	if fullScanCancel != nil {
		fullScanCancel()
		fullScanCancel = nil
	}
}

// RunV2WFullScan запускает полное сканирование всех переданных узлов и отдаёт JSON-результаты по мере готовности.
func RunV2WFullScan(configs string, maxConcurrency int64, callback V2WFullScanCallback) {
	opts, links, hasHeader := ParseConfigsWithOptions(configs, int(maxConcurrency))
	if maxConcurrency > 0 && !hasHeader {
		opts.VerifyConcurrency = int(maxConcurrency)
	}

	fullScanMutex.Lock()
	if fullScanCancel != nil {
		fullScanCancel()
	}
	globalCtx, cancelGlobal := context.WithCancel(context.Background())
	fullScanCancel = cancelGlobal
	fullScanMutex.Unlock()

	if len(links) == 0 {
		if callback != nil {
			callback.OnScanComplete(0, 0)
		}
		return
	}

	go func() {
		RunFullScanPipeline(
			globalCtx,
			links,
			opts,
			func(res FullScanNodeResult) {
				if callback == nil {
					return
				}
				payload, err := json.Marshal(res)
				if err != nil {
					return
				}
				callback.OnServerResult(string(payload))
			},
			func(totalSuccess int64, totalFailed int64) {
				if callback != nil {
					callback.OnScanComplete(totalSuccess, totalFailed)
				}
			},
		)
	}()
}

// InspectNode проводит полную диагностику живого или кандидата-узла:
// 1) Проверка живости + Exit IP + CountryCode через cdn-cgi/trace (с fallback на фрагментацию и 204).
// 2) Взаимоисключающе: либо E2E-проверка конечного узла (если задан EndpointURL), либо проверка всех включённых сервисов без fail-fast.
func InspectNode(
	ctx context.Context,
	scanner *core.Scanner,
	cand parsedCandidate,
	handler core.ProxyHandler,
	dialer internet.Dialer,
	opts ScanOptions,
	exitCompiled *exitNodeCompiled,
	targetDest net.Destination,
	enabledServices []core.ServiceCheckSpec,
) FullScanNodeResult {
	// Шаг A: Живость + Exit IP + авто-фрагментация
	probeCtx, cancelProbe := context.WithTimeout(ctx, time.Duration(opts.TimeoutSec)*time.Second)
	exitInfo, err := scanner.ProbeExitIP(probeCtx, handler, dialer)
	cancelProbe()

	fragMode := ""
	if err != nil && shouldRetryWithFragment(err, cand) && ctx.Err() == nil {
		for _, fragStep := range []struct {
			mode internet.FragmentMode
			tag  string
		}{
			{internet.FragmentTCP, "tcp"},
			{internet.FragmentRecord, "record"},
		} {
			fragCtx, cancelFrag := context.WithTimeout(ctx, time.Duration(opts.FragTimeoutSec)*time.Second)
			fragCtx = internet.ContextWithFragment(fragCtx, fragStep.mode)
			fInfo, fErr := scanner.ProbeExitIP(fragCtx, handler, dialer)
			cancelFrag()
			if fErr == nil {
				exitInfo = fInfo
				fragMode = fragStep.tag
				err = nil
				break
			}
		}
	}

	var fallbackDelayMs int64
	if err != nil && ctx.Err() == nil {
		fbCtx, cancelFb := context.WithTimeout(ctx, time.Duration(opts.TimeoutSec)*time.Second)
		fbRes := scanner.TestNode(fbCtx, handler, dialer, targetDest)
		cancelFb()
		if fbRes.Error == nil {
			err = nil
			exitInfo = nil
			fragMode = ""
			fallbackDelayMs = fbRes.Latency.Milliseconds()
		} else if shouldRetryWithFragment(fbRes.Error, cand) && ctx.Err() == nil {
			for _, fragStep := range []struct {
				mode internet.FragmentMode
				tag  string
			}{
				{internet.FragmentTCP, "tcp"},
				{internet.FragmentRecord, "record"},
			} {
				fragCtx, cancelFrag := context.WithTimeout(ctx, time.Duration(opts.FragTimeoutSec)*time.Second)
				fragCtx = internet.ContextWithFragment(fragCtx, fragStep.mode)
				fRes := scanner.TestNode(fragCtx, handler, dialer, targetDest)
				cancelFrag()
				if fRes.Error == nil {
					err = nil
					exitInfo = nil
					fragMode = fragStep.tag
					fallbackDelayMs = fRes.Latency.Milliseconds()
					break
				}
			}
		}
	}

	if err != nil {
		return FullScanNodeResult{
			Link:    cand.link,
			Alive:   false,
			DelayMs: -1,
			Error:   err.Error(),
		}
	}

	// Шаг B: Контекст этапа 3 с учётом обнаруженной фрагментации
	stageCtx := ctx
	switch fragMode {
	case "tcp":
		stageCtx = internet.ContextWithFragment(stageCtx, internet.FragmentTCP)
	case "record":
		stageCtx = internet.ContextWithFragment(stageCtx, internet.FragmentRecord)
	}

	result := FullScanNodeResult{
		Link:     cand.link,
		Alive:    true,
		FragMode: fragMode,
	}
	if exitInfo != nil {
		result.ExitIP = exitInfo.IP
		result.CountryCode = exitInfo.CountryCode
		result.DelayMs = exitInfo.Latency.Milliseconds()
	} else {
		result.DelayMs = fallbackDelayMs
	}
	if result.DelayMs < 0 {
		result.DelayMs = 0
	}

	// Шаг C: Взаимоисключающая проверка — Конечный узел ИЛИ Сервисы
	if strings.TrimSpace(opts.EndpointURL) != "" {
		result.Services = nil
		if exitCompiled != nil {
			epTimeoutSec := opts.TimeoutSec
			if epTimeoutSec < 8 {
				epTimeoutSec = 8
			}
			epCtx, cancelEp := context.WithTimeout(stageCtx, time.Duration(epTimeoutSec)*time.Second)
			chainCtx := core.NewChainDialerCtx(epCtx, handler, dialer)
			epRes := scanner.TestNode(chainCtx, exitCompiled.handler, exitCompiled.dialer, targetDest)
			cancelEp()

			passed := epRes.Error == nil
			result.EndpointPassed = &passed
			if passed && epRes.Latency.Milliseconds() > 0 {
				result.DelayMs = epRes.Latency.Milliseconds()
			}
		} else {
			result.EndpointPassed = nil
		}
	} else {
		result.EndpointPassed = nil
		if len(enabledServices) > 0 {
			perSvcTimeout := serviceCheckTimeout(opts.TimeoutSec)
			if perSvcTimeout < 4500*time.Millisecond {
				perSvcTimeout = 4500 * time.Millisecond
			}
			batches := (len(enabledServices) + 3) / 4
			if batches < 1 {
				batches = 1
			}
			allSvcCtx, cancelSvc := context.WithTimeout(stageCtx, time.Duration(batches)*perSvcTimeout+2*time.Second)
			result.Services = scanner.CheckServicesAll(
				allSvcCtx,
				handler,
				dialer,
				enabledServices,
				perSvcTimeout,
				result.CountryCode,
			)
			cancelSvc()
		}
	}

	return result
}

type keyedGatePool struct {
	limit int
	mu    sync.Mutex
	gates map[string]chan struct{}
}

func newKeyedGatePool(limit int) *keyedGatePool {
	return &keyedGatePool{
		limit: limit,
		gates: make(map[string]chan struct{}),
	}
}

func (p *keyedGatePool) gateFor(key string) chan struct{} {
	p.mu.Lock()
	defer p.mu.Unlock()
	ch, ok := p.gates[key]
	if !ok {
		ch = make(chan struct{}, p.limit)
		p.gates[key] = ch
	}
	return ch
}

func (p *keyedGatePool) acquire(ctx context.Context, key string) (func(), bool) {
	ch := p.gateFor(key)
	select {
	case ch <- struct{}{}:
		return func() { <-ch }, true
	case <-ctx.Done():
		return nil, false
	}
}

func candidateHostAndSNI(cand parsedCandidate) (string, string) {
	hostKey := ""
	if cand.dest.Address != nil {
		hostKey = strings.ToLower(strings.TrimSpace(cand.dest.Address.String()))
	}
	sniKey := ""
	if cand.stream != nil {
		switch sec := cand.stream.SecuritySettings.(type) {
		case *tls.Config:
			sniKey = strings.ToLower(strings.TrimSpace(sec.ServerName))
		case *reality.Config:
			sniKey = strings.ToLower(strings.TrimSpace(sec.ServerName))
		}
	}
	if sniKey == "" {
		sniKey = hostKey
	}
	return hostKey, sniKey
}

// RunFullScanPipeline запускает двухступенчатый пайплайн полного сканирования:
// 1) TCP pre-check (ошибки сразу отправляются в onResult с Alive=false).
// 2) Полная диагностика через InspectNode.
func RunFullScanPipeline(
	ctx context.Context,
	links []string,
	opts ScanOptions,
	onResult func(FullScanNodeResult),
	onComplete func(totalSuccess int64, totalFailed int64),
) {
	opts.normalize()
	if len(links) == 0 {
		if onComplete != nil {
			onComplete(0, 0)
		}
		return
	}

	targetDest := parseTargetDest(opts.DestURL)
	enabledServices := opts.EnabledServices()

	var exitCompiled *exitNodeCompiled
	if strings.TrimSpace(opts.EndpointURL) != "" {
		exitCfg, exitStream, _, err := ParseNodeURL(strings.TrimSpace(opts.EndpointURL))
		if err == nil {
			if exitHandler, hErr := BuildProxyHandler(exitCfg, exitStream); hErr == nil && exitHandler != nil {
				exitCompiled = &exitNodeCompiled{
					handler: exitHandler,
					dialer:  &customDialer{streamSettings: exitStream},
				}
			}
		}
	}

	coreScanner := core.NewScanner()

	var (
		successCount int64
		failCount    int64
	)

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
					if onResult != nil && ctx.Err() == nil {
						onResult(FullScanNodeResult{
							Link:    linkStr,
							Alive:   false,
							DelayMs: -1,
							Error:   err.Error(),
						})
					}
					continue
				}

				if dest.Network == net.Network_TCP {
					targetAddr := stdnet.JoinHostPort(dest.Address.String(), dest.Port.String())
					tcpDialer := stdnet.Dialer{Timeout: 2500 * time.Millisecond}
					network := "tcp"
					if dest.Address.Family().IsDomain() {
						network = "tcp4"
					}
					conn, tcpErr := tcpDialer.DialContext(ctx, network, targetAddr)
					if tcpErr != nil {
						atomic.AddInt64(&failCount, 1)
						if onResult != nil && ctx.Err() == nil {
							onResult(FullScanNodeResult{
								Link:    linkStr,
								Alive:   false,
								DelayMs: -1,
								Error:   tcpErr.Error(),
							})
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

	var verifyWg sync.WaitGroup
	verifyWorkers := opts.VerifyConcurrency
	if len(enabledServices) > 0 && verifyWorkers > 12 {
		verifyWorkers = 12
	}
	if verifyWorkers > len(links) {
		verifyWorkers = len(links)
	}

	const (
		maxPerHost = 3
		maxPerSNI  = 6
	)
	hostGates := newKeyedGatePool(maxPerHost)
	sniGates := newKeyedGatePool(maxPerSNI)

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
					errMsg := "failed to build proxy handler"
					if hErr != nil {
						errMsg = hErr.Error()
					}
					if onResult != nil && ctx.Err() == nil {
						onResult(FullScanNodeResult{
							Link:    cand.link,
							Alive:   false,
							DelayMs: -1,
							Error:   errMsg,
						})
					}
					continue
				}
				dialer := &customDialer{streamSettings: cand.stream}

				hostKey, sniKey := candidateHostAndSNI(cand)
				releaseHost, ok := hostGates.acquire(ctx, hostKey)
				if !ok {
					return
				}
				releaseSNI, ok := sniGates.acquire(ctx, sniKey)
				if !ok {
					releaseHost()
					return
				}

				res := InspectNode(ctx, coreScanner, cand, handler, dialer, opts, exitCompiled, targetDest, enabledServices)
				releaseSNI()
				releaseHost()

				if res.Alive {
					atomic.AddInt64(&successCount, 1)
				} else {
					atomic.AddInt64(&failCount, 1)
				}
				if onResult != nil && ctx.Err() == nil {
					onResult(res)
				}
			}
		}()
	}

	verifyWg.Wait()
	if onComplete != nil {
		onComplete(atomic.LoadInt64(&successCount), atomic.LoadInt64(&failCount))
	}
}
