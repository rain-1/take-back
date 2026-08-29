package api

import "testing"

func TestValidEmojiAcceptsRealEmoji(t *testing.T) {
	ok := []string{
		"👍", "❤️", "😂", "🎉", "🙏", "👀", "🔥", "😮", "😢", "💯", "✅", "🚀",
		"👨‍👩‍👧‍👦", // ZWJ family
		"👍🏽",      // skin tone modifier
		"🇬🇧",      // regional indicator flag
		"1️⃣",     // keycap
		"#️⃣",     // keycap on a symbol base
		"©", "™", "⌚", "⏰", "☕", "✈️",
	}
	for _, e := range ok {
		if !validEmoji(e) {
			t.Errorf("validEmoji(%q) = false, want true", e)
		}
	}
}

// The vulnerability this guards: the old check was `len(s) <= 32`, and every
// payload below fits in 32 bytes.
func TestValidEmojiRejectsMarkupAndText(t *testing.T) {
	bad := []string{
		`<img src=x onerror=alert(1)>`,
		`<script>alert(1)</script>`,
		`<svg onload=alert(1)>`,
		`" onmouseover="alert(1)`,
		`👍<img src=x onerror=alert(1)>`, // emoji prefix must not launder it
		`javascript:alert(1)`,
		"hello",
		"a",
		"1", // a bare digit is not a keycap
		"&lt;",
		"",
		" ",
		"\x00",
		"\xff\xfe", // invalid UTF-8
		"👍👍👍👍👍👍👍👍👍👍👍👍👍", // 13 runes, over the cap
	}
	for _, e := range bad {
		if validEmoji(e) {
			t.Errorf("validEmoji(%q) = true, want false", e)
		}
	}
}

// Every emoji the web picker offers must survive validation, or the UI would
// hand the server values it then rejects.
func TestValidEmojiAcceptsEveryPickerChoice(t *testing.T) {
	// Mirrors REACTION_CHOICES in cmd/web/static/index.html.
	for _, e := range []string{"👍", "❤️", "😂", "🎉", "🙏", "👀", "🔥", "😮", "😢", "💯", "✅", "🚀"} {
		if !validEmoji(e) {
			t.Errorf("picker choice %q rejected by validEmoji", e)
		}
	}
}
