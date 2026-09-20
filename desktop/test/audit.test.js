// Web-parity audit: drive the REAL desktop window (via Chrome DevTools
// Protocol) through the things a browser does for the web client that an
// Electron window might not. Each check reports what actually happened.
//
//   GO=go node test/audit.test.js
"use strict";

const path = require("path");
const fs = require("fs");
const os = require("os");
const puppeteer = require("puppeteer-core");
const H = require("./harness");

const API_PORT = 18691, WEB_PORT = 18690, DEBUG_PORT = 19333, CONTROL_PORT = 19334;
const control = async (cmd) => (await fetch(`http://127.0.0.1:${CONTROL_PORT}/${cmd}`)).json();
const results = [];
const record = (name, ok, detail) => { results.push({ name, ok, detail }); console.log(`  ${ok ? "✓" : "✗"} ${name} — ${detail}`); };

async function api(web, jar, pathname, body, form) {
  const res = await fetch(web + pathname, {
    method: body || form ? "POST" : "GET",
    headers: { ...(jar.cookie ? { Cookie: jar.cookie } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
    body: form || (body ? JSON.stringify(body) : undefined),
  });
  const set = res.headers.get("set-cookie");
  if (set) jar.cookie = set.split(";")[0];
  return res.json();
}

async function seed(web) {
  const alice = {}, bob = {};
  await api(web, alice, "/api/register", { nick: "alice", password: "pw123456" });
  await api(web, bob, "/api/register", { nick: "bob", password: "pw123456" });
  await api(web, alice, "/api/friends/request", { nick: "bob" });
  await api(web, bob, "/api/friends/respond", { userId: 1, accept: true });
  await api(web, bob, "/api/messages", { with: 1, body: "docs are at https://example.com/docs" });
  const form = new FormData();
  form.append("with", "1");
  form.append("file", new Blob(["quarterly numbers"], { type: "application/pdf" }), "report.pdf");
  await api(web, bob, "/api/messages/media", null, form);
}

async function connectToApp(port) {
  const end = Date.now() + 30000;
  while (Date.now() < end) {
    try {
      const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null });
      const pages = await browser.pages();
      const page = pages.find((p) => p.url().startsWith("http://127.0.0.1"));
      if (page) return { browser, page };
      browser.disconnect();
    } catch (_) { /* app not listening yet */ }
    await H.sleep(500);
  }
  throw new Error("couldn't connect to the desktop app's debugging port");
}

