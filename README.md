<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo.svg">
    <source media="(prefers-color-scheme: light)" srcset="assets/logo-light.svg">
    <img src="assets/logo.svg" alt="take-back — peer-to-peer chat &amp; video, self-hosted" width="470">
  </picture>
</p>

<p align="center">
  A chat application with friends, direct messages, presence, and peer-to-peer
  voice/video calls (WebRTC).
</p>

---

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

## The logo

<img src="assets/logo-mark.svg" alt="" width="76" align="left" hspace="14" vspace="4">

A speech bubble — this is a chat app — with a radiant bloom inside it: the signal
going out from you directly to everyone, with nothing in the middle holding on to
it. That is the point of the project, and it is also the shape of a sunflower
turned to the light, which is a nod to the Claude that helped build it. Twelve
petals, evenly spaced, no privileged direction: a full mesh, not a hub. The
bubble's tail is the thirteenth ray, the one pointed at you.

<br clear="left">

| File | Use it for |
| --- | --- |
| [`assets/logo.svg`](assets/logo.svg) | The full lockup — mark, wordmark, tagline. READMEs, headers, slides. Dark backgrounds. |
| [`assets/logo-light.svg`](assets/logo-light.svg) | The same lockup with dark type, for light backgrounds. |
| [`assets/logo-mark.svg`](assets/logo-mark.svg) | The icon on its own. Favicons, app tiles, anywhere the name is already nearby. |
| [`assets/logo-bloom.svg`](assets/logo-bloom.svg) | The bloom without the bubble. Round avatars, launcher icons, 16px favicons where the tail would turn to mush. |
| [`assets/logo-mono.svg`](assets/logo-mono.svg) | One colour, `currentColor`, no gradients. Stencils, stickers, embroidery, inline SVG that should follow the surrounding text. |

Palette: bloom `#FFC98A → #E8935F → #D2664A`, hub `#FFE0B8` / `#C2563C`, bubble
`#2B3550 → #161B29`, rim `#5B8CFF`. The wordmark is Inter Display SemiBold and
the tagline Inter Medium, both outlined to paths — there is no font dependency,
so the lockup is identical on every machine.

The wordmark is always lowercase. Don't recolour the bloom, rotate the mark, or
set the lockup on a busy photo; if you need it on an awkward background, use the
mono version.

### Generating variations

The marks above are hand-drawn geometry, and that is what you should ship. If you
want illustrated or photographic riffs on them — a launch banner, a sticker
sheet, a social card — these prompts describe the mark to an image model:

> **The mark.** A flat vector app icon: a rounded-square speech bubble in deep
> navy blue, with a small tail dropping from its lower-left corner and a thin
> cornflower-blue outline. Centred inside it, a twelve-petal sunflower bloom with
> slender pointed petals, evenly spaced in a full circle, in a warm gradient from
> pale apricot at the top to burnt terracotta at the bottom, around a small cream
> disc with a dark rust centre. Geometric, symmetrical, no text, no shading, no
> perspective. Dark background.

> **A hero banner.** A wide, dark, softly-lit scene: a single twelve-petal
> sunflower rendered in warm apricot and terracotta, glowing gently, floating at
> the centre of a deep navy field. Faint cornflower-blue lines radiate outward
> from it to twelve smaller points arranged around the edge, each line connecting
> directly to every other point — a full mesh, no central hub. Minimal, calm,
> plenty of negative space, no text.

> **A sticker.** A die-cut vinyl sticker on a plain surface: a chunky rounded
> speech bubble with a thick white border, deep navy fill, and a bold twelve-petal
> apricot-to-terracotta sunflower centred inside it. Soft shadow, slight sheen,
> straight-on view, no text.

Keep the counts exact when you prompt — twelve petals, one tail, one bloom. An
image model will happily give you nine petals and two tails, and then it is a
different logo.

## Versioning

`MAJOR.MINOR.PATCH`, where **MAJOR is the wire-protocol version** — so
compatibility is readable straight from the version string (`1.4.0` and `1.9.2`
interoperate; `2.0.0` does not). MINOR carries features and bug fixes. The
server advertises itself at `GET /api/version`, and both clients check it on
startup. See [CHANGELOG.md](CHANGELOG.md) for the full rules and history.

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
