package com.zektopic.cctvapp.webrtc

import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.service.PreviewBus
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * One instance per browser tab -- created by [com.zektopic.cctvapp.web.WebServer.openWebSocket]
 * for every `/webrtc-ws` connection. Pure JSON signaling relay: the browser always initiates
 * with an SDP offer built from `recvonly` transceivers (see `webrtc-preview.js`); this replies
 * with an answer built from [WebRtcPreviewBridge]'s shared video/audio tracks, then relays ICE
 * candidates both ways. LAN-only by design (`PeerConnection.RTCConfiguration(emptyList())` in
 * [WebRtcPreviewBridge.attachViewer] -- no STUN/TURN), so a host candidate is always enough.
 *
 * [authorized] is decided in `openWebSocket()` from the handshake headers -- NanoWSD always
 * completes the 101 handshake regardless, so an unauthorized connection is closed in [onOpen],
 * exactly like [com.zektopic.cctvapp.mse.MseStreamSocket].
 */
class WebRtcSignalingSocket(
    handshake: IHTTPSession,
    private val authorized: Boolean,
) : NanoWSD.WebSocket(handshake) {

    private companion object {
        private const val TAG = "WebRtcSignalingSocket"

        /** Keeps the connection under NanoHTTPD's SOCKET_READ_TIMEOUT (5s) -- a ping's auto-`Pong` reply counts as inbound traffic, same as MseStreamSocket. Signaling itself can otherwise go quiet for a long time once the connection is established. */
        private const val KEEPALIVE_INTERVAL_MS = 2000L

        /** Shared by every connected viewer -- see [MseStreamSocket]'s `keepAliveScope` kdoc for why [Dispatchers.IO]. */
        private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Volatile private var bridge: WebRtcPreviewBridge? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var keepAliveJob: Job? = null

    /** [onClose] can race between the read loop and a WebRTC callback thread both closing the same connection -- makes the second call a no-op. */
    private val closed = AtomicBoolean(false)

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            sendJson(
                JSONObject()
                    .put("type", "candidate")
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                Log.w(TAG, "Peer connection failed, closing viewer socket")
                closeQuietly("Peer connection failed")
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    }

    override fun onOpen() {
        if (!authorized) {
            closeQuietly("Unauthorized", NanoWSD.WebSocketFrame.CloseCode.PolicyViolation)
            return
        }
        val activeBridge = PreviewBus.webRtcBridge.get()
        if (activeBridge == null) {
            closeQuietly("Camera not streaming", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        bridge = activeBridge
        startKeepAlive()
    }

    /** The client only ever sends `offer`/`candidate` messages -- see `webrtc-preview.js`. */
    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = message.textPayload ?: return
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            Log.e(TAG, "Malformed signaling message: $text", e)
            return
        }
        when (json.optString("type")) {
            "offer" -> handleOffer(json)
            "candidate" -> handleCandidate(json)
            else -> Log.w(TAG, "Unknown signaling message type: $text")
        }
    }

    private fun handleOffer(json: JSONObject) {
        val activeBridge = bridge ?: return
        val pc = activeBridge.attachViewer(observer)
        if (pc == null) {
            closeQuietly("Camera not streaming", NanoWSD.WebSocketFrame.CloseCode.GoingAway)
            return
        }
        peerConnection = pc
        val offer = SessionDescription(SessionDescription.Type.OFFER, json.optString("sdp"))
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = createAndSendAnswer(pc)
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "setRemoteDescription(offer) failed: $error")
                }
            },
            offer
        )
    }

    private fun createAndSendAnswer(pc: PeerConnection) {
        pc.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    checkNotNull(sdp)
                    pc.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                sendJson(JSONObject().put("type", "answer").put("sdp", sdp.description))
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "setLocalDescription(answer) failed: $error")
                            }
                        },
                        sdp
                    )
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "createAnswer failed: $error")
                }
            },
            org.webrtc.MediaConstraints()
        )
    }

    private fun handleCandidate(json: JSONObject) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(
            json.optString("sdpMid"),
            json.optInt("sdpMLineIndex"),
            json.optString("candidate")
        )
        pc.addIceCandidate(candidate)
    }

    private fun sendJson(json: JSONObject) {
        try {
            send(json.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send signaling message", e)
            closeQuietly("Send failed", NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure)
        }
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
        peerConnection?.let {
            it.close()
            it.dispose()
        }
        peerConnection = null
        bridge?.detachViewer()
        bridge = null
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {}

    override fun onException(exception: IOException) {
        Log.e(TAG, "WebSocket error", exception)
    }

    private fun closeQuietly(
        reason: String,
        code: NanoWSD.WebSocketFrame.CloseCode = NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
    ) {
        runCatching { close(code, reason, false) }
    }

    /** Trims [SdpObserver]'s four callbacks down to only the ones each call site needs. */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
