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

	// Inviting by nick does NOT make them a member — they must accept.
	invited, err := s.InviteMember(g.ID, owner.ID, "target")
	if err != nil {
		t.Fatalf("InviteMember: %v", err)
	}
	if s.IsMember(g.ID, invited.ID) {
		t.Fatal("an invited user must not be a member until they accept")
	}
	pending, err := s.PendingInvites(invited.ID)
	if err != nil || len(pending) != 1 || pending[0].GroupID != g.ID {
		t.Fatalf("PendingInvites = %+v (err %v)", pending, err)
	}
	if pending[0].InvitedBy != "owner" {
		t.Fatalf("invite should name the inviter, got %q", pending[0].InvitedBy)
	}

	// Accepting joins them.
	if err := s.RespondInvite(g.ID, invited.ID, true); err != nil {
		t.Fatalf("RespondInvite: %v", err)
	}
	if !s.IsMember(g.ID, invited.ID) {
		t.Fatal("should be a member after accepting")
	}

	// Re-inviting someone who already joined must not demote them.
	if _, err := s.InviteMember(g.ID, owner.ID, "target"); err != nil {
		t.Fatalf("re-invite: %v", err)
	}
	if !s.IsMember(g.ID, invited.ID) {
		t.Fatal("re-inviting a joined member must not demote them to invited")
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
	if err := s.RemoveMember(g.ID, invited.ID); err != nil {
		t.Fatalf("RemoveMember: %v", err)
	}
	if s.IsMember(g.ID, invited.ID) {
		t.Fatal("removed user should not be a member")
	}
}

func TestGroupInviteDeclined(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "gowner")
	mustUser(t, s, "guest")

	g, _ := s.CreateGroup(owner.ID, "Crew")
	u, err := s.InviteMember(g.ID, owner.ID, "guest")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.RespondInvite(g.ID, u.ID, false); err != nil {
		t.Fatalf("decline: %v", err)
	}
	if s.IsMember(g.ID, u.ID) {
		t.Fatal("declining must not join the group")
	}
	if p, _ := s.PendingInvites(u.ID); len(p) != 0 {
		t.Fatalf("declined invite should be gone, got %+v", p)
	}
	// A declined invite can't be accepted after the fact.
	if err := s.RespondInvite(g.ID, u.ID, true); err == nil {
		t.Fatal("accepting a non-existent invite should fail")
	}
}

// An invited-but-not-joined user must not see the group at all.
func TestInvitedUserSeesNothing(t *testing.T) {
	s := newTestStore(t)
	owner := mustUser(t, s, "iowner")
	mustUser(t, s, "invitee")

	g, _ := s.CreateGroup(owner.ID, "Secret")
	u, _ := s.InviteMember(g.ID, owner.ID, "invitee")
	if _, err := s.AddGroupMessage(GroupMessage{GroupID: g.ID, SenderID: owner.ID, Body: "hi"}); err != nil {
		t.Fatal(err)
	}

	groups, _ := s.GroupsForUser(u.ID)
	if len(groups) != 0 {
		t.Fatalf("invited user should not list the group yet, got %+v", groups)
	}
	unread, _ := s.UnreadGroupCounts(u.ID)
	if len(unread) != 0 {
		t.Fatalf("invited user should have no unread for it, got %+v", unread)
	}
	// The owner still sees it, and the member count excludes the invitee.
	got, _ := s.GroupByID(g.ID)
	if got.MemberCount != 1 {
		t.Fatalf("member count = %d, want 1 (invitee not counted)", got.MemberCount)
	}
}

func TestGroupsForUserAndMessages(t *testing.T) {
	s := newTestStore(t)
	a := mustUser(t, s, "ga")
	b := mustUser(t, s, "gb")

	g1, _ := s.CreateGroup(a.ID, "One")
	g2, _ := s.CreateGroup(a.ID, "Two")
	if _, err := s.InviteMember(g1.ID, a.ID, "gb"); err != nil {
		t.Fatal(err)
	}
	if err := s.RespondInvite(g1.ID, b.ID, true); err != nil {
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
