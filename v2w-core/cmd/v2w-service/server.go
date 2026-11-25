package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/kiktor/v2w-core/tunrouter"
)

const (
	DefaultListenAddr = "127.0.0.1:53729"
	DefaultTunIP      = "198.18.0.2"
	DefaultTunGW      = "198.18.0.1"
	DefaultTunMask    = "255.255.0.0"
	DefaultDeviceName = "wintun"
)

type StartTunRequest struct {
	SocksPort     int      `json:"socks_port"`
	ServerIP      string   `json:"server_ip"`
	Gateway       string   `json:"gateway"`
	PhysicalDev   string   `json:"physical_dev"`
	PhysicalIfIdx int      `json:"physical_if_idx,omitempty"`
	Tun2Socks     string   `json:"tun2socks_path"`
	TunIP         string   `json:"tun_ip"`
	TunMask       string   `json:"tun_mask"`
	TunGateway    string   `json:"tun_gateway"`
	DnsServers    []string `json:"dns_servers"`
	TunEngine     string   `json:"tun_engine,omitempty"`
	SocksUser     string   `json:"socks_user,omitempty"`
	SocksPass     string   `json:"socks_pass,omitempty"`
	DirectDNS     string   `json:"direct_dns,omitempty"`
	SplitEnabled  bool     `json:"split_enabled,omitempty"`
	SplitMode     string   `json:"split_mode,omitempty"`
	DirectApps    []string `json:"direct_apps,omitempty"`
	ProxyApps     []string `json:"proxy_apps,omitempty"`
	SelfTestURL   string   `json:"self_test_url,omitempty"`
}

type ApiResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
	Error   string `json:"error,omitempty"`
}

type StatusResponse struct {
	Success    bool   `json:"success"`
	Service    string `json:"service"`
	Version    string `json:"version"`
	TunActive  bool   `json:"tun_active"`
	SocksPort  int    `json:"socks_port,omitempty"`
	ServerIP   string `json:"server_ip,omitempty"`
	TunIP      string `json:"tun_ip,omitempty"`
	UptimeSec  int64  `json:"uptime_sec,omitempty"`
}

type TunServer struct {
	mu              sync.Mutex
	tunActive       bool
	tunCmd          *exec.Cmd
	tunDone         chan struct{}
	tunRouter       *tunrouter.Router
	startedAt       time.Time
	activeConfig    StartTunRequest
	activeTunGW     string
	addedDnsRoutes  []string
	addedDnsGateway string
	httpServer      *http.Server
	shutdownCh      chan struct{}
	shutdownOnce    sync.Once
}

func NewTunServer() *TunServer {
	return &TunServer{
		shutdownCh: make(chan struct{}),
	}
}

func (s *TunServer) ShutdownChan() <-chan struct{} {
	s.mu.Lock()
	ch := s.shutdownCh
	s.mu.Unlock()
	if ch == nil {
		// На случай прямой инициализации структуры без NewTunServer
		closed := make(chan struct{})
		close(closed)
		return closed
	}
	return ch
}

// RequestShutdown останавливает активный TUN, закрывает HTTP IPC сервер
// и сигнализирует сервис-менеджеру о завершении работы службы.
func (s *TunServer) RequestShutdown() {
	s.mu.Lock()
	s.stopTunLocked()
	srv := s.httpServer
	ch := s.shutdownCh
	s.mu.Unlock()

	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = srv.Shutdown(ctx)
		cancel()
	}

	if ch != nil {
		s.shutdownOnce.Do(func() {
			close(ch)
		})
	}
}

func (s *TunServer) Start(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/ping", s.handlePing)
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/tun/status", s.handleStatus)
	mux.HandleFunc("/tun/start", s.handleStartTun)
	mux.HandleFunc("/tun/stop", s.handleStopTun)
	mux.HandleFunc("/service/shutdown", s.handleShutdown)

	server := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	s.mu.Lock()
	s.httpServer = server
	s.mu.Unlock()

	log.Printf("[v2w-service] HTTP IPC server listening on http://%s", addr)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return err
	}
	return nil
}

