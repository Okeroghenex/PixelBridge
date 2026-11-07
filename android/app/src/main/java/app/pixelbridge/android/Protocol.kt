package app.pixelbridge.android

import android.net.Uri
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire protocol shared with the Mac app. See PROTOCOL.md at the repo root. */
object Proto {
    const val SERVICE_TYPE = "_pixelbridge._tcp"
    const val DEFAULT_PORT = 47820
    const val VERSION = 1
    const val MAX_FRAME = 8 * 1024 * 1024
    const val CHUNK = 256 * 1024
    const val MAX_CLIP_IMAGE = 6 * 1024 * 1024
    val HKDF_INFO = "pixelbridge-v1".toByteArray()

    private val random = SecureRandom()
    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
}

/** JSON header (always has "t") + binary body. Encoded as [u32 BE header length][header JSON][body]. */
class Message(val header: JSONObject, val body: ByteArray = ByteArray(0)) {
    val type: String get() = header.optString("t")

    fun encode(): ByteArray {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + h.size + body.size).putInt(h.size).put(h).put(body).array()
    }

    companion object {
        fun of(type: String, fields: Map<String, Any?> = emptyMap(), body: ByteArray = ByteArray(0)): Message {
            val o = JSONObject()
            o.put("t", type)
            for ((k, v) in fields) o.put(k, v)
            return Message(o, body)
        }

        fun decode(data: ByteArray): Message {
            require(data.size >= 4) { "short message" }
            val hlen = ByteBuffer.wrap(data, 0, 4).int
            require(hlen >= 0 && hlen <= data.size - 4) { "bad header length" }
            val header = JSONObject(String(data, 4, hlen, Charsets.UTF_8))
            return Message(header, data.copyOfRange(4 + hlen, data.size))
        }
    }
}

/**
 * AES-256-GCM session. key = HKDF-SHA256(secret, salt = clientNonce||serverNonce, info = "pixelbridge-v1").
 * nonce = [u32 direction][u64 counter]; direction 0 = phone->Mac, 1 = Mac->phone.
 */
class SessionCrypto(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray, isServer: Boolean) {
    private val key = SecretKeySpec(hkdf(secret, clientNonce + serverNonce, Proto.HKDF_INFO, 32), "AES")
    private val sendDir = if (isServer) 1 else 0
    private val recvDir = if (isServer) 0 else 1
    private var sendCounter = 0L
    private var recvCounter = 0L

    private fun nonce(dir: Int, counter: Long) = ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()

    /** Returns ciphertext || tag. */
    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendDir, sendCounter)))
        sendCounter++
        return c.doFinal(plain)
    }

    /** Throws (AEADBadTagException) if the frame wasn't sealed with our key/counter. */
    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(recvDir, recvCounter)))
        val plain = c.doFinal(frame)
        recvCounter++
        return plain
    }

    companion object {
        /** RFC 5869 HKDF-SHA256 (single block is enough for 32 bytes). */
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1.toByte())
            return mac.doFinal().copyOf(length)
        }
    }
}

data class PairingInfo(val secret: ByteArray, val host: String?, val port: Int, val name: String?)

object Pairing {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** "ABCD-EFGH-…" code shown on the Mac. */
    fun secretFromCode(code: String): ByteArray? {
        val cleaned = code.uppercase().filter { it.isLetterOrDigit() }
        return base32Decode(cleaned)?.takeIf { it.size == 16 }
    }

    /** pixelbridge://pair?v=1&k=<base32>&p=<port>&n=<name>&h=<ip> */
    fun parseUri(text: String): PairingInfo? {
        val uri = Uri.parse(text.trim())
        if (uri.scheme != "pixelbridge" || uri.host != "pair") return null
        val secret = uri.getQueryParameter("k")?.let { secretFromCode(it) } ?: return null
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: Proto.DEFAULT_PORT
        return PairingInfo(secret, uri.getQueryParameter("h"), port, uri.getQueryParameter("n"))
    }
}
