# How to install Link to Mac

A step-by-step guide, from download to copying on your phone and pasting on your Mac.
It takes about 10 minutes. Keep your Mac and phone on the **same Wi-Fi** the whole time.

**You need**
- A Mac with macOS 13 or later (Apple Silicon or Intel)
- An Android phone with Android 10 or later

---

## Part 1: Install on the Mac

1. Go to the **[latest release](https://github.com/Okeroghenex/PixelBridge/releases/latest)** and download **`Link-to-Mac-<version>.dmg`**.
2. Open the DMG and drag **Link to Mac** into **Applications**.
3. Open **Link to Mac** from Applications.
4. macOS will say it can't check the app for malware, because it isn't notarized by Apple yet:
   - Open **System Settings › Privacy & Security**.
   - Scroll down and click **Open Anyway** next to *Link to Mac*, then confirm.
   - You only do this once.
5. When macOS asks, click **Allow** for:
   - **Find devices on local networks**
   - **Notifications**
6. A Link to Mac icon appears in the menu bar at the top of the screen. The **Pair your Android phone** window opens with a QR code. Leave it open.

## Part 2: Install on the phone

1. On your phone, open the **[latest release](https://github.com/Okeroghenex/PixelBridge/releases/latest)** and download **`LinkToMac-<version>.apk`**.
2. Open the downloaded file and tap **Install**. If your browser or Files app asks, allow it to install apps.

### If Google Play Protect blocks the install

Play Protect may say **"App blocked to protect your device"**. It blocks apps from outside the Play Store that can read notifications or text messages, which Link to Mac does to mirror them to your Mac. You can turn Play Protect off **for a moment** to install it:

1. Open the **Play Store** and tap your **profile picture** (top right).
2. Tap **Play Protect**, then the **settings gear** (top right).
3. Turn off **Scan apps with Play Protect** and confirm.
4. Go back to the APK and install it.
5. **Turn Play Protect back on** straight away: same place, switch **Scan apps with Play Protect** on again.

> Only do this for the APK from this repo's **Releases** page. Each APK has a `.sha256` file next to it, so you can check the download wasn't changed.
>
> Don't want to touch Play Protect? Install **`LinkToMac-Lite-<version>.apk`** instead. Lite does clipboard, files, Lock Mac and Phone Screen, but not notifications, calls or texts, and Play Protect doesn't block it.

## Part 3: Pair the phone with the Mac

1. Open **Link to Mac** on the phone and tap **Scan QR code**.
2. Scan the QR code in the **Pair your Android phone** window on the Mac.
   (Window closed? Click the menu bar icon › **Pair Phone…**)
3. Tap **Pair**. The phone shows **Connected** with your Mac's name.

From now on, anything you copy on the Mac can be pasted on the phone.

## Part 4: Allow permissions on the phone

In the phone app, tap **⋮ › Settings**. Under **On your Mac**, turn on what you want:
- **Notifications and calls**: shows phone notifications on the Mac and rings the Mac for calls.
- **Text message history**: shows your SMS in **Messages** on the Mac.
- **Let app run in the background**: keeps it connected when the screen is off. Tap **Allow**.

### If a permission is greyed out ("Restricted setting")

Android blocks some permissions for apps installed from outside the Play Store until you allow them:

1. Open **Settings › Apps › Link to Mac** on the phone (or touch and hold the app icon › **App info**).
2. Tap **⋮** (three dots, top right).
3. Tap **Allow restricted settings** and confirm with your fingerprint or PIN.
4. Go back to Link to Mac and turn the permission on again.

## Part 5: Copy on the phone, paste on the Mac (automatic)

This needs a one-time setup from the Mac over **Wireless debugging**.

### Turn on Wireless debugging

1. On the phone, open **Settings › About phone** and tap **Build number** 7 times. You'll see *"You are now a developer"*.
2. Go to **Settings › System › Developer options**.
3. Turn on **Wireless debugging** and tap **Allow** for your Wi-Fi network.

### Link the phone from the Mac

1. On the Mac, click the menu bar icon › **Set Up Phone…**
2. Leave **Pair with QR code** selected.
3. On the phone, tap **Wireless debugging › Pair device with QR code** and scan the code on the Mac.
4. The Mac window changes to show your phone. Next to **Copy on phone, paste on Mac**, click **Turn On**.
5. On the phone, tap **Allow one-time access**.

Now copy something on the phone and press ⌘V on the Mac.

> QR code not working? Choose **Pair with pairing code** in the Mac window instead. On the phone, tap **Pair device with pairing code** and type the **IP address & Port** and the **Wi-Fi pairing code** into the Mac window.

### Phone Screen

In **Set Up Phone…**, click **Open** next to **Phone Screen** to see and control the phone from the Mac. Wireless debugging must be on for this; it switches off when you leave your Wi-Fi.

---

## If something isn't working

- **Phone copies stopped reaching the Mac:** after the phone restarts (or the app is updated), Android asks again. Tap the **"Tap to turn on automatic copy"** notification, then **Allow one-time access**.
- **"Copy here, paste on Mac" asks you to set up, but you already did:** reinstalling the phone app removes its permissions. Do **Part 5 › Link the phone from the Mac** again and click **Turn On**.
- **Phone stuck on "Looking for your Mac…":** check both are on the same Wi-Fi and that **System Settings › Privacy & Security › Local Network › Link to Mac** is on.
- **"Pairing code rejected":** the Mac has a new pairing code (for example after reinstalling it). Pair again from **Pair Phone…**.
- **Set Up Phone says the phone isn't found:** turn Wireless debugging back on, then click **Check Again**. Still nothing? Quit Link to Mac from the menu bar and open it again.
