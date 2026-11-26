package core

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/kiktor/v2w-core/transport/internet"
)

// ServiceCheckSpec описывает проверяемый сервис (встроенный или добавленный пользователем).
type ServiceCheckSpec struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	URL      string `json:"url"`
	Enabled  bool   `json:"enabled"`
	IsCustom bool   `json:"isCustom"`
}

// ExitIPInfo содержит реальный выходной IP, ISO-код страны и задержку, полученные через cdn-cgi/trace.
type ExitIPInfo struct {
	IP          string
	CountryCode string
	Latency     time.Duration
}

// SingleServiceResult описывает результат проверки одного сервиса без fail-fast.
type SingleServiceResult struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Success   bool   `json:"success"`
	LatencyMs int64  `json:"latency_ms"`
	Error     string `json:"error,omitempty"`
}

var (
	blockedRegions2Letter = map[string]bool{
		"AF": true, "BY": true, "CN": true, "CU": true, "HK": true,
		"IR": true, "KP": true, "MO": true, "RU": true, "SY": true,
	}
	blockedGeminiRegions = map[string]bool{
		"AFG": true, "CHN": true, "RUS": true, "BLR": true, "CUB": true,
		"IRN": true, "PRK": true, "SYR": true, "HKG": true, "MAC": true,
	}
	cfTraceIPRegex        = regexp.MustCompile(`(?m)^ip=([^\r\n]+)\r?$`)
	cfTraceLocRegex       = regexp.MustCompile(`(?m)^loc=([A-Za-z]{2})\r?$`)
	geminiMarkerRegex     = regexp.MustCompile(`,2,1,200,"([A-Za-z]{3})"`)
	geminiStatusCodeRegex = regexp.MustCompile(`,2,1,([0-9]+),`)
	geminiHeaders         = map[string]string{
		"Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
		"Accept-Language": "en-US,en;q=0.9",
		"Cookie":          "SOCS=CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmVuIAEaBgiAo_CmBg",
	}
	whatsappHeaders = map[string]string{
		"User-Agent":     "WhatsApp/2.24.1.6 A",
		"Accept":         "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
		"Sec-Fetch-Site": "none",
		"Sec-Fetch-Mode": "navigate",
		"Sec-Fetch-Dest": "document",
	}
)

// CheckServicesParallel легковесно и асинхронно проверяет все включённые сервисы через ProxyHandler (fail-fast).
// Возвращает среднюю задержку и ошибку первого недоступного сервиса (мгновенно отменяя остальные пробы).
func (s *Scanner) CheckServicesParallel(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	services []ServiceCheckSpec,
	perServiceTimeout time.Duration,
) (time.Duration, error) {
	var enabled []ServiceCheckSpec
	for _, svc := range services {
		if svc.Enabled && strings.TrimSpace(svc.URL) != "" {
			enabled = append(enabled, svc)
		}
	}
	if len(enabled) == 0 {
		return 0, nil
	}
	if perServiceTimeout <= 0 {
		perServiceTimeout = 4500 * time.Millisecond
	}

	svcCtx, cancelAll := context.WithCancel(ctx)
	defer cancelAll()

	var (
		wg       sync.WaitGroup
		mu       sync.Mutex
		firstErr error
		latSum   time.Duration
		latCount int
	)

	for _, svc := range enabled {
		wg.Add(1)
		go func(item ServiceCheckSpec) {
			defer wg.Done()
			if svcCtx.Err() != nil {
				return
			}
			itemCtx, cancelItem := context.WithTimeout(svcCtx, perServiceTimeout)
			defer cancelItem()

			lat, err := s.checkSingleService(itemCtx, handler, dialer, item)
			mu.Lock()
			if err != nil {
				if firstErr == nil {
					firstErr = fmt.Errorf("%s: %w", item.Name, err)
					cancelAll()
				}
			} else {
				latSum += lat
				latCount++
			}
			mu.Unlock()
		}(svc)
	}

	wg.Wait()
	if firstErr != nil {
		return 0, firstErr
	}
	if latCount > 0 {
		return latSum / time.Duration(latCount), nil
	}
	return 0, fmt.Errorf("no services completed")
}

