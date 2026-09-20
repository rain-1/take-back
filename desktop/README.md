# take-back desktop (proof of concept)

Screen sharing that sends **one application's audio** — not your whole
computer's, and never the call itself echoed back. Browsers can't do this; this
Electron app can, with a small native helper per OS.

It loads the ordinary take-back web client. Nothing about calls or chat is
reimplemented, and **no other client needs updating**: the app's audio travels
as an extra track on the screen-share stream, which every client already sends
and plays from the screen tile.

## How it fits together

```
 chosen app ──► native helper ──► main process ──IPC──► preload ──► AudioWorklet
 (per OS)       raw f32 48k stereo                                  │
                                                                     ▼
            everyone's screen tile ◄── WebRTC ◄── screen stream + audio track
```

- `src/main.js` — window, screen/window picker, `getDisplayMedia` handler, audio IPC
- `src/preload.js` — the small `window.tbDesktop` bridge, and a wrapper around
  `getDisplayMedia` that adds the chosen app's audio track
- `src/audio-sources.js` — per-OS capture backends behind one contract
- `native/win/tb-app-audio.cpp` — Windows helper (WASAPI process loopback)
- Linux uses PipeWire's own `pw-dump` / `pw-record`; no compiled code

## Status

| | |
|---|---|
| Pipeline (Electron → WebRTC → other person's screen tile) | ✅ `npm run test:pipeline` — 440 Hz tone arrives at the right frequency and level; control run sends no audio |
| Windows per-app capture | ✅ run on real Windows 11: lists audio apps, captures one app's audio in isolation |
| Windows build | ✅ `scripts/package-win.sh` (from Linux/WSL, no Windows toolchain). Ran on real Windows 11 in a call: the other side received the source at 441 Hz within 0.25 s |
| Real app capture inside the Windows build, in a call | ⏳ needs a human test on Windows |
| Linux (PipeWire) | ⚠️ written, not yet run on a PipeWire desktop |
| macOS | ❌ not started (ScreenCaptureKit) |

Findings so far: capture follows the app's own volume and its Windows mixer
slider, and a muted app shares silence — Windows applies both before loopback.

## Running

```sh
npm install
npm start                                   # connects to takeback.chain-of-thought.org
TB_SERVER=http://localhost:8080 npm start    # a local server
```

Windows helper (cross-compiled from Linux/WSL with Zig, no Windows SDK needed):

```sh
ZIG=/path/to/zig native/win/build.sh        # -> native/win/bin/tb-app-audio.exe
native/win/bin/tb-app-audio.exe --list
```

Tests (need Go, Chrome and a display):

```sh
GO=go npm run test:pipeline     # app audio reaches the other person's screen tile
GO=go node test/audit.test.js   # web-parity checks against the real window
GO=go TB_SOURCE_ROOT=/path/to/take-back node test/servers.test.js
                                # servers, invite links and voice channels (needs a v1.22.0+ checkout)
node test/security.test.js      # what the page is NOT allowed to do (no Go needed)
```

`security.test.js` drives the real window as if the page had been taken over:
it tries to capture audio nobody picked, to leave take-back (directly and
through a redirect on take-back's own origin), to load another site in an
iframe, to open a local file, and to help itself to permissions. Everything the
app grants a page is listed there, so a change that widens it fails a check.

Server invite links (`<server>/?invite=CODE`) open the join dialog **in the
app**: clicked in chat, or passed on the command line, including to a copy
that's already running (`take-back.exe https://…/?invite=CODE`).

Windows package (from Linux/WSL):

```sh
ZIG=/path/to/zig scripts/package-win.sh   # -> dist/take-back-desktop-win32-x64-<version>.zip
```

It downloads the official Electron Windows build, verifies it against
Electron's published SHA-256 sums, adds the app and the cross-compiled audio
helper, zips it, and writes `<zip>.sha256` beside it.

The zip is **unsigned** — Windows SmartScreen warns on first run, and there is
no update channel: whoever downloads it is trusting the HTTPS connection to the
download page and nothing else. Anyone who can change what that page serves (a
compromised server, a stolen certificate) can hand out a trojaned build, and
nothing on the user's machine would object. Until there is a signing
certificate, the honest improvement is to make tampering *visible*: publish the
`.sha256` next to the download link so a build can be checked against it, and
keep the app's own version visible in-app so people can tell which one they
are running.
