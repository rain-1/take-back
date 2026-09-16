package store

import (
	"database/sql"
	"errors"
	"strings"
	"time"
)

// Servers are communities: a named space with an icon, members with a role,
// and channels. Text channels hold persistent chat; voice channels are drop-in
// calls. Unlike a group (a private conversation between people who already
// know each other), a server is joined through an invite link or code.
//
// NB: the database does not enforce foreign keys (no PRAGMA foreign_keys), so
// the ON DELETE CASCADE clauses below are documentation only. Deleting a server
// or channel removes its dependent rows explicitly, in one transaction.

var (
	ErrNotServerMember = errors.New("not a member of this server")
	ErrNotAdmin        = errors.New("only a server admin can do that")
	ErrNoSuchServer    = errors.New("no such server")
	ErrNoSuchChannel   = errors.New("no such channel")
	ErrBadInvite       = errors.New("that invite link is invalid or has been revoked")
	ErrOwnerCantLeave  = errors.New("the server's creator can't leave it; delete it instead")
)

// Server roles. Deliberately just two for now; finer-grained role management
// comes later.
const (
	RoleAdmin = "admin"
	RoleUser  = "user"
)

// Channel kinds.
const (
	ChannelText  = "text"
	ChannelVoice = "voice"
)

// KindChannel is the read_state kind and reaction scope for channel messages.
const KindChannel = "channel"

func init() {
	schemaExtras = append(schemaExtras, `
CREATE TABLE IF NOT EXISTS servers (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT NOT NULL,
  icon_file  TEXT NOT NULL DEFAULT '',
  owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS server_members (
  server_id INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role      TEXT NOT NULL DEFAULT 'user',
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (server_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_server_members_user ON server_members (user_id);
CREATE TABLE IF NOT EXISTS server_invites (
  code       TEXT PRIMARY KEY,
  server_id  INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER NOT NULL DEFAULT 0,
  uses       INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS channels (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id  INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  kind       TEXT NOT NULL,
  position   INTEGER NOT NULL DEFAULT 0,
  call_code  TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_channels_server ON channels (server_id, position, id);
CREATE TABLE IF NOT EXISTS channel_messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id INTEGER NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  sender_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body       TEXT NOT NULL DEFAULT '',
  image_file TEXT NOT NULL DEFAULT '',
  thumb_file TEXT NOT NULL DEFAULT '',
  media_kind TEXT NOT NULL DEFAULT '',
  media_name TEXT NOT NULL DEFAULT '',
  media_size INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  edited_at  INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER NOT NULL DEFAULT 0,
  reply_to   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_channel_messages ON channel_messages (channel_id, id);`)
}

// Server is a community as seen by one member.
type Server struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	IconFile    string `json:"-"`
	IconURL     string `json:"iconUrl,omitempty"`
	OwnerID     int64  `json:"ownerId"`
	Role        string `json:"role,omitempty"` // the viewing member's role
	MemberCount int    `json:"memberCount"`
	Unread      int    `json:"unread"`
}

// ServerMember is a member and their role.
type ServerMember struct {
	User User   `json:"user"`
	Role string `json:"role"`
}

// Channel is a text or voice channel. CallCode is the signaling room for a voice
// channel and is only returned to members.
type Channel struct {
	ID       int64  `json:"id"`
	ServerID int64  `json:"serverId"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
	Position int    `json:"position"`
	CallCode string `json:"callCode,omitempty"`
	Unread   int    `json:"unread"`
}

// ChannelMessage is one message in a text channel.
type ChannelMessage struct {
	ID          int64     `json:"id"`
	ChannelID   int64     `json:"channelId"`
	SenderID    int64     `json:"senderId"`
	Body        string    `json:"body"`
	ImageFile   string    `json:"imageFile,omitempty"`
	ThumbFile   string    `json:"thumbFile,omitempty"`
	MediaKind   string    `json:"mediaKind,omitempty"`
	MediaName   string    `json:"mediaName,omitempty"`
	MediaSize   int64     `json:"mediaSize,omitempty"`
	Created     time.Time `json:"created"`
	EditedAt    int64     `json:"editedAt,omitempty"`
	DeletedAt   int64     `json:"deletedAt,omitempty"`
	ReplyTo     int64     `json:"replyTo,omitempty"`
	ReplySender int64     `json:"replySender,omitempty"`
	ReplyBody   string    `json:"replyBody,omitempty"`
}

// ---- servers ------------------------------------------------------------------

// CreateServer makes a server owned by ownerID, who becomes its first admin,
// with a starter text channel and voice channel so it's usable immediately.
func (s *Store) CreateServer(ownerID int64, name string) (*Server, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	now := time.Now().Unix()
	res, err := tx.Exec(`INSERT INTO servers (name, owner_id, created_at) VALUES (?, ?, ?)`, name, ownerID, now)
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	if _, err := tx.Exec(`INSERT INTO server_members (server_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)`,
		id, ownerID, RoleAdmin, now); err != nil {
		return nil, err
	}
	for i, ch := range []struct{ name, kind string }{{"general", ChannelText}, {"General", ChannelVoice}} {
		if _, err := tx.Exec(`INSERT INTO channels (server_id, name, kind, position, call_code, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
			id, ch.name, ch.kind, i, voiceCode(ch.kind), now); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &Server{ID: id, Name: name, OwnerID: ownerID, Role: RoleAdmin, MemberCount: 1}, nil
}

