package store

import (
	"database/sql"
	"errors"
	"time"
)

func init() {
	// Extend the schema with the messages table. Kept here so message concerns
	// live beside their queries; Open() runs the combined DDL.
	// Attachments started out image-only, so the storage key column is still
	// called image_file. media_kind/media_name/media_size were added when any
	// file became sendable; rows that predate them carry an empty kind and are
	// read back as images (see Message.Kind).
	migrations = append(migrations,
		`ALTER TABLE messages ADD COLUMN media_kind TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE messages ADD COLUMN media_name TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE messages ADD COLUMN media_size INTEGER NOT NULL DEFAULT 0`,
	)
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
//
// MediaKind says how to render the attachment (image | video | audio | file);
// MediaName is the sender's original filename, shown for non-images and used as
// the download name.
type Message struct {
	ID          int64     `json:"id"`
	SenderID    int64     `json:"senderId"`
	RecipientID int64     `json:"recipientId"`
	Body        string    `json:"body"`
	ImageFile   string    `json:"imageFile,omitempty"`
	ThumbFile   string    `json:"thumbFile,omitempty"`
	MediaKind   string    `json:"mediaKind,omitempty"`
	MediaName   string    `json:"mediaName,omitempty"`
	MediaSize   int64     `json:"mediaSize,omitempty"`
	Created     time.Time `json:"created"`
	EditedAt    int64     `json:"editedAt,omitempty"`
	ReplyTo     int64     `json:"replyTo,omitempty"`     // id of the message being replied to
	ReplySender int64     `json:"replySender,omitempty"` // its sender (for the quote)
	ReplyBody   string    `json:"replyBody,omitempty"`   // its body, truncated
}

// AddMessage stores a DM and returns it with its assigned id.
//
// A non-zero ReplyTo must name a message in THIS conversation; see
// replyQuoteDM. Otherwise ErrReplyOutOfScope is returned and nothing is stored.
func (s *Store) AddMessage(m Message) (Message, error) {
	// Resolve the quote BEFORE inserting: the reply id is caller-supplied, and
	// an out-of-scope one must not reach the table at all.
	var replySender int64
	var replyBody string
	if m.ReplyTo != 0 {
		var err error
		replySender, replyBody, err = s.replyQuoteDM(m.ReplyTo, m.SenderID, m.RecipientID)
		if err != nil {
			return Message{}, err
		}
	}

	now := time.Now()
	res, err := s.db.Exec(
		`INSERT INTO messages (sender_id, recipient_id, body, image_file, thumb_file,
		                       media_kind, media_name, media_size, created_at, reply_to)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.SenderID, m.RecipientID, m.Body, m.ImageFile, m.ThumbFile,
		m.MediaKind, m.MediaName, m.MediaSize, now.Unix(), m.ReplyTo,
	)
	if err != nil {
		return Message{}, err
	}
	m.ID, _ = res.LastInsertId()
	m.Created = now
	// The freshly-inserted row only carries reply_to; the quote's sender and
	// body live on the *replied-to* message. The GET conversation path fills
	// these via a JOIN, but the send path returns this struct straight to the
	// sender AND pushes it to the recipient, so carry the quote here too —
	// otherwise a just-sent / live-received reply shows an empty quote until reload.
	m.ReplySender, m.ReplyBody = replySender, replyBody
	return m, nil
}

// replyQuoteDM resolves the sender and truncated body of the message being
// replied to, but ONLY if it belongs to the conversation between a and b.
//
// Reply ids are global row ids chosen by the caller. Resolving one without this
// constraint meant anyone could post a reply carrying any id and read back the
// sender plus the first 80 characters of a stranger's private message — ids are
// sequential, so the whole table was walkable. The quote is the payload, so the
// scope check has to live here rather than at the handler.
func (s *Store) replyQuoteDM(replyTo, a, b int64) (sender int64, body string, err error) {
	err = s.db.QueryRow(
		`SELECT sender_id, body FROM messages
		  WHERE id = ?
		    AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))`,
		replyTo, a, b, b, a,
	).Scan(&sender, &body)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, "", ErrReplyOutOfScope
	}
	if err != nil {
		return 0, "", err
	}
	return sender, truncate(body, 80), nil
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
		`SELECT m.id, m.sender_id, m.recipient_id, m.body, m.image_file, m.thumb_file,
		        m.media_kind, m.media_name, m.media_size,
		        m.created_at, m.edited_at, m.reply_to,
		        COALESCE(rm.sender_id, 0), COALESCE(rm.body, '')
		   FROM messages m
		   -- The join is scoped to this same conversation, not just rm.id: a
		   -- reply_to stored before that was enforced must not leak a stranger's
		   -- message text through the quote.
		   LEFT JOIN messages rm
		          ON rm.id = m.reply_to
		         AND ((rm.sender_id = ? AND rm.recipient_id = ?) OR (rm.sender_id = ? AND rm.recipient_id = ?))
		  WHERE m.id < ?
		    AND ((m.sender_id = ? AND m.recipient_id = ?) OR (m.sender_id = ? AND m.recipient_id = ?))
		  ORDER BY m.id DESC LIMIT ?`,
		meID, otherID, otherID, meID, beforeID, meID, otherID, otherID, meID, limit,
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
		&m.ImageFile, &m.ThumbFile, &m.MediaKind, &m.MediaName, &m.MediaSize,
		&created, &m.EditedAt,
		&m.ReplyTo, &m.ReplySender, &m.ReplyBody); err != nil {
		return Message{}, err
	}
	m.MediaKind = mediaKindOf(m.MediaKind, m.ImageFile)
	m.Created = time.Unix(created, 0)
	m.ReplyBody = truncate(m.ReplyBody, 80)
	return m, nil
}

// mediaKindOf backfills the kind of an attachment stored before media_kind
// existed. Every such row was an image (that was the only attachment type),
// so a non-empty file with no recorded kind reads back as one.
func mediaKindOf(kind, file string) string {
	if kind != "" || file == "" {
		return kind
	}
	return "image"
}
