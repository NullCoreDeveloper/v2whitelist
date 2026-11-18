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
