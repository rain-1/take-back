// Command tb is a small command-line client for take-back.
//
// It exists so a terminal-bound user (hello) can read and answer their messages
// without a browser:
//
//	tb login claude          # once; stores the session under ~/.config/take-back
//	tb inbox                 # conversations, newest first, with unread counts
//	tb read river            # print a conversation (and mark it read)
//	tb send river "hello"    # send a message
//	tb watch                 # live-tail incoming messages
//
// A conversation is named by a friend's nick, or by #group for a group chat.
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const defaultServer = "https://takeback.chain-of-thought.org"

// ---- config ----

// config lives in ~/.config/take-back/cli.json (0600). The password is kept so
// the CLI can silently re-login when its session expires; it's a personal tool
// talking to the user's own server.
type config struct {
	Server   string `json:"server"`
	Nick     string `json:"nick,omitempty"`
	Password string `json:"password,omitempty"`
	Session  string `json:"session,omitempty"`
}

func configPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = filepath.Join(os.Getenv("HOME"), ".config")
	}
	return filepath.Join(dir, "take-back", "cli.json")
}

func loadConfig() *config {
	c := &config{Server: defaultServer}
	data, err := os.ReadFile(configPath())
	if err == nil {
		_ = json.Unmarshal(data, c)
	}
	if c.Server == "" {
		c.Server = defaultServer
	}
	return c
}

func (c *config) save() error {
	if err := os.MkdirAll(filepath.Dir(configPath()), 0o700); err != nil {
		return err
	}
	data, _ := json.MarshalIndent(c, "", "  ")
	return os.WriteFile(configPath(), data, 0o600) // contains a credential
}

// ---- API plumbing ----

type client struct {
	cfg  *config
	http *http.Client
}

func newClient(cfg *config) *client {
	return &client{cfg: cfg, http: &http.Client{Timeout: 20 * time.Second}}
}

var errUnauthorized = errors.New("not logged in")

// do performs a request, retrying once after a silent re-login if the session
// has expired (sessions last 30 days, so this is rare but annoying when it hits).
func (c *client) do(method, path string, body any, out any) error {
	err := c.once(method, path, body, out)
	if errors.Is(err, errUnauthorized) && c.cfg.Password != "" {
		if lerr := c.login(c.cfg.Nick, c.cfg.Password); lerr == nil {
			return c.once(method, path, body, out)
		}
	}
	return err
}

