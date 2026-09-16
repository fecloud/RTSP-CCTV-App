package com.zektopic.cctvapp.streaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.TimeUtils
import com.pedro.common.VideoCodec
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.AndroidMuxerRecordController
import com.pedro.library.view.GlStreamInterface
import com.pedro.rtspserver.server.RtspServer
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the camera/GL/encoder pipeline directly, in place of `RtspServerStream`/`StreamBase`
 * (`third_party/RTSP-Server`, `third_party/RootEncoder`) -- so that RTSP send, local
 * recording, and the dashboard's MSE preview (`com.zektopic.cctvapp.mse.MseVideoBridge`, which
 * relays raw NALs -- fmp4 muxing happens client-side, see its kdoc) can all be direct consumers
 * of one encoded bitstream, instead of the preview needing a loopback RTSP client to re-derive
 * it (the previous design; see git history on `WebRtcVideoBridge` for why that existed and was
 * replaced).
 *
 * `StreamBase` itself turned out to be nothing more than orchestration glue around public,
 * independently-usable RootEncoder classes -- [Camera2Source], [GlStreamInterface],
 * [VideoEncoder]/[AudioEncoder], [RecordController], and [RtspServer] (the class that
 * actually sends RTP, independent of `RtspServerStream`'s wrapper). Its one real limitation is
 * that its private `GetVideoData` dispatcher is hardwired 1:1 to the RTSP sender, with no way
 * to add a second consumer of the encoded output -- forking `third_party/RTSP-Server` to add
 * one was considered and rejected (would force CI/release onto `useSourceDeps=true`
 * permanently, see root `CLAUDE.md`). This class reproduces `StreamBase`'s orchestration
 * (prepare/start/stop sequencing, GL rotation setup, error-recovery-capable encoders) using
 * those same public building blocks, faithfully mirroring `StreamBase.kt`'s own call order --
 * plus [addVideoDataListener]/[addAudioDataListener], the thing it lacked.
 *
 * Deliberately drops everything this app never uses: preview/multi-preview surfaces
 * (`startPreview`/`addPreviewSurface`) and `differentRecordResolution`'s dual-encoder-surface
 * path (this app never sets a separate record resolution, so `StreamBase` itself never
 * exercises that path either).
 */
class SharedCameraStream(
    context: Context,
    port: Int,
    connectChecker: ConnectChecker,
    val camera2Source: Camera2Source,
    private val audioSource: AudioSource,
) {

    private val glInterface = GlStreamInterface(context)
    private val rtspServer = RtspServer(connectChecker, port)

    /** [com.zektopic.cctvapp.mse.MseVideoBridge] (MSE preview) registers here -- RTSP send and recording are always-on, hardwired below. */
    private val extraVideoListeners = CopyOnWriteArrayList<GetVideoData>()

    /** Same purpose as [extraVideoListeners], for the audio side. */
    private val extraAudioListeners = CopyOnWriteArrayList<GetAudioData>()

    private var recordController: RecordController = AndroidMuxerRecordController()

    private val getVideoData = object : GetVideoData {
        override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            rtspServer.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
            extraVideoListeners.forEach { it.onVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate()) }
        }

        override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            // Same buffer instance handed to every consumer in turn, exactly like
            // StreamBase.kt's own getVideoData dispatcher -- each consumer (RtspServer's
            // internal clone-before-enqueue, MediaMuxer's offset-based reads, and our own
            // duplicate()-based copy below) reads independently without mutating shared
            // position/limit state, an already-proven-safe pattern in the code this mirrors.
            rtspServer.sendVideo(videoBuffer, info)
            extraVideoListeners.forEach { it.getVideoData(videoBuffer, info) }
            recordController.recordVideo(videoBuffer, info)
        }

        override fun onVideoFormat(mediaFormat: MediaFormat) {
            recordController.setVideoFormat(mediaFormat)
        }
    }

    private val getAacData = object : GetAudioData {
        override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            rtspServer.sendAudio(audioBuffer, info)
            extraAudioListeners.forEach { it.getAudioData(audioBuffer, info) }
            recordController.recordAudio(audioBuffer, info)
        }

        override fun onAudioFormat(mediaFormat: MediaFormat) {
            recordController.setAudioFormat(mediaFormat)
            extraAudioListeners.forEach { it.onAudioFormat(mediaFormat) }
        }
    }

    private val getMicrophoneData = object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) {
            audioEncoder.inputPCMData(frame)
        }
    }

    private val videoEncoder = VideoEncoder(getVideoData)
    private val audioEncoder = AudioEncoder(getAacData)

    var isStreaming = false
        private set
    val isRecording: Boolean get() = recordController.isRunning()

    /** Mirrors `StreamBase.prepareAudio` -- must be called before [startStream]/[startRecord]. */
    fun prepareAudio(sampleRate: Int, isStereo: Boolean, bitrate: Int): Boolean {
        if (isStreaming || isRecording) throw IllegalStateException("Stream and record must be stopped before prepareAudio")
        val audioResult = audioSource.init(sampleRate, isStereo, false, false)
        if (audioResult) {
            rtspServer.setAudioInfo(sampleRate, isStereo)
            return audioEncoder.prepareAudioEncoder(bitrate, sampleRate, isStereo)
        }
        return false
    }

    /**
     * Mirrors `StreamBase.prepareVideo` -- the GL rotation/orientation setup
     * (`setEncoderSize`/`setIsPortrait`/`setCameraOrientation`/`setOrientationConfig`) is
     * copied verbatim from there, including the `rotation == 0 -> 270` default mapping that
     * `CctvServerService.applyVerticalFlip`'s kdoc explicitly depends on.
     */
    fun prepareVideo(
        width: Int, height: Int, bitrate: Int, fps: Int = 30, iFrameInterval: Int = 2,
        rotation: Int = 0, profile: Int = -1, level: Int = -1,
    ): Boolean {
        if (isStreaming || isRecording) throw IllegalStateException("Stream and record must be stopped before prepareVideo")
        val videoResult = camera2Source.init(width, height, fps, rotation)
        if (videoResult) {
            if (rotation == 90 || rotation == 270) glInterface.setEncoderSize(height, width) else glInterface.setEncoderSize(width, height)
            glInterface.setIsPortrait(rotation == 90 || rotation == 270)
            glInterface.setCameraOrientation(if (rotation == 0) 270 else rotation - 90)
            glInterface.setOrientationConfig(camera2Source.getOrientationConfig())
            val result = videoEncoder.prepareVideoEncoder(
                width, height, fps, bitrate, rotation, iFrameInterval, FormatVideoEncoder.SURFACE, profile, level
            )
            videoEncoder.setForceFps(videoEncoder.fps)
            glInterface.forceFpsLimit(videoEncoder.fps)
            return result
        }
        return false
    }

    /** Mirrors `StreamBase.startStream` -- must be called after [prepareVideo]/[prepareAudio]. */
    fun startStream() {
        if (isStreaming) throw IllegalStateException("Stream already started, stopStream before startStream again")
        isStreaming = true
        rtspServer.startServer()
        if (!isRecording) startSources() else requestKeyframe()
    }

    /** Mirrors `StreamBase.stopStream`. */
    fun stopStream(): Boolean {
        isStreaming = false
        rtspServer.stopServer()
        if (!isRecording) {
            stopSources()
            return prepareEncoders()
        }
        return true
    }

    private fun startSources() {
        if (!glInterface.isRunning) glInterface.start()
        if (!camera2Source.isRunning()) camera2Source.start(glInterface.surfaceTexture)
        audioSource.start(getMicrophoneData)
        val startTs = TimeUtils.getCurrentTimeMicro()
        videoEncoder.start(startTs)
        audioEncoder.start(startTs)
        glInterface.addMediaCodecSurface(videoEncoder.inputSurface)
    }

    private fun stopSources() {
        camera2Source.stop()
        audioSource.stop()
        glInterface.removeMediaCodecSurface()
        glInterface.stop()
        videoEncoder.stop()
        audioEncoder.stop()
        if (!isRecording) recordController.resetFormats()
    }

    private fun prepareEncoders(): Boolean = videoEncoder.prepareVideoEncoder() && audioEncoder.prepareAudioEncoder()

    /** Forces the shared MediaCodec's next output to be an IDR -- also reaches recording/WebRTC since the encoder is shared. */
    fun requestKeyframe() {
        if (videoEncoder.isRunning) videoEncoder.requestKeyframe()
    }

    fun setVideoBitrateOnFly(bitrate: Int) {
        videoEncoder.setVideoBitrateOnFly(bitrate)
    }

    fun forceCodecType(codecTypeVideo: CodecUtil.CodecType, codecTypeAudio: CodecUtil.CodecType) {
        videoEncoder.forceCodecType(codecTypeVideo)
        audioEncoder.forceCodecType(codecTypeAudio)
    }

    /** [codec] is only ever [VideoCodec.H264]/[VideoCodec.H265] -- see `CctvServerService.startStream`; AV1 is never offered. */
    fun setVideoCodec(codec: VideoCodec) {
        rtspServer.setVideoCodec(codec)
        recordController.setVideoCodec(codec)
        videoEncoder.type = when (codec) {
            VideoCodec.H264 -> CodecUtil.H264_MIME
            VideoCodec.H265 -> CodecUtil.H265_MIME
            VideoCodec.AV1 -> error("AV1 is not a supported codec in this app")
        }
    }

    fun setAudioCodec(codec: AudioCodec) {
        rtspServer.setAudioCodec(codec)
        recordController.setAudioCodec(codec)
        audioEncoder.type = when (codec) {
            AudioCodec.G711 -> CodecUtil.G711_MIME
            AudioCodec.AAC -> CodecUtil.AAC_MIME
            AudioCodec.OPUS -> CodecUtil.OPUS_MIME
        }
    }

    /** Replaces `getStreamClient().setAuthorization(user, password)` -- `RtspServer` (unlike `RtspServerStream`) exposes this directly. */
    fun setRtspAuthorization(user: String?, password: String?) {
        rtspServer.setAuth(user, password)
    }

    /** Used by `CctvServerService.applyVerticalFlip`/`applyTimestampOverlay` exactly as `RtspServerStream.getGlInterface()` was. */
    fun getGlInterface(): GlStreamInterface = glInterface

    fun setRecordController(recordController: RecordController) {
        if (!isRecording) {
            recordController.updateInfo(this.recordController.getVideoCodec(), this.recordController.getAudioCodec())
            this.recordController = recordController
        }
    }

    fun startRecord(path: String, listener: RecordController.Listener) {
        if (isRecording) throw IllegalStateException("Record already started, stopRecord before startRecord again")
        val tracks = if (audioSource is NoAudioSource) RecordController.RecordTracks.VIDEO else RecordController.RecordTracks.ALL
        recordController.setRequestKeyFrame { videoEncoder.requestKeyframe() }
        recordController.startRecord(path, listener, tracks)
        if (!isStreaming) startSources()
    }

    fun stopRecord(): Boolean {
        recordController.stopRecord()
        if (!isStreaming) {
            stopSources()
            return prepareEncoders()
        }
        return true
    }

    /** Every registered listener gets the exact same [GetVideoData] callbacks RTSP send/recording do -- no protocol round trip. */
    fun addVideoDataListener(listener: GetVideoData) {
        extraVideoListeners.add(listener)
    }

    fun removeVideoDataListener(listener: GetVideoData) {
        extraVideoListeners.remove(listener)
    }

    /** Same purpose as [addVideoDataListener], for the audio side. */
    fun addAudioDataListener(listener: GetAudioData) {
        extraAudioListeners.add(listener)
    }

    fun removeAudioDataListener(listener: GetAudioData) {
        extraAudioListeners.remove(listener)
    }

    /** Only called from the service's `onDestroy()`. */
    fun release() {
        if (isStreaming) stopStream()
        if (isRecording) stopRecord()
        stopSources()
        camera2Source.release()
        audioSource.release()
    }
}
