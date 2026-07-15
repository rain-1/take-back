package api

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/image/draw"

	// Register GIF decoding too, so animated/static GIFs can be thumbnailed.
	_ "image/gif"
)

// thumbMax is the longest-edge size (px) of generated thumbnails.
const thumbMax = 320

// MediaStore saves uploaded images and their thumbnails under Dir. Files are
// served read-only from /media/ by the API router.
type MediaStore struct {
	Dir string
}

// NewMediaStore ensures the directory exists.
func NewMediaStore(dir string) (*MediaStore, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &MediaStore{Dir: dir}, nil
}

// SaveImage decodes an uploaded image, stores the original (re-encoded to a
// known format) and a downscaled thumbnail, and returns their filenames.
func (m *MediaStore) SaveImage(r io.Reader) (imageFile, thumbFile string, err error) {
	img, format, err := image.Decode(r)
	if err != nil {
		return "", "", fmt.Errorf("not a decodable image: %w", err)
	}

	ext := "jpg"
	if format == "png" {
		ext = "png"
	}
	id := randHex()
	imageFile = id + "." + ext
	thumbFile = id + "_thumb.jpg"

	if err := m.encode(filepath.Join(m.Dir, imageFile), img, ext); err != nil {
		return "", "", err
	}
	if err := m.encode(filepath.Join(m.Dir, thumbFile), thumbnail(img), "jpg"); err != nil {
		return "", "", err
	}
	return imageFile, thumbFile, nil
}

func (m *MediaStore) encode(path string, img image.Image, ext string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	if ext == "png" {
		return png.Encode(f, img)
	}
	return jpeg.Encode(f, img, &jpeg.Options{Quality: 85})
}

// thumbnail returns img scaled so its longest edge is thumbMax, preserving
// aspect ratio (no upscaling).
func thumbnail(img image.Image) image.Image {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= thumbMax && h <= thumbMax {
		return img
	}
	scale := float64(thumbMax) / float64(w)
	if h > w {
		scale = float64(thumbMax) / float64(h)
	}
	nw, nh := int(float64(w)*scale), int(float64(h)*scale)
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
	return dst
}

func randHex() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
