// Signing in through the identity provider, in a real browser.
//
// The Go tests cover the protocol against a stub provider; this one checks the
// thing people actually do: land on take-back, get sent to Authentik, type a
// password there, and come back signed in — with the old password endpoints
// gone and log-out ending the provider's session too.
//
// Needs a take-back started with TB_OIDC_* pointing at a running Authentik
// (scratchpad/reset-oidc.sh) and an Authentik user to sign in as.
//
//   BASE=http://127.0.0.1:19290 AK=http://127.0.0.1:9100 \
//   AK_USER=river AK_PASS=... node test/e2e/auth-oidc.mjs
import puppeteer from 'puppeteer-core';

const B = process.env.BASE || 'http://127.0.0.1:19290';
const AK = process.env.AK || 'http://127.0.0.1:9100';
const USER = process.env.AK_USER || 'river';
const PASS = process.env.AK_PASS;
const CHROME = process.env.CHROME || '/usr/bin/google-chrome';
const TOKEN = process.env.AK_TOKEN;

let failures = 0;
function check(ok, what) {
  console.log(`${ok ? '✓' : '✗'} ${what}`);
  if (!ok) failures++;
}

// Authentik's flow UI lives in nested shadow roots, so every field is found
// with a pierce selector rather than a normal one.
// Type into a field, waiting for a *visible* one.
//
// Authentik renders hidden username/password/code inputs on the first stage so
// password managers and passkey autofill have something to fill. A plain
// selector matches those immediately, so waiting for "an input named password"
// hands back a hidden box on the wrong stage and everything typed goes nowhere.
async function typeInto(page, name, text) {
  const handle = await page.waitForFunction((fieldName) => {
    const find = (root) => {
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) {
          const hit = find(el.shadowRoot);
          if (hit) return hit;
        }
        if (el.tagName === 'INPUT' && el.name === fieldName && el.offsetParent !== null) return el;
      }
      return null;
    };
    return find(document);
  }, { timeout: 20000, polling: 300 }, name);
  await handle.evaluate((e) => e.focus());
  await page.keyboard.type(text);
  return handle;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Each stage is a form: Enter submits it, which avoids hunting for a button
// inside nested shadow roots. The pause afterwards is load-bearing — Authentik
// swaps stages over XHR, and the next stage's field exists in the DOM before
// it is wired up, so typing immediately goes into an element that is about to
// be replaced.
async function submitStage(page) {
  await page.keyboard.press('Enter');
  await sleep(2000);
}


// Start from a known state: the provider remembers enrolled devices, and a
// passkey left over from an earlier run turns every password sign-in into a
// two-factor one. Needs AK_TOKEN (the bootstrap/admin API token).
async function clearMfaDevices() {
  if (!TOKEN) {
    console.log('  (AK_TOKEN not set — skipping device cleanup)');
    return 0;
  }
  const headers = { Authorization: 'Bearer ' + TOKEN };
  let removed = 0;
  for (const kind of ['webauthn', 'totp', 'static']) {
    const res = await fetch(`${AK}/api/v3/authenticators/admin/${kind}/`, { headers });
    if (!res.ok) continue;
    const { results } = await res.json();
    for (const dev of results) {
      if (dev.user?.username !== USER) continue;
      await fetch(`${AK}/api/v3/authenticators/admin/${kind}/${dev.pk}/`, { method: 'DELETE', headers });
      removed++;
    }
  }
  return removed;
}

async function main() {
  if (!PASS) throw new Error('set AK_PASS to the Authentik password for ' + USER);

  await clearMfaDevices(); // a leftover passkey would make this a 2FA sign-in

  const status = await (await fetch(B + '/api/auth/status')).json();
  check(status.provider === true, 'the server says it signs in through a provider');
  check(status.ready === true, 'and that the provider is reachable');

  // The password endpoints are gone, not merely unused.
  for (const path of ['/api/login', '/api/register']) {
    const res = await fetch(B + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nick: USER, password: 'anything' }),
    });
    check(res.status === 410, `${path} is retired (410)`);
  }

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const page = await browser.newPage();
    await page.goto(B, { waitUntil: 'networkidle2' });

    const btnText = await page.$eval('#authBtn', (b) => b.textContent.trim());
    check(btnText === 'Sign in', `the sign-in screen offers one button (got "${btnText}")`);
    const passwordBoxHidden = await page.$eval('#authPass', (i) => i.classList.contains('hidden'));
    check(passwordBoxHidden, 'and no password box, because take-back never sees one');

    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }),
      page.click('#authBtn'),
    ]);
    check(page.url().startsWith(AK), `the browser is handed to the provider (${page.url().slice(0, 60)})`);

    // Identification stage, then the password stage.
    await typeInto(page, 'uidField', USER);
    await submitStage(page);
    await typeInto(page, 'password', PASS);
    await submitStage(page);

    // Authentik drives its stages over XHR and only the last step is a real
    // navigation, so wait for the destination rather than for "a navigation".
    await page.waitForFunction(
      (base) => location.href.startsWith(base) &&
        !document.getElementById('main')?.classList.contains('hidden'),
      { timeout: 45000 }, B,
    );
    check(page.url().startsWith(B), 'and comes back to take-back');
    const me = await page.evaluate(async () => {
      const r = await fetch('/api/me');
      return r.ok ? r.json() : null;
    });
    check(me?.nick?.toLowerCase() === USER.toLowerCase(),
      `signed in as ${me?.nick} — the session is take-back's own cookie`);

    // The session survives a reload: this is an ordinary tb_session, not
    // something that needs the provider on every request.
    await page.reload({ waitUntil: 'networkidle2' });
    const stillIn = await page.evaluate(async () => (await fetch('/api/me')).ok);
    check(stillIn, 'the session survives a reload');

    // Logging out clears take-back and ends the provider's session, so the
    // next sign-in has to prove who it is again rather than walking back in.
    await page.goto(B + '/auth/logout', { waitUntil: 'networkidle2', timeout: 30000 });
    // Come back to take-back before asking: after logout the browser may be
    // sitting on the provider's origin, where /api/me means something else.
    await page.goto(B, { waitUntil: 'networkidle2' });
    const loggedOut = await page.evaluate(async () => (await fetch('/api/me')).status);
    check(loggedOut === 401, 'log out ends the take-back session');

    await page.goto(B + '/auth/login', { waitUntil: 'networkidle2', timeout: 30000 });
    await sleep(2000);
    const askedAgain = (await page.$('>>> input[name=uidField]'))
      || (await page.$('>>> input[name=password]'));
    check(!!askedAgain, 'and the provider asks for credentials again');
  } finally {
    await browser.close();
  }

  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
