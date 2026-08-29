/**
 * TBCall — the take-back call engine, mountable into any container.
 *
 * It used to live inline in call.html, which meant the chat page could only
 * launch a call by opening a second tab. Now both use it: call.html mounts it
 * full-window after its lobby, and index.html mounts it into a panel above the
 * message list so you can read and type while you're on a call.
 *
 * Everything it renders lives under a single .tbc element and is addressed
 * through per-session element references — no global element ids — so it can sit
 * inside a page that has ids of its own.
 *
 *   TBCall.mount(el, { nick, room, onLeave, onStatus })
 *   TBCall.leave()
 *   TBCall.active()   // is a call running?
 *   TBCall.layout()   // re-fit the grid (call after resizing the container)
 */
window.TBCall = (function () {
  // Signaling is proxied same-origin by the web app, so derive it from the page.
  const SIGNAL_URL = (location.protocol === "https:" ? "wss:" : "ws:") + "//" + location.host + "/ws";
  // Public STUN so peers can discover their NAT-mapped address. Add TURN here
  // for networks where a direct path can't be punched.
  const ICE_CONFIG = { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] };

  // DROP_GRACE_MS is how long a peer may sit "disconnected" (frozen) before we
  // remove them from the call. Long enough to ride out a brief network blip,
  // short enough that a real drop clears quickly.
  const DROP_GRACE_MS = 6000;

  // Speaking detection thresholds (short-term RMS).
  const SPEAK_ON = 0.035;   // start showing as speaking
  const SPEAK_OFF = 0.020;  // drop below this to stop (hysteresis avoids flicker)
  const HANG_MS = 350;      // keep the ring this long after you stop talking

  // Preferences that outlive a call.
  const PREF_MIRROR = "tb.mirror", PREF_MIC = "tb.micId", PREF_CAM = "tb.camId";
  const PREF_STEREO = "tb.stereo", PREF_GAIN = "tb.micGain", PREF_FIT = "tb.videoFit";

  // Transmit mono by default; stereo is opt-in (it roughly doubles audio bitrate
  // and most mics are mono anyway). Stored as "1" (stereo) / "0" or unset (mono).
  function stereoWanted() { return localStorage.getItem(PREF_STEREO) === "1"; }
  // Scale-to-fit by default: show the whole frame rather than cropping it.
  function fillWanted() { return localStorage.getItem(PREF_FIT) === "fill"; }

  // The single live session, or null. One call at a time is the whole model:
  // there is one microphone and one camera.
  let S = null;
  // The AudioContext is shared across sessions — browsers cap how many you can
  // create, and re-creating one per call would eventually fail.
  let audioCtx = null;

  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };

  // ---- mount / unmount -----------------------------------------------------

  function mount(container, opts) {
    if (S) leave();
    S = {
      container,
      nick: opts.nick,
      room: (opts.room || "").toUpperCase(),
      onLeave: opts.onLeave || function () {},
      onStatus: opts.onStatus || function () {},
      // Whether to show the call code + Copy. A call launched from a chat is
      // identified by the conversation, not by a code you read out loud.
      showCode: opts.showCode !== false,

      ws: null, myId: null,
      cameraStream: null,   // the original camera+mic, kept so we can revert
      screenStream: null,   // active screen capture, if any
      peers: new Map(),     // peerId -> { pc, nick, ... }
      peerState: new Map(), // peerId -> { video, audio, screenId }
      peerVolumes: new Map(),
      vadTimers: new Map(),

      micOn: true, camOn: true,
      spotlight: null, // tile id blown up to fill the call area, or null
      leaving: false,
      reconnectTimer: null, reconnectDelay: 1000,
      mirrored: localStorage.getItem(PREF_MIRROR) !== "0",

      micGainValue: parseFloat(localStorage.getItem(PREF_GAIN) || "1") || 1,
      micGainNode: null, micDest: null, micSourceNode: null,
      gainChainActive: false, localRms: 0, meterTimer: null,
      ui: null,
    };
    buildUI();
    return start();
  }

  function buildUI() {
    const root = el("div", "tbc");
    root.classList.toggle("fill", fillWanted());

    // --- control bar ---
    const bar = el("div", "tbc-bar");
    const u = {};
    u.codeLabel = el("span", "tbc-status", "Call code:");
    u.code = el("span", "tbc-code", S.room);
    u.copy = el("button", "secondary", "Copy");
    u.pill = el("span", "tbc-pill", "connecting…");
    u.signalWarn = el("span", "tbc-pill tbc-warn tbc-hidden", "⚠ signaling");
    u.signalWarn.title = "The call keeps running; new peers can't join until this recovers";
    u.audioGate = el("button", "tbc-hidden", "🔊 Enable audio");
    u.mic = el("button", "secondary", "🎤 Mic on");
    u.cam = el("button", "secondary", "📷 Camera on");
    u.present = el("button", null, "🖥 Present screen");
    u.zoomOut = el("button", "secondary tbc-hidden", "⤡ Show everyone");
    u.zoomOut.title = "Back to the grid";
    u.settings = el("button", "secondary", "⚙");
    u.settings.title = "Devices & preferences";
    u.leave = el("button", "secondary", "Leave");
    if (S.showCode) bar.append(u.codeLabel, u.code, u.copy);
    bar.append(u.pill, u.signalWarn, el("span", "tbc-spacer"),
      u.audioGate, u.zoomOut, u.mic, u.cam, u.present, u.settings, u.leave);

    // --- settings panel ---
    const panel = el("div", "tbc-settings tbc-hidden");
    const row = (labelText, ...controls) => {
      const r = el("div", "tbc-setrow");
      r.append(el("label", null, labelText), ...controls);
      panel.append(r);
      return r;
    };
    u.micSelect = el("select");
    u.stereoSel = el("select");
    u.stereoSel.append(new Option("Mono (default)", "mono"), new Option("Stereo", "stereo"));
    u.fitSel = el("select");
    u.fitSel.append(new Option("Fit — show the whole frame", "fit"),
      new Option("Fill — crop to the tile", "fill"));
    u.camSelect = el("select");

    row("Microphone", u.micSelect);
    row("Transmit audio", u.stereoSel);

    const levelbox = el("div", "tbc-levelbox");
    const meter = el("div", "tbc-meter");
    meter.title = "Live level of what your peers hear";
    u.meterFill = el("div");
    meter.append(u.meterFill);
    u.gain = el("input");
    u.gain.type = "range"; u.gain.min = "0"; u.gain.max = "200"; u.gain.step = "5";
    u.gainVal = el("span", "tbc-note");
    levelbox.append(meter, u.gain, u.gainVal);
    row("Mic level", levelbox);

    u.volumes = el("div", "tbc-volumes");
    row("Volumes", u.volumes).classList.add("tbc-volrow-wrap");

    row("Camera", u.camSelect);
    row("Video scaling", u.fitSel);

    const mirrorNote = el("span", "tbc-note");
    u.mirror = el("input");
    u.mirror.type = "checkbox";
    u.mirror.style.width = "auto";
    mirrorNote.append(u.mirror,
      document.createTextNode("Only changes your own preview — peers always see you un-mirrored."));
    row("Mirror my video", mirrorNote);

    u.grid = el("div", "tbc-grid");
    u.log = el("p", "tbc-status");
    u.log.style.margin = "0";

    root.append(bar, panel, u.grid, u.log);
    u.root = root;
    u.panel = panel;
    S.ui = u;
    S.container.append(root);

    wireUI();
  }

  function unmount() {
    if (S && S.ui && S.ui.root.parentNode) S.ui.root.remove();
  }

  function log(msg) {
    if (S && S.ui) S.ui.log.textContent = msg;
    console.log("[take-back]", msg);
  }

  // ---- starting and leaving ------------------------------------------------

  async function start() {
    try {
      // First try to REQUIRE the saved devices (deviceId: {exact}). Firefox
      // silently ignores `ideal` device hints and grabs its own default mic, so
      // an {ideal} constraint meant the saved microphone was never actually used
      // there — exact makes the chosen device stick on both Firefox and Chrome.
      S.cameraStream = await navigator.mediaDevices.getUserMedia(gumConstraints(true));
    } catch (err) {
      // A saved device can be gone (unplugged, or a permission/enumeration
      // quirk), in which case `exact` rejects. Retry once PREFERRING the saved
      // devices so the call still starts on whatever is available.
      try {
        S.cameraStream = await navigator.mediaDevices.getUserMedia(gumConstraints(false));
      } catch (err2) {
        log("Could not access camera/microphone: " + err2.message);
        const failed = S;
        unmount();
        S = null;
        failed.onLeave({ error: err2 });
        return false;
      }
    }
    addTile("local", S.nick + " (you)", S.cameraStream, true);
    applyMirror();
    connectSignaling();
    layoutGrid();
    return true;
  }

  function leave() {
    if (!S) return;
    const sess = S;
    sess.leaving = true;
    clearTimeout(sess.reconnectTimer);
    stopMeter();
    for (const id of [...sess.vadTimers.keys()]) detachVAD(id);
    for (const entry of sess.peers.values()) {
      clearTimeout(entry.dropTimer);
      try { entry.pc.close(); } catch (e) { /* already closed */ }
    }
    sess.peers.clear();
    if (sess.ws) { try { sess.ws.close(); } catch (e) { /* already closed */ } }
    // Release the camera light and the microphone — a call that "ended" while
    // still holding the devices is the single most alarming bug in a video app.
    for (const s of [sess.cameraStream, sess.screenStream]) {
      if (s) s.getTracks().forEach((t) => t.stop());
    }
    unmount();
    S = null;
    sess.onLeave({});
  }

  function active() { return !!S; }

  // ---- UI wiring -----------------------------------------------------------

  function wireUI() {
    const u = S.ui;

    u.copy.onclick = () => {
      navigator.clipboard.writeText(S.room).then(() => log("Call code copied."));
    };
    u.leave.onclick = () => leave();

    u.settings.onclick = async () => {
      u.panel.classList.toggle("tbc-hidden");
      // Opening/closing the panel changes how much room is left for the grid,
      // so re-fit it either way.
      layoutGrid();
      if (u.panel.classList.contains("tbc-hidden")) { stopMeter(); return; }
      u.stereoSel.value = stereoWanted() ? "stereo" : "mono";
      u.fitSel.value = fillWanted() ? "fill" : "fit";
      await loadDevices();
      renderVolumes();
      startMeter();
    };

    // Mono/stereo transmit toggle. Two things have to change for it to take
    // effect on a live call: the CAPTURE channel count (getUserMedia) and the
    // Opus SDP (stereo / sprop-stereo fmtp params, added by tuneAudio).
    u.stereoSel.onchange = async () => {
      const stereo = u.stereoSel.value === "stereo";
      localStorage.setItem(PREF_STEREO, stereo ? "1" : "0");
      if (S.cameraStream) await recaptureMic(); // new channelCount
      // Renegotiate so the Opus fmtp change reaches peers. Only from a stable
      // state, so we don't collide with an in-flight offer (perfect negotiation
      // covers the rest if we do race).
      for (const [peerId, entry] of S.peers) {
        if (entry.pc.signalingState === "stable") await makeOffer(peerId, entry);
      }
      log("Transmitting " + (stereo ? "stereo" : "mono") + " audio.");
    };

    // Fit vs fill. Purely local and purely CSS — nothing renegotiates.
    u.fitSel.onchange = () => {
      const fill = u.fitSel.value === "fill";
      localStorage.setItem(PREF_FIT, fill ? "fill" : "fit");
      u.root.classList.toggle("fill", fill);
    };

    u.gain.value = String(Math.round(S.micGainValue * 100));
    u.gainVal.textContent = Math.round(S.micGainValue * 100) + "%";
    u.gain.oninput = () => {
      S.micGainValue = Number(u.gain.value) / 100;
      u.gainVal.textContent = Math.round(S.micGainValue * 100) + "%";
      localStorage.setItem(PREF_GAIN, String(S.micGainValue));
      applyMicGain();
    };

    u.mirror.onchange = () => {
      S.mirrored = u.mirror.checked;
      localStorage.setItem(PREF_MIRROR, S.mirrored ? "1" : "0");
      applyMirror();
    };

    u.micSelect.onchange = () => switchDevice("audio", u.micSelect.value);
    u.camSelect.onchange = () => switchDevice("video", u.camSelect.value);

    // Browsers can block autoplay-with-sound and suspend the AudioContext (which
    // would also silently kill the speaking rings). One tap fixes both.
    u.audioGate.onclick = async () => {
      if (audioCtx && audioCtx.state === "suspended") await audioCtx.resume();
      u.grid.querySelectorAll("video").forEach((v) => v.play().catch(() => {}));
      u.audioGate.classList.add("tbc-hidden");
      log("Audio enabled.");
    };

    u.mic.onclick = () => {
      S.micOn = !S.micOn;
      S.cameraStream.getAudioTracks().forEach((t) => (t.enabled = S.micOn));
      // When the gain chain is engaged, that processed track is what peers get.
      if (S.gainChainActive && S.micDest) {
        S.micDest.stream.getAudioTracks().forEach((t) => (t.enabled = S.micOn));
      }
      u.mic.textContent = S.micOn ? "🎤 Mic on" : "🔇 Mic off";
      setTileMuted("local", !S.micOn);
      broadcastState();
    };

    u.cam.onclick = () => {
      S.camOn = !S.camOn;
      // Camera and screen are separate tracks, so this only affects the camera.
      S.cameraStream.getVideoTracks().forEach((t) => (t.enabled = S.camOn));
      u.cam.textContent = S.camOn ? "📷 Camera on" : "📷 Camera off";
      setTileVideo("local", S.camOn);
      broadcastState();
    };

    u.present.onclick = () => (S.screenStream ? stopScreenShare() : startScreenShare());
    u.zoomOut.onclick = () => setSpotlight(null);
  }

  // gumConstraints builds the getUserMedia request from the saved device choices
  // and the mono/stereo preference. With `exact` the saved devices are REQUIRED
  // (used for the first attempt); without it they're merely preferred, so a call
  // can still start when a saved device has gone away.
  function gumConstraints(exact) {
    const micId = localStorage.getItem(PREF_MIC), camId = localStorage.getItem(PREF_CAM);
    const pick = (id) => (exact ? { exact: id } : { ideal: id });
    // channelCount is a soft hint (ideal): plenty of mics are mono-only, and we
    // never want a stereo request to fail the whole capture.
    const audio = { channelCount: { ideal: stereoWanted() ? 2 : 1 } };
    if (micId) audio.deviceId = pick(micId);
    const video = camId ? { deviceId: pick(camId) } : true;
    return { audio, video };
  }

  // applyMirror only touches your own camera tile — never the screen tile (a
  // mirrored screen share would be unreadable) and never what peers receive.
  function applyMirror() {
    const tile = tileEl("local");
    if (tile) tile.classList.toggle("mirror", S.mirrored);
    S.ui.mirror.checked = S.mirrored;
  }

  // ---- devices -------------------------------------------------------------

  async function loadDevices() {
    let devices = [];
    try { devices = await navigator.mediaDevices.enumerateDevices(); } catch (e) { return; }
    fillSelect(S.ui.micSelect, devices.filter((d) => d.kind === "audioinput"),
      currentTrackDeviceId("audio"), "Microphone");
    fillSelect(S.ui.camSelect, devices.filter((d) => d.kind === "videoinput"),
      currentTrackDeviceId("video"), "Camera");
  }

  function fillSelect(sel, devices, activeId, fallbackLabel) {
    sel.innerHTML = "";
    devices.forEach((d, i) => {
      const o = document.createElement("option");
      o.value = d.deviceId;
      o.textContent = d.label || `${fallbackLabel} ${i + 1}`;
      if (d.deviceId === activeId) o.selected = true;
      sel.append(o);
    });
    if (!devices.length) sel.append(new Option("none found", ""));
  }

  function currentTrackDeviceId(kind) {
    const t = kind === "audio"
      ? S.cameraStream.getAudioTracks()[0]
      : S.cameraStream.getVideoTracks()[0];
    return t ? (t.getSettings().deviceId || "") : "";
  }

  // switchDevice swaps a capture device mid-call. replaceTrack reuses the
  // existing senders, so no renegotiation and peers see no interruption.
  async function switchDevice(kind, deviceId) {
    if (!deviceId) return;
    let fresh;
    try {
      fresh = await navigator.mediaDevices.getUserMedia(
        kind === "audio"
          // Keep the mono/stereo preference across a mic change too.
          ? { audio: { deviceId: { exact: deviceId }, channelCount: { ideal: stereoWanted() ? 2 : 1 } } }
          : { video: { deviceId: { exact: deviceId } } });
    } catch (err) {
      log("Couldn't switch " + kind + ": " + err.message);
      return;
    }
    // Remember the choice so the NEXT call opens with this device.
    localStorage.setItem(kind === "audio" ? PREF_MIC : PREF_CAM, deviceId);

    if (kind === "audio") {
      await applyAudioTrack(fresh.getAudioTracks()[0]);
      log("Microphone switched.");
    } else {
      await applyVideoTrack(fresh.getVideoTracks()[0]);
      log("Camera switched.");
    }
  }

  // recaptureMic re-opens the microphone with the current channelCount
  // preference (same device) and swaps the fresh track into the call.
  async function recaptureMic() {
    const micId = localStorage.getItem(PREF_MIC);
    const audio = { channelCount: { ideal: stereoWanted() ? 2 : 1 } };
    if (micId) audio.deviceId = { exact: micId };
    let fresh;
    try { fresh = await navigator.mediaDevices.getUserMedia({ audio }); }
    catch (e) { log("Couldn't re-capture microphone: " + e.message); return; }
    await applyAudioTrack(fresh.getAudioTracks()[0]);
  }

  // applyAudioTrack installs a freshly captured microphone track as our outgoing
  // audio: hand it to every peer, swap it into cameraStream, and re-point the
  // meter/gain/VAD (all bound to the OLD track, which would otherwise go silent).
  async function applyAudioTrack(newTrack) {
    if (!newTrack) return;
    for (const entry of S.peers.values()) {
      const sender = micSenderFor(entry);
      if (sender) await sender.replaceTrack(newTrack);
    }
    const old = S.cameraStream.getAudioTracks()[0];
    if (old) { S.cameraStream.removeTrack(old); old.stop(); }
    S.cameraStream.addTrack(newTrack);
    newTrack.enabled = S.micOn;

    // The analyser is bound to the OLD track, so the speaking ring would go dead
    // after a mic change unless we rebuild it against the new stream.
    detachVAD("local");
    if (S.gainChainActive) {
      rebuildMicSource(); // point the gain chain at the new mic
      const processed = S.micDest.stream.getAudioTracks()[0];
      processed.enabled = S.micOn;
      for (const entry of S.peers.values()) {
        const snd = micSenderFor(entry);
        if (snd) await snd.replaceTrack(processed);
      }
      attachVAD("local", S.micDest.stream);
    } else {
      attachVAD("local", S.cameraStream);
    }
  }

  // micSenderFor finds the sender carrying our microphone — i.e. the audio
  // sender that isn't the screen-share's system-audio track.
  function micSenderFor(entry) {
    return entry.pc.getSenders().find(
      (s) => s.track && s.track.kind === "audio" && !entry.screenSenders.includes(s));
  }

  // applyVideoTrack is the camera equivalent: swap the new track into the
  // senders, the camera stream and the local preview.
  async function applyVideoTrack(newTrack) {
    if (!newTrack) return;
    for (const entry of S.peers.values()) {
      const sender = entry.pc.getSenders().find(
        (s) => s.track && s.track.kind === "video" && !entry.screenSenders.includes(s));
      if (sender) await sender.replaceTrack(newTrack);
    }
    const old = S.cameraStream.getVideoTracks()[0];
    if (old) { S.cameraStream.removeTrack(old); old.stop(); }
    S.cameraStream.addTrack(newTrack);
    newTrack.enabled = S.camOn;
    const v = tileEl("local") && tileEl("local").querySelector("video");
    if (v) { v.srcObject = S.cameraStream; v.play().catch(() => {}); }
  }

  // ---- mic gain + meter ----------------------------------------------------

  /**
   * Route the mic through a GainNode and send THAT to peers.
   *
   * Deliberately lazy: a suspended AudioContext makes a MediaStreamDestination
   * emit silence, and the context starts suspended when a call auto-joins from a
   * link (no user gesture on that page) — routing by default could leave you
   * inaudible. So we keep sending the raw track until you actually touch the
   * slider, which is itself the gesture that lets the context run.
   */
  function applyMicGain() {
    if (S.micGainValue === 1 && !S.gainChainActive) return; // nothing to do
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();

    if (!S.micGainNode) {
      S.micGainNode = audioCtx.createGain();
      S.micDest = audioCtx.createMediaStreamDestination();
      S.micGainNode.connect(S.micDest);
    }
    S.micGainNode.gain.value = S.micGainValue;

    if (!S.gainChainActive) {
      rebuildMicSource();
      const processed = S.micDest.stream.getAudioTracks()[0];
      processed.enabled = S.micOn;
      for (const entry of S.peers.values()) {
        const sender = micSenderFor(entry);
        if (sender) sender.replaceTrack(processed);
      }
      S.gainChainActive = true;
      // Meter/ring should follow what we now send.
      detachVAD("local");
      attachVAD("local", S.micDest.stream);
      log("Mic gain applied.");
    }
  }

  // (Re)connect the chain's input to the current microphone.
  function rebuildMicSource() {
    if (!S.micGainNode) return;
    try { if (S.micSourceNode) S.micSourceNode.disconnect(); } catch (e) { /* not connected */ }
    S.micSourceNode = audioCtx.createMediaStreamSource(S.cameraStream);
    S.micSourceNode.connect(S.micGainNode);
  }

  // The meter only needs to run while the panel is visible.
  function startMeter() {
    stopMeter();
    S.meterTimer = setInterval(() => {
      if (!S) return;
      // Scale so normal speech fills a useful part of the bar.
      const pct = Math.min(100, Math.round((S.localRms / 0.25) * 100));
      S.ui.meterFill.style.width = pct + "%";
      S.ui.meterFill.style.background = S.micOn ? "var(--tbc-online)" : "var(--tbc-dim)";
    }, 80);
  }
  function stopMeter() {
    if (S && S.meterTimer) clearInterval(S.meterTimer);
    if (S) S.meterTimer = null;
  }

  // ---- per-participant volume ---------------------------------------------
  // Each remote camera tile's <video> carries that peer's audio, so its .volume
  // gives us a clean per-person control.

  function renderVolumes() {
    const box = S.ui.volumes;
    box.innerHTML = "";
    const remotes = [...S.peers.entries()];
    if (!remotes.length) {
      box.append(el("span", "tbc-note", "No one else here yet."));
      return;
    }
    for (const [peerId, entry] of remotes) {
      const row = el("div", "tbc-volrow");
      const who = el("span", "who", entry.nick);
      const slider = el("input");
      // 0–100%: the element's own volume. Boosting past 100% would mean routing
      // their audio through Web Audio, which goes silent if the AudioContext is
      // suspended — not worth risking someone's audio to make them louder.
      slider.type = "range"; slider.min = "0"; slider.max = "100"; slider.step = "5";
      slider.value = String(Math.round((S.peerVolumes.get(peerId) ?? 1) * 100));
      const val = el("span", "val", slider.value + "%");
      slider.oninput = () => {
        const v = Number(slider.value) / 100;
        S.peerVolumes.set(peerId, v);
        val.textContent = slider.value + "%";
        setPeerVolume(peerId, v);
      };
      row.append(who, slider, val);
      box.append(row);
    }
  }

  function setPeerVolume(peerId, v) {
    const tile = tileEl(peerId);
    const video = tile && tile.querySelector("video");
    if (video) video.volume = Math.max(0, Math.min(1, v));
  }

  // ---- mic / camera state --------------------------------------------------

  function broadcastState() {
    if (!S.ws || S.ws.readyState !== WebSocket.OPEN) return;
    send({ type: "state", payload: JSON.stringify({
      video: S.camOn,
      audio: S.micOn,
      // A peer can't tell a screen track from a camera track, so name the stream.
      screenId: S.screenStream ? S.screenStream.id : "",
    }) });
  }

  function applyState(id, s) {
    setTileVideo(id, !!s.video);
    setTileMuted(id, !s.audio);
    if (!s.screenId) { // they stopped sharing
      const t = tileEl(id + "-screen");
      if (t) { t.remove(); layoutGrid(); }
    }
  }

  // ---- screen sharing ------------------------------------------------------
  // The screen goes out as ADDITIONAL tracks, so your camera keeps streaming
  // alongside it — people expect to see your face while you present. Adding a
  // track triggers renegotiation, handled by the perfect-negotiation logic in
  // createPeer.

  async function startScreenShare() {
    let capture;
    try {
      // audio:true asks for the shared window/tab/system audio, so presenting a
      // video actually carries its sound. Chromium honours it (systemAudio hints
      // that the whole-screen case should offer it too); Firefox and Safari just
      // return video, which we handle by simply not finding an audio track.
      capture = await navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: {
          // Screen audio is a clean digital feed: the voice-call processing
          // would only chew it up.
          echoCancellation: false, noiseSuppression: false, autoGainControl: false,
        },
        systemAudio: "include",
      });
    } catch (err) {
      // Older browsers can reject the whole request over the audio constraint
      // rather than ignoring it; retry video-only before giving up.
      try {
        capture = await navigator.mediaDevices.getDisplayMedia({ video: true });
      } catch (err2) {
        log("Screen share cancelled: " + (err2 && err2.message ? err2.message : err2));
        return;
      }
    }
    S.screenStream = capture;
    const screenTrack = capture.getVideoTracks()[0];
    const screenAudio = capture.getAudioTracks()[0] || null;

    // Tell peers which stream id is the screen BEFORE the tracks arrive, so they
    // can tell it apart from a camera when ontrack fires.
    broadcastState();

    for (const entry of S.peers.values()) {
      entry.screenSenders = addScreenTracks(entry.pc, capture);
    }

    // Our own screen preview sits next to (not instead of) our camera tile. It
    // is muted: playing our own captured system audio back would loop it.
    addTile("local-screen", "Your screen", capture, true);

    // The browser's own "Stop sharing" bar ends the track directly.
    screenTrack.onended = stopScreenShare;
    S.ui.present.textContent = "🖥 Stop presenting";
    log(screenAudio
      ? "Sharing your screen with its audio — your camera is still on."
      : "Sharing your screen — your camera is still on. (This browser didn't offer screen audio; " +
        "in Chrome, tick “Share tab audio” / “Share system audio” in the picker.)");
  }

  // addScreenTracks puts every track of the capture (video, and the system audio
  // when the browser gave us one) onto a peer connection, tagged with the screen
  // stream so the far side groups them onto the same tile — which is what makes
  // the shared audio come out of the screen tile rather than over the caller.
  function addScreenTracks(pc, capture) {
    return capture.getTracks().map((t) => pc.addTrack(t, capture));
  }

  function stopScreenShare() {
    if (!S || !S.screenStream) return;
    S.screenStream.getTracks().forEach((t) => t.stop());
    S.screenStream = null;

    // Drop the extra senders from each peer; this renegotiates too.
    for (const entry of S.peers.values()) {
      for (const sender of entry.screenSenders) {
        try { entry.pc.removeTrack(sender); } catch (e) { /* already gone */ }
      }
      entry.screenSenders = [];
    }
    const tile = tileEl("local-screen");
    if (tile) tile.remove();
    clearSpotlightIfGone();
    layoutGrid();

    S.ui.present.textContent = "🖥 Present screen";
    broadcastState();
    log("Stopped sharing.");
  }

  // ---- signaling -----------------------------------------------------------

  function connectSignaling() {
    const url = SIGNAL_URL + "?room=" + encodeURIComponent(S.room) +
      "&nick=" + encodeURIComponent(S.nick);
    const ws = new WebSocket(url);
    S.ws = ws;

    ws.onopen = () => {
      if (S && S.ws === ws) { S.reconnectDelay = 1000; setSignalOK(true); log("Connected to signaling."); }
    };
    ws.onclose = () => {
      // The call itself keeps working (media is peer-to-peer), so don't claim
      // the call dropped — just flag signaling and get the socket back,
      // otherwise the server drops us from the room and nobody can re-negotiate.
      if (!S || S.ws !== ws) return;
      setSignalOK(false);
      if (!S.leaving) scheduleReconnect();
    };
    ws.onerror = () => { if (S && S.ws === ws) setSignalOK(false); };
    ws.onmessage = (ev) => { if (S && S.ws === ws) handleSignal(JSON.parse(ev.data)); };
  }

  function scheduleReconnect() {
    clearTimeout(S.reconnectTimer);
    log("Signaling lost — reconnecting…");
    const delay = S.reconnectDelay;
    S.reconnectTimer = setTimeout(() => {
      if (!S || S.leaving) return;
      // A reconnect gets us a fresh peer id, so drop the old peer connections
      // and renegotiate from scratch rather than ending up with duplicates.
      resetPeers();
      connectSignaling();
    }, delay);
    S.reconnectDelay = Math.min(delay * 2, 15000);
  }

  function resetPeers() {
    for (const [id, entry] of S.peers) {
      clearTimeout(entry.dropTimer);
      entry.pc.close();
      const tile = tileEl(id);
      if (tile) tile.remove();
      const screen = tileEl(id + "-screen");
      if (screen) screen.remove();
    }
    S.peers.clear();
    S.peerState.clear();
    updateCallPill();
    layoutGrid();
  }

  function send(obj) { S.ws.send(JSON.stringify(obj)); }

  async function handleSignal(msg) {
    switch (msg.type) {
      case "welcome":
        S.myId = msg.to;
        broadcastState(); // let the room know our initial mic/camera state
        // We are the newcomer: initiate an offer to each existing peer.
        for (const p of msg.peers || []) await createPeer(p.id, p.nick, true);
        updateCallPill();
        break;
      case "hello":
        // An existing peer sees a newcomer; wait for their offer. Re-announce
        // our mic/camera state so they render us correctly from the start.
        broadcastState();
        break;
      case "state": {
        const s = JSON.parse(msg.payload);
        S.peerState.set(msg.from, s);
        applyState(msg.from, s);
        break;
      }
      case "offer": {
        await createPeer(msg.from, msg.nick, false);
        const entry = S.peers.get(msg.from);
        const pc = entry.pc;

        // Perfect negotiation: only the polite peer gives way on a collision.
        // Without this, two people sharing a screen at once would wedge the call.
        const collision = entry.makingOffer || pc.signalingState !== "stable";
        entry.ignoreOffer = !entry.polite && collision;
        if (entry.ignoreOffer) break;

        // setRemoteDescription implicitly rolls back our own offer when polite.
        await pc.setRemoteDescription(JSON.parse(msg.payload));
        // Explicit createAnswer (rather than implicit setLocalDescription) so we
        // can apply the Opus mono/stereo tuning before we answer.
        const answer = await pc.createAnswer();
        answer.sdp = tuneAudio(answer.sdp);
        await pc.setLocalDescription(answer);
        send({ type: "answer", to: msg.from, nick: S.nick, payload: JSON.stringify(pc.localDescription) });
        break;
      }
      case "answer": {
        const entry = S.peers.get(msg.from);
        if (!entry) break;
        try { await entry.pc.setRemoteDescription(JSON.parse(msg.payload)); }
        catch (e) { console.warn("setRemoteDescription(answer)", e); }
        break;
      }
      case "candidate": {
        const entry = S.peers.get(msg.from);
        if (entry && msg.payload) {
          try { await entry.pc.addIceCandidate(JSON.parse(msg.payload)); }
          // Candidates for an offer we deliberately ignored are expected noise.
          catch (e) { if (!entry.ignoreOffer) console.warn("addIceCandidate", e); }
        }
        break;
      }
      case "leave":
        removePeer(msg.from);
        break;
    }
  }

  // makeOffer creates and sends an SDP offer for one peer, applying our audio
  // tuning (the mono/stereo Opus params). Used by onnegotiationneeded and by the
  // stereo toggle when it renegotiates an already-connected peer.
  async function makeOffer(peerId, entry) {
    const pc = entry.pc;
    try {
      entry.makingOffer = true;
      const offer = await pc.createOffer();
      offer.sdp = tuneAudio(offer.sdp);
      await pc.setLocalDescription(offer);
      send({ type: "offer", to: peerId, nick: S.nick, payload: JSON.stringify(pc.localDescription) });
    } catch (err) {
      console.warn("negotiationneeded", err);
    } finally {
      entry.makingOffer = false;
    }
  }

  // tuneAudio rewrites the Opus fmtp line so peers know whether to expect
  // stereo. Mono is the default and leaves the SDP untouched (the lowest-risk
  // path, byte for byte what we sent before this feature existed); stereo
  // appends stereo=1;sprop-stereo=1 to the Opus payload's fmtp so both
  // directions carry two channels. SDP lines are CRLF-separated, so preserve
  // that on the way out.
  function tuneAudio(sdp) {
    if (!stereoWanted()) return sdp;
    const m = sdp.match(/a=rtpmap:(\d+) opus\/48000/i);
    if (!m) return sdp;
    const pt = m[1];
    const params = "stereo=1;sprop-stereo=1";
    const lines = sdp.split("\r\n");
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("a=fmtp:" + pt + " ")) {
        if (!/stereo=1/.test(lines[i])) lines[i] += ";" + params;
        return lines.join("\r\n");
      }
    }
    // No fmtp line for Opus yet: add one right after its rtpmap.
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("a=rtpmap:" + pt + " ")) {
        lines.splice(i + 1, 0, "a=fmtp:" + pt + " " + params);
        break;
      }
    }
    return lines.join("\r\n");
  }

  async function createPeer(peerId, peerNick, initiator) {
    let entry = S.peers.get(peerId);
    if (entry) return entry.pc;

    const pc = new RTCPeerConnection(ICE_CONFIG);
    entry = {
      pc,
      nick: peerNick || "peer",
      // "Perfect negotiation": either side may add/remove tracks at any time
      // (that's what lets a screen share join as EXTRA tracks instead of
      // replacing the camera). If both offer at once, the impolite peer ignores
      // the incoming offer and the polite one gives way. The tiebreak must be
      // deterministic and opposite on each side, so compare peer ids.
      polite: String(S.myId) < String(peerId),
      makingOffer: false,
      ignoreOffer: false,
      screenSenders: [],
      dropTimer: null, // set while the connection is "disconnected" (see below)
    };
    S.peers.set(peerId, entry);

    S.cameraStream.getTracks().forEach((t) => pc.addTrack(t, S.cameraStream));
    // Already sharing when this peer arrives? Send them the screen too.
    if (S.screenStream) entry.screenSenders = addScreenTracks(pc, S.screenStream);

    pc.onnegotiationneeded = () => makeOffer(peerId, entry);
    pc.onicecandidate = (e) => {
      if (e.candidate) send({ type: "candidate", to: peerId, payload: JSON.stringify(e.candidate) });
    };
    pc.ontrack = (e) => {
      routeRemoteTrack(peerId, entry, e);
      updateCallPill();
    };
    pc.onconnectionstatechange = () => {
      const st = pc.connectionState;
      log("peer " + peerId + ": " + st);
      updateCallPill();
      if (st === "failed" || st === "closed") {
        removePeer(peerId); // dead for good — drop immediately
        return;
      }
      if (st === "disconnected") {
        // A blip (network hiccup, peer's tab backgrounded) can recover, so don't
        // yank them the instant it happens — that's the "frozen" state a viewer
        // sees. Grey the tile as "reconnecting" and give it a few seconds; if it
        // hasn't recovered by then, pop them out of the call.
        markReconnecting(peerId, true);
        clearTimeout(entry.dropTimer);
        entry.dropTimer = setTimeout(() => {
          if (pc.connectionState !== "connected" && pc.connectionState !== "completed") {
            removePeer(peerId);
          }
        }, DROP_GRACE_MS);
      } else if (st === "connected" || st === "completed") {
        clearTimeout(entry.dropTimer);
        entry.dropTimer = null;
        markReconnecting(peerId, false); // recovered
      }
    };

    // addTrack above fires negotiationneeded, which sends the offer for us.
    void initiator;
    return pc;
  }

  // routeRemoteTrack decides whether an incoming stream is a peer's camera or
  // their screen. The track alone doesn't say, so peers announce their screen's
  // stream id in their `state` message. Both the screen's video AND its system
  // audio arrive tagged with that stream, so they land on the same tile and the
  // shared audio plays from it.
  function routeRemoteTrack(peerId, entry, e) {
    const stream = e.streams[0];
    if (!stream) return;
    const st = S.peerState.get(peerId);
    if (st && st.screenId && stream.id === st.screenId) {
      addTile(peerId + "-screen", entry.nick + "'s screen", stream, false);
    } else {
      addTile(peerId, entry.nick, stream, false);
    }
  }

  // markReconnecting greys a peer's tile(s) and shows a "Reconnecting…" overlay
  // while their connection is wobbling. Covers both camera and screen tiles.
  function markReconnecting(peerId, on) {
    for (const suffix of ["", "-screen"]) {
      const tile = tileEl(peerId + suffix);
      if (tile) tile.classList.toggle("reconnecting", on);
    }
  }

  function removePeer(peerId) {
    if (!S) return;
    S.peerVolumes.delete(peerId);
    if (!S.ui.panel.classList.contains("tbc-hidden")) renderVolumes();
    const entry = S.peers.get(peerId);
    if (entry) { clearTimeout(entry.dropTimer); entry.pc.close(); S.peers.delete(peerId); }
    for (const suffix of ["", "-screen"]) {
      const tile = tileEl(peerId + suffix);
      if (tile) { detachVAD(peerId + suffix); tile.remove(); }
    }
    clearSpotlightIfGone();
    updateCallPill();
    layoutGrid(); // a tile left: re-fit the space
  }

  // ---- status --------------------------------------------------------------
  // The pill reflects the CALL (peer connections), not the signaling socket:
  // they are independent, and a dropped signaling socket used to make a
  // perfectly good call read as "disconnected".

  function updateCallPill() {
    const states = [...S.peers.values()].map((p) => p.pc.connectionState);
    const pill = S.ui.pill;
    if (states.some((s) => s === "connected")) pill.textContent = "connected";
    else if (S.peers.size > 0) pill.textContent = "connecting…";
    else pill.textContent = "waiting for others";
    S.onStatus({ text: pill.textContent, peers: S.peers.size });
  }

  // Signaling trouble is shown separately and quietly — it doesn't mean the call
  // is down, but it does mean new peers can't join until it recovers.
  function setSignalOK(ok) { S.ui.signalWarn.classList.toggle("tbc-hidden", ok); }

  // ---- speaking detection (voice activity) --------------------------------
  // Each participant's audio is tapped with an AnalyserNode; when the short-term
  // RMS crosses a threshold we ring their tile green. This doubles as a "is my
  // mic actually picking me up?" check for yourself.

  function attachVAD(tileId, stream) {
    if (S.vadTimers.has(tileId) || !stream.getAudioTracks().length) return;
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();

    const analyser = audioCtx.createAnalyser();
    analyser.fftSize = 512;
    analyser.smoothingTimeConstant = 0.4;
    audioCtx.createMediaStreamSource(stream).connect(analyser);

    const buf = new Uint8Array(analyser.fftSize);
    let speaking = false, quietSince = 0;

    function tick() {
      const tile = tileEl(tileId);
      if (!tile) return;

      analyser.getByteTimeDomainData(buf);
      let sum = 0;
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i] - 128) / 128;
        sum += v * v;
      }
      const rms = Math.sqrt(sum / buf.length);
      if (tileId === "local") S.localRms = rms; // feeds the settings meter
      const now = performance.now();

      if (!speaking && rms > SPEAK_ON) { speaking = true; quietSince = 0; }
      else if (speaking && rms < SPEAK_OFF) {
        if (!quietSince) quietSince = now;
        if (now - quietSince > HANG_MS) speaking = false;
      } else if (speaking) quietSince = 0;

      tile.classList.toggle("speaking", speaking);
    }

    // Deliberately setInterval, not requestAnimationFrame: rAF is paused
    // entirely in background tabs, which froze the ring mid-state until you
    // looked back.
    const timer = setInterval(() => {
      if (!S || !tileEl(tileId)) { // session or tile gone: stop polling
        clearInterval(timer);
        if (S) S.vadTimers.delete(tileId);
        return;
      }
      tick();
    }, 100);
    S.vadTimers.set(tileId, timer);
  }

  // detachVAD stops a tile's level poll. Needed when the audio source changes:
  // the analyser is bound to the old track and would otherwise go silent forever.
  function detachVAD(tileId) {
    const t = S.vadTimers.get(tileId);
    if (t) clearInterval(t);
    S.vadTimers.delete(tileId);
    const tile = tileEl(tileId);
    if (tile) tile.classList.remove("speaking");
  }

  // ---- tiles ---------------------------------------------------------------

  // Tiles are addressed by a data attribute scoped to this session's grid, not
  // by a document id — the panel shares a page with the chat client's own ids.
  function tileEl(id) {
    return S && S.ui ? S.ui.grid.querySelector('[data-tile="' + id + '"]') : null;
  }

  function initialsOf(name) { return (name || "?").slice(0, 2).toUpperCase(); }
  function colorFor(name) {
    let h = 0;
    for (let i = 0; i < (name || "").length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return `hsl(${h % 360} 55% 42%)`;
  }

  // setTileVideo toggles between the video feed and the profile picture.
  function setTileVideo(id, on) {
    const tile = tileEl(id);
    if (tile) tile.classList.toggle("novideo", !on);
  }
  function setTileMuted(id, isMuted) {
    const tile = tileEl(id);
    if (tile) tile.classList.toggle("muted", isMuted);
  }

  function addTile(id, name, stream, muted) {
    let tile = tileEl(id);
    if (!tile) {
      tile = el("div", "tbc-tile");
      tile.dataset.tile = id;
      // Screen shares are always letterboxed, never cropped.
      if (id.endsWith("-screen")) tile.classList.add("screen");
      const video = el("video");
      video.autoplay = true; video.playsInline = true; video.muted = muted;
      const mic = el("div", "tbc-micoff", "🔇");
      const zoom = el("div", "tbc-zoom", "⤢");
      tile.append(video, el("div", "tbc-avatar"), mic, zoom, el("div", "tbc-name"));
      // Click to fill the call area with this feed; click again to come back.
      // Especially wanted for a shared screen, where one tile among several is
      // usually the only one you actually need to read.
      tile.addEventListener("click", () => toggleSpotlight(id));
      S.ui.grid.append(tile);
    }
    const v = tile.querySelector("video");
    if (v.srcObject !== stream) v.srcObject = stream;
    // Autoplay with sound can be blocked (e.g. when a call auto-joins from a
    // link, with no click on this page). Silent failure here = "their audio just
    // doesn't work", so surface it and offer a one-tap fix.
    v.play().catch(() => { if (id !== "local") S.ui.audioGate.classList.remove("tbc-hidden"); });
    tile.querySelector(".tbc-name").textContent = name;

    // Profile picture shown whenever the camera is off.
    const av = tile.querySelector(".tbc-avatar");
    av.textContent = initialsOf(baseNick(name));
    av.style.background = colorFor(baseNick(name));

    // Ring this tile when its owner speaks.
    attachVAD(id, stream);

    // A peer's state may have arrived before their video track did.
    if (S.peerState.has(id)) applyState(id, S.peerState.get(id));
    if (id === "local") applyMirror();

    // Re-apply any volume the user already set for this peer, and keep the
    // settings list in step as people arrive.
    if (S.peerVolumes.has(id)) setPeerVolume(id, S.peerVolumes.get(id));
    if (!S.ui.panel.classList.contains("tbc-hidden")) renderVolumes();

    layoutGrid(); // a tile appeared (or its stream changed): re-fit
  }

  // baseNick strips the decorations we add to labels ("river (you)").
  function baseNick(label) { return (label || "").split(" (")[0]; }

  // ---- spotlight ----------------------------------------------------------
  // One tile blown up to fill the call area. S.spotlight holds its id, or null.

  function toggleSpotlight(id) {
    if (!S) return;
    setSpotlight(S.spotlight === id ? null : id);
  }

  function setSpotlight(id) {
    S.spotlight = id;
    S.ui.grid.classList.toggle("spotlight", !!id);
    for (const tile of S.ui.grid.children) {
      tile.classList.toggle("spot", !!id && tile.dataset.tile === id);
    }
    S.ui.zoomOut.classList.toggle("tbc-hidden", !id);
    layoutGrid();
  }

  // If the spotlit peer leaves, fall back to the grid rather than showing an
  // empty spotlight over everyone else.
  function clearSpotlightIfGone() {
    if (S && S.spotlight && !tileEl(S.spotlight)) setSpotlight(null);
  }

  // ---- grid layout ---------------------------------------------------------
  // The grid is a flex child that already owns its space (see call.css), so all
  // this has to do is pick the column/row counts that make the tiles as large as
  // possible for the current participant count while holding a ~16:9 shape.

  function layoutGrid() {
    if (!S || !S.ui) return;
    const grid = S.ui.grid;
    const n = grid.children.length;
    if (!n) return;
    // A spotlit tile is positioned over the grid and sized by it, so the
    // column/row packing below has nothing to decide.
    if (S.spotlight) return;

    const gap = 10;    // keep in step with the .6rem grid gap in call.css
    const AR = 16 / 9; // target tile aspect ratio
    const W = grid.clientWidth, H = grid.clientHeight;
    if (W <= 0 || H <= 0) return;

    let best = 1, bestArea = 0;
    for (let cols = 1; cols <= n; cols++) {
      const rows = Math.ceil(n / cols);
      const cellW = (W - gap * (cols - 1)) / cols;
      const cellH = (H - gap * (rows - 1)) / rows;
      if (cellW <= 0 || cellH <= 0) continue;
      // A tile is bounded by whichever dimension is tighter for the target ratio.
      const w = Math.min(cellW, cellH * AR);
      const area = w * (w / AR);
      if (area > bestArea) { bestArea = area; best = cols; }
    }
    grid.style.gridTemplateColumns = `repeat(${best}, 1fr)`;
    grid.style.gridTemplateRows = `repeat(${Math.ceil(n / best)}, 1fr)`;
  }

  window.addEventListener("resize", layoutGrid);

  // Any click is a user gesture: opportunistically un-suspend the AudioContext,
  // and only then engage a saved mic gain (the chain emits silence while
  // suspended).
  document.addEventListener("click", () => {
    if (audioCtx && audioCtx.state === "suspended") audioCtx.resume();
    if (S && S.micGainValue !== 1 && !S.gainChainActive && S.cameraStream) applyMicGain();
  });
  // Coming back to the tab can leave the context suspended; the rings depend on it.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && audioCtx && audioCtx.state === "suspended") audioCtx.resume();
  });

  return { mount, leave, active, layout: layoutGrid, room: () => (S ? S.room : "") };
})();
