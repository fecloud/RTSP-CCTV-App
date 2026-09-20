package com.zektopic.cctvapp.webrtc

import android.content.Context
import com.zektopic.cctvapp.streaming.SharedCameraStream
import org.webrtc.PeerConnection

/**
 * Shared WebRTC video source feeding every dashboard viewer that's opted into the WebRTC preview
 * (see `dashboard.js`'s preview-type chip) off one [SharedCameraStream]. Unlike
 * [com.zektopic.cctvapp.mse.MseVideoBridge] (which forwards the already-hardware-encoded H264
 * bitstream), [WebRtcEngine] taps the camera's raw GL composite via
 * [com.pedro.library.view.GlStreamInterface.addMultiPreviewSurface] -- a second render target off
 * the same EGL context RootEncoder already draws the encoder's frame into, so no second `Camera2`
 * session is needed, and it works regardless of which codec RTSP currently has selected (WebRTC
 * re-encodes the raw frames itself).
 *
 * Video-only -- audio is a separate, shared preview independent of which video transport is
 * active, see `com.zektopic.cctvapp.audio.AudioStreamBridge`'s kdoc for why (a real WebRTC audio
 * track would need a second concurrent `AudioRecord` this app's target hardware's
 * `AudioPolicyManager` outright refuses to grant while `SharedCameraStream`'s own
 * `MicrophoneSource` is already recording).
 *
 * This class does viewer bookkeeping (reference counting, [PeerConnection] creation per viewer)
 * -- the actual engine resources (factory, video track, GL preview surface) live in
 * [WebRtcEngine], created/torn down here as the viewer count goes 0->1/1->0.
 */
class WebRtcPreviewBridge(private val context: Context) {

    private companion object {
        private const val STREAM_ID = "cctv-stream"
    }

    /** The exact [SharedCameraStream] instance this is currently registered on, if any -- see [onStreamStarted]. */
    @Volatile private var stream: SharedCameraStream? = null

    /** Non-null only while [viewerCount] is >0 -- see [attachViewer]/[detachViewer]. */
    private var engine: WebRtcEngine? = null

    private var viewerCount = 0

    /** Called once per successful `startStream()` -- listener registration is idempotent since the stream instance is a long-lived singleton, mirroring `MseVideoBridge.onStreamStarted`. */
    @Synchronized
    fun onStreamStarted(newStream: SharedCameraStream) {
        stream = newStream
    }

    /**
     * Lazily brings up [WebRtcEngine] on the first viewer, and hands back a fresh
     * [PeerConnection] carrying its shared video track. Returns null if the camera isn't actually
     * streaming yet, mirroring `MseVideoBridge.snapshotForNewViewer` returning null pre-stream.
     */
    @Synchronized
    fun attachViewer(observer: PeerConnection.Observer): PeerConnection? {
        val activeStream = stream ?: return null
        val activeEngine = engine ?: WebRtcEngine(context, activeStream).also { engine = it }

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = activeEngine.factory.createPeerConnection(rtcConfig, observer) ?: return null
        pc.addTrack(activeEngine.videoTrack, listOf(STREAM_ID))
        viewerCount++
        return pc
    }

    /** Must be called exactly once per successful [attachViewer], when that viewer disconnects. */
    @Synchronized
    fun detachViewer() {
        if (viewerCount <= 0) return
        viewerCount--
        if (viewerCount == 0) {
            engine?.stop()
            engine = null
        }
    }

    /** Only called from the service's `onDestroy()`. */
    @Synchronized
    fun release() {
        viewerCount = 0
        engine?.stop()
        engine = null
        stream = null
    }
}
