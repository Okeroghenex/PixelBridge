#!/bin/bash
# Packages dist/Link to Mac.app into dist/Link-to-Mac-<version>.dmg (drag-to-Applications layout).
# Run ./build-app.sh first.
set -euo pipefail
cd "$(dirname "$0")"
APP="dist/Link to Mac.app"
[ -d "$APP" ] || { echo "Build the app first: ./build-app.sh"; exit 1; }
VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "$APP/Contents/Info.plist")
DMG="dist/Link-to-Mac-$VERSION.dmg"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cat > "$STAGE/If macOS won't open it.txt" <<'TXT'
Link to Mac isn't signed with a paid Apple Developer certificate yet, so the first time
you open it macOS says it "can't check it for malicious software". To open it anyway:

1. Drag "Link to Mac" into Applications and double-click it once (it will be blocked).
2. Open System Settings › Privacy & Security, scroll down, and click "Open Anyway"
   next to the message about Link to Mac. Confirm with your password or Touch ID.

You only need to do this once. The source code is at
https://github.com/Okeroghenex/PixelBridge
TXT

rm -f "$DMG"
hdiutil create -volname "Link to Mac" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$DMG" >/dev/null
shasum -a 256 "$DMG" | tee "$DMG.sha256"
echo "Created $(pwd)/$DMG ($(du -h "$DMG" | cut -f1))"
