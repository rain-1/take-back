package auth

import "net/url"

// Keycloak requires a confidential client's secret at the device
// authorization endpoint. RFC 8628 requires client_id there but permits the
// authorization server's normal client authentication rules as well;
// x/oauth2's DeviceAuth sends only client_id, so this profile supplies the
// additional form value. Token polling still uses standard client basic auth.
type keycloakProfile struct{ standardProfile }

func (keycloakProfile) Name() string { return BackendKeycloak }

func (keycloakProfile) DeviceAuthorizationParameters(secret string) url.Values {
	q := make(url.Values)
	q.Set("client_secret", secret)
	return q
}
