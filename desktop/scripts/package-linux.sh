#!/usr/bin/env bash
# Package the desktop app for Linux x64: the official Electron Linux build
# (checksum-verified) and the app's own files. Per-app audio uses PipeWire's
# pw-dump / pw-record from the user's system, so there is no helper to build.
# Needs curl, jq and unzip — not Node, so it runs on a machine without npm.
#
#   scripts/package-linux.sh      -> dist/take-back-desktop-linux-x64-<ver>.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(jq -r .version package.json)
# The Electron version npm resolved, so this matches what `npm start` runs.
ELECTRON=$(jq -r '.packages["node_modules/electron"].version' package-lock.json)
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/take-back-desktop"
NAME="take-back-desktop-linux-x64-$VERSION"
OUT="dist/$NAME"
ZIPNAME="electron-v$ELECTRON-linux-x64.zip"
BASE="https://github.com/electron/electron/releases/download/v$ELECTRON"

mkdir -p "$CACHE" dist
if [ ! -f "$CACHE/$ZIPNAME" ]; then
  echo "downloading Electron $ELECTRON for Linux…"
  curl -sSL -o "$CACHE/$ZIPNAME.part" "$BASE/$ZIPNAME"
  mv "$CACHE/$ZIPNAME.part" "$CACHE/$ZIPNAME"
fi
# Never ship an unverified runtime: check it against Electron's published sums.
curl -sSL "$BASE/SHASUMS256.txt" | grep " \*\?$ZIPNAME\$" | sed 's/\*//' > "$CACHE/$ZIPNAME.sha256"
(cd "$CACHE" && sha256sum -c "$ZIPNAME.sha256")

rm -rf "$OUT" && mkdir -p "$OUT"
unzip -q "$CACHE/$ZIPNAME" -d "$OUT"
mv "$OUT/electron" "$OUT/take-back"
rm -f "$OUT/resources/default_app.asar"

APP="$OUT/resources/app"
mkdir -p "$APP"
cp -r src build "$APP/"
# Runtime needs no npm dependencies; ship a package.json without the dev ones.
jq 'del(.devDependencies, .allowScripts, .scripts)' package.json > "$APP/package.json"

# A launcher entry and icon; install.sh puts them where the desktop finds them.
cp build/icon.png "$OUT/take-back.png"
cat > "$OUT/take-back.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=take-back
Comment=Chat and peer-to-peer calls
Exec=take-back %U
Icon=take-back
Terminal=false
Categories=Network;Chat;InstantMessaging;
StartupWMClass=take-back
EOF
cat > "$OUT/install.sh" <<'EOF'
#!/usr/bin/env bash
# Add take-back to your applications menu (per user, no root needed).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
icons="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/512x512/apps"
mkdir -p "$apps" "$icons"
cp "$here/take-back.png" "$icons/take-back.png"
sed "s|^Exec=take-back|Exec=\"$here/take-back\"|" "$here/take-back.desktop" > "$apps/take-back.desktop"
echo "installed: $apps/take-back.desktop"
EOF
chmod +x "$OUT/install.sh"

rm -f "dist/$NAME.tar.gz" "dist/$NAME.tar.gz.sha256"
# Owner/permissions normalised so the archive doesn't carry this machine's uid.
tar -C dist --owner=0 --group=0 -czf "dist/$NAME.tar.gz" "$NAME"
# The build is unsigned, so this checksum is all anyone downloading it has to
# check it against. Publish it next to the download link.
(cd dist && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
ls -la "dist/$NAME.tar.gz"
cat "dist/$NAME.tar.gz.sha256"
