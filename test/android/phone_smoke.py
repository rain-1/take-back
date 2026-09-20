#!/usr/bin/env python3
"""Tap through the phone app on the emulator: sign in, servers, channels, voice."""
import json, os, subprocess, sys, time

# Scratch space for the bits a run needs on disk (a test picture, the browser
# peer's output). Defaults to a temp directory.
WORK = os.environ.get("TB_WORK", "/tmp/tb-android-test")
os.makedirs(WORK, exist_ok=True)
SHOT = os.path.join(WORK, "shot.png")
if not os.path.exists(SHOT):
    import base64
    open(SHOT, "wb").write(base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import *  # noqa

fails = []
def check(label, ok, extra=""):
    print(("✓ " if ok else "✗ ") + label + (f" — {extra}" if extra and not ok else ""))
    if not ok: fails.append(label)

PKG = "com.takeback.app"
me = register("phoneuser")
other = register("webuser")

shell(f"am force-stop {PKG}")   # a screen left over from a previous run
shell(f"pm clear {PKG}")
# Grant the microphone up front; leave the camera for the in-app request.
for perm in ["RECORD_AUDIO", "POST_NOTIFICATIONS"]:
    shell(f"pm grant {PKG} android.permission.{perm}")
shell(f"am start -n {PKG}/.LoginActivity")
time.sleep(3)

# ---- sign in ----
xml = dump()
nick = find(xml, rid="nick")
check("login screen opens", nick is not None, str(texts())[:200])
if nick:
    tap(nick); type_text(me["nick"])
    pw = find(dump(), rid="password")
    tap(pw); type_text("pw123456")
    tap_text(rid="submit", label="Log in button")
    time.sleep(3)
    # Android may still ask about notifications on first run.
    allow = find(dump(), text="Allow")
    if allow: tap(allow); time.sleep(1.5)

t = texts()
check("home screen shows the servers section", any("SERVERS" in x for x in t), str(t)[:200])
check("home screen still has groups and friends", any("GROUPS" in x for x in t) and any("FRIENDS" in x for x in t), str(t)[:200])

# ---- create a server ----
tap_text(rid="newServerBtn", label="create server button")
time.sleep(1)
xml = dump()
check("create-server dialog opens", find(xml, contains="Create a server") is not None, str(texts())[:250])
field = find(xml, cls="android.widget.EditText")
if field:
    tap(field); type_text("Emu Crew")
tap_text(text="Create", label="Create button")
time.sleep(3)

t = texts()
check("the server screen opens", "Emu Crew" in t, str(t)[:250])
check("it lists the starter channels", "general" in t and "General" in t, str(t)[:250])
check("it shows who is active", any(x.startswith("ACTIVE") for x in t), str(t)[:250])

servers, _ = api("/api/servers", cookie=me["cookie"])
check("the server exists on the server", len(servers) == 1 and servers[0]["name"] == "Emu Crew", str(servers)[:200])
sid = servers[0]["id"]
chans, _ = api(f"/api/servers/channels?server={sid}", cookie=me["cookie"])
text_ch = [c for c in chans if c["kind"] == "text"][0]
voice_ch = [c for c in chans if c["kind"] == "voice"][0]

# ---- a text channel ----
tap_text(text="general", label="#general")
time.sleep(2)
box = find(dump(), rid="input")
check("the channel chat opens", box is not None, str(texts())[:250])
if box:
    tap(box); type_text("hello from the phone")
    tap_text(rid="sendBtn", label="send")
    time.sleep(2)
msgs, _ = api(f"/api/channels/messages?channel={text_ch['id']}", cookie=me["cookie"])
check("the message reached the server", any(m["body"] == "hello from the phone" for m in msgs), str([m['body'] for m in msgs])[:200])
check("and is on screen", any("hello from the phone" in x for x in texts()))

# ---- a picture, sent from the web side, opens in the app ----
inv, _ = api("/api/servers/invites", "POST", {"server": sid}, cookie=me["cookie"])
api("/api/invites/join", "POST", {"code": inv["code"]}, cookie=other["cookie"])
subprocess.run(["curl", "-s", "-X", "POST", f"{WEB}/api/channels/messages/media",
                "-H", f"Cookie: {other['cookie']}", "-F", f"channel={text_ch['id']}",
                "-F", "body=", "-F", "name=shot.png",
                "-F", f"file=@{SHOT};type=image/png"], capture_output=True)
time.sleep(2.5)
img = find(dump(), cls="android.widget.ImageView")
check("the picture arrives live in the channel", img is not None, str(texts())[:250])
# The message row's picture is the last ImageView on screen; tap it.
imgs = [a for a in nodes(dump()) if a.get("class") == "android.widget.ImageView" and a.get("clickable") == "true"]
if imgs:
    tap(imgs[-1])
    time.sleep(2)
t = texts()
check("tapping it opens the viewer in the app", any("Open in browser" in x for x in t) and any("Close" in x for x in t), str(t)[:250])
tap_text(text="Close", label="close viewer")

# ---- voice channel, with a browser on the other end ----
for _ in range(3):   # the viewer may still be closing; keep going back
    if "Emu Crew" in texts(): break
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
check("back on the server screen", "Emu Crew" in texts(), str(texts())[:200])
tap_text(text="General", label="voice channel")
time.sleep(6)
t = texts()
check("the call screen opens for the voice channel",
      any("General" in x for x in t) and find(dump(), rid="leaveBtn") is not None, str(t)[:300])
act, _ = api(f"/api/servers/active?server={sid}", cookie=me["cookie"])
in_voice = act.get("voice", {}).get(str(voice_ch["id"]), [])
check("the server says the phone is in that voice channel", me["id"] in in_voice, str(act))
check("the phone counts as active in the server", me["id"] in act.get("active", []), str(act))
servers2, _ = api("/api/servers", cookie=other["cookie"])
check("the server list reports someone in voice", servers2[0].get("voiceCount") == 1, str(servers2)[:200])

# ---- video inside a voice channel, with a browser on the other end ----
env = dict(os.environ, PUPPETEER=os.environ.get("PUPPETEER", "puppeteer-core"), COOKIE=other["cookie"])
peer_log = open(os.path.join(WORK, "web-peer.log"), "w+")
peer = subprocess.Popen(["node", os.path.join(os.path.dirname(os.path.abspath(__file__)), "web-peer.cjs")],
                        stdout=peer_log, stderr=subprocess.STDOUT, env=env)

def peer_lines():
    with open(os.path.join(WORK, "web-peer.log")) as f:
        return f.read().splitlines()

joined = False
for _ in range(40):
    if any(l.startswith("JOINED") for l in peer_lines()):
        joined = True
        break
    time.sleep(1)
check("a browser joins the same voice channel", joined)
time.sleep(3)
act3, _ = api(f"/api/servers/active?server={sid}", cookie=me["cookie"])
both = sorted(act3.get("voice", {}).get(str(voice_ch["id"]), []))
check("both the phone and the browser are in it", both == sorted([me["id"], other["id"]]), str(act3))

cam = find(dump(), rid="camBtn")
check("the voice call has a camera button", cam is not None, str(texts())[:300])
if cam:
    tap(cam)
    time.sleep(2)
    t = texts()
    asked = any("llow" in x for x in t)
    check("it asks for camera permission the first time", asked, str(t)[:300])
    if asked:
        allow = find(dump(), contains="While using") or find(dump(), text="Allow")
        if allow: tap(allow)
        time.sleep(4)

got_video = False
for _ in range(25):
    for line in peer_lines():
        if not line.startswith("TILES "): continue
        for tile in json.loads(line[6:]):
            if tile["tile"] != "local" and tile["w"] > 0:
                got_video = True
    if got_video: break
    time.sleep(2)
check("the browser receives the phone's video", got_video, peer_lines()[-1] if peer_lines() else "")
check("Leave is reachable on the call screen", find(dump(), rid="leaveBtn") is not None, str(texts())[:300])

tap_text(rid="leaveBtn", label="leave call")
time.sleep(2)
act2, _ = api(f"/api/servers/active?server={sid}", cookie=me["cookie"])
check("leaving clears the voice seat", me["id"] not in act2.get("voice", {}).get(str(voice_ch["id"]), []), str(act2))

print()
print(f"{len(fails)} FAILED" if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
