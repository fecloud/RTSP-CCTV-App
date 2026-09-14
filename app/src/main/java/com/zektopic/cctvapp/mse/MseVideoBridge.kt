package com.zektopic.cctvapp.mse

import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.streaming.Fmp4Fragmenter
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges [SharedCameraStream]'s encoded H.264 + AAC output into the dashboard's MSE preview --
 * replaces the old WebRTC-based `WebRtcVideoBridge`. Owns one [Fmp4Fragmenter] shared by every
 * viewer (unlike WebRTC's one-`PeerConnection`-per-viewer model, MSE viewers are just readers of
 * the same broadcast byte stream -- see [MseStreamSocket]).
 *
 * One instance is created lazily the first time
 * [CctvServerService][com.zektopic.cctvapp.service.CctvServerService] starts streaming and lives
 * for as long as the service does, published via [MseBus].
 */
class MseVideoBridge {

    private companion object {
        private const val TAG = "MseVideoBridge"
    }

    private val fragmenter: Fmp4Fragmenter = Fmp4Fragmenter(onFragment = { broadcast(it) }).apply {
        onInitSegmentChanged = { onInitSegmentChanged() }
    }

    private val viewers = CopyOnWriteArrayList<MseStreamSocket>()

    /** The exact [SharedCameraStream] instance [fragmenter] is currently registered on, if any -- see [onStreamStarted]. */
    private var registeredOn: SharedCameraStream? = null

    fun initSegment(): ByteArray? = fragmenter.initSegment
    fun lastKeyframeFragment(): ByteArray? = fragmenter.lastKeyframeFragment
    fun codecsString(): String? = fragmenter.codecsString

    /**
     * Called once per successful `SharedCameraStream.startStream()`, including after a
     * settings-triggered restart -- registration itself only needs to happen once per
     * [SharedCameraStream] instance (idempotent here) since that instance is a singleton reused
     * across every restart for the service's whole lifetime (see its own kdoc), but the
     * configured resolution can change on a restart, so [Fmp4Fragmenter.setVideoSize] always
     * runs again regardless.
     */
    fun onStreamStarted(stream: SharedCameraStream) {
        fragmenter.setVideoSize(stream.videoWidth, stream.videoHeight)
        // Every real startStream() call is a new session -- reset unconditionally, regardless
        // of whether listener registration below is a no-op this time. See
        // Fmp4Fragmenter.resetSession's kdoc for why this (not onVideoInfo/onAudioFormat
        // firing) is the reset signal.
        fragmenter.resetSession()
        if (registeredOn === stream) return
        registeredOn = stream
        stream.addVideoDataListener(fragmenter)
        stream.addAudioDataListener(fragmenter)
    }

    /** Called instead of [onStreamStarted] when the active codec isn't H264 -- `avc1`-shaped fmp4 fundamentally can't carry H265, the only other codec this app offers. */
    fun onUnsupportedCodec() {
        closeAllViewers("Live preview requires the H264 codec")
    }

    fun requestKeyframe() {
        registeredOn?.requestKeyframe()
    }

    fun attach(socket: MseStreamSocket) {
        viewers.add(socket)
    }

    fun detach(socket: MseStreamSocket) {
        viewers.remove(socket)
    }

    private fun broadcast(fragment: ByteArray) {
        viewers.forEach { it.enqueue(fragment) }
    }

    /**
     * A real codec/resolution change: every currently-connected viewer's `SourceBuffer` was
     * created against the old codec string/resolution and can't be hot-swapped safely, so force
     * them all closed -- the dashboard's reconnect-on-close logic (see dashboard.html) brings
     * each one back with a brand new `MediaSource` against the new init segment.
     */
    private fun onInitSegmentChanged() {
        Log.d(TAG, "Init segment changed (codec/resolution restart) -- closing ${viewers.size} viewer(s) so they reconnect cleanly")
        closeAllViewers("Stream configuration changed")
    }

    private fun closeAllViewers(reason: String) {
        viewers.toList().forEach { it.closeQuietly(reason) }
    }

    /** Only called from the service's `onDestroy()`. */
    fun release() {
        registeredOn?.let {
            it.removeVideoDataListener(fragmenter)
            it.removeAudioDataListener(fragmenter)
        }
        registeredOn = null
        closeAllViewers("Server shutting down")
    }
}