// voiceCode gives voice channels a long, unguessable signaling room. The call
// server additionally checks membership, but the code shouldn't be the weak link.
func voiceCode(kind string) string {
	if kind != ChannelVoice {
		return ""
	}
	return "V" + randCode(15)
}

// ServersForUser lists the servers userID belongs to, with their role, member
// count and total unread across text channels.
func (s *Store) ServersForUser(userID int64) ([]Server, error) {
	rows, err := s.db.Query(
		`SELECT sv.id, sv.name, sv.icon_file, sv.owner_id, me.role,
		        (SELECT COUNT(*) FROM server_members m WHERE m.server_id = sv.id)
		   FROM servers sv
		   JOIN server_members me ON me.server_id = sv.id AND me.user_id = ?
		  ORDER BY me.joined_at, sv.id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Server
	for rows.Next() {
		var sv Server
		if err := rows.Scan(&sv.ID, &sv.Name, &sv.IconFile, &sv.OwnerID, &sv.Role, &sv.MemberCount); err != nil {
			return nil, err
		}
		sv.IconURL = mediaPath(sv.IconFile)
		out = append(out, sv)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	unread, err := s.UnreadChannelCounts(userID)
	if err != nil {
		return nil, err
	}
	byServer, err := s.channelServers()
	if err != nil {
		return nil, err
	}
	for i := range out {
		for ch, n := range unread {
			if byServer[ch] == out[i].ID {
				out[i].Unread += n
			}
		}
	}
	return out, nil
}

// channelServers maps channel id -> server id.
func (s *Store) channelServers() (map[int64]int64, error) {
	rows, err := s.db.Query(`SELECT id, server_id FROM channels`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]int64{}
	for rows.Next() {
		var ch, sv int64
		if err := rows.Scan(&ch, &sv); err != nil {
			return nil, err
		}
		out[ch] = sv
	}
	return out, rows.Err()
}

// ServerByID returns a server without per-member fields.
func (s *Store) ServerByID(serverID int64) (*Server, error) {
	var sv Server
	err := s.db.QueryRow(
		`SELECT id, name, icon_file, owner_id, (SELECT COUNT(*) FROM server_members WHERE server_id = servers.id)
		   FROM servers WHERE id = ?`, serverID,
	).Scan(&sv.ID, &sv.Name, &sv.IconFile, &sv.OwnerID, &sv.MemberCount)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoSuchServer
	}
	if err != nil {
		return nil, err
	}
	sv.IconURL = mediaPath(sv.IconFile)
	return &sv, nil
}

// MemberRole returns userID's role in serverID, or ErrNotServerMember.
func (s *Store) MemberRole(serverID, userID int64) (string, error) {
	var role string
	err := s.db.QueryRow(`SELECT role FROM server_members WHERE server_id = ? AND user_id = ?`,
		serverID, userID).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotServerMember
	}
	return role, err
}

// RequireAdmin returns nil if userID is an admin of serverID.
func (s *Store) RequireAdmin(serverID, userID int64) error {
	role, err := s.MemberRole(serverID, userID)
	if err != nil {
		return err
	}
	if role != RoleAdmin {
		return ErrNotAdmin
	}
	return nil
}

// ServerMemberIDs lists everyone in a server, for fanning out events.
func (s *Store) ServerMemberIDs(serverID int64) ([]int64, error) {
	rows, err := s.db.Query(`SELECT user_id FROM server_members WHERE server_id = ?`, serverID)
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

// ServerMembers lists members with their roles, admins first.
func (s *Store) ServerMembers(serverID int64) ([]ServerMember, error) {
	rows, err := s.db.Query(
		`SELECT u.id, u.nick, u.created_at, u.avatar_file, m.role
		   FROM server_members m JOIN users u ON u.id = m.user_id
		  WHERE m.server_id = ?
		  ORDER BY CASE m.role WHEN 'admin' THEN 0 ELSE 1 END, u.nick COLLATE NOCASE`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ServerMember
	for rows.Next() {
		var m ServerMember
		var created int64
		var avatar string
		if err := rows.Scan(&m.User.ID, &m.User.Nick, &created, &avatar, &m.Role); err != nil {
			return nil, err
		}
		m.User.Created = time.Unix(created, 0)
		m.User.AvatarURL = avatarURL(avatar)
		out = append(out, m)
	}
	return out, rows.Err()
}

// RenameServer and SetServerIcon: admins only (checked by the caller).
func (s *Store) RenameServer(serverID int64, name string) error {
	_, err := s.db.Exec(`UPDATE servers SET name = ? WHERE id = ?`, name, serverID)
	return err
}

// SetServerIcon records a new icon and returns the previous file so the caller
// can remove it from disk.
func (s *Store) SetServerIcon(serverID int64, file string) (previous string, err error) {
	_ = s.db.QueryRow(`SELECT icon_file FROM servers WHERE id = ?`, serverID).Scan(&previous)
	_, err = s.db.Exec(`UPDATE servers SET icon_file = ? WHERE id = ?`, file, serverID)
	return previous, err
}

// LeaveServer removes userID. The owner can't leave (delete the server instead),
// which also guarantees a server always has at least one admin.
func (s *Store) LeaveServer(serverID, userID int64) error {
	sv, err := s.ServerByID(serverID)
	if err != nil {
		return err
	}
	if sv.OwnerID == userID {
		return ErrOwnerCantLeave
	}
	res, err := s.db.Exec(`DELETE FROM server_members WHERE server_id = ? AND user_id = ?`, serverID, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotServerMember
	}
	return nil
}

// DeleteServer removes a server and everything in it, returning the media files
// (message attachments and the icon) the caller should delete from disk.
func (s *Store) DeleteServer(serverID int64) ([]string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var files []string
	var icon string
	_ = tx.QueryRow(`SELECT icon_file FROM servers WHERE id = ?`, serverID).Scan(&icon)
	files = append(files, nonEmpty(icon)...)
	rows, err := tx.Query(
		`SELECT image_file, thumb_file FROM channel_messages
		  WHERE channel_id IN (SELECT id FROM channels WHERE server_id = ?)`, serverID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var a, b string
		if err := rows.Scan(&a, &b); err != nil {
			rows.Close()
			return nil, err
		}
		files = append(files, nonEmpty(a, b)...)
	}
	rows.Close()

	stmts := []string{
		`DELETE FROM reactions WHERE scope = 'channel' AND message_id IN
		   (SELECT id FROM channel_messages WHERE channel_id IN (SELECT id FROM channels WHERE server_id = ?1))`,
		`DELETE FROM read_state WHERE kind = 'channel' AND target_id IN (SELECT id FROM channels WHERE server_id = ?1)`,
		`DELETE FROM channel_messages WHERE channel_id IN (SELECT id FROM channels WHERE server_id = ?1)`,
		`DELETE FROM channels WHERE server_id = ?1`,
		`DELETE FROM server_invites WHERE server_id = ?1`,
		`DELETE FROM server_members WHERE server_id = ?1`,
		`DELETE FROM servers WHERE id = ?1`,
	}
	for _, q := range stmts {
		if _, err := tx.Exec(q, serverID); err != nil {
			return nil, err
		}
	}
	return files, tx.Commit()
}

// ---- invites -----------------------------------------------------------------------

// CreateInvite mints a new invite code for a server. Any member may invite.
func (s *Store) CreateInvite(serverID, createdBy int64) (string, error) {
	code := randCode(10)
	_, err := s.db.Exec(`INSERT INTO server_invites (code, server_id, created_by, created_at) VALUES (?, ?, ?, ?)`,
		code, serverID, createdBy, time.Now().Unix())
	return code, err
}

// InviteServer resolves a live invite code to its server.
func (s *Store) InviteServer(code string) (*Server, error) {
	var serverID int64
	err := s.db.QueryRow(`SELECT server_id FROM server_invites WHERE code = ? AND revoked_at = 0`,
		normaliseCode(code)).Scan(&serverID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrBadInvite
	}
	if err != nil {
		return nil, err
	}
	return s.ServerByID(serverID)
}

// JoinByInvite adds userID to the invite's server. Joining a server you're
// already in is a no-op success, so opening an old invite link is harmless.
func (s *Store) JoinByInvite(code string, userID int64) (*Server, bool, error) {
	sv, err := s.InviteServer(code)
	if err != nil {
		return nil, false, err
	}
	res, err := s.db.Exec(
		`INSERT OR IGNORE INTO server_members (server_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)`,
		sv.ID, userID, RoleUser, time.Now().Unix())
	if err != nil {
		return nil, false, err
	}
	joined := false
	if n, _ := res.RowsAffected(); n > 0 {
		joined = true
		_, _ = s.db.Exec(`UPDATE server_invites SET uses = uses + 1 WHERE code = ?`, normaliseCode(code))
	}
	return sv, joined, nil
}

// RevokeInvite stops a code working (admins only; checked by the caller).
func (s *Store) RevokeInvite(serverID int64, code string) error {
	_, err := s.db.Exec(`UPDATE server_invites SET revoked_at = ? WHERE code = ? AND server_id = ?`,
		time.Now().Unix(), normaliseCode(code), serverID)
	return err
}

// Invite codes are case-insensitive for people typing them in.
func normaliseCode(code string) string { return strings.ToUpper(strings.TrimSpace(code)) }

// ---- channels -------------------------------------------------------------------------

// Channels lists a server's channels in display order, with userID's unread
// count on text channels.
func (s *Store) Channels(serverID, userID int64) ([]Channel, error) {
	rows, err := s.db.Query(
		`SELECT id, server_id, name, kind, position, call_code FROM channels
		  WHERE server_id = ? ORDER BY CASE kind WHEN 'text' THEN 0 ELSE 1 END, position, id`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Channel
	for rows.Next() {
		var c Channel
		if err := rows.Scan(&c.ID, &c.ServerID, &c.Name, &c.Kind, &c.Position, &c.CallCode); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	unread, err := s.UnreadChannelCounts(userID)
	if err != nil {
		return nil, err
	}
	for i := range out {
		out[i].Unread = unread[out[i].ID]
	}
	return out, nil
}

// ChannelByID returns a channel (including its server id, for authorization).
func (s *Store) ChannelByID(channelID int64) (*Channel, error) {
	var c Channel
	err := s.db.QueryRow(`SELECT id, server_id, name, kind, position, call_code FROM channels WHERE id = ?`,
		channelID).Scan(&c.ID, &c.ServerID, &c.Name, &c.Kind, &c.Position, &c.CallCode)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoSuchChannel
	}
	return &c, err
}

// VoiceChannelByCode resolves a signaling room code to its voice channel, so the
// call server can check the caller is a member of that channel's server.
func (s *Store) VoiceChannelByCode(code string) (*Channel, error) {
	var c Channel
	err := s.db.QueryRow(
		`SELECT id, server_id, name, kind, position, call_code FROM channels WHERE kind = 'voice' AND call_code = ?`,
		code).Scan(&c.ID, &c.ServerID, &c.Name, &c.Kind, &c.Position, &c.CallCode)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoSuchChannel
	}
	return &c, err
}

// CreateChannel adds a channel at the end of its kind's list.
func (s *Store) CreateChannel(serverID int64, name, kind string) (*Channel, error) {
	var pos int
	_ = s.db.QueryRow(`SELECT COALESCE(MAX(position), -1) + 1 FROM channels WHERE server_id = ?`, serverID).Scan(&pos)
	code := voiceCode(kind)
	res, err := s.db.Exec(`INSERT INTO channels (server_id, name, kind, position, call_code, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		serverID, name, kind, pos, code, time.Now().Unix())
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	return &Channel{ID: id, ServerID: serverID, Name: name, Kind: kind, Position: pos, CallCode: code}, nil
}

// RenameChannel renames a channel.
func (s *Store) RenameChannel(channelID int64, name string) error {
	_, err := s.db.Exec(`UPDATE channels SET name = ? WHERE id = ?`, name, channelID)
	return err
}

// DeleteChannel removes a channel and its messages, returning attachment files
// to remove from disk.
func (s *Store) DeleteChannel(channelID int64) ([]string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var files []string
	rows, err := tx.Query(`SELECT image_file, thumb_file FROM channel_messages WHERE channel_id = ?`, channelID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var a, b string
		if err := rows.Scan(&a, &b); err != nil {
			rows.Close()
			return nil, err
		}
		files = append(files, nonEmpty(a, b)...)
	}
	rows.Close()
	for _, q := range []string{
		`DELETE FROM reactions WHERE scope = 'channel' AND message_id IN (SELECT id FROM channel_messages WHERE channel_id = ?1)`,
		`DELETE FROM read_state WHERE kind = 'channel' AND target_id = ?1`,
		`DELETE FROM channel_messages WHERE channel_id = ?1`,
		`DELETE FROM channels WHERE id = ?1`,
	} {
		if _, err := tx.Exec(q, channelID); err != nil {
			return nil, err
		}
	}
	return files, tx.Commit()
}

// ---- channel messages ---------------------------------------------------------------

// AddChannelMessage stores a message. A non-zero ReplyTo must be in the SAME
// channel (ErrReplyOutOfScope otherwise) — reply ids are global row ids, and
// resolving one unscoped would leak text from channels the sender can't see.
func (s *Store) AddChannelMessage(m ChannelMessage) (ChannelMessage, error) {
	if m.ReplyTo != 0 {
		var sender int64
		var body string
		err := s.db.QueryRow(`SELECT sender_id, body FROM channel_messages WHERE id = ? AND channel_id = ?`,
			m.ReplyTo, m.ChannelID).Scan(&sender, &body)
		if errors.Is(err, sql.ErrNoRows) {
			return ChannelMessage{}, ErrReplyOutOfScope
		}
		if err != nil {
			return ChannelMessage{}, err
		}
		m.ReplySender, m.ReplyBody = sender, truncate(body, 80)
	}
	now := time.Now()
	res, err := s.db.Exec(
		`INSERT INTO channel_messages (channel_id, sender_id, body, image_file, thumb_file,
		                               media_kind, media_name, media_size, created_at, reply_to)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.ChannelID, m.SenderID, m.Body, m.ImageFile, m.ThumbFile,
		m.MediaKind, m.MediaName, m.MediaSize, now.Unix(), m.ReplyTo)
	if err != nil {
		return ChannelMessage{}, err
	}
	m.ID, _ = res.LastInsertId()
	m.Created = now
	return m, nil
}

// ChannelConversation returns a page of a channel's messages, oldest first.
func (s *Store) ChannelConversation(channelID, beforeID int64, limit int) ([]ChannelMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if beforeID <= 0 {
		beforeID = 1 << 62
	}
	rows, err := s.db.Query(
		`SELECT m.id, m.channel_id, m.sender_id, m.body, m.image_file, m.thumb_file,
		        m.media_kind, m.media_name, m.media_size,
		        m.created_at, m.edited_at, m.deleted_at, m.reply_to,
		        COALESCE(rm.sender_id, 0), COALESCE(rm.body, '')
		   FROM channel_messages m
		   LEFT JOIN channel_messages rm ON rm.id = m.reply_to AND rm.channel_id = m.channel_id
		  WHERE m.channel_id = ? AND m.id < ?
		  ORDER BY m.id DESC LIMIT ?`, channelID, beforeID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var msgs []ChannelMessage
	for rows.Next() {
		var m ChannelMessage
		var created int64
		if err := rows.Scan(&m.ID, &m.ChannelID, &m.SenderID, &m.Body, &m.ImageFile, &m.ThumbFile,
			&m.MediaKind, &m.MediaName, &m.MediaSize, &created, &m.EditedAt, &m.DeletedAt, &m.ReplyTo,
			&m.ReplySender, &m.ReplyBody); err != nil {
			return nil, err
		}
		m.MediaKind = mediaKindOf(m.MediaKind, m.ImageFile)
		m.Created = time.Unix(created, 0)
		m.ReplyBody = truncate(m.ReplyBody, 80)
		msgs = append(msgs, m)
	}
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, rows.Err()
}

// ChannelOfMessage returns the channel a channel message is in.
func (s *Store) ChannelOfMessage(msgID int64) (*Channel, error) {
	var channelID int64
	err := s.db.QueryRow(`SELECT channel_id FROM channel_messages WHERE id = ?`, msgID).Scan(&channelID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, sql.ErrNoRows
	}
	if err != nil {
		return nil, err
	}
	return s.ChannelByID(channelID)
}

// EditChannelMessage rewrites the body of the caller's own message.
func (s *Store) EditChannelMessage(userID, msgID int64, body string) (ChannelMessage, error) {
	var m ChannelMessage
	var created int64
	err := s.db.QueryRow(`SELECT id, channel_id, sender_id, created_at, deleted_at FROM channel_messages WHERE id = ?`,
		msgID).Scan(&m.ID, &m.ChannelID, &m.SenderID, &created, &m.DeletedAt)
	if err != nil {
		return ChannelMessage{}, err
	}
	if m.SenderID != userID {
		return ChannelMessage{}, ErrNotSender
	}
	if m.DeletedAt != 0 {
		return ChannelMessage{}, ErrAlreadyDeleted
	}
	now := time.Now().Unix()
	if _, err := s.db.Exec(`UPDATE channel_messages SET body = ?, edited_at = ? WHERE id = ?`, body, now, msgID); err != nil {
		return ChannelMessage{}, err
	}
	m.Body, m.EditedAt, m.Created = body, now, time.Unix(created, 0)
	return m, nil
}

// DeleteChannelMessage soft-deletes a message. The author may delete their own;
// a server admin may delete anyone's (moderation). Returns media files to remove.
func (s *Store) DeleteChannelMessage(userID, msgID int64) (DeletedMessage, error) {
	var d DeletedMessage
	var imageFile, thumbFile string
	var deletedAt, channelID int64
	err := s.db.QueryRow(`SELECT id, channel_id, sender_id, image_file, thumb_file, deleted_at FROM channel_messages WHERE id = ?`,
		msgID).Scan(&d.ID, &channelID, &d.SenderID, &imageFile, &thumbFile, &deletedAt)
	if err != nil {
		return DeletedMessage{}, err
	}
	ch, err := s.ChannelByID(channelID)
	if err != nil {
		return DeletedMessage{}, err
	}
	if d.SenderID != userID {
		if s.RequireAdmin(ch.ServerID, userID) != nil {
			return DeletedMessage{}, ErrNotSender
		}
	}
	if deletedAt != 0 {
		return DeletedMessage{}, ErrAlreadyDeleted
	}
	if _, err := s.db.Exec(
		`UPDATE channel_messages SET body = '', image_file = '', thumb_file = '',
		        media_kind = '', media_name = '', media_size = 0, deleted_at = ? WHERE id = ?`,
		time.Now().Unix(), msgID); err != nil {
		return DeletedMessage{}, err
	}
	d.ServerID, d.ChannelID = ch.ServerID, channelID
	d.Files = nonEmpty(imageFile, thumbFile)
	return d, nil
}

// UnreadChannelCounts returns channel id -> unread messages (from others) for
// every text channel in every server userID belongs to.
func (s *Store) UnreadChannelCounts(userID int64) (map[int64]int, error) {
	rows, err := s.db.Query(
		`SELECT cm.channel_id, COUNT(*)
		   FROM channel_messages cm
		   JOIN channels c ON c.id = cm.channel_id
		   JOIN server_members mem ON mem.server_id = c.server_id AND mem.user_id = ?
		   LEFT JOIN read_state r ON r.user_id = ? AND r.kind = 'channel' AND r.target_id = cm.channel_id
		  WHERE cm.sender_id != ? AND cm.deleted_at = 0 AND cm.id > COALESCE(r.last_read, 0)
		  GROUP BY cm.channel_id`, userID, userID, userID)
	if err != nil {
		return nil, err
	}
	return scanCounts(rows)
}

// mediaPath turns a stored media filename into a servable path.
func mediaPath(file string) string {
	if file == "" {
		return ""
	}
	return "/media/" + file
}
