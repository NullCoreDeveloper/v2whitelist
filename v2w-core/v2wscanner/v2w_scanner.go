package v2wscanner

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	stdnet "net"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/kiktor/v2w-core/common/net"
	"github.com/kiktor/v2w-core/common/protocol"
	"github.com/kiktor/v2w-core/common/serial"
	"github.com/kiktor/v2w-core/proxy/hysteria"
	"github.com/kiktor/v2w-core/proxy/hysteria/account"
	"github.com/kiktor/v2w-core/proxy/shadowsocks"
	"github.com/kiktor/v2w-core/proxy/trojan"
	"github.com/kiktor/v2w-core/proxy/vless"
	vless_outbound "github.com/kiktor/v2w-core/proxy/vless/outbound"
	"github.com/kiktor/v2w-core/proxy/vmess"
	vmess_outbound "github.com/kiktor/v2w-core/proxy/vmess/outbound"
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
	opts, links, hasHeader := ParseConfigsWithOptions(configs, int(maxConcurrency))
	if maxConcurrency > 0 && !hasHeader {
		opts.VerifyConcurrency = int(maxConcurrency)
	}

	scanMutex.Lock()
	if scanCancel != nil {
		scanCancel()
	}
	globalCtx, cancelGlobal := context.WithCancel(context.Background())
	scanCancel = cancelGlobal
	scanMutex.Unlock()

	if len(links) == 0 {
		if callback != nil {
			callback.OnScanComplete(0, 0)
		}
		return
	}

	go func() {
		RunScanPipeline(globalCtx, links, opts, ScanEventCallbacks{
			OnSuccess: func(link string, delayMs int64, mode string, _ string) {
				if callback != nil {
					outLink := link
					if hasHeader {
						outLink = appendQueryParam(link, "v2w_checked", mode)
					}
					callback.OnServerSuccess(outLink, delayMs)
				}
			},
			OnComplete: func(totalSuccess int64, totalFailed int64, _ int64) {
				if callback != nil {
					callback.OnScanComplete(totalSuccess, totalFailed)
				}
			},
		})
	}()
}


func decodeBase64Safe(s string) string {
	s = strings.TrimSpace(s)
	if unescaped, err := url.PathUnescape(s); err == nil {
		s = unescaped
	}
	if b, err := base64.StdEncoding.DecodeString(s); err == nil {
		return string(b)
	}
	if b, err := base64.RawStdEncoding.DecodeString(s); err == nil {
		return string(b)
	}
	if b, err := base64.URLEncoding.DecodeString(s); err == nil {
		return string(b)
	}
	if b, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return string(b)
	}
	return s
}

func parseShadowsocksURL(rawURL string) (any, *internet.MemoryStreamConfig, net.Destination, error) {
	trimmed := strings.TrimPrefix(rawURL, "ss://")
	if idx := strings.Index(trimmed, "#"); idx != -1 {
		trimmed = trimmed[:idx]
	}

	var method, password, hostStr, portStr string

	if strings.Contains(trimmed, "@") {
		// SIP002 format: ss://[userinfo]@[host]:[port][/?params]
		parts := strings.SplitN(trimmed, "@", 2)
		userInfo := parts[0]
		hostPortPart := parts[1]

		if idx := strings.Index(hostPortPart, "?"); idx != -1 {
			hostPortPart = hostPortPart[:idx]
		}
		hostPortPart = strings.TrimSuffix(hostPortPart, "/")

		decodedUser := decodeBase64Safe(userInfo)
		if strings.Contains(decodedUser, ":") {
			userParts := strings.SplitN(decodedUser, ":", 2)
			method = userParts[0]
			password = userParts[1]
		} else if strings.Contains(userInfo, ":") {
			userParts := strings.SplitN(userInfo, ":", 2)
			method = userParts[0]
			password = userParts[1]
		} else {
			return nil, nil, net.Destination{}, fmt.Errorf("invalid shadowsocks userinfo: %s", userInfo)
		}

		h, p, err := stdnet.SplitHostPort(hostPortPart)
		if err != nil {
			return nil, nil, net.Destination{}, fmt.Errorf("invalid shadowsocks host:port (%s): %w", hostPortPart, err)
		}
		hostStr = h
		portStr = p
	} else {
		// Legacy format: ss://base64(method:password@host:port)
		decoded := decodeBase64Safe(trimmed)
		if idx := strings.Index(decoded, "?"); idx != -1 {
			decoded = decoded[:idx]
		}
		decoded = strings.TrimSuffix(decoded, "/")

		if !strings.Contains(decoded, "@") {
			return nil, nil, net.Destination{}, fmt.Errorf("invalid legacy shadowsocks format: %s", trimmed)
		}
		parts := strings.SplitN(decoded, "@", 2)
		userParts := strings.SplitN(parts[0], ":", 2)
		if len(userParts) != 2 {
			return nil, nil, net.Destination{}, fmt.Errorf("invalid legacy shadowsocks userinfo: %s", parts[0])
		}
		method = userParts[0]
		password = userParts[1]

		h, p, err := stdnet.SplitHostPort(parts[1])
		if err != nil {
			return nil, nil, net.Destination{}, fmt.Errorf("invalid legacy shadowsocks host:port (%s): %w", parts[1], err)
		}
		hostStr = h
		portStr = p
	}

	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, nil, net.Destination{}, fmt.Errorf("invalid port: %s", portStr)
	}

	cipherType := shadowsocks.CipherTypeFromString(method)
	if cipherType == shadowsocks.CipherType_UNKNOWN {
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported shadowsocks cipher: %s", method)
	}

	dest := net.TCPDestination(net.ParseAddress(hostStr), net.Port(port))

	streamSettings := &internet.MemoryStreamConfig{
		ProtocolName:     "tcp",
		SecurityType:     "none",
		ProtocolSettings: &tcp.Config{},
		SocketSettings: &internet.SocketConfig{
			DomainStrategy: internet.DomainStrategy_USE_IP4,
		},
	}

	account := &shadowsocks.Account{
		CipherType: cipherType,
		Password:   password,
	}

	config := &shadowsocks.ClientConfig{
		Server: &protocol.ServerEndpoint{
			Address: net.NewIPOrDomain(net.ParseAddress(hostStr)),
			Port:    uint32(port),
			User: &protocol.User{
				Account: serial.ToTypedMessage(account),
			},
		},
	}

	return config, streamSettings, dest, nil
}

