#!/bin/bash
# Builds "Link to Mac.app" (menu bar app) into ./dist as a universal app (Apple Silicon + Intel).
# Needs only the Xcode Command Line Tools.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-1.5}"
BUILD="${BUILD:-6}"
SCRATCH="${SCRATCH:-.build}"

# 1. The app itself, for both CPU types, merged with lipo.
BINS=()
for ARCH in arm64 x86_64; do
  swift build -c release --product PixelBridge --scratch-path "$SCRATCH/$ARCH" --triple "$ARCH-apple-macosx13.0"
  BINS+=("$(swift build -c release --product PixelBridge --scratch-path "$SCRATCH/$ARCH" --triple "$ARCH-apple-macosx13.0" --show-bin-path)/PixelBridge")
done

APP="dist/Link to Mac.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create "${BINS[@]}" -output "$APP/Contents/MacOS/PixelBridge"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.pixelbridge.mac</string>
  <key>CFBundleName</key><string>Link to Mac</string>
  <key>CFBundleDisplayName</key><string>Link to Mac</string>
  <key>CFBundleExecutable</key><string>PixelBridge</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
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

# 2. Icons from the brand SVGs in ../branding (menu bar template + app icon).
swift tools/make-icons.swift ../branding "$APP/Contents/Resources"

# 3. Phone Screen + one-time phone setup use scrcpy (Apache-2.0), which ships with adb.
#    Pinned release, both CPU builds verified by SHA-256, then merged into universal binaries.
SCRCPY_VER=v5.0.1
fetch_scrcpy() { # $1 = arch name in the release, $2 = sha256
  local dir="vendor/scrcpy-$SCRCPY_VER-$1" tgz="vendor-dl/scrcpy-macos-$1-$SCRCPY_VER.tar.gz"
  if [ ! -x "$dir/scrcpy" ]; then
    mkdir -p vendor-dl "$dir"
    [ -f "$tgz" ] || curl -fL -o "$tgz" "https://github.com/Genymobile/scrcpy/releases/download/$SCRCPY_VER/scrcpy-macos-$1-$SCRCPY_VER.tar.gz"
    echo "$2  $tgz" | shasum -a 256 -c -
    tar -xzf "$tgz" -C "$dir" --strip-components=1
  fi
}
fetch_scrcpy aarch64 33611e51977a8289e2e124b220f727895181ef2eb9060e0987a4dc2db9cf0d6b
fetch_scrcpy x86_64  31a5467a9e907f162b093267cbbab1276b15c6e709c2ea8159566a8d165beb50
ARM="vendor/scrcpy-$SCRCPY_VER-aarch64" INTEL="vendor/scrcpy-$SCRCPY_VER-x86_64"
OUT="$APP/Contents/Resources/scrcpy"
mkdir -p "$OUT"
for BIN in scrcpy adb; do
  if [ "$(lipo -archs "$ARM/$BIN")" != "arm64" ]; then cp "$ARM/$BIN" "$OUT/$BIN"  # already universal (adb)
  else lipo -create "$ARM/$BIN" "$INTEL/$BIN" -output "$OUT/$BIN"; fi
done
cp "$ARM"/{scrcpy-server,scrcpy.png,disconnected.png,LICENSE} "$OUT/"

# 4. Licences for everything bundled.
cp ../LICENSE "$APP/Contents/Resources/LICENSE.txt" 2>/dev/null || true
cp ../THIRD_PARTY_NOTICES.md "$APP/Contents/Resources/" 2>/dev/null || true

# Ad-hoc signature. For downloads that open without a Gatekeeper warning, sign with a
# Developer ID certificate and notarize instead (see README › Releasing).
codesign --force --deep --sign - "$APP"
echo "Built $(pwd)/$APP ($VERSION, universal)"
