package version

import (
	"strconv"
	"strings"
	"testing"
)

// The whole scheme rests on MAJOR meaning "wire compatibility". If someone
// bumps one without the other, clients would silently mis-detect compatibility,
// so pin the invariant here.
func TestMajorEqualsProtocol(t *testing.T) {
	parts := strings.Split(Version, ".")
	if len(parts) != 3 {
		t.Fatalf("Version %q must be MAJOR.MINOR.PATCH", Version)
	}
	major, err := strconv.Atoi(parts[0])
	if err != nil {
		t.Fatalf("MAJOR of %q is not a number: %v", Version, err)
	}
	if major != Protocol {
		t.Fatalf("MAJOR (%d) must equal Protocol (%d) — bump them together", major, Protocol)
	}
	for _, p := range parts[1:] {
		if _, err := strconv.Atoi(p); err != nil {
			t.Fatalf("Version %q has a non-numeric component: %v", Version, err)
		}
	}
}
