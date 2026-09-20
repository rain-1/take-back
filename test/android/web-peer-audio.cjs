// A browser in the same voice channel, printing how many bytes of audio it has
// received from the phone — so a test can tell whether a backgrounded phone is
// still in the call and still talking.
const puppeteer = require(process.env.PUPPETEER);
const B = 'http://127.0.0.1:19290';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const [n, v] = process.env.COOKIE.split('=');
  await page.setCookie({ name: n, value: v, url: B });
  await page.goto(B + '/', { waitUntil: 'networkidle2' });
  await page.evaluate(() => openServerView(serverState.values().next().value));
  await wait(1500);
  await page.evaluate(() => joinVoice(serverChannels.find((c) => c.kind === 'voice')));
  await wait(4000);
  console.log('JOINED');
  for (let i = 0; i < 60; i++) {
    const total = await page.evaluate(async () => {
      const s = await TBCall.stats();
      return Object.values(s).reduce((n, p) => n + p.audio, 0);
    }).catch(() => -1);
    console.log('AUDIO ' + total);
    await wait(2000);
  }
  await browser.close();
})();
