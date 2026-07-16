package api

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// keyedLimiter holds one token-bucket rate limiter per key (e.g. client IP),
// evicting buckets that have been idle for a while so the map can't grow
// unbounded.
type keyedLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	r       rate.Limit
	burst   int
}

type bucket struct {
	lim  *rate.Limiter
	seen time.Time
}

// newKeyedLimiter allows `burst` requests immediately, then refills at rate r.
func newKeyedLimiter(r rate.Limit, burst int) *keyedLimiter {
	kl := &keyedLimiter{buckets: map[string]*bucket{}, r: r, burst: burst}
	go kl.cleanupLoop()
	return kl
}

// Allow reports whether an event for key may proceed now.
func (kl *keyedLimiter) Allow(key string) bool {
	kl.mu.Lock()
	b := kl.buckets[key]
	if b == nil {
		b = &bucket{lim: rate.NewLimiter(kl.r, kl.burst)}
		kl.buckets[key] = b
	}
	b.seen = time.Now()
	kl.mu.Unlock()
	return b.lim.Allow()
}

func (kl *keyedLimiter) cleanupLoop() {
	for range time.Tick(10 * time.Minute) {
		cutoff := time.Now().Add(-30 * time.Minute)
		kl.mu.Lock()
		for k, b := range kl.buckets {
			if b.seen.Before(cutoff) {
				delete(kl.buckets, k)
			}
		}
		kl.mu.Unlock()
	}
}

// Rate limiters for the unauthenticated auth endpoints. Tuned to stop brute
// force / spam while staying invisible to a normal user:
//   - register: 5 accounts/hour per IP (burst 5).
//   - login:    ~1 attempt/6s per IP, burst 10 (a person retyping a password
//     is fine; a script hammering it is throttled).
var (
	registerLimiter = newKeyedLimiter(rate.Every(12*time.Minute), 5)
	loginLimiter    = newKeyedLimiter(rate.Every(6*time.Second), 10)
)

// clientIP extracts the real client address, trusting the header Cloudflare
// sets. Falls back to X-Forwarded-For then the socket peer for non-proxied
// deployments.
func clientIP(r *http.Request) string {
	if ip := r.Header.Get("CF-Connecting-IP"); ip != "" {
		return ip
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		return strings.TrimSpace(strings.Split(xff, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// allow checks the limiter for this request's client IP, writing a 429 and
// returning false when the caller should stop.
func allow(w http.ResponseWriter, r *http.Request, kl *keyedLimiter) bool {
	if !kl.Allow(clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "too many attempts — please wait and try again")
		return false
	}
	return true
}
