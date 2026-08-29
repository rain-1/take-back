package api

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/image/draw"

	// Register GIF decoding too, so animated/static GIFs can be thumbnailed.
	_ "image/gif"
)

// thumbMax is the longest-edge size (px) of generated thumbnails.
const thumbMax = 320

// MaxUploadBytes caps a single attachment. Images were the only attachment for
// a long time and 16 MB was plenty; video and audio need considerably more.
//
// The ceiling is not ours: takeback sits behind Cloudflare, whose free plan
// rejects request bodies over 100 MB at the edge — a larger cap here would just
// turn into an unexplained 413 the app never sees. 95 MB leaves room for the
// multipart envelope. nginx's client_max_body_size is set to match.
const MaxUploadBytes = 95 << 20

// maxPixels caps the DECODED size of an uploaded image, independently of its
// compressed size. A 90 MB budget says nothing about pixels: a highly compressed
// PNG of a few hundred KB can decode to hundreds of megapixels, and image.Decode
// allocates roughly 4 bytes per pixel before anything else runs — so the byte
// cap alone left a decompression bomb that could exhaust the server's memory.
// 50 MP is comfortably above any real camera or screenshot.
const maxPixels = 50_000_000

// maxAvatarBytes bounds a profile-picture upload. Avatars are downscaled to a
// 320px thumbnail, so nothing large is ever useful here.
const maxAvatarBytes = 12 << 20

// multipartOverhead is the slack allowed above MaxUploadBytes for the multipart
// envelope — boundaries, the other form fields, headers. Small and fixed, so the
// body limit stays effectively the file limit.
const multipartOverhead = 1 << 20

// headerPeek is how much of an upload we look at to read its dimensions. PNG and
// GIF declare them in the first few dozen bytes; JPEG's SOF marker can sit
// further in, after the EXIF block, so allow generous room.
const headerPeek = 128 << 10

// Media kinds, as stored on a message and sent to clients. Kind decides how a
// client renders the attachment: inline picture, <video>, <audio>, or a
// download chip.
const (
	KindImage = "image"
	KindVideo = "video"
	KindAudio = "audio"
	KindFile  = "file"
)

// MediaStore saves uploaded attachments (and thumbnails for images) under Dir.
// Files are served read-only from /media/ by the API router.
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
//
// Re-encoding is the point: it guarantees the bytes we serve really are an
// image we produced, so an "image" attachment can never be a disguised script.
func (m *MediaStore) SaveImage(r io.Reader) (imageFile, thumbFile string, err error) {
	// Check the declared dimensions BEFORE decoding. DecodeConfig reads only the
	// header and allocates nothing, so an image claiming to be enormous is
	// refused without ever materialising its pixels.
	//
	// Peek rather than Read: DecodeConfig must not consume the header, because
	// the full Decode below has to start from the first byte. Peek leaves the
	// bytes in the buffer, so `buffered` is still positioned at the start.
	buffered := bufio.NewReaderSize(r, headerPeek)
	head, err := buffered.Peek(headerPeek)
	if err != nil && len(head) == 0 {
		return "", "", fmt.Errorf("not a decodable image: %w", err)
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(head))
	if err != nil {
		return "", "", fmt.Errorf("not a decodable image: %w", err)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		return "", "", fmt.Errorf("image has no size")
	}
	if int64(cfg.Width)*int64(cfg.Height) > maxPixels {
		return "", "", fmt.Errorf("image is too large: %dx%d is over the %d megapixel limit",
			cfg.Width, cfg.Height, maxPixels/1_000_000)
	}

	img, format, err := image.Decode(buffered)
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

// Upload is a stored attachment: the file to serve, an optional thumbnail
// (images only), and the metadata a client needs to render it.
type Upload struct {
	File  string // storage key, e.g. "9f3c….mp4"
	Thumb string // storage key of the thumbnail, images only
	Kind  string // KindImage | KindVideo | KindAudio | KindFile
	Name  string // original filename, for display and download
	Size  int64
}

// SaveUpload stores any attachment. Images take the SaveImage path (re-encoded
// plus a thumbnail); everything else is stored byte-for-byte under a random
// name, because we can't re-encode a video or a PDF and shouldn't pretend to.
//
// Untrusted bytes served from our own origin are the risk here, so the stored
// extension is normalised and /media/ serves anything that isn't provably safe
// as an attachment download (see serveMedia).
func (m *MediaStore) SaveUpload(r io.Reader, filename string) (Upload, error) {
	// Buffer enough to attempt an image decode without consuming the reader for
	// the non-image path. Everything under the cap goes through a temp file so a
	// large video never has to sit in memory.
	tmp, err := os.CreateTemp(m.Dir, "up-*")
	if err != nil {
		return Upload{}, err
	}
	tmpName := tmp.Name()
	defer func() { tmp.Close(); os.Remove(tmpName) }()

	size, err := io.Copy(tmp, io.LimitReader(r, MaxUploadBytes+1))
	if err != nil {
		return Upload{}, err
	}
	if size > MaxUploadBytes {
		return Upload{}, fmt.Errorf("file too large (max %d MB)", MaxUploadBytes>>20)
	}
	if size == 0 {
		return Upload{}, fmt.Errorf("empty file")
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		return Upload{}, err
	}

	name := sanitizeName(filename)
	kind := kindFor(name)

	// Images keep their existing treatment: decode, re-encode, thumbnail. If the
	// decode fails the file isn't really an image (or is one we refuse, like a
	// pixel bomb), so fall through and store it as an opaque file rather than
	// rejecting the whole upload.
	ext := extOf(name)
	if kind == KindImage {
		if imgFile, thumbFile, err := m.SaveImage(tmp); err == nil {
			return Upload{File: imgFile, Thumb: thumbFile, Kind: KindImage, Name: name, Size: size}, nil
		}
		kind = KindFile
		// Drop the image extension along with the classification. Keeping ".png"
		// would make serveMedia hand these bytes back as image/png for a browser
		// to render — which for a rejected pixel bomb just moves the memory
		// exhaustion from our process to whoever opens the chat.
		ext = ".bin"
		if _, err := tmp.Seek(0, io.SeekStart); err != nil {
			return Upload{}, err
		}
	}

	out := randHex() + ext
	if err := os.Rename(tmpName, filepath.Join(m.Dir, out)); err != nil {
		// Rename can fail across filesystems; fall back to a copy.
		dst, cerr := os.Create(filepath.Join(m.Dir, out))
		if cerr != nil {
			return Upload{}, cerr
		}
		defer dst.Close()
		if _, cerr := io.Copy(dst, tmp); cerr != nil {
			return Upload{}, cerr
		}
	}
	return Upload{File: out, Kind: kind, Name: name, Size: size}, nil
}

// sanitizeName reduces a client-supplied filename to a plain base name. It is
// only ever used for display and for its extension — never to build a path —
// but stripping directory parts keeps it honest either way.
func sanitizeName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(strings.TrimSpace(name))
	if name == "." || name == "/" || name == "" {
		return "file"
	}
	// Control characters and quotes would leak into a Content-Disposition header.
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r == '"' {
			return -1
		}
		return r
	}, name)
	if len(name) > 120 {
		name = name[len(name)-120:]
	}
	if name == "" {
		return "file"
	}
	return name
}