func (s *TunServer) handlePing(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	active := s.tunActive
	s.mu.Unlock()

	resp := map[string]interface{}{
		"status":     "ok",
		"service":    "v2w-service",
		"version":    "1.2.7",
		"tun_active": active,
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *TunServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var uptime int64
	if s.tunActive && !s.startedAt.IsZero() {
		uptime = int64(time.Since(s.startedAt).Seconds())
	}

	resp := StatusResponse{
		Success:   true,
		Service:   "v2w-service",
		Version:   "1.2.7",
		TunActive: s.tunActive,
		SocksPort: s.activeConfig.SocksPort,
		ServerIP:  s.activeConfig.ServerIP,
		TunIP:     s.activeConfig.TunIP,
		UptimeSec: uptime,
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *TunServer) handleStartTun(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, ApiResponse{Success: false, Error: "POST required"})
		return
	}

	var req StartTunRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, ApiResponse{Success: false, Error: "Invalid JSON: " + err.Error()})
		return
	}

	if req.SocksPort <= 0 {
		req.SocksPort = 10808
	}
	if req.TunIP == "" {
		req.TunIP = DefaultTunIP
	}
	if req.TunMask == "" {
		req.TunMask = DefaultTunMask
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.tunActive {
		log.Printf("[v2w-service] TUN already active, restarting...")
		s.stopTunLocked()
	}

	if err := s.startTunLocked(req); err != nil {
		log.Printf("[v2w-service] Failed to start TUN: %v", err)
		s.stopTunLocked()
		writeJSON(w, http.StatusInternalServerError, ApiResponse{Success: false, Error: err.Error()})
		return
	}

	s.tunActive = true
	s.startedAt = time.Now()
	s.activeConfig = req
	go s.watchSocks(req.SocksPort, s.startedAt)

	log.Printf("[v2w-service] TUN successfully started (socks5://127.0.0.1:%d, tun=%s)", req.SocksPort, req.TunIP)
	writeJSON(w, http.StatusOK, ApiResponse{Success: true, Message: "TUN started successfully"})
}

func (s *TunServer) handleStopTun(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.stopTunLocked()
	log.Printf("[v2w-service] TUN stopped")
	writeJSON(w, http.StatusOK, ApiResponse{Success: true, Message: "TUN stopped"})
}

func (s *TunServer) handleShutdown(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, ApiResponse{Success: false, Error: "method not allowed"})
		return
	}
	log.Printf("[v2w-service] Shutdown requested via IPC")
	s.mu.Lock()
	s.stopTunLocked()
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, ApiResponse{Success: true, Message: "Service shutting down"})
	go s.RequestShutdown()
}

