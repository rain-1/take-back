package store

import (
	"errors"
	"time"
)

func init() {
	migrations = append(migrations,
		`ALTER TABLE messages ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE group_messages ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0`,
	)
}

// Deletion is a soft delete: the row stays, its content is cleared, and
// deleted_at is stamped.
//
// Keeping the row matters because other messages may reply to it — a hard
// delete would leave those quotes pointing at nothing, and re-use the id for a
// future message. Clients render a "message deleted" placeholder in its place.
//
// Any attachment filenames are returned so the caller can remove the files from
// disk: leaving a deleted photo fetchable by URL would make the delete cosmetic.

// DeletedMessage is what a delete produced: enough to tell clients what
// changed, plus the media files that should now be removed from disk.
type DeletedMessage struct {
	ID          int64
	SenderID    int64
	RecipientID int64 // DMs only
	GroupID     int64 // groups only
	Files       []string
}

// DeleteMessage soft-deletes a DM the user sent.
func (s *Store) DeleteMessage(userID, msgID int64) (DeletedMessage, error) {
	var d DeletedMessage
	var imageFile, thumbFile string
	var deletedAt int64
	err := s.db.QueryRow(
		`SELECT id, sender_id, recipient_id, image_file, thumb_file, deleted_at
		   FROM messages WHERE id = ?`, msgID,
	).Scan(&d.ID, &d.SenderID, &d.RecipientID, &imageFile, &thumbFile, &deletedAt)
	if err != nil {
		return DeletedMessage{}, err
	}
	if d.SenderID != userID {
		return DeletedMessage{}, ErrNotSender
	}
	if deletedAt != 0 {
		return DeletedMessage{}, ErrAlreadyDeleted
	}

	if _, err := s.db.Exec(
		`UPDATE messages
		    SET body = '', image_file = '', thumb_file = '',
		        media_kind = '', media_name = '', media_size = 0, deleted_at = ?
		  WHERE id = ?`, time.Now().Unix(), msgID,
	); err != nil {
		return DeletedMessage{}, err
	}
	d.Files = nonEmpty(imageFile, thumbFile)
	return d, nil
}

// DeleteGroupMessage soft-deletes a group message the user sent.
func (s *Store) DeleteGroupMessage(userID, msgID int64) (DeletedMessage, error) {
	var d DeletedMessage
	var imageFile, thumbFile string
	var deletedAt int64
	err := s.db.QueryRow(
		`SELECT id, group_id, sender_id, image_file, thumb_file, deleted_at
		   FROM group_messages WHERE id = ?`, msgID,
	).Scan(&d.ID, &d.GroupID, &d.SenderID, &imageFile, &thumbFile, &deletedAt)
	if err != nil {
		return DeletedMessage{}, err
	}
	if d.SenderID != userID {
		return DeletedMessage{}, ErrNotSender
	}
	if deletedAt != 0 {
		return DeletedMessage{}, ErrAlreadyDeleted
	}

	if _, err := s.db.Exec(
		`UPDATE group_messages
		    SET body = '', image_file = '', thumb_file = '',
		        media_kind = '', media_name = '', media_size = 0, deleted_at = ?
		  WHERE id = ?`, time.Now().Unix(), msgID,
	); err != nil {
		return DeletedMessage{}, err
	}
	d.Files = nonEmpty(imageFile, thumbFile)
	return d, nil
}

// ErrAlreadyDeleted keeps a double-delete from re-notifying everyone.
var ErrAlreadyDeleted = errors.New("message is already deleted")

func nonEmpty(names ...string) []string {
	out := make([]string, 0, len(names))
	for _, n := range names {
		if n != "" {
			out = append(out, n)
		}
	}
	return out
}
