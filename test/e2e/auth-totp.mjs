// Two-factor sign-in with an authenticator app, end to end.
//
// Enrols a TOTP device against the identity provider (reading the same secret
// a phone would scan from the QR code), then signs in to take-back with a
// password *and* a generated code — proving the second factor is actually
// demanded, not merely offered.
//
//   AK_TOKEN=... BASE=http://localhost:19290 AK=http://localhost:9100 \
//   AK_USER=river AK_PASS=... node test/e2e/auth-totp.mjs
import puppeteer from 'puppeteer-core';
import crypto from 'node:crypto';

const B = process.env.BASE || 'http://localhost:19290';
const AK = process.env.AK || 'http://localhost:9100';
const USER = process.env.AK_USER || 'river';
const PASS = process.env.AK_PASS;
const TOKEN = process.env.AK_TOKEN;
const CHROME = process.env.CHROME || '/usr/bin/google-chrome';

let failures = 0;
const check = (ok, what) => { console.log(`${ok ? '✓' : '✗'} ${what}`); if (!ok) failures++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- what an authenticator app does (RFC 6238), in a few lines ----

function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out = [];
  for (const ch of s.replace(/=+$/, '').toUpperCase()) {
    const idx = alphabet.indexOf(ch);
    if (idx === -1) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function totp(secret, when = Date.now()) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(when / 1000 / 30)));
  const mac = crypto.createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = mac[mac.length - 1] & 0x0f;
  const code = mac.readUInt32BE(offset) & 0x7fffffff;
  return String(code % 1_000_000).padStart(6, '0');
}

// ---- driving the provider's flow UI ----

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

async function submitStage(page) {
  await page.keyboard.press('Enter');
  await sleep(2500);
}

async function fieldValue(page, name) {
  return page.evaluate((fieldName) => {
    const find = (root) => {
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) {
          const hit = find(el.shadowRoot);
          if (hit) return hit;
        }
        if (el.tagName === 'INPUT' && el.name === fieldName) return el;
      }
      return null;
    };
    return find(document)?.value ?? '';
  }, name);
}

async function clearMfaDevices() {
  if (!TOKEN) { console.log('  (AK_TOKEN not set — skipping device cleanup)'); return; }
  const headers = { Authorization: 'Bearer ' + TOKEN };
  for (const kind of ['webauthn', 'totp', 'static']) {
    const res = await fetch(`${AK}/api/v3/authenticators/admin/${kind}/`, { headers });
    if (!res.ok) continue;
    const { results } = await res.json();
    for (const dev of results) {
      if (dev.user?.username !== USER) continue;
      await fetch(`${AK}/api/v3/authenticators/admin/${kind}/${dev.pk}/`, { method: 'DELETE', headers });
    }
  }
}

async function main() {
  if (!PASS) throw new Error('set AK_PASS to the Authentik password for ' + USER);
  await clearMfaDevices();

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const page = await browser.newPage();

    // ---- enrol an authenticator app ----
    await page.goto(`${AK}/if/user/`, { waitUntil: 'networkidle2' });
    await sleep(2000);
    await typeInto(page, 'uidField', USER);
    await submitStage(page);
    await typeInto(page, 'password', PASS);
    await submitStage(page);

    await page.goto(`${AK}/if/flow/default-authenticator-totp-setup/`, { waitUntil: 'networkidle2' });
    await sleep(3000);

    // The same URI the QR code encodes — this is what a phone would scan.
    const uri = await fieldValue(page, 'otp_uri');
    const secret = new URL(uri.replace('otpauth://', 'https://')).searchParams.get('secret');
    check(!!secret, `the provider offered an enrolment secret (${uri.slice(0, 28)}…)`);

    const enrolCode = totp(secret);
    await typeInto(page, 'code', enrolCode);
    await submitStage(page);
    await sleep(2000);

    const devices = await fetch(`${AK}/api/v3/authenticators/admin/totp/`,
      { headers: { Authorization: 'Bearer ' + TOKEN } }).then((r) => r.json());
    const mine = devices.results.filter((d) => d.user?.username === USER);
    check(mine.length === 1, `the app is registered as a second factor (${mine.length} device)`);

    // ---- sign out, then sign in to take-back with password + code ----
    // End the provider's own session too: this sign-in was made at the
    // provider (to enrol), so take-back has no session to hand back and
    // /auth/logout alone would leave the provider happy to wave us through.
    await page.goto(`${AK}/flows/-/default/invalidation/`, { waitUntil: 'networkidle2' }).catch(() => {});
    await sleep(1500);
    await page.goto(B + '/auth/logout', { waitUntil: 'networkidle2' }).catch(() => {});
    await page.goto(B, { waitUntil: 'networkidle2' });
    check(await page.evaluate(async () => (await fetch('/api/me')).status) === 401,
      'signed out before the two-factor attempt');

    await page.goto(B + '/auth/login', { waitUntil: 'networkidle2' });
    await sleep(2000);
    await typeInto(page, 'uidField', USER);
    await submitStage(page);
    await typeInto(page, 'password', PASS);
    await submitStage(page);

    // The password alone must not be enough now.
    const landedEarly = await page.evaluate((base) => location.href.startsWith(base), B);
    check(!landedEarly, 'the password alone does not get in');

    // A code is good once. Signing in with the code that just enrolled the
    // device is refused (that replay protection is the point of TOTP), so wait
    // for the next 30-second window rather than reusing it.
    while (totp(secret) === enrolCode) await sleep(2000);

    await typeInto(page, 'code', totp(secret));
    await submitStage(page);

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
      `signed in as ${me?.nick ?? '(nobody)'} with a password and a 6-digit code`);
  } finally {
    await browser.close();
  }
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
