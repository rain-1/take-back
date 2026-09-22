package api

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/rain1/take-back/internal/auth"
	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"

	"github.com/go-jose/go-jose/v4"
	josejwt "github.com/go-jose/go-jose/v4/jwt"
	"golang.org/x/crypto/bcrypt"
)

// stubIdP is just enough of an OpenID Connect provider to exercise the real
// verification path: discovery, a JWKS, and a token endpoint that mints signed
// ID tokens. Using a real signer (rather than stubbing out the library) is the
// point — it means these tests would notice if signature or issuer checking
// stopped happening.
type stubIdP struct {
	*httptest.Server
	key      *rsa.PrivateKey
	clientID string

	// what the next token exchange returns
	sub      string
	username string
	email    string
	nonce    string
	// knobs for the failure cases
	signWithWrongKey bool
	wrongAudience    bool
}

func newStubIdP(t *testing.T, clientID string) *stubIdP {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	s := &stubIdP{key: key, clientID: clientID}
	mux := http.NewServeMux()
	s.Server = httptest.NewServer(mux)
	t.Cleanup(s.Close)

	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"issuer":                                s.URL,
			"authorization_endpoint":                s.URL + "/authorize",
			"token_endpoint":                        s.URL + "/token",
			"jwks_uri":                              s.URL + "/jwks",
			"device_authorization_endpoint":         s.URL + "/device",
			"end_session_endpoint":                  s.URL + "/end-session",
			"id_token_signing_alg_values_supported": []string{"RS256"},
			"response_types_supported":              []string{"code"},
			"subject_types_supported":               []string{"public"},
		})
	})
	mux.HandleFunc("/jwks", func(w http.ResponseWriter, r *http.Request) {
		n := base64.RawURLEncoding.EncodeToString(s.key.N.Bytes())
		e := base64.RawURLEncoding.EncodeToString(big.NewInt(int64(s.key.E)).Bytes())
		json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]any{
			{"kty": "RSA", "alg": "RS256", "use": "sig", "kid": "stub", "n": n, "e": e},
		}})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"access_token": "stub-access",
			"token_type":   "Bearer",
			"expires_in":   3600,
			"id_token":     s.mintIDToken(t),
		})
	})
	return s
}

func (s *stubIdP) mintIDToken(t *testing.T) string {
	t.Helper()
	signKey := s.key
	if s.signWithWrongKey {
		other, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			t.Fatal(err)
		}
		signKey = other
	}
	aud := s.clientID
	if s.wrongAudience {
		aud = "some-other-application"
	}
	signer, err := jose.NewSigner(
		jose.SigningKey{Algorithm: jose.RS256, Key: signKey},
		(&jose.SignerOptions{}).WithType("JWT").WithHeader("kid", "stub"),
	)
	if err != nil {
		t.Fatal(err)
	}
	claims := map[string]any{
		"iss":                s.URL,
		"sub":                s.sub,
		"aud":                aud,
		"exp":                time.Now().Add(time.Hour).Unix(),
		"iat":                time.Now().Unix(),
		"nonce":              s.nonce,
		"preferred_username": s.username,
		"email":              s.email,
	}
	tok, err := josejwt.Signed(signer).Claims(claims).Serialize()
	if err != nil {
		t.Fatal(err)
	}
	return tok
}

// newOIDCTestAPI wires an API to the stub provider, with routes registered.
func newOIDCTestAPI(t *testing.T, idp *stubIdP, openReg, claim bool) (*API, *http.ServeMux) {
	t.Helper()
	db, err := store.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	a := &API{
		Store: db, Media: &MediaStore{Dir: t.TempDir()},
		Presence:         presence.NewHub(db.AcceptedFriendIDs),
		OpenRegistration: openReg,
		ClaimByUsername:  claim,
		OIDC: auth.New(auth.Config{
			Issuer:       idp.URL,
			ClientID:     idp.clientID,
			ClientSecret: "stub-secret",
			RedirectURL:  "http://take-back.test/auth/callback",
		}),
	}
	mux := http.NewServeMux()
	a.Routes(mux)
	return a, mux
}

// startSignIn runs /auth/login and returns the state the provider would echo
// back, plus the nonce it was asked to include.
func startSignIn(t *testing.T, a *API, mux *http.ServeMux, native bool) (state, nonce string) {
	t.Helper()
	target := "/auth/login"
	if native {
		target += "?native=1"
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", target, nil))
	if rec.Code != http.StatusFound {
		t.Fatalf("login: got %d, want 302 (body %s)", rec.Code, rec.Body.String())
	}
	loc, err := url.Parse(rec.Header().Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	q := loc.Query()
	state, nonce = q.Get("state"), q.Get("nonce")
	if state == "" || nonce == "" {
		t.Fatalf("authorize URL is missing state/nonce: %s", loc)
	}
	if q.Get("code_challenge") == "" || q.Get("code_challenge_method") != "S256" {
		t.Fatalf("PKCE challenge missing from %s", loc)
	}
	return state, nonce
}

func callback(mux *http.ServeMux, state string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET",
		"/auth/callback?code=stub-code&state="+url.QueryEscape(state), nil))
	return rec
}

