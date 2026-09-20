#!/usr/bin/env bash
# Package the desktop app for Windows x64 from Linux/WSL, with no Windows
# toolchain: the official Electron Windows build (checksum-verified), the app's
# own files, and the per-app audio helper cross-compiled with Zig.
#
#   ZIG=/path/to/zig scripts/package-win.sh      -> dist/take-back-desktop-win32-x64-<ver>.zip
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(node -p 'require("./package.json").version')
ELECTRON=$(node -p 'require("./node_modules/electron/package.json").version')
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/take-back-desktop"
NAME="take-back-desktop-win32-x64-$VERSION"
OUT="dist/$NAME"
ZIPNAME="electron-v$ELECTRON-win32-x64.zip"
BASE="https://github.com/electron/electron/releases/download/v$ELECTRON"

mkdir -p "$CACHE" dist
if [ ! -f "$CACHE/$ZIPNAME" ]; then
  echo "downloading Electron $ELECTRON for Windows…"
  curl -sSL -o "$CACHE/$ZIPNAME.part" "$BASE/$ZIPNAME"
  mv "$CACHE/$ZIPNAME.part" "$CACHE/$ZIPNAME"
fi
# Never ship an unverified runtime: check it against Electron's published sums.
curl -sSL "$BASE/SHASUMS256.txt" | grep " \*\?$ZIPNAME\$" | sed 's/\*//' > "$CACHE/$ZIPNAME.sha256"
(cd "$CACHE" && sha256sum -c "$ZIPNAME.sha256")

echo "building the audio helper…"
native/win/build.sh >/dev/null

rm -rf "$OUT" && mkdir -p "$OUT"
unzip -q "$CACHE/$ZIPNAME" -d "$OUT"
mv "$OUT/electron.exe" "$OUT/take-back.exe"
rm -f "$OUT/resources/default_app.asar"

APP="$OUT/resources/app"
mkdir -p "$APP"
cp -r src build "$APP/"
# Runtime needs no npm dependencies; ship a package.json without the dev ones.
node -e '
  const p = require("./package.json");
  delete p.devDependencies; delete p.allowScripts; delete p.scripts;
  require("fs").writeFileSync(process.argv[1], JSON.stringify(p, null, 2));
' "$APP/package.json"
# audio-sources.js looks for the helper next to the app's resources.
cp native/win/bin/tb-app-audio.exe "$OUT/resources/tb-app-audio.exe"

rm -f "dist/$NAME.zip" "dist/$NAME.zip.sha256"
(cd dist && zip -qr "$NAME.zip" "$NAME")
# The build is unsigned, so this checksum is all anyone downloading it has to
# check it against. Publish it next to the download link.
(cd dist && sha256sum "$NAME.zip" > "$NAME.zip.sha256")
ls -la "dist/$NAME.zip"
cat "dist/$NAME.zip.sha256"
