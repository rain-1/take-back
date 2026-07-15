// Command web serves the take-back browser client.
//
// It hosts the static HTML/JS interface. The page talks WebRTC directly between
// browsers, using the signaling server only to exchange connection metadata.
package main

import (
	"embed"
	"flag"
	"html/template"
	"io/fs"
	"log"
	"net/http"
)

//go:embed static
var staticFS embed.FS

func main() {
	addr := flag.String("addr", ":8080", "listen address for the web client")
	signal := flag.String("signal", "ws://localhost:8081/ws", "signaling server WebSocket URL")
	flag.Parse()

	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatal(err)
	}

	indexTmpl := template.Must(template.ParseFS(staticFS, "static/index.html"))

	mux := http.NewServeMux()
	// Serve the index with the signaling URL injected so the client knows where
	// to connect without hard-coding it.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.FileServer(http.FS(sub)).ServeHTTP(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := indexTmpl.Execute(w, map[string]string{"SignalURL": *signal}); err != nil {
			log.Printf("template: %v", err)
		}
	})

	log.Printf("take-back web client on %s (signaling via %s)", *addr, *signal)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}
