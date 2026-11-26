package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestParseNetshDnsServers(t *testing.T) {
	sampleEnglish := `
Configuration for interface "Ethernet"
    DNS servers configured through DHCP:  46.61.250.138
                                          46.61.250.139
    Register with which suffix:           Primary only

Configuration for interface "Wi-Fi"
    Statically Configured DNS Servers:    192.168.1.1
                                          77.88.8.8

Configuration for interface "wintun"
    Statically Configured DNS Servers:    1.1.1.1
                                          8.8.8.8

Configuration for interface "Loopback Pseudo-Interface 1"
    DNS servers configured through DHCP:  127.0.0.1
`
	got := parseNetshDnsServers(sampleEnglish, []string{"1.1.1.1", "8.8.8.8"}, "198.51.100.10", "192.168.1.1")
	want := []string{"46.61.250.138", "46.61.250.139", "77.88.8.8"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("english netsh parse mismatch: got %v, want %v", got, want)
	}

	sampleRussian := `
Настройка интерфейса "Беспроводная сеть"
    DNS-серверы, настроенные через DHCP:  46.61.250.138
                                          46.61.250.139
    Регистрация с суффиксом:              Только основной

Настройка интерфейса "wintun"
    Статически настроенные DNS-серверы:   9.9.9.9
                                          1.1.1.1
`
	gotRu := parseNetshDnsServers(sampleRussian, []string{"1.1.1.1", "9.9.9.9"}, "46.61.250.138")
	wantRu := []string{"46.61.250.139"}
	if !reflect.DeepEqual(gotRu, wantRu) {
		t.Fatalf("russian netsh parse mismatch: got %v, want %v", gotRu, wantRu)
	}
}

func TestBuildServerRouteArgs(t *testing.T) {
	addStd := buildServerRouteAddArgs("203.0.113.10", "192.168.1.1", 12)
	wantAddStd := []string{"add", "203.0.113.10", "mask", "255.255.255.255", "192.168.1.1", "metric", "1"}
	if !reflect.DeepEqual(addStd, wantAddStd) {
		t.Fatalf("standard route add mismatch: got %v, want %v", addStd, wantAddStd)
	}

	addPpp := buildServerRouteAddArgs("203.0.113.10", "0.0.0.0", 29)
	wantAddPpp := []string{"add", "203.0.113.10", "mask", "255.255.255.255", "0.0.0.0", "metric", "1", "if", "29"}
	if !reflect.DeepEqual(addPpp, wantAddPpp) {
		t.Fatalf("on-link ppp route add mismatch: got %v, want %v", addPpp, wantAddPpp)
	}

	if got := buildServerRouteAddArgs("203.0.113.10", "On-link", 0); got != nil {
		t.Fatalf("expected nil when on-link has no ifIndex, got %v", got)
	}

	delPpp := buildServerRouteDeleteArgs("203.0.113.10", "0.0.0.0")
	wantDelPpp := []string{"delete", "203.0.113.10", "mask", "255.255.255.255"}
	if !reflect.DeepEqual(delPpp, wantDelPpp) {
		t.Fatalf("on-link ppp route delete mismatch: got %v, want %v", delPpp, wantDelPpp)
	}
}

func TestNormalizeCustomSelfTestURL(t *testing.T) {
	if got := normalizeCustomSelfTestURL("  https://ya.ru/generate_204  "); got != "https://ya.ru/generate_204" {
		t.Fatalf("expected trimmed https url, got %q", got)
	}
	if got := normalizeCustomSelfTestURL("http://2ip.ru"); got != "http://2ip.ru" {
		t.Fatalf("expected http url, got %q", got)
	}
	if got := normalizeCustomSelfTestURL("ftp://example.com"); got != "" {
		t.Fatalf("expected empty for non-http scheme, got %q", got)
	}
	if got := normalizeCustomSelfTestURL("   "); got != "" {
		t.Fatalf("expected empty for blank input, got %q", got)
	}
}

func TestHandleShutdownClosesChannel(t *testing.T) {
	srv := NewTunServer()
	handler := srv.requireIpcAuth(srv.handleShutdown)

	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/service/shutdown", nil)
	req.Header.Set(IpcTokenHeader, srv.ipcToken)
	rec := httptest.NewRecorder()
	handler(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rec.Code)
	}

	select {
	case <-srv.ShutdownChan():
	case <-time.After(time.Second):
		t.Fatalf("expected ShutdownChan to be closed after /service/shutdown")
	}

	// Повторный вызов не должен паниковать (sync.Once)
	req2 := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/service/shutdown", nil)
	req2.Header.Set(IpcTokenHeader, srv.ipcToken)
	rec2 := httptest.NewRecorder()
	handler(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("expected 200 OK on repeated shutdown, got %d", rec2.Code)
	}
}

