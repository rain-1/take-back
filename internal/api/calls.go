package api

import (
	"encoding/json"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rain1/take-back/internal/presence"
	"github.com/rain1/take-back/internal/store"
)

// A call announced in a conversation ("📞 call:CODE") is more than a room code:
// it has someone waiting in it, or it was missed, declined, or is over. The
// rules, as River asked for them:
//
//   - no ringing and no timer: a call stays live while the caller sits in it
//   - it is MISSED only if the caller leaves before anyone else joins
//   - it is ENDED when the last person leaves, and then its link stops working
//   - DECLINED is explicit, and the caller is told
//
// Occupancy comes from the signaling server (see voice.go), so "someone is
// waiting" means a socket is really open in that room.
var callRe = regexp.MustCompile(`^📞 call:([A-Z0-9]{4,8})$`)

// emptyGrace is how long a room may sit empty before the call is wound up.
// Signaling drops and comes back on a flaky connection; ending a call the
// instant the room empties would turn a blip into "that call has ended".
// TB_CALL_GRACE_MS shortens it for tests.
var emptyGrace = func() time.Duration {
	if ms, err := strconv.Atoi(os.Getenv("TB_CALL_GRACE_MS")); err == nil && ms > 0 {
		return time.Duration(ms) * time.Millisecond
	}
	return 20 * time.Second
}()

// pendingEnds holds the timer winding up each empty call, so a rejoin can stop
// it. Keyed by call code.
var pendingEnds struct {
	sync.Mutex
	timers map[string]*time.Timer
}

// CallCodeIn returns the call code a message announces, or "".
func CallCodeIn(body string) string {
	m := callRe.FindStringSubmatch(strings.TrimSpace(body))
	if m == nil {
		return ""
	}
	return m[1]
}

// noteCall records a call a message just announced and tells the other side.
// scope is "dm" or "group"; target is the other person or the group.
func (a *API) noteCall(body string, caller *store.User, scope string, target int64) {
	code := CallCodeIn(body)
	if code == "" {
		return
	}
	var peerID, groupID int64
	if scope == "dm" {
		peerID = target
	} else {
		groupID = target
	}
	call, err := a.Store.CreateCall(code, scope, caller.ID, peerID, groupID)
	if err != nil {
		return
	}
	a.notifyCall(call, presence.Event{Type: "call_incoming"}, caller.ID)
}

// callAudience is everyone who should hear about a call: both sides of a DM,
// or the group's members.
func (a *API) callAudience(call *store.Call) []int64 {
	if call.Scope == "dm" {
		return []int64{call.CallerID, call.PeerID}
	}
	ids, err := a.Store.GroupMemberIDs(call.GroupID)
	if err != nil {
		return nil
	}
	return ids
}

// callView is a call prepared for clients: the record, who is in it now, and
// the caller's name.
type callView struct {
	store.Call
	CallerNick   string  `json:"callerNick,omitempty"`
	Participants []int64 `json:"participants"`
	Here         int     `json:"here"`
}

func (a *API) viewCall(call *store.Call) callView {
	ids, total := a.Presence.Participants(call.Code)
	v := callView{Call: *call, Participants: ids, Here: total}
	if u, err := a.Store.UserByID(call.CallerID); err == nil {
		v.CallerNick = u.Nick
	}
	return v
}

// notifyCall pushes a call's current state to its audience (except `except`).
func (a *API) notifyCall(call *store.Call, ev presence.Event, except int64) {
	if ev.Type == "" {
		ev.Type = "call_state"
	}
	raw, err := json.Marshal(a.viewCall(call))
	if err != nil {
		return
	}
	ev.Message = raw
	for _, id := range a.callAudience(call) {
		if id != except && id != 0 {
			a.Presence.NotifyUser(id, ev)
		}
	}
}

func (a *API) broadcastCall(code string) {
	call, err := a.Store.CallByCode(code)
	if err != nil {
		return
	}
	a.notifyCall(call, presence.Event{}, 0)
}

// callJoined is called as a socket enters a call's room.
func (a *API) callJoined(t *VoiceTicket) {
	cancelEnd(t.Code)
	// Someone other than the caller arrived: the call is answered, and stays
	// that way even if they all leave later (it wasn't missed).
	if t.UserID != 0 && t.Call != nil && t.UserID != t.Call.CallerID {
		if answered, err := a.Store.AnswerCall(t.Code); err == nil && answered {
			a.broadcastCall(t.Code)
			return
		}
	}
	a.broadcastCall(t.Code)
}

