# Takeback idea feed

The `codex` Takeback account is a member of the `bugrep & featurereq` group.
The user service in `takeback-idea-feed.service.example` runs `tb watch -plain`,
filters that group, and appends each new message to
`~/.local/share/takeback-ideas/messages.jsonl`.

The relay service in `takeback-session-relay.service.example` reads new queue
records and sends them to one existing Codex thread with `codex queue`. It uses
`TAKEBACK_CODEX_THREAD` from the private file
`~/.config/take-back-codex/session.env`. Each forwarded message includes the
sender and group. `relay.offset` records the last delivered byte, so a relay
restart does not replay old messages. If `codex queue` fails, the relay retries
that message. The relay begins with new messages when first installed.

The account uses its own CLI config under
`~/.config/take-back-codex/take-back/cli.json` (`XDG_CONFIG_HOME` points to the
parent directory). Keep it private: it contains an active session. The old
`claude` CLI config under `~/.config/take-back` is separate.

To rebuild and restart the local services after changing the scripts or CLI:

```sh
mkdir -p ~/.local/bin ~/.config/systemd/user
go build -o ~/.local/bin/tb-codex ./cmd/tb
cp scripts/takeback-idea-feed.service.example ~/.config/systemd/user/takeback-idea-feed.service
cp scripts/takeback-session-relay.service.example ~/.config/systemd/user/takeback-session-relay.service
systemctl --user daemon-reload
systemctl --user enable --now takeback-idea-feed.service takeback-session-relay.service
```

Check both services with `systemctl --user status takeback-idea-feed.service
 takeback-session-relay.service`. The queue and service journals retain intake
and delivery evidence. The feed receives messages while connected; it does not
backfill earlier group history. On this host, systemd lingering is disabled, so
both services run while the `ubuntu` user manager is active. A root operator
can enable lingering if they must survive logout.
