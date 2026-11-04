package main

import (
	"flag"
	"fmt"
	"log"
	"os"
)

func main() {
	installFlag := flag.Bool("install", false, "Install Windows service")
	uninstallFlag := flag.Bool("uninstall", false, "Uninstall Windows service")
	startFlag := flag.Bool("start", false, "Start Windows service")
	stopFlag := flag.Bool("stop", false, "Stop Windows service")
	runFlag := flag.Bool("run", false, "Run server in interactive console mode")
	listenAddr := flag.String("listen", DefaultListenAddr, "HTTP listen address")
	flag.Parse()

	if *installFlag {
		if err := installService(); err != nil {
			log.Fatalf("[v2w-service] Install failed: %v", err)
		}
		fmt.Println("[v2w-service] Successfully installed service")
		return
	}

	if *uninstallFlag {
		if err := uninstallService(); err != nil {
			log.Fatalf("[v2w-service] Uninstall failed: %v", err)
		}
		fmt.Println("[v2w-service] Successfully uninstalled service")
		return
	}

	if *startFlag {
		if err := startService(); err != nil {
			log.Fatalf("[v2w-service] Start failed: %v", err)
		}
		fmt.Println("[v2w-service] Successfully started service")
		return
	}

	if *stopFlag {
		if err := stopService(); err != nil {
			log.Fatalf("[v2w-service] Stop failed: %v", err)
		}
		fmt.Println("[v2w-service] Successfully stopped service")
		return
	}

	server := NewTunServer()

	// Если запущен как системная служба Windows (SCM)
	if isWindowsService() {
		if err := runWindowsService(server); err != nil {
			log.Fatalf("[v2w-service] Service failed: %v", err)
		}
		return
	}

	// Иначе интерактивный / консольный режим
	_ = runFlag
	log.Printf("[v2w-service] Running in standalone mode on %s (PID: %d)", *listenAddr, os.Getpid())
	if err := server.Start(*listenAddr); err != nil {
		log.Fatalf("[v2w-service] Server terminated with error: %v", err)
	}
}

