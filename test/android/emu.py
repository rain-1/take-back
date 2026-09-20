#!/usr/bin/env python3
"""Drive the take-back app on the headless emulator through adb."""
import json, os, re, subprocess, sys, time, urllib.request

ADB = [os.environ.get("ADB", os.path.expanduser("~/android-sdk/platform-tools/adb")),
       "-s", os.environ.get("EMULATOR", "emulator-5556")]
WEB = os.environ.get("WEB", "http://127.0.0.1:19290")

def adb(*args, **kw):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, **kw).stdout

def shell(cmd):
    return adb("shell", cmd)

def dump():
    """The current screen's view hierarchy as XML."""
    for _ in range(6):
        out = shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
        if "<hierarchy" in out:
            return out
        time.sleep(1)
    return ""

def nodes(xml):
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', tag))
        yield attrs

def find(xml, text=None, rid=None, contains=None, cls=None):
    for a in nodes(xml):
        if text is not None and a.get("text") != text: continue
        if rid is not None and not a.get("resource-id", "").endswith(rid): continue
        if contains is not None and contains.lower() not in a.get("text", "").lower(): continue
        if cls is not None and a.get("class") != cls: continue
        return a
    return None

def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node["bounds"]))
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap(node):
    x, y = center(node)
    shell(f"input tap {x} {y}")
    time.sleep(1.2)

def tap_text(text=None, rid=None, contains=None, tries=4, label=None):
    for _ in range(tries):
        n = find(dump(), text=text, rid=rid, contains=contains)
        if n:
            tap(n)
            return True
        time.sleep(1)
    print(f"✗ could not find {label or text or rid or contains}")
    return False

def type_text(s):
    shell("input text " + s.replace(" ", "%s"))
    time.sleep(0.6)

def texts():
    return [a.get("text", "") for a in nodes(dump()) if a.get("text")]

def api(path, method="GET", body=None, cookie=None):
    req = urllib.request.Request(WEB + path, method=method)
    if cookie: req.add_header("Cookie", cookie)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data) as r:
        return json.loads(r.read().decode() or "null"), r.headers.get("set-cookie", "")

def register(nick, password="pw123456"):
    out, sc = api("/api/register", "POST", {"nick": nick, "password": password})
    return {"nick": nick, "id": out["id"], "cookie": sc.split(";")[0]}
