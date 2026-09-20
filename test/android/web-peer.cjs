// A browser joining the same voice channel as the phone, reporting what it sees.
const puppeteer = require(process.env.PUPPETEER);
const B = 'http://127.0.0.1:19290';
const cookie = process.env.COOKIE;      // the web user's session
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const [n, v] = cookie.split('=');
  await page.setCookie({ name: n, value: v, url: B });
  await page.goto(B + '/', { waitUntil: 'networkidle2' });
  await page.evaluate(() => openServerView(serverState.values().next().value));
  await wait(1500);
  await page.evaluate(() => joinVoice(serverChannels.find((c) => c.kind === 'voice')));
  await wait(4000);
  console.log('JOINED');
  for (let i = 0; i < 40; i++) {
    const tiles = await page.evaluate(() => [...document.querySelectorAll('.tbc-tile')].map((t) => {
      const v = t.querySelector('video');
      return { tile: t.dataset.tile, w: v ? v.videoWidth : -1, name: (t.querySelector('.tbc-name') || {}).textContent || '' };
    }));
    console.log('TILES ' + JSON.stringify(tiles));
    await wait(1500);
  }
  await browser.close();
})();
