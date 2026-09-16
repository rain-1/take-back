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
```

Windows package (from Linux/WSL):

```sh
ZIG=/path/to/zig scripts/package-win.sh   # -> dist/take-back-desktop-win32-x64-<version>.zip
```

It downloads the official Electron Windows build, verifies it against
Electron's published SHA-256 sums, adds the app and the cross-compiled audio
helper, and zips it. Unsigned, so Windows SmartScreen will warn on first run.
