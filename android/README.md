# take-back — Android client

A native Kotlin client that speaks the same signaling protocol as the web client
and joins the same WebRTC full mesh. Feature parity with the browser: nickname →
host/join by code → peer-to-peer audio/video, plus screen sharing and camera flip.

## Structure

- `SignalingClient.kt` — OkHttp WebSocket; same JSON shapes as the Go server
  (`type` / `to` / `from` / `nick` / `peers` / string `payload`).
- `RtcEngine.kt` — one `PeerConnection` per remote peer (full mesh); shared local
  mic + camera; screen sharing swaps the capturer feeding one video source, so no
  renegotiation (mirrors the web client's `replaceTrack`).
- `ScreenCaptureService.kt` — mediaProjection foreground service (API 29+ gate).
- `MainActivity.kt` — three-step UI, permissions, and the video grid.

## Build

Open the `android/` folder in **Android Studio** (Giraffe or newer) and let it
sync — it will fetch the WebRTC and OkHttp dependencies and generate the Gradle
wrapper. Or from the command line with a local Gradle 8.7:

```sh
cd android
gradle wrapper        # once, to create ./gradlew
./gradlew assembleDebug
./gradlew installDebug # to a connected device/emulator
```

## Pointing at the signaling server

The signaling URL is a `buildConfigField` in `app/build.gradle.kts`
(`SIGNAL_URL`). Defaults to `ws://10.0.2.2:8081/ws` — `10.0.2.2` is the host
machine from the Android emulator, matching a locally-run `cmd/server`. For a
real device use your machine's LAN IP, and for a TLS deployment use
`wss://yourdomain/ws`.

> Cleartext `ws://` is allowed via `usesCleartextTraffic` for local prototyping.
> Production should use `wss://` behind your TLS reverse proxy.
