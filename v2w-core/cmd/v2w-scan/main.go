package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
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

func parseVlessURL(rawURL string) (*vless_outbound.Config, *internet.MemoryStreamConfig, net.Destination, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, nil, net.Destination{}, err
	}

	if u.Scheme != "vless" {
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported protocol: %s", u.Scheme)
	}

	uuid := u.User.Username()
	host := u.Hostname()
	portStr := u.Port()
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, nil, net.Destination{}, err
	}

	dest := net.TCPDestination(net.ParseAddress(host), net.Port(port))

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
		config := &splithttp.Config{
			Path: q.Get("path"),
			Host: q.Get("host"),
			Mode: q.Get("mode"),
			Headers: map[string]string{
				"Host": q.Get("host"),
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
		streamSettings.ProtocolSettings = &httpupgrade.Config{
			Path: q.Get("path"),
			Host: q.Get("host"),
			Header: map[string]string{
				"Host": q.Get("host"),
			},
		}
	case "grpc":
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
		streamSettings.SecuritySettings = &tls.Config{
			ServerName:   q.Get("sni"),
			NextProtocol: nextProtocol,
		}
	case "reality":
		streamSettings.SecurityType = "reality"
		pbkBytes, _ := base64.RawURLEncoding.DecodeString(q.Get("pbk"))
		shortId, _ := hex.DecodeString(q.Get("sid"))
		streamSettings.SecuritySettings = &reality.Config{
			Show:        false,
			Dest:        q.Get("sni") + ":443",
			Type:        netType,
			ServerNames: []string{q.Get("sni")},
			ShortIds:    [][]byte{shortId},
			PublicKey:   pbkBytes,
			Fingerprint: q.Get("fp"),
		}
	}

	account := &vless.Account{
		Id:         uuid,
		Encryption: q.Get("encryption"),
		Flow:       q.Get("flow"),
	}

	config := &vless_outbound.Config{
		Vnext: &protocol.ServerEndpoint{
			Address: net.NewIPOrDomain(net.ParseAddress(host)),
			Port:    uint32(port),
			User: &protocol.User{
				Account: serial.ToTypedMessage(account),
			},
		},
	}

	return config, streamSettings, dest, nil
}

func main() {
	resp, err := http.Get("https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt")
	if err != nil {
		panic(err)
	}
	var links []string
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, "vless://") {
			links = append(links, line)
		}
	}

	var wg sync.WaitGroup
	coreScanner := core.NewScanner()

	var successCount int32
	var failCount int32

	sem := make(chan struct{}, 30)

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

			handler, err := vless_outbound.New(context.Background(), config)
			if err != nil {
				atomic.AddInt32(&failCount, 1)
				return
			}

			dialer := &customDialer{streamSettings: stream}

			name := "Unknown"
			if idx := strings.Index(l, "#"); idx != -1 {
				name, _ = url.QueryUnescape(l[idx+1:])
			}

			targetDest := net.TCPDestination(net.DomainAddress("cp.cloudflare.com"), 80)
			res := coreScanner.TestNode(context.Background(), handler, dialer, targetDest)
			
			if res.Error == nil {
				atomic.AddInt32(&successCount, 1)
				fmt.Printf("[SUCCESS] %v | %s | %s\n", res.Latency, dest.String(), name)
			} else {
				atomic.AddInt32(&failCount, 1)
				fmt.Printf("[FAILED] %s (%v)\n", dest.String(), res.Error)
			}
		}(link)
	}

	wg.Wait()
	fmt.Printf("\n--- Scan Complete ---\nSuccess: %d\nFailed/Timeout/Unsupported: %d\n", successCount, failCount)
}
