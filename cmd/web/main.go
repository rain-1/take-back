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
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/rain1/take-back/internal/version"
)

//go:embed static
var staticFS embed.FS

// buildTime is the modification time reported for the rendered HTML pages.
// Embedded files have no useful mtime, and it only has to change per build for
// conditional requests to behave — the version string does that.
var buildTime = time.Now()

// assetVersion rewrites `?v=dev` in the HTML to the running build's version, so
// every deploy asks for a URL the caches have never seen.
//
// This exists because Cloudflare applies its own multi-hour edge TTL to static
// extensions (.js/.css/.svg) regardless of what we send, which means a deploy
// can sit invisible behind an edge-cached copy of the PREVIOUS file — or, worse,
// behind a cached 404 from before the file existed. Stamping the version into
// the query string sidesteps the edge cache entirely instead of fighting it, and
// it's automatic: there is no list to remember to bump.
var assetVersion = strings.NewReplacer("?v=dev", "?v="+version.Version)

// renderPages reads every .html file out of the embedded assets and applies
// assetVersion once, at startup, rather than on each request.
func renderPages(sub fs.FS) (map[string]string, error) {
	pages := map[string]string{}
	entries, err := fs.ReadDir(sub, ".")
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".html") {
			continue
		}
		raw, err := fs.ReadFile(sub, e.Name())
		if err != nil {
			return nil, err
		}
		pages["/"+e.Name()] = assetVersion.Replace(string(raw))
	}
	return pages, nil
}

// pagePath maps a request path to a page key, treating "/" as index.html the
// way http.FileServer does.
func pagePath(p string) string {
	if p == "/" {
		return "/index.html"
	}
	return p
}

func main() {
	addr := flag.String("addr", ":8080", "listen address for the web client")
	backend := flag.String("backend", "http://localhost:8081", "server base URL to proxy API/signaling to")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("%s web %s (protocol %d)\n", version.Name, version.Version, version.Protocol)
		return
	}

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
	pages, err := renderPages(sub)
	if err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if isBackendPath(r.URL.Path) {
			proxy.ServeHTTP(w, r)
			return
		}
		// The HTML pages carry no Cache-Control of their own, so browsers cached
		// them heuristically and could keep showing a stale UI across deploys
		// (e.g. a missing button). "no-cache" means "revalidate before reuse", so
		// each load is a cheap 304 when nothing changed but a new build is picked
		// up immediately.
		w.Header().Set("Cache-Control", "no-cache")
		if page, ok := pages[pagePath(r.URL.Path)]; ok {
			http.ServeContent(w, r, "index.html", buildTime, strings.NewReader(page))
			return
		}
		fileServer.ServeHTTP(w, r)
	})

	log.Printf("take-back web %s (protocol %d) on %s (backend %s)",
		version.Version, version.Protocol, *addr, *backend)
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