// callLeft is called as a socket leaves. The call is wound up once the room has
// stayed empty for a moment.
func (a *API) callLeft(userID int64, code string) {
	call, err := a.Store.CallByCode(code)
	if err != nil {
		return // not a call anyone announced
	}
	if _, total := a.Presence.Participants(code); total > 0 {
		a.broadcastCall(code) // someone left; others are still in it
		return
	}
	if !call.Live() {
		return
	}
	a.broadcastCall(code)
	scheduleEnd(code, func() {
		if _, total := a.Presence.Participants(code); total > 0 {
			return // they came back
		}
		current, err := a.Store.CallByCode(code)
		if err != nil || !current.Live() {
			return
		}
		outcome := store.CallEnded
		if current.Answered == 0 {
			// Nobody ever joined: the caller gave up.
			outcome = store.CallMissed
		}
		if ended, err := a.Store.EndCall(code, outcome); err == nil && ended {
			a.broadcastCall(code)
			a.Presence.DropCall(code)
		}
	})
}

func scheduleEnd(code string, fn func()) {
	pendingEnds.Lock()
	defer pendingEnds.Unlock()
	if pendingEnds.timers == nil {
		pendingEnds.timers = map[string]*time.Timer{}
	}
	if t := pendingEnds.timers[code]; t != nil {
		t.Stop()
	}
	pendingEnds.timers[code] = time.AfterFunc(emptyGrace, func() {
		pendingEnds.Lock()
		delete(pendingEnds.timers, code)
		pendingEnds.Unlock()
		fn()
	})
}

func cancelEnd(code string) {
	pendingEnds.Lock()
	defer pendingEnds.Unlock()
	if t := pendingEnds.timers[code]; t != nil {
		t.Stop()
		delete(pendingEnds.timers, code)
	}
}

// ---- routes ----

func (a *API) callRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/calls", a.auth(a.handleCalls))               // GET ?codes=A,B
	mux.HandleFunc("/api/calls/decline", a.auth(a.handleCallDecline)) // POST {code}
}

// handleCalls reports what became of the calls a conversation mentions.
func (a *API) handleCalls(w http.ResponseWriter, r *http.Request, user *store.User) {
	codes := strings.Split(r.URL.Query().Get("codes"), ",")
	clean := make([]string, 0, len(codes))
	for _, c := range codes {
		if c = strings.TrimSpace(c); c != "" && len(c) <= 16 {
			clean = append(clean, c)
		}
		if len(clean) >= 50 {
			break
		}
	}
	calls, err := a.Store.CallsByCodes(clean)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "couldn't read those calls")
		return
	}
	out := make([]callView, 0, len(calls))
	for i := range calls {
		// Only the people a call was for may see who is in it.
		if !inList(a.callAudience(&calls[i]), user.ID) {
			continue
		}
		out = append(out, a.viewCall(&calls[i]))
	}
	writeJSON(w, http.StatusOK, out)
}

// handleCallDecline turns down a call, which tells the caller rather than
// leaving them waiting.
func (a *API) handleCallDecline(w http.ResponseWriter, r *http.Request, user *store.User) {
	var body struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &body) {
		return
	}
	call, err := a.Store.CallByCode(body.Code)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no such call")
		return
	}
	if !inList(a.callAudience(call), user.ID) {
		writeErr(w, http.StatusForbidden, "that call isn't for you")
		return
	}
	if user.ID == call.CallerID {
		writeErr(w, http.StatusBadRequest, "that's your own call")
		return
	}
	if !call.Live() {
		writeJSON(w, http.StatusOK, a.viewCall(call))
		return
	}
	if _, err := a.Store.EndCall(body.Code, store.CallDeclined); err != nil {
		writeErr(w, http.StatusInternalServerError, "couldn't decline")
		return
	}
	// Remember who said no, so the chat can say "river declined".
	if err := a.Store.SetCallDecliner(body.Code, user.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, "couldn't decline")
		return
	}
	updated, err := a.Store.CallByCode(body.Code)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "couldn't decline")
		return
	}
	a.notifyCall(updated, presence.Event{}, 0)
	// Nobody should be left sitting in a call that has been turned down.
	a.Presence.DropCall(body.Code)
	writeJSON(w, http.StatusOK, a.viewCall(updated))
}

func inList(ids []int64, id int64) bool {
	for _, v := range ids {
		if v == id {
			return true
		}
	}
	return false
}
