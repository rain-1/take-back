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

## 1.16.3

**Fixed (web)**
- **Reloading no longer drops you from a call.** Hosting/joining from the lobby
  didn't record the call code in the URL, so a page reload fell back to the
  lobby and left the call. `startCall` now persists `?room=&nick=` (via
  `history.replaceState`), so a reload rejoins the same call; **Leave** clears it
  and returns to the lobby.

---

## 1.16.2

**Fixed (web)**
- **Stale client after deploy.** The web server now sends `Cache-Control: no-cache`
  on the HTML, so browsers revalidate on every load (cheap 304 when unchanged)
  instead of holding an old copy. This was making a freshly shipped control —
  e.g. the call screen's 🖥 Share-screen button — invisible until a hard refresh.

---

## 1.16.1

**Security**
- **No more media directory listing.** `GET /media/` (and any directory path
  under it) now returns 404 instead of an auto-generated index that enumerated
  every uploaded image. Individual images are still reachable by their
  unguessable hash filename; only the listing is gone.

---

## 1.16.0

**Added (web)**
- **Blockquote markdown.** Lines beginning with `> ` now render as a styled
  blockquote in the chat (consecutive `>` lines join into one quote). Bold,
  italic, code and links still work inside a quote.

---

## 1.15.1

**Changed (Android)**
- **Android now matches the web theme and style.** The whole app moves onto the
  web's design tokens (dark palette, accent blue, rounded Material3 buttons) via
  a central `colors.xml` + theme. Chat screens adopt the same **Slack-style
  grouped layout** — one left-aligned column, consecutive messages from a sender
  under a single avatar + name + time (shared `MessageRenderer`). **Profile
  pictures** appear on Android (friend rows, message groups, group members) and
  can be set from **Settings → Profile picture**; same 320px thumbnail and
  nick-hash fallback as the web.

**Fixed (web)**
- The sender name and message text weren't aligned in the new layout — the name
  sat a few px left of the text because the message body had left padding the
  group header didn't. Matched them (verified pixel-aligned).

---

## 1.15.0

Message-layout redesign (web) from Etheri's feedback. Client-only; no wire
change.

**Changed**
- **Slack/Discord-style message layout.** Messages are now a single
  left-aligned column — your own messages are no longer pulled to the right in a
  different colour ("we don't want the text on different sides"). Consecutive
  messages from the same sender within 5 minutes are **grouped under one avatar,
  name, and timestamp** instead of repeating them per line. Profile pictures
  (1.14.0) appear as the group avatar. Per-message hover toolbar (edit / reply /
  react / time) and the reaction, quote, and jump-to features all carry over.

---

## 1.14.0

Two chat requests from the group. Backwards compatible (protocol 1); the
`avatarUrl` field is additive.

**Added**
- **Profile pictures** (web). Click your avatar in the header to upload one; it's
  thumbnailed to 320px and shown wherever you appear — friend rows, the chat
  header, and (via `avatarUrl` on every user) anywhere else that renders an
  avatar. Falls back to the initials circle when unset. `users` gains an
  `avatar_file` column via migration; `POST /api/me/avatar` stores it.

**Fixed**
- **Contrast on your own (blue) messages** (Etheri): the ✎/☺/↩ controls,
  timestamp, and "edited" marker used dim greys that vanished against the accent
  bubble — lightened for sent messages.

Android profile pictures and the Slack-style message layout (same-side +
consecutive-message grouping) are the next items.

---

## 1.13.0

Replies and a keyboard shortcut (web + backend). Backwards compatible
(protocol 1) — the `replyTo`/`replyBody`/`replySender` fields are additive, so
older clients ignore them.

**Added**
- **Reply to a message** (web), in DMs and groups. Hover a message → ↩, and the
  composer shows what you're replying to. The reply carries a **quote block**;
  **clicking the quote jumps to the original** and briefly highlights it. The
  quoted snippet is stored with the reply (joined server-side), so it renders
  even if the original has scrolled far away.
- **Press ↑ in an empty composer to edit your last message** — the shell/Slack
  convention. Only fires when the box is empty, so it never interferes with
  cursor movement.

**Android** has replies too: long-press a message → **Reply** (or React), the
composer shows what you're replying to, the sent message shows a quote block, and
tapping the quote scrolls to the original and flashes it. `messages` and
`group_messages` gain a `reply_to` column via migration.

(↑-to-edit is a desktop-keyboard convention and stays web-only.)

---

## 1.12.0

Message reactions (web + backend). Backwards compatible (protocol 1); the new
`reaction` event and `reactions` field are additive.