func sessionCookieFrom(rec *httptest.ResponseRecorder) string {
	for _, c := range rec.Result().Cookies() {
		if c.Name == sessionCookie && c.Value != "" {
			return c.Value
		}
	}
	return ""
}

func TestSignInLinksExistingAccountThenRemembersIt(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, false, true) // claim-by-username on
	existing, err := a.Store.CreateUser("river", "old-bcrypt-hash")
	if err != nil {
		t.Fatal(err)
	}

	idp.sub, idp.username = "provider-sub-river", "river"
	state, nonce := startSignIn(t, a, mux, false)
	idp.nonce = nonce
	rec := callback(mux, state)
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != "/" {
		t.Fatalf("callback: %d -> %q (%s)", rec.Code, rec.Header().Get("Location"), rec.Body.String())
	}
	tok := sessionCookieFrom(rec)
	if tok == "" {
		t.Fatal("no session cookie was set")
	}
	u, err := a.Store.UserBySession(tok)
	if err != nil || u.ID != existing.ID {
		t.Fatalf("session belongs to %+v (%v), want the existing river account", u, err)
	}

	// Second sign-in: the account is found by sub, so it works even with
	// claiming turned off and even if the username changed in the provider.
	a.ClaimByUsername = false
	idp.username = "river-renamed"
	state, nonce = startSignIn(t, a, mux, false)
	idp.nonce = nonce
	rec = callback(mux, state)
	u2, err := a.Store.UserBySession(sessionCookieFrom(rec))
	if err != nil || u2.ID != existing.ID {
		t.Fatalf("returning sign-in landed on %+v (%v), want the same account", u2, err)
	}
	if u2.Nick != "river" {
		t.Fatalf("nick changed to %q — a provider rename must not rewrite take-back", u2.Nick)
	}
}

func TestCallbackStateIsSingleUse(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, true, false)
	idp.sub, idp.username = "sub-1", "newcomer"

	state, nonce := startSignIn(t, a, mux, false)
	idp.nonce = nonce
	if rec := callback(mux, state); sessionCookieFrom(rec) == "" {
		t.Fatal("first callback should sign in")
	}
	// Replaying the same callback must not mint a second session.
	rec := callback(mux, state)
	if sessionCookieFrom(rec) != "" {
		t.Fatal("a replayed callback must not produce a session")
	}
	if loc := rec.Header().Get("Location"); !strings.Contains(loc, "authError=") {
		t.Fatalf("replay should land on an error, got %q", loc)
	}
}

func TestCallbackRejectsUnknownState(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	_, mux := newOIDCTestAPI(t, idp, true, false)
	idp.sub, idp.username = "sub-x", "stranger"

	rec := callback(mux, "a-state-nobody-issued")
	if sessionCookieFrom(rec) != "" {
		t.Fatal("a forged state must not produce a session")
	}
}

func TestCallbackRejectsBadTokens(t *testing.T) {
	cases := []struct {
		name   string
		break_ func(*stubIdP)
	}{
		{"wrong signing key", func(s *stubIdP) { s.signWithWrongKey = true }},
		{"token minted for another application", func(s *stubIdP) { s.wrongAudience = true }},
		{"replayed nonce from another sign-in", func(s *stubIdP) { s.nonce = "some-other-nonce" }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			idp := newStubIdP(t, "take-back-test")
			a, mux := newOIDCTestAPI(t, idp, true, false)
			idp.sub, idp.username = "sub-attacker", "attacker"

			state, nonce := startSignIn(t, a, mux, false)
			idp.nonce = nonce
			tc.break_(idp)

			rec := callback(mux, state)
			if sessionCookieFrom(rec) != "" {
				t.Fatal("an unverifiable id_token must not produce a session")
			}
			if _, _, err := a.Store.UserByNick("attacker"); err == nil {
				t.Fatal("a refused sign-in must not create an account")
			}
		})
	}
}

func TestClosedServerRefusesUnknownIdentities(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, false, false) // closed, no claiming
	idp.sub, idp.username = "sub-stranger", "stranger"

	state, nonce := startSignIn(t, a, mux, false)
	idp.nonce = nonce
	rec := callback(mux, state)
	if sessionCookieFrom(rec) != "" {
		t.Fatal("a closed server must not admit an unknown identity")
	}
	if _, _, err := a.Store.UserByNick("stranger"); err == nil {
		t.Fatal("no account should have been created")
	}
}

