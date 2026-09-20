// Calls in the conversation, in two browsers: the banner when one comes in,
// what the message says while it's live, declining, and a dead link.
const puppeteer = require(process.env.PUPPETEER || 'puppeteer-core');
const B = process.env.WEB || 'http://127.0.0.1:19290';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra !== '' ? ' — ' + extra : ''}`); };
async function register(tag) {
  const nick = tag + Math.random().toString(36).slice(2, 6);
  const res = await fetch(B + '/api/register', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  const body = await res.json();
  if (!res.ok) throw new Error(body.error);
  return { nick, id: body.id, cookie: res.headers.get('set-cookie').split(';')[0] };
}
async function api(who, method, path, body) {
  const res = await fetch(B + path, { method, headers: { Cookie: who.cookie, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  return { status: res.status, data: await res.json().catch(() => null) };
}

(async () => {
  const alice = await register('caller'), bob = await register('callee');
  await api(alice, 'POST', '/api/friends/request', { nick: bob.nick });
  await api(bob, 'POST', '/api/friends/respond', { userId: alice.id, accept: true });

  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  const errors = [];
  async function open(who) {
    const ctx = await browser.createBrowserContext();
    const page = await ctx.newPage();
    await page.setViewport({ width: 1280, height: 800 });
    page.on('pageerror', (e) => errors.push(`${who.nick}: ${e.message}`));
    const [n, v] = who.cookie.split('=');
    await page.setCookie({ name: n, value: v, url: B });
    await page.goto(B + '/', { waitUntil: 'networkidle2' });
    return page;
  }
  const A = await open(alice), Bp = await open(bob);
  const vis = (p, id) => p.evaluate((id) => { const e = document.getElementById(id); return !!e && !e.classList.contains('hidden'); }, id);
  const cardText = (p) => p.evaluate(() => [...document.querySelectorAll('.msg[data-call] .callcard')].map((e) => e.textContent.trim()).pop() || '');
  const openFriendChat = (p, nick) => p.evaluate((nick) => {
    const f = [...friendState.values()].find((x) => x.user.nick === nick);
    return openChat(f.user);
  }, nick);

  await openFriendChat(A, bob.nick);
  await openFriendChat(Bp, alice.nick);
  await wait(800);

  // Alice starts a call from the chat.
  await A.evaluate(() => document.getElementById('callBtn').click());
  await wait(4000);
  check("the caller's own card says nobody has joined yet", /Calling|You're in this call/.test(await cardText(A)), await cardText(A));
  check('the other side gets a banner', await vis(Bp, 'incoming'), await Bp.evaluate(() => document.getElementById('incoming').textContent));
  check('the banner names the caller', (await Bp.evaluate(() => document.getElementById('incoming').textContent)).includes(alice.nick), await Bp.evaluate(() => document.getElementById('incoming').textContent));
  check('their card says someone is waiting', (await cardText(Bp)).includes('is waiting'), await cardText(Bp));

  // Bob joins from the banner.
  await Bp.evaluate(() => [...document.querySelectorAll('#incoming button')].find((b) => b.textContent === 'Join').click());
  await wait(5000);
  check('joining hides the banner', !(await vis(Bp, 'incoming')));
  check('both are in the call', await Bp.evaluate(() => document.querySelectorAll('.tbc-tile').length) === 2,
    String(await Bp.evaluate(() => document.querySelectorAll('.tbc-tile').length)));
  check("the caller's card now names who is in it", (await cardText(A)).includes(bob.nick) && /In this call with/.test(await cardText(A)), await cardText(A));

  // Everyone leaves: the call is over and the link dies.
  await A.evaluate(() => TBCall.leave());
  await Bp.evaluate(() => TBCall.leave());
  await wait(Number(process.env.GRACE_MS || 4000));
  check('the message says the call ended', /Call ended/.test(await cardText(A)), await cardText(A));
  check('and so does theirs', /Call ended/.test(await cardText(Bp)), await cardText(Bp));
  await A.evaluate(() => document.querySelector('.msg[data-call] .callcard button')?.click());
  await wait(1500);
  check('an ended call offers no way back in', !(await A.evaluate(() => !!document.querySelector('.msg[data-call] .callcard button'))));

  // A second call, declined.
  await A.evaluate(() => document.getElementById('callBtn').click());
  await wait(3500);
  check('a new call rings again', await vis(Bp, 'incoming'));
  await Bp.evaluate(() => [...document.querySelectorAll('#incoming button')].find((b) => b.textContent === 'Decline').click());
  await wait(2500);
  check('declining says so in the chat', /Declined by/.test(await cardText(A)), await cardText(A));
  check('the banner goes away', !(await vis(Bp, 'incoming')));
  check('and the caller is dropped out of the call', !(await vis(A, 'callPane')));

  console.log('page errors:', errors.length ? errors : 'none');
  await browser.close();
  console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
  process.exit(fails ? 1 : 0);
})();
