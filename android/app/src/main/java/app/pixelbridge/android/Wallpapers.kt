package app.pixelbridge.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** Desktop wallpapers sent by each paired Mac, shown on the laptop drawn on the home screen. */
object Wallpapers {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun file(context: Context, macId: String) = File(context.filesDir, "wallpaper-$macId.jpg")

    fun save(context: Context, macId: String, jpeg: ByteArray) {
        val f = file(context, macId)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(jpeg)
        tmp.renameTo(f)
        main.post { listeners.forEach { it(macId) } }
    }

    fun delete(context: Context, macId: String) { file(context, macId).delete() }

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
}
