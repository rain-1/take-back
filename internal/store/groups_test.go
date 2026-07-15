package store

import "testing"

func TestGroupCreateAndMembership(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "owner")
	other := mustUser(t, s, "other")
	mustUser(t, s, "target")

	g, err := s.CreateGroup(owner.ID, "Crew")
	if err != nil {
		t.Fatalf("CreateGroup: %v", err)
	}
	if g.CallCode == "" || g.MemberCount != 1 || g.OwnerID != owner.ID {
		t.Fatalf("unexpected group: %+v", g)
	}
	if !s.IsMember(g.ID, owner.ID) {
		t.Fatal("owner should be a member")
	}
	if s.IsMember(g.ID, other.ID) {
		t.Fatal("non-added user should not be a member")
	}

	// Add by nick.
	added, err := s.AddMember(g.ID, "target")
	if err != nil {
		t.Fatalf("AddMember: %v", err)
	}
	if !s.IsMember(g.ID, added.ID) {
		t.Fatal("added user should be a member")
	}

	// Adding again is idempotent (INSERT OR IGNORE).
	if _, err := s.AddMember(g.ID, "target"); err != nil {
		t.Fatalf("re-add: %v", err)
	}
	got, err := s.GroupByID(g.ID)
	if err != nil || got.MemberCount != 2 {
		t.Fatalf("member count = %d (err %v), want 2", got.MemberCount, err)
	}

	ids, err := s.GroupMemberIDs(g.ID)
	if err != nil || len(ids) != 2 {
		t.Fatalf("GroupMemberIDs = %v (err %v)", ids, err)
	}

	// Leaving drops membership.
	if err := s.RemoveMember(g.ID, added.ID); err != nil {
		t.Fatalf("RemoveMember: %v", err)
	}
	if s.IsMember(g.ID, added.ID) {
		t.Fatal("removed user should not be a member")
	}
}

func TestGroupsForUserAndMessages(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "ga")
	b := mustUser(t, s, "gb")

	g1, _ := s.CreateGroup(a.ID, "One")
	g2, _ := s.CreateGroup(a.ID, "Two")
	if _, err := s.AddMember(g1.ID, "gb"); err != nil {
		t.Fatal(err)
	}

	// a is in both; b only in g1.
	ag, _ := s.GroupsForUser(a.ID)
	if len(ag) != 2 {
		t.Fatalf("a groups = %d, want 2", len(ag))
	}
	bg, _ := s.GroupsForUser(b.ID)
	if len(bg) != 1 || bg[0].ID != g1.ID {
		t.Fatalf("b groups = %+v, want only g1", bg)
	}
	_ = g2

	// Messages are ordered and scoped to their group.
	for i := 0; i < 3; i++ {
		if _, err := s.AddGroupMessage(GroupMessage{GroupID: g1.ID, SenderID: a.ID, Body: string(rune('A' + i))}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.AddGroupMessage(GroupMessage{GroupID: g2.ID, SenderID: a.ID, Body: "other"}); err != nil {
		t.Fatal(err)
	}
	msgs, err := s.GroupConversation(g1.ID, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 3 {
		t.Fatalf("g1 messages = %d, want 3", len(msgs))
	}
	for i := 1; i < len(msgs); i++ {
		if msgs[i-1].ID >= msgs[i].ID {
			t.Fatalf("not chronological: %+v", msgs)
		}
	}
}

func TestGroupByIDMissing(t *testing.T) {
	s := newTestStore(t)
	if _, err := s.GroupByID(999); err == nil {
		t.Fatal("expected error for missing group")
	}
}