// CheckServicesAll проверяет все включённые сервисы параллельно без fail-fast и возвращает результат по каждому.
// Если knownCFLoc задан, проверки Claude/ChatGPT переиспользуют его без повторного запроса к cdn-cgi/trace.
func (s *Scanner) CheckServicesAll(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	services []ServiceCheckSpec,
	perServiceTimeout time.Duration,
	knownCFLoc string,
) []SingleServiceResult {
	var enabled []ServiceCheckSpec
	for _, svc := range services {
		if svc.Enabled && strings.TrimSpace(svc.URL) != "" {
			enabled = append(enabled, svc)
		}
	}
	if len(enabled) == 0 {
		return nil
	}
	if perServiceTimeout <= 0 {
		perServiceTimeout = 5000 * time.Millisecond
	}

	results := make([]SingleServiceResult, len(enabled))
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup

	for i, svc := range enabled {
		wg.Add(1)
		go func(idx int, item ServiceCheckSpec) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				results[idx] = SingleServiceResult{
					ID:        item.ID,
					Name:      item.Name,
					Success:   false,
					LatencyMs: -1,
					Error:     ctx.Err().Error(),
				}
				return
			}
			if ctx.Err() != nil {
				results[idx] = SingleServiceResult{
					ID:        item.ID,
					Name:      item.Name,
					Success:   false,
					LatencyMs: -1,
					Error:     ctx.Err().Error(),
				}
				return
			}
			itemCtx, cancelItem := context.WithTimeout(ctx, perServiceTimeout)
			defer cancelItem()

			lat, err := s.checkSingleServiceWithLoc(itemCtx, handler, dialer, item, knownCFLoc)
			if err != nil {
				results[idx] = SingleServiceResult{
					ID:        item.ID,
					Name:      item.Name,
					Success:   false,
					LatencyMs: -1,
					Error:     err.Error(),
				}
				return
			}
			ms := lat.Milliseconds()
			if ms < 0 {
				ms = 0
			}
			results[idx] = SingleServiceResult{
				ID:        item.ID,
				Name:      item.Name,
				Success:   true,
				LatencyMs: ms,
			}
		}(i, svc)
	}

	wg.Wait()
	return results
}

func (s *Scanner) checkSingleService(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	svc ServiceCheckSpec,
) (time.Duration, error) {
	return s.checkSingleServiceWithLoc(ctx, handler, dialer, svc, "")
}

func (s *Scanner) checkSingleServiceWithLoc(
	ctx context.Context,
	handler ProxyHandler,
	dialer internet.Dialer,
	svc ServiceCheckSpec,
	knownLoc string,
) (time.Duration, error) {
	if !svc.IsCustom {
		switch strings.ToLower(strings.TrimSpace(svc.ID)) {
		case "claude":
			return s.checkClaudeLiteWithLoc(ctx, handler, dialer, knownLoc)
		case "chatgpt":
			return s.checkChatGPTLiteWithLoc(ctx, handler, dialer, knownLoc)
		case "gemini":
			return s.checkGeminiLite(ctx, handler, dialer)
		case "discord":
			return s.checkDiscordLite(ctx, handler, dialer, svc.URL)
		case "telegram":
			return s.checkTelegramLite(ctx, handler, dialer, svc.URL)
		case "whatsapp":
			return s.checkWhatsAppLite(ctx, handler, dialer, svc.URL)
		case "instagram":
			return s.checkInstagramLite(ctx, handler, dialer, svc.URL)
		}
	}
	return s.checkGenericService(ctx, handler, dialer, svc.URL)
}

func extractCFIP(body string) string {
	m := cfTraceIPRegex.FindStringSubmatch(body)
	if len(m) >= 2 {
		return strings.TrimSpace(m[1])
	}
	return ""
}

func extractCFLoc(body string) string {
	m := cfTraceLocRegex.FindStringSubmatch(body)
	if len(m) >= 2 {
		return strings.ToUpper(m[1])
	}
	if idx := strings.Index(body, "loc="); idx != -1 && len(body) >= idx+6 {
		return strings.ToUpper(body[idx+4 : idx+6])
	}
	return ""
}

