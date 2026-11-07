package app.pixelbridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Sends phone copies to the Mac automatically, like Universal Clipboard.
 *
 * Android 10+ only lets the focused app read the clipboard. Two one-time grants (done from the Mac
 * over adb: READ_LOGS and "display over other apps") let us work around that the way KDE Connect does:
 *  1. When anything is copied, ClipboardService logs that it refused to tell *us* about it.
 *  2. Seeing that log line, we add an invisible 1×1 overlay window, which takes focus for a moment,
 *     read the clipboard, send it to the Mac and remove the window.
 */
object AutoClipboard {
    private const val TAG = "PixelBridge/AutoClip"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var watcher: Thread? = null
    @Volatile private var lastGrab = 0L
    private var grabbing = false

    /** Signature of the clip we last put on / sent from the clipboard, to avoid echoes. */
    @Volatile var lastSignature: String? = null

    fun hasLogAccess(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun isAvailable(ctx: Context) = hasLogAccess(ctx) && canOverlay(ctx)

    val isWatching: Boolean get() = watcher?.isAlive == true

    /**
     * Must be called while our UI is in front: Android 13+ asks "Allow access to device logs?" the first
     * time we read logs in each app process, and can only show that question to a visible app.
     */
    @Synchronized
    fun start(context: Context) {
        val ctx = context.applicationContext
        if (!hasLogAccess(ctx) || watcher?.isAlive == true) return
        // Being registered as a listener is what makes ClipboardService log the refusal for us.
        main.post {
            ctx.getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener {
                // Fires directly only while we're in front; otherwise the log path below handles it.
                trigger(ctx)
            }
        }
        watcher = Thread({ watchLog(ctx) }, "pixelbridge-logwatch").apply { isDaemon = true; start() }
    }

    private fun watchLog(ctx: Context) {
        val pkg = ctx.packageName
        while (true) {
            try {
                // Only lines written from now on: replaying an old line would re-send a stale clip.
                val since = String.format(java.util.Locale.US, "%.3f", System.currentTimeMillis() / 1000.0)
                val p = ProcessBuilder("logcat", "-v", "epoch", "-T", since, "ClipboardService:E", "*:S")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains(pkg) && line.contains("clipboard", ignoreCase = true)) trigger(ctx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat", e)
            }
            Thread.sleep(3000) // logcat exited (e.g. permission revoked); retry
        }
    }

    private fun trigger(ctx: Context) {
        if (!Prefs.autoSendClipboard || Bridge.state !is BridgeState.Connected) return
        main.post { grab(ctx) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun grab(ctx: Context) {
        val now = System.currentTimeMillis()
        if (grabbing || now - lastGrab < 800) return
        lastGrab = now
        // Don't try a direct read here: a refused read logs another denial and would loop.
        if (!canOverlay(ctx)) return
        grabbing = true
        val wm = ctx.getSystemService(WindowManager::class.java)
        var view: View? = null
        val done = Runnable {
            view?.let { v -> try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
            view = null
            grabbing = false
        }
        view = object : View(ctx) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) {
                    ClipReader.read(ctx, auto = true)
                    main.removeCallbacks(done)
                    main.post(done)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(view, lp)
            main.postDelayed(done, 1500) // never hold focus longer than this
        } catch (e: Exception) {
            Log.w(TAG, "overlay", e)
            grabbing = false
        }
    }
}

/** Reads the current clip (only works while we have focus) and sends it to the Mac. */
object ClipReader {
    /** Returns true if the clipboard could be read. */
    fun read(ctx: Context, auto: Boolean): Boolean {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = try { cm.primaryClip } catch (_: SecurityException) { null } ?: return false
        if (clip.itemCount == 0) return true
        val item = clip.getItemAt(0)
        val app = ctx.applicationContext
        val done: (Boolean) -> Unit = { ok ->
            if (!auto) Toast.makeText(app, if (ok) "Sent to Mac clipboard" else "Not connected to your Mac", Toast.LENGTH_SHORT).show()
        }
        item.uri?.let { uri ->
            val mime = ctx.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) {
                val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                if (bytes != null && bytes.size <= Proto.MAX_CLIP_IMAGE) {
                    val sig = "img:${bytes.size}:${bytes.contentHashCode()}"
                    if (auto && sig == AutoClipboard.lastSignature) return true
                    AutoClipboard.lastSignature = sig
                    Bridge.sendClipImage(bytes, mime, done)
                    return true
                }
            }
        }
        val text = item.coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty()) {
            if (!auto) Toast.makeText(app, "Nothing to send", Toast.LENGTH_SHORT).show()
            return true
        }
        val sig = "txt:${text.hashCode()}:${text.length}"
        if (auto && sig == AutoClipboard.lastSignature) return true
        AutoClipboard.lastSignature = sig
        Bridge.sendText(text, done)
        return true
    }
}
