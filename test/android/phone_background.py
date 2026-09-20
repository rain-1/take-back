#!/usr/bin/env python3
"""A call must survive the app going to the background (@Etheri, Nothing Phone 1)."""
import json, os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import *

fails = []
def check(label, ok, extra=""):
    print(("✓ " if ok else "✗ ") + label + (f" — {extra}" if extra and not ok else ""))
    if not ok: fails.append(label)

PKG = "com.takeback.app"
me = register("phoneBG")
peer = register("webBG")
sv, _ = api("/api/servers", "POST", {"name": "Background"}, cookie=me["cookie"])
inv, _ = api("/api/servers/invites", "POST", {"server": sv["id"]}, cookie=me["cookie"])
api("/api/invites/join", "POST", {"code": inv["code"]}, cookie=peer["cookie"])
chans, _ = api(f"/api/servers/channels?server={sv['id']}", cookie=me["cookie"])
voice = [c for c in chans if c["kind"] == "voice"][0]

shell(f"pm clear {PKG}")
for perm in ["RECORD_AUDIO", "POST_NOTIFICATIONS"]:
    shell(f"pm grant {PKG} android.permission.{perm}")
shell(f"am start -n {PKG}/.LoginActivity")
time.sleep(3)
tap(find(dump(), rid="nick")); type_text(me["nick"])
tap(find(dump(), rid="password")); type_text("pw123456")
tap_text(rid="submit", label="log in")
time.sleep(3)
a = find(dump(), text="Allow")
if a: tap(a); time.sleep(1.5)

tap_text(contains="Background", label="the server"); time.sleep(2)
tap_text(text="General", label="voice channel"); time.sleep(6)
check("the phone is in the voice channel", me["id"] in api(f"/api/servers/active?server={sv['id']}", cookie=me["cookie"])[0].get("voice", {}).get(str(voice["id"]), []))

# the browser joins and starts reporting how much audio it hears from the phone
log = open("/tmp/tb-bg-peer.log", "w+")
env = dict(os.environ, PUPPETEER=os.environ.get("PUPPETEER", "puppeteer-core"), COOKIE=peer["cookie"])
peer_proc = subprocess.Popen(["node", os.path.join(os.path.dirname(os.path.abspath(__file__)), "web-peer-audio.cjs")],
                             stdout=log, stderr=subprocess.STDOUT, env=env)
def audio_readings():
    with open("/tmp/tb-bg-peer.log") as f:
        return [int(l.split()[1]) for l in f.read().splitlines() if l.startswith("AUDIO ") and l.split()[1].lstrip("-").isdigit()]

for _ in range(40):
    if any(l.startswith("JOINED") for l in open("/tmp/tb-bg-peer.log").read().splitlines()): break
    time.sleep(1)
time.sleep(8)
before = audio_readings()
check("the browser is hearing the phone", len(before) >= 2 and before[-1] > before[0], str(before[-4:]))

# --- send the app to the background and leave it there ---
shell("input keyevent KEYCODE_HOME")
time.sleep(3)
check("an ongoing call notification is showing", "In General" in shell("dumpsys notification --noredact | grep -c 'In General' || true") or
      shell("dumpsys notification --noredact") .count("In General") > 0)
mark = len(audio_readings())
time.sleep(45)
after = audio_readings()
grew = len(after) > mark + 3 and after[-1] > after[mark]
check("audio keeps flowing while the app is in the background", grew, f"{after[mark] if len(after) > mark else '?'} -> {after[-1] if after else '?'}")
act, _ = api(f"/api/servers/active?server={sv['id']}", cookie=me["cookie"])
check("and the server still has the phone in the call", me["id"] in act.get("voice", {}).get(str(voice["id"]), []), str(act))

peer_proc.terminate()
print()
print(f"{len(fails)} FAILED" if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
