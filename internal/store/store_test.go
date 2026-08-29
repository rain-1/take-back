package store

import (
	"errors"
	"testing"
	"time"
)

// newTestStore opens a fresh in-memory database for one test.
func newTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(":memory:")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func mustUser(t *testing.T, s *Store, nick string) *User {
	t.Helper()
	u, err := s.CreateUser(nick, "hash-"+nick)
	if err != nil {
		t.Fatalf("create %s: %v", nick, err)
	}
	return u
}

func TestCreateUserAndLookup(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "alice")

	got, hash, err := s.UserByNick("ALICE") // nick is case-insensitive
	if err != nil {
		t.Fatalf("UserByNick: %v", err)
	}
	if got.ID != u.ID || hash != "hash-alice" {
		t.Fatalf("got %+v hash %q", got, hash)
	}

	if _, err := s.CreateUser("alice", "x"); !errors.Is(err, ErrNickTaken) {
		t.Fatalf("expected ErrNickTaken, got %v", err)
	}
	if _, _, err := s.UserByNick("nobody"); !errors.Is(err, ErrNoSuchUser) {
		t.Fatalf("expected ErrNoSuchUser, got %v", err)
	}
}

func TestSessions(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "bob")

	tok, err := s.NewSession(u.ID, time.Hour)
	if err != nil {
		t.Fatalf("NewSession: %v", err)
	}
	got, err := s.UserBySession(tok)
	if err != nil || got.ID != u.ID {
		t.Fatalf("UserBySession: %v got=%+v", err, got)
	}

	if err := s.DeleteSession(tok); err != nil {
		t.Fatalf("DeleteSession: %v", err)
	}
	if _, err := s.UserBySession(tok); err == nil {
		t.Fatal("expected error after delete")
	}
}

func TestExpiredSessionRejected(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "carol")
	tok, err := s.NewSession(u.ID, -time.Minute) // already expired
	if err != nil {
		t.Fatalf("NewSession: %v", err)
	}
	if _, err := s.UserBySession(tok); err == nil {
		t.Fatal("expected expired session to be rejected")
	}
}

func TestFriendshipLifecycle(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "amy")
	b := mustUser(t, s, "ben")

	// Request a -> b.
	if err := s.SendRequest(a.ID, "ben"); err != nil {
		t.Fatalf("SendRequest: %v", err)
	}
	// Duplicate / reverse request is rejected while pending.
	if err := s.SendRequest(b.ID, "amy"); !errors.Is(err, ErrAlreadyFriend) {
		t.Fatalf("expected ErrAlreadyFriend, got %v", err)
	}
	// Not friends until accepted.
	if s.AreFriends(a.ID, b.ID) {
		t.Fatal("should not be friends before accept")
	}

	// b sees an incoming pending request.
	bf, err := s.Friends(b.ID)
	if err != nil {
		t.Fatalf("Friends: %v", err)
	}
	if len(bf) != 1 || bf[0].Status != StatusPending || bf[0].Direction != "incoming" {
		t.Fatalf("unexpected pending view: %+v", bf)
	}

	// Accept and confirm both directions report friendship.
	if err := s.Accept(b.ID, a.ID); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if !s.AreFriends(a.ID, b.ID) || !s.AreFriends(b.ID, a.ID) {
		t.Fatal("should be friends after accept")
	}

	ids, err := s.AcceptedFriendIDs(a.ID)
	if err != nil || len(ids) != 1 || ids[0] != b.ID {
		t.Fatalf("AcceptedFriendIDs = %v (err %v)", ids, err)
	}

	// Remove clears the relationship.
	if err := s.Remove(a.ID, b.ID); err != nil {
		t.Fatalf("Remove: %v", err)
	}
	if s.AreFriends(a.ID, b.ID) {
		t.Fatal("should not be friends after remove")
	}
}

func TestSendRequestErrors(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "ann")

	if err := s.SendRequest(a.ID, "ann"); !errors.Is(err, ErrSelfFriend) {
		t.Fatalf("expected ErrSelfFriend, got %v", err)
	}
	if err := s.SendRequest(a.ID, "ghost"); !errors.Is(err, ErrNoSuchUser) {
		t.Fatalf("expected ErrNoSuchUser, got %v", err)
	}
}

