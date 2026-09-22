# Driving the phone app on an emulator

These tap through the real Android app on a headless emulator and check what
the server sees, so phone changes can be verified rather than just compiled.

## Once

The emulator needs hardware virtualisation: your user must be able to read and
write `/dev/kvm` (`sudo usermod -aG kvm $USER`, then a fresh login, or
`sg kvm -c '<command>'` in the same session).

```sh
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n tbtest -k "system-images;android-34;google_apis;x86_64" -d pixel_5
```

## Each run

```sh
# 1. a local take-back (see ../e2e/README.md), web on :19290
# 2. the emulator, headless and silent — no window appears on anyone's desktop
sg kvm -c '$ANDROID_HOME/emulator/emulator -avd tbtest -no-window -no-audio \
    -no-snapshot -no-boot-anim -gpu swiftshader_indirect -port 5556'

# 3. the app, built against the local server (10.0.2.2 is the host from inside
#    the emulator), installed, and driven
./gradlew -PtakeBackBaseUrl=http://10.0.2.2:19290 assembleDebug
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
PUPPETEER=/path/to/node_modules/puppeteer-core python3 test/android/phone_smoke.py
```

`phone_dm_notify.py` checks a DM reaches you with the app killed.
`phone_background.py` is the one that matters for calls: it joins a voice
channel, sends the app to the background with the home key, and checks a
browser keeps receiving its audio for a minute (via `TBCall.stats()`), plus
that the ongoing-call notification is up. `phone_calls.py` watches a call arrive in a chat and turn into history;
`phone_extras.py` covers the incoming-call banner and coming back to the last
conversation (run that one against a server with the normal call grace, not a
shortened one). `phone_smoke.py` signs in, makes a server, sends a message in a channel, opens
a picture, joins a voice channel — with a headless browser joining the same
channel as the other person — turns the camera on, and checks the browser
receives the phone's video. `emu.py` holds the adb/uiautomator helpers.

`cookie_scope.py` is the odd one out: it needs no take-back server and no
browser, because it brings its own stand-in server. It checks the session cookie
goes to the server it belongs to and nowhere else — not to a host the server
redirects to — and that it still survives the app being closed. It builds and
installs the app itself (restoring `build.gradle.kts` afterwards) and clears the
app's data, so run it when you don't mind being signed out on the emulator.

Leave the emulator on the home screen between runs: an expanded notification
shade swallows taps and every test then fails at its first tap.
