package v2wscanner

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	core "github.com/kiktor/v2w-core" // the scanner package
	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/protocol"
	"github.com/kiktor/v2w-core/common/serial"
	"github.com/kiktor/v2w-core/common/session"
	"github.com/kiktor/v2w-core/proxy/hysteria"
	"github.com/kiktor/v2w-core/proxy/hysteria/account"
	"github.com/kiktor/v2w-core/proxy/vless"
	vless_outbound "github.com/kiktor/v2w-core/proxy/vless/outbound"
	"github.com/kiktor/v2w-core/transport/internet"
	"github.com/kiktor/v2w-core/transport/internet/grpc"
	"github.com/kiktor/v2w-core/transport/internet/httpupgrade"
	hysteria_transport "github.com/kiktor/v2w-core/transport/internet/hysteria"
	"github.com/kiktor/v2w-core/transport/internet/reality"
	"github.com/kiktor/v2w-core/transport/internet/splithttp"
	"github.com/kiktor/v2w-core/transport/internet/stat"
	"github.com/kiktor/v2w-core/transport/internet/tcp"
	"github.com/kiktor/v2w-core/transport/internet/tls"
	"github.com/kiktor/v2w-core/transport/internet/websocket"
)

type V2WScanCallback interface {
	OnServerSuccess(configUrl string, delay int64)
	OnScanComplete(totalSuccess int64, totalFailed int64)
}

type V2WScanner struct{}

func NewV2WScanner() *V2WScanner {
	return &V2WScanner{}
}

func (s *V2WScanner) Run(configs string, maxConcurrency int64, callback V2WScanCallback) {
	RunV2WScanner(configs, maxConcurrency, callback)
}

func (s *V2WScanner) Stop() {
	StopV2WScanner()
}

type customDialer struct {
	streamSettings *internet.MemoryStreamConfig
}

func (d *customDialer) Dial(ctx context.Context, dest net.Destination) (stat.Connection, error) {
	return internet.Dial(ctx, dest, d.streamSettings)
}
func (d *customDialer) DestIpAddress() net.IP {
	return nil
}

var (
	scanCancel context.CancelFunc
	scanMutex  sync.Mutex
)

func StopV2WScanner() {
	scanMutex.Lock()
	defer scanMutex.Unlock()
	if scanCancel != nil {
		scanCancel()
		scanCancel = nil
	}
}

func RunV2WScanner(configs string, maxConcurrency int64, callback V2WScanCallback) {
	if maxConcurrency <= 0 {
		maxConcurrency = 20
	}
	
	scanMutex.Lock()
	if scanCancel != nil {
		scanCancel()
	}
	globalCtx, cancelGlobal := context.WithCancel(context.Background())
	scanCancel = cancelGlobal
	scanMutex.Unlock()

	configs = strings.ReplaceAll(configs, "\r\n", "\n")
	rawLines := strings.Split(configs, "\n")
	var links []string
	for _, line := range rawLines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "vless://") || strings.HasPrefix(line, "hysteria2://") {
			links = append(links, line)
		}
	}

	if len(links) == 0 {
		if callback != nil {
			callback.OnScanComplete(0, 0)
		}
		return
	}

	var wg sync.WaitGroup
	coreScanner := core.NewScanner()
	var successCount int32
	var failCount int32

	sem := make(chan struct{}, maxConcurrency)

	for _, l := range links {
		wg.Add(1)
		go func(linkStr string) {
			defer wg.Done()

			// Check if we should abort before waiting for semaphore
			if globalCtx.Err() != nil {
				return
			}

			sem <- struct{}{}
			defer func() { <-sem }()

			// Check again after acquiring semaphore
			if globalCtx.Err() != nil {
				return
			}

			config, stream, dest, err := parseVlessURL(linkStr)
			if err != nil {
				atomic.AddInt32(&failCount, 1)
				return
			}
			_ = dest

			var handler core.ProxyHandler
			if outboundConfig, ok := config.(*vless_outbound.Config); ok {
				h, err := vless_outbound.New(context.Background(), outboundConfig)
				if err != nil || h == nil {
					atomic.AddInt32(&failCount, 1)
					return
				}
				handler = h
			} else if outboundConfig, ok := config.(*hysteria.ClientConfig); ok {
				h, err := hysteria.NewClient(session.ContextWithStreamSettings(context.Background(), stream), outboundConfig)
				if err != nil || h == nil {
					atomic.AddInt32(&failCount, 1)
					return
				}
				handler = h
			}

			if handler == nil {
				atomic.AddInt32(&failCount, 1)
				return
			}

			dialer := &customDialer{streamSettings: stream}
			targetDest := net.TCPDestination(net.DomainAddress("www.google.com"), 443)

			startT := time.Now()
			ctx, cancel := context.WithTimeout(globalCtx, 20*time.Second)
			res := coreScanner.TestNode(ctx, handler, dialer, targetDest)
			cancel()

			if res.Error == nil {
				atomic.AddInt32(&successCount, 1)
				delayMs := time.Since(startT).Milliseconds()
				if callback != nil {
					callback.OnServerSuccess(linkStr, delayMs)
				}
			} else {
				atomic.AddInt32(&failCount, 1)
			}
		}(l)
	}

	go func() {
		wg.Wait()
		if callback != nil {
			callback.OnScanComplete(atomic.LoadInt32(&successCount), atomic.LoadInt32(&failCount))
		}
	}()
}


