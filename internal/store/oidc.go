package store

import (
	"database/sql"
	"errors"
	"time"
)

// Identities from an OpenID Connect provider.
//
// take-back does not hold passwords any more: an account is recognised by the
// `sub` claim the provider puts in its ID token. Everything else in the schema
// still keys off users.id, so linking an existing row to a sub is all that a
// migrated account needs — friendships, messages and groups come along
// untouched.
func init() {
	migrations = append(migrations,
		`ALTER TABLE users ADD COLUMN oidc_sub TEXT NOT NULL DEFAULT ''`,
		// Partial index: every locally-created account has '' here, and '' must
		// be allowed to repeat. A real sub may not.
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_oidc_sub
		   ON users(oidc_sub) WHERE oidc_sub <> ''`,
	)
}

// ErrSubTaken means another account is already linked to that identity.
var ErrSubTaken = errors.New("that identity is already linked to another account")

// UserByOIDCSub finds the account linked to a provider identity.
func (s *Store) UserByOIDCSub(sub string) (*User, error) {
	if sub == "" {
		return nil, ErrNoSuchUser
	}
	row := s.db.QueryRow(
		`SELECT id, nick, pass_hash, created_at, avatar_file FROM users WHERE oidc_sub = ?`, sub)
	u, _, err := scanUserWithHash(row)
	return u, err
}

// LinkOIDCSub attaches a provider identity to an existing account. It refuses
// to move a link: an account that already has a different sub is left alone,
// so a provider that starts handing out new subs (a rebuilt instance, a
// changed sub_mode) can't quietly take over somebody's account.
func (s *Store) LinkOIDCSub(userID int64, sub string) error {
	if sub == "" {
		return errors.New("empty sub")
	}
	res, err := s.db.Exec(
		`UPDATE users SET oidc_sub = ? WHERE id = ? AND oidc_sub = ''`, sub, userID)
	if err != nil {
		return ErrSubTaken // UNIQUE violation on the partial index
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		var existing string
		if err := s.db.QueryRow(`SELECT oidc_sub FROM users WHERE id = ?`, userID).
			Scan(&existing); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNoSuchUser
			}
			return err
		}
		if existing == sub {
			return nil // already linked to this identity: nothing to do
		}
		return ErrSubTaken
	}
	return nil
}

// CreateOIDCUser makes a new account owned by a provider identity. There is no
// password to store, so pass_hash stays empty — and an empty hash can never
// match a bcrypt comparison, which is what keeps these accounts unreachable by
// the old password endpoints.
func (s *Store) CreateOIDCUser(nick, sub string) (*User, error) {
	if sub == "" {
		return nil, errors.New("empty sub")
	}
	now := time.Now()
	res, err := s.db.Exec(
		`INSERT INTO users (nick, pass_hash, created_at, oidc_sub) VALUES (?, '', ?, ?)`,
		nick, now.Unix(), sub,
	)
	if err != nil {
		return nil, ErrNickTaken
	}
	id, _ := res.LastInsertId()
	return &User{ID: id, Nick: nick, Created: now}, nil
}

// UnlinkedUserByNick finds an account that has no provider identity yet. It is
// how a pre-existing take-back account is claimed the first time its owner
// signs in through the provider under the same nick.
func (s *Store) UnlinkedUserByNick(nick string) (*User, error) {
	row := s.db.QueryRow(
		`SELECT id, nick, pass_hash, created_at, avatar_file
		   FROM users WHERE nick = ? COLLATE NOCASE AND oidc_sub = ''`, nick)
	u, _, err := scanUserWithHash(row)
	return u, err
}

// Account is one row of the account list an admin sees.
type Account struct {
	ID   int64
	Nick string
	Sub  string // empty: still a local-password account
}

// AllAccounts lists every account and its provider identity, for the admin
// commands in cmd/server. Ordered by id so the output is stable between runs.
func (s *Store) AllAccounts() ([]Account, error) {
	rows, err := s.db.Query(`SELECT id, nick, oidc_sub FROM users ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Account
	for rows.Next() {
		var a Account
		if err := rows.Scan(&a.ID, &a.Nick, &a.Sub); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// Sessions remember the ID token that created them, purely so logging out can
// hand it back to the provider as an id_token_hint. Without it the provider
// cannot tell which session to end and asks the person to confirm — and if
// they don't, "log out" leaves the provider happy to sign them straight back
// in without asking for anything.
func init() {
	migrations = append(migrations,
		`ALTER TABLE sessions ADD COLUMN id_token TEXT NOT NULL DEFAULT ''`)
}

// StashIDToken records the ID token a session was created from.
func (s *Store) StashIDToken(sessionToken, idToken string) error {
	_, err := s.db.Exec(`UPDATE sessions SET id_token = ? WHERE token = ?`, idToken, sessionToken)
	return err
}

// SessionIDToken returns the ID token a session was created from, if any.
func (s *Store) SessionIDToken(sessionToken string) string {
	var tok string
	_ = s.db.QueryRow(`SELECT id_token FROM sessions WHERE token = ?`, sessionToken).Scan(&tok)
	return tok
}
