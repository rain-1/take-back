// Voice channels and server activity over real sockets: who may join a voice
// channel's call, under what name, and whether the active list and voice
// occupancy follow people in and out.
const B = process.env.WEB || 'http://127.0.0.1:19290';
const WSB = B.replace(/^http/, 'ws');
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra !== '' ? ' — ' + extra : ''}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const people = {};
async function register(alias) {
  const nick = 'v' + alias + Math.random().toString(36).slice(2, 6);
  const res = await fetch(B + '/api/register', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  const body = await res.json();
  if (!res.ok) throw new Error(`register ${nick}: ${body.error}`);
  people[alias] = { nick, id: body.id, cookie: res.headers.get('set-cookie').split(';')[0] };
}
async function call(who, method, path, body) {
  const res = await fetch(B + path, { method, headers: { Cookie: people[who].cookie, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  let data = null; try { data = await res.json(); } catch {}
  return { status: res.status, data };
}
// open resolves to the socket once open, or null if the handshake is refused.
function open(url, headers = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(url, { headers });
    ws.msgs = [];
    ws.onmessage = (e) => ws.msgs.push(JSON.parse(e.data));
    ws.onopen = () => resolve(ws);
    ws.onerror = () => resolve(null);
  });
}
const closed = (ws) => new Promise((r) => { if (ws.readyState === 3) r(true); else ws.addEventListener('close', () => r(true)); setTimeout(() => r(false), 3000); });

for (const a of ['boss', 'pal', 'nosy']) await register(a);
const sv = (await call('boss', 'POST', '/api/servers', { name: 'Voice test' })).data;
const inv = (await call('boss', 'POST', '/api/servers/invites', { server: sv.id })).data;
await call('pal', 'POST', '/api/invites/join', { code: inv.code });
const voice = (await call('boss', 'GET', `/api/servers/channels?server=${sv.id}`)).data.find((c) => c.kind === 'voice');
check('members see the voice channel\'s call code', /^V\w{15}$/.test(voice.callCode || ''), voice.callCode);
const room = (nick) => `${WSB}/ws?room=${voice.callCode}&nick=${nick}`;
const cookie = (who, extra = {}) => ({ Cookie: people[who].cookie, ...extra });

console.log('\n— who may join a voice channel —');
let s;
check('no session: refused', (s = await open(room('x'))) === null); s?.close();
check('non-member: refused', (s = await open(room('x'), cookie('nosy'))) === null); s?.close();
check('member from another origin: refused', (s = await open(room('x'), cookie('pal', { Origin: 'https://evil.example' }))) === null); s?.close();
check('ordinary call code still needs no session', (s = await open(`${WSB}/ws?room=ABC123&nick=anyone`)) !== null); s?.close();

// Everyone watches events as boss.
const bossEvents = await open(`${WSB}/api/events`, cookie('boss'));
const lastActivity = () => bossEvents.msgs.filter((m) => m.type === 'server_active' && m.message.serverId === sv.id).map((m) => m.message).pop();

const palCall = await open(room('an-alias'), cookie('pal', { Origin: B }));
check('member from this site: admitted', palCall !== null);
await sleep(300);
let act = lastActivity();
check('voice seat broadcast to members', act && (act.voice[voice.id] || []).includes(people.pal.id), JSON.stringify(act));
check('in voice counts as active', act && act.active.includes(people.pal.id));

const bossCall = await open(room('whatever'), cookie('boss'));
await sleep(300);
const welcome = bossCall.msgs.find((m) => m.type === 'welcome');
check('voice name is the account nick, not the one asked for', welcome && welcome.peers.some((p) => p.nick === people.pal.nick), JSON.stringify(welcome && welcome.peers));

console.log('\n— viewing —');
const nosyEvents = await open(`${WSB}/api/events`, cookie('nosy'));
nosyEvents.send(JSON.stringify({ type: 'view', serverId: sv.id }));
const palEvents = await open(`${WSB}/api/events`, cookie('pal'));
bossEvents.send(JSON.stringify({ type: 'view', serverId: sv.id }));
await sleep(400);
act = (await call('boss', 'GET', `/api/servers/active?server=${sv.id}`)).data;
check('viewer is active', act.active.includes(people.boss.id), JSON.stringify(act.active));
check('non-member can\'t make themselves active by claiming to view', !act.active.includes(people.nosy.id));
check('non-member can\'t read activity', (await call('nosy', 'GET', `/api/servers/active?server=${sv.id}`)).status === 403);
check('non-member hears no activity', !nosyEvents.msgs.some((m) => m.type === 'server_active'));
bossCall.close();
await sleep(300);
act = lastActivity();
check('boss still active after hanging up (still viewing)', act.active.includes(people.boss.id) && !(act.voice[voice.id] || []).includes(people.boss.id), JSON.stringify(act));
bossEvents.send(JSON.stringify({ type: 'view', serverId: 0 }));
await sleep(300);
act = lastActivity();
check('stops being active when no longer viewing', !act.active.includes(people.boss.id), JSON.stringify(act.active));
check('snapshots carry increasing seq', bossEvents.msgs.filter((m) => m.type === 'server_active').map((m) => m.message.seq).every((v, i, a) => i === 0 || v >= a[i - 1]));

console.log('\n— leaving —');
palEvents.send(JSON.stringify({ type: 'view', serverId: sv.id }));
await sleep(300);
check('pal viewing and in voice', lastActivity().active.includes(people.pal.id));
await call('pal', 'POST', '/api/servers/leave', { server: sv.id });
check('leaving the server disconnects its voice call', await closed(palCall));
await sleep(300);
act = lastActivity();
check('and clears them from activity', !act.active.includes(people.pal.id) && !(act.voice[voice.id] || []).length, JSON.stringify(act));

const bossCall2 = await open(room('b'), cookie('boss'));
await sleep(200);
await call('boss', 'POST', '/api/channels/delete', { channel: voice.id });
check('deleting a voice channel disconnects whoever is in it', await closed(bossCall2));
check('a deleted voice channel\'s code no longer admits anyone special', (s = await open(room('x'))) !== null); s?.close();

for (const w of [bossEvents, nosyEvents, palEvents]) w.close();
console.log(fails ? `\n${fails} FAILED` : '\nALL PASSED');
process.exit(fails ? 1 : 0);