// ProbeExitIP проверяет живость прокси и определяет реальный выходной IP и страну через cloudflare cdn-cgi/trace.
func (s *Scanner) ProbeExitIP(ctx context.Context, handler ProxyHandler, dialer internet.Dialer) (*ExitIPInfo, error) {
	res, err := s.ProbeHTTPWarm(ctx, handler, dialer, "https://www.cloudflare.com/cdn-cgi/trace", nil, 2048)
	if err != nil || res == nil {
		if err != nil {
			return nil, err
		}
		return nil, fmt.Errorf("empty probe response")
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return nil, &HTTPStatusError{StatusCode: res.StatusCode, WantCode: http.StatusOK}
	}
	ip := extractCFIP(res.Body)
	loc := extractCFLoc(res.Body)
	if ip == "" && loc == "" {
		return nil, fmt.Errorf("empty cloudflare trace response")
	}
	return &ExitIPInfo{
		IP:          ip,
		CountryCode: loc,
		Latency:     res.Latency,
	}, nil
}

func (s *Scanner) checkClaudeLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer) (time.Duration, error) {
	return s.checkClaudeLiteWithLoc(ctx, handler, dialer, "")
}

func (s *Scanner) checkClaudeLiteWithLoc(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, knownLoc string) (time.Duration, error) {
	loc := strings.ToUpper(strings.TrimSpace(knownLoc))
	if loc != "" {
		if blockedRegions2Letter[loc] {
			return 0, fmt.Errorf("region %s blocked", loc)
		}
	} else {
		cfRes, _ := s.ProbeHTTP(ctx, handler, dialer, "https://www.cloudflare.com/cdn-cgi/trace", nil, 2048)
		if cfRes != nil {
			loc = extractCFLoc(cfRes.Body)
			if blockedRegions2Letter[loc] {
				return 0, fmt.Errorf("region %s blocked", loc)
			}
		}
	}

	apiRes, apiErr := s.ProbeHTTP(ctx, handler, dialer, "https://api.anthropic.com/v1/models", nil, 1024)
	if apiErr == nil && apiRes != nil {
		lower := strings.ToLower(apiRes.Body)
		if strings.Contains(lower, "not available in your region") || strings.Contains(lower, "permission_error") {
			return 0, fmt.Errorf("anthropic region blocked")
		}
		if apiRes.StatusCode == 401 || (apiRes.StatusCode >= 200 && apiRes.StatusCode < 400) {
			return apiRes.Latency, nil
		}
	}

	webRes, webErr := s.ProbeHTTP(ctx, handler, dialer, "https://claude.ai", nil, 512)
	if webErr == nil && webRes != nil {
		if (webRes.StatusCode >= 200 && webRes.StatusCode < 400) || (webRes.StatusCode == 403 && loc != "" && !blockedRegions2Letter[loc]) {
			return webRes.Latency, nil
		}
		return 0, fmt.Errorf("HTTP %d", webRes.StatusCode)
	}
	if apiErr != nil {
		return 0, apiErr
	}
	return 0, webErr
}

func (s *Scanner) checkChatGPTLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer) (time.Duration, error) {
	return s.checkChatGPTLiteWithLoc(ctx, handler, dialer, "")
}

func (s *Scanner) checkChatGPTLiteWithLoc(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, knownLoc string) (time.Duration, error) {
	loc := strings.ToUpper(strings.TrimSpace(knownLoc))
	if loc != "" {
		if blockedRegions2Letter[loc] {
			return 0, fmt.Errorf("region %s blocked", loc)
		}
	} else {
		cfRes, _ := s.ProbeHTTP(ctx, handler, dialer, "https://www.cloudflare.com/cdn-cgi/trace", nil, 2048)
		if cfRes != nil {
			if traceLoc := extractCFLoc(cfRes.Body); blockedRegions2Letter[traceLoc] {
				return 0, fmt.Errorf("region %s blocked", traceLoc)
			}
		}
	}

	iosRes, iosErr := s.ProbeHTTP(ctx, handler, dialer, "https://ios.chat.openai.com/", nil, 2048)
	if iosErr != nil || iosRes == nil {
		if iosErr != nil {
			return 0, iosErr
		}
		return 0, fmt.Errorf("unreachable")
	}
	lower := strings.ToLower(iosRes.Body)
	if strings.Contains(lower, "disallowed isp") || strings.Contains(lower, "sorry, you have been blocked") {
		return 0, fmt.Errorf("openai blocked IP")
	}
	if iosRes.StatusCode >= 200 && iosRes.StatusCode < 400 || strings.Contains(lower, "request is not allowed") {
		return iosRes.Latency, nil
	}
	return 0, fmt.Errorf("HTTP %d", iosRes.StatusCode)
}

