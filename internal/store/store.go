// Package store is the take-back persistence layer: users, sessions, and
// friendships, backed by SQLite (pure-Go driver, no cgo).
package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

var (
	ErrNickTaken     = errors.New("nickname already taken")
	ErrNoSuchUser    = errors.New("no such user")
	ErrNotFriends    = errors.New("not friends")
	ErrSelfFriend    = errors.New("cannot friend yourself")
	ErrAlreadyFriend = errors.New("already friends or request pending")
)

// User is a registered account. PassHash is never serialized to clients.
type User struct {
	ID      int64     `json:"id"`
	Nick    string    `json:"nick"`
	Created time.Time `json:"created"`
}

// Friendship status values.
const (
	StatusPending  = "pending"
	StatusAccepted = "accepted"
)

// Friend is another user plus the relationship's status from the querying
// user's point of view.
type Friend struct {
	User      User   `json:"user"`
	Status    string `json:"status"`   // pending | accepted
	Direction string `json:"direction"` // incoming | outgoing (meaningful when pending)
}

// Store wraps the database handle and its prepared schema.
type Store struct {
	db *sql.DB
}

// Open opens (creating if needed) the SQLite database at path and applies the
// schema. Use ":memory:" for tests.
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	// SQLite handles one writer at a time; keep the pool small.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		return nil, fmt.Errorf("apply schema: %w", err)
	}
	for _, extra := range schemaExtras {
		if _, err := db.Exec(extra); err != nil {
			return nil, fmt.Errorf("apply schema extra: %w", err)
		}
	}
	return &Store{db: db}, nil
}

// schemaExtras holds DDL contributed by other files in this package (e.g.
// messages.go) via init(); Open applies them after the base schema.
var schemaExtras []string

func (s *Store) Close() error { return s.db.Close() }

const schema = `
CREATE TABLE IF NOT EXISTS users (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  nick       TEXT UNIQUE NOT NULL COLLATE NOCASE,
  pass_hash  TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS friendships (
  requester_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  addressee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status       TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (requester_id, addressee_id)
);
`

// ---- Users ----

// CreateUser inserts a new account. passHash must already be hashed.
func (s *Store) CreateUser(nick, passHash string) (*User, error) {
	res, err := s.db.Exec(
		`INSERT INTO users (nick, pass_hash, created_at) VALUES (?, ?, ?)`,
		nick, passHash, time.Now().Unix(),
	)
	if err != nil {
		// UNIQUE violation on nick.
		return nil, ErrNickTaken
	}
	id, _ := res.LastInsertId()
	return &User{ID: id, Nick: nick, Created: time.Now()}, nil
}

// UserByNick returns the user and its stored password hash.
func (s *Store) UserByNick(nick string) (*User, string, error) {
	row := s.db.QueryRow(
		`SELECT id, nick, pass_hash, created_at FROM users WHERE nick = ? COLLATE NOCASE`, nick)
	return scanUserWithHash(row)
}

// UserByID looks up a user by primary key.
func (s *Store) UserByID(id int64) (*User, error) {
	row := s.db.QueryRow(`SELECT id, nick, pass_hash, created_at FROM users WHERE id = ?`, id)
	u, _, err := scanUserWithHash(row)
	return u, err
}

func scanUserWithHash(row *sql.Row) (*User, string, error) {
	var u User
	var hash string
	var created int64
	if err := row.Scan(&u.ID, &u.Nick, &hash, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, "", ErrNoSuchUser
		}
		return nil, "", err
	}
	u.Created = time.Unix(created, 0)
	return &u, hash, nil
}

// ---- Sessions ----

// NewSession mints a random session token for userID valid for ttl.
func (s *Store) NewSession(userID int64, ttl time.Duration) (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	token := hex.EncodeToString(buf)
	_, err := s.db.Exec(
		`INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)`,
		token, userID, time.Now().Add(ttl).Unix(),
	)
	return token, err
}

