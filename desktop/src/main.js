// take-back desktop (proof of concept).
//
// Loads the ordinary take-back web client and adds one thing a browser can't
// do: screen sharing that carries ONE application's audio. The web client is
// untouched — preload.js wraps getDisplayMedia so the native audio arrives as
// an extra track on the screen stream, which the existing call code already
// sends to everyone and plays from the screen tile.
"use strict";

const { app, BrowserWindow, desktopCapturer, ipcMain, session, shell } = require("electron");
const path = require("path");
const audio = require("./audio-sources");

const SERVER = (process.env.TB_SERVER || "https://takeback.chain-of-thought.org").replace(/\/$/, "");
const START_URL = process.env.TB_START_URL || SERVER + "/";

// Test mode drives the whole flow unattended (see test/pipeline.test.js):
// fake camera/mic, no picker, share this window's own frame + a test tone.
const TEST = process.env.TB_TEST === "1";
if (TEST) {
  app.commandLine.appendSwitch("use-fake-device-for-media-stream");
  app.commandLine.appendSwitch("use-fake-ui-for-media-stream");
}

let win = null;
let pendingShare = null; // { sourceId, audioId } chosen for the next getDisplayMedia
let stopAudio = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    title: "take-back",
    backgroundColor: "#0b0d11",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      sandbox: true,
      // The call engine starts an AudioContext without a click when a call
      // auto-joins from a link; a desktop app shouldn't need the gesture.
      autoplayPolicy: "no-user-gesture-required",
    },
  });
  // The preload bridge belongs to the take-back server only. Links in chat go
  // to the system browser, and the window can't be navigated to another site
  // with the bridge still attached.
  const allowedOrigin = new URL(START_URL).origin;
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (event, url) => {
    if (new URL(url).origin !== allowedOrigin) {
      event.preventDefault();
      if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    }
  });

  if (TEST) {
    win.webContents.on("console-message", (event) => {
      console.log(`[page] ${event.message}`);
    });
  }

  win.loadURL(START_URL);
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
  stopAudio = audio.start(id, (chunk) => {
    // The sender can disappear mid-stream (window closed, page reloaded).
    if (!target.isDestroyed()) target.send("audio:chunk", chunk);
    else stopAppAudio();
  });
  return true;
});

ipcMain.handle("audio:stop", () => { stopAppAudio(); return true; });

// ---- lifecycle -------------------------------------------------------------------

app.whenReady().then(() => {
  installDisplayMediaHandler();
  createWindow();
  if (TEST) runTestScript();
});

app.on("window-all-closed", () => app.quit());

// In test mode, press Present once the call is up, so the test only has to
// watch the far side. Kept here (not in the page) so the web client stays
// exactly as deployed.
function runTestScript() {
  const click = `(() => {
    const b = [...document.querySelectorAll('button')].find(x => /Present screen/.test(x.textContent));
    if (b) { b.click(); return true; } return false;
  })()`;
  const tryClick = async (attempt = 0) => {
    if (!win) return;
    const ok = await win.webContents.executeJavaScript(click).catch(() => false);
    if (ok) { console.log("[test] pressed Present"); return; }
    if (attempt < 60) setTimeout(() => tryClick(attempt + 1), 500);
    else console.log("[test] Present button never appeared");
  };
  win.webContents.once("did-finish-load", () => setTimeout(tryClick, 3000));
}
