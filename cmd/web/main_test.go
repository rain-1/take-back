package main

import (
	"io/fs"
	"strings"
	"testing"

	"github.com/rain1/take-back/internal/version"
)

// TestRenderPagesStampsAssetVersion is the guard on the cache-busting scheme:
// if the rewrite silently stopped working, a deploy would look fine locally and
// then sit invisible behind Cloudflare's edge cache in production, which is
// exactly the failure it exists to prevent.
func TestRenderPagesStampsAssetVersion(t *testing.T) {
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		t.Fatal(err)
	}
	pages, err := renderPages(sub)
	if err != nil {
		t.Fatalf("renderPages: %v", err)
	}
	if len(pages) == 0 {
		t.Fatal("no HTML pages found in the embedded assets")
	}

	want := "?v=" + version.Version
	stamped := 0
	for name, body := range pages {
		if strings.Contains(body, "?v=dev") {
			t.Errorf("%s still references ?v=dev after rendering", name)
		}
		stamped += strings.Count(body, want)
	}
	if stamped == 0 {
		t.Fatalf("no asset URL carries %q — cache busting is not wired up", want)
	}
}

// The pages served must be the rendered ones, including for "/".
func TestPagePath(t *testing.T) {
	for in, want := range map[string]string{
		"/":             "/index.html",
		"/index.html":   "/index.html",
		"/call.html":    "/call.html",
		"/call-core.js": "/call-core.js", // not a page; falls through to the file server
	} {
		if got := pagePath(in); got != want {
			t.Errorf("pagePath(%q) = %q, want %q", in, got, want)
		}
	}
}