// UserBySession resolves a session token to its (unexpired) user.
func (s *Store) UserBySession(token string) (*User, error) {
	row := s.db.QueryRow(
		`SELECT u.id, u.nick, u.pass_hash, u.created_at
		   FROM sessions s JOIN users u ON u.id = s.user_id
		  WHERE s.token = ? AND s.expires_at > ?`,
		token, time.Now().Unix(),
	)
	u, _, err := scanUserWithHash(row)
	return u, err
}

// DeleteSession logs a session out.
func (s *Store) DeleteSession(token string) error {
	_, err := s.db.Exec(`DELETE FROM sessions WHERE token = ?`, token)
	return err
}

// ---- Friendships ----

// SendRequest records a pending friend request from → to (by nick).
func (s *Store) SendRequest(fromID int64, toNick string) error {
	to, _, err := s.UserByNick(toNick)
	if err != nil {
		return err
	}
	if to.ID == fromID {
		return ErrSelfFriend
	}
	// Already related in either direction?
	if _, st, err := s.edge(fromID, to.ID); err == nil {
		_ = st
		return ErrAlreadyFriend
	}
	_, err = s.db.Exec(
		`INSERT INTO friendships (requester_id, addressee_id, status, created_at)
		 VALUES (?, ?, ?, ?)`,
		fromID, to.ID, StatusPending, time.Now().Unix(),
	)
	return err
}

// Accept turns a pending request (addressed to meID, from otherID) into a
// friendship.
func (s *Store) Accept(meID, otherID int64) error {
	res, err := s.db.Exec(
		`UPDATE friendships SET status = ?
		  WHERE requester_id = ? AND addressee_id = ? AND status = ?`,
		StatusAccepted, otherID, meID, StatusPending,
	)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNoSuchUser
	}
	return nil
}

// Remove deletes any friendship or pending request between the two users.
func (s *Store) Remove(meID, otherID int64) error {
	_, err := s.db.Exec(
		`DELETE FROM friendships
		  WHERE (requester_id = ? AND addressee_id = ?)
		     OR (requester_id = ? AND addressee_id = ?)`,
		meID, otherID, otherID, meID,
	)
	return err
}

// edge returns the direction-normalized relationship between a and b, if any.
// The returned bool reports whether `a` is the requester.
func (s *Store) edge(a, b int64) (aIsRequester bool, status string, err error) {
	row := s.db.QueryRow(
		`SELECT requester_id, status FROM friendships
		  WHERE (requester_id = ? AND addressee_id = ?)
		     OR (requester_id = ? AND addressee_id = ?)`,
		a, b, b, a,
	)
	var requester int64
	if err = row.Scan(&requester, &status); err != nil {
		return false, "", err
	}
	return requester == a, status, nil
}

// AreFriends reports whether a and b have an accepted friendship.
func (s *Store) AreFriends(a, b int64) bool {
	_, status, err := s.edge(a, b)
	return err == nil && status == StatusAccepted
}

// Friends lists everyone related to meID: accepted friends plus pending
// requests (with direction so the UI can offer Accept on incoming ones).
func (s *Store) Friends(meID int64) ([]Friend, error) {
	rows, err := s.db.Query(
		`SELECT u.id, u.nick, u.created_at, f.status, f.requester_id
		   FROM friendships f
		   JOIN users u ON u.id = CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END
		  WHERE f.requester_id = ? OR f.addressee_id = ?
		  ORDER BY u.nick`,
		meID, meID, meID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Friend
	for rows.Next() {
		var f Friend
		var created, requester int64
		if err := rows.Scan(&f.User.ID, &f.User.Nick, &created, &f.Status, &requester); err != nil {
			return nil, err
		}
		f.User.Created = time.Unix(created, 0)
		if requester == meID {
			f.Direction = "outgoing"
		} else {
			f.Direction = "incoming"
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// AcceptedFriendIDs returns the ids of meID's accepted friends — used to decide
// who should be notified of a presence change.
func (s *Store) AcceptedFriendIDs(meID int64) ([]int64, error) {
	rows, err := s.db.Query(
		`SELECT CASE WHEN requester_id = ? THEN addressee_id ELSE requester_id END
		   FROM friendships
		  WHERE status = ? AND (requester_id = ? OR addressee_id = ?)`,
		meID, StatusAccepted, meID, meID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