// extOf returns the lowercased extension of name (with the dot), restricted to
// a short alphanumeric run so a crafted name can't produce a weird stored path.
func extOf(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if len(ext) < 2 || len(ext) > 8 {
		return ".bin"
	}
	for _, r := range ext[1:] {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') {
			return ".bin"
		}
	}
	return ext
}

// kindFor classifies an attachment by extension. Kind only drives how a client
// renders it; the security decision is made independently in serveMedia.
func kindFor(name string) string {
	switch extOf(name) {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp":
		return KindImage
	case ".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi":
		return KindVideo
	case ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac":
		return KindAudio
	}
	return KindFile
}

// inlineTypes maps the extensions we are willing to serve with a real
// Content-Type for the browser to render in place. Everything else is served as
// an opaque download, which is what keeps an uploaded .html or .svg from
// becoming same-origin script (both can carry <script>, and /media/ shares an
// origin with the app).
var inlineTypes = map[string]string{
	".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
	".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp",

	".mp4": "video/mp4", ".m4v": "video/mp4", ".webm": "video/webm",
	".mov": "video/quicktime", ".mkv": "video/x-matroska",

	".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac",
	".ogg": "audio/ogg", ".oga": "audio/ogg", ".opus": "audio/ogg",
	".wav": "audio/wav", ".flac": "audio/flac",

	".pdf": "application/pdf",
}

// serveMedia wraps the media file server with the headers that make serving
// user-uploaded bytes from our own origin safe:
//
//   - nosniff, so the browser never second-guesses the Content-Type we set;
//   - an explicit Content-Type from the allow-list above, or
//     application/octet-stream + Content-Disposition: attachment for anything
//     else (an uploaded .html/.svg/.js must download, never execute);
//   - a locked-down CSP as belt and braces for the types we do render inline.
//
// Directory listings stay blocked by noDirList underneath: filenames are the
// only access control.
func (a *API) serveMedia(fs http.Handler) http.Handler {
	inner := noDirList(fs)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Content-Security-Policy", "default-src 'none'; sandbox")

		ext := strings.ToLower(filepath.Ext(r.URL.Path))
		if ct, ok := inlineTypes[ext]; ok {
			h.Set("Content-Type", ct)
		} else {
			h.Set("Content-Type", "application/octet-stream")
			// The stored name is a random hash; give the browser the original
			// name back so a download lands with something recognisable.
			name := sanitizeName(r.URL.Query().Get("name"))
			h.Set("Content-Disposition", mime.FormatMediaType("attachment",
				map[string]string{"filename": name}))
		}
		inner.ServeHTTP(w, r)
	})
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

// Remove deletes stored files by name. Names come from the database, never from
// a request, but Base() them anyway so a corrupted row can't escape the media
// directory. Missing files are not an error — the caller is deleting, and the
// desired end state is "not there".
func (m *MediaStore) Remove(names ...string) {
	for _, n := range names {
		if n == "" {
			continue
		}
		_ = os.Remove(filepath.Join(m.Dir, filepath.Base(n)))
	}
}
