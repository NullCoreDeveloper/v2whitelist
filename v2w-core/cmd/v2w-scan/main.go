package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	core "github.com/kiktor/v2w-core"
	"github.com/kiktor/v2w-core/common/log"
	"github.com/kiktor/v2w-core/v2wscanner"
)

var stdoutMu sync.Mutex

func parseServicesFlag(raw string) ([]core.ServiceCheckSpec, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, nil
	}
	var payload []byte
	if strings.HasPrefix(trimmed, "@") {
		filePath := strings.TrimSpace(strings.TrimPrefix(trimmed, "@"))
		data, err := os.ReadFile(filePath)
		if err != nil {
			return nil, fmt.Errorf("read services file %s: %w", filePath, err)
		}
		payload = data
	} else {
		payload = []byte(trimmed)
	}
	var services []core.ServiceCheckSpec
	if err := json.Unmarshal(payload, &services); err != nil {
		return nil, fmt.Errorf("parse services json: %w", err)
	}
	return services, nil
}

func loadInputConfigs(fromStdin bool, subURL string) (string, error) {
	if fromStdin {
		data, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", err
		}
		return string(data), nil
	}
	if strings.HasPrefix(subURL, "http://") || strings.HasPrefix(subURL, "https://") {
		resp, err := http.Get(subURL)
		if err != nil {
			return "", err
		}
		defer resp.Body.Close()
		data, err := io.ReadAll(resp.Body)
		if err != nil {
			return "", err
		}
		return string(data), nil
	}
	data, err := os.ReadFile(subURL)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func syncIfFile(out io.Writer) {
	if f, ok := out.(*os.File); ok {
		_ = f.Sync()
	}
}

func runFullScanMode(ctx context.Context, links []string, opts v2wscanner.ScanOptions, out io.Writer) {
	v2wscanner.RunFullScanPipeline(
		ctx,
		links,
		opts,
		func(res v2wscanner.FullScanNodeResult) {
			payload, err := json.Marshal(res)
			if err != nil {
				return
			}
			stdoutMu.Lock()
			_, _ = fmt.Fprintf(out, "RESULT\t%s\n", payload)
			syncIfFile(out)
			stdoutMu.Unlock()
		},
		func(totalSuccess int64, totalFailed int64) {
			stdoutMu.Lock()
			_, _ = fmt.Fprintf(out, "COMPLETE\t%d\t%d\n", totalSuccess, totalFailed)
			syncIfFile(out)
			stdoutMu.Unlock()
		},
	)
}

