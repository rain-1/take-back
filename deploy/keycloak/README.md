# Keycloak sign-in

take-back uses standard OpenID Connect discovery, authorization code + PKCE,
RP-initiated logout, and the device authorization grant. Keycloak supports all
four; `setup.py` creates the confidential client and enables its device grant.

Run Keycloak behind HTTPS, create a realm (do not put application users in the
`master` administration realm), then run:

```sh
KC_URL=https://sso.example.org \
KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... \
python3 deploy/keycloak/setup.py --realm take-back \
  --redirect https://takeback.example.org/auth/callback
```

Add `--create-realm` if `take-back` does not exist yet. Store the printed values
in `/etc/takeback/oidc.env` with mode `0600`, then restart take-back. The issuer
has the form `https://sso.example.org/realms/take-back`.

In Keycloak's realm settings, configure WebAuthn Passwordless Policy and an
authentication flow if passkeys should replace passwords; configure OTP Policy
for authenticator apps. These are realm security decisions rather than client
settings, so the setup script deliberately does not overwrite them.

Existing-account migration is provider-independent. Follow
[`../auth/README.md`](../auth/README.md), making each Keycloak username match
the existing take-back nickname for the one-time link.
