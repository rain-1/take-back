// End-to-end proof of the desktop audio pipeline, in a real call:
//
//   audio source (generated 440 Hz tone) -> main process -> IPC -> preload
//   -> AudioWorklet -> MediaStream track added to the screen share
//   -> WebRTC -> the OTHER participant's screen tile.
//
// "alice" is this Electron app, joining through the ordinary web client.
// "bob" is a headless Chrome running the same client, as any other participant
// would. The test listens to what bob's screen tile plays and checks for the
// tone's frequency — so it can't be satisfied by the fake microphone on the
// camera tile, or by silence that merely has an audio track attached.
//
// Then a control run shares with no app audio and expects no audio track.
//
//   node test/pipeline.test.js         (needs Go, Chrome, and a display)
"use strict";

const { spawn, execFileSync } = require("child_process");
const path = require("path");
const fs = require("fs");
const os = require("os");
const puppeteer = require("puppeteer-core");

const ROOT = path.resolve(__dirname, "..", "..");
const DESKTOP = path.resolve(__dirname, "..");
const GO = process.env.GO || "go";
const CHROME = process.env.CHROME || "/usr/bin/google-chrome";
const API_PORT = 18591, WEB_PORT = 18590;
const WEB = `http://127.0.0.1:${WEB_PORT}`;
const TONE_HZ = 440;

const procs = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(cmd, args, opts = {}) {
  const p = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"], ...opts });
  procs.push(p);
  return p;
}

async function waitFor(url, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { if ((await fetch(url)).ok) return; } catch (_) { /* not up yet */ }
    await sleep(250);
  }
  throw new Error(`timed out waiting for ${url}`);
}

async function startTakeBack() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-test-"));
  const serverBin = path.join(tmp, "server"), webBin = path.join(tmp, "web");
  execFileSync(GO, ["build", "-o", serverBin, "./cmd/server"], { cwd: ROOT, stdio: "inherit" });
  execFileSync(GO, ["build", "-o", webBin, "./cmd/web"], { cwd: ROOT, stdio: "inherit" });
  fs.mkdirSync(path.join(tmp, "media"));
  run(serverBin, ["-addr", `127.0.0.1:${API_PORT}`, "-db", path.join(tmp, "t.db"), "-media", path.join(tmp, "media")]);
  await waitFor(`http://127.0.0.1:${API_PORT}/api/version`);
  run(webBin, ["-addr", `127.0.0.1:${WEB_PORT}`, "-backend", `http://127.0.0.1:${API_PORT}`]);
  await waitFor(`${WEB}/api/version`);
}

function startElectron(room, audioId) {
  const electron = require("electron"); // resolves to the binary path in Node
  const p = run(electron, [DESKTOP, "--no-sandbox"], {
    env: {
      ...process.env,
      TB_TEST: "1",
      TB_TEST_AUDIO: audioId,
      TB_START_URL: `${WEB}/call.html?room=${room}&nick=alice`,
    },
  });
  p.stdout.on("data", (d) => process.stdout.write(`  [electron] ${d}`));
  p.stderr.on("data", (d) => {
    const s = String(d);
    // Chromium is chatty on stderr about GPU/dbus on a headless-ish Linux box.
    if (/\[test\]|error|Error/.test(s) && !/dbus|gpu|GPU|viz|ALSA|Fontconfig/.test(s)) process.stdout.write(`  [electron] ${s}`);
  });
  return p;
}

// Listen to bob's screen tile: how many audio tracks it has, and the loudest
// frequency and level over a couple of seconds.
async function measureScreenTile(page) {
  await page.waitForSelector('[data-tile$="-screen"] video', { timeout: 45000 });
  await sleep(2500); // let the jitter buffer and the worklet's prebuffer settle
  return page.evaluate(async () => {
    const video = document.querySelector('[data-tile$="-screen"] video');
    const tracks = video.srcObject ? video.srcObject.getAudioTracks() : [];
    if (!tracks.length) return { audioTracks: 0 };
    const ctx = new AudioContext();
    const src = ctx.createMediaStreamSource(new MediaStream([tracks[0]]));
    const an = ctx.createAnalyser();
    an.fftSize = 8192;
    src.connect(an);
    const bins = new Float32Array(an.frequencyBinCount);
    const time = new Float32Array(an.fftSize);
    let bestHz = 0, bestDb = -Infinity, maxRms = 0;
    for (let i = 0; i < 25; i++) {
      await new Promise((r) => setTimeout(r, 100));
      an.getFloatFrequencyData(bins);
      an.getFloatTimeDomainData(time);
      let peak = 1;
      for (let b = 2; b < bins.length; b++) if (bins[b] > bins[peak]) peak = b;
      if (bins[peak] > bestDb) { bestDb = bins[peak]; bestHz = (peak * ctx.sampleRate) / an.fftSize; }
      let sum = 0; for (const v of time) sum += v * v;
      maxRms = Math.max(maxRms, Math.sqrt(sum / time.length));
    }
    ctx.close();
    return { audioTracks: tracks.length, peakHz: Math.round(bestHz), peakDb: Math.round(bestDb), rms: maxRms };
  });
}

async function scenario(browser, name, audioId, check) {
  const room = "POC" + Math.random().toString(36).slice(2, 7).toUpperCase();
  console.log(`\n▶ ${name} (room ${room})`);
  const page = await browser.newPage();
  page.on("pageerror", (e) => console.log(`  [bob] page error: ${e.message}`));
  await page.goto(`${WEB}/call.html?room=${room}&nick=bob`, { waitUntil: "domcontentloaded" });
  const app = startElectron(room, audioId);
  try {
    const result = await measureScreenTile(page);
    console.log(`  bob's screen tile: ${JSON.stringify(result)}`);
    check(result);
    console.log(`  ✓ ${name}`);
  } finally {
    app.kill();
    await page.close();
    await sleep(1000);
  }
}

function assert(cond, msg) { if (!cond) throw new Error(msg); }

(async () => {
  let browser;
  try {
    await startTakeBack();
    browser = await puppeteer.launch({
      executablePath: CHROME,
      headless: true,
      args: ["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream",
             "--autoplay-policy=no-user-gesture-required"],
    });

    await scenario(browser, "app audio reaches the other person's screen tile", `tone:${TONE_HZ}`, (r) => {
      assert(r.audioTracks === 1, `expected 1 audio track on the screen tile, got ${r.audioTracks}`);
      // FFT bin width is ~5.9 Hz at 48k/8192; allow a few bins.
      assert(Math.abs(r.peakHz - TONE_HZ) <= 20, `expected a ${TONE_HZ} Hz peak, heard ${r.peakHz} Hz`);
      assert(r.rms > 0.02, `screen audio too quiet to be the tone (rms ${r.rms.toFixed(4)})`);
    });

    await scenario(browser, "control: no app audio chosen -> no audio on the screen tile", "", (r) => {
      assert(r.audioTracks === 0, `expected no audio track without an app chosen, got ${r.audioTracks}`);
    });

    console.log("\nPASS");
    process.exitCode = 0;
  } catch (e) {
    console.error(`\nFAIL: ${e.message}`);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close().catch(() => {});
    for (const p of procs) { try { p.kill(); } catch (_) { /* gone */ } }
  }
})();
