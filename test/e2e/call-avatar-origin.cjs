// A call peer cannot choose where your browser fetches their picture from.
//
// Everyone in a call announces an avatarUrl in their `state` signaling message,
// and a call room is joined by knowing its code — so the value is chosen by
// whoever is in the room, signed in or not, and a peer running its own build of
// the client can announce anything. It used to go straight into a CSS
// background-image, which made pointing it at a server you own a way to collect
// the IP address of everyone in the call the moment your tile appeared.
//
// Four browsers join one room: the victim, two peers announcing off-site
// avatars, and one announcing a real /media/ path (so the check also proves
// ordinary pictures still arrive).
const puppeteer = require(process.env.PUPPETEER || 'puppeteer-core');
const B = process.env.WEB || 'http://127.0.0.1:19290';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra !== '' ? ' — ' + extra : ''}`); };

const ROOM = 'AVTEST';
const BEACON = 'https://beacon.invalid/pixel.png';
const GOOD = '/media/nothing-here.jpg';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  const errors = [];
  async function open(nick) {
    const ctx = await browser.createBrowserContext();
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(`${nick}: ${e.message}`));
    await page.goto(B + '/call.html', { waitUntil: 'networkidle2' });
    return page;
  }
  // join mounts the call engine directly, which is how a peer announces an
  // avatar of its choosing — exactly what a modified client would do.
  async function join(page, nick, avatarUrl) {
    await page.evaluate(async (nick, room, avatarUrl) => {
      document.getElementById('nickStep').classList.add('hidden');
      document.getElementById('lobbyStep').classList.add('hidden');
      document.getElementById('callHost').classList.remove('hidden');
      await window.TBCall.mount(document.getElementById('callHost'), { nick, room, avatarUrl });
    }, nick, ROOM, avatarUrl);
  }

  const victim = await open('victim');
  await join(victim, 'victim', '');
  await victim.waitForSelector('[data-tile="local"]', { timeout: 15000 });

  for (const [nick, url] of [['evil', BEACON], ['sneaky', `/media/x.jpg"),url("${BEACON}`], ['friend', GOOD]]) {
    const p = await open(nick);
    await join(p, nick, url);
  }
  // Tiles for the others appear once their peer connections come up.
  await victim.waitForFunction(() => document.querySelectorAll('.tbc-tile').length >= 4, { timeout: 30000 });
  await wait(2000);

  const backgrounds = await victim.$$eval('.tbc-avatar', (els) => els.map((e) => e.style.backgroundImage || ''));
  check('the victim sees every peer', backgrounds.length >= 4, `${backgrounds.length} tiles`);
  check('no peer-chosen avatar is fetched off-site',
    !backgrounds.some((b) => b.includes('beacon.invalid')), JSON.stringify(backgrounds));
  check('a real /media/ avatar still shows',
    backgrounds.some((b) => b.includes(GOOD)), JSON.stringify(backgrounds));

  console.log('page errors:', errors.length ? errors.join(' | ') : 'none');
  await browser.close();
  console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
  process.exit(fails ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
