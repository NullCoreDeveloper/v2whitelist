package tunrouter

import (
	"path/filepath"
	"strings"
)

// RouteAction определяет направление маршрутизации соединения.
type RouteAction int

const (
	RouteProxy RouteAction = iota
	RouteDirect
)

func (a RouteAction) String() string {
	if a == RouteDirect {
		return "DIRECT"
	}
	return "PROXY"
}

// SplitMode задаёт политику по умолчанию для процессов, не попавших в явные списки.
const (
	SplitModeAllProxy  = "all_proxy"
	SplitModeBypass    = "bypass"
	SplitModeOnlyProxy = "only_proxy"
)

// SplitConfig описывает настройки раздельного туннелирования по приложениям.
type SplitConfig struct {
	Enabled    bool     `json:"enabled"`
	Mode       string   `json:"mode"`
	DirectApps []string `json:"direct_apps"`
	ProxyApps  []string `json:"proxy_apps"`
}

// ProcessInfo содержит сведения о процессе-владельце сокета.
type ProcessInfo struct {
	PID  int
	Name string // базовое имя исполняемого файла, напр. "chrome.exe" или "firefox"
	Path string // полный путь к исполняемому файлу
}

// PolicyEngine принимает решения PROXY/DIRECT по имени и пути процесса.
type PolicyEngine struct {
	enabled      bool
	defaultRoute RouteAction
	directRules  []appRule
	proxyRules   []appRule
}

type appRule struct {
	raw      string
	isPath   bool
	baseName string
	fullPath string
}

var coreLoopGuardNames = map[string]bool{
	"xray":        true,
	"xray-core":   true,
	"v2w-tun":     true,
	"v2w-service": true,
	"tun2socks":   true,
}

func NewPolicyEngine(cfg SplitConfig) *PolicyEngine {
	mode := strings.ToLower(strings.TrimSpace(cfg.Mode))
	defaultRoute := RouteProxy
	if cfg.Enabled && mode == SplitModeOnlyProxy {
		defaultRoute = RouteDirect
	}

	return &PolicyEngine{
		enabled:      cfg.Enabled,
		defaultRoute: defaultRoute,
		directRules:  compileAppRules(cfg.DirectApps),
		proxyRules:   compileAppRules(cfg.ProxyApps),
	}
}

// NeedsProcessLookup возвращает true, если для принятия решения нужно определять процесс.
func (p *PolicyEngine) NeedsProcessLookup() bool {
	if p == nil || !p.enabled {
		return false
	}
	return len(p.directRules) > 0 || len(p.proxyRules) > 0 || p.defaultRoute == RouteDirect
}

// Decide определяет маршрут (PROXY или DIRECT) для процесса.
func (p *PolicyEngine) Decide(proc ProcessInfo) RouteAction {
	if isCoreLoopGuard(proc) {
		return RouteDirect
	}
	if p == nil || !p.enabled {
		return RouteProxy
	}
	if matchesAnyRule(proc, p.directRules) {
		return RouteDirect
	}
	if matchesAnyRule(proc, p.proxyRules) {
		return RouteProxy
	}
	return p.defaultRoute
}

func isCoreLoopGuard(proc ProcessInfo) bool {
	base := normalizeBaseName(proc.Name)
	if base == "" && proc.Path != "" {
		base = normalizeBaseName(filepath.Base(strings.ReplaceAll(proc.Path, "\\", "/")))
	}
	return coreLoopGuardNames[base]
}

func compileAppRules(items []string) []appRule {
	var rules []appRule
	for _, raw := range items {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		norm := normalizePath(trimmed)
		isPath := strings.Contains(norm, "/")
		base := normalizeBaseName(norm)
		if !isPath && base == "" {
			continue
		}
		rules = append(rules, appRule{
			raw:      trimmed,
			isPath:   isPath,
			baseName: base,
			fullPath: norm,
		})
	}
	return rules
}

func matchesAnyRule(proc ProcessInfo, rules []appRule) bool {
	if len(rules) == 0 {
		return false
	}
	procPath := normalizePath(proc.Path)
	procBase := normalizeBaseName(proc.Name)
	if procBase == "" && procPath != "" {
		procBase = normalizeBaseName(procPath)
	}
	if procBase == "" && procPath == "" {
		return false
	}

	for _, r := range rules {
		if r.isPath {
			if procPath != "" && procPath == r.fullPath {
				return true
			}
			// Поддержка совпадения по суффиксу пути, если пользователь указал относительный путь или bundle
			if procPath != "" && strings.HasSuffix(procPath, "/"+r.fullPath) {
				return true
			}
		} else {
			if procBase != "" && procBase == r.baseName {
				return true
			}
		}
	}
	return false
}

func normalizePath(p string) string {
	s := strings.TrimSpace(strings.ReplaceAll(p, "\\", "/"))
	return strings.ToLower(s)
}

func normalizeBaseName(nameOrPath string) string {
	s := normalizePath(nameOrPath)
	if idx := strings.LastIndexByte(s, '/'); idx != -1 {
		s = s[idx+1:]
	}
	s = strings.TrimSuffix(s, ".exe")
	return s
}
