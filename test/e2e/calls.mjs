// A call in a conversation: who's in it, missed, declined, ended, and the link
// dying once everyone has gone.
const B = process.env.WEB || 'http://127.0.0.1:19290';
const WSB = B.replace(/^http/, 'ws');
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra !== '' ? ' — ' + extra : ''}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const people = {};
async function register(alias) {
  const nick = 'c' + alias + Math.random().toString(36).slice(2, 6);
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
function join(code, who, nick = 'x') {
  return new Promise((resolve) => {
    const headers = who ? { Cookie: people[who].cookie, Origin: B } : {};
    const ws = new WebSocket(`${WSB}/ws?room=${code}&nick=${nick}`, { headers });
    ws.msgs = [];
    ws.onmessage = (e) => ws.msgs.push(JSON.parse(e.data));
    ws.onopen = () => resolve(ws);
    ws.onerror = () => resolve(null);
  });
}
const closed = (ws) => new Promise((r) => { if (!ws || ws.readyState === 3) return r(true); ws.addEventListener('close', () => r(true)); setTimeout(() => r(false), 3000); });
const stateOf = async (who, code) => ((await call(who, 'GET', `/api/calls?codes=${code}`)).data || [])[0];

await register('alice'); await register('bob'); await register('nosy');
await call('alice', 'POST', '/api/friends/request', { nick: people.bob.nick });
await call('bob', 'POST', '/api/friends/respond', { userId: people.alice.id, accept: true });

console.log('— a call someone is waiting in —');
const code = 'AB' + Math.random().toString(36).slice(2, 6).toUpperCase();
await call('alice', 'POST', '/api/messages', { with: people.bob.id, body: `📞 call:${code}` });
let st = await stateOf('bob', code);
check('the call is recorded from the message', st && st.code === code && st.outcome === '', JSON.stringify(st));
check('and names who is calling', st && st.callerNick === people.alice.nick, JSON.stringify(st));
check('nobody is in it yet', st && st.here === 0, JSON.stringify(st));
const aliceWs = await join(code, 'alice', 'alice');
await sleep(400);
st = await stateOf('bob', code);
check('the caller waiting shows as one person in it', st.here === 1 && st.participants.includes(people.alice.id), JSON.stringify(st));
check('it is not answered yet', st.answered === undefined || !st.answered, JSON.stringify(st));

const bobWs = await join(code, 'bob', 'bob');
await sleep(500);
st = await stateOf('alice', code);
check('someone else joining answers it', st.answered > 0 && st.here === 2, JSON.stringify(st));

console.log('\n— privacy —');
check("someone it isn't for can't see it", ((await call('nosy', 'GET', `/api/calls?codes=${code}`)).data || []).length === 0);
check("and can't decline it", (await call('nosy', 'POST', '/api/calls/decline', { code })).status === 403);

console.log('\n— it ends when the last person leaves —');
aliceWs.close(); bobWs.close();
await sleep(1000);
st = await stateOf('alice', code);
check('still live during the grace period', st.outcome === '', JSON.stringify(st));
await sleep(Number(process.env.GRACE_MS || 21000));
st = await stateOf('alice', code);
check('then it is over', st.outcome === 'ended', JSON.stringify(st));
check('and it lasted a sensible time', st.ended >= st.answered, JSON.stringify(st));
check('its link no longer works', (await join(code, 'bob', 'bob')) === null);

console.log('\n— a call nobody answered —');
const code2 = 'MS' + Math.random().toString(36).slice(2, 6).toUpperCase();
await call('alice', 'POST', '/api/messages', { with: people.bob.id, body: `📞 call:${code2}` });
const waiting = await join(code2, 'alice', 'alice');
await sleep(400);
waiting.close();
await sleep(Number(process.env.GRACE_MS || 21000));
st = await stateOf('bob', code2);
check('is missed once the caller gives up', st.outcome === 'missed', JSON.stringify(st));

console.log('\n— declining —');
const code3 = 'DC' + Math.random().toString(36).slice(2, 6).toUpperCase();
await call('alice', 'POST', '/api/messages', { with: people.bob.id, body: `📞 call:${code3}` });
const ringing = await join(code3, 'alice', 'alice');
await sleep(300);
check('bob can decline', (await call('bob', 'POST', '/api/calls/decline', { code: code3 })).status === 200);
st = await stateOf('alice', code3);
check('the caller sees it was declined, and by whom', st.outcome === 'declined' && st.declinedBy === people.bob.id, JSON.stringify(st));
check('a declined call cannot be joined', (await join(code3, 'bob', 'bob')) === null);
check('the caller is dropped from the declined call', await closed(ringing));

console.log('\n— a call nobody ever entered —');
const code4 = 'NV' + Math.random().toString(36).slice(2, 6).toUpperCase();
await call('alice', 'POST', '/api/messages', { with: people.bob.id, body: `📞 call:${code4}` });
st = await stateOf('bob', code4);
check('starts out live', st.outcome === '', JSON.stringify(st));
await sleep(Number(process.env.GRACE_MS || 21000) * 3);
st = await stateOf('bob', code4);
check('is missed once nobody has turned up at all', st.outcome === 'missed', JSON.stringify(st));

console.log('\n— a code nobody announced still works —');
const plain = await join('ZZ9TEST', null, 'guest');
check('an ordinary room is unaffected', plain !== null);
if (plain) plain.close();

console.log(fails ? `\n${fails} FAILED` : '\nALL PASSED');
process.exit(fails ? 1 : 0);