func tagLinkWithFragment(linkStr string, mode string) string {
	if strings.HasPrefix(linkStr, "vmess://") {
		trimmed := strings.TrimPrefix(linkStr, "vmess://")
		decoded := decodeBase64Safe(trimmed)
		var m map[string]any
		if err := json.Unmarshal([]byte(decoded), &m); err == nil {
			m["v2w_fragment"] = mode
			ps, _ := m["ps"].(string)
			m["ps"] = ps + " ⚡[FRAG:" + strings.ToUpper(mode) + "]"
			if b, err := json.Marshal(m); err == nil {
				return "vmess://" + base64.StdEncoding.EncodeToString(b)
			}
		}
		return linkStr
	}

	u, err := url.Parse(linkStr)
	if err != nil {
		if strings.Contains(linkStr, "#") {
			parts := strings.SplitN(linkStr, "#", 2)
			return fmt.Sprintf("%s&v2w_fragment=%s#%s ⚡[FRAG:%s]", parts[0], mode, parts[1], strings.ToUpper(mode))
		}
		return linkStr + "&v2w_fragment=" + mode
	}

	q := u.Query()
	q.Set("v2w_fragment", mode)
	u.RawQuery = q.Encode()

	fragTag := "⚡[FRAG:" + strings.ToUpper(mode) + "]"
	if u.Fragment != "" {
		u.Fragment = u.Fragment + " " + fragTag
	} else {
		u.Fragment = fragTag
	}
	return u.String()
}

type vmessRawJson struct {
	V    any    `json:"v"`
	Ps   string `json:"ps"`
	Add  string `json:"add"`
	Port any    `json:"port"`
	Id   string `json:"id"`
	Aid  any    `json:"aid"`
	Scy  string `json:"scy"`
	Net  string `json:"net"`
	Type string `json:"type"`
	Host string `json:"host"`
	Path string `json:"path"`
	Tls  string `json:"tls"`
	Sni  string `json:"sni"`
	Alpn string `json:"alpn"`
	Fp   string `json:"fp"`
}

