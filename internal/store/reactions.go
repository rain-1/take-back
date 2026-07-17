package store

import "strings"

// Reactions: emoji attached to a message by a user. DM and group messages have
// separate id spaces, so every reaction is scoped by which it belongs to.

func init() {
	schemaExtras = append(schemaExtras, `
CREATE TABLE IF NOT EXISTS reactions (
  scope      TEXT NOT NULL,           -- 'dm' | 'group'
  message_id INTEGER NOT NULL,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji      TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (scope, message_id, user_id, emoji)
);
CREATE INDEX IF NOT EXISTS idx_reactions_msg ON reactions (scope, message_id);`)
}

// Reaction is one person's emoji on one message.
type Reaction struct {
	UserID int64  `json:"userId"`
	Nick   string `json:"nick"`
	Emoji  string `json:"emoji"`
}

// SetReaction adds or removes a reaction and reports the resulting state (true =
// present after the call). Toggling is idempotent, so double-taps are harmless.
func (s *Store) SetReaction(scope string, msgID, userID int64, emoji string, add bool) error {
	if add {
		_, err := s.db.Exec(
			`INSERT OR IGNORE INTO reactions (scope, message_id, user_id, emoji, created_at)
			 VALUES (?, ?, ?, ?, strftime('%s','now'))`,
			scope, msgID, userID, emoji)
		return err
	}
	_, err := s.db.Exec(
		`DELETE FROM reactions WHERE scope = ? AND message_id = ? AND user_id = ? AND emoji = ?`,
		scope, msgID, userID, emoji)
	return err
}

// ReactionsFor returns the reactions on the given messages, keyed by message id,
// with the reactor's nick joined in. Ordered by creation so aggregation is
// stable (first reactor of an emoji comes first).
func (s *Store) ReactionsFor(scope string, msgIDs []int64) (map[int64][]Reaction, error) {
	out := map[int64][]Reaction{}
	if len(msgIDs) == 0 {
		return out, nil
	}
	// Build an IN (?, ?, …) list; ids are ours, not user input.
	place := make([]string, len(msgIDs))
	args := make([]any, 0, len(msgIDs)+1)
	args = append(args, scope)
	for i, id := range msgIDs {
		place[i] = "?"
		args = append(args, id)
	}
	rows, err := s.db.Query(
		`SELECT r.message_id, r.user_id, u.nick, r.emoji
		   FROM reactions r JOIN users u ON u.id = r.user_id
		  WHERE r.scope = ? AND r.message_id IN (`+strings.Join(place, ",")+`)
		  ORDER BY r.created_at`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var msgID int64
		var rc Reaction
		if err := rows.Scan(&msgID, &rc.UserID, &rc.Nick, &rc.Emoji); err != nil {
			return nil, err
		}
		out[msgID] = append(out[msgID], rc)
	}
	return out, rows.Err()
}

// GroupOfMessage returns the group a group-message belongs to.
func (s *Store) GroupOfMessage(msgID int64) (int64, error) {
	var gid int64
	err := s.db.QueryRow(`SELECT group_id FROM group_messages WHERE id = ?`, msgID).Scan(&gid)
	return gid, err
}

// DMParticipants returns the sender and recipient of a direct message.
func (s *Store) DMParticipants(msgID int64) (sender, recipient int64, err error) {
	err = s.db.QueryRow(`SELECT sender_id, recipient_id FROM messages WHERE id = ?`, msgID).
		Scan(&sender, &recipient)
	return
}
