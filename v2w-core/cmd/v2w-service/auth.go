package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	IpcTokenHeader      = "X-V2W-Token"
	MaxRequestBodyBytes = 256 * 1024
)

func generateIpcToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func defaultIpcTokenPath() string {
	if runtime.GOOS == "windows" {
		progData := strings.TrimSpace(os.Getenv("ProgramData"))
		if progData == "" {
			progData = `C:\ProgramData`
		}
		return filepath.Join(progData, "v2whitelist", "ipc.token")
	}
	if dir := strings.TrimSpace(os.Getenv("XDG_RUNTIME_DIR")); dir != "" {
		return filepath.Join(dir, "v2whitelist", "ipc.token")
	}
	return filepath.Join(os.TempDir(), fmt.Sprintf("v2w_ipc_%d.token", os.Getuid()))
}

func writeIpcTokenFile(path, token string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, []byte(token), 0600); err != nil {
		return err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if runtime.GOOS == "windows" {
		// Ограничиваем ACL токена только для SYSTEM, Administrators и текущего пользователя
		_ = applyWindowsRestrictedAcl(path)
	}
	return nil
}

func applyWindowsRestrictedAcl(path string) error {
	args := []string{
		path,
		"/inheritance:r",
		"/grant:r", "*S-1-5-18:(F)", // SYSTEM
		"/grant:r", "*S-1-5-32-544:(F)", // BUILTIN\Administrators
		"/grant:r", "*S-1-5-32-545:(R)", // BUILTIN\Users (чтение для локального UI-клиента)
	}
	cmd := exec.Command("icacls", args...)
	return cmd.Run()
}

func isAllowedLoopbackHost(hostHeader string) bool {
	trimmed := strings.TrimSpace(hostHeader)
	if trimmed == "" {
		return true
	}
	host := trimmed
	if h, _, err := net.SplitHostPort(trimmed); err == nil {
		host = h
	} else if strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]") {
		host = strings.TrimSuffix(strings.TrimPrefix(trimmed, "["), "]")
	}
	switch strings.ToLower(host) {
	case "127.0.0.1", "localhost", "::1":
		return true
	default:
		return false
	}
}

func validateStartTunRequest(req *StartTunRequest) error {
	if req == nil {
		return fmt.Errorf("empty request")
	}
	if req.SocksPort <= 0 || req.SocksPort > 65535 {
		return fmt.Errorf("invalid socksPort: %d", req.SocksPort)
	}
	if serverIP := strings.TrimSpace(req.ServerIP); serverIP != "" {
		if net.ParseIP(serverIP) == nil {
			return fmt.Errorf("server_ip must be a valid IP literal")
		}
	}
	if gw := strings.TrimSpace(req.Gateway); gw != "" && !strings.EqualFold(gw, "on-link") {
		if net.ParseIP(gw) == nil {
			return fmt.Errorf("gateway must be a valid IP literal")
		}
	}
	for _, field := range []struct {
		name string
		val  string
	}{
		{"tun_ip", req.TunIP},
		{"tun_mask", req.TunMask},
		{"tun_gateway", req.TunGateway},
		{"direct_dns", req.DirectDNS},
	} {
		if v := strings.TrimSpace(field.val); v != "" && net.ParseIP(v) == nil {
			return fmt.Errorf("invalid %s IP: %s", field.name, v)
		}
	}
	if len(req.DnsServers) > 32 {
		return fmt.Errorf("too many dns_servers entries")
	}
	for _, dns := range req.DnsServers {
		trimmed := strings.TrimSpace(dns)
		if trimmed == "" {
			continue
		}
		if net.ParseIP(trimmed) == nil {
			return fmt.Errorf("invalid dns_server IP: %s", trimmed)
		}
	}
	return nil
}

func constantTimeTokenMatch(expected, actual string) bool {
	if expected == "" || actual == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(expected), []byte(actual)) == 1
}

func (s *TunServer) requireIpcAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != "" || r.Header.Get("Referer") != "" {
			writeJSON(w, http.StatusForbidden, ApiResponse{Success: false, Error: "Forbidden origin"})
			return
		}
		if !isAllowedLoopbackHost(r.Host) {
			writeJSON(w, http.StatusForbidden, ApiResponse{Success: false, Error: "Forbidden host"})
			return
		}
		provided := strings.TrimSpace(r.Header.Get(IpcTokenHeader))
		if !constantTimeTokenMatch(s.ipcToken, provided) {
			writeJSON(w, http.StatusUnauthorized, ApiResponse{Success: false, Error: "Unauthorized IPC client"})
			return
		}
		next(w, r)
	}
}
