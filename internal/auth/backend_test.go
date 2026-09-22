package auth

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"golang.org/x/oauth2"
)

func TestBackendProfiles(t *testing.T) {
	for _, name := range []string{"", BackendGeneric, BackendAuthentik, BackendKeycloak, "KEYCLOAK"} {
		p := New(Config{Backend: name, Issuer: "https://id.example/realms/chat"})
		if p.configErr != nil {
			t.Fatalf("backend %q: %v", name, p.configErr)
		}
		if p.Backend() == "" {
			t.Fatalf("backend %q has no profile name", name)
		}
	}
	if err := ValidateConfig(Config{Backend: "keycloack"}); err == nil {
		t.Fatal("an unknown backend should be rejected")
	}
}

func TestPollDeviceTokenIsOneAuthenticatedRequest(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("client:secret"))
		if r.Header.Get("Authorization") != wantAuth {
			t.Errorf("authorization = %q, want client basic auth", r.Header.Get("Authorization"))
		}
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		if r.Form.Get("device_code") != "device-code" ||
			r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:device_code" {
			t.Errorf("unexpected form: %v", r.Form)
		}
		w.Header().Set("Content-Type", "application/json")
		if calls == 1 {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"error":"authorization_pending"}`))
			return
		}
		w.Write([]byte(`{"access_token":"access","token_type":"Bearer","expires_in":60,"id_token":"id"}`))
	}))
	defer server.Close()
	cfg := &oauth2.Config{
		ClientID: "client", ClientSecret: "secret",
		Endpoint: oauth2.Endpoint{TokenURL: server.URL},
	}
	if _, err := pollDeviceToken(context.Background(), cfg, "device-code"); err == nil {
		t.Fatal("pending response should be returned to the caller")
	} else if e, ok := err.(*deviceTokenError); !ok || e.Code != "authorization_pending" {
		t.Fatalf("pending error = %T %v", err, err)
	}
	tok, err := pollDeviceToken(context.Background(), cfg, "device-code")
	if err != nil || tok.AccessToken != "access" || tok.Extra("id_token") != "id" {
		t.Fatalf("approved token = %#v, %v", tok, err)
	}
	if calls != 2 {
		t.Fatalf("made %d requests, want exactly one per call", calls)
	}
}

func TestBackendProfileSecurityParameters(t *testing.T) {
	for _, name := range []string{BackendGeneric, BackendAuthentik, BackendKeycloak} {
		profile, err := profileFor(name)
		if err != nil {
			t.Fatal(err)
		}
		if got := profile.AuthorizationParameters(true).Get("prompt"); got != "login" {
			t.Errorf("%s force-login prompt = %q", name, got)
		}
		logout := profile.LogoutParameters("signed-id-token", "https://chat.example/")
		if logout.Get("id_token_hint") != "signed-id-token" ||
			logout.Get("post_logout_redirect_uri") != "https://chat.example/" {
			t.Errorf("%s logout parameters = %v", name, logout)
		}
	}
	keycloak, _ := profileFor(BackendKeycloak)
	if got := keycloak.DeviceAuthorizationParameters("secret").Get("client_secret"); got != "secret" {
		t.Fatalf("keycloak device authorization secret = %q", got)
	}
	authentik, _ := profileFor(BackendAuthentik)
	if got := authentik.DeviceAuthorizationParameters("secret").Get("client_secret"); got != "" {
		t.Fatalf("authentik device authorization unexpectedly sends a secret: %q", got)
	}
}

func TestAuthorizationOrigin(t *testing.T) {
	tests := []struct {
		issuer string
		want   string
	}{
		{"https://sso.example/realms/take-back", "https://sso.example"},
		{"http://localhost:8080/realms/dev", "http://localhost:8080"},
		{"javascript:alert(1)", ""},
		{"not a URL", ""},
	}
	for _, tt := range tests {
		p := New(Config{Issuer: tt.issuer})
		if got := p.AuthorizationOrigin(); got != tt.want {
			t.Errorf("origin(%q) = %q, want %q", tt.issuer, got, tt.want)
		}
	}
}

func TestKeycloakProfileLeavesStandardAuthURLIntact(t *testing.T) {
	profile, _ := profileFor(BackendKeycloak)
	q := make(url.Values)
	q.Set("state", "state-value")
	q.Set("nonce", "nonce-value")
	for key, values := range profile.AuthorizationParameters(false) {
		for _, value := range values {
			q.Add(key, value)
		}
	}
	if q.Get("state") == "" || q.Get("nonce") == "" {
		t.Fatalf("backend parameters discarded protocol protections: %v", q)
	}
}
