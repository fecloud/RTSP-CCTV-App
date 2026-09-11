package com.zektopic.cctvapp.service

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.library.base.recording.RecordController
import com.zektopic.cctvapp.log.AppLog as Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A [RecordController] that rotates between output files itself instead of RootEncoder's
 * built-in one (`AndroidMuxerRecordController`), which only supports one file per
 * `startRecord()`/`stopRecord()` call pair. RootEncoder forwards every encoded frame to
 * whatever `RecordController` is installed regardless of recording state, so installing this
 * one via `RtspServerCamera2.setRecordController(...)` and calling `startRecord()`/
 * `stopRecord()` exactly once per recording session (never per segment -- see
 * [RecordingManager]) lets the encoder run uninterrupted across segment boundaries: there is
 * no window where neither the old nor the new file is receiving frames, unlike a design that
 * stops and restarts recording per segment.
 *
 * Also owns the segment-length timer itself -- [recordVideo] runs on every encoded video
 * frame anyway, so it just checks elapsed time there instead of a caller having to run a
 * separate timer and call in to request a rotation. [nextSegmentFile] is asked for a new
 * file exactly when the current segment has run long enough; the actual cut only happens
 * once a video keyframe arrives after that (a new file's video track can't start mid-GOP).
 */
class SeamlessMp4RecordController(
    private val segmentDurationMinutes: () -> Int,
    private val nextSegmentFile: () -> File,
    private val onSegmentFinalized: (File) -> Unit,
) : RecordController {

    companion object {
        private const val TAG = "SeamlessMp4RecordController"
        private const val H264_IDR = 5
        private const val H265_IDR_W_RADL = 19
        private const val H265_IDR_N_LP = 20

        // RootEncoder's Camera2Base.startRecord(path, listener) infers RecordTracks.ALL from its
        // own `audioInitialized` flag, which (per Camera2Base source) latches true forever once
        // any earlier prepareAudio() succeeded and is never reset by a later failed prepareAudio()
        // or by disableAudio() -- so a segment can be told to expect an audio track that this
        // session's audio pipeline will in fact never produce a format for (mic grabbed by
        // another app, blocked by the OEM's background-mic restrictions, etc). Without a bound,
        // that leaves [openMuxer] waiting forever: every keyframe re-checks `audioFormat == null`
        // and bails, so `muxerStarted` never becomes true and no file is ever produced -- silently,
        // since that early return logs nothing. Capping the wait lets a segment fall back to
        // video-only once it's clearly not coming, rather than recording nothing at all.
        private const val AUDIO_FORMAT_TIMEOUT_MS = 3_000L
    }

    // Closing a MediaMuxer (writing its moov atom) can take a moment; doing it here instead of
    // on the encoder's own callback thread (where every recordVideo/recordAudio call runs)
    // keeps a segment cut from ever stalling frame delivery.
    private val teardownScope = CoroutineScope(Dispatchers.IO)

    @Volatile private var status = RecordController.Status.STOPPED
    private var listener: RecordController.Listener? = null
    private var tracks = RecordController.RecordTracks.ALL
    private var videoCodec = VideoCodec.H264
    private var audioCodec = AudioCodec.AAC

    // Never nulled out after first use (unlike RootEncoder's own controllers) -- safe to call
    // repeatedly, and every segment rotation needs to be able to ask for an early keyframe.
    private var requestKeyFrame: RecordController.RequestKeyFrame? = null

    // Set once, shortly after the encoder starts, and reused for every segment's MediaMuxer --
    // the encoder is never restarted between segments, so its format never changes either.
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var muxer: MediaMuxer? = null
    private var muxerFile: File? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    // The presentation timestamp of the segment's first (video) sample, subtracted from every
    // sample's own timestamp -- each output file needs to start near time zero the same way
    // RootEncoder's own controller does per recording, even though the underlying encoder here
    // keeps counting continuously across every segment in the whole session.
    private var segmentStartUs = 0L

    // Wall-clock time (SystemClock.elapsedRealtime) the current segment's muxer was opened,
    // used only to decide when it's time to ask [nextSegmentFile] for a new one -- unrelated
    // to [segmentStartUs], which rebases *sample* timestamps instead.
    private var segmentStartElapsedMs = 0L

    private var pendingFile: File? = null

    // Wall-clock time [pendingFile] was set, used only to bound how long a segment will wait for
    // an audio format before opening video-only -- see [AUDIO_FORMAT_TIMEOUT_MS].
    private var pendingSinceElapsedMs = 0L

    @Synchronized
    override fun startRecord(path: String, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        this.listener = listener
        this.tracks = tracks
        status = RecordController.Status.STARTED
        listener?.onStatusChange(status)
        pendingFile = File(path)
        pendingSinceElapsedMs = SystemClock.elapsedRealtime()
        requestKeyFrame?.onRequestKeyFrame()
    }

    override fun startRecord(fd: FileDescriptor, listener: RecordController.Listener?, tracks: RecordController.RecordTracks) {
        throw UnsupportedOperationException("File-descriptor recording isn't used by this app")
    }

    @Synchronized
    override fun stopRecord() {
        pendingFile = null
        val finishedFile = closeCurrentMuxer()
        status = RecordController.Status.STOPPED
        listener?.onStatusChange(status)
        finishedFile?.let { onSegmentFinalized(it) }
    }

    @Synchronized
    override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
        if (status == RecordController.Status.STOPPED || tracks == RecordController.RecordTracks.AUDIO) return

        if (pendingFile == null && muxerStarted && currentSegmentExpired()) {
            val file = try {
                nextSegmentFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create next segment file", e)
                null
            }
            if (file != null) {
                pendingFile = file
                pendingSinceElapsedMs = SystemClock.elapsedRealtime()
                requestKeyFrame?.onRequestKeyFrame()
            }
        }

        val keyFrame = videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME || isKeyFrame(videoBuffer)
        val nextFile = pendingFile
        if (nextFile != null) {
            if (!keyFrame) return
            val audioMissing = tracks != RecordController.RecordTracks.VIDEO && audioFormat == null
            if (audioMissing && millisSince(pendingSinceElapsedMs) < AUDIO_FORMAT_TIMEOUT_MS) {
                return // give the audio pipeline a bit longer; retried on the next keyframe.
            }
            if (audioMissing) {
                Log.w(TAG, "No audio format after ${AUDIO_FORMAT_TIMEOUT_MS}ms, starting $nextFile video-only")
            }
            if (!openMuxer(nextFile, videoInfo.presentationTimeUs)) return
            pendingFile = null
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            if (status == RecordController.Status.STARTED) {
                status = RecordController.Status.RECORDING
                listener?.onStatusChange(status)
            }
        }

        if (muxerStarted) writeSample(videoTrack, videoBuffer, videoInfo)
    }

    private fun millisSince(markElapsedMs: Long) = SystemClock.elapsedRealtime() - markElapsedMs

    private fun currentSegmentExpired(): Boolean {
        val durationMs = segmentDurationMinutes() * 60_000L
        return millisSince(segmentStartElapsedMs) >= durationMs
    }

    @Synchronized
    override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
        if (status != RecordController.Status.RECORDING || tracks == RecordController.RecordTracks.VIDEO) return
        if (muxerStarted) writeSample(audioTrack, audioBuffer, audioInfo)
    }

    @Synchronized
    override fun setVideoFormat(videoFormat: MediaFormat) {
        this.videoFormat = videoFormat
    }

    @Synchronized
    override fun setAudioFormat(audioFormat: MediaFormat) {
        this.audioFormat = audioFormat
    }

    @Synchronized
    override fun resetFormats() {
        videoFormat = null
        audioFormat = null
    }

    override fun isRunning() = status != RecordController.Status.STOPPED
    override fun isRecording() = status == RecordController.Status.RECORDING
    override fun getStatus() = status

    @Synchronized
    override fun setVideoCodec(videoCodec: VideoCodec) {
        this.videoCodec = videoCodec
    }

    @Synchronized
    override fun setAudioCodec(audioCodec: AudioCodec) {
        this.audioCodec = audioCodec
    }

    override fun getVideoCodec() = videoCodec
    override fun getAudioCodec() = audioCodec

    @Synchronized
    override fun updateInfo(videoCodec: VideoCodec, audioCodec: AudioCodec) {
        this.videoCodec = videoCodec
        this.audioCodec = audioCodec
    }

    @Synchronized
    override fun setRequestKeyFrame(requestKeyFrame: RecordController.RequestKeyFrame?) {
        this.requestKeyFrame = requestKeyFrame
    }

    @Synchronized
    override fun pauseRecord() {
        if (status == RecordController.Status.RECORDING) {
            status = RecordController.Status.PAUSED
            listener?.onStatusChange(status)
        }
    }

    @Synchronized
    override fun resumeRecord() {
        if (status == RecordController.Status.PAUSED) {
            status = RecordController.Status.RESUMED
            listener?.onStatusChange(status)
        }
    }

    /**
     * Always called from inside an already-@Synchronized method. Opens without an audio track
     * if [audioFormat] isn't available yet -- by the time this is called, [recordVideo] has
     * already decided (via [AUDIO_FORMAT_TIMEOUT_MS]) that it's not worth waiting any longer.
     */
    private fun openMuxer(file: File, firstSampleTimeUs: Long): Boolean {
        val vFormat = videoFormat ?: return false

        val newMuxer = try {
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open muxer for $file", e)
            listener?.onError(e)
            return false
        }
        val newVideoTrack = newMuxer.addTrack(vFormat)
        val newAudioTrack = if (tracks != RecordController.RecordTracks.VIDEO) {
            audioFormat?.let { newMuxer.addTrack(it) } ?: -1
        } else {
            -1
        }
        newMuxer.start()

        val finishedFile = closeCurrentMuxer()

        muxer = newMuxer
        muxerFile = file
        videoTrack = newVideoTrack
        audioTrack = newAudioTrack
        muxerStarted = true
        segmentStartUs = firstSampleTimeUs

        finishedFile?.let { f -> teardownScope.launch { onSegmentFinalized(f) } }
        return true
    }

    /** Always called from inside an already-@Synchronized method. */
    private fun closeCurrentMuxer(): File? {
        val oldMuxer = muxer ?: return null
        val oldFile = muxerFile
        muxer = null
        muxerFile = null
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
        try {
            oldMuxer.stop()
            oldMuxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close segment $oldFile", e)
        }
        return oldFile
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        val currentMuxer = muxer ?: return
        val rebased = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, maxOf(0L, info.presentationTimeUs - segmentStartUs), info.flags)
        }
        try {
            currentMuxer.writeSampleData(track, buffer, rebased)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData failed", e)
            listener?.onError(e)
        }
    }

    /** Mirrors RootEncoder's own NAL-sniffing fallback for encoders that don't flag keyframes. */
    private fun isKeyFrame(buffer: ByteBuffer): Boolean {
        val header = ByteArray(5)
        if (buffer.remaining() < header.size) return false
        buffer.duplicate().get(header, 0, header.size)
        return when (videoCodec) {
            VideoCodec.H264 -> (header[4].toInt() and 0x1F) == H264_IDR
            VideoCodec.H265 -> {
                val type = (header[4].toInt() shr 1) and 0x3F
                type == H265_IDR_W_RADL || type == H265_IDR_N_LP
            }
            else -> false
        }
    }
}