const geminiMaxBodyBytes = 2_000_000

func resolveGeminiRedirect(baseRaw, location string) (string, error) {
	loc := strings.TrimSpace(location)
	if loc == "" {
		return "", fmt.Errorf("empty redirect location")
	}
	lowerLoc := strings.ToLower(loc)
	if strings.Contains(lowerLoc, "/sorry/") {
		return "", fmt.Errorf("google bot-protection")
	}
	baseURL, err := url.Parse(baseRaw)
	if err != nil {
		return "", fmt.Errorf("invalid base url: %w", err)
	}
	refURL, err := url.Parse(loc)
	if err != nil {
		return "", fmt.Errorf("invalid redirect location: %w", err)
	}
	resolved := baseURL.ResolveReference(refURL)
	host := strings.ToLower(resolved.Hostname())
	if host != "gemini.google.com" && !strings.HasSuffix(host, ".gemini.google.com") {
		return "", fmt.Errorf("redirect to %s", host)
	}
	if strings.HasPrefix(strings.ToLower(resolved.Path), "/faq") {
		return "", fmt.Errorf("redirect to gemini faq (region unsupported)")
	}
	return resolved.String(), nil
}

func geminiBodyHasResult(window string) bool {
	lower := strings.ToLower(window)
	if strings.Contains(lower, "not supported in your country") ||
		strings.Contains(lower, "isn't supported in your country") ||
		strings.Contains(lower, "is not available in your country") ||
		strings.Contains(lower, "isn't available in your country") ||
		strings.Contains(lower, "not available in your region") ||
		strings.Contains(lower, "isn't available in your region") ||
		strings.Contains(lower, "unusual traffic") ||
		strings.Contains(lower, "/sorry/") {
		return true
	}
	if geminiMarkerRegex.MatchString(window) {
		return true
	}
	if sm := geminiStatusCodeRegex.FindStringSubmatch(window); len(sm) >= 2 && sm[1] != "200" {
		return true
	}
	return false
}

func (s *Scanner) checkGeminiLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer) (time.Duration, error) {
	targetURL := "https://gemini.google.com/app"
	var res *HTTPProbeResult
	for hop := 0; hop < 3; hop++ {
		var err error
		res, err = s.ProbeHTTPStream(ctx, handler, dialer, targetURL, geminiHeaders, geminiMaxBodyBytes, geminiBodyHasResult)
		if err != nil || res == nil {
			return 0, err
		}
		if res.StatusCode == 429 || strings.Contains(strings.ToLower(res.Location), "/sorry/") {
			return 0, fmt.Errorf("google bot-protection")
		}
		if res.StatusCode >= 300 && res.StatusCode < 400 {
			nextURL, redirErr := resolveGeminiRedirect(targetURL, res.Location)
			if redirErr != nil {
				return 0, redirErr
			}
			targetURL = nextURL
			continue
		}
		break
	}
	if res == nil {
		return 0, fmt.Errorf("empty response")
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return 0, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	lower := strings.ToLower(res.Body)
	if strings.Contains(lower, "not supported in your country") ||
		strings.Contains(lower, "isn't supported in your country") ||
		strings.Contains(lower, "is not available in your country") ||
		strings.Contains(lower, "isn't available in your country") ||
		strings.Contains(lower, "not available in your region") ||
		strings.Contains(lower, "isn't available in your region") ||
		strings.Contains(lower, "unusual traffic") ||
		strings.Contains(lower, "/sorry/") {
		return 0, fmt.Errorf("region or bot blocked")
	}
	m := geminiMarkerRegex.FindStringSubmatch(res.Body)
	if len(m) < 2 {
		if sm := geminiStatusCodeRegex.FindStringSubmatch(res.Body); len(sm) >= 2 && sm[1] != "200" {
			return 0, fmt.Errorf("gemini marker status %s", sm[1])
		}
		return 0, fmt.Errorf("gemini region marker missing (body=%d bytes)", len(res.Body))
	}
	reg := strings.ToUpper(m[1])
	if blockedGeminiRegions[reg] {
		return 0, fmt.Errorf("region %s blocked", reg)
	}
	return res.Latency, nil
}

func (s *Scanner) checkDiscordLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, rawURL string) (time.Duration, error) {
	res, err := s.ProbeHTTP(ctx, handler, dialer, "https://discord.com/api/v9/auth/location-metadata", nil, 1024)
	if err == nil && res != nil && res.StatusCode >= 200 && res.StatusCode < 400 {
		return res.Latency, nil
	}
	return s.checkGenericService(ctx, handler, dialer, rawURL)
}