**Added**
- **Emoji reactions on messages**, in DMs and groups. Hover a message → ☺ to
  pick from a small palette, or click an existing pill to add/remove yours.
- **Hover a reaction to see who reacted** (the pill's tooltip lists the nicks).
- Reactions sync live over `/api/events`, and are embedded in message listings
  so they're there on load.

**Android** has reactions too: reaction chips under each bubble, long-press a
message to pick an emoji, tap a chip to toggle yours, and long-press a chip to
see who reacted (mobile has no hover). Same wire format as web, so a reaction
from one shows on the other.

Reactions are scoped (dm/group, since the two message tables have separate id
spaces), authorized (you can only react where you can see the message — DM
participants or group members), and toggling is idempotent.

---

## 1.11.0

Group membership now requires consent. Backwards compatible (protocol 1) —
`/api/groups/add` still exists and simply sends an invite now, so older clients
keep working and get the safer behaviour for free.

**Changed**
- **You can no longer be added to a group against your will.** Adding someone
  now sends an **invite**, which lands in their requests tray next to friend
  requests, to accept or decline. Until they accept they are not a member: the
  group doesn't appear in their list, they can't read it, they aren't counted in
  the member count, and it produces no unread for them.

**Added**
- `GET /api/groups/invites`, `POST /api/groups/invite`, `POST /api/groups/respond`,
  and a `group_invite` event (carrying the group name and who invited you) so it
  appears live and notifies.

`group_members` gains `status` (invited/joined) via a migration that defaults
existing rows to **joined** — verified against a copy of the production database
so nobody currently in a group is bumped back to a pending invite.

---

## 1.10.0

**Added**
- **`cmd/tb`, a command-line client.** Reads and sends messages from a terminal,
  against the same API as the apps:

      tb login [-register] <nick>     log in (session stored 0600 under ~/.config)
      tb inbox                        conversations, newest first, with unread
      tb read [-n N] <nick|#group>    show a conversation, marks it read
      tb send <nick|#group> <text>    send a message
      tb add / tb accept              friend requests
      tb watch                        live-tail incoming messages over /api/events

  It re-logs in silently when a session expires, and orders conversations by
  recency to match the apps.

---

## 1.9.0

Android gains the 1.8.0 audio controls. Backwards compatible (protocol 1);
client-side only, no wire changes.

**Added (Android)**
- **Mic gain**, and it's a *real* one: this WebRTC build exposes
  `setAudioRecordDataCallback`, which hands over the actual capture buffer
  **before it's encoded**, so scaling the samples there changes what peers
  receive. (The `setSamplesReadyCallback` used for the meter only gets a copy —
  useful for monitoring, useless for gain.) At 100% the buffer isn't touched at
  all, so the default path is byte-for-byte unchanged. Samples are clamped, since
  wrapping 16-bit values turns loud speech into noise.
- **Live mic level meter** in the settings panel, reading post-gain (the samples
  callback runs after the data callback), so it shows what peers hear. Polls only
  while the panel is open, and reads zero while muted.
- **Per-participant volume** sliders. WebRTC's `AudioTrack.setVolume` takes
  0..10, so unlike the web (capped at 1.0 by the media element) Android can
  actually **boost** a quiet talker — the slider goes to 200%.

Both platforms now use the same meter scaling, so the bars feel alike.

---

## 1.8.0

Audio controls in the web call settings. Backwards compatible (protocol 1);
purely client-side, no wire changes.

**Added**
- **Per-participant volume** sliders — turn down whoever's loud, per person
  rather than one blunt master. Applied to each peer's media element, and
  re-applied if their tile is rebuilt. Capped at 100%: boosting past it would
  mean routing their audio through Web Audio, which goes silent if the
  AudioContext is suspended — not worth risking someone's audio.
- **Mic gain** — your level as peers hear it, persisted across calls.
- **Live mic level meter** showing what peers actually hear (post-gain), so you
  can check you're being picked up and aren't too quiet or loud. It only polls
  while the settings panel is open.

The gain is applied by routing the mic through a Web Audio GainNode and sending
that processed track. That's engaged **lazily** — only once you touch the slider
— because a suspended AudioContext makes the processed track emit silence, and
the context starts suspended when a call auto-joins from a chat link (no user
gesture on that page). Until then we send the raw track exactly as before, so
the default path is unchanged. A saved gain is applied on your first click.

**Not yet on Android**: these audio controls are web-only.

---

## 1.7.0

Android reaches parity with the web client's 1.6.0 call features. Backwards
compatible (protocol 1); the two interoperate — an Android screen share shows as
a separate tile to a web peer and vice versa.

**Added (Android)**
- **Screen sharing alongside the camera**, as on web: the screen is a second
  video track and peers get a separate "<name>'s screen" tile. Android already
  answered incoming re-offers, so renegotiation is initiated explicitly only when
  the screen track is added/removed — with the same polite/impolite glare
  tiebreak as the web, and SDP rollback for the polite side.
- Track routing now uses `onAddTrack` (not `onTrack`) because it supplies the
  MediaStreams — the stream id is what tells a screen apart from a camera. State
  can arrive after the track, so tracks are re-routed when it does.
- **In-call settings panel (⚙)**: camera picker, audio source, and a **mirror**
  toggle that persists across calls (self-view only; the screen share is never
  mirrored).

**Note on Android audio source**: WebRTC's audio device module always captures
from the system *communication* device, so unlike the web there's no direct mic
picker — the setting chooses the communication **route** (built-in, wired,
Bluetooth…) via `AudioManager`. That API is Android 12+; older devices show
"System default" only.

---

## 1.6.0

Backwards compatible (protocol 1). The `state` message gains an additive
`screenId` field; older clients ignore it.

**Added**
- **Screen sharing now runs alongside your camera** instead of replacing it —
  people expect to see your face while you present. The screen goes out as a
  second video track, so peers get a separate "<name>'s screen" tile next to
  their camera tile. This needed real renegotiation, so the call now implements
  the **perfect negotiation** pattern (deterministic polite/impolite tiebreak on
  peer id), which also makes simultaneous shares safe instead of wedging the
  connection.
- **Settings panel (⚙)** for set-once-and-keep preferences, which persist in
  localStorage across calls:
  - **Microphone** and **camera** pickers. Switching uses `replaceTrack`, so
    there's no renegotiation and peers see no interruption. Saved devices are
    reused on the next call (falling back if unplugged).
  - **Mirror my video** moved here from the toolbar. Self-view only — peers
    always see you un-mirrored, and the screen share is never mirrored.

**Changed**
- Share screen is now a primary toolbar action; it was easy to miss in a
  crowded, wrapping bar.

**Not yet on Android**: the settings panel, device pickers, and simultaneous
camera+screen are web-only; Android still swaps the camera for the screen.

---

## 1.5.0

Backwards compatible (protocol 1). Adds an `edited_at` column via an idempotent
migration — verified against a copy of the production database (33 messages, 5
users preserved; safe to re-run).

**Added**
- **Edit your own messages** (web), in DMs and groups. Hover your message and
  click ✎ edit; Enter saves, Escape cancels. Edited messages carry an "· edited"
  marker with the edit time on hover, and the change pushes live to the other
  side (`message_edited` / `group_message_edited`).
- Editing is author-only, enforced server-side (not just hidden in the UI), and
  restricted to text — an image message keeps its attachment.

**Not yet on Android**: message editing is web-only for now.

---

## 1.4.0

Call reliability fixes from web-client user reports. Backwards compatible
(protocol 1).

**Fixed**
- **Refreshing the page broke the call and the peer never reconnected.** Root
  cause: signaling had no keepalive. Media is peer-to-peer, so the signaling
  socket goes silent for the whole call — and an idle socket is culled by
  proxies (Cloudflare drops them after ~100s). That silently removed the peer
  from its room server-side, so a refreshing peer's offer reached nobody. The
  server now pings clients (with read deadlines to reap genuinely dead ones),
  and the client auto-reconnects with backoff if the socket does drop.
- **"Disconnected" while the call was working fine.** The indicator was wired to
  the *signaling socket*, not the call. It now derives from peer connection
  state; signaling trouble shows separately and quietly (⚠ signaling), since it
  doesn't interrupt an in-progress call — it only blocks new peers joining.
- **Talk indicator broke intermittently.** The level poll used
  `requestAnimationFrame`, which browsers pause entirely in background tabs, so
  the ring froze mid-state. Now on a timer, with the AudioContext resumed on
  visibility change / user gesture (a suspended context silently kills all rings).
- **Audio playback failed intermittently.** Two causes: autoplay-with-sound can
  be blocked when a call auto-joins from a chat link (no click on that page) —
  the failure was silent, and now surfaces a one-tap "🔊 Enable audio"; and the
  camera-off tile used `display:none` on the video element, which can tear down
  playback — it now stays rendered (`opacity:0`) under the avatar.

**Added**
- **Flip my video** — mirrors your own self-view only; what peers receive is
  unchanged. Defaults to mirrored, like most video apps.

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