func (s *TunServer) startTunLocked(req StartTunRequest) error {
	// Остатки прошлого запуска (убитая служба/краш) не должны ломать новый запуск.
	// Также удаляем персистентный маршрут если он остался в реестре.
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()

	useCustomRouter := strings.EqualFold(strings.TrimSpace(req.TunEngine), "v2w-tun") || req.SplitEnabled
	if strings.EqualFold(strings.TrimSpace(req.TunEngine), "tun2socks") {
		useCustomRouter = false
	}

	if useCustomRouter {
		directDNS := strings.TrimSpace(req.DirectDNS)
		if directDNS == "" {
			if phys := detectWindowsPhysicalDNS(req.DnsServers, req.ServerIP, req.Gateway); len(phys) > 0 {
				directDNS = phys[0]
			}
		}
		routerCfg := tunrouter.RouterConfig{
			DeviceName:    DefaultDeviceName,
			MTU:           1500,
			SocksAddr:     fmt.Sprintf("127.0.0.1:%d", req.SocksPort),
			SocksUser:     req.SocksUser,
			SocksPass:     req.SocksPass,
			PhysicalIface: req.PhysicalDev,
			DirectDNS:     directDNS,
			Split: tunrouter.SplitConfig{
				Enabled:    req.SplitEnabled,
				Mode:       req.SplitMode,
				DirectApps: req.DirectApps,
				ProxyApps:  req.ProxyApps,
			},
		}
		router, err := tunrouter.StartRouter(routerCfg)
		if err != nil {
			return fmt.Errorf("failed to start v2w-tun router: %w", err)
		}
		s.tunRouter = router
		log.Printf("[v2w-service] Started built-in v2w-tun router (split=%v, mode=%s, direct=%d, proxy=%d)",
			req.SplitEnabled, req.SplitMode, len(req.DirectApps), len(req.ProxyApps))
	} else {
		// Служба работает от SYSTEM: путь из запроса НЕ используется (иначе любой локальный процесс
		// мог бы запустить произвольный exe). Берём только tun2socks рядом с v2w-service.exe.
		tun2socksBin := findTun2SocksBin()
		if tun2socksBin == "" || !fileExists(tun2socksBin) {
			return fmt.Errorf("tun2socks executable not found (provided: %s)", req.Tun2Socks)
		}

		binDir := filepath.Dir(tun2socksBin)
		exec.Command("taskkill", "/F", "/IM", filepath.Base(tun2socksBin)).Run()
		time.Sleep(500 * time.Millisecond)

		args := []string{
			"-device", DefaultDeviceName,
			"-proxy", fmt.Sprintf("socks5://127.0.0.1:%d", req.SocksPort),
			"-loglevel", "info",
		}
		cmd := exec.Command(tun2socksBin, args...)
		cmd.Dir = binDir
		if lf := openLogFile("tun2socks.log"); lf != nil {
			cmd.Stdout = lf
			cmd.Stderr = lf
		}

		if err := cmd.Start(); err != nil {
			return fmt.Errorf("failed to launch tun2socks: %w", err)
		}
		s.tunCmd = cmd
		done := make(chan struct{})
		s.tunDone = done
		go func() {
			_ = cmd.Wait()
			close(done)
			// tun2socks упал сам — снимаем маршруты, иначе весь трафик уходит в «чёрную дыру»
			s.mu.Lock()
			defer s.mu.Unlock()
			if s.tunCmd == cmd {
				log.Printf("[v2w-service] tun2socks неожиданно завершился — откат маршрутов")
				s.stopTunLocked()
			}
		}()
	}

	// Ожидаем появления активного (connected) интерфейса wintun в системе (до 5 секунд)
	var wintunIdx int = -1
	var wintunConnected bool
	for i := 0; i < 34; i++ {
		idx, connected := getWindowsInterfaceInfo(DefaultDeviceName)
		if idx > 0 {
			wintunIdx = idx
			wintunConnected = connected
			if connected {
				break
			}
		}
		time.Sleep(150 * time.Millisecond)
	}
	if wintunIdx <= 0 {
		return fmt.Errorf("адаптер %s не появился за 5 сек — TUN не поднят, маршруты не изменены", DefaultDeviceName)
	}
	if !wintunConnected {
		log.Printf("[v2w-service] Warning: %s (index %d) ещё не в состоянии connected после 5 сек, продолжаем настройку", DefaultDeviceName, wintunIdx)
	} else {
		log.Printf("[v2w-service] Detected %s with interface index %d (connected)", DefaultDeviceName, wintunIdx)
	}

	tunIP, tunMask, tunGW := normalizeTunNetwork(req.TunIP, req.TunMask, req.TunGateway)
	s.activeTunGW = tunGW
	log.Printf("[v2w-service] Configuring %s: ip=%s mask=%s gw=%s", DefaultDeviceName, tunIP, tunMask, tunGW)

	// Назначаем статический IP и маску на интерфейс wintun (БЕЗ gateway в netsh — иначе
	// Windows создаёт персистентный маршрут 0.0.0.0/0 в реестре, который остаётся после остановки TUN
	// и при следующем старте ломает интернет ещё до запуска tun2socks).
	netshAddrArgs := []string{
		"interface", "ipv4", "set", "address",
		fmt.Sprintf("name=%s", DefaultDeviceName),
		"source=static",
		fmt.Sprintf("addr=%s", tunIP),
		fmt.Sprintf("mask=%s", tunMask),
		"gateway=none",
	}
	if out, err := exec.Command("netsh", netshAddrArgs...).CombinedOutput(); err != nil {
		log.Printf("[v2w-service] netsh set address warning: %s (%v)", string(out), err)
	}

	// Устанавливаем наивысший приоритет метрики интерфейса Wintun (Metric = 1)
	exec.Command("netsh", "interface", "ipv4", "set", "interface", fmt.Sprintf("name=%s", DefaultDeviceName), "metric=1").Run()
	exec.Command("netsh", "interface", "ipv4", "set", "subinterface", fmt.Sprintf("name=%s", DefaultDeviceName), "mtu=1500", "metric=1").Run()

	// Назначаем DNS серверы на интерфейс wintun
	dnsServers := req.DnsServers
	if len(dnsServers) == 0 {
		dnsServers = []string{"1.1.1.1", "8.8.8.8"}
	}

	for i, dns := range dnsServers {
		if strings.TrimSpace(dns) == "" {
			continue
		}
		var dnsArgs []string
		if i == 0 {
			dnsArgs = []string{
				"interface", "ipv4", "set", "dnsservers",
				fmt.Sprintf("name=%s", DefaultDeviceName),
				"source=static",
				fmt.Sprintf("address=%s", dns),
				"validate=no",
			}
		} else {
			dnsArgs = []string{
				"interface", "ipv4", "add", "dnsservers",
				fmt.Sprintf("name=%s", DefaultDeviceName),
				fmt.Sprintf("address=%s", dns),
				"validate=no",
			}
		}
		exec.Command("netsh", dnsArgs...).Run()
	}

	// Добавляем прямой маршрут к серверу прокси (чтобы трафик Xray не попадал в петлю через TUN)
	if addArgs := buildServerRouteAddArgs(req.ServerIP, req.Gateway, req.PhysicalIfIdx); len(addArgs) > 0 {
		out, err := exec.Command("route", addArgs...).CombinedOutput()
		if err != nil {
			log.Printf("[v2w-service] route add server warning: %s (%v)", string(out), err)
		} else {
			log.Printf("[v2w-service] Direct route added for proxy server: %s -> %s (if=%d)", req.ServerIP, req.Gateway, req.PhysicalIfIdx)
		}
	}

	// Добавляем прямые маршруты к DNS-серверам физического адаптера (защита от DNS-ловушки провайдера при route 0/1)
	s.addedDnsRoutes = nil
	s.addedDnsGateway = req.Gateway
	if req.Gateway != "" && !isOnLinkGateway(req.Gateway) {
		physDNS := detectWindowsPhysicalDNS(dnsServers, req.ServerIP, req.Gateway)
		for _, dnsIP := range physDNS {
			out, err := exec.Command("route", "add", dnsIP, "mask", "255.255.255.255", req.Gateway, "metric", "1").CombinedOutput()
			if err != nil {
				log.Printf("[v2w-service] route add physical DNS %s warning: %s (%v)", dnsIP, string(out), err)
			} else {
				s.addedDnsRoutes = append(s.addedDnsRoutes, dnsIP)
				log.Printf("[v2w-service] Direct route added for physical DNS: %s -> %s", dnsIP, req.Gateway)
			}
		}
	}

	// Маршрут для loopback: tun2socks работает как SYSTEM и подключается к Xray по 127.0.0.1.
	// Без явного маршрута 127.0.0.0/8 трафик к loopback может уходить через wintun (метрика 1 < loopback 331),
	// что приводит к "unsupported method" — tun2socks не может достучаться до Xray SOCKS5.

	// Ожидаем завершения DAD и перехода IPv4-адреса интерфейса в состояние Preferred,
	// иначе исходящие соединения сразу после route add падают с WSAEHOSTUNREACH.
	waitForIPAddressReady(wintunIdx, tunIP, 4*time.Second)

	// Перепроверяем актуальный ifIndex непосредственно перед добавлением маршрутов
	if actualIdx, _ := getWindowsInterfaceInfo(DefaultDeviceName); actualIdx > 0 && actualIdx != wintunIdx {
		log.Printf("[v2w-service] %s interface index changed %d -> %d", DefaultDeviceName, wintunIdx, actualIdx)
		wintunIdx = actualIdx
	}

	// Очищаем старые маршруты во избежание коллизий (включая возможный предыдущий шлюз)
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0").Run()
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", tunGW).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", tunGW).Run()

	// Маршрутизируем весь входящий IPv4 трафик в wintun (0.0.0.0/1 и 128.0.0.0/1) с указанием ifIndex
	var r1Args, r2Args []string
	if wintunIdx > 0 {
		r1Args = []string{"add", "0.0.0.0", "mask", "128.0.0.0", tunGW, "metric", "1", "if", fmt.Sprintf("%d", wintunIdx)}
		r2Args = []string{"add", "128.0.0.0", "mask", "128.0.0.0", tunGW, "metric", "1", "if", fmt.Sprintf("%d", wintunIdx)}
	} else {
		r1Args = []string{"add", "0.0.0.0", "mask", "128.0.0.0", tunGW, "metric", "1"}
		r2Args = []string{"add", "128.0.0.0", "mask", "128.0.0.0", tunGW, "metric", "1"}
	}

	out1, err1 := exec.Command("route", r1Args...).CombinedOutput()
	out2, err2 := exec.Command("route", r2Args...).CombinedOutput()
	if err1 != nil || err2 != nil {
		log.Printf("[v2w-service] route add 0/1 warning: %s (%v) / %s (%v)", string(out1), err1, string(out2), err2)
		return fmt.Errorf("failed to add IPv4 routes into wintun: %s %s", string(out1), string(out2))
	}

	// IPv6 НЕ перехватываем: tun2socks принимает TCP-подключения локально, даже если дальше
	// IPv6 недоступен, из-за чего Happy Eyeballs не переключается на IPv4 и сайты зависают.

	// Сброс DNS кэша Windows
	exec.Command("ipconfig", "/flushdns").Run()

	// Самопроверка: если через туннель реально нет интернета — не оставляем систему «без соединения»
	if err := selfTest(dnsServers, req.SelfTestURL); err != nil {
		dumpDiagnostics()
		return fmt.Errorf("SELFTEST_FAILED: через TUN нет интернета (%v). Подробности: %s", err, logDir())
	}
	return nil
}

