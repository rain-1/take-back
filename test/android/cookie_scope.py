#!/usr/bin/env python3
"""The session cookie must not follow a redirect to a different host.

The phone's cookie jar persists the session so a login survives a restart. The
danger in writing one yourself is that the jar is asked for cookies on EVERY
request the HTTP client makes, including the second leg of a redirect — a jar
that answers "all of them" hands the session to whatever host the first server
names.

This drives the real app on the emulator against a stand-in take-back server:
  * :19690 is "the server the app is pointed at". It sets a session cookie and
    answers /api/version with a redirect to a DIFFERENT host.
  * :19691 is that other host, reached from the phone as 127.0.0.1 through
    `adb reverse`. It records whether a Cookie header arrived.

A different port is not enough to make a different host — cookies ignore ports
— hence the loopback/host-IP pair: the app's server is 10.0.2.2, the redirect
target is 127.0.0.1.

Run it like the other phone tests, with the emulator up:

    python3 test/android/cookie_scope.py
"""
import http.server
import os
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.abspath(os.path.join(HERE, "..", "..", "android"))
ADB = [os.environ.get("ADB", os.path.expanduser("~/android-sdk/platform-tools/adb")),
       "-s", os.environ.get("EMULATOR", "emulator-5556")]
PKG = "com.takeback.app"

SERVER_PORT = 19690   # stands in for the take-back server, as 10.0.2.2
OTHER_PORT = 19691    # the host a redirect points at, as 127.0.0.1
SESSION = "tb_session=LEAKME"

seen = []      # Cookie headers (or None) that reached the other host
returned = []  # Cookie headers (or None) the app sent back to its own server


class Server(http.server.BaseHTTPRequestHandler):
    """The server the app is pointed at: sets a session, then redirects away."""

    def do_GET(self):
        returned.append(self.headers.get("Cookie"))
        if self.path.startswith("/api/version"):
            self.send_response(302)
            self.send_header("Set-Cookie", SESSION + "; Path=/; HttpOnly")
            self.send_header("Location", f"http://127.0.0.1:{OTHER_PORT}/api/version")
            self.end_headers()
            return
        self.send_response(401)
        self.send_header("Set-Cookie", SESSION + "; Path=/; HttpOnly")
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"error":"not logged in"}')

    do_POST = do_GET

    def log_message(self, *a):
        pass


class Other(http.server.BaseHTTPRequestHandler):
    """A different host entirely. It should never be shown the session."""

    def do_GET(self):
        seen.append(self.headers.get("Cookie"))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"name":"x","version":"1.0.0","protocol":1}')

    do_POST = do_GET

    def log_message(self, *a):
        pass


def serve(handler, port):
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", port), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True).stdout


def build_and_install():
    """Build the app pointed at the stand-in server, and put it on the phone.

    build.gradle.kts is restored whatever happens: a checkout left pointing at a
    test server is how you ship a broken release.
    """
    gradle = os.path.join(ANDROID, "app", "build.gradle.kts")
    original = open(gradle).read()
    patched = re.sub(r'"\\"https?://[^"\\]*\\""',
                     f'"\\\\"http://10.0.2.2:{SERVER_PORT}\\\\""', original, count=1)
    if patched == original:
        sys.exit("✗ could not point BASE_URL at the stand-in server")
    env = dict(os.environ, ANDROID_HOME=os.path.expanduser("~/android-sdk"),
               JAVA_HOME=os.path.expanduser("~/jdk21"))
    try:
        open(gradle, "w").write(patched)
        r = subprocess.run(["./gradlew", "-q", "assembleDebug"], cwd=ANDROID, env=env,
                           capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit("✗ build failed\n" + r.stdout + r.stderr)
    finally:
        open(gradle, "w").write(original)
    apk = os.path.join(ANDROID, "app/build/outputs/apk/debug/app-debug.apk")
    print(adb("install", "-r", apk).strip().splitlines()[-1])
    adb("shell", f"pm clear {PKG}")  # a stored server URL would override the build's


def main():
    serve(Server, SERVER_PORT)
    serve(Other, OTHER_PORT)
    # The phone's own 127.0.0.1:19691 has to reach the collector on this machine.
    adb("reverse", f"tcp:{OTHER_PORT}", f"tcp:{OTHER_PORT}")
    try:
        build_and_install()
        adb("shell", f"am start -n {PKG}/.LoginActivity")
        for _ in range(30):
            if seen:
                break
            time.sleep(1)
        # ...and the other half of the jar's job: the session has to survive the
        # app closing, and go back to the server it belongs to. Scoping cookies
        # too tightly would break the login instead of the leak.
        adb("shell", f"am force-stop {PKG}")
        returned.clear()
        adb("shell", f"am start -n {PKG}/.LoginActivity")
        for _ in range(30):
            if any(c and "tb_session" in c for c in returned):
                break
            time.sleep(1)
        adb("shell", f"am force-stop {PKG}")
        adb("shell", "input keyevent KEYCODE_HOME")  # leave the phone on home
    finally:
        adb("reverse", "--remove", f"tcp:{OTHER_PORT}")

    if not seen:
        sys.exit("✗ the redirect was never followed — the test proved nothing")
    leaked = [c for c in seen if c and "tb_session" in c]
    if leaked:
        sys.exit(f"✗ the session cookie followed the redirect to another host: {leaked[0]}")
    if not any(c and "tb_session" in c for c in returned):
        sys.exit("✗ the session was not sent back to its own server after a restart")
    print(f"✓ {len(seen)} request(s) reached the other host with no session cookie, "
          "and the session still returns to its own server after a restart")


if __name__ == "__main__":
    main()
