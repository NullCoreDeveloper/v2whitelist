package main

import (
	"reflect"
	"testing"
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
