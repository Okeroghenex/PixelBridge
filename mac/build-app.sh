#!/bin/bash
# Builds PixelBridge.app (menu bar app) into ./dist. Needs only the Xcode Command Line Tools.
set -euo pipefail
cd "$(dirname "$0")"
SCRATCH="${SCRATCH:-.build}"
swift build -c release --product PixelBridge --scratch-path "$SCRATCH"
BIN="$(swift build -c release --product PixelBridge --scratch-path "$SCRATCH" --show-bin-path)/PixelBridge"

APP=dist/PixelBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PixelBridge"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.pixelbridge.mac</string>
  <key>CFBundleName</key><string>PixelBridge</string>
  <key>CFBundleDisplayName</key><string>PixelBridge</string>
  <key>CFBundleExecutable</key><string>PixelBridge</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>PixelBridge talks to your Android phone over your local Wi-Fi network.</string>
  <key>NSBonjourServices</key>
  <array><string>_pixelbridge._tcp</string></array>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key><dict><key>default</key><string>Send to Phone (PixelBridge)</string></dict>
      <key>NSMessage</key><string>sendToPhone</string>
      <key>NSPortName</key><string>PixelBridge</string>
      <key>NSRequiredContext</key><dict/>
      <key>NSSendFileTypes</key><array><string>public.item</string></array>
      <key>NSSendTypes</key><array><string>NSStringPboardType</string><string>public.utf8-plain-text</string></array>
    </dict>
  </array>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "$APP"
echo "Built $(pwd)/$APP"
