//go:build !windows

package main

import (
	"fmt"
)

func isWindowsService() bool {
	return false
}

func runWindowsService(server *TunServer) error {
	return fmt.Errorf("Windows service mode is only supported on Windows")
}

func installService() error {
	return fmt.Errorf("Service installation is only supported on Windows")
}

func uninstallService() error {
	return fmt.Errorf("Service uninstallation is only supported on Windows")
}

func startService() error {
	return fmt.Errorf("Service start is only supported on Windows")
}

func stopService() error {
	return fmt.Errorf("Service stop is only supported on Windows")
}

