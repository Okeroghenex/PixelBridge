package app.pixelbridge.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads SMS conversations for the Mac's Messages window.
 * Only SMS lives in the system store: RCS chats in Google Messages are private to that app,
 * so they reach the Mac as notifications (with reply) instead.
 */
object SmsStore {
    private val names = ConcurrentHashMap<String, String>()

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Latest message per conversation, newest first. */
    fun threads(ctx: Context, limit: Int = 80): JSONArray {
        val out = JSONArray()
        val seen = HashMap<Long, JSONObject>()
        val projection = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.DATE} DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < 6000) {
                val thread = c.getLong(0)
                val existing = seen[thread]
                val unread = c.getInt(5) == 0 && c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                if (existing != null) {
                    if (unread) existing.put("unread", existing.optInt("unread") + 1)
                    continue
                }
                if (seen.size >= limit) continue
                val address = c.getString(1).orEmpty()
                val o = JSONObject()
                    .put("threadId", thread)
                    .put("address", address)
                    .put("name", contactName(ctx, address))
                    .put("snippet", c.getString(2).orEmpty().take(140))
                    .put("date", c.getLong(3))
                    .put("fromMe", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    .put("unread", if (unread) 1 else 0)
                seen[thread] = o
                out.put(o)
            }
        }
        return out
    }

    /** Messages in one conversation, oldest first (last [limit]). */
    fun messages(ctx: Context, threadId: Long, limit: Int = 300): JSONArray {
        val rows = ArrayList<JSONObject>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                rows.add(JSONObject()
                    .put("id", c.getLong(0))
                    .put("body", c.getString(1).orEmpty())
                    .put("date", c.getLong(2))
                    .put("fromMe", c.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX))
            }
        }
        return JSONArray().also { arr -> rows.asReversed().forEach { arr.put(it) } }
    }

    private fun contactName(ctx: Context, address: String): String {
        if (address.isBlank()) return "Unknown"
        names[address]?.let { return it }
        var name = address
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
                }
            } catch (_: Exception) {}
        }
        names[address] = name
        return name
    }
}
