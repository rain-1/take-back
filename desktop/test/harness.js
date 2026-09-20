// Shared test plumbing: a throwaway take-back server built from this checkout,
// and the desktop app launched against it.
"use strict";

const { spawn, execFileSync } = require("child_process");
const path = require("path");
const fs = require("fs");
const os = require("os");

// The take-back checkout whose server the tests run. TB_SOURCE_ROOT points at
// another one, e.g. a newer web client than this branch has.
const ROOT = process.env.TB_SOURCE_ROOT || path.resolve(__dirname, "..", "..");
const DESKTOP = path.resolve(__dirname, "..");
const GO = process.env.GO || "go";
const CHROME = process.env.CHROME || "/usr/bin/google-chrome";

const procs = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(cmd, args, opts = {}) {
  const p = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"], ...opts });
  procs.push(p);
  return p;
}

function killAll() {
  for (const p of procs) { try { p.kill(); } catch (_) { /* gone */ } }
}

async function waitFor(url, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { if ((await fetch(url)).ok) return; } catch (_) { /* not up yet */ }
    await sleep(250);
  }
  throw new Error(`timed out waiting for ${url}`);
}

// Build and start server + web on the given ports. Returns the web base URL.
async function startTakeBack({ apiPort, webPort, openRegistration = false }) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "tb-desktop-test-"));
  const serverBin = path.join(tmp, "server"), webBin = path.join(tmp, "web");
  execFileSync(GO, ["build", "-o", serverBin, "./cmd/server"], { cwd: ROOT, stdio: "inherit" });
  execFileSync(GO, ["build", "-o", webBin, "./cmd/web"], { cwd: ROOT, stdio: "inherit" });
  fs.mkdirSync(path.join(tmp, "media"));
  const args = ["-addr", `127.0.0.1:${apiPort}`, "-db", path.join(tmp, "t.db"), "-media", path.join(tmp, "media")];
  if (openRegistration) args.push("-open-registration");
  run(serverBin, args);
  await waitFor(`http://127.0.0.1:${apiPort}/api/version`);
  run(webBin, ["-addr", `127.0.0.1:${webPort}`, "-backend", `http://127.0.0.1:${apiPort}`]);
  const web = `http://127.0.0.1:${webPort}`;
  await waitFor(`${web}/api/version`);
  return { web, tmp };
}

// Launch the desktop app. Lines it prints go to onLine as well as the console.
function startElectron(env, onLine = () => {}, extraArgs = []) {
  const electron = require("electron"); // the binary's path, when required from Node
  const p = run(electron, [DESKTOP, "--no-sandbox", ...extraArgs], { env: { ...process.env, TB_TEST: "1", ...env } });
  const handle = (d) => {
    for (const line of String(d).split("\n")) {
      if (!line.trim()) continue;
      // Chromium is chatty about GPU/dbus on a desktop-less Linux box.
      if (/dbus|gpu|GPU|viz|ALSA|Fontconfig|Security Warning|Content-Security|unsafe-eval|electronjs|This warning|once the app|For more information|risks\.|Policy set/.test(line)) continue;
      process.stdout.write(`  [electron] ${line}\n`);
      onLine(line);
    }
  };
  p.stdout.on("data", handle);
  p.stderr.on("data", handle);
  return p;
}

function assert(cond, msg) { if (!cond) throw new Error(msg); }

module.exports = { ROOT, DESKTOP, CHROME, sleep, run, killAll, waitFor, startTakeBack, startElectron, assert };
