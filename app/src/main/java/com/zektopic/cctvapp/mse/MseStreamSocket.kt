package com.zektopic.cctvapp.mse

import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.service.PreviewBus
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One instance per browser tab -- created by [com.zektopic.cctvapp.web.WebServer.openWebSocket]
 * for every `/ws` connection. Send-only: on [onOpen] it sends the control message (fps, and audio
 * format if a microphone is present) and the SPS+PPS, then registers with [MseVideoBridge] for
 * the ongoing broadcast of raw NALs/AAC frames -- [enqueue] drops non-keyframe video NALs from
 * that broadcast until a fresh keyframe arrives, since this viewer's `h264-converter.js`
 * `VideoConverter` has no valid reference chain before then (and, unlike a real decoder, would
 * otherwise happily treat whatever NAL it sees first as if it were one). Audio frames are never
 * gated -- they have no such dependency chain. Every binary message is prefixed with a 1-byte tag
 * ([TAG_VIDEO]/[TAG_AUDIO]) so the client can tell them apart. See `dashboard.html` for the
 * client side.
 *
 * [authorized] is decided in `openWebSocket()` from the handshake headers -- NanoWSD always
 * completes the 101 handshake regardless, so an unauthorized connection is closed in [onOpen].
 */
class MseStreamSocket(
    handshake: IHTTPSession,
    private val authorized: Boolean,
) : NanoWSD.WebSocket(handshake) {

    private companion object {
        private const val TAG = "MseStreamSocket"

        /** Keeps the connection under NanoHTTPD's SOCKET_READ_TIMEOUT (5s) -- a ping's auto-`Pong` reply counts as inbound traffic. */
        private const val KEEPALIVE_INTERVAL_MS = 2000L

        /** Bounded so a slow/dead viewer can never make the shared camera callback thread ([enqueue]) block. */
        private const val PENDING_QUEUE_CAPACITY = 64

        private const val TAG_VIDEO: Byte = 0
        private const val TAG_AUDIO: Byte = 1

        /**
         * Shared by every connected viewer -- a keepalive tick is a cheap periodic check (a ping
         * plus a boolean read), not worth a dedicated sleeping OS thread per connection the way
         * [startSender]'s long-lived blocking send loop is. [Dispatchers.IO] rather than a small
         * fixed pool: a slow/dead viewer's blocking [ping] call only ties up one of its elastic
         * worker threads, so one stuck viewer doesn't stall everyone else's ticks the way it
         * could on a hard-capped pool.
         */
        private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Volatile private var bridge: MseVideoBridge? = null
    @Volatile private var keepAliveJob: Job? = null
    @Volatile private var senderThread: Thread? = null
    private val senderRunning = AtomicBoolean(false)
    private val pending = ArrayBlockingQueue<ByteArray>(PENDING_QUEUE_CAPACITY)

    /** [onClose] can race between the read loop and the sender thread both closing the same connection -- makes the second call a no-op. */
    private val closed = AtomicBoolean(false)

    /** Non-keyframe NALs are undecodable until a keyframe resyncs this viewer -- see [enqueue]. */
    @Volatile private var awaitingKeyframe = true

    /** Diagnostic only -- how many NALs got dropped during the current gated stretch, logged once it clears. */
    @Volatile private var droppedDeltaCount = 0

    /** Set by [enqueue] on a dropped frame; consumed by the keepalive thread so the encoder is never called from the camera callback thread. */
    @Volatile private var needsKeyframeRequest = false

    override fun onOpen() {
        if (!authorized) {
            closeQuietly("Unauthorized", NanoWSD.WebSocketFrame.CloseCode.PolicyViolation)
            return
        }
        val activeBridge = PreviewBus.mseBridge.get()
        val handshake = activeBridge?.snapshotForNewViewer()
        if (activeBridge == null || handshake == null) {
            closeQuietly("Camera not streaming", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        try {
            val controlMessage = JSONObject().put("fps", handshake.fps)
            if (handshake.audioConfig != null) {
                controlMessage.put("sampleRate", handshake.sampleRate)
                controlMessage.put("channelCount", handshake.channelCount)
                controlMessage.put("audioConfig", JSONArray(handshake.audioConfig.map { it.toInt() and 0xFF }))
            }
            send(controlMessage.toString())
            val spsAndPps = ByteArrayOutputStream(1 + handshake.sps.size + handshake.pps.size).apply {
                write(TAG_VIDEO.toInt())
                write(handshake.sps)
                write(handshake.pps)
            }.toByteArray()
            send(spsAndPps)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send SPS/PPS", e)
            closeQuietly("Init send failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
            return
        }
        bridge = activeBridge
        activeBridge.requestKeyframe()
        activeBridge.attach(this)
        startSender()
        startKeepAlive()
    }

    /** Called from [MseVideoBridge.getVideoData]/[MseVideoBridge.getAudioData] on the shared camera callback thread -- must never block. */
    fun enqueue(kind: MseVideoBridge.MseFrameKind, payload: ByteArray) {
        if (kind == MseVideoBridge.MseFrameKind.VIDEO_DELTA && awaitingKeyframe) {
            droppedDeltaCount++
            return
        }
        if (kind == MseVideoBridge.MseFrameKind.VIDEO_KEYFRAME) {
            if (droppedDeltaCount > 0) Log.d(TAG, "Resynced by fresh keyframe after dropping $droppedDeltaCount NAL(s)")
            droppedDeltaCount = 0
            awaitingKeyframe = false
        }
        val tag = if (kind == MseVideoBridge.MseFrameKind.AUDIO) TAG_AUDIO else TAG_VIDEO
        val tagged = ByteArray(1 + payload.size).also {
            it[0] = tag
            System.arraycopy(payload, 0, it, 1, payload.size)
        }
        if (!pending.offer(tagged)) {
            // A full queue means we're about to evict the oldest queued item. Only a video delta's
            // loss matters here -- it breaks the reference chain just as surely as the gate above,
            // so re-gate and ask for a fresh keyframe instead of waiting out the next one. Losing a
            // keyframe or an audio frame this way doesn't need the same response (a keyframe just
            // resynced the chain regardless of what got evicted before it -- confirmed on-device as
            // a real bug when this used to re-arm the gate on itself unconditionally, silently
            // dropping every frame after it forever; audio has no reference chain to break).
            pending.poll()
            pending.offer(tagged)
            if (kind == MseVideoBridge.MseFrameKind.VIDEO_DELTA) {
                if (!awaitingKeyframe) Log.w(TAG, "Send queue full, re-gating viewer and requesting a fresh keyframe")
                awaitingKeyframe = true
                needsKeyframeRequest = true
            }
        }
    }

    private fun startSender() {
        senderRunning.set(true)
        senderThread = Thread({
            try {
                while (senderRunning.get()) {
                    val data = pending.take()
                    try {
                        send(data)
                    } catch (e: IOException) {
                        Log.w(TAG, "Send failed, closing viewer socket", e)
                        closeQuietly("Send failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
                        return@Thread
                    }
                }
            } catch (e: InterruptedException) {
                // Normal shutdown, see onClose().
            }
        }, "MseStreamSocket-Sender").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSender() {
        senderRunning.set(false)
        senderThread?.interrupt()
        senderThread = null
        pending.clear()
    }

    private fun startKeepAlive() {
        keepAliveJob = keepAliveScope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (!isOpen) continue
                try {
                    if (needsKeyframeRequest) {
                        needsKeyframeRequest = false
                        bridge?.requestKeyframe()
                    }
                    ping(ByteArray(0))
                } catch (e: IOException) {
                    // Dead connection the read loop hasn't noticed yet -- close now instead of
                    // waiting out NanoHTTPD's read timeout.
                    Log.w(TAG, "Keepalive ping failed, closing viewer socket", e)
                    closeQuietly("Keepalive failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        stopKeepAlive()
        stopSender()
        bridge?.detach(this)
        bridge = null
    }

    /** This socket is send-only from the server's side -- the dashboard client never sends anything after connecting. */
    override fun onMessage(message: NanoWSD.WebSocketFrame) {}

    override fun onPong(pong: NanoWSD.WebSocketFrame) {}

    override fun onException(exception: IOException) {
        Log.e(TAG, "WebSocket error", exception)
    }

    /** Called by [MseVideoBridge] to force a reconnect (codec/resolution change) -- also used for the rejection paths in [onOpen]. */
    fun closeQuietly(
        reason: String,
        code: NanoWSD.WebSocketFrame.CloseCode = NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
    ) {
        runCatching { close(code, reason, false) }
    }
}
