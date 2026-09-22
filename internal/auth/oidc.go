// Package auth makes take-back a relying party of an OpenID Connect provider,
// which is where passwords, passkeys and 2FA live.
//
// The take-back *server* is the only OIDC client. Browsers, the phone, the
// desktop app and the CLI all authenticate against take-back, which does the
// OAuth dance on their behalf — so the client secret never leaves the server
// and the native clients never have to implement OAuth at all.
package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

// Config is what the operator supplies (see deploy/auth/README.md).
type Config struct {
	Backend      string // generic (default), authentik, or keycloak
	Issuer       string // e.g. https://auth.example.org/application/o/take-back/
	ClientID     string
	ClientSecret string
	RedirectURL  string // https://takeback.example.org/auth/callback
}

// Enabled reports whether the server has been given a provider to talk to.
func (c Config) Enabled() bool {
	return c.Issuer != "" && c.ClientID != "" && c.ClientSecret != ""
}

// Identity is the part of a verified ID token take-back cares about.
type Identity struct {
	Subject  string // stable per account: what take-back stores
	Username string // preferred_username — matched against a take-back nick
	Email    string
	Name     string
	// IDToken is kept so logout can hand it back to the provider, ending the
	// session there too rather than only locally.
	IDToken string
}

// Provider is a lazily-discovered OIDC provider.
//
// Discovery is deliberately not done at startup: take-back must come up even
// when the identity provider is still booting (they restart together), and a
// chat server that refuses to start because an unrelated container is slow is
// worse than one that reports "sign-in unavailable" for a few seconds.
type Provider struct {
	cfg       Config
	backend   backendProfile
	configErr error

	mu       sync.Mutex
	provider *oidc.Provider
	verifier *oidc.IDTokenVerifier
	oauth    *oauth2.Config
	lastTry  time.Time
	lastErr  error
}

// ErrNotConfigured is returned when no provider has been configured at all.
var ErrNotConfigured = errors.New("no identity provider configured")

func New(cfg Config) *Provider {
	backend, err := profileFor(cfg.Backend)
	return &Provider{cfg: cfg, backend: backend, configErr: err}
}

// ValidateConfig catches a misspelled backend at startup instead of turning it
// into an unhelpful "identity provider unavailable" message on the login page.
func ValidateConfig(cfg Config) error {
	_, err := profileFor(cfg.Backend)
	return err
}

func toggleTrailingSlash(s string) string {
	if strings.HasSuffix(s, "/") {
		return strings.TrimSuffix(s, "/")
	}
	return s + "/"
}

func (p *Provider) Config() Config { return p.cfg }

func (p *Provider) Backend() string {
	if p.backend == nil {
		return ""
	}
	return p.backend.Name()
}

// AuthorizationOrigin is the only additional top-level origin a native shell
// needs to allow while a browser is completing sign-in. It deliberately
// returns only scheme+host, never an authorization path or query string.
func (p *Provider) AuthorizationOrigin() string {
	u, err := url.Parse(p.cfg.Issuer)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return ""
	}
	return u.Scheme + "://" + u.Host
}

// discoveryRetry is how long a failed discovery is remembered, so a provider
// that is down doesn't turn every sign-in into a fresh timeout.
const discoveryRetry = 10 * time.Second

