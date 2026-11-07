package app.pixelbridge.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Recent transfers shown on the home screen ("Copied from Mac", "File sent to Mac", …). */
object History {
    enum class Kind { CLIP_IN, CLIP_OUT, IMAGE_IN, IMAGE_OUT, FILE_IN, FILE_OUT }

    data class Entry(val kind: Kind, val title: String, val time: Long, val uri: String? = null) {
        val incoming get() = kind == Kind.CLIP_IN || kind == Kind.IMAGE_IN || kind == Kind.FILE_IN
        val isFile get() = kind == Kind.FILE_IN || kind == Kind.FILE_OUT
    }

    private const val MAX = 30
    private lateinit var sp: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(List<Entry>) -> Unit>()
    @Volatile private var cache: List<Entry>? = null

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)
    }

    fun entries(): List<Entry> = cache ?: load().also { cache = it }

    @Synchronized
    fun add(kind: Kind, title: String, uri: String? = null) {
        if (!::sp.isInitialized) return
        val clean = title.replace(Regex("\\s+"), " ").trim().take(200)
        val list = (listOf(Entry(kind, clean, System.currentTimeMillis(), uri)) + entries()).take(MAX)
        save(list)
    }

    @Synchronized
    fun clear() = save(emptyList())

    fun addListener(l: (List<Entry>) -> Unit) { listeners.add(l); main.post { l(entries()) } }
    fun removeListener(l: (List<Entry>) -> Unit) { listeners.remove(l) }

    private fun save(list: List<Entry>) {
        cache = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("k", it.kind.name).put("t", it.title).put("time", it.time).put("u", it.uri))
        }
        sp.edit().putString("entries", arr.toString()).apply()
        main.post { listeners.forEach { l -> l(list) } }
    }

    private fun load(): List<Entry> {
        if (!::sp.isInitialized) return emptyList()
        val arr = try { JSONArray(sp.getString("entries", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            try {
                Entry(Kind.valueOf(o.getString("k")), o.optString("t"), o.optLong("time"),
                    o.optString("u").takeIf { s -> s.isNotEmpty() && s != "null" })
            } catch (_: Exception) { null }
        }
    }
}
