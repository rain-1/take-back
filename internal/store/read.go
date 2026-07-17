package store

// Read state: per-user "last message id I've seen" for each conversation,
// stored server-side so unread pips agree across web and Android.

func init() {
	schemaExtras = append(schemaExtras, `
CREATE TABLE IF NOT EXISTS read_state (
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind      TEXT NOT NULL,            -- 'dm' (target = peer user) | 'group' (target = group)
  target_id INTEGER NOT NULL,
  last_read INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, kind, target_id)
);`)
}

// Conversation kinds for read_state.
const (
	KindDM    = "dm"
	KindGroup = "group"
)

// MarkRead records that userID has seen up to lastID in a conversation. It only
// ever moves the marker forward, so an out-of-order request can't "unread"
// messages.
func (s *Store) MarkRead(userID int64, kind string, targetID, lastID int64) error {
	_, err := s.db.Exec(
		`INSERT INTO read_state (user_id, kind, target_id, last_read)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(user_id, kind, target_id)
		 DO UPDATE SET last_read = MAX(last_read, excluded.last_read)`,
		userID, kind, targetID, lastID,
	)
	return err
}

// UnreadDMCounts returns peer user id -> number of unread messages they sent to
// userID. Peers with nothing unread are absent.
func (s *Store) UnreadDMCounts(userID int64) (map[int64]int, error) {
	rows, err := s.db.Query(
		`SELECT m.sender_id, COUNT(*)
		   FROM messages m
		   LEFT JOIN read_state r
		     ON r.user_id = ? AND r.kind = 'dm' AND r.target_id = m.sender_id
		  WHERE m.recipient_id = ? AND m.id > COALESCE(r.last_read, 0)
		  GROUP BY m.sender_id`,
		userID, userID,
	)
	if err != nil {
		return nil, err
	}
	return scanCounts(rows)
}

// UnreadGroupCounts returns group id -> number of unread messages (from others)
// in each group userID belongs to.
func (s *Store) UnreadGroupCounts(userID int64) (map[int64]int, error) {
	rows, err := s.db.Query(
		`SELECT gm.group_id, COUNT(*)
		   FROM group_messages gm
		   JOIN group_members mem
		     ON mem.group_id = gm.group_id AND mem.user_id = ?
		    AND mem.status = 'joined' 
		   LEFT JOIN read_state r
		     ON r.user_id = ? AND r.kind = 'group' AND r.target_id = gm.group_id
		  WHERE gm.sender_id != ? AND gm.id > COALESCE(r.last_read, 0)
		  GROUP BY gm.group_id`,
		userID, userID, userID,
	)
	if err != nil {
		return nil, err
	}
	return scanCounts(rows)
}

func scanCounts(rows interface {
	Next() bool
	Scan(...any) error
	Close() error
	Err() error
}) (map[int64]int, error) {
	defer rows.Close()
	out := map[int64]int{}
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}