func TestIpcAuthRejectsMissingTokenOriginAndBadHost(t *testing.T) {
	srv := NewTunServer()
	handler := srv.requireIpcAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, ApiResponse{Success: true})
	})

	// 1. Нет токена -> 401
	reqNoTok := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/tun/stop", nil)
	recNoTok := httptest.NewRecorder()
	handler(recNoTok, reqNoTok)
	if recNoTok.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without token, got %d", recNoTok.Code)
	}

	// 2. Неверный токен -> 401
	reqBadTok := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/tun/stop", nil)
	reqBadTok.Header.Set(IpcTokenHeader, "wrong-token")
	recBadTok := httptest.NewRecorder()
	handler(recBadTok, reqBadTok)
	if recBadTok.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 with bad token, got %d", recBadTok.Code)
	}

	// 3. Браузерный Origin -> 403
	reqOrigin := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/tun/stop", nil)
	reqOrigin.Header.Set(IpcTokenHeader, srv.ipcToken)
	reqOrigin.Header.Set("Origin", "https://evil.example")
	recOrigin := httptest.NewRecorder()
	handler(recOrigin, reqOrigin)
	if recOrigin.Code != http.StatusForbidden {
		t.Fatalf("expected 403 with Origin header, got %d", recOrigin.Code)
	}

	// 4. DNS Rebinding Host -> 403
	reqBadHost := httptest.NewRequest(http.MethodPost, "http://evil.example:53729/tun/stop", nil)
	reqBadHost.Header.Set(IpcTokenHeader, srv.ipcToken)
	recBadHost := httptest.NewRecorder()
	handler(recBadHost, reqBadHost)
	if recBadHost.Code != http.StatusForbidden {
		t.Fatalf("expected 403 with non-loopback Host, got %d", recBadHost.Code)
	}

	// 5. Корректный токен и loopback Host -> 200
	reqOK := httptest.NewRequest(http.MethodPost, "http://localhost:53729/tun/stop", nil)
	reqOK.Header.Set(IpcTokenHeader, srv.ipcToken)
	recOK := httptest.NewRecorder()
	handler(recOK, reqOK)
	if recOK.Code != http.StatusOK {
		t.Fatalf("expected 200 with valid token and localhost host, got %d", recOK.Code)
	}
}

func TestHandleStartTunValidatesIpAndBodySize(t *testing.T) {
	srv := NewTunServer()

	// Невалидный server_ip с инъекцией аргументов -> 400
	badPayload := `{"socks_port":10808,"server_ip":"8.8.8.8 && calc.exe"}`
	reqBadIP := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/tun/start", strings.NewReader(badPayload))
	recBadIP := httptest.NewRecorder()
	srv.handleStartTun(recBadIP, reqBadIP)
	if recBadIP.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid server_ip, got %d (%s)", recBadIP.Code, recBadIP.Body.String())
	}

	// Слишком большое тело запроса -> 413
	huge := bytes.Repeat([]byte("a"), MaxRequestBodyBytes+1024)
	reqHuge := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:53729/tun/start", bytes.NewReader(huge))
	recHuge := httptest.NewRecorder()
	srv.handleStartTun(recHuge, reqHuge)
	if recHuge.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 for oversized body, got %d", recHuge.Code)
	}
}

func TestBuildLanRouteArgs(t *testing.T) {
	addArgs := buildLanRouteAddArgs("192.168.0.0", "255.255.0.0", "192.168.1.1", 12)
	wantAdd := []string{"add", "192.168.0.0", "mask", "255.255.0.0", "192.168.1.1", "metric", "1", "if", "12"}
	if !reflect.DeepEqual(addArgs, wantAdd) {
		t.Fatalf("lan route add mismatch: got %v, want %v", addArgs, wantAdd)
	}

	if got := buildLanRouteAddArgs("192.168.0.0", "255.255.0.0", "On-link", 12); got != nil {
		t.Fatalf("expected nil for on-link gateway, got %v", got)
	}

	delArgs := buildLanRouteDeleteArgs("10.0.0.0", "255.0.0.0", "192.168.1.1")
	wantDel := []string{"delete", "10.0.0.0", "mask", "255.0.0.0", "192.168.1.1"}
	if !reflect.DeepEqual(delArgs, wantDel) {
		t.Fatalf("lan route delete mismatch: got %v, want %v", delArgs, wantDel)
	}
}