func logDir() string {
	// Portable-сборка: рядом со службой лежит файл ".portable" — логи кладём в папку приложения.
	// Установленная версия (MSI/NSIS): логи в C:\ProgramData\v2whitelist\logs\
	exe, err := os.Executable()
	if err == nil {
		dir := filepath.Dir(exe)
		if fileExists(filepath.Join(dir, ".portable")) {
			return filepath.Join(dir, "logs")
		}
	}
	base := os.Getenv("ProgramData")
	if base == "" {
		base = os.TempDir()
	}
	return filepath.Join(base, "v2whitelist", "logs")
}

func openLogFile(name string) *os.File {
	dir := logDir()
	_ = os.MkdirAll(dir, 0o755)
	f, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return nil
	}
	return f
}

// selfTest: HTTP-запрос через системную маршрутизацию (т.е. через wintun -> tun2socks -> Xray).
// Проверяет и DNS (домен), и реальную передачу данных (TCP-handshake в tun2socks успешен всегда).
func detectWindowsPhysicalDNS(tunDNS []string, excludeIPs ...string) []string {
	out, err := exec.Command("netsh", "interface", "ipv4", "show", "dnsservers").CombinedOutput()
	if err != nil {
		return nil
	}
	return parseNetshDnsServers(string(out), tunDNS, excludeIPs...)
}

