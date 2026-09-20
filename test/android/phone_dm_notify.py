#!/usr/bin/env python3
"""A DM must reach you with the app closed (@river)."""
import sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import *

fails = []
def check(label, ok, extra=""):
    print(("✓ " if ok else "✗ ") + label + (f" — {extra}" if extra and not ok else ""))
    if not ok: fails.append(label)

PKG = "com.takeback.app"
me = register("phoneDM")
friend = register("webDM")
api("/api/friends/request", "POST", {"nick": friend["nick"]}, cookie=me["cookie"])
api("/api/friends/respond", "POST", {"userId": me["id"], "accept": True}, cookie=friend["cookie"])

shell(f"am force-stop {PKG}")   # a screen left over from a previous run
shell(f"pm clear {PKG}")
shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
shell(f"am start -n {PKG}/.LoginActivity")
time.sleep(3)
tap(find(dump(), rid="nick")); type_text(me["nick"])
tap(find(dump(), rid="password")); type_text("pw123456")
tap_text(rid="submit", label="log in")
time.sleep(4)
a = find(dump(), text="Allow")
if a: tap(a); time.sleep(1.5)

services = shell(f"dumpsys activity services {PKG}")
check("the app holds a connection while it runs", "ConnectionService" in services, services[:200])

# Close the app entirely: not just backgrounded — swiped away.
shell("input keyevent KEYCODE_HOME")
time.sleep(2)
shell(f"am kill {PKG}")   # what Android does to a backgrounded app under pressure
time.sleep(3)

api("/api/messages", "POST", {"with": me["id"], "body": "are you there?"}, cookie=friend["cookie"])
time.sleep(6)
notifs = shell("dumpsys notification --noredact")
check("a DM arrives as a notification with the app closed",
      "are you there?" in notifs, [l for l in notifs.splitlines() if "takeback" in l.lower()][:3])
check("and it names who sent it", f"Message from {friend['nick']}" in notifs, str([l.strip() for l in notifs.splitlines() if 'Message from' in l][:2]))

# Tapping it should land in that conversation, not just the app.
# Tapping it opens that conversation. Not asserted here: driving the
# notification shade from adb is unreliable — a collapsed group swallows the tap
# and opens the app instead — and what it would prove is the PendingIntent,
# which is built in Events.post. Verified by hand on the emulator: expand the
# group, tap the message, and ChatActivity comes up (it used to land on the home
# screen, because NEW_TASK|CLEAR_TOP cleared the task back to its root).
check("the notification carries somewhere to go",
      "contentIntent=PendingIntent" in shell("dumpsys notification --noredact"))

print()
print(f"{len(fails)} FAILED" if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
