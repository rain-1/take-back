package store

import (
	"errors"
	"testing"
)

func TestServerCreateRolesAndStarterChannels(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")

	sv, err := s.CreateServer(owner.ID, "Crew")
	if err != nil {
		t.Fatal(err)
	}
	if role, _ := s.MemberRole(sv.ID, owner.ID); role != RoleAdmin {
		t.Fatalf("creator should be admin, got %q", role)
	}
	chs, err := s.Channels(sv.ID, owner.ID)
	if err != nil || len(chs) != 2 {
		t.Fatalf("want a starter text and voice channel, got %v (%v)", chs, err)
	}
	if chs[0].Kind != ChannelText || chs[1].Kind != ChannelVoice {
		t.Fatalf("text channels list first: %+v", chs)
	}
	if chs[0].CallCode != "" || len(chs[1].CallCode) < 12 {
		t.Fatalf("only voice channels get a (long) call code: %+v", chs)
	}
}

// Invites are the only way in, and they're the security boundary for a server.
func TestInvitesJoinAndRevoke(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	guest := mustUser(t, s, "guest")
	other := mustUser(t, s, "other")
	sv, _ := s.CreateServer(owner.ID, "Crew")

	if _, err := s.MemberRole(sv.ID, guest.ID); !errors.Is(err, ErrNotServerMember) {
		t.Fatalf("guest shouldn't be a member before joining: %v", err)
	}
	code, err := s.CreateInvite(sv.ID, owner.ID)
	if err != nil || len(code) < 10 {
		t.Fatalf("invite code %q (%v)", code, err)
	}

	// Codes are case-insensitive for people typing them.
	got, joined, err := s.JoinByInvite(" "+lower(code)+" ", guest.ID)
	if err != nil || !joined || got.ID != sv.ID {
		t.Fatalf("join: %+v joined=%v err=%v", got, joined, err)
	}
	if role, _ := s.MemberRole(sv.ID, guest.ID); role != RoleUser {
		t.Fatalf("joiners are plain users, got %q", role)
	}
	// Re-using an invite you've already used is a harmless no-op.
	if _, joined, err := s.JoinByInvite(code, guest.ID); err != nil || joined {
		t.Fatalf("rejoin should be a no-op: joined=%v err=%v", joined, err)
	}

	if _, _, err := s.JoinByInvite("NOTACODE12", other.ID); !errors.Is(err, ErrBadInvite) {
		t.Fatalf("bogus code: want ErrBadInvite, got %v", err)
	}
	if err := s.RevokeInvite(sv.ID, code); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.JoinByInvite(code, other.ID); !errors.Is(err, ErrBadInvite) {
		t.Fatalf("revoked code: want ErrBadInvite, got %v", err)
	}
}

func lower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + 32
		}
	}
	return string(b)
}

func TestAdminChecksAndLeaving(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	guest := mustUser(t, s, "guest")
	stranger := mustUser(t, s, "stranger")
	sv, _ := s.CreateServer(owner.ID, "Crew")
	code, _ := s.CreateInvite(sv.ID, owner.ID)
	s.JoinByInvite(code, guest.ID)

	if err := s.RequireAdmin(sv.ID, owner.ID); err != nil {
		t.Fatalf("owner is admin: %v", err)
	}
	if err := s.RequireAdmin(sv.ID, guest.ID); !errors.Is(err, ErrNotAdmin) {
		t.Fatalf("plain member: want ErrNotAdmin, got %v", err)
	}
	if err := s.RequireAdmin(sv.ID, stranger.ID); !errors.Is(err, ErrNotServerMember) {
		t.Fatalf("non-member: want ErrNotServerMember, got %v", err)
	}
	if err := s.LeaveServer(sv.ID, owner.ID); !errors.Is(err, ErrOwnerCantLeave) {
		t.Fatalf("owner leaving: want ErrOwnerCantLeave, got %v", err)
	}
	if err := s.LeaveServer(sv.ID, guest.ID); err != nil {
		t.Fatalf("guest leaving: %v", err)
	}
	if _, err := s.MemberRole(sv.ID, guest.ID); !errors.Is(err, ErrNotServerMember) {
		t.Fatal("guest should be gone after leaving")
	}
}

