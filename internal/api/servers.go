package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/time/rate"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// Invite codes are the way into a server, so guessing them must be slow. Ten
// base-31 characters is already ~10^15 codes; this makes a sweep hopeless.
var inviteLimiter = newKeyedLimiter(rate.Every(3*time.Second), 20)

const (
	maxServerName  = 48
	maxChannelName = 32
)

func (a *API) serverRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/servers", a.auth(a.handleServers))                     // GET mine, POST create
	mux.HandleFunc("/api/servers/update", a.auth(a.handleServerUpdate))         // POST rename (admin)
	mux.HandleFunc("/api/servers/icon", a.auth(a.handleServerIcon))             // POST multipart (admin)
	mux.HandleFunc("/api/servers/delete", a.auth(a.handleServerDelete))         // POST (owner)
	mux.HandleFunc("/api/servers/leave", a.auth(a.handleServerLeave))           // POST
	mux.HandleFunc("/api/servers/members", a.auth(a.handleServerMembers))       // GET
	mux.HandleFunc("/api/servers/invites", a.auth(a.handleServerInvite))        // POST create (member)
	mux.HandleFunc("/api/servers/invites/revoke", a.auth(a.handleInviteRevoke)) // POST (admin)
	mux.HandleFunc("/api/invites", a.auth(a.handleInvitePreview))               // GET ?code=
	mux.HandleFunc("/api/invites/join", a.auth(a.handleInviteJoin))             // POST {code}
	mux.HandleFunc("/api/servers/channels", a.auth(a.handleChannels))           // GET list, POST create (admin)
	mux.HandleFunc("/api/channels/update", a.auth(a.handleChannelUpdate))       // POST rename (admin)
	mux.HandleFunc("/api/channels/delete", a.auth(a.handleChannelDelete))       // POST (admin)
	mux.HandleFunc("/api/channels/messages", a.auth(a.handleChannelMessages))   // GET list, POST send
	mux.HandleFunc("/api/channels/messages/media", a.auth(a.handleChannelMedia))
	mux.HandleFunc("/api/servers/active", a.auth(a.handleServerActivity)) // GET who's viewing / in voice
	a.wireActivity()
}

// ---- access helpers --------------------------------------------------------------

// serverAccess maps store errors to HTTP responses. It returns false (after
// writing the response) when the caller may not proceed.
func serverAccess(w http.ResponseWriter, err error) bool {
	switch {
	case err == nil:
		return true
	case errors.Is(err, store.ErrNotServerMember):
		writeErr(w, http.StatusForbidden, err.Error())
	case errors.Is(err, store.ErrNotAdmin):
		writeErr(w, http.StatusForbidden, err.Error())
	case errors.Is(err, store.ErrNoSuchServer), errors.Is(err, store.ErrNoSuchChannel):
		writeErr(w, http.StatusNotFound, err.Error())
	case errors.Is(err, store.ErrBadInvite):
		writeErr(w, http.StatusNotFound, err.Error())
	case errors.Is(err, store.ErrOwnerCantLeave):
		writeErr(w, http.StatusConflict, err.Error())
	default:
		writeErr(w, http.StatusInternalServerError, err.Error())
	}
	return false
}

func (a *API) requireServerMember(w http.ResponseWriter, serverID, userID int64) bool {
	_, err := a.Store.MemberRole(serverID, userID)
	return serverAccess(w, err)
}

// channelFor loads a channel and checks the caller belongs to its server.
func (a *API) channelFor(w http.ResponseWriter, channelID, userID int64) (*store.Channel, bool) {
	ch, err := a.Store.ChannelByID(channelID)
	if !serverAccess(w, err) {
		return nil, false
	}
	if !a.requireServerMember(w, ch.ServerID, userID) {
		return nil, false
	}
	return ch, true
}

