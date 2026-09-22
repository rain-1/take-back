# Authentik sign-in

`setup.py` idempotently creates the take-back OIDC application, enables the
device flow used by the CLI, and adds passwordless WebAuthn to Authentik's
normal sign-in screen.

## Configure Authentik

Install Authentik using its upstream Compose deployment, publish it behind the
included `../nginx-authentik.conf`, then create an API token in its admin UI:

```sh
AK_URL=https://auth.example.org AK_TOKEN=... ./deploy/authentik/setup.py \
  --redirect https://takeback.example.org/auth/callback
```

The command prints the client ID and secret. Store them on the take-back host
in `/etc/takeback/oidc.env`, owned by root and mode `0600`:

```ini
TB_OIDC_BACKEND=authentik
TB_OIDC_ISSUER=https://auth.example.org/application/o/take-back/
TB_OIDC_CLIENT_ID=...
TB_OIDC_CLIENT_SECRET=...
TB_OIDC_REDIRECT_URL=https://takeback.example.org/auth/callback

# Migration only. Remove both lines after every account is linked and old
# clients have been replaced.
TB_OIDC_CLAIM_BY_USERNAME=1
TB_AUTH_PASSWORD_FALLBACK=1
```

The systemd unit reads that file. Never put its values directly in `ExecStart`:
command-line secrets are visible in process listings.

## Safe migration

1. Back up SQLite with `.backup` and verify it with `PRAGMA integrity_check`.
2. Create each existing nickname as the same username in Authentik. Do not
   recycle a username that belonged to somebody else.
3. Deploy server, web, Android APK, and desktop together with both migration
   flags enabled. Existing sessions and old clients continue to work.
4. Ask each person to sign in once through Authentik. Their matching unlinked
   take-back row is linked to Authentik's stable user UUID.
5. Run `takeback-server -list-accounts` against the production database and
   confirm every required account says `identity ...`.
6. Remove `TB_OIDC_CLAIM_BY_USERNAME` first. After the client upgrade window,
   remove `TB_AUTH_PASSWORD_FALLBACK` too and restart the server. Password POSTs
   then return HTTP 410.

For an explicit link, use `takeback-server -link nick=subject`. To provision a
provider-only local row without opening registration, use
`takeback-server -make-account nick`. Stop the service before running these
commands against SQLite, or use a copied database for inspection.

The desktop app discovers the Authentik origin from the server. `TB_AUTH_ORIGIN`
is only an override for development or servers predating that discovery field.
