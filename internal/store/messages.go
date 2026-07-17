package store

import (
	"database/sql"
	"time"
)

func init() {
	// Extend the schema with the messages table. Kept here so message concerns
	// live beside their queries; Open() runs the combined DDL.
	schemaExtras = append(schemaExtras, `
CREATE TABLE IF NOT EXISTS messages (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  sender_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body         TEXT NOT NULL DEFAULT '',
  image_file   TEXT NOT NULL DEFAULT '',
  thumb_file   TEXT NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_pair
  ON messages (sender_id, recipient_id, id);`)
}

// Message is one direct message. Body holds raw Markdown; ImageFile/ThumbFile
// are storage keys (empty when the message is text-only). Clients receive them
// as URLs assembled by the API layer. EditedAt is 0 when never edited.
type Message struct {
	ID          int64     `json:"id"`
	SenderID    int64     `json:"senderId"`
	RecipientID int64     `json:"recipientId"`
	Body        string    `json:"body"`
	ImageFile   string    `json:"imageFile,omitempty"`
	ThumbFile   string    `json:"thumbFile,omitempty"`
	Created     time.Time `json:"created"`
	EditedAt    int64     `json:"editedAt,omitempty"`
}

// AddMessage stores a DM and returns it with its assigned id.
func (s *Store) AddMessage(m Message) (Message, error) {
	now := time.Now()
	res, err := s.db.Exec(
		`INSERT INTO messages (sender_id, recipient_id, body, image_file, thumb_file, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		m.SenderID, m.RecipientID, m.Body, m.ImageFile, m.ThumbFile, now.Unix(),
	)
	if err != nil {
		return Message{}, err
	}
	m.ID, _ = res.LastInsertId()
	m.Created = now
	return m, nil
}

// Conversation returns messages exchanged between the two users, oldest first.
// beforeID pages backwards (0 = most recent page); limit caps the count.
func (s *Store) Conversation(meID, otherID int64, beforeID int64, limit int) ([]Message, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if beforeID <= 0 {
		beforeID = 1 << 62
	}
	rows, err := s.db.Query(
		`SELECT id, sender_id, recipient_id, body, image_file, thumb_file, created_at, edited_at
		   FROM messages
		  WHERE id < ?
		    AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
		  ORDER BY id DESC LIMIT ?`,
		beforeID, meID, otherID, otherID, meID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var msgs []Message
	for rows.Next() {
		m, err := scanMessage(rows)
		if err != nil {
			return nil, err
		}
		msgs = append(msgs, m)
	}
	// Reverse into chronological order for display.
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, rows.Err()
}

func scanMessage(rows *sql.Rows) (Message, error) {
	var m Message
	var created int64
	if err := rows.Scan(&m.ID, &m.SenderID, &m.RecipientID, &m.Body,
		&m.ImageFile, &m.ThumbFile, &created, &m.EditedAt); err != nil {
		return Message{}, err
	}
	m.Created = time.Unix(created, 0)
	return m, nil
}
