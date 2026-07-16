package store

// Conversation activity: when each conversation last saw a message. Clients
// order their lists by this (newest first), which — unlike ordering by unread or
// presence — doesn't reshuffle when you open a chat or someone comes online.

// LastDMActivity returns peer user id -> unix time of the most recent message
// exchanged with userID (in either direction). Peers with no messages are absent.
func (s *Store) LastDMActivity(userID int64) (map[int64]int64, error) {
	rows, err := s.db.Query(
		`SELECT CASE WHEN sender_id = ? THEN recipient_id ELSE sender_id END AS peer,
		        MAX(created_at)
		   FROM messages
		  WHERE sender_id = ? OR recipient_id = ?
		  GROUP BY peer`,
		userID, userID, userID,
	)
	if err != nil {
		return nil, err
	}
	return scanTimes(rows)
}

// LastGroupActivity returns group id -> unix time of the most recent message in
// each group userID belongs to. Groups with no messages are absent.
func (s *Store) LastGroupActivity(userID int64) (map[int64]int64, error) {
	rows, err := s.db.Query(
		`SELECT gm.group_id, MAX(gm.created_at)
		   FROM group_messages gm
		   JOIN group_members mem
		     ON mem.group_id = gm.group_id AND mem.user_id = ?
		  GROUP BY gm.group_id`,
		userID,
	)
	if err != nil {
		return nil, err
	}
	return scanTimes(rows)
}

func scanTimes(rows interface {
	Next() bool
	Scan(...any) error
	Close() error
	Err() error
}) (map[int64]int64, error) {
	defer rows.Close()
	out := map[int64]int64{}
	for rows.Next() {
		var id, ts int64
		if err := rows.Scan(&id, &ts); err != nil {
			return nil, err
		}
		out[id] = ts
	}
	return out, rows.Err()
}
