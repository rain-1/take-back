// Authorization matrix for servers over real HTTP, plus live events.
const B = 'http://127.0.0.1:19290';
const people = {};
let fails = 0, passes = 0;
async function call(who, method, path, body, form) {
  const headers = { Cookie: people[who].cookie };
  if (body) headers['Content-Type'] = 'application/json';
  const res = await fetch(B + path, { method, headers, body: form || (body ? JSON.stringify(body) : undefined) });
  let data = null; try { data = await res.json(); } catch {}
  return { status: res.status, data };
}
const expect = (label, got, want) => {
  const ok = Array.isArray(want) ? want.includes(got) : got === want;
  ok ? passes++ : fails++;
  console.log(`${ok ? '✓' : '✗'} ${label}: ${got}${ok ? '' : ` (wanted ${want})`}`);
};
async function register(nick) {
  const res = await fetch(B + '/api/register', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  people[nick] = { cookie: res.headers.get('set-cookie').split(';')[0], id: (await res.json()).id };
}
for (const n of ['owner', 'member', 'outsider', 'stranger']) await register(n);

// Setup: owner's server with member joined via invite; outsider has their own server.
const sv = (await call('owner', 'POST', '/api/servers', { name: 'Crew' })).data;
const other = (await call('outsider', 'POST', '/api/servers', { name: 'Elsewhere' })).data;
const inv = (await call('owner', 'POST', '/api/servers/invites', { server: sv.id })).data;
expect('invite link path', inv.path, `/?invite=${inv.code}`);
expect('stranger previews invite', (await call('stranger', 'GET', `/api/invites?code=${inv.code}`)).status, 200);
expect('member joins by invite', (await call('member', 'POST', '/api/invites/join', { code: inv.code })).status, 200);
const chans = (await call('owner', 'GET', `/api/servers/channels?server=${sv.id}`)).data;
const text = chans.find((c) => c.kind === 'text'), voice = chans.find((c) => c.kind === 'voice');
const otherText = (await call('outsider', 'GET', `/api/servers/channels?server=${other.id}`)).data.find((c) => c.kind === 'text');

console.log('\n— outsiders see nothing —');
for (const who of ['stranger', 'outsider']) {
  expect(`${who}: list channels`, (await call(who, 'GET', `/api/servers/channels?server=${sv.id}`)).status, 403);
  expect(`${who}: read messages`, (await call(who, 'GET', `/api/channels/messages?channel=${text.id}`)).status, 403);
  expect(`${who}: post message`, (await call(who, 'POST', '/api/channels/messages', { channel: text.id, body: 'hi' })).status, 403);
  expect(`${who}: members`, (await call(who, 'GET', `/api/servers/members?server=${sv.id}`)).status, 403);
  expect(`${who}: create invite`, (await call(who, 'POST', '/api/servers/invites', { server: sv.id })).status, 403);
}

console.log('\n— members chat, but can\'t administer —');
const msg = (await call('member', 'POST', '/api/channels/messages', { channel: text.id, body: 'hello crew' }));
expect('member: post message', msg.status, 200);
expect('member: read messages', (await call('member', 'GET', `/api/channels/messages?channel=${text.id}`)).status, 200);
expect('member: can invite others', (await call('member', 'POST', '/api/servers/invites', { server: sv.id })).status, 200);
expect('member: post to a VOICE channel', (await call('member', 'POST', '/api/channels/messages', { channel: voice.id, body: 'x' })).status, 400);
for (const [label, path, body] of [
  ['create channel', '/api/servers/channels', { server: sv.id, name: 'nope', kind: 'text' }],
  ['rename channel', '/api/channels/update', { channel: text.id, name: 'nope' }],
  ['delete channel', '/api/channels/delete', { channel: text.id }],
  ['rename server', '/api/servers/update', { server: sv.id, name: 'nope' }],
  ['revoke invite', '/api/servers/invites/revoke', { server: sv.id, code: inv.code }],
  ['delete server', '/api/servers/delete', { server: sv.id }],
]) expect(`member: ${label}`, (await call('member', 'POST', path, body)).status, 403);

console.log('\n— cross-server boundaries —');
const outsiderMsg = (await call('outsider', 'POST', '/api/channels/messages', { channel: otherText.id, body: 'private' })).data;
expect('member reacts to a message in a server they\'re not in', (await call('member', 'POST', '/api/reactions', { scope: 'channel', messageId: outsiderMsg.id, emoji: '👍', add: true })).status, 403);
expect('member replies quoting another server\'s message', (await call('member', 'POST', '/api/channels/messages', { channel: text.id, body: 're', replyTo: outsiderMsg.id })).status, 400);
expect('owner (admin elsewhere) deletes outsider\'s message', (await call('owner', 'POST', '/api/messages/delete', { id: outsiderMsg.id, scope: 'channel' })).status, 403);

console.log('\n— admins administer —');
expect('owner: create channel', (await call('owner', 'POST', '/api/servers/channels', { server: sv.id, name: 'memes', kind: 'text' })).status, 200);
expect('owner: rename server', (await call('owner', 'POST', '/api/servers/update', { server: sv.id, name: 'The Crew' })).status, 200);
expect('owner: bad channel kind', (await call('owner', 'POST', '/api/servers/channels', { server: sv.id, name: 'x', kind: 'video' })).status, 400);
expect('owner: empty server name', (await call('owner', 'POST', '/api/servers/update', { server: sv.id, name: '   ' })).status, 400);
expect('member edits own message', (await call('member', 'POST', '/api/messages/edit', { id: msg.data.id, scope: 'channel', body: 'hello crew!' })).status, 200);
expect('owner edits member\'s message', (await call('owner', 'POST', '/api/messages/edit', { id: msg.data.id, scope: 'channel', body: 'x' })).status, 403);
expect('member reacts in own server', (await call('member', 'POST', '/api/reactions', { scope: 'channel', messageId: msg.data.id, emoji: '🎉', add: true })).status, 200);
expect('owner moderates (deletes member\'s message)', (await call('owner', 'POST', '/api/messages/delete', { id: msg.data.id, scope: 'channel' })).status, 200);
expect('mark channel read', (await call('owner', 'POST', '/api/read', { kind: 'channel', id: text.id, lastId: msg.data.id })).status, 200);

console.log('\n— invites and leaving —');
expect('owner revokes invite', (await call('owner', 'POST', '/api/servers/invites/revoke', { server: sv.id, code: inv.code })).status, 200);
expect('stranger joins with revoked invite', (await call('stranger', 'POST', '/api/invites/join', { code: inv.code })).status, 404);
expect('owner tries to leave own server', (await call('owner', 'POST', '/api/servers/leave', { server: sv.id })).status, 409);
expect('member leaves', (await call('member', 'POST', '/api/servers/leave', { server: sv.id })).status, 200);
expect('member reads after leaving', (await call('member', 'GET', `/api/channels/messages?channel=${text.id}`)).status, 403);
expect('owner deletes server', (await call('owner', 'POST', '/api/servers/delete', { server: sv.id })).status, 200);
expect('server list after delete', (await call('owner', 'GET', '/api/servers')).data.length, 0);

console.log(`\n${passes} passed, ${fails} failed`);
process.exitCode = fails ? 1 : 0;
