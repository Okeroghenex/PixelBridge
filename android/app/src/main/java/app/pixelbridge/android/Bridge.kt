package app.pixelbridge.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

sealed class BridgeState {
    object NotPaired : BridgeState()
    object Searching : BridgeState()
    data class Connecting(val target: String) : BridgeState()
    data class Connected(val macName: String) : BridgeState()
    data class Error(val message: String) : BridgeState()
}

/** Process-wide connection to the paired Mac. Kept alive by [BridgeService]. */
object Bridge {
    private const val TAG = "PixelBridge"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(BridgeState) -> Unit>()
    private val stateLock = Object()
    private val wakeLock = Object()
    private val sendExec = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    @Volatile var state: BridgeState = BridgeState.Searching
        private set
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var crypto: SessionCrypto? = null
    private var worker: Thread? = null
    private var pinger: ScheduledFuture<*>? = null
    private var discovery: Discovery? = null
    private val incoming = HashMap<String, IncomingFile>()

    /** Called from the UI for progress/toasts. */
    var onEvent: ((String) -> Unit)? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Prefs.init(app)
        History.init(app)
    }

    @Synchronized
    fun start(context: Context) {
        init(context)
        if (running) return
        running = true
        discovery = Discovery(app) { wake() }
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wake() }
                override fun onLost(network: Network) { dropConnection() }
            })
        worker = Thread(::runLoop, "pixelbridge-conn").also { it.isDaemon = true; it.start() }
    }

    fun addListener(l: (BridgeState) -> Unit) { listeners.add(l); main.post { l(state) } }
    fun removeListener(l: (BridgeState) -> Unit) { listeners.remove(l) }

    /** Forget the current socket and try again right away (e.g. after re-pairing). */
    fun reconnect() { dropConnection(); wake() }

    // region Sending

    fun sendText(text: String, onResult: ((Boolean) -> Unit)? = null) =
        enqueue(onResult) {
            sendClip(Message.of("clip_text", body = text.toByteArray(Charsets.UTF_8)))
            History.add(History.Kind.CLIP_OUT, text)
        }

    fun sendClipImage(bytes: ByteArray, mime: String, onResult: ((Boolean) -> Unit)? = null) =
        enqueue(onResult) {
            sendClip(Message.of("clip_image", mapOf("mime" to mime), bytes))
            History.add(History.Kind.IMAGE_OUT, "Image")
        }

    /** Locks the Mac's screen, like Link to Windows' "Lock PC". */
    fun lockMac(onResult: ((Boolean) -> Unit)? = null) = enqueue(onResult) { write(Message.of("lock_mac")) }

    /** Streams content:// files to the Mac's Downloads folder. */
    fun sendUris(uris: List<Uri>, onResult: ((Boolean) -> Unit)? = null) = enqueue(onResult) {
        val resolver = app.contentResolver
        for ((index, uri) in uris.withIndex()) {
            var name = "file"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val id = UUID.randomUUID().toString()
            val input = resolver.openInputStream(uri) ?: throw IOException("Can't open $uri")
            input.use { stream ->
                write(Message.of("file_start", mapOf("id" to id, "name" to name, "size" to size, "mime" to mime)))
                val buf = ByteArray(Proto.CHUNK)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = stream.readNBytesCompat(buf)
                    if (n <= 0) break
                    write(Message.of("file_chunk", mapOf("id" to id), buf.copyOf(n)))
                    sent += n
                    if (size > 0) {
                        val pct = (sent * 100 / size).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            BridgeService.showProgress(app, name, index + 1, uris.size, pct)
                        }
                    }
                }
                write(Message.of("file_end", mapOf("id" to id)))
                History.add(History.Kind.FILE_OUT, name, uri.toString())
            }
        }
        BridgeService.clearProgress(app)
    }

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Runs [block] on the send thread once connected (waits up to 15 s for a connection). */
    private fun enqueue(onResult: ((Boolean) -> Unit)?, block: () -> Unit) {
        start(app)
        sendExec.execute {
            val ok = try {
                if (!awaitConnected(15_000)) throw IOException("Not connected to your Mac")
                block()
                true
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                BridgeService.clearProgress(app)
                false
            }
            onResult?.let { cb -> main.post { cb(ok) } }
        }
    }

    // Clips must not vanish into a connection that died without us noticing (e.g. the Mac app restarted):
    // after a clip we send a ping, and the Mac answers pings in order, so its pong proves the clip arrived.
    // No pong within 4 s → reconnect and send the clip again.
    @Volatile private var pendingClip: Message? = null
    @Volatile private var pendingClipAt = 0L
    @Volatile private var pendingClipPing = 0
    private val pingsSent = java.util.concurrent.atomic.AtomicInteger()
    private val pongsReceived = java.util.concurrent.atomic.AtomicInteger()

    private fun sendPing(): Int {
        val n = pingsSent.incrementAndGet()
        write(Message.of("ping"))
        return n
    }

    private fun sendClip(m: Message) {
        pendingClip = m
        pendingClipAt = System.currentTimeMillis()
        write(m)
        val ping = sendPing()
        pendingClipPing = ping
        timer.schedule({
            if (pendingClip === m && pongsReceived.get() < ping) {
                Log.i(TAG, "Mac didn't confirm the clip; reconnecting")
                reconnect()
            }
        }, 4, TimeUnit.SECONDS)
    }

    private fun onPong() {
        if (pongsReceived.incrementAndGet() >= pendingClipPing) pendingClip = null
    }

    /** After (re)connecting: deliver a clip the last connection lost, if it's still recent. */
    private fun resendPendingClip() {
        val m = pendingClip ?: return
        if (System.currentTimeMillis() - pendingClipAt > 120_000) { pendingClip = null; return }
        sendExec.execute { try { if (pendingClip === m) sendClip(m) } catch (_: Exception) {} }
    }

    private fun write(m: Message) {
        val c = crypto ?: throw IOException("Not connected")
        val o = out ?: throw IOException("Not connected")
        synchronized(o) {
            try {
                writeFrame(o, c.seal(m.encode()))
            } catch (e: IOException) {
                dropConnection(); throw e
            }
        }
    }

    // endregion

    // region Connection loop

    private fun runLoop() {
        var backoff = 1000L
        while (running) {
            val macs = orderedMacs()
            if (macs.isEmpty()) {
                setState(BridgeState.NotPaired)
                waitWake(30_000)
                continue
            }
            val found = discovery?.services ?: emptyMap()
            val attempts = macs.flatMap { mac -> targetsFor(mac, found).map { mac to it } }
            if (attempts.isEmpty()) {
                setState(BridgeState.Searching)
                discovery?.start()
                waitWake(5_000)
                continue
            }
            var authFailures = 0
            for ((mac, target) in attempts) {
                val (host, port) = target
                val started = System.currentTimeMillis()
                try {
                    connectAndServe(host, port, mac)
                } catch (e: AEADBadTagException) {
                    authFailures++
                    Log.w(TAG, "auth failed for ${mac.displayName} at $host:$port")
                } catch (e: Exception) {
                    Log.i(TAG, "connection to ${mac.displayName} at $host:$port ended: $e")
                }
                currentMacId = null
                if (!running) return
                // Only a connection that actually lasted resets the backoff; quick drops slow us down.
                if (state is BridgeState.Connected && System.currentTimeMillis() - started > 10_000) {
                    backoff = 1000L
                    break
                }
            }
            setState(if (authFailures == attempts.size) BridgeState.Error("Pairing code rejected — pair again")
                     else BridgeState.Searching)
            discovery?.start()
            waitWake(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }

    /** The Mac you picked first, then the others, most recently used first. */
    private fun orderedMacs(): List<PairedMac> {
        val all = Prefs.pairedMacs()
        val preferred = Prefs.preferredId
        return all.filter { it.id == preferred } + all.filter { it.id != preferred }
    }

    /** Where to find a Mac: its Bonjour address, then the last address it was reached at. */
    private fun targetsFor(mac: PairedMac, found: Map<String, Pair<String, Int>>): List<Pair<String, Int>> {
        val list = LinkedHashSet<Pair<String, Int>>()
        mac.name?.let { found[it] }?.let { list.add(it) }
        mac.host?.let { list.add(it to mac.port) }
        if (mac.name == null) found.values.forEach { list.add(it) } // name not learned yet: auth decides
        return list.toList()
    }

    /** The Mac we're connected to right now, if any. */
    @Volatile var currentMacId: String? = null
        private set

    /** Switch to another paired Mac (drops the current connection; the loop then tries it first). */
    fun switchTo(id: String) {
        Prefs.preferredId = id
        if (currentMacId != id) reconnect()
    }

    /** Forget a Mac; disconnects first if it's the one we're connected to. */
    fun forget(id: String) {
        Prefs.removeMac(id)
        Wallpapers.delete(app, id)
        if (currentMacId == id) reconnect() else wake()
    }

    private fun connectAndServe(host: String, port: Int, mac: PairedMac) {
        val secret = mac.secret
        setState(BridgeState.Connecting("$host:$port"))
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))

            // Plaintext handshake: exchange random nonces, then everything is encrypted.
            val clientNonce = Proto.randomBytes(16)
            writeFrame(output, JSONObject().put("v", Proto.VERSION)
                .put("nonce", Base64.encodeToString(clientNonce, Base64.NO_WRAP)).toString().toByteArray())
            val reply = JSONObject(String(readFrame(input), Charsets.UTF_8))
            val serverNonce = Base64.decode(reply.getString("nonce"), Base64.DEFAULT)
            val c = SessionCrypto(secret, clientNonce, serverNonce, isServer = false)
            writeFrame(output, c.seal(Message.of("hello", mapOf("name" to deviceName(), "platform" to "android")).encode()))
            val hello = Message.decode(c.open(readFrame(input))) // AEADBadTagException = wrong key
            if (hello.type != "hello") throw IOException("unexpected ${hello.type}")
            val macName = hello.header.optString("name", "Mac")

            Prefs.updateMac(mac.id, macName, host, port)
            currentMacId = mac.id
            crypto = c
            out = output
            s.soTimeout = 60_000 // Mac answers our 20 s pings, so silence means the link is dead
            pingsSent.set(0)
            pongsReceived.set(0)
            pendingClipPing = 0
            setState(BridgeState.Connected(macName))
            resendPendingClip()
            if (Prefs.autoSendClipboard && AutoClipboard.isAvailable(app) && AutoClipboard.needsTap) {
                BridgeService.notifyAutoClipNeedsTap(app) // log access must be re-approved after a restart
            }
            discovery?.stop()
            pinger = timer.scheduleWithFixedDelay({
                sendExec.execute { try { sendPing() } catch (_: Exception) {} }
            }, 20, 20, TimeUnit.SECONDS)

            while (running) {
                handle(Message.decode(c.open(readFrame(input))))
            }
        } finally {
            pinger?.cancel(false)
            crypto = null
            out = null
            try { s.close() } catch (_: IOException) {}
            synchronized(incoming) { incoming.values.forEach { it.abort() }; incoming.clear() }
        }
    }

    private fun dropConnection() {
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        if (len <= 0 || len > Proto.MAX_FRAME) throw IOException("bad frame length $len")
        return ByteArray(len).also { input.readFully(it) }
    }

    private fun writeFrame(o: DataOutputStream, payload: ByteArray) {
        o.writeInt(payload.size)
        o.write(payload)
        o.flush()
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL

    // endregion

    // region Incoming

    private fun handle(m: Message) {
        when (m.type) {
            "pong" -> onPong()
            "clip_text" -> if (Prefs.receiveClipboard) {
                val text = String(m.body, Charsets.UTF_8)
                History.add(History.Kind.CLIP_IN, text)
                AutoClipboard.lastSignature = "txt:${text.hashCode()}:${text.length}"
                main.post { setClip(ClipData.newPlainText("From Mac", text)) }
            }
            "clip_image" -> if (Prefs.receiveClipboard) {
                putImageOnClipboard(m.body)
                History.add(History.Kind.IMAGE_IN, "Image")
            }
            "file_start" -> {
                val id = m.header.optString("id")
                val f = IncomingFile.create(app, m.header.optString("name", "file"),
                    m.header.optString("mime", "application/octet-stream"), m.header.optLong("size", -1))
                if (f != null) synchronized(incoming) { incoming[id] = f }
            }
            "file_chunk" -> synchronized(incoming) { incoming[m.header.optString("id")] }?.write(m.body)
            "file_end" -> {
                val f = synchronized(incoming) { incoming.remove(m.header.optString("id")) } ?: return
                if (f.finish()) {
                    History.add(History.Kind.FILE_IN, f.name, f.uri.toString())
                    BridgeService.notifyFileReceived(app, f.name, f.uri, f.mime)
                }
            }
            "wallpaper" -> currentMacId?.let { Wallpapers.save(app, it, m.body) }
            "notif_reply" -> {
                val ok = NotificationMirror.instance?.reply(m.header.optString("key"), String(m.body, Charsets.UTF_8)) ?: false
                sendEvent(Message.of("notif_reply_result", mapOf("key" to m.header.optString("key"), "ok" to ok)))
            }
            "notif_dismiss" -> NotificationMirror.instance?.dismiss(m.header.optString("key"))
            "call_action" -> {
                val key = m.header.optString("key")
                val action = m.header.optString("action")
                val mirror = NotificationMirror.instance
                val ok = when (action) {
                    "answer" -> mirror?.answer(key)
                    "decline", "hangup" -> mirror?.decline(key)
                    else -> false
                } ?: false
                sendEvent(Message.of("call_action_result", mapOf("key" to key, "action" to action, "ok" to ok)))
            }
            "sms_threads_req" -> sendExec.execute {
                val reply = if (Edition.isLite) Message.of("sms_threads", mapOf("error" to "unsupported"))
                else if (!SmsStore.hasPermission(app)) Message.of("sms_threads", mapOf("error" to "permission"))
                else Message.of("sms_threads", body = SmsStore.threads(app).toString().toByteArray())
                try { write(reply) } catch (_: Exception) {}
            }
            "sms_messages_req" -> sendExec.execute {
                val tid = m.header.optLong("threadId")
                val reply = if (!SmsStore.hasPermission(app)) Message.of("sms_messages", mapOf("threadId" to tid, "error" to "permission"))
                else Message.of("sms_messages", mapOf("threadId" to tid), SmsStore.messages(app, tid).toString().toByteArray())
                try { write(reply) } catch (_: Exception) {}
            }
        }
    }

    /** Fire-and-forget event (notifications, calls): dropped if the Mac isn't connected right now. */
    fun sendEvent(m: Message) {
        if (!::app.isInitialized || state !is BridgeState.Connected) return
        sendExec.execute { try { write(m) } catch (_: Exception) {} }
    }

    private fun putImageOnClipboard(bytes: ByteArray) {
        val dir = File(app.cacheDir, "clip").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val file = File(dir, "mac-clip-${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        main.post { setClip(ClipData.newUri(app.contentResolver, "Image from Mac", uri)) }
    }

    /** Android's clipboard rejects (and crashes the app on) clips over about 1 MB, so catch that. */
    private fun setClip(clip: ClipData) {
        try {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (e: RuntimeException) {
            Log.w(TAG, "setPrimaryClip", e)
            Toast.makeText(app, "Mac copy too large for the phone clipboard", Toast.LENGTH_LONG).show()
        }
    }

    // endregion

    // region State helpers

    private fun setState(s: BridgeState) {
        synchronized(stateLock) {
            if (state == s) return
            state = s
            stateLock.notifyAll()
        }
        main.post { listeners.forEach { it(s) } }
    }

    private fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(stateLock) {
            while (state !is BridgeState.Connected) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                stateLock.wait(left)
            }
        }
        return true
    }

    private fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }
    private fun waitWake(ms: Long) = synchronized(wakeLock) { wakeLock.wait(ms) }

    // endregion
}

/** A file being received into Downloads/PixelBridge via MediaStore (no storage permission needed). */
class IncomingFile private constructor(
    private val context: Context, val name: String, val mime: String, val uri: Uri,
    private val stream: OutputStream, private val expected: Long,
) {
    private var written = 0L

    fun write(bytes: ByteArray) {
        stream.write(bytes)
        written += bytes.size
    }

    fun finish(): Boolean {
        stream.close()
        if (expected >= 0 && written != expected) {
            context.contentResolver.delete(uri, null, null)
            return false
        }
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, v, null, null)
        return true
    }

    fun abort() {
        try { stream.close() } catch (_: IOException) {}
        context.contentResolver.delete(uri, null, null)
    }

    companion object {
        fun create(context: Context, name: String, mime: String, size: Long): IncomingFile? {
            val safe = name.substringAfterLast('/').ifBlank { "file" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Link to Mac")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: return null
            return IncomingFile(context, safe, mime, uri, stream.buffered(256 * 1024), size)
        }
    }
}
