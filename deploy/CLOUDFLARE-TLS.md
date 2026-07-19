# Cloudflare ↔ origin TLS (Full strict) + origin hardening

How take-back's edge is set up on Cloudflare and the origin VPS, and the one
dashboard step needed to finish the TLS upgrade.

## The picture

```
browser ──HTTPS──▶ Cloudflare ──HTTPS(:443, Origin cert)──▶ nginx ──HTTP──▶ cmd/web (:8080) ──▶ cmd/server (:8081)
```

- The browser always talks HTTPS to Cloudflare (required for getUserMedia /
  Notifications). Cloudflare proxies the domain (orange cloud).
- **Origin hardening:** the nginx vhost (`deploy/nginx-takeback.conf`) only
  accepts connections from Cloudflare's published IP ranges and denies all else,
  so nobody can hit the origin directly to forge the `CF-Connecting-IP` header
  the app rate-limits on, or bypass Cloudflare. Verified: domain → 200, direct
  origin IP → 403.
- **CF → origin TLS:** nginx listens on `:443` with a **Cloudflare Origin CA**
  cert. Once the SSL mode is Full (strict), the whole path is encrypted and the
  origin cert is validated.

## Already done on the origin (VPS 194.238.29.198)

- Cloudflare Origin cert + key installed:
  - `/etc/ssl/takeback/origin.pem`  (cert, 644 root)
  - `/etc/ssl/takeback/private.pem` (key, **600 root** — never leaves the box, never committed)
- nginx vhost answers both `:80` and `:443 ssl` (single server block; the
  Cloudflare allow-list applies to both). `ssl_protocols TLSv1.2 TLSv1.3`.
- Cert SAN = `takeback.chain-of-thought.org` (matches the hostname → Full strict validates).
- Verified: `openssl s_client` to origin `:443` serves the Origin cert; site
  still 200 through Cloudflare.

The `*.pem` files are git-ignored. Keep your local copies somewhere safe (or
delete them — they live on the server now). If you lose the key, just generate a
new Origin cert (below) and reinstall.

## The one step left — flip the SSL mode (Cloudflare dashboard)

1. Cloudflare dashboard → select **chain-of-thought.org**.
2. **SSL/TLS → Overview** → set encryption mode to **Full (strict)**.
   - NB this is a **zone-wide** setting. It affects every site on
     chain-of-thought.org, so each origin behind this zone must also answer
     HTTPS with a valid cert. If other sites on the VPS are HTTP-only, either
     give them origin certs too, or use a per-hostname **Configuration Rule**
     (Rules → Overrides) to set SSL = Full (strict) for `takeback.*` only and
     leave the zone default as-is.
3. (Recommended) **SSL/TLS → Edge Certificates** → enable **Always Use HTTPS**.

## Verify after flipping

```bash
# Through Cloudflare — should stay 200:
curl -s -o /dev/null -w "%{http_code}\n" https://takeback.chain-of-thought.org/api/version
# A 526 = "invalid origin cert": SSL mode is strict but the origin cert didn't
# validate. Check the cert SAN covers the hostname and the mode scope (step 2).
```

## How the Origin cert was created (for renewal)

Cloudflare dashboard → **SSL/TLS → Origin Server → Create Certificate** →
hostnames `takeback.chain-of-thought.org` → gives a **cert** (→ `origin.pem`)
and a **private key** (→ `private.pem`). Default validity is 15 years. To
rotate: create a new one, copy both files to `/etc/ssl/takeback/`, fix perms
(`chmod 600 private.pem`), `nginx -t && systemctl reload nginx`.

## Refreshing Cloudflare's IP allow-list

The ranges in `deploy/nginx-takeback.conf` are hardcoded from
<https://www.cloudflare.com/ips>. They change rarely; if Cloudflare updates
them, refresh the `allow` lines, `nginx -t`, and reload.
