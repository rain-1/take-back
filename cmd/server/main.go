// Command server is the take-back signaling server.
//
// It is a small WebSocket relay that helps WebRTC peers find each other and
// exchange the SDP / ICE metadata needed to punch through NATs. It does not
// touch media itself — once peers are connected, audio and video flow directly
// (or via a public STUN-discovered path) between browsers.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"
	"github.com/rain1/take-back/internal/api"
	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
	"github.com/rain1/take-back/internal/version"
)

// Signal is one signaling message relayed between peers in a room.
//
// The server only ever inspects Type ("hello"/"leave" are synthesized by the
// server; everything else — "offer", "answer", "candidate" — is opaque payload
// forwarded verbatim). From/To carry per-connection peer ids so clients can run
// a full mesh.
type Signal struct {
	Type    string          `json:"type"`
	From    string          `json:"from,omitempty"`
	To      string          `json:"to,omitempty"`
	Nick    string          `json:"nick,omitempty"`
	Peers   []Peer          `json:"peers,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// Peer is the public identity of a connection within a room.
type Peer struct {
	ID   string `json:"id"`
	Nick string `json:"nick"`
}

// client is a single connected browser.
type client struct {
	id   string
	nick string
	room *room
	conn *websocket.Conn
	send chan Signal
}

// room is the set of clients sharing one call id-code.
type room struct {
	id      string
	mu      sync.Mutex
	clients map[string]*client
}

func (r *room) peerList(except string) []Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	peers := make([]Peer, 0, len(r.clients))
	for id, c := range r.clients {
		if id == except {
			continue
		}
		peers = append(peers, Peer{ID: id, Nick: c.nick})
	}
	return peers
}

// broadcast delivers s to every client in the room except `from`.
func (r *room) broadcast(from string, s Signal) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for id, c := range r.clients {
		if id == from {
			continue
		}
		c.trySend(s)
	}
}

// sendTo delivers s to a single client by id. Returns false if absent.
func (r *room) sendTo(id string, s Signal) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.clients[id]
	if !ok {
		return false
	}
	c.trySend(s)
	return true
}

// trySend does a non-blocking send so one stuck client can't wedge the room.
func (c *client) trySend(s Signal) {
	select {
	case c.send <- s:
	default:
		log.Printf("dropping message to slow client %s", c.id)
	}
}

// hub owns all rooms.
type hub struct {
	mu    sync.Mutex
	rooms map[string]*room
}

func newHub() *hub { return &hub{rooms: map[string]*room{}} }

// join adds c to the named room, creating it if needed.
func (h *hub) join(roomID string, c *client) *room {
	h.mu.Lock()
	r, ok := h.rooms[roomID]
	if !ok {
		r = &room{id: roomID, clients: map[string]*client{}}
		h.rooms[roomID] = r
	}
	h.mu.Unlock()

	r.mu.Lock()
	r.clients[c.id] = c
	r.mu.Unlock()
	c.room = r
	return r
}

// leave removes c from its room and garbage-collects empty rooms.
func (h *hub) leave(c *client) {
	r := c.room
	if r == nil {
		return
	}
	r.mu.Lock()
	delete(r.clients, c.id)
	empty := len(r.clients) == 0
	r.mu.Unlock()

	if empty {
		h.mu.Lock()
		delete(h.rooms, r.id)
		h.mu.Unlock()
	}
	r.broadcast(c.id, Signal{Type: "leave", From: c.id})
}

var upgrader = websocket.Upgrader{
	// This is a LAN/prototype tool; accept any origin so the browser served
	// from the web app (a different port) can connect.
	CheckOrigin: func(r *http.Request) bool { return true },
}

var idCounter struct {
	sync.Mutex
	n int
}

func nextID() string {
	idCounter.Lock()
	defer idCounter.Unlock()
	idCounter.n++
	return "peer-" + itoa(idCounter.n)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

func (h *hub) serveWS(w http.ResponseWriter, req *http.Request) {
	roomID := req.URL.Query().Get("room")
	nick := req.URL.Query().Get("nick")
	if roomID == "" {
		http.Error(w, "missing room", http.StatusBadRequest)
		return
	}
	if nick == "" {
		nick = "anon"
	}

	conn, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}

	c := &client{
		id:   nextID(),
		nick: nick,
		conn: conn,
		send: make(chan Signal, 32),
	}
	r := h.join(roomID, c)
	log.Printf("%s (%s) joined room %s", c.id, c.nick, roomID)

	// Tell the newcomer who it is and who's already here. The newcomer is the
	// initiator: it will create offers toward each existing peer.
	c.trySend(Signal{Type: "welcome", To: c.id, Peers: r.peerList(c.id)})
	// Tell the existing peers someone arrived (informational; they wait for the
	// newcomer's offer).
	r.broadcast(c.id, Signal{Type: "hello", From: c.id, Nick: c.nick})

	go c.writePump()
	c.readPump(h)
}

// readPump reads signaling messages from the browser and routes them.
func (c *client) readPump(h *hub) {
	defer func() {
		h.leave(c)
		c.conn.Close()
		close(c.send)
		log.Printf("%s (%s) left room", c.id, c.nick)
	}()

	for {
		var s Signal
		if err := c.conn.ReadJSON(&s); err != nil {
			return
		}
		s.From = c.id // trust the connection, not the client's claim
		if s.To != "" {
			if !c.room.sendTo(s.To, s) {
				log.Printf("%s -> %s: target gone", c.id, s.To)
			}
		} else {
			c.room.broadcast(c.id, s)
		}
	}
}

// writePump ships queued signals to the browser.
func (c *client) writePump() {
	for s := range c.send {
		if err := c.conn.WriteJSON(s); err != nil {
			return
		}
	}
}

func main() {
	addr := flag.String("addr", ":8081", "listen address for the server")
	dbPath := flag.String("db", "takeback.db", "SQLite database path")
	mediaDir := flag.String("media", "media", "directory for uploaded images")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("%s server %s (protocol %d)\n", version.Name, version.Version, version.Protocol)
		return
	}

	// Persistence.
	db, err := store.Open(*dbPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()

	media, err := api.NewMediaStore(*mediaDir)
	if err != nil {
		log.Fatalf("media dir: %v", err)
	}

	// Presence hub is told who each user's friends are so it can route events.
	pres := presence.NewHub(db.AcceptedFriendIDs)

	restAPI := &api.API{Store: db, Presence: pres, Media: media}

	// WebRTC signaling hub (unchanged).
	h := newHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", h.serveWS) // WebRTC signaling
	restAPI.Routes(mux)              // auth, friends, DMs, images, /api/events, /media/
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte("ok"))
	})

	log.Printf("take-back server %s (protocol %d) listening on %s (db=%s, media=%s)",
		version.Version, version.Protocol, *addr, *dbPath, *mediaDir)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}
