package com.zektopic.cctvapp.mse

import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.streaming.Fmp4Fragmenter
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges [SharedCameraStream]'s encoded H.264 + AAC output into the dashboard's MSE preview.
 * Owns one [Fmp4Fragmenter] shared by every viewer -- all viewers just read the same broadcast
 * byte stream (see [MseStreamSocket]). Created lazily on first stream start, published via [MseBus].
 */
class MseVideoBridge {

    private companion object {
        private const val TAG = "MseVideoBridge"
    }

    private val fragmenter: Fmp4Fragmenter = Fmp4Fragmenter(
        onFragment = { fragment, kind -> broadcast(fragment, kind) },
        hasViewers = { viewers.isNotEmpty() },
    ).apply {
        onInitSegmentChanged = { onInitSegmentChanged() }
    }

    private val viewers = CopyOnWriteArrayList<MseStreamSocket>()

    /** The exact [SharedCameraStream] instance [fragmenter] is currently registered on, if any -- see [onStreamStarted]. */
    private var registeredOn: SharedCameraStream? = null

    fun snapshotForNewViewer(): Fmp4Fragmenter.ViewerSnapshot? = fragmenter.snapshotForNewViewer()

    /** Called once per successful `startStream()`; listener registration is idempotent since the stream instance is a long-lived singleton. */
    fun onStreamStarted(stream: SharedCameraStream) {
        fragmenter.setVideoSize(stream.videoWidth, stream.videoHeight)
        fragmenter.resetSession()
        if (registeredOn === stream) return
        registeredOn = stream
        stream.addVideoDataListener(fragmenter)
        stream.addAudioDataListener(fragmenter)
    }

    /** Called instead of [onStreamStarted] when the active codec isn't H264 -- fmp4 here can't carry H265. */
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

    private fun broadcast(fragment: ByteArray, kind: Fmp4Fragmenter.FragmentKind) {
        viewers.forEach { it.enqueue(fragment, kind) }
    }

    /** Codec/resolution changed -- every viewer's SourceBuffer is stale, so force them to reconnect against the new init segment. */
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