(async () => {
  const lines = [];
  const userData = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-profile-"));
  const downloads = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-dl-"));
  // TB_TEST_SHOW: this audit minimizes and restores the window, so it needs a
  // real one on screen — warn whoever is at the PC before running it on WSL.
  const env = { TB_TEST_SHOW: "1", TB_START_URL: "", TB_DEBUG_PORT: String(DEBUG_PORT), TB_CONTROL_PORT: String(CONTROL_PORT),
                TB_USER_DATA: userData, TB_DOWNLOAD_DIR: downloads };
  let app, conn;
  try {
    const { web } = await H.startTakeBack({ apiPort: API_PORT, webPort: WEB_PORT, openRegistration: true });
    env.TB_START_URL = web + "/";
    await seed(web);

    console.log("\n▶ first launch");
    app = H.startElectron(env, (l) => lines.push(l));
    conn = await connectToApp(DEBUG_PORT);
    let { page } = conn;
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
    await page.waitForSelector(".msg .body a", { timeout: 10000 });

    // 1. A link in a message goes to the system browser; the app stays put.
    lines.length = 0;
    const before = page.url();
    await page.evaluate(() => document.querySelector('.msg .body a[href^="https://example.com"]').click());
    await H.sleep(800);
    record("links open in the system browser",
      lines.some((l) => l.includes("openExternal https://example.com/docs")) && page.url() === before,
      lines.find((l) => l.includes("openExternal")) || "no openExternal logged");

    // 2. Downloading an attachment saves the real file under its real name.
    lines.length = 0;
    await page.evaluate(() => document.querySelector(".filechip").click());
    await H.sleep(2000);
    const saved = path.join(downloads, "report.pdf");
    const content = fs.existsSync(saved) ? fs.readFileSync(saved, "utf8") : null;
    record("attachment downloads save the file", content === "quarterly numbers",
      content === null ? `nothing saved (${lines.find((l) => l.includes("download")) || "no download event"})`
                       : `saved report.pdf, ${content.length} bytes, content ${content === "quarterly numbers" ? "matches" : "WRONG"}`);

    // 3. The window can't be navigated away from take-back (a dropped file,
    //    or a page redirect, would otherwise load with the bridge attached).
    lines.length = 0;
    for (const target of ["file:///etc/hostname", "https://example.com/evil"]) {
      await page.evaluate((t) => { location.href = t; }, target).catch(() => {});
      await H.sleep(700);
    }
    const opened = lines.filter((l) => l.includes("openExternal"));
    record("navigation away from take-back is blocked", page.url().startsWith("http://127.0.0.1") && opened.length === 0,
      `still on ${new URL(page.url()).origin}; ${opened.length ? "but it OPENED " + opened.join(", ") : "nothing opened"}`);

    // 4. Notifications: permission, and whether clicking one can bring back a
    //    minimized window (the web client's click handler calls window.focus()).
    const perm = await page.evaluate(() => Notification.permission);
    record("notification permission", perm === "granted", `Notification.permission = ${perm}`);
    await control("minimize");
    await H.sleep(800);
    // Exactly what the web client's notification click handler does.
    await page.evaluate(() => window.focus());
    await H.sleep(800);
    const st = await control("state");
    record("clicking a notification brings back a minimized window", !st.minimized,
      `after window.focus() the window is ${st.minimized ? "still minimized" : "restored"}`);
    await control("restore");

    // 5. Camera/mic work in the desktop window.
    const gum = await page.evaluate(async () => {
      try { const s = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
            const k = s.getTracks().map((t) => t.kind).join("+"); s.getTracks().forEach((t) => t.stop()); return k; }
      catch (e) { return "error: " + e.name; }
    });
    record("camera and microphone available", gum === "audio+video", gum);

    // 6. Launching a second copy hands over to this window instead.
    const second = H.startElectron({ ...env, TB_DEBUG_PORT: "", TB_CONTROL_PORT: "" });
    const exited = await Promise.race([
      new Promise((r) => second.once("exit", () => r(true))),
      H.sleep(8000).then(() => false),
    ]);
    if (!exited) second.kill();
    const stillOne = (await control("state")).visible;
    record("a second launch reuses the open window", exited && stillOne,
      exited ? "second copy exited; first window still up" : "second copy kept running (two windows)");

    // 7. Window size is remembered across a restart.
    await control("resize");
    await H.sleep(1200); // saved on a short debounce

    // 8. Staying logged in across a restart.
    console.log("\n▶ relaunch with the same profile");
    conn.browser.disconnect();
    app.kill();
    await H.sleep(2000);
    app = H.startElectron(env, (l) => lines.push(l));
    conn = await connectToApp(DEBUG_PORT);
    page = conn.page;
    const loggedIn = await page.waitForSelector("#main:not(.hidden)", { timeout: 15000 }).then(() => true).catch(() => false);
    record("stays logged in after restarting the app", loggedIn, loggedIn ? "went straight to the chat" : "showed the login screen");
    const { bounds } = await control("state");
    record("window size remembered", bounds.width === 900 && bounds.height === 700,
      `reopened at ${bounds.width}x${bounds.height} (was resized to 900x700)`);

    // 7. Things a desktop app should do that a tab doesn't (informational).
    const title = await page.title();
    record("window title", true, JSON.stringify(title));
  } catch (e) {
    record("audit ran to completion", false, e.message);
  } finally {
    if (conn) conn.browser.disconnect();
    H.killAll();
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks as expected`);
  process.exitCode = failed.length ? 1 : 0;
})();
