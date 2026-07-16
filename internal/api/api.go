// Package api exposes take-back's HTTP JSON API: authentication, friends,
// direct messages, and image uploads. It sits alongside the WebRTC signaling
// server and the presence hub.
package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
	"golang.org/x/crypto/bcrypt"
)

const (
	sessionCookie = "tb_session"
	sessionTTL    = 30 * 24 * time.Hour
)

// API bundles the dependencies the handlers need.
type API struct {
	Store    *store.Store
	Presence *presence.Hub
	Media    *MediaStore
}

// Routes registers all API endpoints on mux under /api/.
func (a *API) Routes(mux *http.ServeMux) {
	mux.HandleFunc("/api/register", a.handleRegister)
	mux.HandleFunc("/api/login", a.handleLogin)
	mux.HandleFunc("/api/logout", a.handleLogout)
	mux.HandleFunc("/api/me", a.auth(a.handleMe))

	mux.HandleFunc("/api/friends", a.auth(a.handleFriends))
	mux.HandleFunc("/api/friends/request", a.auth(a.handleFriendRequest))
	mux.HandleFunc("/api/friends/respond", a.auth(a.handleFriendRespond))
	mux.HandleFunc("/api/friends/remove", a.auth(a.handleFriendRemove))

	mux.HandleFunc("/api/messages", a.auth(a.handleMessages)) // GET list, POST send text
	mux.HandleFunc("/api/messages/image", a.auth(a.handleImageMessage))
	mux.HandleFunc("/api/read", a.auth(a.handleRead)) // mark a conversation read
	mux.HandleFunc("/api/events", a.auth(a.handleEvents)) // presence + message stream (WS)

	a.groupRoutes(mux)

	// Serve uploaded media (originals + thumbnails).
	mux.Handle("/media/", http.StripPrefix("/media/", http.FileServer(http.Dir(a.Media.Dir))))
}

// ---- request/response helpers ----

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

type ctxUserKey struct{}

// auth wraps a handler, requiring a valid session cookie and passing the user.
func (a *API) auth(next func(http.ResponseWriter, *http.Request, *store.User)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(sessionCookie)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "not logged in")
			return
		}
		user, err := a.Store.UserBySession(cookie.Value)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "session expired")
			return
		}
		next(w, r, user)
	}
}

func (a *API) setSession(w http.ResponseWriter, userID int64) error {
	token, err := a.Store.NewSession(userID, sessionTTL)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(sessionTTL.Seconds()),
	})
	return nil
}

// ---- auth handlers ----

type credentials struct {
	Nick     string `json:"nick"`
	Password string `json:"password"`
}

func (a *API) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !allow(w, r, registerLimiter) {
		return
	}
	var c credentials
	if !decode(w, r, &c) {
		return
	}
	c.Nick = strings.TrimSpace(c.Nick)
	if len(c.Nick) < 2 || len(c.Password) < 6 {
		writeErr(w, http.StatusBadRequest, "nick min 2 chars, password min 6")
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(c.Password), bcrypt.DefaultCost)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "hash failed")
		return
	}
	user, err := a.Store.CreateUser(c.Nick, string(hash))
	if err != nil {
		writeErr(w, http.StatusConflict, err.Error())
		return
	}
	if err := a.setSession(w, user.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, "session failed")
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *API) handleLogin(w http.ResponseWriter, r *http.Request) {
	if !allow(w, r, loginLimiter) {
		return
	}
	var c credentials
	if !decode(w, r, &c) {
		return
	}
	user, hash, err := a.Store.UserByNick(strings.TrimSpace(c.Nick))
	if err != nil || bcrypt.CompareHashAndPassword([]byte(hash), []byte(c.Password)) != nil {
		writeErr(w, http.StatusUnauthorized, "wrong nick or password")
		return
	}
	if err := a.setSession(w, user.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, "session failed")
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *API) handleLogout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(sessionCookie); err == nil {
		_ = a.Store.DeleteSession(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleMe(w http.ResponseWriter, _ *http.Request, user *store.User) {
	writeJSON(w, http.StatusOK, user)
}

// ---- friends handlers ----

// friendView is a Friend enriched with live online status and unread count.
type friendView struct {
	store.Friend
	Online bool `json:"online"`
	Unread int  `json:"unread"`
}

func (a *API) handleFriends(w http.ResponseWriter, _ *http.Request, user *store.User) {
	friends, err := a.Store.Friends(user.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	unread, err := a.Store.UnreadDMCounts(user.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	views := make([]friendView, 0, len(friends))
	for _, f := range friends {
		views = append(views, friendView{
			Friend: f,
			Online: a.Presence.Online(f.User.ID),
			Unread: unread[f.User.ID],
		})
	}
	writeJSON(w, http.StatusOK, views)
}

// handleRead marks a conversation read up to a message id.
// POST {kind: "dm"|"group", id: <peer or group id>, lastId: <message id>}
func (a *API) handleRead(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Kind   string `json:"kind"`
		ID     int64  `json:"id"`
		LastID int64  `json:"lastId"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Kind != store.KindDM && body.Kind != store.KindGroup {
		writeErr(w, http.StatusBadRequest, "kind must be dm or group")
		return
	}
	if err := a.Store.MarkRead(user.ID, body.Kind, body.ID, body.LastID); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleFriendRequest(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Nick string `json:"nick"`
	}
	if !decode(w, r, &body) {
		return
	}
	targetNick := strings.TrimSpace(body.Nick)
	err := a.Store.SendRequest(user.ID, targetNick)
	switch {
	case err == nil:
		// Notify the addressee live (drives their tray notification + UI).
		if target, _, e := a.Store.UserByNick(targetNick); e == nil {
			a.Presence.NotifyUser(target.ID, presence.Event{
				Type: "friend_request", UserID: user.ID, Nick: user.Nick,
			})
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	case errors.Is(err, store.ErrNoSuchUser):
		writeErr(w, http.StatusNotFound, "no such user")
	case errors.Is(err, store.ErrSelfFriend), errors.Is(err, store.ErrAlreadyFriend):
		writeErr(w, http.StatusConflict, err.Error())
	default:
		writeErr(w, http.StatusInternalServerError, err.Error())
	}
}

func (a *API) handleFriendRespond(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		UserID int64 `json:"userId"`
		Accept bool  `json:"accept"`
	}
	if !decode(w, r, &body) {
		return
	}
	var err error
	if body.Accept {
		err = a.Store.Accept(user.ID, body.UserID)
	} else {
		err = a.Store.Remove(user.ID, body.UserID) // decline == remove pending edge
	}
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	// Tell the original requester their request was answered so their friends
	// list updates live (previously only visible on reload).
	a.Presence.NotifyUser(body.UserID, presence.Event{Type: "friend_update"})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleFriendRemove(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		UserID int64 `json:"userId"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Store.Remove(user.ID, body.UserID); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	a.Presence.NotifyUser(body.UserID, presence.Event{Type: "friend_update"})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- events (presence + message push) ----

func (a *API) handleEvents(w http.ResponseWriter, r *http.Request, user *store.User) {
	a.Presence.Serve(w, r, user.ID)
}

// ---- small helpers ----

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "POST required")
		return false
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "bad JSON")
		return false
	}
	return true
}

// parseID parses a base-10 int64 query/form value, defaulting to 0.
func parseID(s string) int64 {
	n, _ := strconv.ParseInt(s, 10, 64)
	return n
}