func (c *client) once(method, path string, body any, out any) error {
	var buf *bytes.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		buf = bytes.NewReader(data)
	} else {
		buf = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, c.cfg.Server+path, buf)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.cfg.Session != "" {
		req.AddCookie(&http.Cookie{Name: "tb_session", Value: c.cfg.Session})
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return errUnauthorized
	}
	if resp.StatusCode >= 300 {
		var e struct {
			Error string `json:"error"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&e)
		if e.Error == "" {
			e.Error = resp.Status
		}
		return errors.New(e.Error)
	}
	if out != nil {
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return nil
}

func (c *client) login(nick, password string) error {
	req, _ := http.NewRequest("POST", c.cfg.Server+"/api/login",
		strings.NewReader(fmt.Sprintf(`{"nick":%q,"password":%q}`, nick, password)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("login failed: %s", resp.Status)
	}
	for _, ck := range resp.Cookies() {
		if ck.Name == "tb_session" {
			c.cfg.Session = ck.Value
		}
	}
	if c.cfg.Session == "" {
		return errors.New("no session cookie returned")
	}
	c.cfg.Nick, c.cfg.Password = nick, password
	return c.cfg.save()
}

// ---- models (subset of the API) ----

type user struct {
	ID   int64  `json:"id"`
	Nick string `json:"nick"`
}

type friend struct {
	User         user   `json:"user"`
	Status       string `json:"status"`
	Direction    string `json:"direction"`
	Online       bool   `json:"online"`
	Unread       int    `json:"unread"`
	LastActivity int64  `json:"lastActivity"`
}

type group struct {
	ID           int64  `json:"id"`
	Name         string `json:"name"`
	Unread       int    `json:"unread"`
	LastActivity int64  `json:"lastActivity"`
}

type message struct {
	ID          int64  `json:"id"`
	SenderID    int64  `json:"senderId"`
	RecipientID int64  `json:"recipientId"`
	GroupID     int64  `json:"groupId"`
	Body        string `json:"body"`
	ImageURL    string `json:"imageUrl"`
	Created     int64  `json:"created"`
	EditedAt    int64  `json:"editedAt"`
}

// convo is a DM or a group, unified for listing.
type convo struct {
	name     string // "river" or "#Weekend Crew"
	id       int64  // peer id or group id
	isGroup  bool
	unread   int
	online   bool
	lastSeen int64
}

func (c *client) convos() ([]convo, error) {
	var fs []friend
	if err := c.do("GET", "/api/friends", nil, &fs); err != nil {
		return nil, err
	}
	var gs []group
	if err := c.do("GET", "/api/groups", nil, &gs); err != nil {
		return nil, err
	}
	var out []convo
	for _, f := range fs {
		if f.Status != "accepted" {
			continue
		}
		out = append(out, convo{
			name: f.User.Nick, id: f.User.ID, unread: f.Unread,
			online: f.Online, lastSeen: f.LastActivity,
		})
	}
	for _, g := range gs {
		out = append(out, convo{
			name: "#" + g.Name, id: g.ID, isGroup: true,
			unread: g.Unread, lastSeen: g.LastActivity,
		})
	}
	// Newest first — same ordering as the apps.
	sort.Slice(out, func(i, j int) bool { return out[i].lastSeen > out[j].lastSeen })
	return out, nil
}

// find resolves a conversation name typed on the command line.
func (c *client) find(name string) (convo, error) {
	all, err := c.convos()
	if err != nil {
		return convo{}, err
	}
	for _, cv := range all {
		if strings.EqualFold(cv.name, name) || strings.EqualFold(strings.TrimPrefix(cv.name, "#"), strings.TrimPrefix(name, "#")) {
			return cv, nil
		}
	}
	return convo{}, fmt.Errorf("no conversation called %q (try `tb inbox`)", name)
}

func (c *client) history(cv convo, limit int) ([]message, error) {
	var msgs []message
	var path string
	if cv.isGroup {
		path = fmt.Sprintf("/api/groups/messages?group=%d", cv.id)
	} else {
		path = fmt.Sprintf("/api/messages?with=%d", cv.id)
	}
	if err := c.do("GET", path, nil, &msgs); err != nil {
		return nil, err
	}
	if limit > 0 && len(msgs) > limit {
		msgs = msgs[len(msgs)-limit:]
	}
	return msgs, nil
}

func (c *client) markRead(cv convo, msgs []message) {
	if len(msgs) == 0 {
		return
	}
	kind := "dm"
	if cv.isGroup {
		kind = "group"
	}
	_ = c.do("POST", "/api/read", map[string]any{
		"kind": kind, "id": cv.id, "lastId": msgs[len(msgs)-1].ID,
	}, nil)
}

// nicks maps user ids to names, for showing who said what in groups.
func (c *client) nicks(cv convo) map[int64]string {
	out := map[int64]string{}
	if cv.isGroup {
		var members []user
		if err := c.do("GET", fmt.Sprintf("/api/groups/members?group=%d", cv.id), nil, &members); err == nil {
			for _, m := range members {
				out[m.ID] = m.Nick
			}
		}
		return out
	}
	out[cv.id] = cv.name
	return out
}

// ---- output ----

const (
	dim   = "\033[2m"
	bold  = "\033[1m"
	green = "\033[32m"
	blue  = "\033[34m"
	reset = "\033[0m"
)

func ago(ts int64) string {
	if ts == 0 {
		return "never"
	}
	d := time.Since(time.Unix(ts, 0))
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(d.Hours()/24))
	}
}

func printMessage(m message, who string, me int64) {
	name := who
	color := blue
	if m.SenderID == me {
		name, color = "you", dim
	}
	ts := time.Unix(m.Created, 0).Format("15:04")
	body := m.Body
	if m.ImageURL != "" {
		if body != "" {
			body += " "
		}
		body += "[image: " + m.ImageURL + "]"
	}
	edited := ""
	if m.EditedAt != 0 {
		edited = dim + " (edited)" + reset
	}
	fmt.Printf("%s%s%s %s%s%s  %s%s\n", dim, ts, reset, color+bold, name, reset, body, edited)
}

// ---- commands ----

func cmdLogin(c *client, args []string) error {
	fs := flag.NewFlagSet("login", flag.ExitOnError)
	server := fs.String("server", c.cfg.Server, "server base URL")
	password := fs.String("password", "", "password (prompted if omitted)")
	register := fs.Bool("register", false, "create the account instead of logging in")
	_ = fs.Parse(args)
	if fs.NArg() < 1 {
		return errors.New("usage: tb login [-register] [-password PW] <nick>")
	}
	nick := fs.Arg(0)
	c.cfg.Server = *server

	pw := *password
	if pw == "" {
		fmt.Print("password: ")
		line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		pw = strings.TrimSpace(line)
	}
	if *register {
		if err := c.once("POST", "/api/register", map[string]string{"nick": nick, "password": pw}, nil); err != nil {
			return fmt.Errorf("register: %w", err)
		}
	}
	if err := c.login(nick, pw); err != nil {
		return err
	}
	fmt.Printf("logged in as %s%s%s on %s\n", bold, nick, reset, c.cfg.Server)
	return nil
}

func cmdInbox(c *client, args []string) error {
	all, err := c.convos()
	if err != nil {
		return err
	}
	if len(all) == 0 {
		fmt.Println("no conversations yet — add a friend in the app first")
		return nil
	}
	var me user
	if err := c.do("GET", "/api/me", nil, &me); err != nil {
		return err
	}
	for _, cv := range all {
		badge := "   "
		if cv.unread > 0 {
			badge = fmt.Sprintf("%s%2d%s ", green+bold, cv.unread, reset)
		}
		dot := " "
		if cv.online {
			dot = green + "•" + reset
		}
		// Show the newest line so `tb inbox` alone is often enough.
		preview := ""
		if msgs, err := c.history(cv, 1); err == nil && len(msgs) > 0 {
			m := msgs[len(msgs)-1]
			who := ""
			if m.SenderID == me.ID {
				who = "you: "
			}
			body := m.Body
			if body == "" && m.ImageURL != "" {
				body = "[image]"
			}
			body = strings.ReplaceAll(body, "\n", " ")
			if len(body) > 60 {
				body = body[:57] + "…"
			}
			preview = dim + who + body + reset
		}
		fmt.Printf("%s%s %s%-16s%s %s%8s%s  %s\n",
			badge, dot, bold, cv.name, reset, dim, ago(cv.lastSeen), reset, preview)
	}
	return nil
}

func cmdRead(c *client, args []string) error {
	fs := flag.NewFlagSet("read", flag.ExitOnError)
	n := fs.Int("n", 20, "how many messages to show")
	fs.Parse(args)
	if fs.NArg() < 1 {
		return errors.New("usage: tb read [-n N] <nick|#group>")
	}
	cv, err := c.find(fs.Arg(0))
	if err != nil {
		return err
	}
	msgs, err := c.history(cv, *n)
	if err != nil {
		return err
	}
	var me user
	_ = c.do("GET", "/api/me", nil, &me)
	names := c.nicks(cv)

	fmt.Printf("%s%s%s\n", bold, cv.name, reset)
	for _, m := range msgs {
		who := names[m.SenderID]
		if who == "" {
			who = "peer"
		}
		printMessage(m, who, me.ID)
	}
	c.markRead(cv, msgs) // reading it in the terminal counts as reading it
	return nil
}

func cmdSend(c *client, args []string) error {
	if len(args) < 2 {
		return errors.New(`usage: tb send <nick|#group> <message...>`)
	}
	cv, err := c.find(args[0])
	if err != nil {
		return err
	}
	body := strings.Join(args[1:], " ")
	var path string
	var payload map[string]any
	if cv.isGroup {
		path, payload = "/api/groups/messages", map[string]any{"group": cv.id, "body": body}
	} else {
		path, payload = "/api/messages", map[string]any{"with": cv.id, "body": body}
	}
	var m message
	if err := c.do("POST", path, payload, &m); err != nil {
		return err
	}
	fmt.Printf("%s→ %s%s %s\n", dim, cv.name, reset, body)
	return nil
}

