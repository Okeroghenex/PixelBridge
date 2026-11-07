package app.pixelbridge.android

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Android only lets the app in focus read the clipboard, so "send clipboard" briefly opens this
 * invisible activity, reads the clip once it has window focus, sends it, and closes.
 * Launched from the Quick Settings tile, the notification action, and the main screen.
 */
class ClipboardSendActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        sendClipboard()
        finish()
    }

    private fun sendClipboard() {
        if (!ClipReader.read(this, auto = false)) toast("Clipboard is empty")
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}

/** "Mac" target in the Android share sheet: text → Mac clipboard, files → Mac Downloads. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        val uris = streamUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        when {
            uris.isNotEmpty() -> forwardUris(uris)
            !text.isNullOrEmpty() -> {
                val app = applicationContext
                Bridge.sendText(text) { ok ->
                    Toast.makeText(app, if (ok) "Copied to Mac clipboard" else "Not connected to your Mac", Toast.LENGTH_SHORT).show()
                }
            }
            else -> Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** Hand the URIs (and our read grant) to the service so the transfer outlives this activity. */
    private fun forwardUris(uris: List<Uri>) {
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val i = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_SEND_URIS)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = clip
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Sending to Mac…", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }
}

/** Quick Settings tile: one tap sends the phone's clipboard to the Mac. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        Bridge.init(this)
        qsTile?.apply {
            val s = Bridge.state
            state = Tile.STATE_INACTIVE // always tappable; the send waits briefly for a connection
            subtitle = if (s is BridgeState.Connected) s.macName else "Not connected"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