// notifyServer pushes an event to every member of a server except `except`.
func (a *API) notifyServer(serverID int64, ev presence.Event, except int64) {
	ids, err := a.Store.ServerMemberIDs(serverID)
	if err != nil {
		return
	}
	for _, id := range ids {
		if id != except {
			a.Presence.NotifyUser(id, ev)
		}
	}
}

// serverChanged tells members to refresh a server's details (name, icon,
// channels, membership). The payload is just the id; clients re-fetch.
func (a *API) serverChanged(serverID, except int64) {
	raw, _ := json.Marshal(map[string]int64{"serverId": serverID})
	a.notifyServer(serverID, presence.Event{Type: "server_update", Message: raw}, except)
}

// cleanName trims a name and checks its length in characters, not bytes.
func cleanName(name string, max int) (string, bool) {
	name = strings.TrimSpace(name)
	n := utf8.RuneCountInString(name)
	return name, n >= 1 && n <= max
}

// ---- servers ------------------------------------------------------------------------

func (a *API) handleServers(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		list, err := a.Store.ServersForUser(user.ID)
		if !serverAccess(w, err) {
			return
		}
		if list == nil {
			list = []store.Server{}
		}
		// How many people are sitting in each server's voice channels, so the
		// list can show that something is happening there without opening it.
		type serverView struct {
			store.Server
			VoiceCount int `json:"voiceCount"`
		}
		views := make([]serverView, 0, len(list))
		for _, sv := range list {
			n := 0
			for _, users := range a.Presence.Activity(sv.ID).Voice {
				n += len(users)
			}
			views = append(views, serverView{sv, n})
		}
		writeJSON(w, http.StatusOK, views)
	case http.MethodPost:
		var body struct {
			Name string `json:"name"`
		}
		if !decode(w, r, &body) {
			return
		}
		name, ok := cleanName(body.Name, maxServerName)
		if !ok {
			writeErr(w, http.StatusBadRequest, "server name must be 1–48 characters")
			return
		}
		sv, err := a.Store.CreateServer(user.ID, name)
		if !serverAccess(w, err) {
			return
		}
		writeJSON(w, http.StatusOK, sv)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

func (a *API) handleServerUpdate(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Server int64  `json:"server"`
		Name   string `json:"name"`
	}
	if !decode(w, r, &body) {
		return
	}
	if !serverAccess(w, a.Store.RequireAdmin(body.Server, user.ID)) {
		return
	}
	name, ok := cleanName(body.Name, maxServerName)
	if !ok {
		writeErr(w, http.StatusBadRequest, "server name must be 1–48 characters")
		return
	}
	if !serverAccess(w, a.Store.RenameServer(body.Server, name)) {
		return
	}
	a.serverChanged(body.Server, 0)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// handleServerIcon stores an uploaded icon, thumbnailed like an avatar.
func (a *API) handleServerIcon(w http.ResponseWriter, r *http.Request, user *store.User) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxAvatarBytes)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		writeErr(w, http.StatusBadRequest, "bad upload")
		return
	}
	serverID := parseID(r.FormValue("server"))
	if !serverAccess(w, a.Store.RequireAdmin(serverID, user.ID)) {
		return
	}
	file, _, err := r.FormFile("image")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "missing image field")
		return
	}
	defer file.Close()
	original, thumb, err := a.Media.SaveImage(file)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	// Only the thumbnail is used as the icon; don't keep the full-size original.
	a.Media.Remove(original)
	previous, err := a.Store.SetServerIcon(serverID, thumb)
	if !serverAccess(w, err) {
		return
	}
	a.Media.Remove(previous)
	a.serverChanged(serverID, 0)
	writeJSON(w, http.StatusOK, map[string]string{"iconUrl": "/media/" + thumb})
}