func parseNetshDnsServers(output string, tunDNS []string, excludeIPs ...string) []string {
	excluded := make(map[string]bool)
	for _, ip := range tunDNS {
		if trimmed := strings.TrimSpace(ip); trimmed != "" {
			excluded[trimmed] = true
		}
	}
	for _, ip := range excludeIPs {
		if trimmed := strings.TrimSpace(ip); trimmed != "" {
			excluded[trimmed] = true
		}
	}

	var result []string
	seen := make(map[string]bool)
	skipSection := false

	for _, rawLine := range strings.Split(output, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" {
			skipSection = false
			continue
		}
		// Заголовок секции содержит имя интерфейса в кавычках, напр. Configuration for interface "wintun"
		if strings.Contains(line, "\"") {
			lower := strings.ToLower(line)
			skipSection = strings.Contains(lower, "\"wintun\"") || strings.Contains(lower, "loopback")
		}
		if skipSection {
			continue
		}
		for _, field := range strings.Fields(line) {
			tok := strings.Trim(field, "(),;")
			parsed := net.ParseIP(tok)
			if parsed == nil {
				continue
			}
			v4 := parsed.To4()
			if v4 == nil {
				continue
			}
			ipStr := v4.String()
			if v4.IsLoopback() || v4.IsUnspecified() || v4.IsLinkLocalUnicast() || v4.IsMulticast() || isLocalIP(ipStr) {
				continue
			}
			if excluded[ipStr] || seen[ipStr] {
				continue
			}
			seen[ipStr] = true
			result = append(result, ipStr)
		}
	}
	return result
}

