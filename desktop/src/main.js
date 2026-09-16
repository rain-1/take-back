// take-back desktop (proof of concept).
//
// Loads the ordinary take-back web client and adds one thing a browser can't
// do: screen sharing that carries ONE application's audio. The web client is
// untouched — preload.js wraps getDisplayMedia so the native audio arrives as
// an extra track on the screen stream, which the existing call code already
// sends to everyone and plays from the screen tile.
"use strict";

const { app, BrowserWindow, desktopCapturer, ipcMain, screen, session, shell } = require("electron");
const fs = require("fs");
const path = require("path");
const audio = require("./audio-sources");

// Losing stdout/stderr must never crash the app. When whatever launched it
// closes the pipe (a terminal, a test runner that stopped first), the next
// console.log throws EPIPE — uncaught in the main process that's a modal error
// dialog, which is exactly what popped up on River's screen.
for (const stream of [process.stdout, process.stderr]) {
  if (stream && stream.on) stream.on("error", () => {});
}

const SERVER = (process.env.TB_SERVER || "https://takeback.chain-of-thought.org").replace(/\/$/, "");
const START_URL = process.env.TB_START_URL || SERVER + "/";

// Test mode (see test/): fake camera/mic, no picker (share this window's own
// frame + TB_TEST_AUDIO), and side effects that would need a person — opening
// the system browser, a save dialog — are logged instead. TB_TEST_PRESENT=1
// also presses Present once the call is up.
const TEST = process.env.TB_TEST === "1";
if (TEST) {
  app.commandLine.appendSwitch("use-fake-device-for-media-stream");
  app.commandLine.appendSwitch("use-fake-ui-for-media-stream");
  // A test app must not outlive its test runner: if the runner is killed, an
  // orphaned app keeps running (and, on WSL, keeps a window on the Windows
  // desktop). Quit as soon as the parent process is gone.
  const parent = process.ppid;
  setInterval(() => {
    try { process.kill(parent, 0); } catch (_) { app.quit(); }
  }, 1000).unref();
}
// Test windows stay hidden unless a test needs one on screen (TB_TEST_SHOW=1):
// on WSL every Linux window appears on the Windows desktop of whoever is
// sitting at that PC.
const SHOW_WINDOW = !TEST || process.env.TB_TEST_SHOW === "1";
// Lets a test drive the real window with Puppeteer.
if (process.env.TB_DEBUG_PORT) {
  app.commandLine.appendSwitch("remote-debugging-port", process.env.TB_DEBUG_PORT);
}
// A throwaway profile per test run, so tests don't share logins.
if (process.env.TB_USER_DATA) app.setPath("userData", process.env.TB_USER_DATA);

function openExternal(url) {
  if (TEST) return console.log(`[test] openExternal ${url}`);
  shell.openExternal(url);
}

let win = null;
let pendingShare = null; // { sourceId, audioId } chosen for the next getDisplayMedia
let stopAudio = null;

// ---- window size and position, remembered between launches ------------------

const boundsFile = () => path.join(app.getPath("userData"), "window.json");

function loadBounds() {
  try {
    const b = JSON.parse(fs.readFileSync(boundsFile(), "utf8"));
    // A monitor that was unplugged since last time would leave the window
    // somewhere nobody can see it; only restore bounds that are still on-screen.
    const area = screen.getDisplayMatching(b).workArea;
    const visible = b.x < area.x + area.width && b.x + b.width > area.x &&
                    b.y < area.y + area.height && b.y + b.height > area.y;
    return visible ? b : null;
  } catch (_) {
    return null; // first launch, or an unreadable file: use the defaults
  }
}

function saveBounds() {
  if (!win || win.isDestroyed() || win.isMinimized()) return;
  try {
    fs.writeFileSync(boundsFile(), JSON.stringify({ ...win.getNormalBounds(), maximized: win.isMaximized() }));
  } catch (_) { /* not worth failing over */ }
}

