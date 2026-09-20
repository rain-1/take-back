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
#    (edit BASE_URL in android/app/build.gradle.kts to http://10.0.2.2:19290)
./gradlew assembleDebug && adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
PUPPETEER=/path/to/node_modules/puppeteer-core python3 test/android/phone_smoke.py
```

`phone_calls.py` watches a call arrive in a chat and turn into history;
`phone_extras.py` covers the incoming-call banner and coming back to the last
conversation (run that one against a server with the normal call grace, not a
shortened one). `phone_smoke.py` signs in, makes a server, sends a message in a channel, opens
a picture, joins a voice channel — with a headless browser joining the same
channel as the other person — turns the camera on, and checks the browser
receives the phone's video. `emu.py` holds the adb/uiautomator helpers.