func (p *Provider) ensure(ctx context.Context) error {
	if p.configErr != nil {
		return p.configErr
	}
	if !p.cfg.Enabled() {
		return ErrNotConfigured
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.provider != nil {
		return nil
	}
	if time.Since(p.lastTry) < discoveryRetry && p.lastErr != nil {
		return p.lastErr
	}
	p.lastTry = time.Now()

	// The issuer string has to match the discovery document's `issuer` claim
	// byte for byte — go-oidc checks it, and that check is what ties a token
	// to the provider we meant. Authentik's ends in a slash; plenty of others
	// don't. Rather than make the operator get that detail exactly right in an
	// environment variable, try the configured form and then the other one.
	prov, err := oidc.NewProvider(ctx, p.cfg.Issuer)
	if err != nil {
		if alt := toggleTrailingSlash(p.cfg.Issuer); alt != p.cfg.Issuer {
			if prov2, err2 := oidc.NewProvider(ctx, alt); err2 == nil {
				prov, err = prov2, nil
			}
		}
	}
	if err != nil {
		p.lastErr = fmt.Errorf("discover %s: %w", p.cfg.Issuer, err)
		return p.lastErr
	}
	p.provider = prov
	p.verifier = prov.Verifier(&oidc.Config{ClientID: p.cfg.ClientID})
	p.oauth = &oauth2.Config{
		ClientID:     p.cfg.ClientID,
		ClientSecret: p.cfg.ClientSecret,
		Endpoint:     prov.Endpoint(),
		RedirectURL:  p.cfg.RedirectURL,
		Scopes:       []string{oidc.ScopeOpenID, "profile", "email"},
	}
	p.lastErr = nil
	return nil
}

// Ready reports whether the provider has been reached, so callers can tell a
// misconfiguration from an outage without starting a sign-in.
func (p *Provider) Ready(ctx context.Context) error { return p.ensure(ctx) }

// AuthCodeURL builds the URL to send a browser to. The caller keeps state,
// nonce and the PKCE verifier and must present them again at the callback.
func (p *Provider) AuthCodeURL(ctx context.Context, state, nonce, verifier string) (string, error) {
	if err := p.ensure(ctx); err != nil {
		return "", err
	}
	p.mu.Lock()
	cfg := *p.oauth
	p.mu.Unlock()
	opts := []oauth2.AuthCodeOption{
		oidc.Nonce(nonce),
		oauth2.S256ChallengeOption(verifier),
	}
	for key, values := range p.backend.AuthorizationParameters(false) {
		for _, value := range values {
			opts = append(opts, oauth2.SetAuthURLParam(key, value))
		}
	}
	return cfg.AuthCodeURL(state, opts...), nil
}

// ForceLoginURL is AuthCodeURL with prompt=login: used when someone explicitly
// signs out and back in, so they are not silently returned to the same account.
func (p *Provider) ForceLoginURL(ctx context.Context, state, nonce, verifier string) (string, error) {
	u, err := p.AuthCodeURL(ctx, state, nonce, verifier)
	if err != nil {
		return "", err
	}
	parsed, err := url.Parse(u)
	if err != nil {
		return "", err
	}
	q := parsed.Query()
	for key, values := range p.backend.AuthorizationParameters(true) {
		q.Del(key)
		for _, value := range values {
			q.Add(key, value)
		}
	}
	parsed.RawQuery = q.Encode()
	return parsed.String(), nil
}

// Exchange turns an authorization code into a verified identity. It checks the
// ID token's signature, issuer, audience and expiry (go-oidc), and the nonce
// (ours), which together are what stop a token minted for somewhere else — or
// replayed from an earlier sign-in — from being accepted here.
func (p *Provider) Exchange(ctx context.Context, code, verifier, nonce string) (*Identity, error) {
	if err := p.ensure(ctx); err != nil {
		return nil, err
	}
	p.mu.Lock()
	cfg, ver := *p.oauth, p.verifier
	p.mu.Unlock()

	tok, err := cfg.Exchange(ctx, code, oauth2.VerifierOption(verifier))
	if err != nil {
		return nil, fmt.Errorf("token exchange: %w", err)
	}
	return p.identityFrom(ctx, ver, tok, nonce)
}

func (p *Provider) identityFrom(ctx context.Context, ver *oidc.IDTokenVerifier, tok *oauth2.Token, nonce string) (*Identity, error) {
	raw, ok := tok.Extra("id_token").(string)
	if !ok || raw == "" {
		return nil, errors.New("provider returned no id_token")
	}
	idToken, err := ver.Verify(ctx, raw)
	if err != nil {
		return nil, fmt.Errorf("verify id_token: %w", err)
	}
	if nonce != "" && idToken.Nonce != nonce {
		return nil, errors.New("id_token nonce does not match this sign-in")
	}
	var claims struct {
		Username string `json:"preferred_username"`
		Email    string `json:"email"`
		Name     string `json:"name"`
	}
	if err := idToken.Claims(&claims); err != nil {
		return nil, fmt.Errorf("read claims: %w", err)
	}
	if idToken.Subject == "" {
		return nil, errors.New("id_token has no subject")
	}
	return &Identity{
		Subject:  idToken.Subject,
		Username: strings.TrimSpace(claims.Username),
		Email:    claims.Email,
		Name:     claims.Name,
		IDToken:  raw,
	}, nil
}

// ---- device flow (the CLI, which has no browser to redirect) ----

// DeviceAuth is what the user is asked to do on another device.
type DeviceAuth struct {
	DeviceCode      string
	UserCode        string
	VerificationURI string // already includes the code when the provider offers that
	ExpiresIn       int
	Interval        int
}

// StartDevice begins the device authorization grant.
func (p *Provider) StartDevice(ctx context.Context) (*DeviceAuth, error) {
	if err := p.ensure(ctx); err != nil {
		return nil, err
	}
	p.mu.Lock()
	cfg := *p.oauth
	p.mu.Unlock()

	var opts []oauth2.AuthCodeOption
	for key, values := range p.backend.DeviceAuthorizationParameters(cfg.ClientSecret) {
		for _, value := range values {
			opts = append(opts, oauth2.SetAuthURLParam(key, value))
		}
	}
	resp, err := cfg.DeviceAuth(ctx, opts...)
	if err != nil {
		return nil, fmt.Errorf("device authorization: %w", err)
	}
	uri := resp.VerificationURIComplete
	if uri == "" {
		uri = resp.VerificationURI
	}
	interval := int(resp.Interval)
	if interval <= 0 {
		interval = 5
	}
	return &DeviceAuth{
		DeviceCode:      resp.DeviceCode,
		UserCode:        resp.UserCode,
		VerificationURI: uri,
		ExpiresIn:       int(time.Until(resp.Expiry).Seconds()),
		Interval:        interval,
	}, nil
}

// ErrDevicePending means the person hasn't finished signing in yet: the CLI
// should wait and ask again.
var ErrDevicePending = errors.New("authorization pending")

// PollDevice asks once whether the device code has been approved.
func (p *Provider) PollDevice(ctx context.Context, deviceCode string) (*Identity, error) {
	if err := p.ensure(ctx); err != nil {
		return nil, err
	}
	p.mu.Lock()
	cfg, ver := *p.oauth, p.verifier
	p.mu.Unlock()

	tok, err := pollDeviceToken(ctx, &cfg, deviceCode)
	if err != nil {
		var pe *deviceTokenError
		if errors.As(err, &pe) && (pe.Code == "authorization_pending" || pe.Code == "slow_down") {
			return nil, ErrDevicePending
		}
		return nil, err
	}
	// The device grant has no browser redirect, so there is no nonce to check.
	return p.identityFrom(ctx, ver, tok, "")
}

type deviceTokenError struct {
	Code        string
	Description string
}

func (e *deviceTokenError) Error() string {
	if e.Description != "" {
		return e.Code + ": " + e.Description
	}
	return e.Code
}

// pollDeviceToken makes exactly one RFC 8628 token request. x/oauth2's
// DeviceAccessToken owns the polling loop itself; wrapping it in a short
// context races its first ticker and can report "pending" even after approval.
// The take-back HTTP API already owns the polling cadence, so one request per
// call is both simpler and correct.
func pollDeviceToken(ctx context.Context, cfg *oauth2.Config, deviceCode string) (*oauth2.Token, error) {
	form := url.Values{
		"client_id":   {cfg.ClientID},
		"grant_type":  {"urn:ietf:params:oauth:grant-type:device_code"},
		"device_code": {deviceCode},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.Endpoint.TokenURL,
		strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if cfg.ClientSecret != "" {
		req.SetBasicAuth(cfg.ClientID, cfg.ClientSecret)
	}
	client := http.DefaultClient
	if configured, ok := ctx.Value(oauth2.HTTPClient).(*http.Client); ok && configured != nil {
		client = configured
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var payload struct {
		AccessToken      string `json:"access_token"`
		TokenType        string `json:"token_type"`
		RefreshToken     string `json:"refresh_token"`
		ExpiresIn        int64  `json:"expires_in"`
		IDToken          string `json:"id_token"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, fmt.Errorf("device token response (HTTP %d): invalid JSON", resp.StatusCode)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 || payload.Error != "" {
		return nil, &deviceTokenError{Code: payload.Error, Description: payload.ErrorDescription}
	}
	if payload.AccessToken == "" || payload.IDToken == "" {
		return nil, errors.New("device token response omitted a token")
	}
	tok := &oauth2.Token{
		AccessToken:  payload.AccessToken,
		TokenType:    payload.TokenType,
		RefreshToken: payload.RefreshToken,
	}
	if payload.ExpiresIn > 0 {
		tok.Expiry = time.Now().Add(time.Duration(payload.ExpiresIn) * time.Second)
	}
	return tok.WithExtra(map[string]any{"id_token": payload.IDToken}), nil
}

// ---- logout ----

// EndSessionURL is where to send a browser so the provider forgets the session
// too. Without this, "log out" only clears take-back's own cookie and the next
// sign-in silently walks back in through the provider's still-valid session.
func (p *Provider) EndSessionURL(ctx context.Context, idToken, postLogout string) string {
	if err := p.ensure(ctx); err != nil {
		return postLogout
	}
	p.mu.Lock()
	prov := p.provider
	p.mu.Unlock()

	var claims struct {
		EndSession string `json:"end_session_endpoint"`
	}
	if err := prov.Claims(&claims); err != nil || claims.EndSession == "" {
		return postLogout
	}
	u, err := url.Parse(claims.EndSession)
	if err != nil {
		return postLogout
	}
	q := u.Query()
	for key, values := range p.backend.LogoutParameters(idToken, postLogout) {
		for _, value := range values {
			q.Add(key, value)
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}
