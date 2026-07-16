# Changelog

## Versioning scheme

take-back uses `MAJOR.MINOR.PATCH`, where **MAJOR is the wire-protocol version**:

| Component | Meaning | Bump when |
|-----------|---------|-----------|
| **MAJOR** | The API/wire contract. Always equal to `Protocol` in `internal/version`. | A **breaking** change: removing/repurposing an endpoint, field or event, or changing auth. Old clients **must** update. |
| **MINOR** | Backwards-compatible changes — **new features and bug fixes**. | Anything additive or a fix that doesn't break an older client on the same MAJOR. |
| **PATCH** | No client-visible surface at all (internal refactors, packaging, docs). | Housekeeping. |

Because MAJOR == protocol, **compatibility is readable from the version string**:
`1.4.0` and `1.9.2` interoperate; `1.9.2` and `2.0.0` do not.

- The server advertises itself at `GET /api/version` →
  `{"name":"take-back","version":"1.0.0","protocol":1}`.
- The web client and the Android app each compile in the protocol they speak and
  compare on startup — the web shows a "reload" warning, Android shows an
  "update required" dialog.
- `internal/version` is the single source of truth for the Go side; the Android
  `versionName`/`PROTOCOL` in `app/build.gradle.kts` must be kept in step. A unit
  test pins MAJOR == Protocol so the two can't silently drift.

---

## 1.0.0

First versioned release — the app is deployed and working end to end at
https://takeback.chain-of-thought.org (web + native Android).

**Features**
- Accounts with session-cookie auth (bcrypt), rate-limited register/login.
- Friends: requests, accept/decline, remove, with live online/offline presence.
- Direct messages: Markdown text and image sharing with server-side thumbnails.
- Group chats: create, member list with presence, add members, group messages.
- Calls: peer-to-peer WebRTC voice/video with screen sharing, in DMs and groups
  (full mesh, STUN-assisted); launchable from any chat.
- Unread pips on conversations, backed by server-side read state so counts agree
  across web and Android.
- Desktop/OS notifications for friend requests and messages.
- Native Android client at parity, with a configurable server URL.

**Known gaps** (see `deploy/README.md` and the roadmap)
- No background push (FCM / Web Push) — notifications need the client running.
- No TURN server — calls between two symmetric NATs may not connect.
- Debug APK only; no signed release build yet.