func (a *API) handleServerDelete(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Server int64 `json:"server"`
	}
	if !decode(w, r, &body) {
		return
	}
	sv, err := a.Store.ServerByID(body.Server)
	if !serverAccess(w, err) {
		return
	}
	// Deleting a whole community is the owner's call alone, not any admin's.
	if sv.OwnerID != user.ID {
		writeErr(w, http.StatusForbidden, "only the server's creator can delete it")
		return
	}
	// Collect members before the rows go, so they can all be told.
	members, _ := a.Store.ServerMemberIDs(body.Server)
	files, err := a.Store.DeleteServer(body.Server)
	if !serverAccess(w, err) {
		return
	}
	a.Media.Remove(files...)
	raw, _ := json.Marshal(map[string]any{"serverId": body.Server, "deleted": true})
	for _, id := range members {
		a.Presence.NotifyUser(id, presence.Event{Type: "server_update", Message: raw})
	}
	a.Presence.DropFromServer(body.Server, 0)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleServerLeave(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Server int64 `json:"server"`
	}
	if !decode(w, r, &body) {
		return
	}
	if !serverAccess(w, a.Store.LeaveServer(body.Server, user.ID)) {
		return
	}
	a.Presence.DropFromServer(body.Server, user.ID)
	a.serverChanged(body.Server, user.ID)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleServerMembers(w http.ResponseWriter, r *http.Request, user *store.User) {
	serverID := parseID(r.URL.Query().Get("server"))
	if !a.requireServerMember(w, serverID, user.ID) {
		return
	}
	members, err := a.Store.ServerMembers(serverID)
	if !serverAccess(w, err) {
		return
	}
	type view struct {
		store.ServerMember
		Online bool `json:"online"`
	}
	out := make([]view, 0, len(members))
	for _, m := range members {
		out = append(out, view{m, a.Presence.Online(m.User.ID)})
	}
	writeJSON(w, http.StatusOK, out)
}

// ---- invites ---------------------------------------------------------------------------

func (a *API) handleServerInvite(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Server int64 `json:"server"`
	}
	if !decode(w, r, &body) {
		return
	}
	// Any member may invite, like handing out a link to a Discord server.
	if !a.requireServerMember(w, body.Server, user.ID) {
		return
	}
	code, err := a.Store.CreateInvite(body.Server, user.ID)
	if !serverAccess(w, err) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"code": code, "path": "/?invite=" + code})
}

func (a *API) handleInviteRevoke(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Server int64  `json:"server"`
		Code   string `json:"code"`
	}
	if !decode(w, r, &body) {
		return
	}
	if !serverAccess(w, a.Store.RequireAdmin(body.Server, user.ID)) {
		return
	}
	if !serverAccess(w, a.Store.RevokeInvite(body.Server, body.Code)) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// handleInvitePreview shows what you'd be joining, before you join. Only the
// server's public face: name, icon, member count.
func (a *API) handleInvitePreview(w http.ResponseWriter, r *http.Request, user *store.User) {
	if !allow(w, r, inviteLimiter) {
		return
	}
	sv, err := a.Store.InviteServer(r.URL.Query().Get("code"))
	if !serverAccess(w, err) {
		return
	}
	_, memberErr := a.Store.MemberRole(sv.ID, user.ID)
	writeJSON(w, http.StatusOK, map[string]any{
		"server": map[string]any{"id": sv.ID, "name": sv.Name, "iconUrl": sv.IconURL, "memberCount": sv.MemberCount},
		"member": memberErr == nil,
	})
}

func (a *API) handleInviteJoin(w http.ResponseWriter, r *http.Request, user *store.User) {
	if !allow(w, r, inviteLimiter) {
		return
	}
	var body struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &body) {
		return
	}
	sv, joined, err := a.Store.JoinByInvite(body.Code, user.ID)
	if !serverAccess(w, err) {
		return
	}
	if joined {
		a.serverChanged(sv.ID, user.ID)
	}
	writeJSON(w, http.StatusOK, map[string]any{"server": sv, "joined": joined})
}

// ---- channels ----------------------------------------------------------------------------

