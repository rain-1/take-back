package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// Voice channels are calls on the ordinary signaling server, in a room named by
// the channel's call code. Unlike a call code shared between friends, a voice
// channel belongs to a community, so joining one requires being signed in as a
// member of its server.

// VoiceTicket admits one signaling socket to a voice channel.
type VoiceTicket struct {
	UserID, ServerID, ChannelID int64
	Nick                        string
}

// SignalGate decides whether a signaling socket may join room. A room that isn't
// a voice channel is open to anyone who knows its code, as before: it returns
// (nil, 0, ""). For a voice channel it returns a ticket, or an HTTP status and
// message to refuse with.
func (a *API) SignalGate(r *http.Request, room string) (*VoiceTicket, int, string) {
	ch, err := a.Store.VoiceChannelByCode(room)
	if errors.Is(err, store.ErrNoSuchChannel) {
		return nil, 0, ""
	}
	if err != nil {
		return nil, http.StatusInternalServerError, "couldn't look up the room"
	}
	// The session cookie rides along on this handshake, so a page on another
	// origin must not be able to open it on a member's behalf.
	if !presence.AllowedOrigin(r.Header.Get("Origin"), r.Host) {
		return nil, http.StatusForbidden, "cross-origin voice join refused"
	}
	cookie, err := r.Cookie(sessionCookie)
	if err != nil {
		return nil, http.StatusUnauthorized, "sign in to join a voice channel"
	}
	user, err := a.Store.UserBySession(cookie.Value)
	if err != nil {
		return nil, http.StatusUnauthorized, "session expired"
	}
	if _, err := a.Store.MemberRole(ch.ServerID, user.ID); err != nil {
		return nil, http.StatusForbidden, "you're not a member of this server"
	}
	return &VoiceTicket{UserID: user.ID, ServerID: ch.ServerID, ChannelID: ch.ID, Nick: user.Nick}, 0, ""
}

// VoiceJoined and VoiceLeft are called by the signaling server as a ticketed
// socket (key: its connection id) enters and leaves its room.
func (a *API) VoiceJoined(key string, t *VoiceTicket, kick func()) {
	a.Presence.VoiceJoin(key, t.UserID, t.ServerID, t.ChannelID, kick)
}

func (a *API) VoiceLeft(key string) { a.Presence.VoiceLeave(key) }

// wireActivity connects the presence hub's activity tracking to server
// membership, and broadcasts each change to the server's members.
func (a *API) wireActivity() {
	a.Presence.SetActivityHooks(
		func(userID, serverID int64) bool {
			_, err := a.Store.MemberRole(serverID, userID)
			return err == nil
		},
		func(serverID int64) {
			raw, _ := json.Marshal(a.Presence.Activity(serverID))
			a.notifyServer(serverID, presence.Event{Type: "server_active", Message: raw}, 0)
		},
	)
}

// handleServerActivity returns who is active in a server and who is in each of
// its voice channels, for a client that has just opened it.
func (a *API) handleServerActivity(w http.ResponseWriter, r *http.Request, user *store.User) {
	serverID := parseID(r.URL.Query().Get("server"))
	if !a.requireServerMember(w, serverID, user.ID) {
		return
	}
	writeJSON(w, http.StatusOK, a.Presence.Activity(serverID))
}
