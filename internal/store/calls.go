package store

import (
	"database/sql"
	"errors"
	"strings"
	"time"
)

// A call is a room on the signaling server that someone announced in a
// conversation ("📞 call:CODE"). The row here is what makes it a CALL rather
// than a bare room code: who started it, who it was for, and how it went.
//
// Without it every call invite looked alike — you couldn't tell whether anyone
// was waiting in it, and a code from last week still worked forever.

// Call outcomes. An empty outcome means the call is still live: ringing until
// someone answers, then answered until everyone leaves.
const (
	CallRinging  = ""
	CallMissed   = "missed"
	CallDeclined = "declined"
	CallEnded    = "ended"
)

var ErrNoSuchCall = errors.New("no such call")

type Call struct {
	Code     string `json:"code"`
	Scope    string `json:"scope"` // dm | group
	CallerID int64  `json:"callerId"`
	PeerID   int64  `json:"peerId,omitempty"`  // the other person, for a DM
	GroupID  int64  `json:"groupId,omitempty"` // the group, for a group call
	Created  int64  `json:"created"`
	Answered int64  `json:"answered,omitempty"`
	Ended    int64  `json:"ended,omitempty"`
	Outcome  string `json:"outcome"`
}

// Live reports whether the call can still be joined.
func (c *Call) Live() bool { return c.Outcome == CallRinging }

// Seconds is how long the call lasted, once it has ended.
func (c *Call) Seconds() int64 {
	if c.Answered == 0 || c.Ended == 0 {
		return 0
	}
	return c.Ended - c.Answered
}

func (s *Store) initCalls() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS calls (
  code        TEXT PRIMARY KEY,
  scope       TEXT    NOT NULL,
  caller_id   INTEGER NOT NULL,
  peer_id     INTEGER NOT NULL DEFAULT 0,
  group_id    INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  answered_at INTEGER NOT NULL DEFAULT 0,
  ended_at    INTEGER NOT NULL DEFAULT 0,
  outcome     TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_calls_created ON calls (created_at);`)
	return err
}

// CreateCall records a newly announced call. A code that somehow repeats
// replaces the old row: the new announcement is the live one.
func (s *Store) CreateCall(code, scope string, callerID, peerID, groupID int64) (*Call, error) {
	now := time.Now().Unix()
	_, err := s.db.Exec(
		`INSERT INTO calls (code, scope, caller_id, peer_id, group_id, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT(code) DO UPDATE SET
		   scope = excluded.scope, caller_id = excluded.caller_id, peer_id = excluded.peer_id,
		   group_id = excluded.group_id, created_at = excluded.created_at,
		   answered_at = 0, ended_at = 0, outcome = ''`,
		code, scope, callerID, peerID, groupID, now)
	if err != nil {
		return nil, err
	}
	return &Call{Code: code, Scope: scope, CallerID: callerID, PeerID: peerID, GroupID: groupID, Created: now}, nil
}

func (s *Store) CallByCode(code string) (*Call, error) {
	var c Call
	err := s.db.QueryRow(
		`SELECT code, scope, caller_id, peer_id, group_id, created_at, answered_at, ended_at, outcome
		 FROM calls WHERE code = ?`, code).
		Scan(&c.Code, &c.Scope, &c.CallerID, &c.PeerID, &c.GroupID, &c.Created, &c.Answered, &c.Ended, &c.Outcome)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoSuchCall
	}
	return &c, err
}

// AnswerCall marks the moment someone other than the caller arrived. It reports
// whether this was the answer (so the ringing can be called off once).
func (s *Store) AnswerCall(code string) (bool, error) {
	now := time.Now().Unix()
	res, err := s.db.Exec(
		`UPDATE calls SET answered_at = ? WHERE code = ? AND answered_at = 0 AND outcome = ''`, now, code)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// EndCall closes a live call with an outcome, and reports whether it did so —
// false means it had already ended (whoever got there first decides).
func (s *Store) EndCall(code, outcome string) (bool, error) {
	res, err := s.db.Exec(
		`UPDATE calls SET ended_at = ?, outcome = ? WHERE code = ? AND outcome = ''`,
		time.Now().Unix(), outcome, code)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// CallsByCodes loads the calls a conversation's messages refer to, so they can
// be drawn as what they became rather than as identical Join buttons.
func (s *Store) CallsByCodes(codes []string) ([]Call, error) {
	if len(codes) == 0 {
		return nil, nil
	}
	args := make([]any, len(codes))
	for i, c := range codes {
		args[i] = c
	}
	rows, err := s.db.Query(
		`SELECT code, scope, caller_id, peer_id, group_id, created_at, answered_at, ended_at, outcome
		 FROM calls WHERE code IN (?`+strings.Repeat(", ?", len(codes)-1)+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Call
	for rows.Next() {
		var c Call
		if err := rows.Scan(&c.Code, &c.Scope, &c.CallerID, &c.PeerID, &c.GroupID,
			&c.Created, &c.Answered, &c.Ended, &c.Outcome); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// LiveCallsFor returns the calls still ringing or running that userID belongs
// to, so a client that has just connected can show them.
func (s *Store) LiveCallsFor(userID int64) ([]Call, error) {
	rows, err := s.db.Query(
		`SELECT code, scope, caller_id, peer_id, group_id, created_at, answered_at, ended_at, outcome
		 FROM calls
		 WHERE outcome = '' AND (
		   caller_id = ? OR peer_id = ? OR
		   group_id IN (SELECT group_id FROM group_members WHERE user_id = ?))
		 ORDER BY created_at DESC LIMIT 20`, userID, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Call
	for rows.Next() {
		var c Call
		if err := rows.Scan(&c.Code, &c.Scope, &c.CallerID, &c.PeerID, &c.GroupID,
			&c.Created, &c.Answered, &c.Ended, &c.Outcome); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
