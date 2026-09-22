// Command server is the take-back signaling server.
//
// It is a small WebSocket relay that helps WebRTC peers find each other and
// exchange the SDP / ICE metadata needed to punch through NATs. It does not
// touch media itself — once peers are connected, audio and video flow directly
// (or via a public STUN-discovered path) between browsers.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/rain1/take-back/internal/api"
	"github.com/rain1/take-back/internal/auth"
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
	id    string
	nick  string
	voice bool // admitted to a voice channel; its seat is released on leave
	room  *room
	conn  *websocket.Conn
	send  chan Signal
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
	api   *api.API // gates voice-channel rooms; nil in tests
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
	// Signaling carries no ambient credentials — a room is joined by knowing its
	// code, not by a cookie — so an Origin check would buy nothing here and would
	// break the Android client and local dev on a different port.
	CheckOrigin: func(r *http.Request) bool { return true },
}

// Limits on the public signaling socket. It is reachable without a session, so
// every input has to be bounded: without a read limit a single client could make
// the server buffer and decode an arbitrarily large frame.
const (
	signalReadLimit = 256 << 10 // an SDP offer with many ICE candidates is ~tens of KB
	maxRoomLen      = 64
	maxNickLen      = 64
)

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
	// Bound the identifiers before they are stored in the room map and echoed to
	// every other peer: they arrive on an unauthenticated endpoint.
	if len(roomID) > maxRoomLen {
		http.Error(w, "room too long", http.StatusBadRequest)
		return
	}
	// A voice channel's room is for its server's members only, under their own
	// names. Any other room is joined by knowing its code.
	var ticket *api.VoiceTicket
	if h.api != nil {
		t, status, msg := h.api.SignalGate(req, roomID)
		if status != 0 {
			http.Error(w, msg, status)
			return
		}
		ticket = t
	}
	if ticket != nil {
		nick = ticket.Nick
	}
	if nick == "" {
		nick = "anon"
	} else if len(nick) > maxNickLen {
		nick = nick[:maxNickLen]
	}

	conn, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}
	// Cap inbound frames. ReadJSON otherwise buffers whatever the peer sends,
	// which on a public socket is an invitation to exhaust memory.
	conn.SetReadLimit(signalReadLimit)

	c := &client{
		id:   nextID(),
		nick: nick,
		conn: conn,
		send: make(chan Signal, 32),
	}
	r := h.join(roomID, c)
	if ticket != nil {
		c.voice = true
		h.api.VoiceJoined(c.id, ticket, func() { conn.Close() })
	}
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

// WebSocket keepalive. Signaling goes silent for the whole of a call (media is
// peer-to-peer), and an idle socket gets culled by proxies — Cloudflare drops
// them after ~100s. That silently removes the peer from its room, so a peer
// refreshing the page finds nobody to negotiate with. Pings keep it alive; the
// read deadline reaps genuinely dead clients.
const (
	pongWait   = 60 * time.Second
	pingPeriod = (pongWait * 9) / 10
	writeWait  = 10 * time.Second
)

