// Web-client / API hardening checks (audit, 2026-09).
//
// Each case is a thing an attacker could do before the fix it guards:
//   - drive a state-changing POST from a page on another origin, using the
//     victim's ambient session cookie (SameSite=Lax lets a sibling subdomain
//     through, and a JSON body sent as text/plain needs no CORS preflight);
//   - store an unbounded message body or group name;
//   - frame the app, or leak an invite code through the Referer.
//
// Run against a fresh server with -open-registration (registers 2 accounts).
// BASE lets this run against a server on another port without editing the file.
const B = process.env.BASE || 'http://127.0.0.1:19290';
const EVIL = 'https://evil.example';

let failures = 0;
function check(ok, what) {
  console.log(`${ok ? '✓' : '✗'} ${what}`);
  if (!ok) failures++;
}

async function register(nick) {
  const res = await fetch(B + '/api/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nick, password: 'pw123456' }),
  });
  if (!res.ok) throw new Error(`register ${nick}: ${res.status} ${await res.text()}`);
  return res.headers.get('set-cookie').split(';')[0];
}
async function post(cookie, path, body, extra = {}) {
  const res = await fetch(B + path, {
    method: 'POST',
    headers: { Cookie: cookie, 'Content-Type': 'application/json', ...extra },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

const suffix = Math.random().toString(36).slice(2, 7);
const alice = await register('sec-a-' + suffix);
const bob = await register('sec-b-' + suffix);
const me = (await (await fetch(B + '/api/me', { headers: { Cookie: bob } })).json());
await post(alice, '/api/friends/request', { nick: me.nick });
const aliceMe = await (await fetch(B + '/api/me', { headers: { Cookie: alice } })).json();
await post(bob, '/api/friends/respond', { userId: aliceMe.id, accept: true });

// ---- cross-origin state change ----
const csrf = await post(alice, '/api/messages', { with: me.id, body: 'csrf' }, { Origin: EVIL });
check(csrf.status === 403, `cross-origin POST /api/messages refused (got ${csrf.status})`);

const csrfRemove = await post(alice, '/api/friends/remove', { userId: me.id }, { Origin: EVIL });
check(csrfRemove.status === 403, `cross-origin POST /api/friends/remove refused (got ${csrfRemove.status})`);

// A multipart upload doesn't go through the JSON decoder, so check one too.
const form = new FormData();
form.set('image', new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])]), 'x.png');
const up = await fetch(B + '/api/me/avatar', {
  method: 'POST', headers: { Cookie: alice, Origin: EVIL }, body: form,
});
check(up.status === 403, `cross-origin multipart upload refused (got ${up.status})`);

// ...and the app's own origin must still work, as must a client that sends no
// Origin at all (the tb CLI, the Android app).
const same = await post(alice, '/api/messages', { with: me.id, body: 'hello' }, { Origin: B });
check(same.status === 200, `same-origin POST still works (got ${same.status})`);
const noOrigin = await post(alice, '/api/messages', { with: me.id, body: 'from the CLI' });
check(noOrigin.status === 200, `POST with no Origin still works (got ${noOrigin.status})`);

// ---- size bounds ----
const huge = await post(alice, '/api/messages', { with: me.id, body: 'A'.repeat(900_000) });
check(huge.status === 413, `900 KB message body rejected (got ${huge.status})`);

const hugeName = await post(alice, '/api/groups', { name: 'G'.repeat(2000) });
check(hugeName.status === 400, `2000-character group name rejected (got ${hugeName.status})`);
const okName = await post(alice, '/api/groups', { name: 'book club' });
check(okName.status === 200, `an ordinary group name is still accepted (got ${okName.status})`);

// ---- headers on the app's own pages ----
const page = await fetch(B + '/');
check(page.headers.get('x-frame-options') === 'DENY'
  && (page.headers.get('content-security-policy') || '').includes("frame-ancestors 'none'"),
  'the app page refuses to be framed');
check(page.headers.get('referrer-policy') === 'no-referrer',
  'the app page sends no Referer, so an invite code in the URL cannot leak');

process.exit(failures === 0 ? 0 : 1);
