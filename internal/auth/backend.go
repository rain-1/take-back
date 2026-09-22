package auth

import (
	"fmt"
	"net/url"
	"strings"
)

const (
	BackendGeneric   = "generic"
	BackendAuthentik = "authentik"
	BackendKeycloak  = "keycloak"
)

// backendProfile is the deliberately small seam for provider-specific OIDC
// behaviour. Discovery, PKCE, token verification and device flow remain in the
// shared standards implementation; a backend only supplies the few request
// details on which providers can differ.
type backendProfile interface {
	Name() string
	AuthorizationParameters(forceLogin bool) url.Values
	DeviceAuthorizationParameters(clientSecret string) url.Values
	LogoutParameters(idToken, postLogout string) url.Values
}

type standardProfile string

func (p standardProfile) Name() string { return string(p) }

func (p standardProfile) AuthorizationParameters(forceLogin bool) url.Values {
	q := make(url.Values)
	if forceLogin {
		q.Set("prompt", "login")
	}
	return q
}

func (p standardProfile) DeviceAuthorizationParameters(_ string) url.Values {
	return make(url.Values)
}

func (p standardProfile) LogoutParameters(idToken, postLogout string) url.Values {
	q := make(url.Values)
	if idToken != "" {
		q.Set("id_token_hint", idToken)
	}
	if postLogout != "" {
		q.Set("post_logout_redirect_uri", postLogout)
	}
	return q
}

var backendProfiles = map[string]backendProfile{
	BackendGeneric:   standardProfile(BackendGeneric),
	BackendAuthentik: standardProfile(BackendAuthentik),
	BackendKeycloak:  keycloakProfile{},
}

func profileFor(name string) (backendProfile, error) {
	name = strings.ToLower(strings.TrimSpace(name))
	if name == "" {
		name = BackendGeneric // backward compatible with the original config
	}
	p, ok := backendProfiles[name]
	if !ok {
		return nil, fmt.Errorf("unsupported OIDC backend %q (use generic, authentik, or keycloak)", name)
	}
	return p, nil
}
