# End-to-end API checks

Run against a local server started with `-open-registration` (web on :19290):

```sh
go run ./cmd/server -addr 127.0.0.1:19291 -db /tmp/t.db -media /tmp/media -open-registration &
go run ./cmd/web -addr 127.0.0.1:19290 -backend http://127.0.0.1:19291 &
node test/e2e/servers-authz.mjs    # who may do what: owner / member / other server's owner / stranger
node test/e2e/servers-events.mjs   # live events reach members, and only members
```

`servers-events.mjs` logs in as accounts `servers-authz.mjs` creates, because
sign-ups are rate-limited to 5 per 12 minutes per IP — run the authz check first
on a fresh database.

The UI flow drives two real browsers (needs Chrome and `puppeteer-core`), on a
fresh database since it registers `alice` and `bob`:

```sh
PUPPETEER=/path/to/node_modules/puppeteer-core node test/e2e/servers-ui.cjs
```
