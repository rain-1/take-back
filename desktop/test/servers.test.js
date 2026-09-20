// Servers in the desktop app: the web client's communities, driven through the
// real (hidden) desktop window. The other member is a headless Chrome.
//
//   GO=go TB_SOURCE_ROOT=/path/to/take-back node test/servers.test.js
//
// Needs a take-back checkout with servers (v1.22.0+) as TB_SOURCE_ROOT.
"use strict";

const path = require("path");
const fs = require("fs");
const os = require("os");
const puppeteer = require("puppeteer-core");
const H = require("./harness");

const API_PORT = 18791, WEB_PORT = 18790, DEBUG_PORT = 18792;
const results = [];
const record = (name, ok, detail = "") => { results.push({ name, ok }); console.log(`  ${ok ? "✓" : "✗"} ${name}${detail ? " — " + detail : ""}`); };

async function api(web, jar, pathname, body) {
  const res = await fetch(web + pathname, {
    method: body ? "POST" : "GET",
    headers: { ...(jar.cookie ? { Cookie: jar.cookie } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const set = res.headers.get("set-cookie");
  if (set) jar.cookie = set.split(";")[0];
  return res.json();
}

async function connectToApp(port) {
  const end = Date.now() + 30000;
  while (Date.now() < end) {
    try {
      const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null });
      const page = (await browser.pages()).find((p) => p.url().startsWith("http://127.0.0.1"));
      if (page) return { browser, page };
      browser.disconnect();
    } catch (_) { /* not listening yet */ }
    await H.sleep(500);
  }
  throw new Error("couldn't connect to the desktop app's debugging port");
}

const waitUntil = async (fn, ms = 10000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await fn().catch(() => false)) return true; await H.sleep(250); }
  return false;
};
const texts = (page, sel) => page.evaluate((s) => [...document.querySelectorAll(s)].map((e) => e.innerText.trim()), sel);
const visible = (page, id) => page.evaluate((id) => { const e = document.getElementById(id); return !!e && !e.classList.contains("hidden"); }, id);

