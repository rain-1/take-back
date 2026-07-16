package api

import (
	"net/http/httptest"
	"testing"
	"time"

	"golang.org/x/time/rate"
)

func TestKeyedLimiterBurstThenBlock(t *testing.T) {
	// Effectively no refill during the test; burst of 3.
	kl := newKeyedLimiter(rate.Every(time.Hour), 3)
	for i := 0; i < 3; i++ {
		if !kl.Allow("1.2.3.4") {
			t.Fatalf("request %d should be allowed within burst", i)
		}
	}
	if kl.Allow("1.2.3.4") {
		t.Fatal("4th request should be blocked")
	}
	// A different key has its own bucket.
	if !kl.Allow("5.6.7.8") {
		t.Fatal("different key should have its own allowance")
	}
}

func TestClientIPPrefersCloudflareHeader(t *testing.T) {
	r := httptest.NewRequest("POST", "/api/login", nil)
	r.RemoteAddr = "10.0.0.1:5555"
	r.Header.Set("X-Forwarded-For", "9.9.9.9, 10.0.0.2")
	r.Header.Set("CF-Connecting-IP", "203.0.113.7")
	if got := clientIP(r); got != "203.0.113.7" {
		t.Fatalf("clientIP = %q, want Cloudflare header 203.0.113.7", got)
	}

	// Without the CF header, first XFF entry wins.
	r.Header.Del("CF-Connecting-IP")
	if got := clientIP(r); got != "9.9.9.9" {
		t.Fatalf("clientIP = %q, want XFF 9.9.9.9", got)
	}

	// With neither, the socket peer (host only) is used.
	r.Header.Del("X-Forwarded-For")
	if got := clientIP(r); got != "10.0.0.1" {
		t.Fatalf("clientIP = %q, want 10.0.0.1", got)
	}
}
