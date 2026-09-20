package com.zektopic.cctvapp.mse

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.common.isKeyframe
import com.pedro.common.toByteArray
import com.pedro.encoder.video.GetVideoData
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges [SharedCameraStream]'s raw H.264 output into the dashboard's MSE preview. Unlike the
 * fmp4-muxing design this replaced, the server does no container work at all -- it forwards raw
 * Annex-B NALs (start code included) straight from the encoder, and the dashboard's client builds
 * the fmp4 fragments itself: `h264-converter.js` (vendored, the same library ws-scrcpy's web
 * client uses).
 *
 * Video-only -- audio is a separate, shared preview independent of which video transport is
 * active (MSE or WebRTC), see `com.zektopic.cctvapp.audio.AudioStreamBridge`'s kdoc.
 */
class MseVideoBridge : GetVideoData {

    private companion object {
        private const val TAG = "MseVideoBridge"
    }

    @Volatile private var spsBytes: ByteArray? = null
    @Volatile private var ppsBytes: ByteArray? = null
    @Volatile private var lastFps = -1

    /** The exact [SharedCameraStream] instance this is currently registered on, if any -- see [onStreamStarted]. */
    private var registeredOn: SharedCameraStream? = null

    private val viewers = CopyOnWriteArrayList<MseStreamSocket>()

    /** Everything a newly-connecting viewer needs to construct its own `VideoConverter`. */
    data class ViewerHandshake(val fps: Int, val sps: ByteArray, val pps: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ViewerHandshake

            if (fps != other.fps) return false
            if (!sps.contentEquals(other.sps)) return false
            if (!pps.contentEquals(other.pps)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = fps
            result = 31 * result + sps.contentHashCode()
            result = 31 * result + pps.contentHashCode()
            return result
        }
    }

    fun snapshotForNewViewer(): ViewerHandshake? {
        val sps = spsBytes ?: return null
        val pps = ppsBytes ?: return null
        return ViewerHandshake(fps = lastFps, sps = sps, pps = pps)
    }

    /** Called once per successful `startStream()`; listener registration is idempotent since the stream instance is a long-lived singleton. */
    fun onStreamStarted(stream: SharedCameraStream) {
        val fps = ServiceStateRepository.settings.videoFps
        if (lastFps != -1 && lastFps != fps) {
            Log.d(TAG, "Frame rate changed ($lastFps -> $fps) -- closing ${viewers.size} viewer(s) so they reconnect cleanly")
            closeAllViewers("Stream configuration changed")
        }
        lastFps = fps
        if (registeredOn === stream) return
        registeredOn = stream
        stream.addVideoDataListener(this)
    }

    /** Called instead of [onStreamStarted] when the active codec isn't H264 -- this bridge can't carry H265. */
    fun onUnsupportedCodec() {
        closeAllViewers("Live preview requires the H264 codec")
    }

    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        val newSps = normalizeStartCode(sps.toByteArray())
        val newPps = pps?.toByteArray()?.let { normalizeStartCode(it) }
        if (spsBytes != null && newPps != null && !spsBytes.contentEquals(newSps)) {
            Log.d(TAG, "SPS changed -- closing ${viewers.size} viewer(s) so they reconnect cleanly")
            closeAllViewers("Stream configuration changed")
        }
        spsBytes = newSps
        ppsBytes = newPps
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {}

    /** Called from the shared camera callback thread -- must never block. */
    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (viewers.isEmpty()) return
        val nal = normalizeStartCode(bufferToArray(videoBuffer, info))
        viewers.forEach { it.enqueue(info.isKeyframe(), nal) }
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

    private fun closeAllViewers(reason: String) {
        viewers.toList().forEach { it.closeQuietly(reason) }
    }

    /** Only called from the service's `onDestroy()`. */
    fun release() {
        registeredOn?.removeVideoDataListener(this)
        registeredOn = null
        closeAllViewers("Server shutting down")
    }
}

/** [ByteBuffer.toByteArray] copies the whole buffer -- [info]'s offset/size mark the actual sample within it. */
private fun bufferToArray(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
    val dup = buffer.duplicate()
    dup.position(info.offset)
    dup.limit(info.offset + info.size)
    return ByteArray(dup.remaining()).also { dup.get(it) }
}

/**
 * The client's NAL splitter (`h264-converter.js`'s `nalu-stream-buffer.js`) only recognizes a
 * 4-byte Annex-B start code (`00 00 00 01`); a 3-byte one (`00 00 01`) slips through unrecognized,
 * silently merging that NAL into whichever one follows until the next 4-byte-prefixed NAL
 * resyncs it -- matches the pattern of a real, intermittent gap observed in the client's
 * buffered ranges. The old `Fmp4Fragmenter` handled both forms too (see git history), which is
 * why this suspects RootEncoder doesn't always emit a 4-byte one. Always re-prefixing with a
 * canonical 4-byte start code, regardless of what the encoder gave us, keeps the client's parser
 * in sync either way.
 *
 * Called on every video NAL on the shared camera callback thread while any viewer is attached,
 * so the already-4-byte-prefixed case (the common one) returns [nal] itself with zero copying --
 * only a 3-byte or missing prefix pays for the copy-and-reprefix below.
 */
private fun normalizeStartCode(nal: ByteArray): ByteArray {
    if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
        return nal
    }
    val payload = if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
        nal.copyOfRange(3, nal.size)
    } else {
        nal
    }
    val result = ByteArray(4 + payload.size)
    result[3] = 1
    System.arraycopy(payload, 0, result, 4, payload.size)
    return result
}
