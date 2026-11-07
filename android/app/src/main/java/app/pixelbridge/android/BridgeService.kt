package app.pixelbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service that keeps the Mac connection alive while the app is in the background. */
class BridgeService : Service() {
    private val listener: (BridgeState) -> Unit = { updateNotification(it) }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        ServiceCompat.startForeground(this, ONGOING_ID, buildOngoing(Bridge.state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Bridge.start(this)
        Bridge.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_URIS -> {
                val clip = intent.clipData
                val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
                if (uris.isNotEmpty()) {
                    Bridge.sendUris(uris) { ok ->
                        toast(if (ok) "Sent ${if (uris.size == 1) "file" else "${uris.size} files"} to Mac" else "Couldn't send to Mac")
                    }
                }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Bridge.removeListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun updateNotification(s: BridgeState) {
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, buildOngoing(s))
    }

    private fun buildOngoing(s: BridgeState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sendClip = PendingIntent.getActivity(this, 1,
            Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(statusText(s))
            .setContentText(if (s is BridgeState.Connected) "Copies on your Mac appear here automatically" else "Link to Mac")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (s is BridgeState.Connected) addAction(0, "Send clipboard to Mac", sendClip) }
            .build()
    }

    companion object {
        const val ACTION_SEND_URIS = "app.pixelbridge.SEND_URIS"
        const val ACTION_STOP = "app.pixelbridge.STOP"
        private const val CHANNEL_ONGOING = "connection"
        private const val CHANNEL_FILES = "files"
        private const val ONGOING_ID = 1
        private const val PROGRESS_ID = 2

        fun start(context: Context) {
            Bridge.init(context)
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }

        fun statusText(s: BridgeState) = when (s) {
            is BridgeState.Connected -> "Connected to ${s.macName}"
            is BridgeState.Connecting -> "Connecting…"
            BridgeState.Searching -> "Looking for your Mac…"
            BridgeState.NotPaired -> "Not paired"
            is BridgeState.Error -> s.message
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Connection", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun showProgress(context: Context, name: String, index: Int, total: Int, pct: Int) {
            val n = NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_link)
                .setContentTitle(if (total > 1) "Sending $index of $total to Mac" else "Sending to Mac")
                .setContentText(name)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setSilent(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, n)
        }

        private const val AUTOCLIP_ID = 3

        fun notifyAutoClipNeedsTap(context: Context) {
            createChannels(context)
            val pi = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_link)
                .setContentTitle("Tap to turn on automatic copy")
                .setContentText("Then choose “Allow one-time access” so phone copies reach your Mac")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(AUTOCLIP_ID, n)
        }

        fun clearAutoClipPrompt(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(AUTOCLIP_ID)

        fun clearProgress(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)

        fun notifyFileReceived(context: Context, name: String, uri: Uri, mime: String) {
            createChannels(context)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_link)
                .setContentTitle("Received from Mac")
                .setContentText("$name · Downloads/Link to Mac")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(uri.hashCode(), n)
        }
    }
}

/** Reconnects after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (Prefs.secret != null) BridgeService.start(context)
    }
}
