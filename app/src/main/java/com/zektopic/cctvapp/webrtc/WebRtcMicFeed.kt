package com.zektopic.cctvapp.webrtc

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.webrtc.audio.ExternalAudioSource
import org.webrtc.audio.WebRtcAudioRecord

/**
 * Feeds WebRTC's real audio track with RootEncoder's live mic PCM, without ever opening a second
 * `android.media.AudioRecord` -- see `webrtc-audio-patch/org/webrtc/audio/WebRtcAudioRecord.java`'s
 * header comment for why that's necessary on this app's target hardware, and
 * [WebRtcEngine]/[com.zektopic.cctvapp.webrtc.WebRtcPreviewBridge] for how the resulting track gets
 * attached to each viewer's `PeerConnection`.
 *
 * Structurally this mirrors the now-removed `com.zektopic.cctvapp.audio.AudioStreamBridge` (same
 * [GetMicrophoneData] subscription, same idempotent [onStreamStarted] registration against
 * [SharedCameraStream]) -- but where that one just forwarded whatever chunk size RootEncoder
 * handed it straight to a browser over a WebSocket, WebRTC's patched `WebRtcAudioRecord` demands
 * *exactly* [CHUNK_BYTES] bytes (16kHz mono 16-bit PCM, 10ms) on every pull, so this does the
 * re-slicing: [inputPCMData] accumulates arbitrary-sized frames into a rolling [pending] buffer and
 * only ever enqueues whole [CHUNK_BYTES]-sized chunks.
 *
 * [CHUNK_BYTES] is only correct because [WebRtcEngine] pins the `JavaAudioDeviceModule` to
 * `setInputSampleRate(16000)`/`setUseStereoInput(false)` -- matching `CctvServerService`'s own
 * `stream.prepareAudio(sampleRate = 16000, isStereo = false, ...)` call -- and the patched
 * `WebRtcAudioRecord` hardcodes a 10ms callback period same as upstream. If either side of that
 * three-way agreement ever changes, [pollChunk] starts getting asked for a different `chunkBytes`
 * than this produces and silently returns silence instead (see its kdoc) -- there's no dynamic
 * resampling/rechunking here, by design, to keep this simple.
 */
object WebRtcMicFeed : GetMicrophoneData, ExternalAudioSource {

    /** 16kHz mono 16-bit PCM, 10ms -- see this object's kdoc for why this exact number is load-bearing. */
    private const val CHUNK_BYTES = 320

    /**
     * ~300ms of buffering. Confirmed on-device (diagnostic counters, since removed) that
     * RootEncoder's mic callback does *not* deliver one [CHUNK_BYTES] chunk every ~10ms -- it
     * arrives in much larger, infrequent bursts (consistent with AAC's 1024-sample/64ms frame
     * size: roughly 6-7 chunks' worth at once, then a gap), even though the *average* production
     * rate over time matches consumption almost exactly. An 8-chunk (80ms) queue was smaller than
     * a single burst, so every burst both overflowed (dropping most of its own chunks) and was
     * followed by a gap long enough to starve [pollChunk] into returning silence -- audible as
     * constant stutter. This capacity only needs to smooth out the burst-to-burst unevenness, not
     * compensate for a real rate mismatch (production already keeps up on average), so the added
     * latency is a fixed ~300ms, not something that grows over time.
     */
    private const val QUEUE_CAPACITY = 30

    private val SILENCE = ByteArray(CHUNK_BYTES)

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    /** Carries a partial chunk across [inputPCMData] calls when a frame's size isn't a multiple of [CHUNK_BYTES]. Only ever touched from the single RootEncoder mic callback thread -- see [inputPCMData]'s kdoc. */
    private val pending = ByteArray(CHUNK_BYTES)
    private var pendingLen = 0

    /** The exact [SharedCameraStream] instance this is currently registered on, if any -- see [onStreamStarted]. */
    private var registeredOn: SharedCameraStream? = null

    /** Sticky once true -- [WebRtcAudioRecord.setExternalAudioSource] only needs to be called once ever, since at most one [WebRtcEngine]/`JavaAudioDeviceModule` instance exists at a time (enforced by `WebRtcPreviewBridge`'s viewer-count lifecycle). */
    @Volatile private var wired = false

    /** Called once per successful `startStream()` -- listener registration is idempotent since the stream instance is a long-lived singleton, mirroring the removed `AudioStreamBridge.onStreamStarted`. */
    @Synchronized
    fun onStreamStarted(stream: SharedCameraStream) {
        if (registeredOn !== stream) {
            registeredOn = stream
            stream.addPcmDataListener(this)
        }
        if (!wired) {
            WebRtcAudioRecord.setExternalAudioSource(this)
            wired = true
        }
    }

    /**
     * Called from RootEncoder's shared camera callback thread -- must never block. Re-slices
     * whatever size frame RootEncoder hands over into whole [CHUNK_BYTES] chunks, carrying any
     * remainder in [pending] to be completed by the next call.
     */
    @Synchronized
    override fun inputPCMData(frame: Frame) {
        val buffer = frame.buffer
        var offset = frame.offset
        var remaining = frame.size

        if (pendingLen > 0) {
            val need = CHUNK_BYTES - pendingLen
            val take = minOf(need, remaining)
            System.arraycopy(buffer, offset, pending, pendingLen, take)
            pendingLen += take
            offset += take
            remaining -= take
            if (pendingLen == CHUNK_BYTES) {
                offerChunk(pending.copyOf())
                pendingLen = 0
            }
        }

        while (remaining >= CHUNK_BYTES) {
            offerChunk(buffer.copyOfRange(offset, offset + CHUNK_BYTES))
            offset += CHUNK_BYTES
            remaining -= CHUNK_BYTES
        }

        if (remaining > 0) {
            System.arraycopy(buffer, offset, pending, 0, remaining)
            pendingLen = remaining
        }
    }

    /** Drops the oldest queued chunk to make room rather than blocking the mic callback thread -- staying close to real time matters more than never losing a 10ms chunk under a momentary backlog. */
    private fun offerChunk(chunk: ByteArray) {
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
        }
    }

    /**
     * Called from WebRtcAudioRecord's own capture thread (see the patched
     * `WebRtcAudioRecord.AudioRecordThread.run()`). Falls back to silence -- never blocks past
     * [timeoutMs], never returns null or a mismatched length -- when nothing is queued yet (mic
     * hasn't started, no RECORD_AUDIO permission, or [onStreamStarted] was simply never called
     * because a viewer's WebRTC engine spun up before any camera stream did) or when [chunkBytes]
     * doesn't match [CHUNK_BYTES] (see this object's kdoc for when that can happen).
     */
    override fun pollChunk(chunkBytes: Int, timeoutMs: Int): ByteArray {
        val chunk = queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return SILENCE
        return if (chunk.size == chunkBytes) chunk else SILENCE
    }

    /** Only called from the service's `onDestroy()`. */
    @Synchronized
    fun release() {
        registeredOn?.removePcmDataListener(this)
        registeredOn = null
        queue.clear()
        pendingLen = 0
    }
}