func parseVlessURL(rawURL string) (any, *internet.MemoryStreamConfig, net.Destination, error) {
	if strings.HasPrefix(rawURL, "hysteria2://") {
		u, err := url.Parse(rawURL)
		if err != nil {
			return nil, nil, net.Destination{}, err
		}
		password := u.User.Username()
		host := u.Hostname()
		portStr := u.Port()
		port, _ := strconv.Atoi(portStr)
		dest := net.TCPDestination(net.ParseAddress(host), net.Port(port))
		q := u.Query()
		sni := q.Get("sni")
		if sni == "" {
			sni = q.Get("host")
		}
		streamSettings := &internet.MemoryStreamConfig{
			ProtocolName: "hysteria",
			ProtocolSettings: &hysteria_transport.Config{
				Auth: password,
			},
			SecurityType: "tls",
			SecuritySettings: &tls.Config{
				ServerName: sni,
			},
		}
		if fp := q.Get("fp"); fp != "" {
			streamSettings.SecuritySettings.(*tls.Config).Fingerprint = fp
		}
		config := &hysteria.ClientConfig{
			Server: &protocol.ServerEndpoint{
				Address: net.NewIPOrDomain(net.ParseAddress(host)),
				Port:    uint32(port),
				User: &protocol.User{
					Account: serial.ToTypedMessage(&account.Account{
						Auth: password,
					}),
				},
			},
		}
		return config, streamSettings, dest, nil
	}

	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, nil, net.Destination{}, err
	}

	if u.Scheme != "vless" {
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported protocol: %s", u.Scheme)
	}

	uuid := u.User.Username()
	serverIP := u.Hostname()
	portStr := u.Port()
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, nil, net.Destination{}, err
	}

	dest := net.TCPDestination(net.ParseAddress(serverIP), net.Port(port))

	q := u.Query()
	netType := q.Get("type")
	if netType == "" {
		netType = "tcp"
	}
	if netType == "raw" {
		netType = "tcp"
	}

	security := q.Get("security")

	streamSettings := &internet.MemoryStreamConfig{
		ProtocolName: netType,
		SecurityType: security,
	}

	switch netType {
	case "tcp":
		streamSettings.ProtocolName = "tcp"
		streamSettings.ProtocolSettings = &tcp.Config{}
	case "ws":
		fallthrough
	case "websocket":
		streamSettings.ProtocolName = "websocket"
		streamSettings.ProtocolSettings = &websocket.Config{
			Path: q.Get("path"),
			Header: map[string]string{
				"Host": q.Get("host"),
			},
		}
	case "xhttp":
		streamSettings.ProtocolName = "splithttp"
		host := q.Get("host")
		if host == "" {
			host = q.Get("sni")
		}
		mode := q.Get("mode")
		if mode == "" {
			mode = "auto"
		}
		config := &splithttp.Config{
			Path: q.Get("path"),
			Host: host,
			Mode: mode,
			Headers: map[string]string{
				"Host": host,
			},
		}
		parseRange := func(s string) *splithttp.RangeConfig {
			if s == "" {
				return nil
			}
			parts := strings.Split(s, "-")
			if len(parts) == 1 {
				v, _ := strconv.Atoi(parts[0])
				return &splithttp.RangeConfig{From: int32(v), To: int32(v)}
			}
			from, _ := strconv.Atoi(parts[0])
			to, _ := strconv.Atoi(parts[1])
			return &splithttp.RangeConfig{From: int32(from), To: int32(to)}
		}
		config.XPaddingBytes = parseRange(q.Get("x_padding_bytes"))
		if config.XPaddingBytes == nil {
			config.XPaddingBytes = parseRange(q.Get("xPaddingBytes"))
		}
		streamSettings.ProtocolSettings = config
	case "httpupgrade":
		streamSettings.ProtocolName = "httpupgrade"
		host := q.Get("host")
		if host == "" {
			host = q.Get("sni")
		}
		streamSettings.ProtocolSettings = &httpupgrade.Config{
			Path: q.Get("path"),
			Host: host,
			Header: map[string]string{
				"Host": host,
			},
		}
	case "grpc":
		streamSettings.ProtocolName = "grpc"
		streamSettings.ProtocolSettings = &grpc.Config{
			ServiceName: q.Get("serviceName"),
		}
	default:
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported network type: %s", netType)
	}

	switch security {
	case "tls":
		streamSettings.SecurityType = "tls"
		alpn := q.Get("alpn")
		var nextProtocol []string
		if alpn != "" {
			for _, p := range strings.Split(alpn, ",") {
				nextProtocol = append(nextProtocol, strings.TrimSpace(p))
			}
		}
		sni := q.Get("sni")
		if sni == "" {
			sni = q.Get("host")
		}
		streamSettings.SecuritySettings = &tls.Config{
			ServerName:   sni,
			NextProtocol: nextProtocol,
		}
		if fp := q.Get("fp"); fp != "" {
			streamSettings.SecuritySettings.(*tls.Config).Fingerprint = fp
		}
	case "reality":
		streamSettings.SecurityType = "reality"
		pbkStr := q.Get("pbk")
		pbkBytes, err := base64.RawURLEncoding.DecodeString(pbkStr)
		if err != nil {
			pbkBytes, _ = base64.URLEncoding.DecodeString(pbkStr)
		}

		sidStr := q.Get("sid")
		shortId, err := hex.DecodeString(sidStr)
		if err != nil && len(sidStr) > 0 {
			shortId = []byte(sidStr)
		}
		sni := q.Get("sni")
		if sni == "" {
			sni = q.Get("host")
		}

		streamSettings.SecuritySettings = &reality.Config{
			Show:        false,
			Dest:        sni + ":443",
			Type:        netType,
			ServerNames: []string{sni},
			ServerName:  sni,
			ShortIds:    [][]byte{shortId},
			ShortId:     shortId,
			PublicKey:   pbkBytes,
			Fingerprint: q.Get("fp"),
			SpiderX:     q.Get("spx"),
			SpiderY:     []int64{100, 1000, 1, 3, 2, 4, 10, 50, 10, 50},
		}
	}

	account := &vless.Account{
		Id:         uuid,
		Encryption: q.Get("encryption"),
		Flow:       q.Get("flow"),
	}

	config := &vless_outbound.Config{
		Vnext: &protocol.ServerEndpoint{
			Address: net.NewIPOrDomain(net.ParseAddress(serverIP)),
			Port:    uint32(port),
			User: &protocol.User{
				Account: serial.ToTypedMessage(account),
			},
		},
	}

	return config, streamSettings, dest, nil
}