func normalizeCustomSelfTestURL(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	lower := strings.ToLower(trimmed)
	if !strings.HasPrefix(lower, "http://") && !strings.HasPrefix(lower, "https://") {
		return ""
	}
	return trimmed
}

func selfTest(dnsServers []string, customURL string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()

	var targetDNS []string
	for _, d := range dnsServers {
		d = strings.TrimSpace(d)
		if d != "" {
			targetDNS = append(targetDNS, d)
		}
	}
	if len(targetDNS) == 0 {
		targetDNS = []string{"1.1.1.1", "8.8.8.8"}
	}

	tunResolver := &net.Resolver{
		PreferGo: true,
		Dial: func(rctx context.Context, network, _ string) (net.Conn, error) {
			dialer := &net.Dialer{Timeout: 2 * time.Second}
			var lastErr error
			for _, dnsIP := range targetDNS {
				conn, err := dialer.DialContext(rctx, network, net.JoinHostPort(dnsIP, "53"))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}

	ipTransport := &http.Transport{
		DisableKeepAlives: true,
		DialContext: func(dctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err == nil && strings.EqualFold(host, "cp.cloudflare.com") {
				// Проверяем HTTP через туннель без зависимости от UDP:53
				addr = net.JoinHostPort("104.16.132.229", port)
			}
			return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(dctx, network, addr)
		},
	}
	ipClient := &http.Client{
		Timeout:   3500 * time.Millisecond,
		Transport: ipTransport,
	}

	domainTransport := &http.Transport{
		DisableKeepAlives: true,
		DialContext: (&net.Dialer{
			Timeout:  3 * time.Second,
			Resolver: tunResolver,
		}).DialContext,
	}
	domainClient := &http.Client{
		Timeout:   3500 * time.Millisecond,
		Transport: domainTransport,
	}

	type probe struct {
		url    string
		client *http.Client
	}
	var probes []probe
	if custom := normalizeCustomSelfTestURL(customURL); custom != "" {
		probes = append(probes, probe{url: custom, client: domainClient})
	}
	probes = append(probes,
		probe{url: "http://1.1.1.1/", client: ipClient},
		probe{url: "http://1.0.0.1/", client: ipClient},
		probe{url: "http://cp.cloudflare.com/generate_204", client: ipClient},
		probe{url: "http://www.msftconnecttest.com/connecttest.txt", client: domainClient},
	)

	var lastErr error
	for round := 0; round < 2; round++ {
		if ctx.Err() != nil {
			break
		}
		if round == 0 {
			time.Sleep(300 * time.Millisecond)
		} else {
			time.Sleep(600 * time.Millisecond)
		}
		for _, p := range probes {
			if ctx.Err() != nil {
				if lastErr == nil {
					lastErr = ctx.Err()
				}
				return lastErr
			}
			req, reqErr := http.NewRequestWithContext(ctx, http.MethodGet, p.url, nil)
			if reqErr != nil {
				continue
			}
			resp, err := p.client.Do(req)
			if err != nil {
				lastErr = fmt.Errorf("%s: %v", p.url, err)
				log.Printf("[v2w-service] selftest (round %d) %s: %v", round+1, p.url, err)
				continue
			}
			_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
			resp.Body.Close()
			if resp.StatusCode > 0 {
				log.Printf("[v2w-service] selftest %s: HTTP %d — OK", p.url, resp.StatusCode)
				return nil
			}
		}
	}
	if lastErr == nil && ctx.Err() != nil {
		lastErr = ctx.Err()
	}
	return lastErr
}

func dumpDiagnostics() {
	f := openLogFile("diagnostics.log")
	if f == nil {
		return
	}
	defer f.Close()
	for _, c := range [][]string{
		{"route", "print", "-4"},
		{"netsh", "interface", "ipv4", "show", "interfaces"},
		{"netsh", "interface", "ipv4", "show", "dnsservers"},
		{"ipconfig", "/all"},
	} {
		fmt.Fprintf(f, "\n===== %s =====\n", strings.Join(c, " "))
		out, _ := exec.Command(c[0], c[1:]...).CombinedOutput()
		f.Write(out)
	}
}

func (s *TunServer) stopTunLocked() {
	tunGW := s.activeTunGW
	if tunGW == "" {
		tunGW = DefaultTunGW
	}

	// Удаляем маршруты из таблицы
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", tunGW).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", tunGW).Run()
	if tunGW != DefaultTunGW {
		exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()
		exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()
	}

	// Удаляем IPv6 маршруты
	exec.Command("netsh", "interface", "ipv6", "delete", "route", "::/1", fmt.Sprintf("interface=%s", DefaultDeviceName)).Run()
	exec.Command("netsh", "interface", "ipv6", "delete", "route", "8000::/1", fmt.Sprintf("interface=%s", DefaultDeviceName)).Run()

	if delArgs := buildServerRouteDeleteArgs(s.activeConfig.ServerIP, s.activeConfig.Gateway); len(delArgs) > 0 {
		exec.Command("route", delArgs...).Run()
	}
	for _, dnsIP := range s.addedDnsRoutes {
		if s.addedDnsGateway != "" {
			exec.Command("route", "delete", dnsIP, "mask", "255.255.255.255", s.addedDnsGateway).Run()
		} else {
			exec.Command("route", "delete", dnsIP, "mask", "255.255.255.255").Run()
		}
	}
	s.addedDnsRoutes = nil
	s.addedDnsGateway = ""

	// Сброс DNS кэша Windows
	exec.Command("ipconfig", "/flushdns").Run()

	// Завершаем процесс tun2socks или встроенный v2w-tun роутер
	if s.tunRouter != nil {
		_ = s.tunRouter.Close()
		s.tunRouter = nil
	}
	if s.tunCmd != nil && s.tunCmd.Process != nil {
		_ = s.tunCmd.Process.Kill()
		if s.tunDone != nil {
			select {
			case <-s.tunDone:
			case <-time.After(3 * time.Second):
			}
		}
		s.tunCmd = nil
		s.tunDone = nil
	}

	s.tunActive = false
	s.startedAt = time.Time{}
	s.activeConfig = StartTunRequest{}
	s.activeTunGW = ""
}

func writeJSON(w http.ResponseWriter, code int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(data)
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func findTun2SocksBin() string {
	exe, err := os.Executable()
	if err == nil {
		dir := filepath.Dir(exe)
		candidates := []string{
			filepath.Join(dir, "tun2socks.exe"),
			filepath.Join(dir, "bin", "tun2socks.exe"),
			filepath.Join(dir, "..", "bin", "tun2socks.exe"),
			filepath.Join(dir, "tun2socks"),
		}
		for _, c := range candidates {
			if fileExists(c) {
				return c
			}
		}
	}
	return ""
}

func isLocalIP(ipStr string) bool {
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified()
}

func isOnLinkGateway(gw string) bool {
	trimmed := strings.TrimSpace(gw)
	return trimmed == "" || trimmed == "0.0.0.0" || strings.EqualFold(trimmed, "On-link")
}

func buildServerRouteAddArgs(serverIP, gateway string, physicalIfIdx int) []string {
	srv := strings.TrimSpace(serverIP)
	gw := strings.TrimSpace(gateway)
	if srv == "" || isLocalIP(srv) {
		return nil
	}
	if isOnLinkGateway(gw) {
		if physicalIfIdx <= 0 {
			return nil
		}
		return []string{"add", srv, "mask", "255.255.255.255", "0.0.0.0", "metric", "1", "if", fmt.Sprintf("%d", physicalIfIdx)}
	}
	return []string{"add", srv, "mask", "255.255.255.255", gw, "metric", "1"}
}

func buildServerRouteDeleteArgs(serverIP, gateway string) []string {
	srv := strings.TrimSpace(serverIP)
	gw := strings.TrimSpace(gateway)
	if srv == "" || isLocalIP(srv) {
		return nil
	}
	if isOnLinkGateway(gw) {
		return []string{"delete", srv, "mask", "255.255.255.255"}
	}
	return []string{"delete", srv, "mask", "255.255.255.255", gw}
}

func normalizeTunNetwork(rawIP, rawMask, rawGW string) (string, string, string) {
	ipStr := strings.TrimSpace(rawIP)
	if idx := strings.IndexByte(ipStr, '/'); idx != -1 {
		ipStr = strings.TrimSpace(ipStr[:idx])
	}
	parsedIP := net.ParseIP(ipStr).To4()
	if parsedIP == nil {
		ipStr = DefaultTunIP
		parsedIP = net.ParseIP(ipStr).To4()
	}

	maskStr := strings.TrimSpace(rawMask)
	if net.ParseIP(maskStr).To4() == nil {
		if strings.HasPrefix(ipStr, "198.18.") {
			maskStr = DefaultTunMask
		} else {
			maskStr = "255.255.255.0"
		}
	}

	gwStr := strings.TrimSpace(rawGW)
	if net.ParseIP(gwStr).To4() == nil || gwStr == ipStr {
		gwStr = deriveTunGateway(parsedIP)
	}
	return ipStr, maskStr, gwStr
}

func deriveTunGateway(ip net.IP) string {
	v4 := ip.To4()
	if v4 == nil {
		return DefaultTunGW
	}
	gw := make(net.IP, 4)
	copy(gw, v4)
	if gw[3] == 1 {
		gw[3] = 2
	} else {
		gw[3] = 1
	}
	return gw.String()
}

func getWindowsInterfaceIndex(name string) int {
	idx, _ := getWindowsInterfaceInfo(name)
	return idx
}

func getWindowsInterfaceInfo(name string) (int, bool) {
	out, err := exec.Command("netsh", "interface", "ipv4", "show", "interfaces").Output()
	if err != nil {
		return -1, false
	}
	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "---") || strings.HasPrefix(line, "Idx") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 5 {
			var idx int
			if _, err := fmt.Sscanf(fields[0], "%d", &idx); err == nil {
				ifName := strings.Trim(strings.Join(fields[4:], " "), "\"")
				if strings.EqualFold(ifName, name) {
					return idx, strings.EqualFold(fields[3], "connected")
				}
			}
		}
	}
	return -1, false
}

