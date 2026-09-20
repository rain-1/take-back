// Package presence tracks which users are online and pushes real-time events
// (presence changes and new direct messages) to their connected friends over a
// WebSocket.
package presence

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Keepalive tuning, mirroring the signaling server.
const (
	pongWait   = 60 * time.Second
	pingPeriod = (pongWait * 9) / 10
	writeWait  = 10 * time.Second
	// heartbeatPeriod is how often we send an app-level {"type":"ping"} the
	// browser's JS can actually see (WebSocket ping *frames* are invisible to
	// JS). The client uses it to notice a silently-dropped socket and reconnect,
	// so live messages resume without a manual page reload.
	heartbeatPeriod = 20 * time.Second
)

// FriendLookup returns the accepted-friend ids of a user. The hub uses it to
// decide who should hear about a user's presence change.
type FriendLookup func(userID int64) ([]int64, error)

// Event is a message pushed to a client's events socket.
type Event struct {
	Type      string          `json:"type"` // "presence" | "message" | "hello" | "friend_request" | "group_invite"
	UserID    int64           `json:"userId,omitempty"`
	Nick      string          `json:"nick,omitempty"` // actor's nick (e.g. friend requester, inviter)
	GroupID   int64           `json:"groupId,omitempty"`
	GroupName string          `json:"groupName,omitempty"`
	Online    bool            `json:"online,omitempty"`
	Online0   []int64         `json:"onlineFriends,omitempty"` // sent once on connect
	Message   json.RawMessage `json:"message,omitempty"`
}

// conn is one open events socket for a user (a user may have several).
type conn struct {
	ws      *websocket.Conn
	send    chan Event
	userID  int64
	viewing int64 // server this socket is looking at, 0 for none; guarded by Hub.mu
}

// Hub owns presence state and the set of live event sockets.
type Hub struct {
	mu      sync.Mutex
	conns   map[int64]map[*conn]struct{} // userID -> connections
	friends FriendLookup

	// Server activity (see activity.go).
	epoch      string               // this run of the server; Seq restarts with it
	voice      map[string]voiceSeat // signaling connection id -> seat
	seq        int64
	mayView    func(userID, serverID int64) bool
	onActivity func(serverID int64)
}

func NewHub(friends FriendLookup) *Hub {
	return &Hub{
		conns:   map[int64]map[*conn]struct{}{},
		friends: friends,
		voice:   map[string]voiceSeat{},
		epoch:   newEpoch(),
	}
}

// newEpoch names this run of the server, so clients can tell a restart from an
// out-of-order message.
func newEpoch() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(b[:])
}

// Online reports whether the user currently has at least one live socket.
func (h *Hub) Online(userID int64) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns[userID]) > 0
}

// add registers a connection, returning whether this made the user newly online.
func (h *Hub) add(userID int64, c *conn) (firstConn bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[userID]
	if set == nil {
		set = map[*conn]struct{}{}
		h.conns[userID] = set
	}
	firstConn = len(set) == 0
	set[c] = struct{}{}
	return firstConn
}

// remove drops a connection, returning whether the user went fully offline.
func (h *Hub) remove(userID int64, c *conn) (lastConn bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[userID]
	if set == nil {
		return false
	}
	delete(set, c)
	if len(set) == 0 {
		delete(h.conns, userID)
		return true
	}
	return false
}

// onlineFriends returns which of userID's friends are currently online.
func (h *Hub) onlineFriends(userID int64) []int64 {
	ids, _ := h.friends(userID)
	var online []int64
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, id := range ids {
		if len(h.conns[id]) > 0 {
			online = append(online, id)
		}
	}
	return online
}

// sendTo delivers an event to every live socket of userID (non-blocking).
func (h *Hub) sendTo(userID int64, ev Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.conns[userID] {
		select {
		case c.send <- ev:
		default:
		}
	}
}

// notifyFriends pushes ev to each of userID's friends.
func (h *Hub) notifyFriends(userID int64, ev Event) {
	ids, _ := h.friends(userID)
	for _, id := range ids {
		h.sendTo(id, ev)
	}
}

// NotifyMessage pushes a new-message event to the recipient (used by the API
// when a DM is stored). The message payload is pre-serialized JSON.
func (h *Hub) NotifyMessage(recipientID int64, msg json.RawMessage) {
	h.sendTo(recipientID, Event{Type: "message", Message: msg})
}

