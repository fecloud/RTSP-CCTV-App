package com.zektopic.cctvapp.webrtc

import com.zektopic.cctvapp.log.AppLog as Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import android.util.Base64
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

/**
 * Fetches a short-lived TURN credential on demand from a CrossDesk-style signaling server (default
 * `wss://api.crossdesk.cn:9099`): connect, send one `{"type":"login","user_id":"web",...}` frame,
 * and take the first message carrying a valid `turn` object (same shape the CrossDesk web client
 * consumes -- see `/tmp/cd_probe/get_turn.mjs`, the reference this mirrors).
 *
 * The credential expires (`expires_at`), so the result is cached and only re-fetched once it's
 * within [EXPIRY_MARGIN_SEC] of expiry; a fetch failure keeps serving a still-valid cached one
 * rather than dropping TURN entirely. Merged into the WebRTC preview's ICE server list by
 * [WebRtcIceServers] -- enabled via [com.zektopic.cctvapp.settings.ServiceSettings.webrtcDynamicTurnEnabled].
 *
 * Hand-rolled minimal WSS client (TLS socket + manual RFC 6455 handshake/framing) on purpose: the
 * app ships no WebSocket *client* library (NanoHTTPD is server-only), and pulling one in would mean
 * a new dependency this one short request doesn't justify -- see `CLAUDE.md` on dependency caution.
 * Blocking by design; only ever called from NanoHTTPD worker threads, never the main thread.
 */
object DynamicTurnProvider {

    private const val TAG = "DynamicTurn"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Re-fetch once the cached credential is within this many seconds of expiring. */
    private const val EXPIRY_MARGIN_SEC = 60L

    private val lock = ReentrantLock()
    private var cached: IceServerConfig.Entry? = null
    private var cachedExpiresAtSec: Long = 0
    private var cachedUrl: String? = null

    /**
     * Returns a TURN [IceServerConfig.Entry] fetched from [url], or null if it can't be obtained.
     * Cheap on the hot path: only actually hits the network when the cache is empty/stale or [url]
     * changed. Serialized so concurrent viewers trigger at most one in-flight fetch.
     */
    fun getTurnEntry(url: String): IceServerConfig.Entry? = lock.withLock {
        val nowSec = System.currentTimeMillis() / 1000
        val fresh = cached != null && cachedUrl == url && cachedExpiresAtSec - EXPIRY_MARGIN_SEC > nowSec
        if (fresh) return cached

        val fetched = runCatching { fetch(url) }.getOrElse { e ->
            Log.w(TAG, "TURN fetch failed: ${e.message}")
            // Fall back to a still-unexpired cached credential rather than dropping TURN entirely.
            return if (cached != null && cachedUrl == url && cachedExpiresAtSec > nowSec) cached else null
        }
        cached = fetched.first
        cachedExpiresAtSec = fetched.second
        cachedUrl = url
        return fetched.first
    }

    /** Connects, logs in, and returns the first valid `turn` credential as (entry, expiresAtSec). */
    private fun fetch(urlStr: String): Pair<IceServerConfig.Entry, Long> {
        val uri = URI(urlStr)
        require(uri.scheme == "wss") { "only wss:// is supported, got ${uri.scheme}" }
        val host = requireNotNull(uri.host) { "missing host in $urlStr" }
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.path?.takeIf { it.isNotEmpty() } ?: "/"

        val socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
            startHandshake()
        }
        socket.use {
            val out = socket.outputStream
            val inp = BufferedInputStream(socket.inputStream)

            performHandshake(out, inp, host, port, path)

            val login = JSONObject()
                .put("type", "login")
                .put("user_id", "web")
                .put("session_resume_version", 1)
                .toString()
            writeTextFrame(out, login)

            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val msg = readTextFrame(inp) ?: continue
                val turn = runCatching { JSONObject(msg).optJSONObject("turn") }.getOrNull() ?: continue
                parseTurn(turn)?.let { return it }
            }
            throw IllegalStateException("timed out waiting for a valid TURN credential")
        }
    }

    private fun performHandshake(out: OutputStream, inp: InputStream, host: String, port: Int, path: String) {
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHeaderLine(inp)
        if (!statusLine.contains(" 101 ")) throw IllegalStateException("handshake rejected: $statusLine")
        // Drain the rest of the response headers up to the blank line.
        while (readHeaderLine(inp).isNotEmpty()) { /* discard */ }
    }

    private fun readHeaderLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) throw EOFException("stream closed during handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Client-to-server frames must be masked (RFC 6455 §5.3). */
    private fun writeTextFrame(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val buf = ArrayList<Byte>(payload.size + 14)
        buf.add(0x81.toByte()) // FIN + text opcode
        val len = payload.size
        when {
            len < 126 -> buf.add((0x80 or len).toByte())
            len < 65_536 -> {
                buf.add((0x80 or 126).toByte())
                buf.add(((len ushr 8) and 0xff).toByte())
                buf.add((len and 0xff).toByte())
            }
            else -> {
                buf.add((0x80 or 127).toByte())
                for (i in 7 downTo 0) buf.add(((len ushr (8 * i)) and 0xff).toByte())
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        mask.forEach { buf.add(it) }
        for (i in payload.indices) buf.add((payload[i].toInt() xor mask[i % 4].toInt()).toByte())
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Returns the text payload, or null for a frame that isn't a final text message (ping/pong/binary). */
    private fun readTextFrame(inp: InputStream): String? {
        val b0 = readUByte(inp)
        val opcode = b0 and 0x0f
        val b1 = readUByte(inp)
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            len = (readUByte(inp).toLong() shl 8) or readUByte(inp).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readUByte(inp).toLong() }
        }
        val maskKey = if (masked) ByteArray(4) { readUByte(inp).toByte() } else null
        val payload = ByteArray(len.toInt())
        var read = 0
        while (read < payload.size) {
            val r = inp.read(payload, read, payload.size - read)
            if (r < 0) throw EOFException("stream closed mid-frame")
            read += r
        }
        maskKey?.let { for (i in payload.indices) payload[i] = (payload[i].toInt() xor it[i % 4].toInt()).toByte() }
        return when (opcode) {
            0x1 -> String(payload, Charsets.UTF_8)
            0x8 -> throw IllegalStateException("server closed the connection")
            else -> null // continuation/binary/ping/pong -- not what we're after
        }
    }

    private fun readUByte(inp: InputStream): Int {
        val b = inp.read()
        if (b < 0) throw EOFException("stream closed")
        return b and 0xff
    }

    /** Mirrors `parseTurnCredentials` in the reference script: all fields present, not expired. */
    private fun parseTurn(turn: JSONObject): Pair<IceServerConfig.Entry, Long>? {
        val host = turn.optString("host").takeIf { it.isNotEmpty() } ?: return null
        val port = turn.optInt("port", -1).takeIf { it > 0 } ?: return null
        val username = turn.optString("username").takeIf { it.isNotEmpty() } ?: return null
        val password = turn.optString("password").takeIf { it.isNotEmpty() } ?: return null
        if (!turn.has("expires_at")) return null
        val expiresAt = turn.optLong("expires_at", 0)
        if (expiresAt <= System.currentTimeMillis() / 1000) return null

        val h = if (host.contains(":")) "[$host]" else host // bracket IPv6 literals
        val entry = IceServerConfig.Entry(
            urls = listOf("turn:$h:$port?transport=udp", "turn:$h:$port?transport=tcp"),
            username = username,
            credential = password,
        )
        return entry to expiresAt
    }
}
