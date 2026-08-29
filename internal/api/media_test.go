package api

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// makePNG returns a solid-color PNG of the given size.
func makePNG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{60, 120, 220, 255})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatalf("encode: %v", err)
	}
	return buf.Bytes()
}

func TestThumbnailDownscalesPreservingAspect(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 600, 400))
	out := thumbnail(img)
	b := out.Bounds()
	if b.Dx() != thumbMax {
		t.Fatalf("width: want %d, got %d", thumbMax, b.Dx())
	}
	// 600x400 -> longest edge 320 => height 320*400/600 = 213.
	if b.Dy() != 213 {
		t.Fatalf("height: want 213, got %d", b.Dy())
	}
}

func TestThumbnailNoUpscale(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 100, 80))
	out := thumbnail(img)
	if out.Bounds().Dx() != 100 || out.Bounds().Dy() != 80 {
		t.Fatalf("small image should be unchanged, got %v", out.Bounds())
	}
}

func TestThumbnailTallImage(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 400, 800))
	out := thumbnail(img)
	if out.Bounds().Dy() != thumbMax {
		t.Fatalf("tall image height: want %d, got %d", thumbMax, out.Bounds().Dy())
	}
	if out.Bounds().Dx() != 160 { // 400*320/800
		t.Fatalf("tall image width: want 160, got %d", out.Bounds().Dx())
	}
}

func TestSaveImageWritesOriginalAndThumb(t *testing.T) {
	dir := t.TempDir()
	m, err := NewMediaStore(dir)
	if err != nil {
		t.Fatalf("NewMediaStore: %v", err)
	}

	imageFile, thumbFile, err := m.SaveImage(bytes.NewReader(makePNG(t, 500, 500)))
	if err != nil {
		t.Fatalf("SaveImage: %v", err)
	}
	if imageFile == "" || thumbFile == "" || imageFile == thumbFile {
		t.Fatalf("bad filenames: %q %q", imageFile, thumbFile)
	}

	// Both files exist on disk.
	for _, f := range []string{imageFile, thumbFile} {
		if _, err := os.Stat(filepath.Join(dir, f)); err != nil {
			t.Fatalf("missing file %s: %v", f, err)
		}
	}

	// The thumbnail decodes and is downscaled from 500 to 320.
	tf, err := os.Open(filepath.Join(dir, thumbFile))
	if err != nil {
		t.Fatal(err)
	}
	defer tf.Close()
	cfg, _, err := image.DecodeConfig(tf)
	if err != nil {
		t.Fatalf("decode thumb: %v", err)
	}
	if cfg.Width != thumbMax || cfg.Height != thumbMax {
		t.Fatalf("thumb size: want %dx%d, got %dx%d", thumbMax, thumbMax, cfg.Width, cfg.Height)
	}
}

func TestSaveImageRejectsNonImage(t *testing.T) {
	m, err := NewMediaStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := m.SaveImage(bytes.NewReader([]byte("not an image"))); err == nil {
		t.Fatal("expected error for non-image input")
	}
}

// TestNoDirList makes sure the media handler serves files by their hash name
// but refuses to list the directory (which would enumerate every upload).
func TestNoDirList(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "abc123.jpg"), []byte("img"), 0o644); err != nil {
		t.Fatal(err)
	}
	h := http.StripPrefix("/media/", noDirList(http.FileServer(http.Dir(dir))))

	cases := []struct {
		path string
		want int
	}{
		{"/media/", http.StatusNotFound},         // root listing blocked
		{"/media/abc123.jpg", http.StatusOK},     // a real file still served
		{"/media/nope.jpg", http.StatusNotFound}, // missing file
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", c.path, nil))
		if rec.Code != c.want {
			t.Errorf("%s: got %d, want %d", c.path, rec.Code, c.want)
		}
	}
}

func TestSaveUploadClassifiesAndStores(t *testing.T) {
	m, err := NewMediaStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}

	// A real image still gets the decode + thumbnail treatment.
	up, err := m.SaveUpload(bytes.NewReader(makePNG(t, 400, 400)), "holiday snap.PNG")
	if err != nil {
		t.Fatalf("SaveUpload(png): %v", err)
	}
	if up.Kind != KindImage || up.Thumb == "" {
		t.Fatalf("png: want image kind with a thumb, got %+v", up)
	}
	if up.Name != "holiday snap.PNG" {
		t.Fatalf("png name: got %q", up.Name)
	}

	// A non-image is stored verbatim, classified by extension, with no thumb.
	body := []byte("\x00\x00\x00\x18ftypmp42 not really a video")
	up, err = m.SaveUpload(bytes.NewReader(body), "clip.mp4")
	if err != nil {
		t.Fatalf("SaveUpload(mp4): %v", err)
	}
	if up.Kind != KindVideo || up.Thumb != "" || up.Size != int64(len(body)) {
		t.Fatalf("mp4: got %+v", up)
	}
	got, err := os.ReadFile(filepath.Join(m.Dir, up.File))
	if err != nil {
		t.Fatalf("stored file: %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatal("non-image should be stored byte-for-byte")
	}

	// Something claiming to be an image but that won't decode must not be
	// served as one — it falls back to an opaque file.
	up, err = m.SaveUpload(bytes.NewReader([]byte("<script>alert(1)</script>")), "evil.png")
	if err != nil {
		t.Fatalf("SaveUpload(fake png): %v", err)
	}
	if up.Kind != KindFile {
		t.Fatalf("undecodable .png should be KindFile, got %q", up.Kind)
	}
}

