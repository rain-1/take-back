package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// msgView is a Message prepared for the client: raw Markdown body plus ready
// media URLs (empty for text-only messages).
//
// ImageURL is only set for images. Clients that predate non-image attachments
// keyed entirely off it, so leaving it empty for a video or a PDF makes them
// show a plain (if bare) message rather than a broken picture. Current clients
// use MediaURL + MediaKind.
type msgView struct {
	ID          int64           `json:"id"`
	SenderID    int64           `json:"senderId"`
	RecipientID int64           `json:"recipientId"`
	Body        string          `json:"body"`
	ImageURL    string          `json:"imageUrl,omitempty"`
	ThumbURL    string          `json:"thumbUrl,omitempty"`
	MediaURL    string          `json:"mediaUrl,omitempty"`
	MediaKind   string          `json:"mediaKind,omitempty"`
	MediaName   string          `json:"mediaName,omitempty"`
	MediaSize   int64           `json:"mediaSize,omitempty"`
	Created     int64           `json:"created"`
	EditedAt    int64           `json:"editedAt,omitempty"`
	Reactions   []reactionGroup `json:"reactions,omitempty"`
	ReplyTo     int64           `json:"replyTo,omitempty"`
	ReplySender int64           `json:"replySender,omitempty"`
	ReplyBody   string          `json:"replyBody,omitempty"`
}

func toView(m store.Message) msgView {
	v := msgView{
		ID: m.ID, SenderID: m.SenderID, RecipientID: m.RecipientID,
		Body: m.Body, Created: m.Created.Unix(), EditedAt: m.EditedAt,
		ReplyTo: m.ReplyTo, ReplySender: m.ReplySender, ReplyBody: m.ReplyBody,
	}
	v.MediaURL, v.ImageURL, v.ThumbURL = mediaURLs(m.ImageFile, m.ThumbFile, m.MediaKind, m.MediaName)
	v.MediaKind, v.MediaName, v.MediaSize = m.MediaKind, m.MediaName, m.MediaSize
	return v
}

// mediaURLs turns stored filenames into servable URLs. Non-inline kinds carry
// ?name= so a download lands under the sender's original filename rather than
// the random storage key (see serveMedia).
func mediaURLs(file, thumb, kind, name string) (mediaURL, imageURL, thumbURL string) {
	if file == "" {
		return "", "", ""
	}
	mediaURL = "/media/" + file
	if kind != KindImage && name != "" {
		mediaURL += "?name=" + url.QueryEscape(name)
	}
	if kind == KindImage {
		imageURL = "/media/" + file
		thumbURL = "/media/" + thumb
	}
	return mediaURL, imageURL, thumbURL
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
			With    int64  `json:"with"`
			Body    string `json:"body"`
			ReplyTo int64  `json:"replyTo"`
		}
		if !decode(w, r, &body) {
			return
		}
		if body.Body == "" {
			writeErr(w, http.StatusBadRequest, "empty message")
			return
		}
		// The GET and attachment paths have always required an accepted
		// friendship; this one didn't, so any account could message — and push a
		// live notification to — any user id it cared to guess.
		if !a.requireFriend(w, user.ID, body.With) {
			return
		}
		a.storeAndPush(w, store.Message{
			SenderID: user.ID, RecipientID: body.With, Body: body.Body, ReplyTo: body.ReplyTo,
		})

	default:
		writeErr(w, http.StatusMethodNotAllowed, "GET or POST")
	}
}

// handleMediaMessage accepts a multipart attachment upload (fields: with, file
// — or `image` from older clients — plus an optional body caption) and stores
// the message. Images are thumbnailed; anything else is stored as-is and
// rendered by kind.
//
// Serves both /api/messages/media and the original /api/messages/image, so an
// older client posting a photo keeps working unchanged.
func (a *API) handleMediaMessage(w http.ResponseWriter, r *http.Request, user *store.User) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return
	}
	// Bound the whole body before parsing: ParseMultipartForm spools past its
	// in-memory budget to disk, so without this an oversized request is written
	// out in full before anything gets to reject it.
	r.Body = http.MaxBytesReader(w, r.Body, MaxUploadBytes+multipartOverhead)
	// Keep only a modest slice in memory; the rest spills to disk, which is what
	// makes a large video upload survivable.
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		writeErr(w, http.StatusBadRequest, "bad upload")
		return
	}
	with := parseID(r.FormValue("with"))
	if !a.requireFriend(w, user.ID, with) {
		return
	}
	up, ok := a.readUpload(w, r)
	if !ok {
		return
	}
	a.storeAndPush(w, store.Message{
		SenderID: user.ID, RecipientID: with,
		Body:      r.FormValue("body"),
		ImageFile: up.File, ThumbFile: up.Thumb,
		MediaKind: up.Kind, MediaName: up.Name, MediaSize: up.Size,
	})
}

// readUpload pulls the attachment out of a parsed multipart form and stores it.
// It accepts either the current `file` field or the legacy `image` one.
func (a *API) readUpload(w http.ResponseWriter, r *http.Request) (Upload, bool) {
	f, hdr, err := r.FormFile("file")
	if err != nil {
		if f, hdr, err = r.FormFile("image"); err != nil {
			writeErr(w, http.StatusBadRequest, "missing file field")
			return Upload{}, false
		}
	}
	defer f.Close()

	name := r.FormValue("name")
	if name == "" && hdr != nil {
		name = hdr.Filename
	}
	up, err := a.Media.SaveUpload(f, name)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return Upload{}, false
	}
	return up, true
}

// storeAndPush persists a message, pushes it to the recipient's events socket,
// and returns the stored view.
func (a *API) storeAndPush(w http.ResponseWriter, m store.Message) {
	saved, err := a.Store.AddMessage(m)
	if errors.Is(err, store.ErrReplyOutOfScope) {
		// A caller-supplied reply id pointing outside this conversation: a bad
		// request, not a server fault, and never a stored message.
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
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
