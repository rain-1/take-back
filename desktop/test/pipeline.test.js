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

const puppeteer = require("puppeteer-core");
const H = require("./harness");

const API_PORT = 18591, WEB_PORT = 18590;
const TONE_HZ = 440;
const { sleep, assert } = H;
let WEB = "";

function startElectron(room, audioId) {
  return H.startElectron({
    TB_TEST_PRESENT: "1",
    TB_TEST_AUDIO: audioId,
    TB_START_URL: `${WEB}/call.html?room=${room}&nick=alice`,
    ...(process.env.TB_USER_DATA ? { TB_USER_DATA: process.env.TB_USER_DATA } : {}),
  });
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

(async () => {
  let browser;
  try {
    WEB = (await H.startTakeBack({ apiPort: API_PORT, webPort: WEB_PORT })).web;
    browser = await puppeteer.launch({
      executablePath: H.CHROME,
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
    H.killAll();
  }
})();
