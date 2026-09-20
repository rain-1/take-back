// What the page is NOT allowed to do. Every check drives the real (hidden)
// desktop window through the Chrome DevTools Protocol, against a stand-in
// "take-back" server and a second origin playing the hostile site — no Go
// checkout or real server needed.
//
// The page here is the attacker: these are the things a compromised take-back
// (XSS, a redirect endpoint, a bad day for the server) must still not be able
// to reach on the user's computer.
//
//   node test/security.test.js
"use strict";

const http = require("http");
const path = require("path");
const fs = require("fs");
const os = require("os");
const puppeteer = require("puppeteer-core");
const H = require("./harness");

const TB_PORT = 19590, EVIL_PORT = 19591, DEBUG_PORT = 19592;
const TB = `http://127.0.0.1:${TB_PORT}`, EVIL = `http://127.0.0.1:${EVIL_PORT}`;
const results = [];
const record = (name, ok, detail = "") => {
  results.push({ name, ok });
  console.log(`  ${ok ? "✓" : "✗"} ${name}${detail ? " — " + detail : ""}`);
};

// Two origins: the app's own server, and somewhere else entirely. /redirect
// stands in for any endpoint that answers with a Location header.
function serve(port, body) {
  return http.createServer((req, res) => {
    const u = new URL(req.url, `http://127.0.0.1:${port}`);
    if (u.pathname === "/redirect") {
      res.writeHead(302, { Location: u.searchParams.get("to") });
      return res.end();
    }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(body);
  }).listen(port, "127.0.0.1");
}

async function connectToApp(port) {
  const end = Date.now() + 30000;
  while (Date.now() < end) {
    try {
      const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null });
      const page = (await browser.pages()).find((p) => p.url().startsWith(TB));
      if (page) return { browser, page };
      browser.disconnect();
    } catch (_) { /* not listening yet */ }
    await H.sleep(500);
  }
  throw new Error("couldn't connect to the desktop app's debugging port");
}