func TestConversationOrderingAndPaging(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "ida")
	b := mustUser(t, s, "jon")

	var ids []int64
	for i := 0; i < 5; i++ {
		sender, recipient := a.ID, b.ID
		if i%2 == 1 {
			sender, recipient = b.ID, a.ID
		}
		m, err := s.AddMessage(Message{SenderID: sender, RecipientID: recipient, Body: string(rune('A' + i))})
		if err != nil {
			t.Fatalf("AddMessage: %v", err)
		}
		ids = append(ids, m.ID)
	}

	msgs, err := s.Conversation(a.ID, b.ID, 0, 50)
	if err != nil {
		t.Fatalf("Conversation: %v", err)
	}
	if len(msgs) != 5 {
		t.Fatalf("want 5 messages, got %d", len(msgs))
	}
	// Chronological (ascending id) order.
	for i := 1; i < len(msgs); i++ {
		if msgs[i-1].ID >= msgs[i].ID {
			t.Fatalf("not chronological: %v", msgs)
		}
	}

	// Paging: messages before the 3rd id should be the first two.
	page, err := s.Conversation(a.ID, b.ID, ids[2], 50)
	if err != nil {
		t.Fatalf("Conversation page: %v", err)
	}
	if len(page) != 2 || page[0].ID != ids[0] || page[1].ID != ids[1] {
		t.Fatalf("unexpected page: %+v", page)
	}
}

func TestConversationIsolatedPerPair(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "p1")
	b := mustUser(t, s, "p2")
	c := mustUser(t, s, "p3")

	if _, err := s.AddMessage(Message{SenderID: a.ID, RecipientID: b.ID, Body: "for b"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddMessage(Message{SenderID: a.ID, RecipientID: c.ID, Body: "for c"}); err != nil {
		t.Fatal(err)
	}
	msgs, err := s.Conversation(a.ID, b.ID, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].Body != "for b" {
		t.Fatalf("conversation leaked across pairs: %+v", msgs)
	}
}

// TestMediaRoundTripDMAndGroup covers the attachment metadata added in 1.18:
// it must survive both the insert path (which returns the message straight to
// the sender) and the read-back query.
func TestMediaRoundTripDMAndGroup(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "alice")
	b := mustUser(t, s, "bob")

	sent, err := s.AddMessage(Message{
		SenderID: a.ID, RecipientID: b.ID, Body: "look at this",
		ImageFile: "deadbeef.mp4", MediaKind: "video",
		MediaName: "holiday.mp4", MediaSize: 4242,
	})
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if sent.MediaKind != "video" || sent.MediaName != "holiday.mp4" || sent.MediaSize != 4242 {
		t.Fatalf("insert path lost media metadata: %+v", sent)
	}

	msgs, err := s.Conversation(a.ID, b.ID, 0, 50)
	if err != nil || len(msgs) != 1 {
		t.Fatalf("Conversation: %v (%d msgs)", err, len(msgs))
	}
	got := msgs[0]
	if got.MediaKind != "video" || got.MediaName != "holiday.mp4" || got.MediaSize != 4242 ||
		got.ImageFile != "deadbeef.mp4" {
		t.Fatalf("read-back lost media metadata: %+v", got)
	}

	g, err := s.CreateGroup(a.ID, "crew")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddGroupMessage(GroupMessage{
		GroupID: g.ID, SenderID: a.ID, ImageFile: "cafe.pdf",
		MediaKind: "file", MediaName: "notes.pdf", MediaSize: 99,
	}); err != nil {
		t.Fatalf("AddGroupMessage: %v", err)
	}
	gms, err := s.GroupConversation(g.ID, 0, 50)
	if err != nil || len(gms) != 1 {
		t.Fatalf("GroupConversation: %v (%d msgs)", err, len(gms))
	}
	if gms[0].MediaKind != "file" || gms[0].MediaName != "notes.pdf" || gms[0].MediaSize != 99 {
		t.Fatalf("group read-back lost media metadata: %+v", gms[0])
	}
}

// TestLegacyImageRowReadsBackAsImage: rows written before media_kind existed
// were all images, so they must not come back as an unknown kind.
func TestLegacyImageRowReadsBackAsImage(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "alice")
	b := mustUser(t, s, "bob")

	// Insert the way the pre-1.18 code did: files set, kind columns defaulted.
	if _, err := s.db.Exec(
		`INSERT INTO messages (sender_id, recipient_id, body, image_file, thumb_file, created_at)
		 VALUES (?, ?, '', 'old.jpg', 'old_thumb.jpg', 1)`, a.ID, b.ID); err != nil {
		t.Fatal(err)
	}
	msgs, err := s.Conversation(a.ID, b.ID, 0, 50)
	if err != nil || len(msgs) != 1 {
		t.Fatalf("Conversation: %v (%d)", err, len(msgs))
	}
	if msgs[0].MediaKind != "image" {
		t.Fatalf("legacy row kind: got %q, want image", msgs[0].MediaKind)
	}
}

