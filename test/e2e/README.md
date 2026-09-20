# End-to-end API checks

Run against a local server started with `-open-registration` (web on :19290):

```sh
go run ./cmd/server -addr 127.0.0.1:19291 -db /tmp/t.db -media /tmp/media -open-registration &
go run ./cmd/web -addr 127.0.0.1:19290 -backend http://127.0.0.1:19291 &
node test/e2e/servers-authz.mjs    # who may do what: owner / member / other server's owner / stranger
node test/e2e/servers-events.mjs   # live events reach members, and only members
node test/e2e/servers-voice.mjs    # voice channels: who may join, activity, leaving (registers 3 accounts)
node test/e2e/calls.mjs            # a call in a conversation: waiting, missed, declined, ended, dead links
```

`servers-events.mjs` logs in as accounts `servers-authz.mjs` creates, because
sign-ups are rate-limited to 5 per 12 minutes per IP — run the authz check first
on a fresh database. The voice and UI checks register their own accounts (3 and
2), so run those two together after restarting the server.

The UI flow drives two real browsers (needs Chrome and `puppeteer-core`), on a
fresh database since it registers `alice` and `bob`:

```sh
PUPPETEER=/path/to/node_modules/puppeteer-core node test/e2e/servers-ui.cjs
PUPPETEER=... node test/e2e/call-ui.cjs     # pictures in calls, video in voice, the picture viewer
PUPPETEER=... node test/e2e/calls-ui.cjs    # the incoming-call banner, declining, an ended call
```

`calls.mjs` and `calls-ui.cjs` wind calls up after a grace period; start the
server with `TB_CALL_GRACE_MS=2000` and pass `GRACE_MS=3500` so they don't wait
twenty seconds each time. `call-ui.cjs` takes `RESTART_API=<script>` to check a
restart mid-call doesn't freeze anyone in a voice channel.