func parseVmessURL(rawURL string) (any, *internet.MemoryStreamConfig, net.Destination, error) {
	trimmed := strings.TrimPrefix(rawURL, "vmess://")
	decoded := decodeBase64Safe(trimmed)
	var vj vmessRawJson
	if err := json.Unmarshal([]byte(decoded), &vj); err != nil {
		return nil, nil, net.Destination{}, fmt.Errorf("invalid vmess json: %w", err)
	}

	serverIP := strings.TrimSpace(vj.Add)
	port := 443
	switch p := vj.Port.(type) {
	case float64:
		port = int(p)
	case string:
		if pi, err := strconv.Atoi(p); err == nil {
			port = pi
		}
	case int:
		port = p
	}

	dest := net.TCPDestination(net.ParseAddress(serverIP), net.Port(port))

	netType := strings.ToLower(strings.TrimSpace(vj.Net))
	if netType == "" || netType == "raw" || netType == "none" {
		netType = "tcp"
	}

	security := strings.ToLower(strings.TrimSpace(vj.Tls))
	if security == "" {
		security = "none"
	}

	streamSettings := &internet.MemoryStreamConfig{
		ProtocolName: netType,
		SecurityType: security,
	}

	host := strings.TrimSpace(vj.Host)
	if host == "" {
		host = strings.TrimSpace(vj.Sni)
	}

	switch netType {
	case "tcp":
		streamSettings.ProtocolName = "tcp"
		streamSettings.ProtocolSettings = &tcp.Config{}
	case "ws", "websocket":
		streamSettings.ProtocolName = "websocket"
		streamSettings.ProtocolSettings = &websocket.Config{
			Path: vj.Path,
			Host: host,
			Header: map[string]string{
				"Host": host,
			},
		}
	case "grpc":
		streamSettings.ProtocolName = "grpc"
		streamSettings.ProtocolSettings = &grpc.Config{
			ServiceName: vj.Path,
		}
	case "httpupgrade":
		streamSettings.ProtocolName = "httpupgrade"
		streamSettings.ProtocolSettings = &httpupgrade.Config{
			Path: vj.Path,
			Host: host,
			Header: map[string]string{
				"Host": host,
			},
		}
	case "xhttp", "splithttp":
		streamSettings.ProtocolName = "splithttp"
		streamSettings.ProtocolSettings = &splithttp.Config{
			Path: vj.Path,
			Host: host,
			Mode: "auto",
		}
	default:
		return nil, nil, net.Destination{}, fmt.Errorf("unsupported network type: %s", netType)
	}

	streamSettings.SocketSettings = &internet.SocketConfig{
		DomainStrategy: internet.DomainStrategy_USE_IP4,
	}

	if security == "tls" {
		streamSettings.SecurityType = "tls"
		var nextProtocol []string
		if vj.Alpn != "" {
			for _, p := range strings.Split(vj.Alpn, ",") {
				nextProtocol = append(nextProtocol, strings.TrimSpace(p))
			}
		}
		if netType == "ws" || netType == "websocket" {
			nextProtocol = []string{"http/1.1"}
		}
		sni := vj.Sni
		if sni == "" {
			sni = vj.Host
		}
		if sni == "" {
			sni = serverIP
		}
		streamSettings.SecuritySettings = &tls.Config{
			ServerName:   sni,
			NextProtocol: nextProtocol,
		}
		if vj.Fp != "" {
			streamSettings.SecuritySettings.(*tls.Config).Fingerprint = vj.Fp
		}
	}

	account := &vmess.Account{
		Id: vj.Id,
		SecuritySettings: &protocol.SecurityConfig{
			Type: protocol.SecurityType_AUTO,
		},
	}

	config := &vmess_outbound.Config{
		Receiver: &protocol.ServerEndpoint{
			Address: net.NewIPOrDomain(net.ParseAddress(serverIP)),
			Port:    uint32(port),
			User: &protocol.User{
				Account: serial.ToTypedMessage(account),
			},
		},
	}

	return config, streamSettings, dest, nil
}

