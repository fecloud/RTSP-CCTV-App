package com.zektopic.cctvapp.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.common.isKeyframe
import com.pedro.common.toByteArray
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.video.GetVideoData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns [SharedCameraStream]'s H.264 + AAC output (delivered via [GetVideoData]/[GetAudioData],
 * the exact same callbacks RTSP send/recording get) into fragmented MP4 (ISO/IEC 14496-12) for
 * the dashboard's MSE (`MediaSource`/`SourceBuffer`) preview -- registered as a listener on
 * [SharedCameraStream] by `com.zektopic.cctvapp.mse.MseVideoBridge`, never constructed here.
 *
 * No vendored library (RootEncoder/RTSP-Server) writes fragmented MP4 -- only a non-fragmented
 * `MediaMuxer`-based recorder exists (`AndroidMuxerRecordController`/`SeamlessMp4RecordController`)
 * -- so this hand-rolls the small subset of ISOBMFF boxes MSE actually requires: an `ftyp`+`moov`
 * init segment (built once both the video SPS/PPS and the audio config are known, and rebuilt --
 * see [onInitSegmentChanged] -- whenever they actually change, i.e. a codec/resolution restart)
 * followed by one `moof`+`mdat` fragment per incoming video NAL or audio access unit.
 *
 * Deliberately targets exactly what this app produces and no more: one H.264 (`avc1`) video
 * track and one AAC-LC (`mp4a`) audio track, two fixed track IDs, no B-frames/reordering (so no
 * composition-time-offset handling), no editable sample tables (`stts`/`stsc`/`stsz`/`stco` are
 * all empty -- every real sample lives in a fragment, as `mvex`'s presence signals).
 *
 * The client uses `sourceBuffer.mode = 'sequence'` (see dashboard.html): a new viewer's first
 * appended sample becomes local position 0 regardless of how many seconds of absolute `tfdt`
 * the server has already accumulated since the stream started -- `'segments'` mode (which
 * trusts each fragment's absolute `tfdt` as-is) was tried and confirmed on-device to leave a
 * newly connected viewer's `<video>` stuck waiting forever for buffered data at position 0 that
 * would never arrive, since a long-running stream's `tfdt` values are never anywhere near zero
 * by the time a new viewer joins. `'sequence'` mode's single shared `timestampOffset` is only
 * safe across two independently-timed tracks (video/audio) if their `tfdt`s stay mutually
 * consistent with each other -- which is exactly what [resetSession] guarantees, by resetting
 * both tracks' clocks together, once per real streaming session (confirmed on-device: without
 * that guarantee, an encoder quirk that redelivers [onVideoInfo] outside of a real restart could
 * silently desync video's clock from audio's, which played out as video buffering fine while
 * audio simply never played -- see [onVideoInfo]'s kdoc).
 */
class Fmp4Fragmenter(
    private val onFragment: (ByteArray) -> Unit,
) : GetVideoData, GetAudioData {

    private companion object {
        const val VIDEO_TRACK_ID = 1
        const val AUDIO_TRACK_ID = 2

        /** Arbitrary but standard choices -- high enough resolution for smooth duration math. */
        const val VIDEO_TIMESCALE = 90_000
        const val MOVIE_TIMESCALE = 1_000
        const val DEFAULT_FPS = 30

        /** AAC-LC always encodes exactly 1024 samples/frame -- exact, not a heuristic. */
        const val AAC_SAMPLES_PER_FRAME = 1_024L

        /** ISO/IEC 14496-12 8.8.3.1 `sample_flags`: sync sample (keyframe) vs. not. */
        const val SAMPLE_FLAGS_SYNC = 0x02000000
        const val SAMPLE_FLAGS_NON_SYNC = 0x01010000

        val UNITY_MATRIX: ByteArray = ByteArrayOutputStream().apply {
            write(u32(0x00010000)); write(u32(0)); write(u32(0))
            write(u32(0)); write(u32(0x00010000)); write(u32(0))
            write(u32(0)); write(u32(0)); write(u32(0x40000000))
        }.toByteArray()

        fun u8(v: Int): ByteArray = byteArrayOf(v.toByte())
        fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())
        fun u24(v: Int): ByteArray = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun u32(v: Int): ByteArray = u32(v.toLong() and 0xFFFFFFFFL)
        fun u32(v: Long): ByteArray =
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun u64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * (7 - i))).toByte() }

        fun box(type: String, vararg payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(8 + payload.sumOf { it.size })
            out.write(u32(8 + payload.sumOf { it.size }))
            out.write(type.toByteArray(Charsets.US_ASCII))
            payload.forEach { out.write(it) }
            return out.toByteArray()
        }

        /** MPEG-4 descriptor "expandable" size: 7 bits/byte, continuation bit set except the last. */
        fun descriptor(tag: Int, vararg payload: ByteArray): ByteArray {
            var length = payload.sumOf { it.size }
            val sizeBytes = ArrayList<Int>()
            do {
                sizeBytes.add(0, length and 0x7F)
                length = length ushr 7
            } while (length > 0)
            for (i in 0 until sizeBytes.size - 1) sizeBytes[i] = sizeBytes[i] or 0x80
            val out = ByteArrayOutputStream()
            out.write(tag)
            sizeBytes.forEach { out.write(it) }
            payload.forEach { out.write(it) }
            return out.toByteArray()
        }

        fun stripStartCode(nal: ByteArray): ByteArray = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte() ->
                nal.copyOfRange(4, nal.size)
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() ->
                nal.copyOfRange(3, nal.size)
            else -> nal
        }

        /** `avc1`/`avcC` samples need 4-byte length-prefixed NALs, not `H264PassthroughEncoder`'s Annex-B. */
        fun lengthPrefixed(nal: ByteArray): ByteArray {
            val out = ByteArray(4 + nal.size)
            out[0] = (nal.size ushr 24).toByte(); out[1] = (nal.size ushr 16).toByte()
            out[2] = (nal.size ushr 8).toByte(); out[3] = nal.size.toByte()
            System.arraycopy(nal, 0, out, 4, nal.size)
            return out
        }

        fun bufferToArray(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
            val dup = buffer.duplicate()
            dup.position(info.offset)
            dup.limit(info.offset + info.size)
            return ByteArray(dup.remaining()).also { dup.get(it) }
        }

        fun sampleEntryHeader(): ByteArray = ByteArray(6) + u16(1) // reserved(6) + data_reference_index=1

        fun ftyp(): ByteArray = box(
            "ftyp",
            "isom".toByteArray(Charsets.US_ASCII), u32(512),
            "isom".toByteArray(Charsets.US_ASCII), "iso2".toByteArray(Charsets.US_ASCII),
            "avc1".toByteArray(Charsets.US_ASCII), "mp41".toByteArray(Charsets.US_ASCII),
        )

        fun mvhd(timescale: Int): ByteArray = box(
            "mvhd", u8(0) + u24(0), u32(0), u32(0), u32(timescale), u32(0),
            u32(0x00010000), u16(0x0100) + u16(0), u32(0) + u32(0), UNITY_MATRIX,
            ByteArray(24), u32(AUDIO_TRACK_ID + 1),
        )

        fun tkhd(trackId: Int, width: Int, height: Int, volume: Int): ByteArray = box(
            "tkhd", u8(0) + u24(0x000007), u32(0), u32(0), u32(trackId), u32(0), u32(0),
            u32(0) + u32(0), u16(0), u16(0), u16(volume) + u16(0), UNITY_MATRIX,
            u32(width shl 16), u32(height shl 16),
        )

        fun mdhd(timescale: Int): ByteArray =
            box("mdhd", u8(0) + u24(0), u32(0), u32(0), u32(timescale), u32(0), u16(0x55C4), u16(0))

        fun hdlr(handlerType: String, name: String): ByteArray = box(
            "hdlr", u8(0) + u24(0), u32(0), handlerType.toByteArray(Charsets.US_ASCII), ByteArray(12),
            name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0),
        )

        fun vmhd(): ByteArray = box("vmhd", u8(0) + u24(1), u16(0), u16(0), u16(0), u16(0))
        fun smhd(): ByteArray = box("smhd", u8(0) + u24(0), u16(0), u16(0))

        fun dinf(): ByteArray {
            val url = box("url ", u8(0) + u24(1))
            return box("dinf", box("dref", u8(0) + u24(0), u32(1), url))
        }

        fun stsd(sampleEntry: ByteArray): ByteArray = box("stsd", u8(0) + u24(0), u32(1), sampleEntry)
        fun emptyStts(): ByteArray = box("stts", u8(0) + u24(0), u32(0))
        fun emptyStsc(): ByteArray = box("stsc", u8(0) + u24(0), u32(0))
        fun emptyStsz(): ByteArray = box("stsz", u8(0) + u24(0), u32(0), u32(0))
        fun emptyStco(): ByteArray = box("stco", u8(0) + u24(0), u32(0))
        fun stbl(stsdBox: ByteArray): ByteArray =
            box("stbl", stsdBox, emptyStts(), emptyStsc(), emptyStsz(), emptyStco())

        fun avcC(profileIdc: Int, compat: Int, levelIdc: Int, sps: ByteArray, pps: ByteArray): ByteArray = box(
            "avcC", u8(1), u8(profileIdc), u8(compat), u8(levelIdc), u8(0xFF), u8(0xE1),
            u16(sps.size), sps, u8(1), u16(pps.size), pps,
        )

        fun avc1(width: Int, height: Int, avcCBox: ByteArray): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(sampleEntryHeader())
            body.write(u16(0)); body.write(u16(0)); body.write(ByteArray(12))
            body.write(u16(width)); body.write(u16(height))
            body.write(u32(0x00480000)); body.write(u32(0x00480000)); body.write(u32(0))
            body.write(u16(1)); body.write(ByteArray(32))
            body.write(u16(0x0018)); body.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            return box("avc1", body.toByteArray(), avcCBox)
        }

        /** `avgBitrate`/`maxBitrate` here are advisory only -- browsers decode from the bitstream, not these. */
        fun esds(audioSpecificConfig: ByteArray, bitrateBps: Int): ByteArray {
            val decoderSpecificInfo = descriptor(0x05, audioSpecificConfig)
            val decoderConfig = descriptor(
                0x04, byteArrayOf(0x40), byteArrayOf(0x15), u24(0), u32(bitrateBps), u32(bitrateBps),
                decoderSpecificInfo,
            )
            val slConfig = descriptor(0x06, byteArrayOf(0x02))
            val esDescriptor = descriptor(0x03, u16(0), byteArrayOf(0x00), decoderConfig, slConfig)
            return box("esds", u8(0) + u24(0), esDescriptor)
        }

        fun mp4a(sampleRate: Int, channelCount: Int, esdsBox: ByteArray): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(sampleEntryHeader())
            body.write(u32(0)); body.write(u32(0))
            body.write(u16(channelCount)); body.write(u16(16)); body.write(u16(0)); body.write(u16(0))
            body.write(u32(sampleRate shl 16))
            return box("mp4a", body.toByteArray(), esdsBox)
        }

        fun mfhd(seq: Long): ByteArray = box("mfhd", u8(0) + u24(0), u32(seq))

        /** `default-base-is-moof` (0x020000) -- no other `tfhd` fields, [trun] carries everything per-sample. */
        fun tfhd(trackId: Int): ByteArray = box("tfhd", u8(0) + u24(0x020000), u32(trackId))

        /** Version 1 (64-bit) -- generous headroom for a 24/7 camera's accumulated decode time. */
        fun tfdt(baseMediaDecodeTime: Long): ByteArray = box("tfdt", u8(1) + u24(0), u64(baseMediaDecodeTime))

        fun trun(durationUnits: Long, sizeBytes: Int, sampleFlags: Int?, dataOffset: Int): ByteArray {
            var flags = 0x000001 or 0x000100 or 0x000200 // data-offset + duration + size present
            if (sampleFlags != null) flags = flags or 0x000400
            val parts = ArrayList<ByteArray>()
            parts += u8(0) + u24(flags)
            parts += u32(1) // sample_count
            parts += u32(dataOffset)
            parts += u32(durationUnits)
            parts += u32(sizeBytes)
            if (sampleFlags != null) parts += u32(sampleFlags)
            return box("trun", *parts.toTypedArray())
        }

        fun trex(trackId: Int): ByteArray =
            box("trex", u8(0) + u24(0), u32(trackId), u32(1), u32(0), u32(0), u32(0))

        /** Builds `moof`+`mdat` for a single sample, computing `trun`'s `data_offset` from the fixed-size boxes around it. */
        fun buildFragment(
            seq: Long, trackId: Int, baseMediaDecodeTime: Long, durationUnits: Long,
            sampleBytes: ByteArray, sampleFlags: Int?,
        ): ByteArray {
            val mfhdBox = mfhd(seq)
            val tfhdBox = tfhd(trackId)
            val tfdtBox = tfdt(baseMediaDecodeTime)
            val trunPlaceholder = trun(durationUnits, sampleBytes.size, sampleFlags, dataOffset = 0)
            val trafSize = 8 + tfhdBox.size + tfdtBox.size + trunPlaceholder.size
            val moofSize = 8 + mfhdBox.size + trafSize
            val trunBox = trun(durationUnits, sampleBytes.size, sampleFlags, dataOffset = moofSize + 8)
            val trafBox = box("traf", tfhdBox, tfdtBox, trunBox)
            val moofBox = box("moof", mfhdBox, trafBox)
            return moofBox + box("mdat", sampleBytes)
        }
    }

    @Volatile private var spsBytes: ByteArray? = null
    @Volatile private var ppsBytes: ByteArray? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var audioSpecificConfig: ByteArray? = null
    @Volatile private var sampleRate = 44_100
    @Volatile private var channelCount = 2

    /** The current fmp4 `ftyp`+`moov`, once both tracks' config is known -- null before that. */
    @Volatile var initSegment: ByteArray? = null
        private set

    /** The most recent video-keyframe fragment -- replayed to new viewers so they don't wait out the IDR interval. */
    @Volatile var lastKeyframeFragment: ByteArray? = null
        private set

    /** The full `MediaSource.addSourceBuffer()` MIME type string, e.g. `video/mp4; codecs="avc1.640028,mp4a.40.2"`. */
    @Volatile var codecsString: String? = null
        private set

    /** Set by `MseVideoBridge` -- fired when [initSegment] is rebuilt due to a real codec/resolution change. */
    var onInitSegmentChanged: (() -> Unit)? = null

    private val initLock = Any()
    private var lastSpsForInit: ByteArray? = null
    private var lastAudioConfigForInit: ByteArray? = null

    private val sequenceNumber = AtomicLong(1)
    @Volatile private var videoBaseUs = -1L
    @Volatile private var lastVideoPtsUs = -1L
    @Volatile private var videoDecodeTimeUnits = 0L
    @Volatile private var audioDecodeTimeUnits = 0L

    /** Called by `MseVideoBridge` once `SharedCameraStream.prepareVideo()` has succeeded. */
    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    /**
     * Called by `MseVideoBridge.onStreamStarted` -- once per actual `SharedCameraStream
     * .startStream()` call, the one signal that reliably means "a new streaming session begins"
     * (see [onVideoInfo]'s kdoc for why that callback firing isn't a reliable signal for this).
     * Resets both tracks' clocks together so they can never end up on the mismatched timelines
     * that caused audio to silently never play.
     */
    fun resetSession() {
        videoBaseUs = -1L
        lastVideoPtsUs = -1L
        videoDecodeTimeUnits = 0L
        audioDecodeTimeUnits = 0L
        lastKeyframeFragment = null
        sequenceNumber.set(1)
    }

    /**
     * Confirmed on-device: some encoders redeliver `onVideoInfo` (SPS/PPS) more than once per
     * streaming session -- observed here specifically right after [requestKeyframe] (called
     * for every newly joined viewer) -- without a genuine `prepareVideo()` restart. Resetting
     * the video timeline unconditionally on every call used to desync it from the audio
     * timeline (which only ever resets once, in [onAudioFormat]): video would snap back to
     * zero on every new viewer join while audio kept climbing, leaving the two tracks on
     * unrelated clocks that MSE could never reconcile -- video degraded gracefully (Chrome
     * just re-buffers), audio simply never played. The timeline is now only reset from
     * `MseVideoBridge.onStreamStarted` (see [resetSession]), a signal tied to an actual
     * `SharedCameraStream.startStream()` call rather than to how many times an encoder
     * happens to redeliver its config.
     */
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        spsBytes = stripStartCode(sps.toByteArray())
        ppsBytes = pps?.toByteArray()?.let { stripStartCode(it) }
        rebuildInitSegmentIfNeeded()
    }

    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val nal = stripStartCode(bufferToArray(videoBuffer, info))
        val isKey = info.isKeyframe()
        if (videoBaseUs < 0) videoBaseUs = info.presentationTimeUs
        val ptsUs = info.presentationTimeUs - videoBaseUs
        val durationUs = if (lastVideoPtsUs < 0) 1_000_000L / DEFAULT_FPS else (ptsUs - lastVideoPtsUs).coerceAtLeast(1L)
        lastVideoPtsUs = ptsUs
        val durationUnits = durationUs * VIDEO_TIMESCALE / 1_000_000L
        val fragment = buildFragment(
            seq = sequenceNumber.getAndIncrement(), trackId = VIDEO_TRACK_ID,
            baseMediaDecodeTime = videoDecodeTimeUnits, durationUnits = durationUnits,
            sampleBytes = lengthPrefixed(nal), sampleFlags = if (isKey) SAMPLE_FLAGS_SYNC else SAMPLE_FLAGS_NON_SYNC,
        )
        videoDecodeTimeUnits += durationUnits
        if (isKey) lastKeyframeFragment = fragment
        onFragment(fragment)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {}

    override fun onAudioFormat(mediaFormat: MediaFormat) {
        mediaFormat.getByteBuffer("csd-0")?.toByteArray()?.let { audioSpecificConfig = it }
        if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        rebuildInitSegmentIfNeeded()
    }

    override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val sampleBytes = bufferToArray(audioBuffer, info)
        val fragment = buildFragment(
            seq = sequenceNumber.getAndIncrement(), trackId = AUDIO_TRACK_ID,
            baseMediaDecodeTime = audioDecodeTimeUnits, durationUnits = AAC_SAMPLES_PER_FRAME,
            sampleBytes = sampleBytes, sampleFlags = null,
        )
        audioDecodeTimeUnits += AAC_SAMPLES_PER_FRAME
        onFragment(fragment)
    }

    private fun rebuildInitSegmentIfNeeded() = synchronized(initLock) {
        val sps = spsBytes ?: return
        val pps = ppsBytes ?: return
        val audioConfig = audioSpecificConfig ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val changed = !sps.contentEquals(lastSpsForInit) || !audioConfig.contentEquals(lastAudioConfigForInit)
        if (initSegment != null && !changed) return
        val hadInitSegment = initSegment != null
        lastSpsForInit = sps
        lastAudioConfigForInit = audioConfig
        initSegment = buildInitSegment(sps, pps, audioConfig)
        codecsString = buildCodecsString(sps)
        lastKeyframeFragment = null
        sequenceNumber.set(1)
        if (hadInitSegment) onInitSegmentChanged?.invoke()
    }

    private fun buildCodecsString(sps: ByteArray): String {
        val profileIdc = sps.getOrElse(1) { 0 }.toInt() and 0xFF
        val compat = sps.getOrElse(2) { 0 }.toInt() and 0xFF
        val levelIdc = sps.getOrElse(3) { 0 }.toInt() and 0xFF
        return "video/mp4; codecs=\"avc1.%02x%02x%02x,mp4a.40.2\"".format(profileIdc, compat, levelIdc)
    }

    private fun buildInitSegment(sps: ByteArray, pps: ByteArray, audioConfig: ByteArray): ByteArray {
        val profileIdc = sps.getOrElse(1) { 0 }.toInt() and 0xFF
        val compat = sps.getOrElse(2) { 0 }.toInt() and 0xFF
        val levelIdc = sps.getOrElse(3) { 0 }.toInt() and 0xFF

        val avc1Box = avc1(videoWidth, videoHeight, avcC(profileIdc, compat, levelIdc, sps, pps))
        val minfVideo = box("minf", vmhd(), dinf(), stbl(stsd(avc1Box)))
        val mdiaVideo = box("mdia", mdhd(VIDEO_TIMESCALE), hdlr("vide", "VideoHandler"), minfVideo)
        val trakVideo = box("trak", tkhd(VIDEO_TRACK_ID, videoWidth, videoHeight, volume = 0), mdiaVideo)

        val mp4aBox = mp4a(sampleRate, channelCount, esds(audioConfig, bitrateBps = 64 * 1024))
        val minfAudio = box("minf", smhd(), dinf(), stbl(stsd(mp4aBox)))
        val mdiaAudio = box("mdia", mdhd(sampleRate), hdlr("soun", "SoundHandler"), minfAudio)
        val trakAudio = box("trak", tkhd(AUDIO_TRACK_ID, 0, 0, volume = 0x0100), mdiaAudio)

        val mvex = box("mvex", trex(VIDEO_TRACK_ID), trex(AUDIO_TRACK_ID))
        val moov = box("moov", mvhd(MOVIE_TIMESCALE), trakVideo, trakAudio, mvex)
        return ftyp() + moov
    }
}
