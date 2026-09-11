package com.zektopic.cctvapp.webrtc

import com.zektopic.cctvapp.log.AppLog as Log
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import java.io.IOException
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * One instance per browser tab -- [com.zektopic.cctvapp.web.WebServer.openWebSocket] creates
 * one of these for every `/ws` connection. Each carries its own [PeerConnection], but every
 * instance adds the SAME shared [WebRtcVideoBridge.videoTrack]/[audioTrack]: one camera/mic
 * tap, any number of simultaneous viewers.
 *
 * Wire protocol is plain JSON, defined here and mirrored in `assets/web/dashboard.html`'s
 * WebRTC client script -- not a standard, just whatever both ends agree on:
 * `{"type":"offer","sdp":...}` / `{"type":"answer","sdp":...}` /
 * `{"type":"candidate","candidate":...,"sdpMid":...,"sdpMLineIndex":...}`.
 *
 * [authorized] is decided once, in `openWebSocket()`, from the handshake request's
 * Origin/Basic-Auth headers -- there is no way to reject the WS upgrade itself from here
 * (NanoWSD always completes the 101 handshake once [openWebSocket] returns a socket), so an
 * unauthorized connection is instead closed immediately in [onOpen].
 */
class WebRtcSignalingSocket(
    handshake: IHTTPSession,
    private val authorized: Boolean,
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "WebRtcSignaling"

        /**
         * Well under NanoHTTPD's own [fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT] (5s),
         * which applies to this socket too (`WebServer.start()` uses the default). Without
         * some inbound traffic within that window, `NanoWSD.WebSocket.readWebsocket()`'s
         * blocking read throws `SocketTimeoutException` and the whole connection -- along
         * with the `PeerConnection` it took several seconds of ICE/DTLS negotiation to
         * bring up -- gets torn down right as it finishes connecting. A `Pong` a browser's
         * WebSocket implementation sends back automatically (per RFC 6455, no client-side
         * code needed) counts as that inbound traffic.
         */
        private const val KEEPALIVE_INTERVAL_MS = 2000L
    }

    private var peerConnection: PeerConnection? = null
    private var bridge: WebRtcVideoBridge? = null
    private var keepAliveThread: Thread? = null

    override fun onOpen() {
        if (!authorized) {
            closeQuietly(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "Unauthorized")
            return
        }
        val activeBridge = WebRtcBus.bridge.get()
        if (activeBridge == null) {
            closeQuietly(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Camera not streaming")
            return
        }
        bridge = activeBridge
        peerConnection = createPeerConnection(activeBridge)
        startKeepAlive()
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
        }, "WebRtcSignaling-KeepAlive").apply {
            isDaemon = true
            start()
        }
    }

    private fun createPeerConnection(activeBridge: WebRtcVideoBridge): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val pc = activeBridge.factory().createPeerConnection(rtcConfig, PeerConnectionObserver())
        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned null")
            return null
        }
        // Same stream ID for both -- addTrack(track) alone (no stream ID list) generates a
        // fresh random one PER CALL, so the video and audio track would each show up in the
        // browser as their OWN single-track MediaStream and fire two separate `ontrack`
        // events. A naive `video.srcObject = event.streams[0]` (see dashboard.html) then
        // gets overwritten by whichever fires last, silently leaving <video> attached to an
        // audio-only stream -- confirmed on-device: audio and stats both worked fine while
        // the picture stayed black. Grouping both tracks under one ID keeps them in the same
        // MediaStream.
        val streamId = "cctv-stream"
        pc.addTrack(activeBridge.videoTrack, listOf(streamId))
        pc.addTrack(activeBridge.audioTrack, listOf(streamId))
        activeBridge.registerPeerConnection(pc)
        return pc
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        keepAliveThread?.interrupt()
        keepAliveThread = null
        val pc = peerConnection ?: return
        bridge?.unregisterPeerConnection(pc)
        runCatching { pc.close() }
        peerConnection = null
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val pc = peerConnection ?: return
        try {
            val json = JSONObject(message.textPayload)
            when (json.optString("type")) {
                "offer" -> handleOffer(pc, json)
                "candidate" -> pc.addIceCandidate(
                    IceCandidate(json.optString("sdpMid"), json.optInt("sdpMLineIndex"), json.getString("candidate"))
                )
                else -> Log.w(TAG, "Unknown signaling message type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle signaling message", e)
        }
    }

    private fun handleOffer(pc: PeerConnection, json: JSONObject) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
        pc.setRemoteDescription(SimpleSdpObserver(onSetSuccess = {
            pc.createAnswer(SimpleSdpObserver(onCreateSuccess = { answer ->
                pc.setLocalDescription(SimpleSdpObserver(), answer)
                sendJson(JSONObject().put("type", "answer").put("sdp", answer.description))
            }), MediaConstraints())
        }), offer)
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {}

    override fun onException(exception: IOException) {
        Log.e(TAG, "WebSocket error", exception)
    }

    private fun sendJson(json: JSONObject) {
        try {
            send(json.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send signaling message", e)
        }
    }

    private fun closeQuietly(code: NanoWSD.WebSocketFrame.CloseCode, reason: String) {
        runCatching { close(code, reason, false) }
    }

    private inner class PeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            sendJson(
                JSONObject()
                    .put("type", "candidate")
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<MediaStream>?) {}
    }
}

/** [onSetSuccess]/[onCreateSuccess] cover the two calls this file actually makes: `setRemoteDescription` (wants a completion signal) and `createAnswer` (wants the resulting SDP). */
private class SimpleSdpObserver(
    private val onSetSuccess: () -> Unit = {},
    private val onCreateSuccess: (SessionDescription) -> Unit = {},
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onCreateSuccess.invoke(sdp)
    override fun onSetSuccess() = onSetSuccess.invoke()
    override fun onCreateFailure(error: String?) {
        Log.e("WebRtcSignaling", "SDP create failed: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.e("WebRtcSignaling", "SDP set failed: $error")
    }
}