// NotifyMessageEdited tells the recipient a DM they already have was edited.
func (h *Hub) NotifyMessageEdited(recipientID int64, msg json.RawMessage) {
	h.sendTo(recipientID, Event{Type: "message_edited", Message: msg})
}

// NotifyUser pushes an arbitrary event to a single user's live sockets (if any).
// Used for out-of-band notifications like incoming friend requests.
func (h *Hub) NotifyUser(userID int64, ev Event) {
	h.sendTo(userID, ev)
}

// eventsReadLimit caps inbound frames on the events socket. Clients only ever
// send pong control frames on it, so anything sizeable is either a bug or an
// attempt to make the server buffer on our behalf.
const eventsReadLimit = 4 << 10

var upgrader = websocket.Upgrader{CheckOrigin: sameOriginOrNoOrigin}

// sameOriginOrNoOrigin decides which handshakes may open the events stream.
//
// This socket is authenticated by the ambient session cookie, so accepting every
// Origin (which is what it used to do) meant any page the victim visited that
// could get the browser to attach that cookie — a sibling subdomain, which is
// same-site for SameSite=Lax — could open the stream and read their private
// messages, invitations and presence live. WebSockets aren't covered by CORS, so
// the Origin check is the only thing standing there.
//
// Requests with no Origin header at all are allowed: those are non-browser
// clients (the `tb` CLI, the Android app), which aren't subject to the ambient
// cookie problem because nothing else is driving them.
func sameOriginOrNoOrigin(r *http.Request) bool {
	return originAllowed(r.Header.Get("Origin"), r.Host)
}

func originAllowed(origin, host string) bool {
	if origin == "" {
		return true // not a browser
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	// Compare hosts, not full URLs: the page is served from the same host as the
	// API (cmd/web proxies /api), and the scheme differs between local dev
	// (http) and production (https).
	return strings.EqualFold(u.Host, host)
}

// Serve upgrades an already-authenticated request to the events WebSocket for
// userID. It marks the user online for the socket's lifetime and streams events
// until the client disconnects.
func (h *Hub) Serve(w http.ResponseWriter, r *http.Request, userID int64) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(eventsReadLimit)
	c := &conn{ws: ws, send: make(chan Event, 32), userID: userID}

	if h.add(userID, c) {
		// Newly online: tell friends.
		h.notifyFriends(userID, Event{Type: "presence", UserID: userID, Online: true})
	}
	// Tell this socket which friends are already online.
	c.send <- Event{Type: "hello", Online0: h.onlineFriends(userID)}

	// Writer goroutine: sends events, and pings so an idle events socket isn't
	// culled by a proxy (Cloudflare drops idle sockets after ~100s, which would
	// silently mark the user offline to their friends).
	go func() {
		ticker := time.NewTicker(pingPeriod)
		heartbeat := time.NewTicker(heartbeatPeriod)
		defer ticker.Stop()
		defer heartbeat.Stop()
		for {
			select {
			case ev, ok := <-c.send:
				ws.SetWriteDeadline(time.Now().Add(writeWait))
				if !ok {
					ws.WriteMessage(websocket.CloseMessage, []byte{})
					return
				}
				if err := ws.WriteJSON(ev); err != nil {
					return
				}
			case <-heartbeat.C:
				// App-level ping the client's JS can observe (see heartbeatPeriod).
				ws.SetWriteDeadline(time.Now().Add(writeWait))
				if err := ws.WriteJSON(Event{Type: "ping"}); err != nil {
					return
				}
			case <-ticker.C:
				ws.SetWriteDeadline(time.Now().Add(writeWait))
				if err := ws.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			}
		}
	}()

	// The read loop detects disconnect (pongs extend the deadline) and takes the
	// one thing a client says on this socket: which server it's viewing.
	ws.SetReadDeadline(time.Now().Add(pongWait))
	ws.SetPongHandler(func(string) error {
		return ws.SetReadDeadline(time.Now().Add(pongWait))
	})
	for {
		_, data, err := ws.ReadMessage()
		if err != nil {
			break
		}
		h.handleClientMessage(c, data)
	}

	h.mu.Lock()
	viewing := c.viewing
	h.mu.Unlock()
	// Unregister before closing the send channel: sendTo holds the hub lock
	// while it writes to every registered socket, and a send on a closed
	// channel panics.
	lastConn := h.remove(userID, c)
	close(c.send)
	ws.Close()
	if viewing != 0 {
		h.activityChanged(viewing)
	}
	if lastConn {
		h.notifyFriends(userID, Event{Type: "presence", UserID: userID, Online: false})
	}
}
