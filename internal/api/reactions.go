package api

import (
	"encoding/json"
	"net/http"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// reactionGroup is one emoji aggregated across everyone who used it on a message.
type reactionGroup struct {
	Emoji string   `json:"emoji"`
	Count int      `json:"count"`
	Nicks []string `json:"nicks"` // who reacted — powers the hover tooltip
	Mine  bool     `json:"mine"`  // did the requesting user react with this
}

// aggregateReactions turns raw reactions into per-emoji groups for one message,
// preserving first-seen order and flagging the caller's own reactions.
func aggregateReactions(rs []store.Reaction, me int64) []reactionGroup {
	if len(rs) == 0 {
		return nil
	}
	order := []string{}
	byEmoji := map[string]*reactionGroup{}
	for _, r := range rs {
		g := byEmoji[r.Emoji]
		if g == nil {
			g = &reactionGroup{Emoji: r.Emoji}
			byEmoji[r.Emoji] = g
			order = append(order, r.Emoji)
		}
		g.Count++
		g.Nicks = append(g.Nicks, r.Nick)
		if r.UserID == me {
			g.Mine = true
		}
	}
	out := make([]reactionGroup, 0, len(order))
	for _, e := range order {
		out = append(out, *byEmoji[e])
	}
	return out
}

// handleReaction toggles the caller's emoji on a message and fans the change out
// live. POST {scope:"dm"|"group", messageId, emoji, add:bool}.
func (a *API) handleReaction(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Scope     string `json:"scope"`
		MessageID int64  `json:"messageId"`
		Emoji     string `json:"emoji"`
		Add       bool   `json:"add"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Scope != store.KindDM && body.Scope != store.KindGroup {
		writeErr(w, http.StatusBadRequest, "scope must be dm or group")
		return
	}
	// A single glyph or short ZWJ sequence; reject anything that's clearly not
	// an emoji so this can't be used as arbitrary text storage.
	if body.Emoji == "" || len(body.Emoji) > 32 {
		writeErr(w, http.StatusBadRequest, "bad emoji")
		return
	}

	// Authorize: you can only react where you can see the message. For a DM the
	// message must involve you; for a group you must be a member.
	targets, ok := a.reactionAudience(w, body.Scope, body.MessageID, user.ID)
	if !ok {
		return
	}

	if err := a.Store.SetReaction(body.Scope, body.MessageID, user.ID, body.Emoji, body.Add); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	// Push the new aggregate for this message to everyone who can see it.
	rs, _ := a.Store.ReactionsFor(body.Scope, []int64{body.MessageID})
	ev := reactionEvent{
		Scope: body.Scope, MessageID: body.MessageID,
		Reactions: rawReactionsFor(rs[body.MessageID]),
	}
	raw, _ := json.Marshal(ev)
	for _, uid := range targets {
		a.Presence.NotifyUser(uid, presence.Event{Type: "reaction", Message: raw})
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// reactionEvent is the payload pushed on a reaction change. Reactions are the
// raw per-user list; each client aggregates with its own "mine" flag.
type reactionEvent struct {
	Scope     string           `json:"scope"`
	MessageID int64            `json:"messageId"`
	Reactions []store.Reaction `json:"reactions"`
}

func rawReactionsFor(rs []store.Reaction) []store.Reaction {
	if rs == nil {
		return []store.Reaction{}
	}
	return rs
}

// reactionAudience returns the user ids that should be notified of a reaction on
// this message (excluding no one — the reactor's own other sessions want it too),
// and false (after writing an error) if the caller isn't allowed to react.
func (a *API) reactionAudience(w http.ResponseWriter, scope string, msgID, userID int64) ([]int64, bool) {
	if scope == store.KindGroup {
		gid, err := a.Store.GroupOfMessage(msgID)
		if err != nil || !a.Store.IsMember(gid, userID) {
			writeErr(w, http.StatusForbidden, "not a member of this group")
			return nil, false
		}
		ids, _ := a.Store.GroupMemberIDs(gid)
		return ids, true
	}
	// DM: the caller must be sender or recipient; notify both.
	s, rcpt, err := a.Store.DMParticipants(msgID)
	if err != nil || (s != userID && rcpt != userID) {
		writeErr(w, http.StatusForbidden, "not your conversation")
		return nil, false
	}
	return []int64{s, rcpt}, true
}
