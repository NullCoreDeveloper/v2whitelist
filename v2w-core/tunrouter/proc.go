package tunrouter

import (
	"net/netip"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ProcessResolver определяет процесс-владелец сетевого сокета с кэшированием.
type ProcessResolver struct {
	mu      sync.RWMutex
	cache   map[procCacheKey]procCacheEntry
	inodes  map[uint32]inodeCacheEntry
	ttl     time.Duration
}

type procCacheKey struct {
	proto string
	port  uint16
}

type procCacheEntry struct {
	info      ProcessInfo
	expiresAt time.Time
}

type inodeCacheEntry struct {
	info      ProcessInfo
	expiresAt time.Time
}

func NewProcessResolver() *ProcessResolver {
	return &ProcessResolver{
		cache:  make(map[procCacheKey]procCacheEntry),
		inodes: make(map[uint32]inodeCacheEntry),
		ttl:    3 * time.Second,
	}
}

// Resolve находит процесс-владелец по протоколу ("tcp"/"udp") и адресу источника (внутри TUN).
func (r *ProcessResolver) Resolve(proto string, src, dst netip.AddrPort) (ProcessInfo, bool) {
	if !src.IsValid() || src.Port() == 0 {
		return ProcessInfo{}, false
	}
	key := procCacheKey{proto: proto, port: src.Port()}
	now := time.Now()

	r.mu.RLock()
	if ent, ok := r.cache[key]; ok && now.Before(ent.expiresAt) {
		r.mu.RUnlock()
		return ent.info, ent.info.PID > 0 || ent.info.Name != "" || ent.info.Path != ""
	}
	r.mu.RUnlock()

	info, ok := r.lookupPlatform(proto, src, dst)
	if ok {
		if info.Name == "" && info.Path != "" {
			info.Name = filepath.Base(strings.ReplaceAll(info.Path, "\\", "/"))
		}
		r.mu.Lock()
		if len(r.cache) > 4096 {
			for k, v := range r.cache {
				if now.After(v.expiresAt) {
					delete(r.cache, k)
				}
			}
		}
		r.cache[key] = procCacheEntry{info: info, expiresAt: now.Add(r.ttl)}
		r.mu.Unlock()
	}
	return info, ok
}