// Same scoping rule as DMs and groups: a reply id must be in this channel, or it
// would read back text from a channel the sender can't see.
func TestChannelReplyScopedToChannel(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "alice")
	b := mustUser(t, s, "bob")
	private, _ := s.CreateServer(a.ID, "private")
	public, _ := s.CreateServer(b.ID, "public")
	privCh, _ := s.Channels(private.ID, a.ID)
	pubCh, _ := s.Channels(public.ID, b.ID)

	secret, err := s.AddChannelMessage(ChannelMessage{ChannelID: privCh[0].ID, SenderID: a.ID, Body: "internal only"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddChannelMessage(ChannelMessage{ChannelID: pubCh[0].ID, SenderID: b.ID, Body: "hi", ReplyTo: secret.ID}); !errors.Is(err, ErrReplyOutOfScope) {
		t.Fatalf("cross-channel reply: want ErrReplyOutOfScope, got %v", err)
	}
	ok, err := s.AddChannelMessage(ChannelMessage{ChannelID: privCh[0].ID, SenderID: a.ID, Body: "re", ReplyTo: secret.ID})
	if err != nil || ok.ReplyBody != "internal only" {
		t.Fatalf("same-channel reply should quote: %+v %v", ok, err)
	}
}

func TestChannelMessagesUnreadEditDelete(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	guest := mustUser(t, s, "guest")
	sv, _ := s.CreateServer(owner.ID, "Crew")
	code, _ := s.CreateInvite(sv.ID, owner.ID)
	s.JoinByInvite(code, guest.ID)
	chs, _ := s.Channels(sv.ID, owner.ID)
	text := chs[0].ID

	m1, _ := s.AddChannelMessage(ChannelMessage{ChannelID: text, SenderID: guest.ID, Body: "hello"})
	s.AddChannelMessage(ChannelMessage{ChannelID: text, SenderID: guest.ID, Body: "anyone?"})

	counts, _ := s.UnreadChannelCounts(owner.ID)
	if counts[text] != 2 {
		t.Fatalf("owner should have 2 unread, got %d", counts[text])
	}
	if own, _ := s.UnreadChannelCounts(guest.ID); own[text] != 0 {
		t.Fatalf("your own messages aren't unread, got %d", own[text])
	}
	servers, _ := s.ServersForUser(owner.ID)
	if len(servers) != 1 || servers[0].Unread != 2 {
		t.Fatalf("server should total its channels' unread: %+v", servers)
	}
	s.MarkRead(owner.ID, KindChannel, text, m1.ID)
	if counts, _ := s.UnreadChannelCounts(owner.ID); counts[text] != 1 {
		t.Fatalf("after reading the first, 1 unread; got %d", counts[text])
	}

	// Editing: author only.
	if _, err := s.EditChannelMessage(owner.ID, m1.ID, "hijacked"); !errors.Is(err, ErrNotSender) {
		t.Fatalf("non-author edit: want ErrNotSender, got %v", err)
	}
	if e, err := s.EditChannelMessage(guest.ID, m1.ID, "hello!"); err != nil || e.Body != "hello!" {
		t.Fatalf("author edit: %+v %v", e, err)
	}

	// Deleting: the author, or an admin moderating. Not another plain member.
	intruder := mustUser(t, s, "intruder")
	code2, _ := s.CreateInvite(sv.ID, owner.ID)
	s.JoinByInvite(code2, intruder.ID)
	if _, err := s.DeleteChannelMessage(intruder.ID, m1.ID); !errors.Is(err, ErrNotSender) {
		t.Fatalf("other member deleting: want ErrNotSender, got %v", err)
	}
	d, err := s.DeleteChannelMessage(owner.ID, m1.ID)
	if err != nil || d.ServerID != sv.ID || d.ChannelID != text {
		t.Fatalf("admin moderation delete: %+v %v", d, err)
	}
	msgs, _ := s.ChannelConversation(text, 0, 50)
	if msgs[0].DeletedAt == 0 || msgs[0].Body != "" {
		t.Fatalf("deleted message should read back cleared: %+v", msgs[0])
	}
}

// With no foreign-key enforcement, deleting a server must clean up every
// dependent row itself — and hand back attachment files for removal.
func TestDeleteServerRemovesEverything(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	sv, _ := s.CreateServer(owner.ID, "Doomed")
	chs, _ := s.Channels(sv.ID, owner.ID)
	m, _ := s.AddChannelMessage(ChannelMessage{ChannelID: chs[0].ID, SenderID: owner.ID, Body: "x", ImageFile: "a.jpg", ThumbFile: "a_t.jpg"})
	s.SetReaction(KindChannel, m.ID, owner.ID, "👍", true)
	s.MarkRead(owner.ID, KindChannel, chs[0].ID, m.ID)
	s.CreateInvite(sv.ID, owner.ID)
	s.SetServerIcon(sv.ID, "icon.png")

	files, err := s.DeleteServer(sv.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 3 {
		t.Fatalf("want icon + image + thumb returned for removal, got %v", files)
	}
	for table, q := range map[string]string{
		"servers": `SELECT COUNT(*) FROM servers`, "server_members": `SELECT COUNT(*) FROM server_members`,
		"server_invites": `SELECT COUNT(*) FROM server_invites`, "channels": `SELECT COUNT(*) FROM channels`,
		"channel_messages": `SELECT COUNT(*) FROM channel_messages`,
		"reactions":        `SELECT COUNT(*) FROM reactions WHERE scope = 'channel'`,
		"read_state":       `SELECT COUNT(*) FROM read_state WHERE kind = 'channel'`,
	} {
		var n int
		if err := s.db.QueryRow(q).Scan(&n); err != nil || n != 0 {
			t.Errorf("%s: %d rows left behind (%v)", table, n, err)
		}
	}
}

func TestVoiceChannelByCode(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	sv, _ := s.CreateServer(owner.ID, "Crew")
	chs, _ := s.Channels(sv.ID, owner.ID)
	voice := chs[1]
	got, err := s.VoiceChannelByCode(voice.CallCode)
	if err != nil || got.ID != voice.ID || got.ServerID != sv.ID {
		t.Fatalf("resolve voice code: %+v %v", got, err)
	}
	if _, err := s.VoiceChannelByCode("NOPE"); !errors.Is(err, ErrNoSuchChannel) {
		t.Fatalf("unknown code: %v", err)
	}
}
