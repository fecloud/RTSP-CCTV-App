package com.zektopic.cctvapp.mse

import com.zektopic.cctvapp.log.AppLog as Log
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * One instance per browser tab -- [com.zektopic.cctvapp.web.WebServer.openWebSocket] creates one
 * of these for every `/ws` connection, replacing the old WebRTC signaling socket. Unlike WebRTC
 * there's no negotiation: this socket is send-only from the server's side. On [onOpen] it sends,
 * in order: one text control message (`{"codecs":"video/mp4; codecs=\"...\""}`, the exact string
 * `MediaSource.addSourceBuffer()` expects), the current fmp4 init segment, and -- if one exists
 * yet -- the most recent video-keyframe fragment (so this viewer doesn't have to wait out the
 * next natural keyframe interval), then registers with [MseVideoBridge] to receive the ongoing
 * broadcast. See `dashboard.html`'s MSE client script for the other end of this wire format.
 *
 * [authorized] is decided once, in `openWebSocket()`, from the handshake request's
 * Origin/Basic-Auth headers -- there is no way to reject the WS upgrade itself from here
 * (NanoWSD always completes the 101 handshake once [openWebSocket] returns a socket), so an
 * unauthorized connection is instead closed immediately in [onOpen].
 */
class MseStreamSocket(
    handshake: IHTTPSession,
    private val authorized: Boolean,
) : NanoWSD.WebSocket(handshake) {

    private companion object {
        private const val TAG = "MseStreamSocket"

        /**
         * This socket never receives anything meaningful from the client (it's send-only), so
         * unlike the old WebRTC signaling socket there's no inbound offer/candidate traffic to
         * rely on either -- a keepalive ping is the only thing keeping this under NanoHTTPD's
         * [fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT] (5s; `WebServer.start()` uses the
         * default). A browser's WebSocket implementation replies to a ping with a `Pong`
         * automatically (RFC 6455, no client-side code needed), which counts as inbound traffic.
         */
        private const val KEEPALIVE_INTERVAL_MS = 2000L

        /** Bounded so a slow/dead viewer can never make the shared camera callback thread ([enqueue]) block. */
        private const val PENDING_QUEUE_CAPACITY = 64
    }

    private var bridge: MseVideoBridge? = null
    private var keepAliveThread: Thread? = null
    private var senderThread: Thread? = null
    private val senderRunning = AtomicBoolean(false)
    private val pending = ArrayBlockingQueue<ByteArray>(PENDING_QUEUE_CAPACITY)

    override fun onOpen() {
        if (!authorized) {
            closeQuietly("Unauthorized", NanoWSD.WebSocketFrame.CloseCode.PolicyViolation)
            return
        }
        val activeBridge = MseBus.bridge.get()
        val init = activeBridge?.initSegment()
        val codecs = activeBridge?.codecsString()
        if (activeBridge == null || init == null || codecs == null) {
            closeQuietly("Camera not streaming", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        try {
            send(JSONObject().put("codecs", codecs).toString())
            send(init)
            activeBridge.lastKeyframeFragment()?.let { send(it) }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send init segment", e)
            closeQuietly("Init send failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
            return
        }
        bridge = activeBridge
        activeBridge.requestKeyframe()
        activeBridge.attach(this)
        startSender()
        startKeepAlive()
    }

    /** Called from [MseVideoBridge.broadcast] on the shared camera callback thread -- must never block. */
    fun enqueue(fragment: ByteArray) {
        if (!pending.offer(fragment)) {
            pending.poll()
            pending.offer(fragment)
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
                    if (isOpen) ping(ByteArray(0))
                }
            } catch (e: InterruptedException) {
                // Normal shutdown, see onClose().
            } catch (e: IOException) {
                Log.e(TAG, "Keepalive ping failed", e)
            }
        }, "MseStreamSocket-KeepAlive").apply {
            isDaemon = true
            start()
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
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
