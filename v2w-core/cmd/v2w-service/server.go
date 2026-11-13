package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	DefaultListenAddr = "127.0.0.1:53729"
	DefaultTunIP      = "198.18.0.2"
	DefaultTunGW      = "198.18.0.1"
	DefaultTunMask    = "255.255.0.0"
	DefaultDeviceName = "wintun"
)

type StartTunRequest struct {
	SocksPort    int      `json:"socks_port"`
	ServerIP     string   `json:"server_ip"`
	Gateway      string   `json:"gateway"`
	PhysicalDev  string   `json:"physical_dev"`
	Tun2Socks    string   `json:"tun2socks_path"`
	TunIP        string   `json:"tun_ip"`
	TunMask      string   `json:"tun_mask"`
	DnsServers   []string `json:"dns_servers"`
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
	mu           sync.Mutex
	tunActive    bool
	tunCmd       *exec.Cmd
	tunDone      chan struct{}
	startedAt    time.Time
	activeConfig StartTunRequest
}

func NewTunServer() *TunServer {
	return &TunServer{}
}

func (s *TunServer) Start(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/ping", s.handlePing)
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/tun/status", s.handleStatus)
	mux.HandleFunc("/tun/start", s.handleStartTun)
	mux.HandleFunc("/tun/stop", s.handleStopTun)

	server := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 15 * time.Second,
	}

	log.Printf("[v2w-service] HTTP IPC server listening on http://%s", addr)
	return server.ListenAndServe()
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

func (s *TunServer) startTunLocked(req StartTunRequest) error {
	// Служба работает от SYSTEM: путь из запроса НЕ используется (иначе любой локальный процесс
	// мог бы запустить произвольный exe). Берём только tun2socks рядом с v2w-service.exe.
	tun2socksBin := findTun2SocksBin()
	if tun2socksBin == "" || !fileExists(tun2socksBin) {
		return fmt.Errorf("tun2socks executable not found (provided: %s)", req.Tun2Socks)
	}

	binDir := filepath.Dir(tun2socksBin)

	args := []string{
		"-device", DefaultDeviceName,
		"-proxy", fmt.Sprintf("socks5://127.0.0.1:%d", req.SocksPort),
		"-loglevel", "warning",
	}

	// Остатки прошлого запуска (убитая служба/краш) не должны ломать новый запуск
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", DefaultTunGW).Run()
	exec.Command("taskkill", "/F", "/IM", filepath.Base(tun2socksBin)).Run()
	time.Sleep(300 * time.Millisecond)

	cmd := exec.Command(tun2socksBin, args...)
	cmd.Dir = binDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

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

	// Ожидаем появления интерфейса wintun в системе (до 3 секунд)
	var wintunIdx int = -1
	for i := 0; i < 20; i++ {
		wintunIdx = getWindowsInterfaceIndex(DefaultDeviceName)
		if wintunIdx > 0 {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
	if wintunIdx <= 0 {
		return fmt.Errorf("адаптер %s не появился за 3 сек — TUN не поднят, маршруты не изменены", DefaultDeviceName)
	} else {
		log.Printf("[v2w-service] Detected %s with interface index %d", DefaultDeviceName, wintunIdx)
	}

	tunIP := req.TunIP
	if tunIP == "" {
		tunIP = DefaultTunIP
	}
	tunGW := DefaultTunGW
	if tunIP == tunGW {
		tunIP = "198.18.0.2"
		tunGW = "198.18.0.1"
	}
	tunMask := req.TunMask
	if tunMask == "" {
		tunMask = DefaultTunMask
	}

	// Назначаем статический IP, маску и шлюз на интерфейс wintun через netsh (для NCSI / NLA)
	netshAddrArgs := []string{
		"interface", "ipv4", "set", "address",
		fmt.Sprintf("name=%s", DefaultDeviceName),
		"source=static",
		fmt.Sprintf("addr=%s", tunIP),
		fmt.Sprintf("mask=%s", tunMask),
		fmt.Sprintf("gateway=%s", tunGW),
		"gwmetric=1",
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
	if req.ServerIP != "" && req.Gateway != "" && !isLocalIP(req.ServerIP) {
		out, err := exec.Command("route", "add", req.ServerIP, "mask", "255.255.255.255", req.Gateway, "metric", "1").CombinedOutput()
		if err != nil {
			log.Printf("[v2w-service] route add server warning: %s (%v)", string(out), err)
		} else {
			log.Printf("[v2w-service] Direct route added for proxy server: %s -> %s", req.ServerIP, req.Gateway)
		}
	}

	// Очищаем старые маршруты во избежание коллизий
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

	// Блокировка утечки IPv6 трафика мимо туннеля (перенаправление IPv6 в Wintun)
	exec.Command("netsh", "interface", "ipv6", "add", "route", "::/1", fmt.Sprintf("interface=%s", DefaultDeviceName), "metric=1", "store=active").Run()
	exec.Command("netsh", "interface", "ipv6", "add", "route", "8000::/1", fmt.Sprintf("interface=%s", DefaultDeviceName), "metric=1", "store=active").Run()

	// Сброс DNS кэша Windows
	exec.Command("ipconfig", "/flushdns").Run()

	return nil
}

func (s *TunServer) stopTunLocked() {
	tunGW := DefaultTunGW

	// Удаляем маршруты из таблицы
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", tunGW).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", tunGW).Run()

	// Удаляем IPv6 маршруты
	exec.Command("netsh", "interface", "ipv6", "delete", "route", "::/1", fmt.Sprintf("interface=%s", DefaultDeviceName)).Run()
	exec.Command("netsh", "interface", "ipv6", "delete", "route", "8000::/1", fmt.Sprintf("interface=%s", DefaultDeviceName)).Run()

	if s.activeConfig.ServerIP != "" && s.activeConfig.Gateway != "" {
		exec.Command("route", "delete", s.activeConfig.ServerIP, "mask", "255.255.255.255", s.activeConfig.Gateway).Run()
	}

	// Сброс DNS кэша Windows
	exec.Command("ipconfig", "/flushdns").Run()

	// Завершаем процесс tun2socks
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

func getWindowsInterfaceIndex(name string) int {
	out, err := exec.Command("netsh", "interface", "ipv4", "show", "interfaces").Output()
	if err != nil {
		return -1
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
				ifName := strings.Trim(fields[4], "\"")
				if strings.EqualFold(ifName, name) || strings.Contains(strings.ToLower(line), strings.ToLower(name)) {
					return idx
				}
			}
		}
	}
	return -1
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
