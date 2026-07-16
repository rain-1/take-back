package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// groupRoutes registers the group endpoints. Called from Routes.
func (a *API) groupRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/groups", a.auth(a.handleGroups))                    // GET list, POST create
	mux.HandleFunc("/api/groups/members", a.auth(a.handleGroupMembers))      // GET member list
	mux.HandleFunc("/api/groups/add", a.auth(a.handleGroupAdd))              // POST add member
	mux.HandleFunc("/api/groups/leave", a.auth(a.handleGroupLeave))          // POST leave
	mux.HandleFunc("/api/groups/messages", a.auth(a.handleGroupMessages))    // GET list, POST send
	mux.HandleFunc("/api/groups/messages/image", a.auth(a.handleGroupImage)) // POST image
}

func (a *API) handleGroups(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		groups, err := a.Store.GroupsForUser(user.ID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		unread, err := a.Store.UnreadGroupCounts(user.ID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		views := make([]groupView, 0, len(groups))
		for _, g := range groups {
			views = append(views, groupView{Group: g, Unread: unread[g.ID]})
		}
		writeJSON(w, http.StatusOK, views)
	case http.MethodPost:
		var body struct {
			Name string `json:"name"`
		}
		if !decode(w, r, &body) {
			return
		}
		name := strings.TrimSpace(body.Name)
		if name == "" {
			writeErr(w, http.StatusBadRequest, "group name required")
			return
		}
		g, err := a.Store.CreateGroup(user.ID, name)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, g)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

// groupView is a Group plus this user's unread count for it.
type groupView struct {
	store.Group
	Unread int `json:"unread"`
}

// memberView is a group member enriched with live presence.
type memberView struct {
	store.User
	Online bool `json:"online"`
	Owner  bool `json:"owner"`
}

func (a *API) handleGroupMembers(w http.ResponseWriter, r *http.Request, user *store.User) {
	gid := parseID(r.URL.Query().Get("group"))
	if !a.requireMember(w, gid, user.ID) {
		return
	}
	g, err := a.Store.GroupByID(gid)
	if err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	members, err := a.Store.GroupMembers(gid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	views := make([]memberView, 0, len(members))
	for _, m := range members {
		views = append(views, memberView{
			User: m, Online: a.Presence.Online(m.ID), Owner: m.ID == g.OwnerID,
		})
	}
	writeJSON(w, http.StatusOK, views)
}

func (a *API) handleGroupAdd(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Group int64  `json:"group"`
		Nick  string `json:"nick"`
	}
	if !decode(w, r, &body) {
		return
	}
	if !a.requireMember(w, body.Group, user.ID) {
		return
	}
	added, err := a.Store.AddMember(body.Group, strings.TrimSpace(body.Nick))
	if err != nil {
		writeErr(w, http.StatusNotFound, "no such user")
		return
	}
	// Let members (incl. the newcomer) know the roster changed.
	a.notifyGroup(body.Group, presence.Event{Type: "group_update", UserID: body.Group}, 0)
	a.Presence.NotifyUser(added.ID, presence.Event{Type: "group_update", UserID: body.Group})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleGroupLeave(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Group int64 `json:"group"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Store.RemoveMember(body.Group, user.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	a.notifyGroup(body.Group, presence.Event{Type: "group_update", UserID: body.Group}, 0)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// groupMsgView is a group message prepared for clients (media as URLs).
type groupMsgView struct {
	ID       int64  `json:"id"`
	GroupID  int64  `json:"groupId"`
	SenderID int64  `json:"senderId"`
	Body     string `json:"body"`
	ImageURL string `json:"imageUrl,omitempty"`
	ThumbURL string `json:"thumbUrl,omitempty"`
	Created  int64  `json:"created"`
}

func toGroupView(m store.GroupMessage) groupMsgView {
	v := groupMsgView{
		ID: m.ID, GroupID: m.GroupID, SenderID: m.SenderID,
		Body: m.Body, Created: m.Created.Unix(),
	}
	if m.ImageFile != "" {
		v.ImageURL = "/media/" + m.ImageFile
		v.ThumbURL = "/media/" + m.ThumbFile
	}
	return v
}

func (a *API) handleGroupMessages(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		gid := parseID(r.URL.Query().Get("group"))
		if !a.requireMember(w, gid, user.ID) {
			return
		}
		msgs, err := a.Store.GroupConversation(gid, parseID(r.URL.Query().Get("before")), 50)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		views := make([]groupMsgView, 0, len(msgs))
		for _, m := range msgs {
			views = append(views, toGroupView(m))
		}
		writeJSON(w, http.StatusOK, views)
	case http.MethodPost:
		var body struct {
			Group int64  `json:"group"`
			Body  string `json:"body"`
		}
		if !decode(w, r, &body) {
			return
		}
		if !a.requireMember(w, body.Group, user.ID) {
			return
		}
		if body.Body == "" {
			writeErr(w, http.StatusBadRequest, "empty message")
			return
		}
		a.storeAndFanout(w, store.GroupMessage{GroupID: body.Group, SenderID: user.ID, Body: body.Body}, user.ID)
	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

func (a *API) handleGroupImage(w http.ResponseWriter, r *http.Request, user *store.User) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	if err := r.ParseMultipartForm(16 << 20); err != nil {
		writeErr(w, http.StatusBadRequest, "bad upload")
		return
	}
	gid := parseID(r.FormValue("group"))
	if !a.requireMember(w, gid, user.ID) {
		return
	}
	file, _, err := r.FormFile("image")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "missing image field")
		return
	}
	defer file.Close()
	imageFile, thumbFile, err := a.Media.SaveImage(file)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	a.storeAndFanout(w, store.GroupMessage{
		GroupID: gid, SenderID: user.ID, Body: r.FormValue("body"),
		ImageFile: imageFile, ThumbFile: thumbFile,
	}, user.ID)
}

// storeAndFanout persists a group message and pushes it to every other member.
func (a *API) storeAndFanout(w http.ResponseWriter, m store.GroupMessage, senderID int64) {
	saved, err := a.Store.AddGroupMessage(m)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	view := toGroupView(saved)
	if raw, err := json.Marshal(view); err == nil {
		a.notifyGroup(saved.GroupID, presence.Event{Type: "group_message", Message: raw}, senderID)
	}
	writeJSON(w, http.StatusOK, view)
}

// notifyGroup pushes ev to all members of the group except `except` (0 = none).
func (a *API) notifyGroup(groupID int64, ev presence.Event, except int64) {
	ids, err := a.Store.GroupMemberIDs(groupID)
	if err != nil {
		return
	}
	for _, id := range ids {
		if id == except {
			continue
		}
		a.Presence.NotifyUser(id, ev)
	}
}

// requireMember enforces group membership for the acting user.
func (a *API) requireMember(w http.ResponseWriter, groupID, userID int64) bool {
	if groupID == 0 || !a.Store.IsMember(groupID, userID) {
		writeErr(w, http.StatusForbidden, "not a member of this group")
		return false
	}
	return true
}
