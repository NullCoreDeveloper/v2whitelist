//go:build windows

package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"time"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const (
	ServiceName        = "v2w-service"
	ServiceDisplayName = "ProBel VPN Service"
	ServiceDescription = "Background service for ProBel (v2whitelist) TUN interface and route management"
)

type windowsService struct {
	server *TunServer
}

func (m *windowsService) Execute(args []string, r <-chan svc.ChangeRequest, changes chan<- svc.Status) (ssec bool, errno uint32) {
	const cmdsAccepted = svc.AcceptStop | svc.AcceptShutdown
	changes <- svc.Status{State: svc.StartPending}

	// Запускаем HTTP сервер в фоновой горутине
	go func() {
		if err := m.server.Start(DefaultListenAddr); err != nil {
			log.Printf("[v2w-service] HTTP server error: %v", err)
		}
	}()

	changes <- svc.Status{State: svc.Running, Accepts: cmdsAccepted}

	for req := range r {
		switch req.Cmd {
		case svc.Interrogate:
			changes <- req.CurrentStatus
		case svc.Stop, svc.Shutdown:
			changes <- svc.Status{State: svc.StopPending}
			m.server.mu.Lock()
			m.server.stopTunLocked()
			m.server.mu.Unlock()
			changes <- svc.Status{State: svc.Stopped}
			return false, 0
		default:
			log.Printf("[v2w-service] Unexpected service control request #%d", req.Cmd)
		}
	}
	return false, 0
}

func isWindowsService() bool {
	isSvc, err := svc.IsWindowsService()
	if err != nil {
		return false
	}
	return isSvc
}

func runWindowsService(server *TunServer) error {
	return svc.Run(ServiceName, &windowsService{server: server})
}

// Разрешаем обычным (интерактивным) пользователям запускать/останавливать службу,
// чтобы GUI без UAC мог поднять её, если она остановлена.
func grantUserStartStop() {
	sddl := "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;RPWPLCLOCRRC;;;IU)"
	if out, err := exec.Command("sc.exe", "sdset", ServiceName, sddl).CombinedOutput(); err != nil {
		log.Printf("[v2w-service] sc sdset warning: %s (%v)", string(out), err)
	}
}

func installService() error {
	exePath, err := os.Executable()
	if err != nil {
		return err
	}

	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("failed to connect to Service Control Manager: %w", err)
	}
	defer m.Disconnect()

	s, err := m.OpenService(ServiceName)
	if err == nil {
		defer s.Close()
		// Служба уже зарегистрирована в системе. Проверяем путь исполняемого файла
		cfg, errCfg := s.Config()
		wantPath := `"` + exePath + `"`
		if errCfg == nil && cfg.BinaryPathName != exePath && cfg.BinaryPathName != wantPath {
			cfg.BinaryPathName = wantPath
			if errUpd := s.UpdateConfig(cfg); errUpd != nil {
				log.Printf("[v2w-service] Warning: failed to update service binary path: %v", errUpd)
			} else {
				log.Printf("[v2w-service] Successfully updated service binary path to: %s", exePath)
			}
		}
		grantUserStartStop()
		// Запускаем службу, если она была остановлена
		if startErr := s.Start(); startErr != nil {
			log.Printf("[v2w-service] Service already running or failed to start: %v", startErr)
		}
		return nil
	}

	s, err = m.CreateService(ServiceName, exePath, mgr.Config{
		DisplayName: ServiceDisplayName,
		Description: ServiceDescription,
		StartType:   mgr.StartAutomatic,
	})
	if err != nil {
		return fmt.Errorf("failed to create service: %w", err)
	}
	defer s.Close()

	grantUserStartStop()
	if err = s.Start(); err != nil {
		log.Printf("[v2w-service] Service installed, but could not start immediately: %v", err)
	} else {
		log.Printf("[v2w-service] Service %s installed and started successfully", ServiceName)
	}
	return nil
}

func uninstallService() error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("failed to connect to SCM: %w", err)
	}
	defer m.Disconnect()

	s, err := m.OpenService(ServiceName)
	if err != nil {
		return fmt.Errorf("service %s is not installed: %w", ServiceName, err)
	}
	defer s.Close()

	_, _ = s.Control(svc.Stop)
	time.Sleep(500 * time.Millisecond)

	err = s.Delete()
	if err != nil {
		return fmt.Errorf("failed to delete service: %w", err)
	}
	log.Printf("[v2w-service] Service %s uninstalled successfully", ServiceName)
	return nil
}

func startService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()

	s, err := m.OpenService(ServiceName)
	if err != nil {
		return err
	}
	defer s.Close()

	return s.Start()
}

func stopService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()

	s, err := m.OpenService(ServiceName)
	if err != nil {
		return err
	}
	defer s.Close()

	_, err = s.Control(svc.Stop)
	return err
}

