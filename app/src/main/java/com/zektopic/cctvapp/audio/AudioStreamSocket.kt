package com.zektopic.cctvapp.audio

import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.service.PreviewBus
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
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
import org.json.JSONObject

/**
 * One instance per browser tab -- created by [com.zektopic.cctvapp.web.WebServer.openWebSocket]
 * for every `/audio-ws` connection. Send-only, and much simpler than
 * [com.zektopic.cctvapp.mse.MseStreamSocket]: audio has no keyframe/GOP dependency, so there's no
 * gating, no dropped-frame bookkeeping, and no tag byte (every binary message is one raw 16-bit
 * PCM chunk, nothing else -- no AAC, no AudioSpecificConfig, see [AudioStreamBridge]'s kdoc for
 * why). On [onOpen] it sends one JSON control message (sample rate, channel count) built from
 * [AudioStreamBridge.snapshotForNewViewer], then registers with [AudioStreamBridge] for the
 * ongoing broadcast. See `audio-preview.js` for the client side.
 *
 * [authorized] is decided in `openWebSocket()` from the handshake headers -- NanoWSD always
 * completes the 101 handshake regardless, so an unauthorized connection is closed in [onOpen],
 * exactly like [com.zektopic.cctvapp.mse.MseStreamSocket].
 */
class AudioStreamSocket(
    handshake: IHTTPSession,
    private val authorized: Boolean,
) : NanoWSD.WebSocket(handshake) {

    private companion object {
        private const val TAG = "AudioStreamSocket"

        /** Keeps the connection under NanoHTTPD's SOCKET_READ_TIMEOUT (5s) -- a ping's auto-`Pong` reply counts as inbound traffic, same as MseStreamSocket. */
        private const val KEEPALIVE_INTERVAL_MS = 2000L

        /** Bounded so a slow/dead viewer can never make the shared camera callback thread ([enqueue]) block. */
        private const val PENDING_QUEUE_CAPACITY = 32

        /** Shared by every connected viewer -- see [MseStreamSocket]'s `keepAliveScope` kdoc for why [Dispatchers.IO]. */
        private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Volatile private var bridge: AudioStreamBridge? = null
    @Volatile private var keepAliveJob: Job? = null
    @Volatile private var senderThread: Thread? = null
    private val senderRunning = AtomicBoolean(false)
    private val pending = ArrayBlockingQueue<ByteArray>(PENDING_QUEUE_CAPACITY)

    /** [onClose] can race between the read loop and the sender thread both closing the same connection -- makes the second call a no-op. */
    private val closed = AtomicBoolean(false)

    override fun onOpen() {
        if (!authorized) {
            closeQuietly("Unauthorized", NanoWSD.WebSocketFrame.CloseCode.PolicyViolation)
            return
        }
        val activeBridge = PreviewBus.audioBridge.get()
        val handshake = activeBridge?.snapshotForNewViewer()
        if (activeBridge == null || handshake == null) {
            closeQuietly("No audio available", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        try {
            send(
                JSONObject()
                    .put("sampleRate", handshake.sampleRate)
                    .put("channelCount", handshake.channelCount)
                    .toString()
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send audio config", e)
            closeQuietly("Init send failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
            return
        }
        bridge = activeBridge
        activeBridge.attach(this)
        startSender()
        startKeepAlive()
    }

    /** Called from [AudioStreamBridge.inputPCMData] on the shared camera callback thread -- must never block. */
    fun enqueue(frame: ByteArray) {
        if (!pending.offer(frame)) {
            // Queue full means a slow viewer -- drop the oldest PCM chunk rather than block the
            // shared camera callback thread. Unlike a video delta frame, one dropped chunk has no
            // reference-chain fallout; playback just resumes on the next one.
            pending.poll()
            pending.offer(frame)
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
        }, "AudioStreamSocket-Sender").apply {
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
                    ping(ByteArray(0))
                } catch (e: IOException) {
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

    /** Called by [AudioStreamBridge.release] on service shutdown -- also used for the rejection paths in [onOpen]. */
    fun closeQuietly(
        reason: String,
        code: NanoWSD.WebSocketFrame.CloseCode = NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
    ) {
        runCatching { close(code, reason, false) }
    }
}
