package app.pixelbridge.android

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors phone notifications to the Mac (with inline reply), and turns the phone app's
 * incoming-call notification into a "ringing" event so the Mac rings too.
 * Needs "Notification access" (Settings › Notifications › Device & app notifications).
 */
class NotificationMirror : NotificationListenerService() {
    private val active = ConcurrentHashMap<String, StatusBarNotification>()
    private val lastSent = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var ringingKey: String? = null

    override fun onListenerConnected() {
        instance = this
        Bridge.init(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handlePosted(sbn) } catch (e: Exception) { Log.w(TAG, "posted", e) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        if (key == ringingKey) {
            ringingKey = null
            Bridge.sendEvent(Message.of("call", mapOf("state" to "ended", "key" to key)))
        }
        if (active.remove(key) != null) {
            lastSent.remove(key)
            Bridge.sendEvent(Message.of("notif_removed", mapOf("key" to key)))
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) { handleCall(sbn); return }

        if (!Prefs.mirrorNotifications) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 || sbn.isOngoing) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = latestMessage(ex)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        // Apps re-post the same notification often (progress, re-sort); only forward real changes.
        val sig = "$title\u0000$text"
        if (lastSent[sbn.key] == sig) return
        lastSent[sbn.key] = sig
        active[sbn.key] = sbn

        Bridge.sendEvent(Message.of("notif", mapOf(
            "key" to sbn.key,
            "app" to sbn.packageName,
            "appName" to appName(sbn.packageName),
            "title" to title,
            "text" to text,
            "time" to sbn.postTime,
            "canReply" to (replyAction(n) != null),
        ), appIcon(sbn.packageName)))
    }

    /** For messaging apps, the newest message reads better than the summary line. */
    private fun latestMessage(ex: Bundle): String? {
        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = msgs.lastOrNull() as? Bundle ?: return null
        val text = last.getCharSequence("text")?.toString() ?: return null
        val sender = last.getCharSequence("sender")?.toString()
        val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return if (isGroup && !sender.isNullOrBlank()) "$sender: $text" else text
    }

    // region Calls

    private fun handleCall(sbn: StatusBarNotification) {
        if (!Prefs.callAlerts) return
        val n = sbn.notification
        val ex = n.extras
        val callType = ex.getInt("android.callType", 0) // 1 incoming, 2 ongoing, 3 screening
        val titles = n.actions.orEmpty().map { it.title?.toString().orEmpty().lowercase() }
        val ringing = callType == 1 || (callType == 0 && titles.any { "answer" in it || "decline" in it })
        if (!ringing) {
            if (ringingKey == sbn.key) {
                ringingKey = null
                Bridge.sendEvent(Message.of("call", mapOf("state" to "active", "key" to sbn.key)))
            }
            return
        }
        if (ringingKey == sbn.key) return
        ringingKey = sbn.key
        active[sbn.key] = sbn
        val person: Person? = if (android.os.Build.VERSION.SDK_INT >= 33)
            ex.getParcelable("android.callPerson", Person::class.java)
        else @Suppress("DEPRECATION") ex.getParcelable("android.callPerson")
        val name = person?.name?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown caller"
        val detail = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        Bridge.sendEvent(Message.of("call", mapOf(
            "state" to "ringing", "key" to sbn.key, "name" to name, "detail" to detail,
            "canDecline" to (declineAction(n) != null),
        ), appIcon(sbn.packageName)))
    }

    private fun declineAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a ->
            val t = a.title?.toString().orEmpty().lowercase()
            "decline" in t || "reject" in t || "hang up" in t
        }

    // endregion

    // region Actions from the Mac

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    fun reply(key: String, text: String): Boolean {
        val sbn = active[key] ?: return false
        val action = replyAction(sbn.notification) ?: return false
        val inputs = action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try { action.actionIntent.send(this, 0, intent); true } catch (e: Exception) { Log.w(TAG, "reply", e); false }
    }

    fun dismiss(key: String) {
        try { cancelNotification(key) } catch (_: Exception) {}
    }

    fun decline(key: String): Boolean {
        val sbn = active[key] ?: return false
        val action = declineAction(sbn.notification) ?: return false
        return try { action.actionIntent.send(); true } catch (e: Exception) { Log.w(TAG, "decline", e); false }
    }

    // endregion

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** 96 px PNG of the app icon so the Mac can show where a notification came from. */
    private fun appIcon(pkg: String): ByteArray = iconCache.getOrPut(pkg) {
        try {
            val d = packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(Canvas(bmp))
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } catch (_: Exception) { ByteArray(0) }
    }

    companion object {
        private const val TAG = "PixelBridge/Notif"
        @Volatile var instance: NotificationMirror? = null

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationMirror::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun accessSettingsIntent(context: Context): Intent =
            if (android.os.Build.VERSION.SDK_INT >= 30)
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, NotificationMirror::class.java).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
