# PixelBridge

Shared clipboard and file transfer between a Mac and an Android phone (built for the Pixel 10 Pro) over your local Wi-Fi. No cloud and no account. Everything is end-to-end encrypted with a key you pair once.

![PixelBridge on Android: pairing, home, scrolled, dark mode](docs/screenshots/overview.png)

| | Mac → Pixel | Pixel → Mac |
|---|---|---|
| **Clipboard** | Automatic: copy on the Mac, paste on the phone | One tap: the **Send to Mac** Quick Settings tile, the notification button, or **Share → Mac** in the copy bubble |
| **Images on clipboard** | Automatic | Same as clipboard |
| **Files** | Drop files on the menu bar icon, use **Send Files to Phone…**, or right-click in Finder → **Services → Send to Phone (PixelBridge)** | **Share → Mac** from any app (Photos, Files, Chrome…) |
| **Where they land** | `Downloads/PixelBridge` on the phone | `~/Downloads` on the Mac |

> **Why phone → Mac isn't fully automatic:** since Android 10, only the app on screen (or the keyboard) can read the clipboard. Every app that shares a clipboard faces this limit, and Microsoft's Link to Windows works around it by being built into the Samsung keyboard. PixelBridge uses the fastest legal ways: a Quick Settings tile and the Share button in Android's "copied" bubble.

## 1. Mac app

Needs only the Xcode Command Line Tools (already installed here).

```bash
cd ~/Desktop/PixelBridge/mac && ./build-app.sh
```

```bash
mv ~/Desktop/PixelBridge/mac/dist/PixelBridge.app /Applications/ && open /Applications/PixelBridge.app
```

- A phone icon appears in the menu bar. The first launch opens the **Pair your Android phone** window.
- Allow **Local Network** access and **incoming connections** if macOS asks, and allow notifications.
- Menu bar → **Launch at Login** keeps it running.

## 2. Android app (Pixel 10 Pro)

1. Install [Android Studio](https://developer.android.com/studio) and open the `android/` folder (**File → Open**). The first Gradle sync downloads the SDK and dependencies.
2. On the Pixel: **Settings → About phone →** tap **Build number** 7 times, then **System → Developer options → USB debugging** on.
3. Plug in the phone, select it in the device dropdown, and press **Run ▶**.
   Or build a small (about 2.5 MB) installable APK from the terminal, then copy `android/app/build/outputs/apk/release/app-release.apk` to the phone:

   ```bash
   cd ~/Desktop/PixelBridge/android && ./gradlew assembleRelease
   ```

## 3. Pair

1. On the Mac: menu bar icon → **Pair Phone…**
2. On the phone: open PixelBridge → **Scan pairing QR code**. Pointing the Pixel camera at the QR code works too.
   No camera? Use **Enter pairing code** and type the code shown under the QR code.
3. The status changes to **Connected to <your Mac>**.
4. On the phone, tap **Add “Send to Mac” Quick Settings tile** and **Let it stay connected in the background**.

The phone finds the Mac automatically over Bonjour/mDNS, and falls back to the Mac's IP address saved during pairing. Both devices must be on the same network. Some guest or office Wi-Fi networks block devices from talking to each other.

## Everyday use

- **Copy on the Mac → paste on the Pixel.** Nothing else to do.
- **Copy on the Pixel → paste on the Mac:** after copying, tap the **Share** icon in the clipboard bubble at the bottom-left and pick **Mac**. You can also pull down Quick Settings and tap **Send to Mac**.
- **Photo or file to the Mac:** Share → **Mac**. It lands in `~/Downloads` with a notification; click it to show the file in Finder.
- **File to the phone:** drag it onto the menu bar icon. On the phone it lands in **Files → Downloads → PixelBridge**.
- Turn off **Sync Clipboard Automatically** in the Mac menu to stop pushing Mac copies, or turn the switch off in the phone app.

## Security

- Pairing creates a random 128-bit secret on the Mac (`~/Library/Application Support/PixelBridge/pairing.key`, mode 600).
- Each connection derives a fresh AES-256-GCM key (HKDF-SHA256 with random nonces from both sides). Every frame is authenticated and counter-numbered, so tampered, replayed or wrong-key traffic is rejected.
- Clipboard items marked as passwords by 1Password, Bitwarden, Keychain and similar apps (`org.nspasteboard.ConcealedType`) are never sent.
- **Reset Pairing Code…** in the Mac menu revokes the phone instantly.

Details of the wire format are in [PROTOCOL.md](PROTOCOL.md).

## Testing without a phone

`bridge-test-client` pretends to be the phone:

```bash
cd ~/Desktop/PixelBridge/mac && swift build && .build/debug/bridge-test-client --keyfile --text "hi from fake phone" --wait 10
```

While it waits, anything you copy on the Mac is printed as `RECV clip_text …`.

## Troubleshooting

- **Phone stuck on "Looking for your Mac…"**: check that both are on the same Wi-Fi, that the Mac menu says "Waiting for phone…", and that **System Settings → Privacy & Security → Local Network → PixelBridge** is on. Then use **Enter pairing code** with the Mac's IP address shown in the pairing window.
- **"Pairing code rejected"**: the Mac's code was reset. Pair again.
- **Connection drops when the phone sleeps**: tap **Let it stay connected in the background** in the phone app.
- **Finder Services item missing**: log out and back in once after moving the app to /Applications.
