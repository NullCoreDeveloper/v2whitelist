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
	"github.com/xtls/xray-core/transport/internet/reality"
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
	case "grpc":
		streamSettings.ProtocolSettings = &grpc.Config{
			ServiceName: q.Get("serviceName"),
		}
	default:
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported network type: %s", netType)
	}

	switch security {
	case "tls":
		streamSettings.SecuritySettings = &tls.Config{
			ServerName: q.Get("sni"),
		}
	case "reality":
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
	resp, err := http.Get("https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt")
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	var links []string
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, "vless://") {
			links = append(links, line)
		}
	}

	fmt.Printf("Fetched %d VLESS links. Starting mass scan with concurrency limit of 30...\n", len(links))

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

			res := coreScanner.TestNode(context.Background(), handler, dialer, dest)
			if res.Error == nil {
				atomic.AddInt32(&successCount, 1)
				fmt.Printf("[SUCCESS] %v | %s | %s\n", res.Latency, dest.String(), name)
			} else {
				atomic.AddInt32(&failCount, 1)
			}
		}(link)
	}

	wg.Wait()
	fmt.Printf("\n--- Scan Complete ---\nSuccess: %d\nFailed/Timeout/Unsupported: %d\n", successCount, failCount)
}
