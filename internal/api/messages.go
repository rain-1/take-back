package api

import (
	"encoding/json"
	"net/http"

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
	Created     int64  `json:"created"`
}

func toView(m store.Message) msgView {
	v := msgView{
		ID: m.ID, SenderID: m.SenderID, RecipientID: m.RecipientID,
		Body: m.Body, Created: m.Created.Unix(),
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

// requireFriend enforces that a DM is between accepted friends.
func (a *API) requireFriend(w http.ResponseWriter, me, other int64) bool {
	if other == 0 || !a.Store.AreFriends(me, other) {
		writeErr(w, http.StatusForbidden, "you can only message friends")
		return false
	}
	return true
}
