package app.pixelbridge.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A Mac this phone has been paired with. */
data class PairedMac(
    val id: String,
    val name: String?,
    val secret: ByteArray,
    val host: String?,
    val port: Int,
    val lastConnected: Long,
) {
    val displayName: String get() = name ?: "Mac"
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val lock = Any()

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("pixelbridge", Context.MODE_PRIVATE)
            migrateSingleMac()
        }
    }

    // region Paired Macs

    /** All paired Macs, most recently used first. */
    fun pairedMacs(): List<PairedMac> = synchronized(lock) { load() }.sortedByDescending { it.lastConnected }

    val isPaired: Boolean get() = pairedMacs().isNotEmpty()

    /** The Mac to try first: the one the user picked, else the most recently connected. */
    var preferredId: String?
        get() = sp.getString("preferredMac", null)
        set(v) = sp.edit().putString("preferredMac", v).apply()

    fun preferredMac(): PairedMac? {
        val all = pairedMacs()
        return all.firstOrNull { it.id == preferredId } ?: all.firstOrNull()
    }

    /** Adds a Mac, or updates it if it's already paired (same secret, or same name with a reset code). */
    fun addMac(info: PairingInfo): PairedMac = synchronized(lock) {
        val list = load().toMutableList()
        val existing = list.indexOfFirst { it.secret.contentEquals(info.secret) }
            .takeIf { it >= 0 } ?: list.indexOfFirst { info.name != null && it.name == info.name }
        val mac = if (existing >= 0) {
            list[existing].copy(secret = info.secret, name = info.name ?: list[existing].name,
                host = info.host ?: list[existing].host, port = info.port)
                .also { list[existing] = it }
        } else {
            PairedMac(UUID.randomUUID().toString(), info.name, info.secret, info.host, info.port, 0L).also { list.add(it) }
        }
        save(list)
        preferredId = mac.id
        mac
    }

    /** Called after a successful connection: remember where the Mac was and what it's called. */
    fun updateMac(id: String, name: String, host: String, port: Int) = synchronized(lock) {
        save(load().map { if (it.id == id) it.copy(name = name, host = host, port = port, lastConnected = System.currentTimeMillis()) else it })
    }

    fun removeMac(id: String) = synchronized(lock) {
        save(load().filterNot { it.id == id })
        if (preferredId == id) preferredId = null
    }

    private fun load(): List<PairedMac> {
        val arr = try { JSONArray(sp.getString("macs", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val secret = o.optString("secret").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: return@mapNotNull null
            PairedMac(o.optString("id"), o.optString("name").takeIf { it.isNotEmpty() && it != "null" }, secret,
                o.optString("host").takeIf { it.isNotEmpty() && it != "null" }, o.optInt("port", Proto.DEFAULT_PORT),
                o.optLong("lastConnected"))
        }
    }

    private fun save(list: List<PairedMac>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("secret", Base64.encodeToString(it.secret, Base64.NO_WRAP))
                .put("host", it.host).put("port", it.port).put("lastConnected", it.lastConnected))
        }
        sp.edit().putString("macs", arr.toString()).apply()
    }

    /** Versions before 1.2 stored a single Mac in flat keys; move it into the list once. */
    private fun migrateSingleMac() {
        val old = sp.getString("secret", null) ?: return
        if (load().isEmpty()) {
            val mac = PairedMac(UUID.randomUUID().toString(), sp.getString("macName", null),
                Base64.decode(old, Base64.NO_WRAP), sp.getString("host", null),
                sp.getInt("port", Proto.DEFAULT_PORT), System.currentTimeMillis())
            save(listOf(mac))
            preferredId = mac.id
        }
        sp.edit().remove("secret").remove("host").remove("port").remove("macName").apply()
    }

    // endregion

    /** Put text/images copied on the Mac straight onto this phone's clipboard. */
    var receiveClipboard: Boolean
        get() = sp.getBoolean("receiveClipboard", true)
        set(v) = sp.edit().putBoolean("receiveClipboard", v).apply()

    /** Send phone copies to the Mac automatically (needs the one-time setup from the Mac). */
    var autoSendClipboard: Boolean
        get() = sp.getBoolean("autoSendClipboard", true)
        set(v) = sp.edit().putBoolean("autoSendClipboard", v).apply()

    var mirrorNotifications: Boolean
        get() = sp.getBoolean("mirrorNotifications", true)
        set(v) = sp.edit().putBoolean("mirrorNotifications", v).apply()

    var callAlerts: Boolean
        get() = sp.getBoolean("callAlerts", true)
        set(v) = sp.edit().putBoolean("callAlerts", v).apply()
}
