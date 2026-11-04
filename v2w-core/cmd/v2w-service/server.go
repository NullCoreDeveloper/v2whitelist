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
	DefaultTunIP      = "198.18.0.1"
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
	tun2socksBin := req.Tun2Socks
	if tun2socksBin == "" || !fileExists(tun2socksBin) {
		tun2socksBin = findTun2SocksBin()
	}
	if tun2socksBin == "" || !fileExists(tun2socksBin) {
		return fmt.Errorf("tun2socks executable not found (provided: %s)", req.Tun2Socks)
	}

	binDir := filepath.Dir(tun2socksBin)

	args := []string{
		"-device", DefaultDeviceName,
		"-proxy", fmt.Sprintf("socks5://127.0.0.1:%d", req.SocksPort),
		"-loglevel", "warning",
	}

	cmd := exec.Command(tun2socksBin, args...)
	cmd.Dir = binDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("failed to launch tun2socks: %w", err)
	}
	s.tunCmd = cmd

	// Ожидаем появления интерфейса wintun в системе
	time.Sleep(500 * time.Millisecond)

	// Назначаем статический IP и маску на интерфейс wintun через netsh
	netshAddrArgs := []string{
		"interface", "ipv4", "set", "address",
		fmt.Sprintf("name=%s", DefaultDeviceName),
		"source=static",
		fmt.Sprintf("addr=%s", req.TunIP),
		fmt.Sprintf("mask=%s", req.TunMask),
		"gateway=none",
	}
	if out, err := exec.Command("netsh", netshAddrArgs...).CombinedOutput(); err != nil {
		log.Printf("[v2w-service] netsh set address warning: %s (%v)", string(out), err)
	}

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

	// Маршрутизируем весь входящий IPv4 трафик в wintun (0.0.0.0/1 и 128.0.0.0/1)
	out1, err1 := exec.Command("route", "add", "0.0.0.0", "mask", "128.0.0.0", req.TunIP, "metric", "1").CombinedOutput()
	out2, err2 := exec.Command("route", "add", "128.0.0.0", "mask", "128.0.0.0", req.TunIP, "metric", "1").CombinedOutput()
	if err1 != nil || err2 != nil {
		log.Printf("[v2w-service] route add 0/1 warning: %s (%v) / %s (%v)", string(out1), err1, string(out2), err2)
	}

	return nil
}

func (s *TunServer) stopTunLocked() {
	tunIP := s.activeConfig.TunIP
	if tunIP == "" {
		tunIP = DefaultTunIP
	}

	// Удаляем маршруты из таблицы
	exec.Command("route", "delete", "0.0.0.0", "mask", "128.0.0.0", tunIP).Run()
	exec.Command("route", "delete", "128.0.0.0", "mask", "128.0.0.0", tunIP).Run()

	if s.activeConfig.ServerIP != "" && s.activeConfig.Gateway != "" {
		exec.Command("route", "delete", s.activeConfig.ServerIP, "mask", "255.255.255.255", s.activeConfig.Gateway).Run()
	}

	// Завершаем процесс tun2socks
	if s.tunCmd != nil && s.tunCmd.Process != nil {
		_ = s.tunCmd.Process.Kill()
		_ = s.tunCmd.Wait()
		s.tunCmd = nil
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

