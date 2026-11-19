//go:build darwin

package tunrouter

import (
	"context"
	"fmt"
	"net/netip"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func (r *ProcessResolver) lookupPlatform(proto string, src, _ netip.AddrPort) (ProcessInfo, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 350*time.Millisecond)
	defer cancel()

	query := fmt.Sprintf("%s:%d", proto, src.Port())
	out, err := exec.CommandContext(ctx, "lsof", "-n", "-P", "-F", "pc", "-i", query).Output()
	if err != nil && len(out) == 0 {
		return ProcessInfo{}, false
	}

	var pid int
	var name string
	for _, line := range strings.Split(string(out), "\n") {
		if len(line) < 2 {
			continue
		}
		switch line[0] {
		case 'p':
			if p, err := strconv.Atoi(strings.TrimSpace(line[1:])); err == nil {
				pid = p
			}
		case 'c':
			name = strings.TrimSpace(line[1:])
		}
		if pid > 0 && name != "" {
			break
		}
	}
	if pid <= 0 && name == "" {
		return ProcessInfo{}, false
	}

	fullPath := ""
	if pid > 0 {
		if psOut, err := exec.CommandContext(ctx, "ps", "-p", strconv.Itoa(pid), "-o", "comm=").Output(); err == nil {
			p := strings.TrimSpace(string(psOut))
			if p != "" {
				fullPath = p
				name = filepath.Base(p)
			}
		}
	}

	return ProcessInfo{
		PID:  pid,
		Name: name,
		Path: fullPath,
	}, true
}