func main() {
	// Изолируем логи Xray в stderr, гарантируя чистый stdout для протокола сканирования.
	log.RegisterHandler(log.NewLogger(log.CreateStderrLogWriter()))

	subURL := flag.String("sub", "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt", "URL to fetch VLESS subscription from or local file path")
	fromStdin := flag.Bool("stdin", false, "Read configuration links directly from stdin")
	streamMode := flag.Bool("stream", false, "Stream successful nodes line-by-line as they respond")
	fullScan := flag.Bool("full-scan", false, "Run full server inspection pipeline and stream JSON-lines results")
	firstOnly := flag.Bool("first", false, "Exit immediately upon finding the first working node (stateless fast mode)")
	concurrency := flag.Int("concurrency", 35, "Maximum concurrent profile verification jobs")
	tcpConcurrency := flag.Int("tcp-concurrency", 100, "Maximum concurrent TCP pre-check jobs")
	timeoutSec := flag.Int("timeout", 6, "Per-node scan timeout in seconds")
	fragTimeoutSec := flag.Int("frag-timeout", 4, "Timeout in seconds for TLS fragmentation retry")
	destURL := flag.String("dest", "http://cp.cloudflare.com/generate_204", "Destination URL to test connectivity")
	endpointURL := flag.String("endpoint", "", "Optional exit node URL for E2E chained verification")
	endpointE2E := flag.Bool("endpoint-e2e", false, "Run E2E connectivity check through exit node (ignores service checks)")
	serviceCheck := flag.Bool("service-check", false, "Run parallel lightweight service checks inside v2w-core")
	servicesJSON := flag.String("services-json", "", "JSON array of services or @path to JSON file")
	flag.Parse()
	explicitFlags := make(map[string]bool)
	flag.Visit(func(f *flag.Flag) {
		explicitFlags[f.Name] = true
	})

	subData, err := loadInputConfigs(*fromStdin, *subURL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "[FATAL] Failed to load configs: %v\n", err)
		os.Exit(1)
	}

	opts, links, hasHeader := v2wscanner.ParseConfigsWithOptions(subData, *concurrency)
	if (explicitFlags["tcp-concurrency"] || !hasHeader) && *tcpConcurrency > 0 {
		opts.TCPConcurrency = *tcpConcurrency
	}
	if (explicitFlags["concurrency"] || !hasHeader) && *concurrency > 0 {
		opts.VerifyConcurrency = *concurrency
	}
	if (explicitFlags["timeout"] || !hasHeader) && *timeoutSec > 0 {
		opts.TimeoutSec = *timeoutSec
	}
	if (explicitFlags["frag-timeout"] || !hasHeader) && *fragTimeoutSec > 0 {
		opts.FragTimeoutSec = *fragTimeoutSec
	}
	if (explicitFlags["dest"] || !hasHeader) && strings.TrimSpace(*destURL) != "" {
		opts.DestURL = strings.TrimSpace(*destURL)
	}
	if strings.TrimSpace(*endpointURL) != "" {
		opts.EndpointURL = strings.TrimSpace(*endpointURL)
	}
	if *endpointE2E {
		opts.EndpointE2E = true
	}
	if *serviceCheck {
		opts.ServiceCheck = true
	}
	if strings.TrimSpace(*servicesJSON) != "" {
		parsedServices, svcErr := parseServicesFlag(*servicesJSON)
		if svcErr != nil {
			fmt.Fprintf(os.Stderr, "[WARN] %v\n", svcErr)
		} else if len(parsedServices) > 0 {
			opts.Services = parsedServices
		}
	}
	opts.FirstOnly = *firstOnly
	opts.StreamMode = *streamMode

	sigCtx, stopSig := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stopSig()
	ctx, cancel := context.WithCancel(sigCtx)
	defer cancel()

	if *fullScan {
		runFullScanMode(ctx, links, opts, os.Stdout)
		return
	}

	checkMode := opts.EffectiveCheckMode()

	var successCount int32
	var directSuccessCount int32
	var fragRevivedCount int32
	var failCount int32
	var successfulLinks []string
	var linksMu sync.Mutex

	v2wscanner.RunScanPipeline(ctx, links, opts, v2wscanner.ScanEventCallbacks{
		OnSuccess: func(link string, delayMs int64, mode string, fragMode string) {
			atomic.AddInt32(&successCount, 1)
			if fragMode != "" {
				atomic.AddInt32(&fragRevivedCount, 1)
			} else {
				atomic.AddInt32(&directSuccessCount, 1)
			}
			effectiveMode := mode
			if effectiveMode == "" {
				effectiveMode = checkMode
			}
			if *streamMode || *firstOnly {
				stdoutMu.Lock()
				fmt.Printf("SUCCESS\t%d\t%s\tCHECKED=%s\n", delayMs, link, effectiveMode)
				_ = os.Stdout.Sync()
				stdoutMu.Unlock()
				if *firstOnly {
					cancel()
					os.Exit(0)
				}
			}
			linksMu.Lock()
			successfulLinks = append(successfulLinks, link)
			linksMu.Unlock()
		},
		OnFailed: func(link string, errStr string) {
			atomic.AddInt32(&failCount, 1)
			if *streamMode {
				cleanErr := strings.ReplaceAll(strings.ReplaceAll(errStr, "\n", " "), "\t", " ")
				stdoutMu.Lock()
				fmt.Printf("FAILED\t%s\t%s\n", cleanErr, link)
				_ = os.Stdout.Sync()
				stdoutMu.Unlock()
			} else {
				fmt.Fprintf(os.Stderr, "[FAILED] %s: %s\n", link, errStr)
			}
		},
	})

	if *streamMode {
		stdoutMu.Lock()
		fmt.Printf("COMPLETE\t%d\t%d\t%d\n", successCount, failCount, fragRevivedCount)
		_ = os.Stdout.Sync()
		stdoutMu.Unlock()
	} else {
		fmt.Printf("\n--- Scan Complete ---\nTotal: %d | Success: %d (Direct: %d, Revived via Fragment: %d) | Failed: %d\n",
			successCount+failCount, successCount, directSuccessCount, fragRevivedCount, failCount)
		if len(successfulLinks) > 0 {
			fmt.Printf("\n--- Working Configs ---\n")
			for _, sl := range successfulLinks {
				fmt.Println(sl)
			}
		}
	}
}
