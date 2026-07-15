# take-back

A chat application with voice and video calls. This first milestone is a
**WebRTC NAT-passthrough prototype**: enter a nickname, host a call to get a
shareable code, and others join that code to connect into a peer-to-peer
audio/video mesh.

## Architecture

Two small Go programs:

- **`cmd/server`** — the signaling server. A WebSocket relay that helps peers
  find each other and exchange SDP/ICE metadata. It never sees media; once
  connected, audio/video flows directly between browsers (using a public STUN
  server to discover NAT-mapped addresses).
- **`cmd/web`** — serves the browser client (single embedded HTML/JS page).

The client runs a **full mesh**: each newcomer creates an offer to every peer
already in the room, so N participants form N·(N−1)/2 direct connections.

## Run

```sh
# terminal 1 — signaling server (default :8081)
go run ./cmd/server

# terminal 2 — web client (default :8080)
go run ./cmd/web
```

Open http://localhost:8080, enter a nickname, and host a call. Share the code;
others open the same URL, enter the code, and join.

### Flags

- `server -addr :8081`
- `web -addr :8080 -signal ws://localhost:8081/ws` — point the client at a
  remotely reachable signaling URL when testing across NATs.

## NAT traversal notes

STUN alone punches through most NATs. For symmetric NATs where no direct path
exists, add a TURN server to `ICE_CONFIG` in `cmd/web/static/index.html`.
Testing real NAT passthrough requires the two browsers to be on different
networks with the signaling server reachable by both.
