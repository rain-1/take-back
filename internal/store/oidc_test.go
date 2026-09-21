package store

import (
	"errors"
	"testing"
)

func TestLinkAndFindByOIDCSub(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "river")

	if _, err := s.UserByOIDCSub("sub-river"); !errors.Is(err, ErrNoSuchUser) {
		t.Fatalf("unlinked sub should find nobody, got %v", err)
	}
	if err := s.LinkOIDCSub(u.ID, "sub-river"); err != nil {
		t.Fatalf("link: %v", err)
	}
	got, err := s.UserByOIDCSub("sub-river")
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if got.ID != u.ID || got.Nick != "river" {
		t.Fatalf("got %+v, want the river account", got)
	}
	// Linking the same identity again is how a second sign-in looks.
	if err := s.LinkOIDCSub(u.ID, "sub-river"); err != nil {
		t.Fatalf("relink same sub: %v", err)
	}
}

func TestOneIdentityPerAccount(t *testing.T) {
	s := newTestStore(t)
	river := mustUser(t, s, "river")
	etheri := mustUser(t, s, "etheri")

	if err := s.LinkOIDCSub(river.ID, "sub-1"); err != nil {
		t.Fatalf("link river: %v", err)
	}
	// The same provider identity must not reach a second account.
	if err := s.LinkOIDCSub(etheri.ID, "sub-1"); !errors.Is(err, ErrSubTaken) {
		t.Fatalf("stealing a sub should fail with ErrSubTaken, got %v", err)
	}
	// And a linked account must not be re-pointed at a different identity:
	// that is what a rebuilt provider handing out fresh subs would look like.
	if err := s.LinkOIDCSub(river.ID, "sub-2"); !errors.Is(err, ErrSubTaken) {
		t.Fatalf("re-pointing a linked account should fail, got %v", err)
	}
	u, err := s.UserByOIDCSub("sub-1")
	if err != nil || u.ID != river.ID {
		t.Fatalf("river should still own sub-1: %+v %v", u, err)
	}
	if _, err := s.UserByOIDCSub("sub-2"); !errors.Is(err, ErrNoSuchUser) {
		t.Fatalf("sub-2 should belong to nobody, got %v", err)
	}
}

func TestUnlinkedUserByNickIsHowAccountsAreClaimed(t *testing.T) {
	s := newTestStore(t)
	u := mustUser(t, s, "Claude")

	// Case-insensitively, as everywhere else nicks are matched.
	found, err := s.UnlinkedUserByNick("claude")
	if err != nil || found.ID != u.ID {
		t.Fatalf("expected to find the unclaimed account: %+v %v", found, err)
	}
	if err := s.LinkOIDCSub(u.ID, "sub-claude"); err != nil {
		t.Fatalf("link: %v", err)
	}
	// Once claimed it is no longer claimable — a second provider account with
	// the same username can't take it over.
	if _, err := s.UnlinkedUserByNick("claude"); !errors.Is(err, ErrNoSuchUser) {
		t.Fatalf("claimed account should not be claimable again, got %v", err)
	}
}

func TestCreateOIDCUserHasNoUsablePassword(t *testing.T) {
	s := newTestStore(t)
	u, err := s.CreateOIDCUser("newcomer", "sub-new")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	_, hash, err := s.UserByNick("newcomer")
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if hash != "" {
		t.Fatalf("provider accounts must not carry a password hash, got %q", hash)
	}
	if _, err := s.CreateOIDCUser("newcomer", "sub-other"); !errors.Is(err, ErrNickTaken) {
		t.Fatalf("nick should still be unique, got %v", err)
	}
	got, err := s.UserByOIDCSub("sub-new")
	if err != nil || got.ID != u.ID {
		t.Fatalf("round trip failed: %+v %v", got, err)
	}
}
