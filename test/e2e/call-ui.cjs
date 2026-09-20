// Profile pictures on call tiles, turning the camera on inside a voice channel,
// and opening a picture in the app instead of a browser tab.
const puppeteer = require(process.env.PUPPETEER || 'puppeteer-core');
const B = process.env.WEB || 'http://127.0.0.1:19290';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra !== '' ? ' — ' + extra : ''}`); };

// A 1x1 PNG, enough to be stored and thumbnailed as a real image.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

async function register(nick) {
  const res = await fetch(B + '/api/register', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  const body = await res.json();
  if (!res.ok) throw new Error(`register ${nick}: ${body.error}`);
  return { nick, id: body.id, cookie: res.headers.get('set-cookie').split(';')[0] };
}
async function call(who, method, path, body) {
  const res = await fetch(B + path, { method, headers: { Cookie: who.cookie, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  let data = null; try { data = await res.json(); } catch {}
  return { status: res.status, data };
}
async function upload(who, path, fields) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  const res = await fetch(B + path, { method: 'POST', headers: { Cookie: who.cookie }, body: form });
  return { status: res.status, data: await res.json().catch(() => null) };
}

(async () => {
  const alice = await register('pic' + Math.random().toString(36).slice(2, 6));
  const bob = await register('pic' + Math.random().toString(36).slice(2, 6));
  // Both get a profile picture, so the tiles have something to show.
  for (const who of [alice, bob]) {
    const r = await upload(who, '/api/me/avatar', { image: new File([PNG], 'me.png', { type: 'image/png' }) });
    if (r.status !== 200) throw new Error('avatar upload failed: ' + JSON.stringify(r.data));
  }
  const sv = (await call(alice, 'POST', '/api/servers', { name: 'Pics' })).data;
  const inv = (await call(alice, 'POST', '/api/servers/invites', { server: sv.id })).data;
  await call(bob, 'POST', '/api/invites/join', { code: inv.code });
  const chans = (await call(alice, 'GET', `/api/servers/channels?server=${sv.id}`)).data;
  const text = chans.find((c) => c.kind === 'text');
  // A picture posted in the channel, to open in the app.
  const form = new FormData();
  form.append('channel', String(text.id));
  form.append('body', '');
  form.append('name', 'shot.png');
  form.append('file', new File([PNG], 'shot.png', { type: 'image/png' }));
  const posted = await fetch(B + '/api/channels/messages/media', { method: 'POST', headers: { Cookie: alice.cookie }, body: form });
  if (!posted.ok) throw new Error('image post failed');

  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  const errors = [];
  async function open(who) {
    const ctx = await browser.createBrowserContext();
    const page = await ctx.newPage();
    await page.setViewport({ width: 1400, height: 850 });
    page.on('pageerror', (e) => errors.push(`${who.nick}: ${e.message}`));
    const [name, value] = who.cookie.split('=');
    await page.setCookie({ name, value: value, url: B });
    await page.goto(B + '/', { waitUntil: 'networkidle2' });
    return page;
  }
  const A = await open(alice), Bp = await open(bob);
  const vis = (p, id) => p.evaluate((id) => { const e = document.getElementById(id); return !!e && !e.classList.contains('hidden'); }, id);

  // Open the server on both, then the voice channel on both.
  for (const p of [A, Bp]) {
    await p.evaluate(() => openServerView(serverState.values().next().value));
    await wait(900);
  }
  await A.evaluate(() => joinVoice(serverChannels.find((c) => c.kind === 'voice')));
  await wait(2500);
  await Bp.evaluate(() => joinVoice(serverChannels.find((c) => c.kind === 'voice')));
  await wait(5000);

  const avatarsOf = (p) => p.evaluate(() => [...document.querySelectorAll('.tbc-tile')].map((t) => {
    const av = t.querySelector('.tbc-avatar');
    return { text: av.textContent.trim(), img: (av.style.backgroundImage || '').includes('/media/') };
  }));
  check('2 tiles in the voice call', (await avatarsOf(Bp)).length === 2, JSON.stringify(await avatarsOf(Bp)));
  const serverRow = (p) => p.evaluate(() => document.querySelector('#servers .row')?.textContent || '');
  check("the server row shows people are in voice", (await serverRow(Bp)).includes('🔊'), await serverRow(Bp));
  check('own tile shows my picture', (await avatarsOf(Bp))[0].img, JSON.stringify(await avatarsOf(Bp)));
  check('the other tile shows their picture', (await avatarsOf(Bp)).every((a) => a.img), JSON.stringify(await avatarsOf(Bp)));

  // Camera inside a voice channel.
  const camBtn = (p) => p.evaluate(() => {
    const b = [...document.querySelectorAll('.tbc button')].find((x) => /Camera|video/i.test(x.textContent));
    return b ? { text: b.textContent.trim(), hidden: b.classList.contains('tbc-hidden'), disabled: b.disabled } : null;
  });
  check('voice call offers a video button', (await camBtn(A))?.text === '📷 Start video' && !(await camBtn(A)).hidden, JSON.stringify(await camBtn(A)));
  await A.evaluate(() => [...document.querySelectorAll('.tbc button')].find((x) => /Start video/.test(x.textContent)).click());
  await wait(6000);
  check('camera turns on mid-call', (await camBtn(A))?.text === '📷 Camera on', JSON.stringify(await camBtn(A)));
  const remoteVideo = await Bp.evaluate(() => [...document.querySelectorAll('.tbc-tile')]
    .filter((t) => t.dataset.tile !== 'local')
    .some((t) => { const v = t.querySelector('video'); return v && v.videoWidth > 0 && !t.classList.contains('novideo'); }));
  check('the other side receives the video', remoteVideo, String(remoteVideo));

  // A picture opens in the app.
  await Bp.evaluate(() => openChannelChat(serverChannels.find((c) => c.kind === 'text')));
  await wait(1200);
  check('picture has an "open in browser" link', await Bp.evaluate(() => !!document.querySelector('.shot .shot-out')));
  const pagesBefore = (await browser.pages()).length;
  await Bp.evaluate(() => document.querySelector('img.thumb[data-full]').click());
  await wait(600);
  check('clicking it opens the lightbox in place', await vis(Bp, 'lightbox') && (await browser.pages()).length === pagesBefore);
  check('lightbox shows the full picture', await Bp.evaluate(() => (document.getElementById('lightboxImg').src || '').includes('/media/')));
  await Bp.keyboard.press('Escape');
  await wait(400);
  check('Escape closes it', !(await vis(Bp, 'lightbox')));
  check('call still up after all that', await vis(A, 'callPane'));

  await A.screenshot({ path: (process.env.SHOTS || '.') + '/call-avatars.png' });
  console.log('page errors:', errors.length ? errors : 'none');
  await browser.close();
  console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
  process.exit(fails ? 1 : 0);
})();