(async () => {
  const lines = [];
  const servers = [serve(TB_PORT, "<!doctype html><title>take-back (stand-in)</title><body><h1>take-back</h1>"),
                   serve(EVIL_PORT, "<!doctype html><title>hostile</title><body><h1>hostile site</h1>")];
  const userData = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-sec-"));
  let conn;
  try {
    H.startElectron({ TB_START_URL: `${TB}/`, TB_DEBUG_PORT: String(DEBUG_PORT), TB_USER_DATA: userData },
                    (l) => lines.push(l));
    conn = await connectToApp(DEBUG_PORT);
    const { page } = conn;

    // 1. The bridge is the whole attack surface: keep it to what it says.
    const keys = await page.evaluate(() => Object.keys(window.tbDesktop || {}).sort());
    const expected = ["bridgeStats", "debug", "onAudio", "pickShare", "startAudio", "stopAudio", "version"];
    record("window.tbDesktop exposes only the documented API", keys.join(",") === expected.join(","), keys.join(","));
    const node = await page.evaluate(() => ({ require: typeof require, process: typeof process, module: typeof module }));
    record("no Node in the page", Object.values(node).every((t) => t === "undefined"), JSON.stringify(node));

    // 2. Capturing audio nobody chose. On Windows "except:<pid>" is the whole
    //    computer's sound; this must need a trip through the picker, not a
    //    line of script.
    const sneaky = await page.evaluate(async () => {
      let chunks = 0;
      window.tbDesktop.onAudio(() => chunks++);
      let err = null;
      try { await window.tbDesktop.startAudio("tone:440"); } catch (e) { err = String(e.message || e); }
      await new Promise((r) => setTimeout(r, 800));
      await window.tbDesktop.stopAudio().catch(() => {});
      return { err, chunks };
    });
    record("audio can't be started without the picker", !!sneaky.err && sneaky.chunks === 0,
      sneaky.err ? `refused (${sneaky.chunks} chunks)` : `STARTED, ${sneaky.chunks} chunks arrived`);

    // 3. ...but the real flow still works, and the approval is good once only.
    const proper = await page.evaluate(async () => {
      const choice = await window.tbDesktop.pickShare();
      let chunks = 0, err = null;
      window.tbDesktop.onAudio(() => chunks++);
      try { await window.tbDesktop.startAudio(choice.audioId); } catch (e) { err = String(e.message || e); }
      await new Promise((r) => setTimeout(r, 800));
      let replay = null;
      try { await window.tbDesktop.startAudio(choice.audioId); replay = "allowed"; } catch (e) { replay = "refused"; }
      await window.tbDesktop.stopAudio().catch(() => {});
      return { err, chunks, replay };
    });
    record("audio the picker approved does start", !proper.err && proper.chunks > 0,
      proper.err || `${proper.chunks} chunks`);
    record("an approval can't be replayed", proper.replay === "refused", proper.replay);

    // 4. Leaving take-back: directly, and through a redirect on take-back's own
    //    origin (will-navigate only ever sees the first hop).
    for (const [name, target] of [["directly", `${EVIL}/`],
                                  ["via a redirect", `${TB}/redirect?to=${encodeURIComponent(EVIL + "/")}`],
                                  ["to a local file", "file:///etc/hostname"]]) {
      await page.evaluate((t) => { location.href = t; }, target).catch(() => {});
      await H.sleep(1200);
      const url = await page.evaluate(() => location.href).catch(() => "?");
      record(`the window can't be navigated away ${name}`, url.startsWith(TB) && !url.includes("/redirect"), `on ${url}`);
    }

    // 5. An iframe is a window with no address bar drawn inside the app: it may
    //    not load another site, and never gets the bridge.
    await page.evaluate((evil) => {
      const f = document.createElement("iframe");
      f.src = evil + "/";
      document.body.append(f);
    }, EVIL);
    await H.sleep(1500);
    const frameUrls = page.frames().map((f) => f.url());
    record("a cross-origin iframe is blocked", !frameUrls.some((u) => u.startsWith(EVIL)), frameUrls.join(" | "));
    for (const f of page.frames()) {
      if (f === page.mainFrame()) continue;
      const bridge = await f.evaluate(() => typeof window.tbDesktop).catch(() => "unreachable");
      record("a subframe has no bridge", bridge === "undefined" || bridge === "unreachable", bridge);
    }

    // 6. window.open: the browser gets http(s) links, everything else is dropped.
    lines.length = 0;
    await page.evaluate(() => {
      for (const u of ["https://example.com/ok", "file:///etc/passwd", "ms-msdt:/id PCWDiagnostic", "javascript:alert(1)"]) {
        window.open(u, "_blank");
      }
    });
    await H.sleep(800);
    const opened = lines.filter((l) => l.includes("openExternal")).map((l) => l.split("openExternal ")[1]);
    record("only http(s) links reach the system browser", opened.length === 1 && opened[0] === "https://example.com/ok",
      opened.join(", ") || "nothing opened");

    // 7. Permissions are granted by Electron unless refused, with no prompt in
    //    front of them.
    const perms = await page.evaluate(async () => {
      const r = { notifications: Notification.permission };
      r.geolocation = await new Promise((res) =>
        navigator.geolocation.getCurrentPosition(() => res("granted"), (e) => res("code" + e.code)));
      try { r.clipboardRead = (await navigator.permissions.query({ name: "clipboard-read" })).state; }
      catch (e) { r.clipboardRead = "error"; }
      return r;
    });
    record("notifications still work", perms.notifications === "granted", perms.notifications);
    record("location is refused", perms.geolocation === "code1", `getCurrentPosition -> ${perms.geolocation}`);
    record("reading the clipboard is refused", perms.clipboardRead === "denied", perms.clipboardRead);
  } catch (e) {
    record("the security checks ran to completion", false, e.message);
  } finally {
    if (conn) conn.browser.disconnect();
    H.killAll();
    for (const s of servers) s.close();
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length ? 1 : 0);
})();
