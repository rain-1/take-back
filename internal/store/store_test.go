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
