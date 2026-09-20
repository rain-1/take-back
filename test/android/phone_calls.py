#!/usr/bin/env python3
"""A call in a DM, seen on the phone: live with Join, then history."""
import json, subprocess, sys, time, urllib.request
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import *

fails = []
def check(label, ok, extra=""):
    print(("✓ " if ok else "✗ ") + label + (f" — {extra}" if extra and not ok else ""))
    if not ok: fails.append(label)

PKG = "com.takeback.app"
me = register("phonecall")
friend = register("webcall")
# friends, so they can DM each other
api("/api/friends/request", "POST", {"nick": friend["nick"]}, cookie=me["cookie"])
api("/api/friends/respond", "POST", {"userId": me["id"], "accept": True}, cookie=friend["cookie"])

shell(f"pm clear {PKG}")
for perm in ["RECORD_AUDIO", "POST_NOTIFICATIONS", "CAMERA"]:
    shell(f"pm grant {PKG} android.permission.{perm}")
shell(f"am start -n {PKG}/.LoginActivity")
time.sleep(3)
tap(find(dump(), rid="nick")); type_text(me["nick"])
tap(find(dump(), rid="password")); type_text("pw123456")
tap_text(rid="submit", label="log in")
time.sleep(3)
allow = find(dump(), text="Allow")
if allow: tap(allow); time.sleep(1.5)

# open the DM with the web friend
tap_text(text=friend["nick"], label="the friend")
time.sleep(2.5)
check("the chat opens", find(dump(), rid="input") is not None, str(texts())[:200])

# the friend starts a call from the web side and sits in it
code = "PH" + str(int(time.time()))[-4:]
api("/api/messages", "POST", {"with": me["id"], "body": f"📞 call:{code}"}, cookie=friend["cookie"])
time.sleep(2.5)
t = texts()
check("the call arrives as a line, not a bare code", any("started a call" in x for x in t), str(t)[:300])
check("and offers Join while it's live", any(x.lower() == "join" for x in t), str(t)[:300])
check("with Decline next to it", any(x.lower() == "decline" for x in t), str(t)[:300])

# nobody ever joins: it becomes history, and the buttons go
time.sleep(12)
t = texts()
check("it turns into history once it's over", any("missed a call" in x.lower() for x in t), str(t)[:300])
check("and the buttons are gone", not any(x.lower() == "join" for x in t), str(t)[:300])

print()
print(f"{len(fails)} FAILED" if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