func (a *API) handleChannels(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		serverID := parseID(r.URL.Query().Get("server"))
		if !a.requireServerMember(w, serverID, user.ID) {
			return
		}
		chs, err := a.Store.Channels(serverID, user.ID)
		if !serverAccess(w, err) {
			return
		}
		if chs == nil {
			chs = []store.Channel{}
		}
		writeJSON(w, http.StatusOK, chs)
	case http.MethodPost:
		var body struct {
			Server int64  `json:"server"`
			Name   string `json:"name"`
			Kind   string `json:"kind"`
		}
		if !decode(w, r, &body) {
			return
		}
		if !serverAccess(w, a.Store.RequireAdmin(body.Server, user.ID)) {
			return
		}
		if body.Kind != store.ChannelText && body.Kind != store.ChannelVoice {
			writeErr(w, http.StatusBadRequest, "kind must be text or voice")
			return
		}
		name, ok := cleanName(body.Name, maxChannelName)
		if !ok {
			writeErr(w, http.StatusBadRequest, "channel name must be 1–32 characters")
			return
		}
		ch, err := a.Store.CreateChannel(body.Server, name, body.Kind)
		if !serverAccess(w, err) {
			return
		}
		a.serverChanged(body.Server, 0)
		writeJSON(w, http.StatusOK, ch)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

func (a *API) handleChannelUpdate(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Channel int64  `json:"channel"`
		Name    string `json:"name"`
	}
	if !decode(w, r, &body) {
		return
	}
	ch, err := a.Store.ChannelByID(body.Channel)
	if !serverAccess(w, err) || !serverAccess(w, a.Store.RequireAdmin(ch.ServerID, user.ID)) {
		return
	}
	name, ok := cleanName(body.Name, maxChannelName)
	if !ok {
		writeErr(w, http.StatusBadRequest, "channel name must be 1–32 characters")
		return
	}
	if !serverAccess(w, a.Store.RenameChannel(ch.ID, name)) {
		return
	}
	a.serverChanged(ch.ServerID, 0)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleChannelDelete(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Channel int64 `json:"channel"`
	}
	if !decode(w, r, &body) {
		return
	}
	ch, err := a.Store.ChannelByID(body.Channel)
	if !serverAccess(w, err) || !serverAccess(w, a.Store.RequireAdmin(ch.ServerID, user.ID)) {
		return
	}
	files, err := a.Store.DeleteChannel(ch.ID)
	if !serverAccess(w, err) {
		return
	}
	if ch.Kind == store.ChannelVoice {
		a.Presence.DropChannel(ch.ServerID, ch.ID)
	}
	a.Media.Remove(files...)
	a.serverChanged(ch.ServerID, 0)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- channel messages ----------------------------------------------------------------------

// channelMsgView is a channel message prepared for clients.
type channelMsgView struct {
	ID          int64           `json:"id"`
	ChannelID   int64           `json:"channelId"`
	ServerID    int64           `json:"serverId"`
	SenderID    int64           `json:"senderId"`
	Body        string          `json:"body"`
	ImageURL    string          `json:"imageUrl,omitempty"`
	ThumbURL    string          `json:"thumbUrl,omitempty"`
	MediaURL    string          `json:"mediaUrl,omitempty"`
	MediaKind   string          `json:"mediaKind,omitempty"`
	MediaName   string          `json:"mediaName,omitempty"`
	MediaSize   int64           `json:"mediaSize,omitempty"`
	Created     int64           `json:"created"`
	EditedAt    int64           `json:"editedAt,omitempty"`
	DeletedAt   int64           `json:"deletedAt,omitempty"`
	Reactions   []reactionGroup `json:"reactions,omitempty"`
	ReplyTo     int64           `json:"replyTo,omitempty"`
	ReplySender int64           `json:"replySender,omitempty"`
	ReplyBody   string          `json:"replyBody,omitempty"`
}

func toChannelView(m store.ChannelMessage, serverID int64) channelMsgView {
	v := channelMsgView{
		ID: m.ID, ChannelID: m.ChannelID, ServerID: serverID, SenderID: m.SenderID,
		Body: m.Body, Created: m.Created.Unix(), EditedAt: m.EditedAt, DeletedAt: m.DeletedAt,
		ReplyTo: m.ReplyTo, ReplySender: m.ReplySender, ReplyBody: m.ReplyBody,
	}
	v.MediaURL, v.ImageURL, v.ThumbURL = mediaURLs(m.ImageFile, m.ThumbFile, m.MediaKind, m.MediaName)
	v.MediaKind, v.MediaName, v.MediaSize = m.MediaKind, m.MediaName, m.MediaSize
	return v
}

func (a *API) handleChannelMessages(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		ch, ok := a.channelFor(w, parseID(r.URL.Query().Get("channel")), user.ID)
		if !ok {
			return
		}
		if ch.Kind != store.ChannelText {
			writeErr(w, http.StatusBadRequest, "not a text channel")
			return
		}
		msgs, err := a.Store.ChannelConversation(ch.ID, parseID(r.URL.Query().Get("before")), 50)
		if !serverAccess(w, err) {
			return
		}
		views := make([]channelMsgView, 0, len(msgs))
		for _, m := range msgs {
			views = append(views, toChannelView(m, ch.ServerID))
		}
		a.attachChannelReactions(views, user.ID)
		writeJSON(w, http.StatusOK, views)
	case http.MethodPost:
		var body struct {
			Channel int64  `json:"channel"`
			Body    string `json:"body"`
			ReplyTo int64  `json:"replyTo"`
		}
		if !decode(w, r, &body) {
			return
		}
		if strings.TrimSpace(body.Body) == "" {
			writeErr(w, http.StatusBadRequest, "empty message")
			return
		}
		ch, ok := a.channelFor(w, body.Channel, user.ID)
		if !ok {
			return
		}
		a.storeAndFanoutChannel(w, ch, store.ChannelMessage{
			ChannelID: ch.ID, SenderID: user.ID, Body: body.Body, ReplyTo: body.ReplyTo,
		})
	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

func (a *API) handleChannelMedia(w http.ResponseWriter, r *http.Request, user *store.User) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxUploadBytes+multipartOverhead)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		writeErr(w, http.StatusBadRequest, "bad upload")
		return
	}
	ch, ok := a.channelFor(w, parseID(r.FormValue("channel")), user.ID)
	if !ok {
		return
	}
	up, ok := a.readUpload(w, r)
	if !ok {
		return
	}
	a.storeAndFanoutChannel(w, ch, store.ChannelMessage{
		ChannelID: ch.ID, SenderID: user.ID, Body: r.FormValue("body"),
		ImageFile: up.File, ThumbFile: up.Thumb, MediaKind: up.Kind, MediaName: up.Name, MediaSize: up.Size,
	})
}

func (a *API) storeAndFanoutChannel(w http.ResponseWriter, ch *store.Channel, m store.ChannelMessage) {
	if ch.Kind != store.ChannelText {
		writeErr(w, http.StatusBadRequest, "messages can only be sent to text channels")
		return
	}
	saved, err := a.Store.AddChannelMessage(m)
	if errors.Is(err, store.ErrReplyOutOfScope) {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	view := toChannelView(saved, ch.ServerID)
	if raw, err := json.Marshal(view); err == nil {
		a.notifyServer(ch.ServerID, presence.Event{Type: "channel_message", Message: raw}, m.SenderID)
	}
	writeJSON(w, http.StatusOK, view)
}

func (a *API) attachChannelReactions(views []channelMsgView, me int64) {
	ids := make([]int64, len(views))
	for i, v := range views {
		ids[i] = v.ID
	}
	rs, err := a.Store.ReactionsFor(store.KindChannel, ids)
	if err != nil {
		return
	}
	for i := range views {
		views[i].Reactions = aggregateReactions(rs[views[i].ID], me)
	}
}
