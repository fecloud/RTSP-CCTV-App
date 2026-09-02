package com.zektopic.cctvapp

import com.pedro.common.VideoCodec
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.view.GlInterface
import com.pedro.rtspserver.RtspServerCamera1
import com.pedro.rtspserver.RtspServerCamera2
import java.io.FileDescriptor
import kotlin.math.roundToInt

/**
 * Unifies [RtspServerCamera2] (Camera2-backed) and [RtspServerCamera1] (legacy
 * Camera1-backed) behind one surface, so [CctvServerService] doesn't need to branch on
 * which camera generation a device actually got at every call site.
 *
 * The two RootEncoder base classes (`Camera2Base`/`Camera1Base`) share no common
 * supertype -- both extend `Object` directly -- but are otherwise duck-type identical
 * for everything this app calls except zoom, which [Camera1Streamer] adapts (see its
 * doc comment).
 */
interface CameraStreamer {
    val isStreaming: Boolean
    fun switchCamera()
    fun enableLantern()
    fun disableLantern()
    fun isLanternEnabled(): Boolean
    fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int): Boolean
    fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean
    fun disableAudio()
    fun setVideoCodec(codec: VideoCodec)
    fun forceCodecType(video: CodecUtil.CodecType, audio: CodecUtil.CodecType)
    fun getStreamClient(): StreamBaseClient
    fun setVideoBitrateOnFly(bitrate: Int)
    fun startStream()
    fun stopStream()
    fun getGlInterface(): GlInterface
    /** Real hardware zoom bounds, in the app's semantic zoom-factor space. */
    fun getZoomRange(): Pair<Float, Float>
    /** [value] is the app's semantic zoom factor (see [getZoomRange]), not a raw device value. */
    fun setZoom(value: Float)
    /**
     * `Camera1Base`/`Camera2Base` share no common supertype (see class doc) but expose
     * identical recording methods, so these just forward -- confirmed by decompiling
     * both: same `startRecord`/`stopRecord`/`isRecording` signatures on each.
     */
    fun startRecord(path: String, listener: RecordController.Listener)
    fun startRecord(fd: FileDescriptor, listener: RecordController.Listener)
    fun stopRecord()
    fun isRecording(): Boolean
}

class Camera2Streamer(private val camera: RtspServerCamera2) : CameraStreamer {
    override val isStreaming get() = camera.isStreaming
    override fun switchCamera() = camera.switchCamera()
    override fun enableLantern() = camera.enableLantern()
    override fun disableLantern() = camera.disableLantern()
    override fun isLanternEnabled() = camera.isLanternEnabled()
    override fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int) =
        camera.prepareVideo(width, height, fps, bitrate, rotation)
    override fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ) = camera.prepareAudio(bitrate, sampleRate, isStereo, echoCanceler, noiseSuppressor)
    override fun disableAudio() = camera.disableAudio()
    override fun setVideoCodec(codec: VideoCodec) = camera.setVideoCodec(codec)
    override fun forceCodecType(video: CodecUtil.CodecType, audio: CodecUtil.CodecType) =
        camera.forceCodecType(video, audio)
    override fun getStreamClient(): StreamBaseClient = camera.getStreamClient()
    override fun setVideoBitrateOnFly(bitrate: Int) = camera.setVideoBitrateOnFly(bitrate)
    override fun startStream() = camera.startStream()
    override fun stopStream() = camera.stopStream()
    override fun getGlInterface(): GlInterface = camera.getGlInterface()
    override fun getZoomRange(): Pair<Float, Float> {
        val range = camera.zoomRange
        return Pair(range.lower, range.upper)
    }
    override fun setZoom(value: Float) {
        camera.zoom = value
    }
    override fun startRecord(path: String, listener: RecordController.Listener) = camera.startRecord(path, listener)
    override fun startRecord(fd: FileDescriptor, listener: RecordController.Listener) = camera.startRecord(fd, listener)
    override fun stopRecord() = camera.stopRecord()
    override fun isRecording() = camera.isRecording()
}

/**
 * Adapts [RtspServerCamera1] (`Camera1Base`) to [CameraStreamer]. Only zoom needs real
 * translation -- see [mapZoomLevelToCamera1Index].
 */
class Camera1Streamer(private val camera: RtspServerCamera1) : CameraStreamer {
    override val isStreaming get() = camera.isStreaming
    override fun switchCamera() = camera.switchCamera()
    override fun enableLantern() = camera.enableLantern()
    override fun disableLantern() = camera.disableLantern()
    override fun isLanternEnabled() = camera.isLanternEnabled()
    override fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int) =
        camera.prepareVideo(width, height, fps, bitrate, rotation)
    override fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ) = camera.prepareAudio(bitrate, sampleRate, isStereo, echoCanceler, noiseSuppressor)
    override fun disableAudio() = camera.disableAudio()
    override fun setVideoCodec(codec: VideoCodec) = camera.setVideoCodec(codec)
    override fun forceCodecType(video: CodecUtil.CodecType, audio: CodecUtil.CodecType) =
        camera.forceCodecType(video, audio)
    override fun getStreamClient(): StreamBaseClient = camera.getStreamClient()
    override fun setVideoBitrateOnFly(bitrate: Int) = camera.setVideoBitrateOnFly(bitrate)
    override fun startStream() = camera.startStream()
    override fun stopStream() = camera.stopStream()
    override fun getGlInterface(): GlInterface = camera.getGlInterface()

    // Report the same semantic bounds the dashboard slider already understands (see
    // AppPreferences.ZOOM_MIN/MAX) rather than raw step-index bounds -- Camera1Base has
    // no getZoomRatios(), so there's no way to know what real multiplier the last step
    // actually reaches; the slider's meaning just stays consistent across both paths.
    override fun getZoomRange(): Pair<Float, Float> = Pair(AppPreferences.ZOOM_MIN, AppPreferences.ZOOM_MAX)

    override fun setZoom(value: Float) {
        camera.setZoom(mapZoomLevelToCamera1Index(value, AppPreferences.ZOOM_MIN, AppPreferences.ZOOM_MAX, camera.maxZoom))
    }
    override fun startRecord(path: String, listener: RecordController.Listener) = camera.startRecord(path, listener)
    override fun startRecord(fd: FileDescriptor, listener: RecordController.Listener) = camera.startRecord(fd, listener)
    override fun stopRecord() = camera.stopRecord()
    override fun isRecording() = camera.isRecording()

    companion object {
        /**
         * Camera1's `setZoom(Int)` takes a step index into the device's own zoom-ratio
         * table (`getZoomRatios()`, not exposed by RootEncoder's `Camera1Base`), not a
         * direct multiplier like Camera2's `setZoom(Float)`. There's no way to match a
         * specific app-level factor (e.g. "2.0x") to its true index without that table,
         * so this is a best-effort linear placement of the app's semantic
         * [appMin]-[appMax] zoom range onto [0, maxZoomIndex] -- "8.0x" on a legacy
         * device moves to the last discrete step, whatever ratio that actually is.
         */
        internal fun mapZoomLevelToCamera1Index(
            zoomLevel: Float,
            appMin: Float,
            appMax: Float,
            maxZoomIndex: Int
        ): Int {
            if (maxZoomIndex <= 0) return 0
            val fraction = ((zoomLevel - appMin) / (appMax - appMin)).coerceIn(0f, 1f)
            return (fraction * maxZoomIndex).roundToInt().coerceIn(0, maxZoomIndex)
        }
    }
}
