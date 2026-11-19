package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/kiktor/v2w-core/tunrouter"
)

func splitCSV(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	var out []string
	for _, part := range strings.Split(raw, ",") {
		if t := strings.TrimSpace(part); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func main() {
	configPath := flag.String("config", "", "Path to JSON RouterConfig file")
	device := flag.String("device", "v2w-tun", "TUN device name")
	mtu := flag.Int("mtu", 1500, "TUN MTU")
	socksAddr := flag.String("proxy", "127.0.0.1:10808", "SOCKS5 proxy address (host:port)")
	socksUser := flag.String("proxy-user", "", "SOCKS5 username")
	socksPass := flag.String("proxy-pass", "", "SOCKS5 password")
	physicalIface := flag.String("physical-iface", "", "Physical interface name or IP for direct bypass traffic")
	directDNS := flag.String("direct-dns", "", "Direct DNS server (IP or IP:port) for bypassed apps")
	splitEnabled := flag.Bool("split", false, "Enable per-app split tunneling")
	splitMode := flag.String("split-mode", tunrouter.SplitModeBypass, "Split mode: bypass | only_proxy | all_proxy")
	directApps := flag.String("direct-apps", "", "Comma-separated apps forced to DIRECT")
	proxyApps := flag.String("proxy-apps", "", "Comma-separated apps forced to PROXY")
	flag.Parse()

	var cfg tunrouter.RouterConfig
	if strings.TrimSpace(*configPath) != "" {
		loaded, err := tunrouter.LoadConfigFile(strings.TrimSpace(*configPath))
		if err != nil {
			log.Fatalf("[v2w-tun] failed to load config %s: %v", *configPath, err)
		}
		cfg = loaded
	} else {
		cfg = tunrouter.RouterConfig{
			DeviceName:    *device,
			MTU:           *mtu,
			SocksAddr:     strings.TrimPrefix(strings.TrimSpace(*socksAddr), "socks5://"),
			SocksUser:     *socksUser,
			SocksPass:     *socksPass,
			PhysicalIface: *physicalIface,
			DirectDNS:     *directDNS,
			Split: tunrouter.SplitConfig{
				Enabled:    *splitEnabled,
				Mode:       *splitMode,
				DirectApps: splitCSV(*directApps),
				ProxyApps:  splitCSV(*proxyApps),
			},
		}
	}

	router, err := tunrouter.StartRouter(cfg)
	if err != nil {
		log.Fatalf("[v2w-tun] start error: %v", err)
	}
	fmt.Printf("V2W_TUN_READY device=%s\n", router.DeviceName())
	_ = os.Stdout.Sync()

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	log.Printf("[v2w-tun] shutting down...")
	_ = router.Close()
}
