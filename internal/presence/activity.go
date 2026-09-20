package presence

import (
	"encoding/json"
	"sort"
)

// Server activity: who is "active" in a server right now. Someone is active
// while one of their event sockets says it is viewing the server, or while they
// sit in one of its voice channels. Viewing is reported by the client over the
// events socket ({"type":"view","serverId":N}); voice seats are reported by the
// signaling server as sockets join and leave a voice channel's room.

// voiceSeat is one signaling connection sitting in a voice channel.
type voiceSeat struct {
	userID, serverID, channelID int64
	kick                        func() // closes the signaling socket
}

// Activity is a snapshot of one server's activity. Seq increases with every
// change across the hub, so a client can drop a snapshot that arrives after a
// newer one — but only within one run of the server: Seq starts again from
// zero when it restarts, which is what Epoch is for. A snapshot from a
// different epoch is always newer, however small its Seq.
type Activity struct {
	ServerID int64             `json:"serverId"`
	Epoch    string            `json:"epoch"`
	Seq      int64             `json:"seq"`
	Active   []int64           `json:"active"`
	Voice    map[int64][]int64 `json:"voice"` // channel id -> user ids
}

// SetActivityHooks connects the hub to the server model. mayView reports
// whether a user belongs to a server (a client can't claim to view one it isn't
// in); changed is called, outside the hub's lock, whenever a server's activity
// may have changed.
func (h *Hub) SetActivityHooks(mayView func(userID, serverID int64) bool, changed func(serverID int64)) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.mayView, h.onActivity = mayView, changed
}

func (h *Hub) activityChanged(serverIDs ...int64) {
	h.mu.Lock()
	h.seq++
	fn := h.onActivity
	h.mu.Unlock()
	if fn == nil {
		return
	}
	seen := map[int64]bool{}
	for _, id := range serverIDs {
		if id != 0 && !seen[id] {
			seen[id] = true
			fn(id)
		}
	}
}

// clientMessage is what a client may send on its events socket.
type clientMessage struct {
	Type     string `json:"type"`
	ServerID int64  `json:"serverId"`
}

func (h *Hub) handleClientMessage(c *conn, data []byte) {
	var m clientMessage
	if json.Unmarshal(data, &m) != nil || m.Type != "view" {
		return
	}
	h.setViewing(c, m.ServerID)
}

func (h *Hub) setViewing(c *conn, serverID int64) {
	h.mu.Lock()
	mayView := h.mayView
	h.mu.Unlock()
	if serverID != 0 && (mayView == nil || !mayView(c.userID, serverID)) {
		serverID = 0
	}
	h.mu.Lock()
	old := c.viewing
	c.viewing = serverID
	h.mu.Unlock()
	if old != serverID {
		h.activityChanged(old, serverID)
	}
}

// VoiceJoin records a signaling connection (key) entering a voice channel.
// kick is how the hub removes it again, e.g. when the channel is deleted.
func (h *Hub) VoiceJoin(key string, userID, serverID, channelID int64, kick func()) {
	h.mu.Lock()
	h.voice[key] = voiceSeat{userID: userID, serverID: serverID, channelID: channelID, kick: kick}
	h.mu.Unlock()
	h.activityChanged(serverID)
}

// VoiceLeave records a signaling connection leaving its voice channel.
func (h *Hub) VoiceLeave(key string) {
	h.mu.Lock()
	seat, ok := h.voice[key]
	delete(h.voice, key)
	h.mu.Unlock()
	if ok {
		h.activityChanged(seat.serverID)
	}
}

// Activity returns who is active in a server and who sits in each voice channel.
func (h *Hub) Activity(serverID int64) Activity {
	h.mu.Lock()
	defer h.mu.Unlock()
	active := map[int64]bool{}
	for userID, set := range h.conns {
		for c := range set {
			if c.viewing == serverID {
				active[userID] = true
			}
		}
	}
	voice := map[int64]map[int64]bool{}
	for _, seat := range h.voice {
		if seat.serverID != serverID {
			continue
		}
		active[seat.userID] = true
		if voice[seat.channelID] == nil {
			voice[seat.channelID] = map[int64]bool{}
		}
		voice[seat.channelID][seat.userID] = true
	}
	out := Activity{ServerID: serverID, Epoch: h.epoch, Seq: h.seq, Active: sortedIDs(active), Voice: map[int64][]int64{}}
	for ch, users := range voice {
		out.Voice[ch] = sortedIDs(users)
	}
	return out
}

func sortedIDs(set map[int64]bool) []int64 {
	ids := make([]int64, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	return ids
}

// DropFromServer ends a user's activity in a server they no longer belong to:
// their sockets stop viewing it and they're disconnected from its voice
// channels. userID 0 means everyone (the server was deleted).
func (h *Hub) DropFromServer(serverID, userID int64) {
	h.mu.Lock()
	for uid, set := range h.conns {
		if userID != 0 && uid != userID {
			continue
		}
		for c := range set {
			if c.viewing == serverID {
				c.viewing = 0
			}
		}
	}
	kicks := h.takeSeats(func(s voiceSeat) bool {
		return s.serverID == serverID && (userID == 0 || s.userID == userID)
	})
	h.mu.Unlock()
	for _, kick := range kicks {
		kick()
	}
	h.activityChanged(serverID)
}

// DropChannel disconnects everyone from a voice channel that was deleted.
func (h *Hub) DropChannel(serverID, channelID int64) {
	h.mu.Lock()
	kicks := h.takeSeats(func(s voiceSeat) bool { return s.channelID == channelID })
	h.mu.Unlock()
	for _, kick := range kicks {
		kick()
	}
	h.activityChanged(serverID)
}

// takeSeats removes matching voice seats and returns their kick functions, to
// be called once the lock is released. Must hold h.mu.
func (h *Hub) takeSeats(match func(voiceSeat) bool) []func() {
	var kicks []func()
	for key, seat := range h.voice {
		if match(seat) {
			delete(h.voice, key)
			if seat.kick != nil {
				kicks = append(kicks, seat.kick)
			}
		}
	}
	return kicks
}

// AllowedOrigin is the events socket's origin rule, for other sockets that
// authenticate with the session cookie.
func AllowedOrigin(originHeader, host string) bool {
	return originAllowed(originHeader, host)
}