func TestSaveUploadRejectsEmptyAndOversize(t *testing.T) {
	m, err := NewMediaStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.SaveUpload(bytes.NewReader(nil), "empty.bin"); err == nil {
		t.Fatal("expected an error for an empty upload")
	}
	// A reader longer than the cap must be refused rather than silently cut.
	big := io.LimitReader(neverEnding{}, MaxUploadBytes+10)
	if _, err := m.SaveUpload(big, "huge.bin"); err == nil {
		t.Fatal("expected an error for an oversize upload")
	}
	// ...and it must not leave the temp file behind.
	ents, _ := os.ReadDir(m.Dir)
	if len(ents) != 0 {
		t.Fatalf("temp files left behind: %v", ents)
	}
}

// neverEnding yields zero bytes forever, for the oversize test.
type neverEnding struct{}

func (neverEnding) Read(p []byte) (int, error) { return len(p), nil }

func TestSanitizeNameAndExt(t *testing.T) {
	cases := []struct{ in, name, ext string }{
		{"../../etc/passwd", "passwd", ".bin"},
		{`C:\Users\me\report.PDF`, "report.PDF", ".pdf"},
		{"quote\"name.mp3", "quotename.mp3", ".mp3"},
		{"", "file", ".bin"},
		{"noext", "noext", ".bin"},
		{"weird.tar.gz", "weird.tar.gz", ".gz"},
		{"bad.e/xt", "xt", ".bin"},
	}
	for _, c := range cases {
		if got := sanitizeName(c.in); got != c.name {
			t.Errorf("sanitizeName(%q) = %q, want %q", c.in, got, c.name)
		}
		if got := extOf(sanitizeName(c.in)); got != c.ext {
			t.Errorf("extOf(%q) = %q, want %q", c.in, got, c.ext)
		}
	}
}

// TestServeMediaHeaders is the security-relevant one: anything we can't prove is
// safe to render must arrive as an opaque download, never as same-origin script.
func TestServeMediaHeaders(t *testing.T) {
	dir := t.TempDir()
	for _, f := range []string{"a.jpg", "b.mp4", "c.pdf", "d.html", "e.svg", "f.bin"} {
		if err := os.WriteFile(filepath.Join(dir, f), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	a := &API{Media: &MediaStore{Dir: dir}}
	h := http.StripPrefix("/media/", a.serveMedia(http.FileServer(http.Dir(dir))))

	inline := map[string]string{
		"a.jpg": "image/jpeg", "b.mp4": "video/mp4", "c.pdf": "application/pdf",
	}
	for f, want := range inline {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", "/media/"+f, nil))
		if got := rec.Header().Get("Content-Type"); got != want {
			t.Errorf("%s: Content-Type %q, want %q", f, got, want)
		}
		if rec.Header().Get("Content-Disposition") != "" {
			t.Errorf("%s: should render inline, got a Content-Disposition", f)
		}
	}

	// html/svg/unknown: octet-stream + attachment, so the browser can't execute
	// them in our origin.
	for _, f := range []string{"d.html", "e.svg", "f.bin"} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", "/media/"+f+"?name=report.html", nil))
		if got := rec.Header().Get("Content-Type"); got != "application/octet-stream" {
			t.Errorf("%s: Content-Type %q, want application/octet-stream", f, got)
		}
		if cd := rec.Header().Get("Content-Disposition"); !strings.HasPrefix(cd, "attachment") {
			t.Errorf("%s: Content-Disposition %q, want an attachment", f, cd)
		}
		if got := rec.Header().Get("X-Content-Type-Options"); got != "nosniff" {
			t.Errorf("%s: missing nosniff, got %q", f, got)
		}
	}

	// Directory listings stay blocked.
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/media/", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("directory listing: got %d, want 404", rec.Code)
	}
}

func TestMediaURLsBackCompat(t *testing.T) {
	// An image fills imageUrl/thumbUrl, so pre-1.18 clients keep rendering it.
	media, img, thumb := mediaURLs("a.jpg", "a_thumb.jpg", KindImage, "a.jpg")
	if media != "/media/a.jpg" || img != "/media/a.jpg" || thumb != "/media/a_thumb.jpg" {
		t.Fatalf("image: %q %q %q", media, img, thumb)
	}
	// A video does NOT, so those clients show a plain message instead of a
	// broken picture — and the download name rides along in the query.
	media, img, thumb = mediaURLs("b.mp4", "", KindVideo, "my clip.mp4")
	if img != "" || thumb != "" {
		t.Fatalf("video should not set image urls: %q %q", img, thumb)
	}
	if media != "/media/b.mp4?name=my+clip.mp4" {
		t.Fatalf("video media url: %q", media)
	}
	if media, _, _ := mediaURLs("", "", "", ""); media != "" {
		t.Fatalf("no attachment should yield no url, got %q", media)
	}
}
