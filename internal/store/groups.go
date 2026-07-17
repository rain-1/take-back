package store

import (
	"crypto/rand"
	"database/sql"
	"errors"
	"time"
)

var (
	ErrNotMember   = errors.New("not a group member")
	ErrNoSuchGroup = errors.New("no such group")
)

func init() {
	schemaExtras = append(schemaExtras, `
CREATE TABLE IF NOT EXISTS groups (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT NOT NULL,
  owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  call_code  TEXT UNIQUE NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS group_members (
  group_id  INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, user_id)
);
CREATE TABLE IF NOT EXISTS group_messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  sender_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body       TEXT NOT NULL DEFAULT '',
  image_file TEXT NOT NULL DEFAULT '',
  thumb_file TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_group_messages ON group_messages (group_id, id);`)
}

// Group is a multi-user chat room. CallCode is the WebRTC signaling room its
// members join for group calls.
type Group struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	OwnerID     int64  `json:"ownerId"`
	CallCode    string `json:"callCode"`
	MemberCount int    `json:"memberCount"`
}

// GroupMessage is one message posted to a group. EditedAt is 0 when never edited.
type GroupMessage struct {
	ID        int64     `json:"id"`
	GroupID   int64     `json:"groupId"`
	SenderID  int64     `json:"senderId"`
	Body      string    `json:"body"`
	ImageFile string    `json:"imageFile,omitempty"`
	ThumbFile string    `json:"thumbFile,omitempty"`
	Created   time.Time `json:"created"`
	EditedAt  int64     `json:"editedAt,omitempty"`
}

// CreateGroup makes a group owned by ownerID (who becomes its first member) and
// assigns a random call code.
func (s *Store) CreateGroup(ownerID int64, name string) (*Group, error) {
	code := randCode(6)
	now := time.Now().Unix()
	res, err := s.db.Exec(
		`INSERT INTO groups (name, owner_id, call_code, created_at) VALUES (?, ?, ?, ?)`,
		name, ownerID, code, now,
	)
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	if _, err := s.db.Exec(
		`INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)`,
		id, ownerID, now,
	); err != nil {
		return nil, err
	}
	return &Group{ID: id, Name: name, OwnerID: ownerID, CallCode: code, MemberCount: 1}, nil
}

// IsMember reports whether userID belongs to groupID.
func (s *Store) IsMember(groupID, userID int64) bool {
	var one int
	err := s.db.QueryRow(
		`SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?`, groupID, userID,
	).Scan(&one)
	return err == nil
}

// GroupByID returns a group with its current member count.
func (s *Store) GroupByID(groupID int64) (*Group, error) {
	g := &Group{}
	err := s.db.QueryRow(
		`SELECT g.id, g.name, g.owner_id, g.call_code,
		        (SELECT COUNT(*) FROM group_members m WHERE m.group_id = g.id)
		   FROM groups g WHERE g.id = ?`, groupID,
	).Scan(&g.ID, &g.Name, &g.OwnerID, &g.CallCode, &g.MemberCount)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoSuchGroup
	}
	return g, err
}

// GroupsForUser lists the groups userID belongs to.
func (s *Store) GroupsForUser(userID int64) ([]Group, error) {
	rows, err := s.db.Query(
		`SELECT g.id, g.name, g.owner_id, g.call_code,
		        (SELECT COUNT(*) FROM group_members m WHERE m.group_id = g.id)
		   FROM groups g
		   JOIN group_members gm ON gm.group_id = g.id
		  WHERE gm.user_id = ?
		  ORDER BY g.name`, userID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.OwnerID, &g.CallCode, &g.MemberCount); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// GroupMemberIDs returns the user ids of every member (used for event fanout).
func (s *Store) GroupMemberIDs(groupID int64) ([]int64, error) {
	rows, err := s.db.Query(`SELECT user_id FROM group_members WHERE group_id = ?`, groupID)
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

// GroupMembers returns the members of a group as users (for the member list).
func (s *Store) GroupMembers(groupID int64) ([]User, error) {
	rows, err := s.db.Query(
		`SELECT u.id, u.nick, u.created_at
		   FROM group_members gm JOIN users u ON u.id = gm.user_id
		  WHERE gm.group_id = ? ORDER BY u.nick`, groupID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		var created int64
		if err := rows.Scan(&u.ID, &u.Nick, &created); err != nil {
			return nil, err
		}
		u.Created = time.Unix(created, 0)
		out = append(out, u)
	}
	return out, rows.Err()
}

// AddMember adds the user with the given nick to the group. The caller must
// already be a member (enforced by the API layer).
func (s *Store) AddMember(groupID int64, nick string) (*User, error) {
	u, _, err := s.UserByNick(nick)
	if err != nil {
		return nil, err
	}
	_, err = s.db.Exec(
		`INSERT OR IGNORE INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)`,
		groupID, u.ID, time.Now().Unix(),
	)
	return u, err
}

// RemoveMember drops userID from the group.
func (s *Store) RemoveMember(groupID, userID int64) error {
	_, err := s.db.Exec(`DELETE FROM group_members WHERE group_id = ? AND user_id = ?`, groupID, userID)
	return err
}

// AddGroupMessage stores a message posted to a group.
func (s *Store) AddGroupMessage(m GroupMessage) (GroupMessage, error) {
	now := time.Now()
	res, err := s.db.Exec(
		`INSERT INTO group_messages (group_id, sender_id, body, image_file, thumb_file, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		m.GroupID, m.SenderID, m.Body, m.ImageFile, m.ThumbFile, now.Unix(),
	)
	if err != nil {
		return GroupMessage{}, err
	}
	m.ID, _ = res.LastInsertId()
	m.Created = now
	return m, nil
}

// GroupConversation returns a page of a group's messages, oldest first.
func (s *Store) GroupConversation(groupID, beforeID int64, limit int) ([]GroupMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if beforeID <= 0 {
		beforeID = 1 << 62
	}
	rows, err := s.db.Query(
		`SELECT id, group_id, sender_id, body, image_file, thumb_file, created_at, edited_at
		   FROM group_messages WHERE group_id = ? AND id < ?
		  ORDER BY id DESC LIMIT ?`, groupID, beforeID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var msgs []GroupMessage
	for rows.Next() {
		var m GroupMessage
		var created int64
		if err := rows.Scan(&m.ID, &m.GroupID, &m.SenderID, &m.Body,
			&m.ImageFile, &m.ThumbFile, &created, &m.EditedAt); err != nil {
			return nil, err
		}
		m.Created = time.Unix(created, 0)
		msgs = append(msgs, m)
	}
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, rows.Err()
}

// randCode returns an n-char human-friendly uppercase code (no ambiguous chars).
func randCode(n int) string {
	const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	buf := make([]byte, n)
	_, _ = rand.Read(buf)
	for i := range buf {
		buf[i] = alphabet[int(buf[i])%len(alphabet)]
	}
	return string(buf)
}