// TestReplyQuoteCannotCrossConversations is the regression test for the reply-id
// leak: reply ids are global row ids chosen by the caller, so a reply carrying
// someone else's id used to read back that message's sender and first 80
// characters. Ids are sequential, which made the whole table walkable.
func TestReplyQuoteCannotCrossConversations(t *testing.T) {
	s := newTestStore(t)
	alice := mustUser(t, s, "alice")
	bob := mustUser(t, s, "bob")
	mallory := mustUser(t, s, "mallory")

	// A private message between alice and bob.
	secret, err := s.AddMessage(Message{
		SenderID: alice.ID, RecipientID: bob.ID, Body: "the vault code is 4815162342",
	})
	if err != nil {
		t.Fatal(err)
	}

	// Mallory replies to it from her own conversation with alice.
	_, err = s.AddMessage(Message{
		SenderID: mallory.ID, RecipientID: alice.ID, Body: "hi", ReplyTo: secret.ID,
	})
	if !errors.Is(err, ErrReplyOutOfScope) {
		t.Fatalf("cross-conversation reply: got err %v, want ErrReplyOutOfScope", err)
	}

	// ...and nothing was stored, so there's no row to leak later either.
	msgs, err := s.Conversation(mallory.ID, alice.ID, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 0 {
		t.Fatalf("rejected reply must not be stored, got %d messages", len(msgs))
	}

	// A reply INSIDE the conversation still works and still carries its quote.
	reply, err := s.AddMessage(Message{
		SenderID: bob.ID, RecipientID: alice.ID, Body: "got it", ReplyTo: secret.ID,
	})
	if err != nil {
		t.Fatalf("in-conversation reply should be allowed: %v", err)
	}
	if reply.ReplySender != alice.ID || reply.ReplyBody != "the vault code is 4815162342" {
		t.Fatalf("legitimate quote not populated: %+v", reply)
	}
}

// Even a row written before the scope check existed must not leak through the
// read path's JOIN, so the fetch is constrained identically.
func TestConversationJoinIgnoresOutOfScopeReplyRows(t *testing.T) {
	s := newTestStore(t)
	alice := mustUser(t, s, "alice")
	bob := mustUser(t, s, "bob")
	mallory := mustUser(t, s, "mallory")

	secret, err := s.AddMessage(Message{
		SenderID: alice.ID, RecipientID: bob.ID, Body: "the vault code is 4815162342",
	})
	if err != nil {
		t.Fatal(err)
	}
	// Write the row the old code would have allowed, straight past the guard.
	if _, err := s.db.Exec(
		`INSERT INTO messages (sender_id, recipient_id, body, created_at, reply_to)
		 VALUES (?, ?, 'hi', 1, ?)`, mallory.ID, alice.ID, secret.ID); err != nil {
		t.Fatal(err)
	}

	msgs, err := s.Conversation(mallory.ID, alice.ID, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("want 1 message, got %d", len(msgs))
	}
	if msgs[0].ReplyBody != "" || msgs[0].ReplySender != 0 {
		t.Fatalf("legacy out-of-scope reply leaked a quote: sender=%d body=%q",
			msgs[0].ReplySender, msgs[0].ReplyBody)
	}
}

func TestGroupReplyQuoteCannotCrossGroups(t *testing.T) {
	s := newTestStore(t)
	alice := mustUser(t, s, "alice")
	mallory := mustUser(t, s, "mallory")

	private, err := s.CreateGroup(alice.ID, "private")
	if err != nil {
		t.Fatal(err)
	}
	other, err := s.CreateGroup(mallory.ID, "other")
	if err != nil {
		t.Fatal(err)
	}
	secret, err := s.AddGroupMessage(GroupMessage{
		GroupID: private.ID, SenderID: alice.ID, Body: "internal only",
	})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := s.AddGroupMessage(GroupMessage{
		GroupID: other.ID, SenderID: mallory.ID, Body: "hi", ReplyTo: secret.ID,
	}); !errors.Is(err, ErrReplyOutOfScope) {
		t.Fatalf("cross-group reply: got err %v, want ErrReplyOutOfScope", err)
	}

	// Same group is still fine.
	ok, err := s.AddGroupMessage(GroupMessage{
		GroupID: private.ID, SenderID: alice.ID, Body: "re", ReplyTo: secret.ID,
	})
	if err != nil {
		t.Fatalf("in-group reply should be allowed: %v", err)
	}
	if ok.ReplyBody != "internal only" {
		t.Fatalf("legitimate group quote not populated: %+v", ok)
	}
}

// TestSessionSlidesOnUse: an actively-used session must stay valid without the
// client holding the account password to re-authenticate. This is what let the
// `tb` CLI stop persisting a reusable credential.
func TestSessionSlidesOnUse(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "alice")

	token, err := s.NewSession(u.ID, SessionTTL)
	if err != nil {
		t.Fatal(err)
	}
	// Age the session so it is close to expiring, the way a month-old one is.
	nearly := time.Now().Add(2 * time.Hour).Unix()
	if _, err := s.db.Exec(`UPDATE sessions SET expires_at = ? WHERE token = ?`, nearly, token); err != nil {
		t.Fatal(err)
	}

	if _, err := s.UserBySession(token); err != nil {
		t.Fatalf("a live session should resolve: %v", err)
	}

	var after int64
	if err := s.db.QueryRow(`SELECT expires_at FROM sessions WHERE token = ?`, token).Scan(&after); err != nil {
		t.Fatal(err)
	}
	if after <= nearly {
		t.Fatalf("expiry did not slide: was %d, still %d", nearly, after)
	}
	if want := time.Now().Add(SessionTTL).Unix(); after < want-60 || after > want+60 {
		t.Fatalf("expiry %d should be ~a full TTL out (%d)", after, want)
	}
}

