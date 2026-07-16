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

## 1.3.0

Backwards compatible (protocol 1). Android calls reach parity with the web
client's 1.2.0 call features, and the two interoperate — an Android user's
camera-off/mute shows correctly to a web user and vice versa.

**Added (Android)**
- **Speaking ring, profile pictures, and mic/camera toggles in calls**, matching
  the web client. Thresholds and hysteresis are shared (see `SpeakingDetector`),
  so both platforms feel the same.

Audio levels can't be measured the way the web does it (there's no Web Audio),
so Android uses two sources:
- **Your own mic**: raw PCM from the WebRTC audio device module
  (`setSamplesReadyCallback`), RMS'd per buffer. This deliberately doesn't use
  `getStats`, which only reports once media is flowing to a peer — you can check
  your mic works before anyone else joins.
- **Remote peers**: `audioLevel` from each peer connection's `inbound-rtp` stats,
  polled every 200ms (fast enough to feel live, cheap enough per peer).

## 1.2.0

Backwards compatible with 1.0/1.1 clients (protocol 1). The new `state`
signaling message is additive — older clients ignore unknown message types, and
newer clients assume "camera on" for a peer that never sends one.

**Added**
- **Speaking indicator in calls.** Each participant's audio is tapped with a Web
  Audio `AnalyserNode`; when their short-term RMS crosses a threshold their tile
  rings green. It uses hysteresis (on at 0.035 RMS, off below 0.020 after 350ms)
  so the ring doesn't flicker between syllables. This shows who's talking in a
  multi-person call, and lets you confirm your own mic is picking you up.
- **Profile pictures in calls.** With the camera off, a tile shows the same
  initials avatar as the chat client, and the speaking ring goes around it.
- **Mic and camera toggles**, so a voice-only call is actually possible. Toggling
  only flips `track.enabled` — no renegotiation. Your mic/camera state is sent to
  peers over signaling (a disabled video track sends black frames, which peers
  would otherwise render as a black tile). A muted mic never shows a speaking
  ring, and shows a 🔇 badge.

**Changed**
- The call page now uses the same design tokens as the chat client, which had
  drifted apart visually.

## 1.1.0

Backwards compatible with 1.0.0 clients (protocol 1) — the new `lastActivity`
field is additive and older clients simply ignore it.

**Fixed**
- Conversation lists no longer reshuffle when you click them. They were sorted
  by online → unread → name, so opening a chat cleared its unread and made the
  row jump. Lists are now ordered by **most recent message first** (a new
  message moves that conversation to the top, live), which is stable across
  clicks and presence changes. Friends with no messages sort last, by name.
- The ✕ on a friend row silently unfriended them — it reads like a "dismiss
  chat" control but is destructive and mutual. It now says "Remove friend" and
  asks for confirmation, on web and Android.
- Android friend/group rows now show unread pips and bold unread names.

**Added**
- `lastActivity` (unix time of the last message) on `/api/friends` and
  `/api/groups`.

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
