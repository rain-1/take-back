package api

import "unicode/utf8"

// maxEmojiRunes caps a reaction. Real emoji sequences are short: a ZWJ family
// with skin tones is about 8 code points, a flag is 2, a keycap is 3.
const maxEmojiRunes = 12

// validEmoji reports whether s is plausibly a single emoji (or emoji sequence)
// rather than arbitrary text.
//
// This used to be a bare `len(s) <= 32` check, which let a reaction carry
// markup: "<img src=x onerror=...>" fits in 32 bytes, and the web client
// interpolated the value into innerHTML — a stored XSS reachable by anyone who
// could react to a shared message. The client now builds text nodes, and this is
// the other half: nothing that isn't an emoji gets stored in the first place.
//
// Deliberately a character-class check rather than a fixed allow-list of the
// dozen emoji the picker offers: the phone keyboards can send thousands, and new
// ones appear with every Unicode release. What matters for the vulnerability is
// that no letter, digit, quote or angle bracket can appear at all — the one
// exception being keycap sequences ("1️⃣"), which are an ASCII character plus
// U+20E3 and are allowed only when that combining mark is present.
func validEmoji(s string) bool {
	if s == "" || !utf8.ValidString(s) {
		return false
	}

	keycap := false
	n := 0
	for _, r := range s {
		if r == 0x20E3 { // COMBINING ENCLOSING KEYCAP
			keycap = true
		}
		n++
	}
	if n > maxEmojiRunes {
		return false
	}

	for _, r := range s {
		switch {
		case isEmojiRune(r):
			continue
		case keycap && (r >= '0' && r <= '9' || r == '#' || r == '*'):
			// The base character of a keycap sequence.
			continue
		default:
			return false
		}
	}
	return true
}

// emojiRanges are the Unicode ranges a reaction may draw from: the emoji blocks
// themselves plus the joiners, variation selectors, skin-tone modifiers, tag
// characters (subdivision flags) and the keycap mark.
var emojiRanges = [][2]rune{
	{0x00A9, 0x00A9},   // ©
	{0x00AE, 0x00AE},   // ®
	{0x200D, 0x200D},   // zero-width joiner
	{0x203C, 0x203C},   // ‼
	{0x2049, 0x2049},   // ⁉
	{0x20E3, 0x20E3},   // combining enclosing keycap
	{0x2122, 0x2122},   // ™
	{0x2139, 0x2139},   // ℹ
	{0x2190, 0x21FF},   // arrows
	{0x2300, 0x23FF},   // misc technical (⌚ ⏰ ⏳)
	{0x24C2, 0x24C2},   // Ⓜ
	{0x25AA, 0x25FE},   // geometric shapes
	{0x2600, 0x27BF},   // misc symbols + dingbats
	{0x2934, 0x2935},   // ⤴ ⤵
	{0x2B00, 0x2BFF},   // misc symbols and arrows
	{0x3030, 0x3030},   // 〰
	{0x303D, 0x303D},   // 〽
	{0x3297, 0x3297},   // ㊗
	{0x3299, 0x3299},   // ㊙
	{0xFE0E, 0xFE0F},   // variation selectors (text / emoji presentation)
	{0x1F000, 0x1FAFF}, // the emoji planes, incl. skin-tone modifiers
	{0xE0020, 0xE007F}, // tag characters, for subdivision flags
}

func isEmojiRune(r rune) bool {
	for _, rg := range emojiRanges {
		if r >= rg[0] && r <= rg[1] {
			return true
		}
	}
	return false
}
