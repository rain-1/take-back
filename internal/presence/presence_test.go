package presence

import (
	"sort"
	"testing"
)

// staticFriends returns a FriendLookup backed by a fixed adjacency map.
func staticFriends(graph map[int64][]int64) FriendLookup {
	return func(id int64) ([]int64, error) { return graph[id], nil }
}

func TestOnlineReflectsConnections(t *testing.T) {
	h := NewHub(staticFriends(nil))
	c := &conn{send: make(chan Event, 1)}

	if h.Online(1) {
		t.Fatal("should start offline")
	}
	if !h.add(1, c) {
		t.Fatal("first connection should report firstConn=true")
	}
	if !h.Online(1) {
		t.Fatal("should be online after add")
	}
	if !h.remove(1, c) {
		t.Fatal("removing the only connection should report lastConn=true")
	}
	if h.Online(1) {
		t.Fatal("should be offline after remove")
	}
}

func TestMultipleConnectionsStayOnline(t *testing.T) {
	h := NewHub(staticFriends(nil))
	c1 := &conn{send: make(chan Event, 1)}
	c2 := &conn{send: make(chan Event, 1)}

	if !h.add(1, c1) {
		t.Fatal("first add should be firstConn")
	}
	if h.add(1, c2) {
		t.Fatal("second add should NOT be firstConn")
	}
	if h.remove(1, c1) {
		t.Fatal("removing one of two should not be lastConn")
	}
	if !h.Online(1) {
		t.Fatal("still online with one connection left")
	}
	if !h.remove(1, c2) {
		t.Fatal("removing the last should be lastConn")
	}
}

func TestOnlineFriends(t *testing.T) {
	// 1 is friends with 2 and 3; only 2 is connected.
	h := NewHub(staticFriends(map[int64][]int64{1: {2, 3}}))
	h.add(2, &conn{send: make(chan Event, 1)})

	got := h.onlineFriends(1)
	sort.Slice(got, func(i, j int) bool { return got[i] < got[j] })
	if len(got) != 1 || got[0] != 2 {
		t.Fatalf("onlineFriends = %v, want [2]", got)
	}
}

func TestNotifyMessageDelivered(t *testing.T) {
	h := NewHub(staticFriends(nil))
	c := &conn{send: make(chan Event, 1)}
	h.add(5, c)

	h.NotifyMessage(5, []byte(`{"body":"hi"}`))

	select {
	case ev := <-c.send:
		if ev.Type != "message" || string(ev.Message) != `{"body":"hi"}` {
			t.Fatalf("unexpected event: %+v", ev)
		}
	default:
		t.Fatal("expected a message event on the connection")
	}
}

func TestNotifyMessageDropsWhenBufferFull(t *testing.T) {
	// A slow client with a full buffer must not block the sender.
	h := NewHub(staticFriends(nil))
	c := &conn{send: make(chan Event)} // unbuffered, no reader
	h.add(7, c)
	done := make(chan struct{})
	go func() { h.NotifyMessage(7, []byte(`{}`)); close(done) }()
	<-done // if sendTo blocked, this would deadlock and the test would time out
}