func TestClaimingIsOffByDefault(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, false, false)
	if _, err := a.Store.CreateUser("river", "hash"); err != nil {
		t.Fatal(err)
	}
	// Somebody who managed to register the username "river" in the provider
	// must not thereby own river's take-back account.
	idp.sub, idp.username = "sub-impostor", "river"
	state, nonce := startSignIn(t, a, mux, false)
	idp.nonce = nonce
	rec := callback(mux, state)
	if sessionCookieFrom(rec) != "" {
		t.Fatal("claiming is off: an unknown sub must not land on an existing account")
	}
	u, _ := a.Store.UserByOIDCSub("sub-impostor")
	if u != nil {
		t.Fatal("no link should have been made")
	}
}

func TestNativeSignInHandsBackAOneTimeCode(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, true, false)
	idp.sub, idp.username = "sub-phone", "phoneuser"

	state, nonce := startSignIn(t, a, mux, true) // native=1
	idp.nonce = nonce
	rec := callback(mux, state)
	loc := rec.Header().Get("Location")
	if !strings.HasPrefix(loc, nativeRedirect+"?code=") {
		t.Fatalf("native callback should deep-link to the app, got %q", loc)
	}
	if sessionCookieFrom(rec) != "" {
		t.Fatal("the browser tab must not get the session; the app does")
	}
	code := strings.TrimPrefix(loc, nativeRedirect+"?code=")

	exchange := func() *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("POST", "/api/auth/native",
			strings.NewReader(fmt.Sprintf(`{"code":%q}`, code)))
		req.Header.Set("Content-Type", "application/json")
		mux.ServeHTTP(rec, req)
		return rec
	}
	rec = exchange()
	if rec.Code != http.StatusOK {
		t.Fatalf("exchange: %d %s", rec.Code, rec.Body.String())
	}
	if sessionCookieFrom(rec) == "" {
		t.Fatal("the app should receive a session")
	}
	// Once only: a code left in a browser history or a log is spent.
	if rec := exchange(); rec.Code != http.StatusUnauthorized {
		t.Fatalf("a reused code should be refused, got %d", rec.Code)
	}
}

func TestPasswordEndpointsRetireWhenProviderConfigured(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, true, false)
	if _, err := a.Store.CreateUser("river", "$2a$10$notarealhashbutlongenoughhere"); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/api/login", "/api/register"} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("POST", path,
			strings.NewReader(`{"nick":"river","password":"whatever"}`))
		req.Header.Set("Content-Type", "application/json")
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusGone {
			t.Fatalf("%s: got %d, want 410 Gone", path, rec.Code)
		}
		if sessionCookieFrom(rec) != "" {
			t.Fatalf("%s handed out a session", path)
		}
	}
}

// The migration window: a server that has a provider but hasn't cut over yet
// must still accept the passwords old clients hold, and must say so.
func TestPasswordFallbackKeepsOldClientsWorking(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	a, mux := newOIDCTestAPI(t, idp, false, false)
	a.PasswordFallback = true
	hash, err := bcrypt.GenerateFromPassword([]byte("pw123456"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.Store.CreateUser("river", string(hash)); err != nil {
		t.Fatal(err)
	}

	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/login",
		strings.NewReader(`{"nick":"river","password":"pw123456"}`))
	req.Header.Set("Content-Type", "application/json")
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("during migration a password must still work: %d %s", rec.Code, rec.Body.String())
	}
	if sessionCookieFrom(rec) == "" {
		t.Fatal("no session was issued")
	}

	// And clients are told, so they can show both ways in.
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", "/api/auth/status", nil))
	var out struct {
		Provider         bool `json:"provider"`
		PasswordFallback bool `json:"passwordFallback"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if !out.Provider || !out.PasswordFallback {
		t.Fatalf("status should advertise both routes: %+v", out)
	}

	// Closing the window retires them again, with no other change.
	a.PasswordFallback = false
	rec = httptest.NewRecorder()
	req = httptest.NewRequest("POST", "/api/login",
		strings.NewReader(`{"nick":"river","password":"pw123456"}`))
	req.Header.Set("Content-Type", "application/json")
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusGone {
		t.Fatalf("after the migration the password endpoint is gone: got %d", rec.Code)
	}
}

func TestAuthStatusTellsClientsHowToSignIn(t *testing.T) {
	idp := newStubIdP(t, "take-back-test")
	_, mux := newOIDCTestAPI(t, idp, false, false)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", "/api/auth/status", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status: %d", rec.Code)
	}
	var out struct {
		Provider            bool   `json:"provider"`
		Ready               bool   `json:"ready"`
		LoginURL            string `json:"loginUrl"`
		Backend             string `json:"backend"`
		AuthorizationOrigin string `json:"authorizationOrigin"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if !out.Provider || !out.Ready || out.LoginURL != "/auth/login" ||
		out.Backend != auth.BackendGeneric || out.AuthorizationOrigin != idp.URL {
		t.Fatalf("unexpected status: %+v", out)
	}
}
