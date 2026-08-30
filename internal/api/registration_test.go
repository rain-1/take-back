package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/rain1/take-back/internal/store"
)

func newTestAPI(t *testing.T, openReg bool) *API {
	t.Helper()
	db, err := store.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return &API{Store: db, Media: &MediaStore{Dir: t.TempDir()}, OpenRegistration: openReg}
}

// The zero value must be "closed": a deployment that forgets the flag should not
// silently accept signups from the whole internet.
func TestRegistrationClosedByDefault(t *testing.T) {
	a := &API{}
	if a.OpenRegistration {
		t.Fatal("OpenRegistration must default to false")
	}
}

func TestRegisterRefusedWhenClosed(t *testing.T) {
	a := newTestAPI(t, false)
	rec := httptest.NewRecorder()
	a.handleRegister(rec, httptest.NewRequest("POST", "/api/register",
		strings.NewReader(`{"nick":"intruder","password":"pw123456"}`)))

	if rec.Code != http.StatusForbidden {
		t.Fatalf("closed registration: got %d, want 403", rec.Code)
	}
	if _, _, err := a.Store.UserByNick("intruder"); err == nil {
		t.Fatal("a refused registration must not create the account")
	}
	// No session may be handed out either.
	if rec.Header().Get("Set-Cookie") != "" {
		t.Fatal("a refused registration must not set a session cookie")
	}
}

func TestRegisterAllowedWhenOpen(t *testing.T) {
	a := newTestAPI(t, true)
	rec := httptest.NewRecorder()
	a.handleRegister(rec, httptest.NewRequest("POST", "/api/register",
		strings.NewReader(`{"nick":"newbie","password":"pw123456"}`)))

	if rec.Code != http.StatusOK {
		t.Fatalf("open registration: got %d (%s), want 200", rec.Code, rec.Body.String())
	}
	if _, _, err := a.Store.UserByNick("newbie"); err != nil {
		t.Fatalf("account should exist: %v", err)
	}
}

// Clients hide their Register affordance based on this, so it has to be right.
func TestVersionAdvertisesRegistrationPolicy(t *testing.T) {
	for _, open := range []bool{true, false} {
		a := newTestAPI(t, open)
		rec := httptest.NewRecorder()
		a.handleVersion(rec, httptest.NewRequest("GET", "/api/version", nil))

		var body map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if body["openRegistration"] != open {
			t.Errorf("openRegistration = %v, want %v", body["openRegistration"], open)
		}
	}
}
