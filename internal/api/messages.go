package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// msgView is a Message prepared for the client: raw Markdown body plus ready
// media URLs (empty for text-only messages).
type msgView struct {
	ID          int64  `json:"id"`
	SenderID    int64  `json:"senderId"`
	RecipientID int64  `json:"recipientId"`
	Body        string `json:"body"`
	ImageURL    string `json:"imageUrl,omitempty"`
	ThumbURL    string `json:"thumbUrl,omitempty"`
	Created     int64           `json:"created"`
	EditedAt    int64           `json:"editedAt,omitempty"`
	Reactions   []reactionGroup `json:"reactions,omitempty"`
}

func toView(m store.Message) msgView {
	v := msgView{
		ID: m.ID, SenderID: m.SenderID, RecipientID: m.RecipientID,
		Body: m.Body, Created: m.Created.Unix(), EditedAt: m.EditedAt,
	}
	if m.ImageFile != "" {
		v.ImageURL = "/media/" + m.ImageFile
		v.ThumbURL = "/media/" + m.ThumbFile
	}
	return v
}

// handleMessages: GET ?with=<userId>[&before=<id>] lists a conversation;
// POST {with, body} sends a Markdown text message.
func (a *API) handleMessages(w http.ResponseWriter, r *http.Request, user *store.User) {
	switch r.Method {
	case http.MethodGet:
		other := parseID(r.URL.Query().Get("with"))
		before := parseID(r.URL.Query().Get("before"))
		if !a.requireFriend(w, user.ID, other) {
			return
		}
		msgs, err := a.Store.Conversation(user.ID, other, before, 50)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		views := make([]msgView, 0, len(msgs))
		for _, m := range msgs {
			views = append(views, toView(m))
		}
		a.attachDMReactions(views, user.ID)
		writeJSON(w, http.StatusOK, views)

	case http.MethodPost:
		var body struct {
			With int64  `json:"with"`
			Body string `json:"body"`
		}
		if !decode(w, r, &body) {
			return
		}
		if body.Body == "" {
			writeErr(w, http.StatusBadRequest, "empty message")
			return
		}
		a.storeAndPush(w, store.Message{
			SenderID: user.ID, RecipientID: body.With, Body: body.Body,
		})

	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

// handleImageMessage accepts a multipart upload (fields: with, image, optional
// body caption), thumbnails it, and stores the message.
func (a *API) handleImageMessage(w http.ResponseWriter, r *http.Request, user *store.User) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	if err := r.ParseMultipartForm(16 << 20); err != nil { // 16 MB cap
		writeErr(w, http.StatusBadRequest, "bad upload")
		return
	}
	with := parseID(r.FormValue("with"))
	if !a.requireFriend(w, user.ID, with) {
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
	a.storeAndPush(w, store.Message{
		SenderID: user.ID, RecipientID: with,
		Body:      r.FormValue("body"),
		ImageFile: imageFile, ThumbFile: thumbFile,
	})
}

// storeAndPush persists a message, pushes it to the recipient's events socket,
// and returns the stored view.
func (a *API) storeAndPush(w http.ResponseWriter, m store.Message) {
	saved, err := a.Store.AddMessage(m)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	view := toView(saved)
	if raw, err := json.Marshal(view); err == nil {
		a.Presence.NotifyMessage(saved.RecipientID, raw)
	}
	writeJSON(w, http.StatusOK, view)
}

// handleEditMessage rewrites the body of a message the caller sent.
// POST {id, body} — works for both DMs and group messages via `scope`.
func (a *API) handleEditMessage(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		ID    int64  `json:"id"`
		Scope string `json:"scope"` // "dm" (default) | "group"
		Body  string `json:"body"`
	}
	if !decode(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.Body) == "" {
		writeErr(w, http.StatusBadRequest, "empty message")
		return
	}

	if body.Scope == "group" {
		saved, err := a.Store.EditGroupMessage(user.ID, body.ID, body.Body)
		if err != nil {
			writeEditErr(w, err)
			return
		}
		view := toGroupView(saved)
		if raw, err := json.Marshal(view); err == nil {
			a.notifyGroup(saved.GroupID, presence.Event{Type: "group_message_edited", Message: raw}, user.ID)
		}
		writeJSON(w, http.StatusOK, view)
		return
	}

	saved, err := a.Store.EditMessage(user.ID, body.ID, body.Body)
	if err != nil {
		writeEditErr(w, err)
		return
	}
	view := toView(saved)
	if raw, err := json.Marshal(view); err == nil {
		a.Presence.NotifyMessageEdited(saved.RecipientID, raw)
	}
	writeJSON(w, http.StatusOK, view)
}

func writeEditErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrNotSender):
		writeErr(w, http.StatusForbidden, err.Error())
	case errors.Is(err, sql.ErrNoRows):
		writeErr(w, http.StatusNotFound, "no such message")
	default:
		writeErr(w, http.StatusInternalServerError, err.Error())
	}
}

// attachDMReactions fills each view's Reactions, aggregated for `me`.
func (a *API) attachDMReactions(views []msgView, me int64) {
	ids := make([]int64, len(views))
	for i, v := range views {
		ids[i] = v.ID
	}
	rs, err := a.Store.ReactionsFor(store.KindDM, ids)
	if err != nil {
		return
	}
	for i := range views {
		views[i].Reactions = aggregateReactions(rs[views[i].ID], me)
	}
}

// requireFriend enforces that a DM is between accepted friends.
func (a *API) requireFriend(w http.ResponseWriter, me, other int64) bool {
	if other == 0 || !a.Store.AreFriends(me, other) {
		writeErr(w, http.StatusForbidden, "you can only message friends")
		return false
	}
	return true
}