function createWindow() {
  const saved = loadBounds();
  win = new BrowserWindow({
    width: saved ? saved.width : 1280,
    height: saved ? saved.height : 820,
    ...(saved ? { x: saved.x, y: saved.y } : {}),
    minWidth: 420,
    minHeight: 360,
    show: SHOW_WINDOW,
    title: "take-back",
    icon: path.join(__dirname, "..", "build", "icon.png"),
    backgroundColor: "#0b0d11",
    // The default File/Edit/View menu is noise in a chat app, but its
    // shortcuts (reload, zoom, dev tools) are useful: Alt reveals it.
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      sandbox: true,
      // The call engine starts an AudioContext without a click when a call
      // auto-joins from a link; a desktop app shouldn't need the gesture.
      autoplayPolicy: "no-user-gesture-required",
      // A hidden test window must still run its timers, WebRTC and audio at
      // full speed, or tests would measure throttling instead of the app.
      backgroundThrottling: SHOW_WINDOW,
    },
  });
  // The preload bridge belongs to the take-back server only. Links in chat go
  // to the system browser, and the window can't be navigated to another site
  // with the bridge still attached.
  const allowedOrigin = new URL(START_URL).origin;
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) openExternal(url);
    return { action: "deny" };
  });
  // Leaving take-back in this window is simply refused. Links people click
  // open in new windows (target=_blank) and go to the browser above; a page
  // that tries to navigate ITSELF elsewhere is not a click, and must not be
  // able to open things on the user's machine either.
  win.webContents.on("will-navigate", (event, url) => {
    if (new URL(url).origin !== allowedOrigin) {
      event.preventDefault();
      if (TEST) console.log(`[test] blocked navigation to ${url}`);
    }
  });

  if (TEST) {
    // Tests run on real people's computers too: keep the app's own speakers
    // silent. The other participant in a test call is a browser with Chromium's
    // fake microphone, which beeps once a second — and it played out loud on
    // River's PC. Muting playback changes nothing that's measured: the checks
    // listen on the far side. TB_TEST_AUDIBLE=1 opts back in.
    if (process.env.TB_TEST_AUDIBLE !== "1") win.webContents.setAudioMuted(true);
    win.webContents.on("console-message", (event) => {
      console.log(`[page] ${event.message}`);
    });
  }

  win.loadURL(START_URL);
  if (saved && saved.maximized) win.maximize();
  let saveTimer = null;
  const saveSoon = () => { clearTimeout(saveTimer); saveTimer = setTimeout(saveBounds, 500); };
  win.on("resize", saveSoon);
  win.on("move", saveSoon);
  win.on("close", saveBounds);
  win.on("closed", () => { stopAppAudio(); win = null; });
}

// ---- screen source selection -----------------------------------------------

// Electron has no built-in screen picker, so getDisplayMedia is answered here
// with whatever the user chose in our picker (preload asks for that first).
function installDisplayMediaHandler() {
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    const choice = pendingShare;
    pendingShare = null;
    if (!choice) return callback({}); // nothing picked: deny
    if (choice.sourceId === "frame") {
      // Share the requesting page itself. Used by the automated test, where
      // there may be no real screen to capture.
      return callback({ video: request.frame });
    }
    const sources = await desktopCapturer.getSources({ types: ["screen", "window"] });
    const source = sources.find((s) => s.id === choice.sourceId);
    // Video only. Chromium's own `audio: 'loopback'` is whole-system audio —
    // exactly what this app exists to replace.
    callback(source ? { video: source } : {});
  });
}

async function openPicker() {
  if (TEST) {
    return { sourceId: "frame", audioId: process.env.TB_TEST_AUDIO ?? "tone:440" };
  }
  const [sources, apps] = await Promise.all([
    desktopCapturer.getSources({ types: ["screen", "window"], thumbnailSize: { width: 320, height: 180 } }),
    audio.list(),
  ]);
  return new Promise((resolve) => {
    const picker = new BrowserWindow({
      parent: win, modal: true, width: 860, height: 640, title: "Share your screen",
      backgroundColor: "#11141b",
      webPreferences: { preload: path.join(__dirname, "picker-preload.js"), contextIsolation: true, sandbox: true },
    });
    let done = false;
    const finish = (choice) => {
      if (done) return;
      done = true;
      ipcMain.removeHandler("picker:data");
      ipcMain.removeAllListeners("picker:choose");
      if (!picker.isDestroyed()) picker.close();
      resolve(choice);
    };
    ipcMain.handle("picker:data", () => ({
      sources: sources.map((s) => ({ id: s.id, name: s.name, thumb: s.thumbnail.toDataURL() })),
      apps,
    }));
    ipcMain.on("picker:choose", (_e, choice) => finish(choice));
    picker.on("closed", () => finish({ cancelled: true }));
    picker.setMenuBarVisibility(false);
    picker.loadFile(path.join(__dirname, "picker.html"));
  });
}