// cmdAdd sends a friend request; cmdAccept answers a pending one.
func cmdAdd(c *client, args []string) error {
	if len(args) < 1 {
		return errors.New("usage: tb add <nick>")
	}
	if err := c.do("POST", "/api/friends/request", map[string]string{"nick": args[0]}, nil); err != nil {
		return err
	}
	fmt.Printf("friend request sent to %s%s%s\n", bold, args[0], reset)
	return nil
}

func cmdAccept(c *client, args []string) error {
	var fs []friend
	if err := c.do("GET", "/api/friends", nil, &fs); err != nil {
		return err
	}
	pending := []friend{}
	for _, f := range fs {
		if f.Status == "pending" && f.Direction == "incoming" {
			pending = append(pending, f)
		}
	}
	if len(pending) == 0 {
		fmt.Println("no incoming friend requests")
		return nil
	}
	for _, f := range pending {
		if len(args) > 0 && !strings.EqualFold(f.User.Nick, args[0]) {
			continue
		}
		if err := c.do("POST", "/api/friends/respond",
			map[string]any{"userId": f.User.ID, "accept": true}, nil); err != nil {
			return err
		}
		fmt.Printf("accepted %s%s%s\n", bold, f.User.Nick, reset)
	}
	return nil
}

// cmdWatch live-tails incoming messages over the same events socket the apps use.
func cmdWatch(c *client, args []string) error {
	u, err := url.Parse(c.cfg.Server)
	if err != nil {
		return err
	}
	scheme := "wss"
	if u.Scheme == "http" {
		scheme = "ws"
	}
	wsURL := fmt.Sprintf("%s://%s/api/events", scheme, u.Host)

	hdr := http.Header{}
	hdr.Set("Cookie", "tb_session="+c.cfg.Session)
	conn, resp, err := websocket.DefaultDialer.Dial(wsURL, hdr)
	if err != nil {
		if resp != nil && resp.StatusCode == http.StatusUnauthorized {
			return errors.New("session expired — run `tb login`")
		}
		return err
	}
	defer conn.Close()

	var me user
	_ = c.do("GET", "/api/me", nil, &me)
	fmt.Printf("%swatching as %s — ctrl-c to stop%s\n", dim, c.cfg.Nick, reset)

	for {
		var ev struct {
			Type    string          `json:"type"`
			UserID  int64           `json:"userId"`
			Nick    string          `json:"nick"`
			Online  bool            `json:"online"`
			Message json.RawMessage `json:"message"`
		}
		if err := conn.ReadJSON(&ev); err != nil {
			return err
		}
		switch ev.Type {
		case "message", "group_message":
			var m message
			if err := json.Unmarshal(ev.Message, &m); err != nil {
				continue
			}
			who := fmt.Sprintf("user %d", m.SenderID)
			if all, err := c.convos(); err == nil {
				for _, cv := range all {
					if !cv.isGroup && cv.id == m.SenderID {
						who = cv.name
					}
					if cv.isGroup && cv.id == m.GroupID {
						who = cv.name
					}
				}
			}
			printMessage(m, who, me.ID)
		case "friend_request":
			fmt.Printf("%s* %s sent you a friend request%s\n", green, ev.Nick, reset)
		case "presence":
			// Too noisy to print by default.
		}
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `tb — take-back from the terminal

  tb login [-register] [-server URL] <nick>   log in (stores session)
  tb inbox                                    conversations, newest first
  tb read [-n N] <nick|#group>                show a conversation, mark read
  tb send <nick|#group> <message...>          send a message
  tb add <nick>                               send a friend request
  tb accept [nick]                            accept incoming friend request(s)
  tb watch                                    live-tail incoming messages
  tb whoami                                   show the logged-in account

Run with no command for the inbox.
`)
}

func main() {
	cfg := loadConfig()
	c := newClient(cfg)

	args := os.Args[1:]
	cmd := "inbox"
	if len(args) > 0 {
		cmd, args = args[0], args[1:]
	}

	var err error
	switch cmd {
	case "login":
		err = cmdLogin(c, args)
	case "inbox", "ls":
		err = cmdInbox(c, args)
	case "read":
		err = cmdRead(c, args)
	case "send":
		err = cmdSend(c, args)
	case "add":
		err = cmdAdd(c, args)
	case "accept":
		err = cmdAccept(c, args)
	case "watch":
		err = cmdWatch(c, args)
	case "whoami":
		var me user
		if err = c.do("GET", "/api/me", nil, &me); err == nil {
			fmt.Printf("%s (id %d) on %s\n", me.Nick, me.ID, cfg.Server)
		}
	case "-h", "--help", "help":
		usage()
		return
	default:
		usage()
		os.Exit(2)
	}

	if err != nil {
		if errors.Is(err, errUnauthorized) {
			fmt.Fprintln(os.Stderr, "not logged in — run `tb login <nick>`")
			os.Exit(1)
		}
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