// ParseNodeURL парсит URI узла (VLESS/VMess/Trojan/Shadowsocks/Hysteria2) в конфиг Xray и настройки транспорта.
func ParseNodeURL(rawURL string) (any, *internet.MemoryStreamConfig, net.Destination, error) {
	if strings.HasPrefix(rawURL, "vmess://") {
		return parseVmessURL(rawURL)
	}
	if strings.HasPrefix(rawURL, "ss://") {
		return parseShadowsocksURL(rawURL)
	}
	if strings.HasPrefix(rawURL, "trojan://") {
		u, err := url.Parse(rawURL)
		if err != nil {
			return nil, nil, net.Destination{}, err
		}
		password := u.User.Username()
		serverIP := u.Hostname()
		portStr := u.Port()
		port := 443
		if portStr != "" {
			if p, err := strconv.Atoi(portStr); err == nil {
				port = p
			}
		}

		dest := net.TCPDestination(net.ParseAddress(serverIP), net.Port(port))

		q := u.Query()
		netType := q.Get("type")
		if netType == "" || netType == "raw" || netType == "none" {
			netType = "tcp"
		}

		security := q.Get("security")
		if security == "" {
			security = "tls"
		}

		streamSettings := &internet.MemoryStreamConfig{
			ProtocolName: netType,
			SecurityType: security,
		}

		switch netType {
		case "tcp":
			streamSettings.ProtocolName = "tcp"
			streamSettings.ProtocolSettings = &tcp.Config{}
		case "ws", "websocket":
			streamSettings.ProtocolName = "websocket"
			host := q.Get("host")
			if host == "" {
				host = q.Get("sni")
			}
			streamSettings.ProtocolSettings = &websocket.Config{
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
		case "xhttp", "splithttp":
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
				Xmux: &splithttp.XmuxConfig{
					MaxConnections:   &splithttp.RangeConfig{From: 3, To: 3},
					HMaxRequestTimes: &splithttp.RangeConfig{From: 600, To: 900},
					HMaxReusableSecs: &splithttp.RangeConfig{From: 1800, To: 3000},
				},
			}
			streamSettings.ProtocolSettings = config
		default:
			return nil, nil, net.Destination{}, fmt.Errorf("unsupported network type: %s", netType)
		}

		streamSettings.SocketSettings = &internet.SocketConfig{
			DomainStrategy: internet.DomainStrategy_USE_IP4,
		}

		if security == "tls" {
			streamSettings.SecurityType = "tls"
			alpn := q.Get("alpn")
			var nextProtocol []string
			if alpn != "" {
				for _, p := range strings.Split(alpn, ",") {
					nextProtocol = append(nextProtocol, strings.TrimSpace(p))
				}
			}
			if netType == "ws" || netType == "websocket" {
				nextProtocol = []string{"http/1.1"}
			}
			sni := q.Get("sni")
			if sni == "" {
				sni = q.Get("peer")
			}
			if sni == "" {
				sni = q.Get("host")
			}
			if sni == "" {
				sni = serverIP
			}
			streamSettings.SecuritySettings = &tls.Config{
				ServerName:   sni,
				NextProtocol: nextProtocol,
			}
			if fp := q.Get("fp"); fp != "" {
				streamSettings.SecuritySettings.(*tls.Config).Fingerprint = fp
			}
		}

		account := &trojan.Account{
			Password: password,
		}

		config := &trojan.ClientConfig{
			Server: &protocol.ServerEndpoint{
				Address: net.NewIPOrDomain(net.ParseAddress(serverIP)),
				Port:    uint32(port),
				User: &protocol.User{
					Account: serial.ToTypedMessage(account),
				},
			},
		}

		return config, streamSettings, dest, nil
	}

	if strings.HasPrefix(rawURL, "hysteria2://") || strings.HasPrefix(rawURL, "hy2://") {
		u, err := url.Parse(rawURL)
		if err != nil {
			return nil, nil, net.Destination{}, err
		}
		password := u.User.Username()
		host := u.Hostname()
		portStr := u.Port()
		port, _ := strconv.Atoi(portStr)
		if port <= 0 {
			port = 443
		}
		dest := net.UDPDestination(net.ParseAddress(host), net.Port(port))
		q := u.Query()
		sni := q.Get("sni")
		if sni == "" {
			sni = q.Get("host")
		}
		if sni == "" {
			sni = host
		}
		streamSettings := &internet.MemoryStreamConfig{
			ProtocolName: "hysteria",
			ProtocolSettings: &hysteria_transport.Config{
				Auth: password,
			},
			SecurityType: "tls",
			SecuritySettings: &tls.Config{
				ServerName:   sni,
				NextProtocol: []string{"h3"},
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
		host := q.Get("host")
		if host == "" {
			host = q.Get("sni")
		}
		streamSettings.ProtocolSettings = &websocket.Config{
			Path: q.Get("path"),
			Host: host,
			Header: map[string]string{
				"Host": host,
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
			Xmux: &splithttp.XmuxConfig{
				MaxConnections:   &splithttp.RangeConfig{From: 3, To: 3},
				HMaxRequestTimes: &splithttp.RangeConfig{From: 600, To: 900},
				HMaxReusableSecs: &splithttp.RangeConfig{From: 1800, To: 3000},
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

	streamSettings.SocketSettings = &internet.SocketConfig{
		DomainStrategy: internet.DomainStrategy_USE_IP4,
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
		if netType == "ws" || netType == "websocket" {
			// Gorilla websocket in Xray core requires HTTP/1.1; h2 ALPN negotiation breaks websocket handshake.
			nextProtocol = []string{"http/1.1"}
		}
		sni := q.Get("sni")
		if sni == "" {
			sni = q.Get("host")
		} else if host := q.Get("host"); host != "" && strings.HasSuffix(host, "."+sni) {
			// If SNI is base domain (often blocked by DPI as BL/SNI) while host is a subdomain,
			// prefer the subdomain for SNI. Cloudflare/CDN wildcard certificates cover *.domain.
			sni = host
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
		shortId := make([]byte, 8)
		if sidStr != "" {
			if len(sidStr)%2 != 0 {
				sidStr = "0" + sidStr
			}
			decoded, err := hex.DecodeString(sidStr)
			if err == nil {
				copy(shortId, decoded)
			}
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
