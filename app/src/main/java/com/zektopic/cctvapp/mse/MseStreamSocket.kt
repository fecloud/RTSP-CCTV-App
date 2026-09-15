package com.zektopic.cctvapp.mse

import com.zektopic.cctvapp.log.AppLog as Log
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * One instance per browser tab -- created by [com.zektopic.cctvapp.web.WebServer.openWebSocket]
 * for every `/ws` connection. Send-only: on [onOpen] it sends the `fps` control message and the
 * SPS+PPS, then registers with [MseVideoBridge] for the ongoing broadcast of raw NALs -- [enqueue]
 * drops non-keyframe NALs from that broadcast until a fresh keyframe arrives, since this viewer's
 * `h264-converter.js` `VideoConverter` has no valid reference chain before then (and, unlike a
 * real decoder, would otherwise happily treat whatever NAL it sees first as if it were one). See
 * `dashboard.html` for the client side.
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
    }

    @Volatile private var bridge: MseVideoBridge? = null
    @Volatile private var keepAliveThread: Thread? = null
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
        val activeBridge = MseBus.bridge.get()
        val handshake = activeBridge?.snapshotForNewViewer()
        if (activeBridge == null || handshake == null) {
            closeQuietly("Camera not streaming", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        try {
            send(JSONObject().put("fps", handshake.fps).toString())
            val spsAndPps = ByteArrayOutputStream(handshake.sps.size + handshake.pps.size).apply {
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

    /** Called from [MseVideoBridge.getVideoData] on the shared camera callback thread -- must never block. */
    fun enqueue(nal: ByteArray, isKeyframe: Boolean) {
        if (!isKeyframe && awaitingKeyframe) {
            droppedDeltaCount++
            return
        }
        if (isKeyframe) {
            if (droppedDeltaCount > 0) Log.d(TAG, "Resynced by fresh keyframe after dropping $droppedDeltaCount NAL(s)")
            droppedDeltaCount = 0
            awaitingKeyframe = false
        }
        if (!pending.offer(nal)) {
            // A full queue means we're about to evict the oldest queued NAL, which could be a
            // non-keyframe one -- its loss breaks the reference chain just as surely as the gate
            // above, so re-gate and ask for a fresh keyframe instead of waiting out the next one.
            // Unless this NAL IS that fresh keyframe: it just resynced the chain regardless of
            // whatever got evicted before it, so don't immediately undo the line above --
            // confirmed on-device as a real bug (a keyframe hitting a full queue re-armed the
            // gate on itself, silently dropping every frame after it forever).
            pending.poll()
            pending.offer(nal)
            if (!isKeyframe) {
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
        keepAliveThread = Thread({
            try {
                while (isOpen) {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                    if (!isOpen) break
                    if (needsKeyframeRequest) {
                        needsKeyframeRequest = false
                        bridge?.requestKeyframe()
                    }
                    ping(ByteArray(0))
                }
            } catch (e: InterruptedException) {
                // Normal shutdown, see onClose().
            } catch (e: IOException) {
                // Dead connection the read loop hasn't noticed yet -- close now instead of
                // waiting out NanoHTTPD's read timeout.
                Log.w(TAG, "Keepalive ping failed, closing viewer socket", e)
                closeQuietly("Keepalive failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
            }
        }, "MseStreamSocket-KeepAlive").apply {
            isDaemon = true
            start()
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        keepAliveThread?.interrupt()
        keepAliveThread = null
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
