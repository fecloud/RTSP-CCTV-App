package com.zektopic.cctvapp.webrtc

import android.content.Context
import com.zektopic.cctvapp.streaming.SharedCameraStream
import org.webrtc.PeerConnection
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Shared WebRTC video/audio source feeding every dashboard viewer that's opted into the WebRTC
 * preview (see `dashboard.js`'s preview-type chip) off one [SharedCameraStream]. Unlike
 * [com.zektopic.cctvapp.mse.MseVideoBridge] (which forwards the
 * already-hardware-encoded H264/AAC bitstream), [WebRtcEngine] taps the camera's raw GL
 * composite via [com.pedro.library.view.GlStreamInterface.addMultiPreviewSurface] -- a second
 * render target off the same EGL context RootEncoder already draws the encoder's frame into, so
 * no second `Camera2` session is needed, and it works regardless of which codec RTSP currently
 * has selected (WebRTC re-encodes the raw frames itself).
 *
 * Audio is the one place this can't reuse the shared pipeline: [JavaAudioDeviceModule] opens
 * its own `AudioRecord`, independent of the `MicrophoneSource`/`AudioEncoder` pipeline
 * [SharedCameraStream] already runs for RTSP/MSE -- this is a known, accepted tradeoff (see
 * root `CLAUDE.md`'s "Known constraints"): it only exists while at least one WebRTC viewer is
 * actually connected ([attachViewer]/[detachViewer] reference-count that via [WebRtcEngine]'s
 * start/stop), so plain MSE/RTSP usage never pays for it, and some devices' mic HALs may not
 * tolerate the resulting concurrent capture (this is exactly why this project's original WebRTC
 * preview was replaced by the current MSE one -- see git history).
 *
 * This class itself only does viewer bookkeeping (reference counting, [PeerConnection] creation
 * per viewer) -- the actual engine resources (factory, tracks, GL preview surface) live in
 * [WebRtcEngine], created/torn down here as the viewer count goes 0->1/1->0.
 */
class WebRtcPreviewBridge(private val context: Context) {

    private companion object {
        /** Same id for both tracks so the browser groups them into one `MediaStream` in `ontrack` instead of two. */
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
     * [PeerConnection] carrying its shared tracks. Returns null if the camera isn't actually
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
        pc.addTrack(activeEngine.audioTrack, listOf(STREAM_ID))
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
