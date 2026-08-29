package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// The session cookie is a 30-day bearer token. It must carry Secure whenever the
// client reached us over TLS, or an accidental plaintext request to the same
// host puts it on the wire in clear.
func TestIsHTTPS(t *testing.T) {
	cases := []struct {
		name  string
		build func() *http.Request
		want  bool
	}{
		{"plain http", func() *http.Request {
			return httptest.NewRequest("GET", "http://x/api/me", nil)
		}, false},
		{"forwarded https (what production sends)", func() *http.Request {
			r := httptest.NewRequest("GET", "http://x/api/me", nil)
			r.Header.Set("X-Forwarded-Proto", "https")
			return r
		}, true},
		{"forwarded chain, client spoke https", func() *http.Request {
			r := httptest.NewRequest("GET", "http://x/api/me", nil)
			r.Header.Set("X-Forwarded-Proto", "https, http")
			return r
		}, true},
		{"forwarded http", func() *http.Request {
			r := httptest.NewRequest("GET", "http://x/api/me", nil)
			r.Header.Set("X-Forwarded-Proto", "http")
			return r
		}, false},
		{"case-insensitive", func() *http.Request {
			r := httptest.NewRequest("GET", "http://x/api/me", nil)
			r.Header.Set("X-Forwarded-Proto", "HTTPS")
			return r
		}, true},
	}
	for _, c := range cases {
		if got := isHTTPS(c.build()); got != c.want {
			t.Errorf("%s: isHTTPS = %v, want %v", c.name, got, c.want)
		}
	}
}
