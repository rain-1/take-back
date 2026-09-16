const B = 'http://127.0.0.1:19290', WS = 'ws://127.0.0.1:19290/api/events';
const people = {};
// Log in as accounts the authorization run created (sign-ups are rate-limited).
async function login(alias, nick) {
  const res = await fetch(B + '/api/login', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  people[alias] = { cookie: res.headers.get('set-cookie').split(';')[0] };
}
async function call(who, method, path, body) {
  const res = await fetch(B + path, { method, headers: { Cookie: people[who].cookie, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  return res.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await login('boss', 'owner'); await login('pal', 'member'); await login('nosy', 'stranger');
const sv = await call('boss', 'POST', '/api/servers', { name: 'Live' });
const inv = await call('boss', 'POST', '/api/servers/invites', { server: sv.id });
await call('pal', 'POST', '/api/invites/join', { code: inv.code });
const text = (await call('boss', 'GET', `/api/servers/channels?server=${sv.id}`)).find((c) => c.kind === 'text');

// pal listens as a member; nosy listens too but is NOT in the server.
const got = { pal: [], nosy: [] };
for (const who of ['pal', 'nosy']) {
  const ws = new WebSocket(WS, { headers: { Cookie: people[who].cookie } });
  ws.onmessage = (e) => { const ev = JSON.parse(e.data); if (!['hello', 'presence', 'ping'].includes(ev.type)) got[who].push(ev.type); };
  await new Promise((r) => (ws.onopen = r));
}
await sleep(300);
const m = await call('boss', 'POST', '/api/channels/messages', { channel: text.id, body: 'live?' });
await call('boss', 'POST', '/api/messages/edit', { id: m.id, scope: 'channel', body: 'live!' });
await call('boss', 'POST', '/api/reactions', { scope: 'channel', messageId: m.id, emoji: '🔥', add: true });
await call('boss', 'POST', '/api/servers/channels', { server: sv.id, name: 'new-channel', kind: 'text' });
await call('boss', 'POST', '/api/messages/delete', { id: m.id, scope: 'channel' });
await sleep(800);
const want = ['channel_message', 'channel_message_edited', 'reaction', 'server_update', 'message_deleted'];
const palOk = want.every((t) => got.pal.includes(t));
console.log(`${palOk ? '✓' : '✗'} member received: ${got.pal.join(', ')}`);
console.log(`${got.nosy.length === 0 ? '✓' : '✗'} non-member received nothing: [${got.nosy.join(', ')}]`);
process.exit(palOk && got.nosy.length === 0 ? 0 : 1);
