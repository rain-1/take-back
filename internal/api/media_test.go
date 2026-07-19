package api

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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
		{"/media/", http.StatusNotFound},          // root listing blocked
		{"/media/abc123.jpg", http.StatusOK},      // a real file still served
		{"/media/nope.jpg", http.StatusNotFound},  // missing file
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", c.path, nil))
		if rec.Code != c.want {
			t.Errorf("%s: got %d, want %d", c.path, rec.Code, c.want)
		}
	}
}