// ---- app audio ----------------------------------------------------------------

function stopAppAudio() {
  if (stopAudio) { try { stopAudio(); } catch (_) { /* already stopped */ } }
  stopAudio = null;
}

ipcMain.handle("share:pick", async () => {
  const choice = await openPicker();
  if (!choice || choice.cancelled) return { cancelled: true };
  pendingShare = choice;
  return { cancelled: false, audioId: choice.audioId || null };
});

ipcMain.handle("audio:start", (event, id) => {
  stopAppAudio();
  const target = event.sender;
  let sent = 0;
  const stopSource = audio.start(id, (chunk) => {
    // The sender can disappear mid-stream (window closed, page reloaded).
    if (!target.isDestroyed()) { target.send("audio:chunk", chunk); sent++; }
    else stopAppAudio();
  });
  const diag = TEST ? setInterval(() => console.log(`[tbdiag] main sent=${sent}`), 1000) : null;
  stopAudio = () => { if (diag) clearInterval(diag); stopSource(); };
  return true;
});

ipcMain.handle("audio:stop", () => { stopAppAudio(); return true; });

// ---- lifecycle -------------------------------------------------------------------

// One window, however many times it's launched: a second launch brings the
// existing window forward instead of opening a second, separately-logged-in copy.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  });
}

app.whenReady().then(() => {
  installDisplayMediaHandler();
  createWindow();
  if (TEST) {
    // Save downloads somewhere known instead of prompting, and say how it went.
    session.defaultSession.on("will-download", (_e, item) => {
      const dir = process.env.TB_DOWNLOAD_DIR || app.getPath("temp");
      item.setSavePath(path.join(dir, item.getFilename()));
      item.once("done", (_ev, state) => console.log(`[test] download ${state} ${item.getFilename()}`));
    });
  }
  if (TEST) {
    // Proves which capture backend a packaged build found (names only).
    audio.list().then((l) => console.log(`[test] audio sources: ${l.map((a) => a.name).join(", ")}`));
    console.log(`[test] window visible: ${win.isVisible()}`);
  }
  if (TEST && process.env.TB_TEST_PRESENT === "1") runTestScript();
  if (TEST && process.env.TB_CONTROL_PORT) startTestControl(Number(process.env.TB_CONTROL_PORT));
});

app.on("window-all-closed", () => app.quit());

// Test-only window control, because Electron's DevTools protocol doesn't
// implement the Browser.*Window* commands. Loopback only, test mode only.
function startTestControl(port) {
  require("http").createServer((req, res) => {
    const act = {
      "/minimize": () => win.minimize(),
      "/restore": () => win.restore(),
      "/state": () => {},
      "/resize": () => win.setBounds({ width: 900, height: 700 }),
    }[req.url];
    if (!act || !win) { res.writeHead(404); return res.end(); }
    act();
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ minimized: win.isMinimized(), focused: win.isFocused(), visible: win.isVisible(),
                             bounds: win.getNormalBounds() }));
  }).listen(port, "127.0.0.1");
}

// In test mode, press Present once the call is up, so the test only has to
// watch the far side. Kept here (not in the page) so the web client stays
// exactly as deployed.
function runTestScript() {
  const click = `(() => {
    const b = [...document.querySelectorAll('button')].find(x => /Present screen/.test(x.textContent));
    if (b) { b.click(); return true; } return false;
  })()`;
  // TB_TEST_MUTE_MIC=1: mute the fake microphone first, so a live demo call
  // isn't full of Chromium's test beeps.
  const muteMic = `(() => {
    const b = [...document.querySelectorAll('button')].find(x => /Mic on/.test(x.textContent));
    if (b) { b.click(); return true; } return false;
  })()`;
  const tryClick = async (attempt = 0) => {
    if (!win) return;
    if (process.env.TB_TEST_MUTE_MIC === "1") {
      await win.webContents.executeJavaScript(muteMic).catch(() => false);
    }
    const ok = await win.webContents.executeJavaScript(click).catch(() => false);
    if (ok) { console.log("[test] pressed Present"); return; }
    if (attempt < 60) setTimeout(() => tryClick(attempt + 1), 500);
    else console.log("[test] Present button never appeared");
  };
  win.webContents.once("did-finish-load", () => setTimeout(tryClick, 3000));
}
