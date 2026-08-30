package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/proxy/hysteria"
	"github.com/xtls/xray-core/proxy/hysteria/account"
	"github.com/xtls/xray-core/proxy/vless"
	vless_outbound "github.com/xtls/xray-core/proxy/vless/outbound"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/grpc"
	"github.com/xtls/xray-core/transport/internet/httpupgrade"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"github.com/xtls/xray-core/transport/internet/stat"
	"github.com/xtls/xray-core/transport/internet/tcp"
	"github.com/xtls/xray-core/transport/internet/tls"
	"github.com/xtls/xray-core/transport/internet/websocket"

	core "github.com/xtls/xray-core" // the scanner package
)

type customDialer struct {
	streamSettings *internet.MemoryStreamConfig
}

func (d *customDialer) Dial(ctx context.Context, dest net.Destination) (stat.Connection, error) {
	return internet.Dial(ctx, dest, d.streamSettings)
}
func (d *customDialer) DestIpAddress() net.IP {
	return nil
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
			ProtocolName: "hysteria2",
			SecurityType: "tls",
			SecuritySettings: &tls.Config{
				ServerName:    sni,
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
			// If sid is invalid hex, we just use it as bytes
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
			Fingerprint: "hellofirefox_102",
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

func main() {
	subUrl := flag.String("sub", "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt", "URL to fetch VLESS subscription from or local file path")
	flag.Parse()

	var subData string
	if strings.HasPrefix(*subUrl, "http://") || strings.HasPrefix(*subUrl, "https://") {
		resp, err := http.Get(*subUrl)
		if err != nil {
			panic(err)
		}
		defer resp.Body.Close()
		data, err := io.ReadAll(resp.Body)
		if err != nil {
			panic(err)
		}
		subData = string(data)
	} else {
		data, err := os.ReadFile(*subUrl)
		if err != nil {
			panic(err)
		}
		subData = string(data)
	}

	subData = strings.ReplaceAll(subData, "\r\n", "\n")
	rawLines := strings.Split(subData, "\n")
	var links []string
	for _, line := range rawLines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "vless://") || strings.HasPrefix(line, "hysteria2://") {
			links = append(links, line)
		}
	}

	var wg sync.WaitGroup
	coreScanner := core.NewScanner()

	var successCount int32
	var failCount int32
	
	var successfulLinks []string
	var linksMu sync.Mutex

	sem := make(chan struct{}, 10)

	for _, link := range links {
		wg.Add(1)
		go func(l string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			config, stream, dest, err := parseVlessURL(l)
			if err != nil {
				atomic.AddInt32(&failCount, 1)
				return
			}

			var handler core.ProxyHandler
			if outboundConfig, ok := config.(*vless_outbound.Config); ok {
				h, err := vless_outbound.New(context.Background(), outboundConfig)
				if err != nil || h == nil {
					atomic.AddInt32(&failCount, 1)
					return
				}
				handler = h
			} else if outboundConfig, ok := config.(*hysteria.ClientConfig); ok {
				h, err := hysteria.NewClient(context.Background(), outboundConfig)
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

			name := "Unknown"
			if idx := strings.Index(l, "#"); idx != -1 {
				name, _ = url.QueryUnescape(l[idx+1:])
			}

			targetDest := net.TCPDestination(net.DomainAddress("cp.cloudflare.com"), 443)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			res := coreScanner.TestNode(ctx, handler, dialer, targetDest)
			
			if res.Error == nil {
				atomic.AddInt32(&successCount, 1)
				linksMu.Lock()
				successfulLinks = append(successfulLinks, l)
				linksMu.Unlock()
			} else {
				atomic.AddInt32(&failCount, 1)
				fmt.Printf("[FAILED] %s (%s): %v\n", name, dest.String(), res.Error)
			}
		}(link)
	}

	wg.Wait()
	fmt.Printf("\n--- Scan Complete ---\nSuccess: %d\nFailed/Timeout/Unsupported: %d\n", successCount, failCount)
	if len(successfulLinks) > 0 {
		fmt.Printf("\n--- Working Configs ---\n")
		for _, sl := range successfulLinks {
			fmt.Println(sl)
		}
	}
}
