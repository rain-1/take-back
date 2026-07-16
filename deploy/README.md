# Deploying take-back

The app is two static Go binaries fronted by nginx (and Cloudflare for TLS).

## Layout on the server

- `/opt/takeback/` — the `takeback-server` and `takeback-web` binaries.
- `/var/lib/takeback/` — SQLite database + uploaded media (writable state).
- Runs as an unprivileged `takeback` user via two systemd units.
- nginx vhost proxies `takeback.chain-of-thought.org` → `127.0.0.1:8080`
  (which serves the client and proxies `/api`, `/media`, `/ws` to `:8081`).

## Build (pure Go, no cgo — fully static, cross-compiles anywhere)

```sh
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o takeback-server ./cmd/server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o takeback-web    ./cmd/web
```

## First-time install

```sh
# on the server
useradd --system --home /opt/takeback --shell /usr/sbin/nologin takeback
mkdir -p /opt/takeback /var/lib/takeback/media
chown -R takeback:takeback /var/lib/takeback

# copy binaries to /opt/takeback, then:
cp deploy/takeback-server.service deploy/takeback-web.service /etc/systemd/system/
cp deploy/nginx-takeback.conf /etc/nginx/sites-available/takeback
ln -sf /etc/nginx/sites-available/takeback /etc/nginx/sites-enabled/takeback
nginx -t && systemctl reload nginx
systemctl daemon-reload
systemctl enable --now takeback-server takeback-web
```

## Updating

Rebuild, copy the new binaries over, then:

```sh
systemctl restart takeback-server takeback-web
```

## Cloudflare

Point `takeback` A record at the origin, proxied (orange cloud). Use SSL mode
**Flexible** (matches the other sites) or add a 443 origin cert for **Full
(strict)**. WebRTC media is peer-to-peer and does not transit Cloudflare or the
origin.

## Android

Set `BASE_URL` in `android/app/build.gradle.kts` to
`https://takeback.chain-of-thought.org` and rebuild the APK.