func (s *Scanner) checkTelegramLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, rawURL string) (time.Duration, error) {
	candidates := []string{"https://telegram.org", "https://api.telegram.org", "https://t.me"}
	if rawURL != "" && rawURL != "https://web.telegram.org" {
		candidates = append([]string{rawURL}, candidates...)
	}
	var lastErr error
	for _, u := range candidates {
		res, err := s.ProbeHTTP(ctx, handler, dialer, u, nil, 0)
		if err == nil && res != nil && ((res.StatusCode >= 200 && res.StatusCode < 400) || res.StatusCode == 403 || res.StatusCode == 429) {
			return res.Latency, nil
		}
		if err != nil {
			lastErr = err
		} else if res != nil {
			lastErr = fmt.Errorf("HTTP %d", res.StatusCode)
		}
	}
	return 0, lastErr
}

func (s *Scanner) checkWhatsAppLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, rawURL string) (time.Duration, error) {
	for _, u := range []string{rawURL, "https://v.whatsapp.net/v2/exist", "https://www.whatsapp.com"} {
		if strings.TrimSpace(u) == "" {
			continue
		}
		res, err := s.ProbeHTTP(ctx, handler, dialer, u, whatsappHeaders, 0)
		if err == nil && res != nil && res.StatusCode >= 200 && res.StatusCode < 400 {
			return res.Latency, nil
		}
	}
	return 0, fmt.Errorf("whatsapp unreachable")
}

func (s *Scanner) checkInstagramLite(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, rawURL string) (time.Duration, error) {
	for _, u := range []string{rawURL, "https://www.instagram.com"} {
		if strings.TrimSpace(u) == "" {
			continue
		}
		res, err := s.ProbeHTTP(ctx, handler, dialer, u, nil, 0)
		if err == nil && res != nil && ((res.StatusCode >= 200 && res.StatusCode < 400) || res.StatusCode == 403) {
			return res.Latency, nil
		}
	}
	return 0, fmt.Errorf("instagram unreachable")
}

func (s *Scanner) checkGenericService(ctx context.Context, handler ProxyHandler, dialer internet.Dialer, rawURL string) (time.Duration, error) {
	res, err := s.ProbeHTTP(ctx, handler, dialer, rawURL, nil, 0)
	if err != nil || res == nil {
		return 0, err
	}
	if strings.Contains(res.Location, "/sorry/") {
		return 0, fmt.Errorf("bot-protection redirect")
	}
	is204Url := strings.Contains(rawURL, "generate_204")
	if is204Url {
		if res.StatusCode >= 200 && res.StatusCode < 400 {
			return res.Latency, nil
		}
		return 0, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	if (res.StatusCode >= 200 && res.StatusCode < 400) ||
		res.StatusCode == 401 || res.StatusCode == 403 || res.StatusCode == 404 || res.StatusCode == 429 {
		return res.Latency, nil
	}
	return 0, fmt.Errorf("HTTP %d", res.StatusCode)
}
