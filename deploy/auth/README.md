# Pluggable identity providers

The server has one shared, standards-based OIDC implementation and small
backend profiles for provider-specific behavior. Supported values are:

- `generic` — any conforming OpenID Connect provider (also the default when
  `TB_OIDC_BACKEND` is omitted, preserving older configurations)
- `authentik`
- `keycloak`

All profiles use discovery, authorization code + PKCE, signed ID-token
verification, nonce/state replay protection, RP-initiated logout, and device
authorization for the CLI. Provider passwords, passkeys, OTP codes, tokens,
and client secrets never enter native clients.

```ini
TB_OIDC_BACKEND=keycloak
TB_OIDC_ISSUER=https://sso.example.org/realms/take-back
TB_OIDC_CLIENT_ID=take-back
TB_OIDC_CLIENT_SECRET=...
TB_OIDC_REDIRECT_URL=https://takeback.example.org/auth/callback
```

The desktop client discovers the authorization origin from
`/api/auth/status`, so changing providers does not require rebuilding it.

## Migrating existing accounts

Set these only during migration:

```ini
TB_OIDC_CLAIM_BY_USERNAME=1
TB_AUTH_PASSWORD_FALLBACK=1
```

Create provider usernames matching existing take-back nicknames. On first OIDC
sign-in, take-back links the stable `sub` claim to that row; subsequent provider
renames do not affect ownership. Confirm links using
`takeback-server -list-accounts`, then remove `TB_OIDC_CLAIM_BY_USERNAME`.
Remove the password fallback after old clients have been upgraded.

Only one provider is active at a time. Because existing account links contain
that provider's stable subject, changing an established deployment to a
different issuer requires an explicit account migration; do not enable
username claiming over already-linked production rows and assume it will move
those links.