// An expired session must stay expired — sliding applies to live ones only.
func TestExpiredSessionIsRejected(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "alice")
	token, err := s.NewSession(u.ID, SessionTTL)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec(`UPDATE sessions SET expires_at = ? WHERE token = ?`,
		time.Now().Add(-time.Hour).Unix(), token); err != nil {
		t.Fatal(err)
	}
	if _, err := s.UserBySession(token); err == nil {
		t.Fatal("an expired session must not resolve")
	}
}

// TestDeleteMessage: a delete clears the content, keeps the row (so replies
// quoting it still resolve and the id is never reused), and reports the media
// files so the caller can remove them from disk.
func TestDeleteMessage(t *testing.T) {
	s := newTestStore(t)
	alice := mustUser(t, s, "alice")
	bob := mustUser(t, s, "bob")

	m, err := s.AddMessage(Message{
		SenderID: alice.ID, RecipientID: bob.ID, Body: "oops",
		ImageFile: "abc.jpg", ThumbFile: "abc_thumb.jpg", MediaKind: "image",
	})
	if err != nil {
		t.Fatal(err)
	}

	// Only the author may delete.
	if _, err := s.DeleteMessage(bob.ID, m.ID); !errors.Is(err, ErrNotSender) {
		t.Fatalf("recipient deleting: got %v, want ErrNotSender", err)
	}

	del, err := s.DeleteMessage(alice.ID, m.ID)
	if err != nil {
		t.Fatalf("DeleteMessage: %v", err)
	}
	if len(del.Files) != 2 {
		t.Fatalf("want both media files reported for removal, got %v", del.Files)
	}
	if del.RecipientID != bob.ID {
		t.Fatalf("recipient should be reported so the event can be routed, got %d", del.RecipientID)
	}

	// Deleting twice must not re-notify everyone.
	if _, err := s.DeleteMessage(alice.ID, m.ID); !errors.Is(err, ErrAlreadyDeleted) {
		t.Fatalf("second delete: got %v, want ErrAlreadyDeleted", err)
	}

	msgs, err := s.Conversation(alice.ID, bob.ID, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("the row must survive the delete, got %d messages", len(msgs))
	}
	got := msgs[0]
	if got.DeletedAt == 0 {
		t.Fatal("deleted message should read back with DeletedAt set")
	}
	if got.Body != "" || got.ImageFile != "" || got.ThumbFile != "" || got.MediaKind != "" {
		t.Fatalf("content should be cleared, got %+v", got)
	}
}

func TestDeleteGroupMessageAuthorOnly(t *testing.T) {
	s := newTestStore(t)
	alice := mustUser(t, s, "alice")
	mallory := mustUser(t, s, "mallory")
	g, err := s.CreateGroup(alice.ID, "crew")
	if err != nil {
		t.Fatal(err)
	}
	m, err := s.AddGroupMessage(GroupMessage{GroupID: g.ID, SenderID: alice.ID, Body: "hello"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.DeleteGroupMessage(mallory.ID, m.ID); !errors.Is(err, ErrNotSender) {
		t.Fatalf("another member deleting: got %v, want ErrNotSender", err)
	}
	if _, err := s.DeleteGroupMessage(alice.ID, m.ID); err != nil {
		t.Fatalf("author deleting: %v", err)
	}
	gms, err := s.GroupConversation(g.ID, 0, 50)
	if err != nil || len(gms) != 1 {
		t.Fatalf("GroupConversation: %v (%d)", err, len(gms))
	}
	if gms[0].DeletedAt == 0 || gms[0].Body != "" {
		t.Fatalf("group message not soft-deleted: %+v", gms[0])
	}
}
