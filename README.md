# take-back

A chat application with friends, direct messages, presence, and peer-to-peer
voice/video calls (WebRTC).

## Features

- **Accounts & auth** — register / log in; session-cookie auth, bcrypt-hashed
  passwords.
- **Friends** — send requests by nickname, accept/decline, remove; live
  **online/offline** presence.
- **Direct messages** — 1:1 chats with **Markdown** text and **image sharing**;
  uploaded images are thumbnailed server-side (longest edge 320px) and open full
  size on click. New messages and presence changes stream live over a WebSocket.
- **Calls** — nickname → host a call for a shareable code → others join into a
  **full-mesh** WebRTC audio/video call, with screen sharing and camera flip.
  Launchable straight from a DM chat.

## Architecture

Two Go programs plus a native Android client:

- **`cmd/server`** — the backend. WebRTC signaling relay (`/ws`), plus the JSON
  API (`/api/*`), presence/events WebSocket (`/api/events`), and media serving
  (`/media/*`). SQLite persistence via a pure-Go driver (no cgo). Media never
  flows through it — once peers connect, audio/video is direct (STUN-assisted).
- **`cmd/web`** — serves the browser client and **reverse-proxies** `/api`,
  `/media`, and `/ws` (including their WebSocket upgrades) to the server, so the
  browser sees a single origin and cookies work.
- **`android/`** — native Kotlin client for the calls (see `android/README.md`).

Internal packages: `internal/store` (SQLite: users, sessions, friendships,
messages), `internal/api` (HTTP handlers + image thumbnailing), `internal/presence`
(online tracking + event push).

## Run

```sh
# terminal 1 — backend (API + signaling), default :8081
go run ./cmd/server                 # -db takeback.db -media media

# terminal 2 — web client + proxy, default :8080
go run ./cmd/web                    # -backend http://localhost:8081
```

Open http://localhost:8080, register two accounts (two browsers/profiles), add
each other as friends, and chat or call.

### Flags

- `server -addr :8081 -db takeback.db -media media`
- `web -addr :8080 -backend http://localhost:8081`

## NAT traversal notes

STUN alone punches through most NATs. For symmetric NATs where no direct path
exists, add a TURN server to `ICE_CONFIG` in `cmd/web/static/call.html`. Testing
real NAT passthrough requires the two browsers on different networks with the
server reachable by both.

## Deploying behind TLS

Front `cmd/web` with your TLS reverse proxy. Because the page and all backend
paths share one origin, everything (including `wss://` upgrades) works through a
single vhost. `getUserMedia` requires HTTPS off localhost.
