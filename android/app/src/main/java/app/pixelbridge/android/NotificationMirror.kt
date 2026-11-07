package app.pixelbridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
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
    /** Key of the call notification we're tracking, and whether it has been picked up. */
    @Volatile private var callKey: String? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingEnd = Runnable {}
    @Volatile private var inCall = false

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
        if (key == callKey) {
            // The phone app often cancels and re-posts its call notification (e.g. when the full-screen
            // call UI opens). Only report "ended" if no call notification comes back shortly.
            main.removeCallbacks(pendingEnd)
            pendingEnd = Runnable {
                if (callKey == key) {
                    callKey = null
                    inCall = false
                    Bridge.sendEvent(Message.of("call", mapOf("state" to "ended", "key" to key)))
                }
            }
            main.postDelayed(pendingEnd, 1500)
        }
        if (active.remove(key) != null) {
            lastSent.remove(key)
            Bridge.sendEvent(Message.of("notif_removed", mapOf("key" to key)))
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (isCall(n)) { handleCall(sbn); return }

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

    /** Call notifications don't always keep category=call when re-posted; their CallStyle layout is reliable. */
    private fun isCall(n: Notification): Boolean =
        n.category == Notification.CATEGORY_CALL ||
            n.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle" ||
            n.extras.containsKey("android.callType")

    private fun handleCall(sbn: StatusBarNotification) {
        if (!Prefs.callAlerts) return
        main.removeCallbacks(pendingEnd) // a call notification is (still) here, so the call hasn't ended
        val n = sbn.notification
        val ex = n.extras
        val callType = ex.getInt("android.callType", 0) // 1 incoming, 2 ongoing, 3 screening
        val ringing = callType == 1 || (callType == 0 && hasCallButtons(n))
        active[sbn.key] = sbn // keep the latest version so Answer / Hang up use current actions

        if (!ringing) {
            // An incoming call we were ringing for has been picked up (here or on the phone).
            if (callKey != null && !inCall) {
                inCall = true
                callKey = sbn.key
                Bridge.sendEvent(Message.of("call", mapOf(
                    "state" to "active", "key" to sbn.key, "name" to callerName(ex),
                    "canHangUp" to (declineIntent(n) != null),
                )))
            }
            return
        }
        if (callKey == sbn.key && !inCall) return
        callKey = sbn.key
        inCall = false
        Bridge.sendEvent(Message.of("call", mapOf(
            "state" to "ringing", "key" to sbn.key, "name" to callerName(ex),
            "detail" to ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            "canDecline" to (declineIntent(n) != null),
            "canAnswer" to (canAcceptDirectly() || answerIntent(n) != null),
        ), appIcon(sbn.packageName)))
    }

    private fun callerName(ex: Bundle): String {
        val person: Person? = if (android.os.Build.VERSION.SDK_INT >= 33)
            ex.getParcelable("android.callPerson", Person::class.java)
        else @Suppress("DEPRECATION") ex.getParcelable("android.callPerson")
        return person?.name?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown caller"
    }

    /*
     * Call controls, most reliable first. Android's CallStyle keeps the phone app's own Answer / Decline /
     * Hang up intents in the notification extras, which works in every language. Older or custom phone
     * apps fall back to English button labels, then to button position (CallStyle puts Decline first).
     */
    private fun extraIntent(n: Notification, key: String): PendingIntent? =
        if (android.os.Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(key, PendingIntent::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelable(key)

    private fun isCallStyle(n: Notification) =
        n.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"

    private fun labelled(n: Notification, vararg words: String): PendingIntent? =
        n.actions?.firstOrNull { a -> val t = a.title?.toString().orEmpty().lowercase(); words.any { it in t } }?.actionIntent

    private fun declineIntent(n: Notification): PendingIntent? =
        extraIntent(n, "android.declineIntent") ?: extraIntent(n, "android.hangUpIntent")
            ?: labelled(n, "decline", "reject", "hang up", "end call")
            ?: n.actions?.takeIf { isCallStyle(n) && it.isNotEmpty() }?.first()?.actionIntent

    private fun answerIntent(n: Notification): PendingIntent? =
        extraIntent(n, "android.answerIntent")
            ?: labelled(n, "answer")
            ?: n.actions?.takeIf { isCallStyle(n) && it.size >= 2 }?.last()?.actionIntent

    private fun hasCallButtons(n: Notification) =
        n.extras.containsKey("android.answerIntent") ||
            n.actions.orEmpty().any { a -> a.title?.toString().orEmpty().lowercase().let { "answer" in it || "decline" in it } }

    private fun canAcceptDirectly() =
        checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

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

    /**
     * Picks up the ringing call on the phone. Uses Telecom directly when "Answer from Mac" is allowed
     * (most reliable); otherwise presses the phone app's own Answer button.
     */
    @SuppressLint("MissingPermission")
    fun answer(key: String): Boolean {
        // Only answer the call this phone reported. (Several phones can be linked to one Mac,
        // and acceptRingingCall() would otherwise pick up whatever is ringing here.)
        if (key != callKey || inCall) return false
        if (canAcceptDirectly()) {
            try {
                @Suppress("DEPRECATION")
                getSystemService(TelecomManager::class.java).acceptRingingCall()
                return true
            } catch (e: Exception) { Log.w(TAG, "acceptRingingCall", e) }
        }
        val intent = active[key]?.let { answerIntent(it.notification) } ?: return false
        return try {
            val opts = if (android.os.Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            else null
            intent.send(this, 0, null, null, null, null, opts)
            true
        } catch (e: Exception) { Log.w(TAG, "answer", e); false }
    }

    /** Declines a ringing call, or hangs up an answered one. */
    fun decline(key: String): Boolean {
        if (key != callKey) return false
        val sbn = active[key] ?: return false
        val intent = declineIntent(sbn.notification) ?: return false
        return try { intent.send(); true } catch (e: Exception) { Log.w(TAG, "decline", e); false }
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
