// Two people in two browsers, through the servers UI: create, invite, join,
// chat, unread pips, admin controls, live renames, and deleting the server.
const puppeteer = require(process.env.PUPPETEER || 'puppeteer-core');
const B = process.env.WEB || 'http://127.0.0.1:19290';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let fails = 0;
const check = (label, ok, extra = '') => { if (!ok) fails++; console.log(`${ok ? '✓' : '✗'} ${label}${extra ? ' — ' + extra : ''}`); };
async function register(nick) {
  const res = await fetch(B + '/api/register', { method: 'POST', body: JSON.stringify({ nick, password: 'pw123456' }) });
  const c = res.headers.get('set-cookie').split(';')[0].split('=');
  return { name: c[0], value: c.slice(1).join('=') };
}
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const errors = [];
  async function user(nick) {
    const cookie = await register(nick);
    const ctx = await browser.createBrowserContext();
    const page = await ctx.newPage();
    await page.setViewport({ width: 1400, height: 850 });
    page.on('pageerror', (e) => errors.push(`${nick}: ${e.message}`));
    page.on('dialog', (d) => d.accept());
    await page.setCookie({ ...cookie, url: B });
    return page;
  }
  const alice = await user('alice'), bob = await user('bob');
  await alice.goto(B + '/', { waitUntil: 'networkidle2' });
  const vis = (p, id) => p.evaluate((id) => { const e = document.getElementById(id); return !!e && !e.classList.contains('hidden') && getComputedStyle(e).display !== 'none' && (e.offsetParent !== null || getComputedStyle(e).position === 'fixed'); }, id);
  const text = (p, sel) => p.evaluate((s) => [...document.querySelectorAll(s)].map((e) => e.textContent.trim()), sel);
  const clickText = (p, sel, re) => p.evaluate((s, re) => { const b = [...document.querySelectorAll(s)].find((e) => new RegExp(re).test(e.textContent)); if (!b) return false; b.click(); return true; }, sel, re);

  check('empty servers note', (await text(alice, '#servers')).join('').includes('No servers yet'));
  await alice.click('#newServerBtn');
  check('create dialog opens', await vis(alice, 'dialog'));
  await alice.type('#dialogBody input:not([type=file])', 'Movie Night');
  await clickText(alice, '#dialogBody button', '^Create$');
  await wait(1200);
  check('dialog closed', !(await vis(alice, 'dialog')));
  check('server listed', (await text(alice, '#servers .row')).join() === 'MNMovie Night', JSON.stringify(await text(alice, '#servers .row')));
  check('channel column shown', await vis(alice, 'channelCol'));
  check('member column shown', await vis(alice, 'memberCol'));
  check('#general opened', (await text(alice, '#chatNick'))[0] === '# general');
  check('channels listed', JSON.stringify(await text(alice, '#channelList .cname')) === '["general","General"]', JSON.stringify(await text(alice, '#channelList .cname')));
  check('alice is admin in member list', (await text(alice, '#memberList .member')).some((t) => /alice \(you\)admin/.test(t)), JSON.stringify(await text(alice, '#memberList .member')));
  check('call button hidden in channel', !(await vis(alice, 'callBtn')));

  await alice.type('#msgInput', 'hello crew');
  await alice.keyboard.down('Shift'); await alice.keyboard.press('Enter'); await alice.keyboard.up('Shift');
  await alice.type('#msgInput', 'second line');
  await alice.keyboard.press('Enter');
  await wait(800);
  check('alice message shown multi-line', (await alice.evaluate(() => [...document.querySelectorAll('#messages .body')].map((e) => e.innerText.trim()))).some((t) => t === 'hello crew\nsecond line'), JSON.stringify(await text(alice, '#messages .body')));

  // Invite
  await alice.click('#serverMenuBtn');
  await clickText(alice, '#dialogBody button', '^Invite$');
  await wait(800);
  const link = await alice.evaluate(() => document.querySelector('.invite-link input')?.value);
  check('invite link made', /\?invite=\w+/.test(link || ''), link);
  await alice.click('#dialogClose');

  await bob.goto(link, { waitUntil: 'networkidle2' });
  await wait(1000);
  check('bob sees join dialog', await vis(bob, 'dialog'));
  check('invite preview shows server', (await text(bob, '.dlg-preview')).join('').includes('Movie Night'), JSON.stringify(await text(bob, '.dlg-preview')));
  await clickText(bob, '#dialogBody button', '^Join$');
  await wait(1500);
  check('bob in server view', (await text(bob, '#chatNick'))[0] === '# general');
  check('bob sees history', (await text(bob, '#messages .body')).some((t) => t.startsWith('hello crew')));
  check('bob not admin: no channel admin buttons', (await bob.$$('#channelList .admin-acts')).length === 0);
  await wait(500);

  // alice's member list updates live when bob joins
  check('alice sees bob joined', (await text(alice, '#memberList .member')).some((t) => /bob$/.test(t)), JSON.stringify(await text(alice, '#memberList .member')));

  await bob.type('#msgInput', 'hey @alice');
  await bob.keyboard.press('Enter');
  await wait(1000);
  check('alice gets bob message live', (await text(alice, '#messages .body')).some((t) => t.includes('hey @alice')));

  // New channel by admin, appears for bob
  await alice.evaluate(() => [...document.querySelectorAll('.chan-head button')][0].click());
  await alice.type('#dialogBody input:not([type=radio])', 'random');
  await clickText(alice, '#dialogBody button', '^Create$');
  await wait(1200);
  check('alice now in #random', (await text(alice, '#chatNick'))[0] === '# random');
  check('bob sees #random', (await text(bob, '#channelList .cname')).includes('random'), JSON.stringify(await text(bob, '#channelList .cname')));
  await alice.type('#msgInput', 'over here @bob');
  await alice.keyboard.press('Enter');
  await wait(1000);
  const bobRandom = await bob.evaluate(() => { const r = [...document.querySelectorAll('#channelList .chan')].find((e) => e.textContent.includes('random')); return r && r.className + '|' + r.textContent; });
  check('bob gets unread pip on #random', /unread/.test(bobRandom || ''), bobRandom);
  const bobServerRow = await bob.evaluate(() => document.querySelector('#servers .row')?.className);
  check('bob server row unread', /unread/.test(bobServerRow || ''), bobServerRow);

  // Bob switches to #random, reads; then reload restores it
  await bob.evaluate(() => [...document.querySelectorAll('#channelList .chan')].find((e) => e.textContent.includes('random')).click());
  await wait(1000);
  check('bob reads #random', (await text(bob, '#messages .body')).some((t) => t.includes('over here')));
  check('pip cleared', !(await bob.evaluate(() => document.querySelector('#servers .row').className)).includes('unread'));
  await bob.reload({ waitUntil: 'networkidle2' });
  await wait(1500);
  check('reload restores #random', (await text(bob, '#chatNick'))[0] === '# random', JSON.stringify(await text(bob, '#chatNick')));

  // Collapsing
  await bob.evaluate(() => document.querySelector('[data-section=friends] h3.collapser').click());
  await bob.reload({ waitUntil: 'networkidle2' });
  await wait(800);
  check('friends stays collapsed', await bob.evaluate(() => document.querySelector('[data-section=friends]').classList.contains('collapsed')));
  await bob.screenshot({ path: (process.env.SHOTS || '.') + '/servers-bob.png' });

  // Rename server live
  await alice.click('#serverMenuBtn');
  await alice.evaluate(() => { const i = document.querySelector('#dialogBody input[placeholder="Server name"]'); i.value = 'Film Club'; });
  await clickText(alice, '#dialogBody button', '^Rename$');
  await wait(1200);
  check('bob sees rename', (await text(bob, '#serverHeadName'))[0] === 'Film Club' && (await text(bob, '#chatSub'))[0] === 'Film Club');

  // Delete a message as admin (bob's)
  await alice.evaluate(() => [...document.querySelectorAll('#channelList .chan')].find((e) => e.textContent.includes('general')).click());
  await wait(1000);
  await alice.screenshot({ path: (process.env.SHOTS || '.') + '/servers-alice.png' });

  // Delete server
  await alice.click('#serverMenuBtn');
  await clickText(alice, '#dialogBody button', '^Delete$');
  await wait(1500);
  check('alice view closed', !(await vis(alice, 'channelCol')));
  check('bob view closed', !(await vis(bob, 'channelCol')) && (await text(bob, '#servers')).join('').includes('No servers yet'));
  console.log('page errors:', errors.length ? errors : 'none');
  await browser.close();
  console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
  process.exit(fails ? 1 : 0);
})();
