#!/usr/bin/env python3
"""The incoming-call banner, and coming back to the conversation you had open."""
import sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import *

fails = []
def check(label, ok, extra=""):
    print(("✓ " if ok else "✗ ") + label + (f" — {extra}" if extra and not ok else ""))
    if not ok: fails.append(label)

PKG = "com.takeback.app"
me = register("phoneX")
friend = register("webX")
api("/api/friends/request", "POST", {"nick": friend["nick"]}, cookie=me["cookie"])
api("/api/friends/respond", "POST", {"userId": me["id"], "accept": True}, cookie=friend["cookie"])

shell(f"am force-stop {PKG}")   # a screen left over from a previous run
shell(f"pm clear {PKG}")
for perm in ["RECORD_AUDIO", "POST_NOTIFICATIONS"]:
    shell(f"pm grant {PKG} android.permission.{perm}")
shell(f"am start -n {PKG}/.LoginActivity")
time.sleep(3)
tap(find(dump(), rid="nick")); type_text(me["nick"])
tap(find(dump(), rid="password")); type_text("pw123456")
tap_text(rid="submit", label="log in")
time.sleep(3)
allow = find(dump(), text="Allow")
if allow: tap(allow); time.sleep(1.5)

# --- the banner, while looking at the home screen ---
code = "BN" + str(int(time.time()))[-4:]
api("/api/messages", "POST", {"with": me["id"], "body": f"📞 call:{code}"}, cookie=friend["cookie"])
time.sleep(3)
t = texts()
check("a call shows a banner over whatever you're looking at", any("is calling" in x for x in t), str(t)[:250])
check("with Join and Decline", any(x.lower() == "join" for x in t) and any(x.lower() == "decline" for x in t), str(t)[:250])

# declining takes it away, and tells the caller
tap_text(contains="Decline", label="decline")
time.sleep(2.5)
check("declining clears the banner", not any("is calling" in x for x in texts()), str(texts())[:250])
state = api(f"/api/calls?codes={code}", cookie=friend["cookie"])[0][0]
check("and the caller is told", state["outcome"] == "declined", str(state))

# --- coming back to where you were ---
tap_text(text=friend["nick"], label="open the DM")
time.sleep(2.5)
check("the chat opens", find(dump(), rid="input") is not None, str(texts())[:200])
shell(f"am force-stop {PKG}")
time.sleep(1)
shell("monkey -p com.takeback.app -c android.intent.category.LAUNCHER 1")
time.sleep(5)
t = texts()
check("reopening the app comes back to that conversation", any(friend["nick"] in x for x in t) and find(dump(), rid="input") is not None, str(t)[:250])

print()
print(f"{len(fails)} FAILED" if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