(async () => {
  const lines = [];
  const userData = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-servers-"));
  const env = { TB_START_URL: "", TB_DEBUG_PORT: String(DEBUG_PORT), TB_USER_DATA: userData };
  let conn, chrome;
  try {
    const { web } = await H.startTakeBack({ apiPort: API_PORT, webPort: WEB_PORT, openRegistration: true });
    env.TB_START_URL = web + "/";
    const alice = {}, bob = {};
    await api(web, alice, "/api/register", { nick: "alice", password: "pw123456" });
    const bobUser = await api(web, bob, "/api/register", { nick: "bob", password: "pw123456" });
    await api(web, alice, "/api/friends/request", { nick: "bob" });
    await api(web, bob, "/api/friends/respond", { userId: 1, accept: true });
    const crew = await api(web, bob, "/api/servers", { name: "Crew" });
    const inv = await api(web, bob, "/api/servers/invites", { server: crew.id });
    await api(web, bob, "/api/messages", { with: 1, body: `come hang out: ${web}${inv.path}` });

    console.log("\n▶ desktop app (alice)");
    H.startElectron(env, (l) => lines.push(l));
    conn = await connectToApp(DEBUG_PORT);
    const { page } = conn;
    await page.waitForSelector("#authNick", { timeout: 20000 });
    await page.type("#authNick", "alice");
    await page.type("#authPass", "pw123456");
    await page.click("#authBtn");
    await page.waitForSelector("#main:not(.hidden)", { timeout: 10000 });
    await H.sleep(800);
    await page.evaluate(() => {
      const el = [...document.querySelectorAll("#friends *")].find((n) => n.textContent.trim() === "bob");
      (el.closest(".row") || el).click();
    });
    await page.waitForSelector('.msg .body a[href*="invite="]', { timeout: 10000 });

    // 1. An invite link in chat opens the join dialog here.
    lines.length = 0;
    const before = page.url();
    await page.evaluate(() => document.querySelector('.msg .body a[href*="invite="]').click());
    const dialog = await waitUntil(async () => (await visible(page, "dialog")) && (await texts(page, ".dlg-preview")).join("").includes("Crew"));
    record("invite link in chat opens the join dialog in the app", dialog && page.url() === before && !lines.some((l) => l.includes("openExternal")),
      `${dialog ? "dialog shows Crew" : "no dialog"}; ${lines.find((l) => l.includes("openExternal")) || "nothing opened in the browser"}; page ${page.url() === before ? "not reloaded" : "navigated to " + page.url()}`);

    await page.evaluate(() => [...document.querySelectorAll("#dialogBody button")].find((b) => b.textContent === "Join").click());
    const joined = await waitUntil(async () => (await texts(page, "#chatNick"))[0] === "# general");
    record("joining puts you in the server's #general", joined, (await texts(page, "#chatNick"))[0]);

    // 2. Text channel: multi-line composer, sending and receiving live.
    await page.type("#msgInput", "line one");
    await page.keyboard.down("Shift"); await page.keyboard.press("Enter"); await page.keyboard.up("Shift");
    await page.type("#msgInput", "line two");
    await page.keyboard.press("Enter");
    const chans = await api(web, bob, `/api/servers/channels?server=${crew.id}`);
    const general = chans.find((c) => c.kind === "text"), voice = chans.find((c) => c.kind === "voice");
    const sent = await waitUntil(async () => (await api(web, bob, `/api/channels/messages?channel=${general.id}`)).some((m) => m.body === "line one\nline two"));
    record("multi-line message sent from the app keeps its lines", sent);
    await api(web, bob, "/api/channels/messages", { channel: general.id, body: "hi @alice" });
    const live = await waitUntil(async () => (await texts(page, "#messages .body")).some((t) => t.includes("hi @alice")));
    record("channel message from someone else arrives live", live);

    // 3. Bob opens the server in Chrome: he shows as active in the app.
    console.log("\n▶ chrome (bob)");
    chrome = await puppeteer.launch({ executablePath: H.CHROME, headless: true,
      args: ["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--mute-audio"] });
    const bp = await chrome.newPage();
    await bp.setCookie({ name: bob.cookie.split("=")[0], value: bob.cookie.split("=").slice(1).join("="), url: web });
    await bp.goto(web + "/", { waitUntil: "networkidle2" });
    await bp.evaluate(() => document.querySelector("#servers .row").click());
    const activeBoth = await waitUntil(async () => (await texts(page, "#memberList .chan-head")).includes("ACTIVE — 2") ||
      (await page.evaluate(() => [...document.querySelectorAll("#memberList .chan-head")].map((e) => e.textContent))).includes("Active — 2"));
    record("the Active list shows the other member once they open the server", activeBoth,
      JSON.stringify(await page.evaluate(() => [...document.querySelectorAll("#memberList .chan-head, #memberList .member")].map((e) => e.textContent.trim()))));

    // 4. Voice channel: the app gets its mic, joins, and connects to bob.
    await page.evaluate(() => [...document.querySelectorAll("#channelList .chan.voice")][0].click());
    await bp.evaluate(() => [...document.querySelectorAll("#channelList .chan.voice")][0].click());
    const inCall = await waitUntil(async () => (await page.$$(".tbc-tile")).length === 2, 15000);
    const mic = await page.evaluate(() => [...document.querySelectorAll(".tbc button")].map((b) => b.textContent).join(" | "));
    record("joining a voice channel connects the app to the other member", inCall, `${(await page.$$(".tbc-tile")).length} tiles; buttons: ${mic}`);
    record("the app's microphone is live in the voice call", /Mic on/.test(mic) && !/No mic/.test(mic));
    const act = await api(web, bob, `/api/servers/active?server=${crew.id}`);
    record("server sees both in the voice channel", (act.voice[voice.id] || []).length === 2, JSON.stringify(act.voice));
    const occupants = await waitUntil(async () => (await texts(page, ".voice-occupant")).length === 2);
    record("voice occupants listed under the channel in the app", occupants, JSON.stringify(await texts(page, ".voice-occupant")));

    // 5. What the page sees about visibility. Test windows are hidden with
    //    background throttling off, which Electron also reports as "visible".
    const vis = await page.evaluate(() => document.visibilityState);
    record("hidden test window reports visible (throttling off)", vis === "visible", vis);

    // 6. A second launch carrying an invite link hands it to this window.
    const other = await api(web, bob, "/api/servers", { name: "Film Club" });
    const inv2 = await api(web, bob, "/api/servers/invites", { server: other.id });
    await page.evaluate(() => document.getElementById("dialogClose").click());
    lines.length = 0;
    const secondLines = [];
    const second = H.startElectron({ ...env, TB_DEBUG_PORT: "" }, (l) => secondLines.push(l), [`${web}${inv2.path}`]);
    const exited = await Promise.race([new Promise((r) => second.once("exit", () => r(true))), H.sleep(10000).then(() => false)]);
    if (!exited) second.kill();
    const handed = await waitUntil(async () => (await visible(page, "dialog")) && (await texts(page, ".dlg-preview")).join("").includes("Film Club"));
    record("an invite link passed to a second launch opens in the running app", exited && handed,
      `${exited ? "second copy exited" : "second copy kept running"}; ${handed ? "dialog shows Film Club" : "no dialog"}; call still up: ${(await page.$$(".tbc-tile")).length === 2}`);
    record("the second copy never opens a window of its own", exited && !secondLines.some((l) => l.includes("window visible")),
      secondLines.find((l) => l.includes("window visible")) || "no window created");

  } catch (e) {
    record("test ran to completion", false, e.stack);
  } finally {
    if (conn) conn.browser.disconnect();
    if (chrome) await chrome.close().catch(() => {});
    H.killAll();
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length ? 1 : 0);
})();