// waitForIPAddressReady ждёт завершения DAD и перехода IPv4-адреса в состояние Preferred.
func waitForIPAddressReady(ifIdx int, ip string, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	psCmd := fmt.Sprintf(
		"(Get-NetIPAddress -InterfaceIndex %d -AddressFamily IPv4 -IPAddress '%s' -ErrorAction SilentlyContinue | Select-Object -First 1).AddressState",
		ifIdx, ip,
	)
	for time.Now().Before(deadline) {
		out, err := exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCmd).Output()
		state := strings.TrimSpace(string(out))
		if err == nil && strings.EqualFold(state, "Preferred") {
			log.Printf("[v2w-service] IPv4 %s on ifIndex %d is Preferred", ip, ifIdx)
			return
		}
		if err != nil {
			// Fallback если PowerShell недоступен
			netshOut, _ := exec.Command("netsh", "interface", "ipv4", "show", "addresses", fmt.Sprintf("name=%d", ifIdx)).Output()
			if strings.Contains(string(netshOut), ip) {
				time.Sleep(500 * time.Millisecond)
				return
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
	log.Printf("[v2w-service] Warning: timed out waiting for IPv4 %s on ifIndex %d to become Preferred", ip, ifIdx)
}




// watchSocks: если SOCKS-порт Xray пропал (приложение закрыто/упало), а TUN остался —
// интернет полностью ломается. Через ~15 сек без ответа снимаем TUN автоматически.
func (s *TunServer) watchSocks(port int, started time.Time) {
	fails := 0
	for {
		time.Sleep(5 * time.Second)
		s.mu.Lock()
		current := s.tunActive && s.startedAt.Equal(started)
		s.mu.Unlock()
		if !current {
			return
		}
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 1500*time.Millisecond)
		if err == nil {
			c.Close()
			fails = 0
			continue
		}
		fails++
		if fails >= 3 {
			s.mu.Lock()
			if s.tunActive && s.startedAt.Equal(started) {
				log.Printf("[v2w-service] SOCKS 127.0.0.1:%d недоступен — автоотключение TUN", port)
				s.stopTunLocked()
			}
			s.mu.Unlock()
			return
		}
	}
}
