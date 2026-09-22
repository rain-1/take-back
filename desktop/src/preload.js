// Bridges the take-back web page to native per-application audio.
//
// Two parts:
//  - `window.tbDesktop`, a deliberately small API (pick a share, start/stop an
//    audio source, receive PCM). It's all the page can reach — no filesystem,
//    no processes, no arbitrary IPC.
//  - a wrapper around navigator.mediaDevices.getDisplayMedia, installed into
//    the page's own JavaScript world, which adds the chosen app's audio to the
//    screen stream. The deployed web client needs no changes: its screen-share
//    code already sends every track the capture returns, and every other client
//    already plays screen audio from the screen tile.
"use strict";

const { contextBridge, ipcRenderer, webFrame } = require("electron");

// This preload is attached to the BrowserWindow, so it also starts when that
// window temporarily visits the identity provider during sign-in. Never give it
// page take-back's native audio bridge. Main-process permission checks provide
// a second boundary, but the bridge should not exist there in the first place.
const serverOrigin = (() => {
  try {
    const server = (process.env.TB_SERVER || "https://takeback.chain-of-thought.org").replace(/\/$/, "");
    return new URL(process.env.TB_START_URL || server + "/").origin;
  } catch (_) {
    return null;
  }
})();
if (location.origin !== serverOrigin) {
  // Preload scripts cannot return at top level; keep all bridge setup inside
  // the trusted-origin branch.
} else {

let audioCallback = null;
const stats = { received: 0, delivered: 0 }; // diagnostics, reported in test mode
ipcRenderer.on("audio:chunk", (_event, chunk) => {
  stats.received++;
  if (audioCallback) { stats.delivered++; audioCallback(chunk); }
});

contextBridge.exposeInMainWorld("tbDesktop", {
  version: 1,
  pickShare: () => ipcRenderer.invoke("share:pick"),
  startAudio: (id) => ipcRenderer.invoke("audio:start", String(id)),
  stopAudio: () => ipcRenderer.invoke("audio:stop"),
  onAudio: (cb) => { audioCallback = typeof cb === "function" ? cb : null; },
  // Per-stage counters for diagnosing audio that doesn't arrive. Only surfaced
  // (logged) when the app runs in test mode.
  debug: process.env.TB_TEST === "1",
  bridgeStats: () => ({ ...stats }),
});

// Everything below runs in the PAGE's world (it's injected as source text), so
// it can replace the getDisplayMedia the page's own scripts will call.
function installShim() {
  const api = window.tbDesktop;
  const md = navigator.mediaDevices;
  if (!api || !md || md.__tbDesktop) return;
  md.__tbDesktop = true;

  // Plays interleaved stereo Float32 chunks as they arrive from the helper.
  const WORKLET = `
    class TbPcmPlayer extends AudioWorkletProcessor {
      constructor() {
        super();
        this.queue = []; this.offset = 0; this.frames = 0; this.primed = false;
        this.port.onmessage = (e) => {
          const f = e.data;
          this.queue.push(f);
          this.frames += f.length / 2;
          // Bound the delay: if audio arrives faster than it plays (or playback
          // stalled), drop the oldest rather than let latency grow forever.
          while (this.frames > 14400 && this.queue.length > 1) {   // > 300 ms
            const old = this.queue.shift();
            this.frames -= (old.length - this.offset) / 2;
            this.offset = 0;
          }
        };
      }
      process(inputs, outputs) {
        this.calls = (this.calls || 0) + 1;
        if (this.calls % 375 === 0) { // ~1 s of 128-frame quanta
          this.port.postMessage({ stats: { played: this.played || 0, silent: this.silent || 0, queued: this.frames } });
        }
        const out = outputs[0];
        const L = out[0], R = out[1] || out[0], n = L.length;
        // Build up ~40 ms before playing, and again after any underrun, so the
        // helper's delivery jitter doesn't become audible clicks.
        if (!this.primed) {
          if (this.frames < 1920) { L.fill(0); R.fill(0); this.silent = (this.silent || 0) + n; return true; }
          this.primed = true;
        }
        for (let i = 0; i < n; i++) {
          if (!this.queue.length) { L.fill(0, i); R.fill(0, i); this.primed = false; this.silent = (this.silent || 0) + (n - i); break; }
          const f = this.queue[0];
          L[i] = f[this.offset];
          R[i] = f[this.offset + 1];
          this.offset += 2;
          this.frames--;
          this.played = (this.played || 0) + 1;
          if (this.offset >= f.length) { this.queue.shift(); this.offset = 0; }
        }
        return true;
      }
    }
    registerProcessor("tb-pcm-player", TbPcmPlayer);
  `;

  const original = md.getDisplayMedia.bind(md);
  let active = null;
  let cancelledAt = 0;

  async function appAudioTrack(id) {
    // sinkId "none": this context never plays anything locally — it only feeds
    // the WebRTC track — so it has no business depending on an output device,
    // which can be missing or fail to open.
    //
    // KNOWN, UNRESOLVED (2026-09-16): on WSL, for about an hour, ~40% of test
    // runs had the page's main thread block for ~7 s as sharing started, after
    // which the other side heard silence for good. Neither this change nor
    // anything else tried fixed it; it then stopped reproducing (21 straight
    // passes). The per-stage counters below exist to catch it if it returns:
    // run with TB_TEST=1 and read the [tbdiag] lines to see which stage stops.
    const ctx = new AudioContext({ sampleRate: 48000, latencyHint: "interactive", sinkId: { type: "none" } });
    const url = URL.createObjectURL(new Blob([WORKLET], { type: "text/javascript" }));
    try { await ctx.audioWorklet.addModule(url); } finally { URL.revokeObjectURL(url); }
    const node = new AudioWorkletNode(ctx, "tb-pcm-player",
      { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2] });
    const dest = ctx.createMediaStreamDestination();
    dest.channelCount = 2;
    node.connect(dest);
    active = { ctx, node };
    let posted = 0;
    api.onAudio((chunk) => { posted++; node.port.postMessage(chunk, [chunk.buffer]); });
    if (api.debug) {
      const started = performance.now();
      node.port.onmessage = (e) => {
        if (!e.data || !e.data.stats) return;
        const b = api.bridgeStats(), w = e.data.stats;
        console.log(`[tbdiag] t=${((performance.now() - started) / 1000).toFixed(1)}s ctx=${ctx.state}@${ctx.currentTime.toFixed(1)} ` +
          `ipc-received=${b.received} delivered=${b.delivered} posted=${posted} ` +
          `worklet-played=${w.played} silent=${w.silent} queued=${w.queued}`);
      };
    }
    await api.startAudio(id);
    if (ctx.state === "suspended") await ctx.resume();
    // One diagnostic line: if app audio ever arrives silent, the first question
    // is whether this context is actually running (its clock advances).
    const t0 = ctx.currentTime, w0 = performance.now();
    setTimeout(() => console.log(`[take-back desktop] app audio context ${ctx.state}, ` +
      `clock advanced ${(ctx.currentTime - t0).toFixed(2)}s while a 3s timer took ` +
      `${((performance.now() - w0) / 1000).toFixed(2)}s; page ${document.visibilityState}, ` +
      `focused ${document.hasFocus()}`), 3000);
    return dest.stream.getAudioTracks()[0];
  }

  function stopAppAudio() {
    if (!active) return;
    const { ctx, node } = active;
    active = null;
    api.onAudio(null);
    api.stopAudio();
    try { node.disconnect(); } catch (_) { /* already disconnected */ }
    ctx.close().catch(() => {});
  }

  md.getDisplayMedia = async function () {
    // The call code retries video-only when a request is refused. Someone who
    // just cancelled the picker must not be shown it a second time.
    if (Date.now() - cancelledAt < 1500) {
      throw new DOMException("Screen share cancelled", "NotAllowedError");
    }
    const choice = await api.pickShare();
    if (!choice || choice.cancelled) {
      cancelledAt = Date.now();
      throw new DOMException("Screen share cancelled", "NotAllowedError");
    }
    // Video only: the audio comes from the chosen app, not Chromium's
    // whole-system loopback.
    const stream = await original({ video: true, audio: false });
    if (choice.audioId) {
      try {
        stream.addTrack(await appAudioTrack(choice.audioId));
      } catch (e) {
        // Still share the screen; just say why there's no sound.
        console.warn("[take-back desktop] app audio unavailable:", e);
        stopAppAudio();
      }
    }
    // The call code ends a share with track.stop(), which fires no event, so
    // hook both that and a track ending by itself (e.g. the window closed).
    for (const t of stream.getTracks()) {
      const stop = t.stop.bind(t);
      t.stop = () => { stop(); stopAppAudio(); };
      t.addEventListener("ended", stopAppAudio);
    }
    return stream;
  };
}

webFrame.executeJavaScript(`(${installShim.toString()})()`);
}
