package store

import (
	"database/sql"
	"errors"
	"time"
)

// ErrNotSender is returned when someone tries to edit a message they didn't
// send. Editing is deliberately restricted to the author.
var ErrNotSender = errors.New("you can only edit your own messages")

func init() {
	// `edited_at` is added to the existing message tables. SQLite has no
	// IF NOT EXISTS for ADD COLUMN, so Open tolerates the duplicate-column
	// error on subsequent starts (see migrate below).
	migrations = append(migrations,
		`ALTER TABLE messages ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE group_messages ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE messages ADD COLUMN reply_to INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE group_messages ADD COLUMN reply_to INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE users ADD COLUMN avatar_file TEXT NOT NULL DEFAULT ''`,
	)
}

// truncate shortens s to n runes, adding an ellipsis when cut.
func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// EditMessage rewrites the body of a DM the user sent. Only the author may
// edit, and only text — an image message keeps its attachment.
func (s *Store) EditMessage(userID, msgID int64, body string) (Message, error) {
	var m Message
	var created int64
	row := s.db.QueryRow(
		`SELECT id, sender_id, recipient_id, body, image_file, thumb_file, created_at
		   FROM messages WHERE id = ?`, msgID)
	if err := row.Scan(&m.ID, &m.SenderID, &m.RecipientID, &m.Body,
		&m.ImageFile, &m.ThumbFile, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Message{}, sql.ErrNoRows
		}
		return Message{}, err
	}
	if m.SenderID != userID {
		return Message{}, ErrNotSender
	}

	now := time.Now().Unix()
	if _, err := s.db.Exec(
		`UPDATE messages SET body = ?, edited_at = ? WHERE id = ?`, body, now, msgID,
	); err != nil {
		return Message{}, err
	}
	m.Body = body
	m.Created = time.Unix(created, 0)
	m.EditedAt = now
	return m, nil
}

// EditGroupMessage rewrites the body of a group message the user sent.
func (s *Store) EditGroupMessage(userID, msgID int64, body string) (GroupMessage, error) {
	var m GroupMessage
	var created int64
	row := s.db.QueryRow(
		`SELECT id, group_id, sender_id, body, image_file, thumb_file, created_at
		   FROM group_messages WHERE id = ?`, msgID)
	if err := row.Scan(&m.ID, &m.GroupID, &m.SenderID, &m.Body,
		&m.ImageFile, &m.ThumbFile, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return GroupMessage{}, sql.ErrNoRows
		}
		return GroupMessage{}, err
	}
	if m.SenderID != userID {
		return GroupMessage{}, ErrNotSender
	}

	now := time.Now().Unix()
	if _, err := s.db.Exec(
		`UPDATE group_messages SET body = ?, edited_at = ? WHERE id = ?`, body, now, msgID,
	); err != nil {
		return GroupMessage{}, err
	}
	m.Body = body
	m.Created = time.Unix(created, 0)
	m.EditedAt = now
	return m, nil
}
