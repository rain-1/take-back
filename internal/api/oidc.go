package api

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/rain1/take-back/internal/auth"
	"github.com/rain1/take-back/internal/store"

	"golang.org/x/oauth2"
)

// Signing in through the identity provider.
//
// The browser (or the phone's tab, or the CLI's device code) never talks OAuth
// itself: it asks take-back to start a sign-in, take-back does the exchange
// with the provider, and what comes back to the client is the same tb_session
// cookie it has always had. Everything downstream — friends, groups, calls —
// keeps working off users.id and is untouched by any of this.

const (
	// A sign-in that isn't finished within this is abandoned. Long enough to
	// enrol a passkey or fish a phone out of a pocket for a TOTP code.
	flightTTL = 15 * time.Minute
	// Concurrent sign-ins to remember. Each entry is tiny; the cap exists so
	// an unauthenticated endpoint can't grow the map without bound.
	maxFlights = 512
	// How long the phone has to swap its one-time code for a session.
	nativeCodeTTL = 2 * time.Minute
)

// A sign-in in progress: what the callback must be able to check.
type flight struct {
	nonce    string
	verifier string // PKCE
	native   bool   // finish by handing a one-time code back to the phone
	created  time.Time
}

// A code the phone exchanges for its session, once.
type nativeCode struct {
	userID  int64
	created time.Time
}

type oidcState struct {
	mu      sync.Mutex
	flights map[string]flight
	codes   map[string]nativeCode
}

func newOIDCState() *oidcState {
	return &oidcState{flights: map[string]flight{}, codes: map[string]nativeCode{}}
}

// sweep drops anything expired. Called on every start, so there is no
// background goroutine to leak and a quiet server holds nothing.
func (s *oidcState) sweep() {
	now := time.Now()
	for k, f := range s.flights {
		if now.Sub(f.created) > flightTTL {
			delete(s.flights, k)
		}
	}
	for k, c := range s.codes {
		if now.Sub(c.created) > nativeCodeTTL {
			delete(s.codes, k)
		}
	}
}

func (s *oidcState) start(state string, f flight) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweep()
	if len(s.flights) >= maxFlights {
		return false
	}
	s.flights[state] = f
	return true
}

// take returns a sign-in exactly once: a replayed callback finds nothing.
func (s *oidcState) take(state string) (flight, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, ok := s.flights[state]
	delete(s.flights, state)
	if ok && time.Since(f.created) > flightTTL {
		return flight{}, false
	}
	return f, ok
}

func (s *oidcState) mintCode(userID int64) string {
	code := randomToken()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweep()
	s.codes[code] = nativeCode{userID: userID, created: time.Now()}
	return code
}

func (s *oidcState) redeemCode(code string) (int64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.codes[code]
	delete(s.codes, code)
	if !ok || time.Since(c.created) > nativeCodeTTL {
		return 0, false
	}
	return c.userID, true
}

func randomToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand failing is not survivable for a security token.
		panic("take-back: no randomness available: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// nickPattern is what a provider username has to look like to become a
// take-back nick: mentions, autolinks and the CLI all assume there is no
// whitespace in one.
var nickPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{2,32}$`)

// oidcRoutes registers the sign-in endpoints. They live outside /api/ because
// a browser is redirected to them directly.
func (a *API) oidcRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/auth/login", a.handleAuthLogin)
	mux.HandleFunc("/auth/callback", a.handleAuthCallback)
	mux.HandleFunc("/auth/logout", a.handleAuthLogout)
	mux.HandleFunc("/api/auth/status", a.handleAuthStatus)
	mux.HandleFunc("/api/auth/native", a.handleAuthNative)
	mux.HandleFunc("/api/auth/device/start", a.handleDeviceStart)
	mux.HandleFunc("/api/auth/device/poll", a.handleDevicePoll)
}

func (a *API) oidcEnabled() bool { return a.OIDC != nil && a.OIDC.Config().Enabled() }

// passwordsRetired reports whether the nick/password endpoints are gone. They
// go when a provider is configured, unless the operator is mid-migration and
// has asked to keep them (PasswordFallback).
func (a *API) passwordsRetired() bool { return a.oidcEnabled() && !a.PasswordFallback }

// handleAuthStatus lets a client discover how to sign in without hardcoding it
// — the phone and desktop app ask this before showing a sign-in screen.
func (a *API) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	resp := map[string]any{
		"provider":     a.oidcEnabled(),
		"loginUrl":     "/auth/login",
		"registration": a.OpenRegistration,
		// True while old clients may still post a password here.
		"passwordFallback": a.oidcEnabled() && a.PasswordFallback,
	}
	if a.oidcEnabled() {
		resp["backend"] = a.OIDC.Backend()
		resp["authorizationOrigin"] = a.OIDC.AuthorizationOrigin()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		if err := a.OIDC.Ready(ctx); err != nil {
			resp["ready"] = false
			// Deliberately not the error text: it names internal hosts.
			resp["detail"] = "the identity provider is not reachable right now"
		} else {
			resp["ready"] = true
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleAuthLogin starts a sign-in and redirects to the provider.
func (a *API) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	if !a.oidcEnabled() {
		writeErr(w, http.StatusNotImplemented, "this server has no identity provider configured")
		return
	}
	if !allow(w, r, loginLimiter) {
		return
	}
	state, nonce := randomToken(), randomToken()
	verifier := oauth2.GenerateVerifier()
	f := flight{
		nonce:    nonce,
		verifier: verifier,
		native:   r.URL.Query().Get("native") == "1",
		created:  time.Now(),
	}
	if !a.oidc.start(state, f) {
		writeErr(w, http.StatusServiceUnavailable, "too many sign-ins in progress — try again shortly")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	var (
		url string
		err error
	)
	if r.URL.Query().Get("switch") == "1" {
		url, err = a.OIDC.ForceLoginURL(ctx, state, nonce, verifier)
	} else {
		url, err = a.OIDC.AuthCodeURL(ctx, state, nonce, verifier)
	}
	if err != nil {
		writeErr(w, http.StatusBadGateway, "the identity provider is not reachable right now")
		return
	}
	http.Redirect(w, r, url, http.StatusFound)
}

// handleAuthCallback is where the provider sends the browser back.
func (a *API) handleAuthCallback(w http.ResponseWriter, r *http.Request) {
	if !a.oidcEnabled() {
		writeErr(w, http.StatusNotImplemented, "this server has no identity provider configured")
		return
	}
	q := r.URL.Query()
	if e := q.Get("error"); e != "" {
		// The person declined, or the provider refused them.
		f, _ := a.oidc.take(q.Get("state"))
		a.authFailure(w, r, "sign-in was cancelled or refused", f.native)
		return
	}
	f, ok := a.oidc.take(q.Get("state"))
	if !ok {
		// Unknown state: a replayed callback, an expired sign-in, or a forged
		// one. All three are the same answer — start again.
		a.authFailure(w, r, "that sign-in expired — please try again", false)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	id, err := a.OIDC.Exchange(ctx, q.Get("code"), f.verifier, f.nonce)
	if err != nil {
		a.authFailure(w, r, "the identity provider could not confirm that sign-in", f.native)
		return
	}
	user, err := a.userForIdentity(id)
	if err != nil {
		a.authFailure(w, r, err.Error(), f.native)
		return
	}

	if f.native {
		// The phone's tab: hand back a one-time code on the app's own scheme.
		// No session cookie is set here — the browser is not the client.
		http.Redirect(w, r, nativeRedirect+"?code="+a.oidc.mintCode(user.ID), http.StatusFound)
		return
	}
	token, err := a.setSessionToken(w, r, user.ID)
	if err != nil {
		a.authFailure(w, r, "could not start a session", false)
		return
	}
	// Kept for logout, so the provider can end its own session without asking
	// the person to confirm who they are.
	_ = a.Store.StashIDToken(token, id.IDToken)
	http.Redirect(w, r, "/", http.StatusFound)
}

// nativeRedirect is the phone app's deep link (see AndroidManifest.xml).
const nativeRedirect = "com.takeback.app://auth"

// authFailure sends a browser somewhere it can read what went wrong. The web
// client shows ?authError= on its sign-in screen.
func (a *API) authFailure(w http.ResponseWriter, r *http.Request, msg string, native bool) {
	// A native sign-in has no useful web page to return to, so return the error
	// to the app that started the flow.
	if native {
		http.Redirect(w, r, nativeRedirect+"?error="+urlQueryEscape(msg), http.StatusFound)
		return
	}
	http.Redirect(w, r, "/?authError="+urlQueryEscape(msg), http.StatusFound)
}

func urlQueryEscape(s string) string {
	return url.QueryEscape(s)
}

// userForIdentity maps a verified provider identity onto a take-back account.
//
// The `sub` claim is the only thing trusted to identify a returning account.
// A username is used *once*, and only when the operator has asked for it, to
// claim an account that predates the provider; after that the link is what
// counts, so renaming somebody in the provider can never hand them somebody
// else's messages.
func (a *API) userForIdentity(id *auth.Identity) (*store.User, error) {
	if u, err := a.Store.UserByOIDCSub(id.Subject); err == nil {
		return u, nil
	} else if !errors.Is(err, store.ErrNoSuchUser) {
		return nil, errors.New("could not look up that account")
	}

	nick := id.Username
	if !nickPattern.MatchString(nick) {
		// Fall back to the local part of an email before giving up.
		if local, _, found := strings.Cut(id.Email, "@"); found && nickPattern.MatchString(local) {
			nick = local
		} else {
			return nil, errors.New("your provider username can't be used as a take-back nick")
		}
	}

	if a.ClaimByUsername {
		if u, err := a.Store.UnlinkedUserByNick(nick); err == nil {
			if err := a.Store.LinkOIDCSub(u.ID, id.Subject); err != nil {
				return nil, errors.New("that account is already linked to another identity")
			}
			return u, nil
		}
	}

	if !a.OpenRegistration {
		return nil, errors.New("there is no account here for you — ask the admin")
	}
	u, err := a.Store.CreateOIDCUser(nick, id.Subject)
	if err != nil {
		return nil, errors.New("that nick is taken on this server")
	}
	return u, nil
}

// handleAuthLogout clears the local session and, if it can, ends the session
// at the provider too — otherwise "log out" leaves the provider happy to sign
// you straight back in without asking anything.
func (a *API) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	var idToken string
	if cookie, err := r.Cookie(sessionCookie); err == nil {
		idToken = a.Store.SessionIDToken(cookie.Value)
		_ = a.Store.DeleteSession(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, Secure: isHTTPS(r), SameSite: http.SameSiteLaxMode,
	})
	if !a.oidcEnabled() {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	home := "https://" + r.Host + "/"
	if !isHTTPS(r) {
		home = "http://" + r.Host + "/"
	}
	http.Redirect(w, r, a.OIDC.EndSessionURL(ctx, idToken, home), http.StatusFound)
}

// ---- the phone ----

// handleAuthNative swaps the one-time code from the deep link for a session.
func (a *API) handleAuthNative(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	var body struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &body) {
		return
	}
	userID, ok := a.oidc.redeemCode(body.Code)
	if !ok {
		writeErr(w, http.StatusUnauthorized, "that sign-in code has expired")
		return
	}
	user, err := a.Store.UserByID(userID)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "that account no longer exists")
		return
	}
	if err := a.setSession(w, r, user.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, "session failed")
		return
	}
	writeJSON(w, http.StatusOK, user)
}

// ---- the CLI ----

func (a *API) handleDeviceStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	if !a.oidcEnabled() {
		writeErr(w, http.StatusNotImplemented, "this server has no identity provider configured")
		return
	}
	if !allow(w, r, loginLimiter) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	da, err := a.OIDC.StartDevice(ctx)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "the identity provider is not reachable right now")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deviceCode":      da.DeviceCode,
		"userCode":        da.UserCode,
		"verificationUri": da.VerificationURI,
		"interval":        da.Interval,
		"expiresIn":       da.ExpiresIn,
	})
}

func (a *API) handleDevicePoll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	if !a.oidcEnabled() {
		writeErr(w, http.StatusNotImplemented, "this server has no identity provider configured")
		return
	}
	if !allow(w, r, devicePollLimiter) {
		return
	}
	var body struct {
		DeviceCode string `json:"deviceCode"`
	}
	if !decode(w, r, &body) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	id, err := a.OIDC.PollDevice(ctx, body.DeviceCode)
	if errors.Is(err, auth.ErrDevicePending) {
		// 202: nothing is wrong, the person just hasn't finished yet.
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "pending"})
		return
	}
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "that sign-in did not complete")
		return
	}
	user, err := a.userForIdentity(id)
	if err != nil {
		writeErr(w, http.StatusForbidden, err.Error())
		return
	}
	token, err := a.Store.NewSession(user.ID, sessionTTL)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "session failed")
		return
	}
	// The CLI keeps the token itself rather than a cookie jar.
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "user": user})
}