// readPump reads signaling messages from the browser and routes them.
func (c *client) readPump(h *hub) {
	defer func() {
		h.leave(c)
		if c.voice {
			h.api.VoiceLeft(c.id)
		}
		c.conn.Close()
		close(c.send)
		log.Printf("%s (%s) left room", c.id, c.nick)
	}()

	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

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

// writePump ships queued signals to the browser and pings it periodically so
// the connection isn't culled while a call is in progress.
func (c *client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case s, ok := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok { // hub closed the channel
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteJSON(s); err != nil {
				return
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func main() {
	addr := flag.String("addr", ":8081", "listen address for the server")
	dbPath := flag.String("db", "takeback.db", "SQLite database path")
	mediaDir := flag.String("media", "media", "directory for uploaded images")
	showVersion := flag.Bool("version", false, "print version and exit")
	openReg := flag.Bool("open-registration", false,
		"allow anyone to create an account via POST /api/register (default: closed)")
	// Account administration, for a server whose accounts live in an identity
	// provider: there is no signup form to use, and hand-writing SQL against a
	// live database is how people lose data.
	makeAccount := flag.String("make-account", "",
		"create a provider-managed account with this nick (no password) and exit")
	linkAccount := flag.String("link", "",
		"link an existing account to a provider identity, as nick=subject, and exit")
	listAccounts := flag.Bool("list-accounts", false,
		"print every account and whether it is linked to an identity, then exit")
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

	if *makeAccount != "" || *linkAccount != "" || *listAccounts {
		if err := runAdmin(db, *makeAccount, *linkAccount, *listAccounts); err != nil {
			log.Fatal(err)
		}
		return
	}

	media, err := api.NewMediaStore(*mediaDir)
	if err != nil {
		log.Fatalf("media dir: %v", err)
	}

	// Presence hub is told who each user's friends are so it can route events.
	pres := presence.NewHub(db.AcceptedFriendIDs)

	// Sign-in is delegated to an OpenID Connect provider when one
	// is configured. The secret arrives by environment, not by flag, so it
	// never shows up in `ps` output; see deploy/auth/README.md.
	oidcCfg := auth.Config{
		Backend:      os.Getenv("TB_OIDC_BACKEND"),
		Issuer:       os.Getenv("TB_OIDC_ISSUER"),
		ClientID:     os.Getenv("TB_OIDC_CLIENT_ID"),
		ClientSecret: os.Getenv("TB_OIDC_CLIENT_SECRET"),
		RedirectURL:  os.Getenv("TB_OIDC_REDIRECT_URL"),
	}
	if err := auth.ValidateConfig(oidcCfg); err != nil {
		log.Fatal(err)
	}
	if oidcCfg.Enabled() {
		provider := auth.New(oidcCfg)
		log.Printf("sign-in: OpenID Connect (%s) via %s", provider.Backend(), oidcCfg.Issuer)
		if os.Getenv("TB_AUTH_PASSWORD_FALLBACK") == "1" {
			log.Printf("sign-in: local passwords ALSO still accepted (migration window)")
		}
	} else {
		log.Printf("sign-in: local passwords (no identity provider configured)")
	}

	restAPI := &api.API{
		Store: db, Presence: pres, Media: media,
		OpenRegistration: *openReg,
		OIDC:             auth.New(oidcCfg),
		ClaimByUsername:  os.Getenv("TB_OIDC_CLAIM_BY_USERNAME") == "1",
		PasswordFallback: os.Getenv("TB_AUTH_PASSWORD_FALLBACK") == "1",
	}

	// WebRTC signaling hub (unchanged).
	h := newHub()
	h.api = restAPI

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", h.serveWS) // WebRTC signaling
	restAPI.Routes(mux)              // auth, friends, DMs, images, /api/events, /media/
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte("ok"))
	})

	if *openReg {
		log.Printf("registration is OPEN — anyone who can reach this server can create an account")
	} else {
		log.Printf("registration is CLOSED (start with -open-registration to allow signups)")
	}
	log.Printf("take-back server %s (protocol %d) listening on %s (db=%s, media=%s)",
		version.Version, version.Protocol, *addr, *dbPath, *mediaDir)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}

// runAdmin handles the one-shot account commands. They exist for the migration
// to an identity provider: accounts have to be created and matched up to
// provider identities without a signup form, and an admin who has to write SQL
// by hand against a live database eventually writes the wrong UPDATE.
func runAdmin(db *store.Store, makeAccount, link string, list bool) error {
	switch {
	case makeAccount != "":
		u, err := db.CreateUser(makeAccount, "")
		if err != nil {
			return fmt.Errorf("create %q: %w", makeAccount, err)
		}
		fmt.Printf("created %s (id %d), with no password — it can only be used through the identity provider\n",
			u.Nick, u.ID)
	case link != "":
		nick, sub, ok := strings.Cut(link, "=")
		if !ok || nick == "" || sub == "" {
			return errors.New("use -link nick=subject")
		}
		u, _, err := db.UserByNick(nick)
		if err != nil {
			return fmt.Errorf("no account %q: %w", nick, err)
		}
		if err := db.LinkOIDCSub(u.ID, sub); err != nil {
			return fmt.Errorf("link %s: %w", nick, err)
		}
		fmt.Printf("%s (id %d) is now the account for identity %s\n", u.Nick, u.ID, sub)
	case list:
		accounts, err := db.AllAccounts()
		if err != nil {
			return err
		}
		for _, a := range accounts {
			state := "not linked yet"
			if a.Sub != "" {
				state = "identity " + a.Sub
			}
			fmt.Printf("%-20s id=%-4d %s\n", a.Nick, a.ID, state)
		}
	}
	return nil
}
