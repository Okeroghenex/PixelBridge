package app.pixelbridge.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("pixelbridge", Context.MODE_PRIVATE)
    }

    var secret: ByteArray?
        get() = sp.getString("secret", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) = sp.edit().putString("secret", v?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    /** Last address the Mac was reached at (fallback when Bonjour/NSD discovery doesn't find it). */
    var host: String?
        get() = sp.getString("host", null)
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", Proto.DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var macName: String?
        get() = sp.getString("macName", null)
        set(v) = sp.edit().putString("macName", v).apply()

    /** Put text/images copied on the Mac straight onto this phone's clipboard. */
    var receiveClipboard: Boolean
        get() = sp.getBoolean("receiveClipboard", true)
        set(v) = sp.edit().putBoolean("receiveClipboard", v).apply()

    fun pair(info: PairingInfo) {
        sp.edit()
            .putString("secret", Base64.encodeToString(info.secret, Base64.NO_WRAP))
            .putString("host", info.host)
            .putInt("port", info.port)
            .putString("macName", info.name)
            .apply()
    }

    fun unpair() {
        sp.edit().remove("secret").remove("host").remove("macName").apply()
    }
}
