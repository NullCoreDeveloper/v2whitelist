package tunrouter

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestPolicyEngineModesAndCombinedRules(t *testing.T) {
	// 1. Выключен -> всё идёт в PROXY, кроме защищённых ядер (loop guard).
	disabled := NewPolicyEngine(SplitConfig{
		Enabled:    false,
		Mode:       SplitModeOnlyProxy,
		DirectApps: []string{"chrome"},
	})
	if got := disabled.Decide(ProcessInfo{Name: "chrome"}); got != RouteProxy {
		t.Fatalf("expected disabled split to route chrome via PROXY, got %v", got)
	}
	if got := disabled.Decide(ProcessInfo{Name: "xray.exe"}); got != RouteDirect {
		t.Fatalf("expected xray.exe loop guard to always route DIRECT, got %v", got)
	}

	// 2. Режим bypass + одновременные direct_apps и proxy_apps
	bypass := NewPolicyEngine(SplitConfig{
		Enabled:    true,
		Mode:       SplitModeBypass,
		DirectApps: []string{"steam.exe", "/usr/bin/qbittorrent"},
		ProxyApps:  []string{"discord"},
	})
	if got := bypass.Decide(ProcessInfo{Name: "Steam.exe", Path: `C:\Program Files (x86)\Steam\steam.exe`}); got != RouteDirect {
		t.Fatalf("expected steam.exe -> DIRECT, got %v", got)
	}
	if got := bypass.Decide(ProcessInfo{Name: "qbittorrent", Path: "/usr/bin/qbittorrent"}); got != RouteDirect {
		t.Fatalf("expected /usr/bin/qbittorrent -> DIRECT, got %v", got)
	}
	if got := bypass.Decide(ProcessInfo{Name: "firefox", Path: "/usr/bin/firefox"}); got != RouteProxy {
		t.Fatalf("expected non-listed firefox in bypass mode -> PROXY, got %v", got)
	}

	// 3. Режим only_proxy + явное переопределение direct_apps + proxy_apps
	onlyProxy := NewPolicyEngine(SplitConfig{
		Enabled:    true,
		Mode:       SplitModeOnlyProxy,
		DirectApps: []string{"telegram.exe"},
		ProxyApps:  []string{"telegram", "chrome.exe"},
	})
	// Direct имеет приоритет над Proxy при конфликте (безопасное правило исключения)
	if got := onlyProxy.Decide(ProcessInfo{Name: "Telegram.exe"}); got != RouteDirect {
		t.Fatalf("expected explicit direct rule to take priority over proxy rule, got %v", got)
	}
	if got := onlyProxy.Decide(ProcessInfo{Name: "chrome.exe"}); got != RouteProxy {
		t.Fatalf("expected chrome.exe in only_proxy mode -> PROXY, got %v", got)
	}
	if got := onlyProxy.Decide(ProcessInfo{Name: "curl"}); got != RouteDirect {
		t.Fatalf("expected non-listed curl in only_proxy mode -> DIRECT, got %v", got)
	}
}

func TestResolveDirectDNSAddr(t *testing.T) {
	d := NewDirectDialer("", "")
	if got := d.ResolveDirectDNSAddr(netip.MustParseAddrPort("198.18.0.1:53")); got != netip.MustParseAddrPort("1.1.1.1:53") {
		t.Fatalf("expected TUN DNS IP to remap to 1.1.1.1:53, got %v", got)
	}
	if got := d.ResolveDirectDNSAddr(netip.MustParseAddrPort("8.8.8.8:53")); got != netip.MustParseAddrPort("8.8.8.8:53") {
		t.Fatalf("expected public DNS 8.8.8.8:53 to remain unchanged, got %v", got)
	}
	dCustom := NewDirectDialer("", "77.88.8.8")
	if got := dCustom.ResolveDirectDNSAddr(netip.MustParseAddrPort("8.8.8.8:53")); got != netip.MustParseAddrPort("77.88.8.8:53") {
		t.Fatalf("expected custom direct DNS 77.88.8.8:53, got %v", got)
	}
}

func TestSocks5UDPPacketCodecAndConnect(t *testing.T) {
	dst := netip.MustParseAddrPort("8.8.8.8:53")
	payload := []byte("hello-dns")
	encoded := EncodeSocks5UDPPacket(dst, payload)
	decodedAddr, decodedPayload, err := DecodeSocks5UDPPacket(encoded)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if decodedAddr != dst || string(decodedPayload) != string(payload) {
		t.Fatalf("roundtrip mismatch: got addr=%v payload=%q", decodedAddr, string(decodedPayload))
	}

	// Тестируем TCP CONNECT с user/pass авторизацией против локального mock SOCKS5 сервера
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		var greet [3]byte
		if _, err := io.ReadFull(conn, greet[:]); err != nil {
			return
		}
		var methods [2]byte
		if _, err := io.ReadFull(conn, methods[:1]); err != nil {
			return
		}
		_, _ = conn.Write([]byte{0x05, 0x02}) // Require user/pass
		var ver [2]byte
		if _, err := io.ReadFull(conn, ver[:]); err != nil {
			return
		}
		user := make([]byte, ver[1])
		_, _ = io.ReadFull(conn, user)
		var plen [1]byte
		_, _ = io.ReadFull(conn, plen[:])
		pass := make([]byte, plen[0])
		_, _ = io.ReadFull(conn, pass)
		if string(user) != "u1" || string(pass) != "p1" {
			_, _ = conn.Write([]byte{0x01, 0x01})
			return
		}
		_, _ = conn.Write([]byte{0x01, 0x00})

		var req [4]byte
		_, _ = io.ReadFull(conn, req[:])
		var ip [4]byte
		_, _ = io.ReadFull(conn, ip[:])
		var port [2]byte
		_, _ = io.ReadFull(conn, port[:])

		reply := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}
		binary.BigEndian.PutUint16(reply[8:10], 10808)
		_, _ = conn.Write(reply)
		_, _ = conn.Write([]byte("PONG"))
	}()

	client := &Socks5Client{ProxyAddr: ln.Addr().String(), Username: "u1", Password: "p1"}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	c, err := client.DialTCP(ctx, netip.MustParseAddrPort("1.1.1.1:80"))
	if err != nil {
		t.Fatalf("DialTCP failed: %v", err)
	}
	defer c.Close()
	got := make([]byte, 4)
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read pong: %v", err)
	}
	if string(got) != "PONG" {
		t.Fatalf("expected PONG, got %q", string(got))
	}
}
