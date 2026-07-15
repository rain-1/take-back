// Command web serves the take-back browser client.
//
// It hosts the static HTML/JS interface and reverse-proxies the backend paths
// (/api, /media, /ws, including their WebSocket upgrades) to the server, so the
// browser sees a single origin and session cookies just work. WebRTC media
// still flows directly between browsers.
package main

import (
	"embed"
	"flag"
	"io/fs"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
)

//go:embed static
var staticFS embed.FS

func main() {
	addr := flag.String("addr", ":8080", "listen address for the web client")
	backend := flag.String("backend", "http://localhost:8081", "server base URL to proxy API/signaling to")
	flag.Parse()

	target, err := url.Parse(*backend)
	if err != nil {
		log.Fatalf("bad backend url: %v", err)
	}
	// httputil.ReverseProxy transparently handles WebSocket upgrades (/ws,
	// /api/events) as well as plain HTTP.
	proxy := httputil.NewSingleHostReverseProxy(target)

	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatal(err)
	}
	fileServer := http.FileServer(http.FS(sub))

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if isBackendPath(r.URL.Path) {
			proxy.ServeHTTP(w, r)
			return
		}
		fileServer.ServeHTTP(w, r)
	})

	log.Printf("take-back web client on %s (backend %s)", *addr, *backend)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}

// isBackendPath reports whether a request should be proxied to the server
// rather than served from the embedded static assets.
func isBackendPath(p string) bool {
	return strings.HasPrefix(p, "/api/") ||
		strings.HasPrefix(p, "/media/") ||
		p == "/ws"
}
