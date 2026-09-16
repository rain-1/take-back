// Per-application audio sources for screen sharing.
//
// Every platform backend honours one contract:
//   list()               -> [{ id, name, pid? }]  apps whose audio can be captured
//   start(id, onChunk)   -> stop()                 onChunk(Float32Array) receives
//                                                  interleaved stereo at 48 kHz
//
// The renderer never sees platform details: it asks for an id and receives PCM.
// Capture runs in helper processes rather than a Node addon, so there is no
// native module to rebuild for each Electron release.
"use strict";

const { spawn, execFile } = require("child_process");
const path = require("path");
const fs = require("fs");

const RATE = 48000;
const CHANNELS = 2;

// ---- test tone ---------------------------------------------------------------
// A generated sine, so the pipeline (helper -> IPC -> AudioWorklet -> WebRTC ->
// the other person's screen tile) can be proven on any machine, including one
// with no capturable apps at all.
// "tone:melody" is the same idea in a form that's pleasant to listen to in a
// live demo: a slow looping arpeggio with a soft pluck envelope.
const MELODY = [261.63, 329.63, 392.0, 493.88, 523.25, 493.88, 392.0, 329.63]; // Cmaj7 up and down
const NOTE_FRAMES = Math.round(RATE * 0.28);

function melodySample(n) {
  const i = Math.floor(n / NOTE_FRAMES);
  const t = (n % NOTE_FRAMES) / RATE;
  const f = MELODY[i % MELODY.length];
  const env = Math.exp(-t * 7) * Math.min(1, t * 400); // quick attack, gentle decay
  const g = 2 * Math.PI * f * (n / RATE);
  return 0.16 * env * (Math.sin(g) + 0.25 * Math.sin(2 * g));
}

const tone = {
  list: () => [
    { id: "tone:melody", name: "Demo music (looping arpeggio)" },
    { id: "tone:440", name: "Test tone (440 Hz)" },
  ],
  start(id, onChunk) {
    const melody = id === "tone:melody";
    const freq = Number(id.split(":")[1]) || 440;
    const frame = 480; // 10 ms
    let phase = 0;
    let sent = 0;
    const t0 = process.hrtime.bigint();
    // Generate against the wall clock rather than trusting setInterval's period,
    // which drifts and would slowly starve or flood the playback buffer.
    const timer = setInterval(() => {
      const elapsed = Number(process.hrtime.bigint() - t0) / 1e9;
      const due = Math.floor(elapsed * RATE) - sent;
      for (let n = 0; n + frame <= due; n += frame) {
        const buf = new Float32Array(frame * CHANNELS);
        for (let i = 0; i < frame; i++) {
          const s = melody ? melodySample(sent + i) : 0.25 * Math.sin(phase);
          phase += (2 * Math.PI * freq) / RATE;
          buf[i * 2] = s;
          buf[i * 2 + 1] = s;
        }
        if (phase > 2 * Math.PI) phase %= 2 * Math.PI;
        sent += frame;
        onChunk(buf);
      }
    }, 5);
    return () => clearInterval(timer);
  },
};

// ---- raw PCM from a child's stdout ------------------------------------------
// Pipe reads don't respect sample boundaries, so carry any partial float over
// to the next chunk rather than decoding a torn sample as noise.
function pcmFromChild(child, onChunk) {
  let rest = Buffer.alloc(0);
  child.stdout.on("data", (data) => {
    const all = rest.length ? Buffer.concat([rest, data]) : data;
    const usable = all.length - (all.length % (4 * CHANNELS));
    if (usable > 0) {
      // Copy into a fresh, correctly aligned ArrayBuffer for the Float32Array.
      const ab = all.buffer.slice(all.byteOffset, all.byteOffset + usable);
      onChunk(new Float32Array(ab));
    }
    rest = all.subarray(usable);
  });
  child.stderr.on("data", (d) => console.error("[app-audio]", String(d).trim()));
  return () => { try { child.kill(); } catch (_) { /* already gone */ } };
}

// ---- Windows: native helper using WASAPI process loopback ---------------------
function winHelperPath() {
  if (process.env.TB_APP_AUDIO_EXE) return process.env.TB_APP_AUDIO_EXE;
  const candidates = [
    path.join(process.resourcesPath || "", "tb-app-audio.exe"),
    path.join(__dirname, "..", "native", "win", "bin", "tb-app-audio.exe"),
  ];
  return candidates.find((p) => { try { return fs.statSync(p).isFile(); } catch (_) { return false; } });
}

const windows = {
  list() {
    const exe = winHelperPath();
    if (!exe) return Promise.resolve([]);
    return new Promise((resolve) => {
      execFile(exe, ["--list"], { timeout: 5000 }, (err, stdout) => {
        if (err) { console.error("[app-audio] list failed:", err.message); return resolve([]); }
        try {
          resolve(JSON.parse(stdout).map((a) => ({ id: `pid:${a.pid}`, name: a.name, pid: a.pid })));
        } catch (e) { console.error("[app-audio] bad list output:", e.message); resolve([]); }
      });
    });
  },
  start(id, onChunk) {
    const exe = winHelperPath();
    if (!exe) throw new Error("tb-app-audio.exe not found");
    const pid = id.split(":")[1];
    return pcmFromChild(spawn(exe, ["--capture", pid], { windowsHide: true }), onChunk);
  },
};

// ---- Linux: PipeWire's own tools, no compiled code ---------------------------
// Every application playing audio is its own PipeWire node (media.class
// Stream/Output/Audio), so per-app capture is just "record that node".
// NOTE: written against PipeWire's documented tools but not yet run on a real
// PipeWire desktop — the machine this was built on has no PipeWire.
const linux = {
  list() {
    return new Promise((resolve) => {
      execFile("pw-dump", [], { timeout: 5000, maxBuffer: 32 * 1024 * 1024 }, (err, stdout) => {
        if (err) return resolve([]); // no PipeWire: no app audio, not an error
        try {
          const nodes = JSON.parse(stdout).filter(
            (o) => o.type === "PipeWire:Interface:Node" &&
                   o.info?.props?.["media.class"] === "Stream/Output/Audio");
          resolve(nodes.map((n) => {
            const p = n.info.props;
            const app = p["application.name"] || p["node.name"] || `node ${n.id}`;
            const media = p["media.name"] ? ` — ${p["media.name"]}` : "";
            return { id: `pw:${n.id}`, name: app + media, pid: Number(p["application.process.id"]) || undefined };
          }));
        } catch (e) { console.error("[app-audio] pw-dump parse:", e.message); resolve([]); }
      });
    });
  },
  start(id, onChunk) {
    const node = id.split(":")[1];
    const child = spawn("pw-record", [
      "--target", node, "--rate", String(RATE), "--channels", String(CHANNELS),
      "--format", "f32", "-",
    ]);
    return pcmFromChild(child, onChunk);
  },
};

const platform = process.platform === "win32" ? windows : process.platform === "linux" ? linux : null;

async function list() {
  const apps = platform ? await platform.list() : [];
  // The tone is always offered: it's how you check the pipeline end to end.
  return [...apps, ...tone.list()];
}

function start(id, onChunk) {
  if (id.startsWith("tone:")) return tone.start(id, onChunk);
  if (!platform) throw new Error(`per-app audio isn't implemented on ${process.platform} yet`);
  return platform.start(id, onChunk);
}

module.exports = { list, start, RATE, CHANNELS };
