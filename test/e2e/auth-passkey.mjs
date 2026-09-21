// Passkeys, end to end, using Chrome's virtual authenticator.
//
// Enrols a passkey against the identity provider, then signs in to take-back
// with it and nothing else — no password typed anywhere. The virtual
// authenticator is a real WebAuthn implementation driven over CDP, so this
// exercises the same code paths a hardware key would.
//
//   BASE=http://127.0.0.1:19290 AK=http://127.0.0.1:9100 \
//   AK_USER=river AK_PASS=... node test/e2e/auth-passkey.mjs
import puppeteer from 'puppeteer-core';

const B = process.env.BASE || 'http://localhost:19290';
// localhost, not 127.0.0.1: WebAuthn refuses an IP address as a
// relying-party ID, so a passkey can never be registered against one.
const AK = process.env.AK || 'http://localhost:9100';
const USER = process.env.AK_USER || 'river';
const PASS = process.env.AK_PASS;
const CHROME = process.env.CHROME || '/usr/bin/google-chrome';
const TOKEN = process.env.AK_TOKEN;

let failures = 0;
const check = (ok, what) => { console.log(`${ok ? '✓' : '✗'} ${what}`); if (!ok) failures++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

// Stages are swapped in over XHR; give the next one time to be wired up.
async function submitStage(page) {
  await page.keyboard.press('Enter');
  await sleep(2500);
}

async function signInWithPassword(page) {
  await typeInto(page, 'uidField', USER);
  await submitStage(page);
  await typeInto(page, 'password', PASS);
  await submitStage(page);
}

// Text anywhere in the flow UI, shadow roots included.
async function flowText(page) {
  return page.evaluate(() => {
    const seen = [];
    const walk = (root) => {
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) walk(el.shadowRoot);
      }
      seen.push(root.textContent || '');
    };
    walk(document);
    return seen.join(' ').replace(/\s+/g, ' ');
  });
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

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const page = await browser.newPage();
    const cdp = await page.createCDPSession();
    await cdp.send('WebAuthn.enable');
    const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2',
        transport: 'internal',        // a platform authenticator, like a phone or laptop
        hasResidentKey: true,         // discoverable: usable without typing a username
        hasUserVerification: true,
        isUserVerified: true,
        automaticPresenceSimulation: true,
      },
    });
    check(!!authenticatorId, 'a virtual passkey authenticator is attached to the browser');
    await clearMfaDevices(); // the passkey enrolled below should be the only one

    // ---- enrol a passkey with the provider ----
    // Sign in first: the setup flow denies anyone it doesn't already know, and
    // a denial sticks for that visit.
    await page.goto(`${AK}/if/user/`, { waitUntil: 'networkidle2' });
    await sleep(2000);
    if (await page.$('>>> input[name=uidField]')) {
      await signInWithPassword(page);
    }
    if (process.env.DEBUG) {
      console.log('  [debug] after sign-in:', (await flowText(page)).slice(0, 160));
      console.log('  [debug] url:', page.url());
    }
    await page.goto(`${AK}/if/flow/default-authenticator-webauthn-setup/`, { waitUntil: 'networkidle2' });
    if (process.env.DEBUG) {
      await sleep(3000);
      console.log('  [debug] setup flow:', (await flowText(page)).slice(0, 200));
    }

    // The stage asks the authenticator to register as soon as it runs. Poll for
    // the credential rather than sleeping a fixed time: clicking around while
    // the registration is in flight cancels it, which looks exactly like a
    // browser that refused.
    let stored = 0;
    for (let i = 0; i < 20 && stored === 0; i++) {
      await sleep(1000);
      stored = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials.length;
      if (stored === 0 && i === 9) {
        // Still nothing after ten seconds: some versions wait to be asked.
        const retry = await page.$('>>> button');
        if (retry) await retry.evaluate((b) => b.click());
      }
    }
    check(stored > 0, `the provider stored a passkey (${stored} credential on the authenticator)`);

    // ---- sign out everywhere ----
    await page.goto(`${AK}/flows/-/default/invalidation/`, { waitUntil: 'networkidle2' }).catch(() => {});
    await page.goto(B + '/auth/logout', { waitUntil: 'networkidle2' }).catch(() => {});
    await page.goto(B, { waitUntil: 'networkidle2' });
    const out = await page.evaluate(async () => (await fetch('/api/me')).status);
    check(out === 401, 'signed out of take-back before the passkey attempt');

    // ---- sign in to take-back with the passkey alone ----
    await page.goto(B + '/auth/login', { waitUntil: 'networkidle2' });
    await sleep(2500);
    const text = await flowText(page);
    const offered = /passkey|security key|webauthn/i.test(text);
    check(offered, 'the sign-in screen offers a passkey instead of a password');

    // Authentik offers it as a link ("Use a security key") next to the username
    // field, not a button — so match either.
    const clicked = await page.evaluate(() => {
      const hit = (root) => {
        for (const el of root.querySelectorAll('*')) {
          if (el.shadowRoot && hit(el.shadowRoot)) return true;
          const label = (el.textContent || '').trim().toLowerCase();
          if ((el.tagName === 'BUTTON' || el.tagName === 'A') &&
              /passkey|security key|webauthn/.test(label)) {
            el.click();
            return true;
          }
        }
        return false;
      };
      return hit(document);
    });
    check(clicked, 'and that option can be chosen');

    await page.waitForFunction(
      (base) => location.href.startsWith(base) &&
        !document.getElementById('main')?.classList.contains('hidden'),
      { timeout: 45000 }, B,
    ).catch(() => {});

    const me = await page.evaluate(async () => {
      const r = await fetch('/api/me');
      return r.ok ? r.json() : null;
    });
    check(me?.nick?.toLowerCase() === USER.toLowerCase(),
      `signed in as ${me?.nick ?? '(nobody)'} with a passkey and no password`);
  } finally {
    await browser.close();
  }
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
