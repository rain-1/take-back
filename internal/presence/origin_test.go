package presence

import (
	"net/http"
	"testing"
)

// The events socket is authenticated by an ambient session cookie, so accepting
// any Origin let a page on a same-site sibling origin open a victim's private
// event stream. WebSockets bypass CORS, so this check is the only guard.
func TestCheckOriginRejectsForeignBrowserOrigins(t *testing.T) {
	cases := []struct {
		name, host, origin string
		want               bool
	}{
		{"no origin (CLI / Android)", "takeback.example.org", "", true},
		{"same host over https", "takeback.example.org", "https://takeback.example.org", true},
		{"same host over http (local dev)", "localhost:8080", "http://localhost:8080", true},
		{"host casing differs", "TakeBack.example.org", "https://takeback.example.org", true},

		{"sibling subdomain", "takeback.example.org", "https://evil.example.org", false},
		{"parent domain", "takeback.example.org", "https://example.org", false},
		{"unrelated site", "takeback.example.org", "https://evil.test", false},
		{"suffix trick", "takeback.example.org", "https://takeback.example.org.evil.test", false},
		{"different port", "localhost:8080", "http://localhost:9999", false},
		{"unparseable", "takeback.example.org", "://", false},
	}
	for _, c := range cases {
		r := &http.Request{Host: c.host, Header: http.Header{}}
		if c.origin != "" {
			r.Header.Set("Origin", c.origin)
		}
		if got := sameOriginOrNoOrigin(r); got != c.want {
			t.Errorf("%s: Origin %q against host %q = %v, want %v",
				c.name, c.origin, c.host, got, c.want)
		}
	}
}
