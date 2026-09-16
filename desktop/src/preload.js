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

let audioCallback = null;
ipcRenderer.on("audio:chunk", (_event, chunk) => {
  if (audioCallback) audioCallback(chunk);
});

contextBridge.exposeInMainWorld("tbDesktop", {
  version: 1,
  pickShare: () => ipcRenderer.invoke("share:pick"),
  startAudio: (id) => ipcRenderer.invoke("audio:start", String(id)),
  stopAudio: () => ipcRenderer.invoke("audio:stop"),
  onAudio: (cb) => { audioCallback = typeof cb === "function" ? cb : null; },
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
        const out = outputs[0];
        const L = out[0], R = out[1] || out[0], n = L.length;
        // Build up ~40 ms before playing, and again after any underrun, so the
        // helper's delivery jitter doesn't become audible clicks.
        if (!this.primed) {
          if (this.frames < 1920) { L.fill(0); R.fill(0); return true; }
          this.primed = true;
        }
        for (let i = 0; i < n; i++) {
          if (!this.queue.length) { L.fill(0, i); R.fill(0, i); this.primed = false; break; }
          const f = this.queue[0];
          L[i] = f[this.offset];
          R[i] = f[this.offset + 1];
          this.offset += 2;
          this.frames--;
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
    const ctx = new AudioContext({ sampleRate: 48000, latencyHint: "interactive" });
    const url = URL.createObjectURL(new Blob([WORKLET], { type: "text/javascript" }));
    try { await ctx.audioWorklet.addModule(url); } finally { URL.revokeObjectURL(url); }
    const node = new AudioWorkletNode(ctx, "tb-pcm-player",
      { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2] });
    const dest = ctx.createMediaStreamDestination();
    dest.channelCount = 2;
    node.connect(dest);
    active = { ctx, node };
    api.onAudio((chunk) => node.port.postMessage(chunk, [chunk.buffer]));
    await api.startAudio(id);
    if (ctx.state === "suspended") await ctx.resume();
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
