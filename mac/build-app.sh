#!/bin/bash
# Builds "Link to Mac.app" (menu bar app) into ./dist. Needs only the Xcode Command Line Tools.
set -euo pipefail
cd "$(dirname "$0")"
SCRATCH="${SCRATCH:-.build}"
swift build -c release --product PixelBridge --scratch-path "$SCRATCH"
BIN="$(swift build -c release --product PixelBridge --scratch-path "$SCRATCH" --show-bin-path)/PixelBridge"

APP="dist/Link to Mac.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PixelBridge"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.pixelbridge.mac</string>
  <key>CFBundleName</key><string>Link to Mac</string>
  <key>CFBundleDisplayName</key><string>Link to Mac</string>
  <key>CFBundleExecutable</key><string>PixelBridge</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>Link to Mac talks to your Android phone over your local Wi-Fi network.</string>
  <key>NSBonjourServices</key>
  <array><string>_pixelbridge._tcp</string></array>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key><dict><key>default</key><string>Send to Phone (Link to Mac)</string></dict>
      <key>NSMessage</key><string>sendToPhone</string>
      <key>NSPortName</key><string>Link to Mac</string>
      <key>NSRequiredContext</key><dict/>
      <key>NSSendFileTypes</key><array><string>public.item</string></array>
      <key>NSSendTypes</key><array><string>NSStringPboardType</string><string>public.utf8-plain-text</string></array>
    </dict>
  </array>
</dict>
</plist>
PLIST

# Phone Screen + one-time phone setup use scrcpy (Apache-2.0), which ships with adb.
# Pinned release, verified by SHA-256 before use.
SCRCPY_VER=v5.0.1
SCRCPY_SHA=33611e51977a8289e2e124b220f727895181ef2eb9060e0987a4dc2db9cf0d6b
SCRCPY_DIR="vendor/scrcpy-$SCRCPY_VER"
if [ ! -x "$SCRCPY_DIR/scrcpy" ]; then
  mkdir -p vendor-dl "$SCRCPY_DIR"
  TGZ="vendor-dl/scrcpy-macos-aarch64-$SCRCPY_VER.tar.gz"
  [ -f "$TGZ" ] || curl -fL -o "$TGZ" "https://github.com/Genymobile/scrcpy/releases/download/$SCRCPY_VER/scrcpy-macos-aarch64-$SCRCPY_VER.tar.gz"
  echo "$SCRCPY_SHA  $TGZ" | shasum -a 256 -c -
  tar -xzf "$TGZ" -C "$SCRCPY_DIR" --strip-components=1
fi
mkdir -p "$APP/Contents/Resources/scrcpy"
cp "$SCRCPY_DIR"/{scrcpy,adb,scrcpy-server,scrcpy.png,disconnected.png,LICENSE} "$APP/Contents/Resources/scrcpy/"

codesign --force --deep --sign - "$APP"
echo "Built $(pwd)/$APP"
