package com.zektopic.cctvapp.audio

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges [SharedCameraStream]'s raw mic PCM into the dashboard's audio preview -- independent
 * of, and shared by, both the MSE and WebRTC video previews (see `audio-preview.js`). Mirrors
 * [com.zektopic.cctvapp.mse.MseVideoBridge]'s own structure closely, minus everything
 * video-specific (there's no keyframe/GOP dependency for audio, so no gating is needed).
 *
 * This exists as its own bridge, rather than each video preview muxing/relaying audio itself
 * (the previous design, see git history), because audio genuinely doesn't care which video
 * transport is active: RootEncoder's mic capture runs continuously regardless of whether the
 * dashboard is showing MSE or WebRTC video, so one shared subscription and one shared client-side
 * playback path is strictly simpler than two -- and it keeps [com.zektopic.cctvapp.webrtc.WebRtcPreviewBridge]
 * from ever needing a real WebRTC audio track, which would require a second concurrent
 * `AudioRecord` this app's target hardware's `AudioPolicyManager` outright refuses to grant
 * while [SharedCameraStream]'s own `MicrophoneSource` is already recording.
 *
 * Relays raw PCM, not RootEncoder's AAC-encoded output (tried first, see git history): the
 * browser schedules raw PCM directly via the Web Audio API with no decode step at all, which is
 * what keeps playback latency from silently accumulating. Both MediaSource/SourceBuffer (fed AAC,
 * muxed into fmp4 client-side) and the WebCodecs `AudioDecoder` API (also AAC) were tried and
 * reverted -- the former has no live-edge tracking of its own once audio isn't riding along
 * inside the video preview's own MediaSource/self-healing anymore, so buffered audio just grows
 * and playback drifts further behind real time forever; the latter is unavailable in some
 * browsers (Firefox, at the time this was written). Raw PCM's bandwidth cost (~256kbps at this
 * app's fixed 16kHz mono capture, 8x AAC's ~32kbps) is trivial next to the video already on the
 * same LAN connection.
 */
class AudioStreamBridge : GetMicrophoneData {

    private companion object {
        // Must match CctvServerService.kt's `stream.prepareAudio(sampleRate = 16000, isStereo =
        // false, ...)` -- this app never changes that at runtime, so it's hardcoded here exactly
        // like it is at that call site, rather than threading it through SharedCameraStream's
        // audioSource (RootEncoder's own AudioSource base class does expose sampleRate/isStereo
        // as public fields once initialized, but there's no public getter path from here to it).
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
    }

    /** Sticky once true -- flips as soon as the first real PCM [Frame] arrives, proving RootEncoder's `MicrophoneSource` is actually alive (RECORD_AUDIO granted, encoder started). */
    @Volatile private var hasAudio = false

    /** The exact [SharedCameraStream] instance this is currently registered on, if any -- see [onStreamStarted]. */
    private var registeredOn: SharedCameraStream? = null

    private val viewers = CopyOnWriteArrayList<AudioStreamSocket>()

    /** Everything a newly-connecting viewer needs to start scheduling PCM -- null until the first frame has arrived (or forever, if there's no `RECORD_AUDIO` permission -- see `NoAudioSource` in `CctvServerService`), in which case the preview should just stay silent. */
    data class ViewerHandshake(val sampleRate: Int, val channelCount: Int)

    fun snapshotForNewViewer(): ViewerHandshake? {
        if (!hasAudio) return null
        return ViewerHandshake(SAMPLE_RATE, CHANNEL_COUNT)
    }

    /** Called once per successful `startStream()`; listener registration is idempotent since the stream instance is a long-lived singleton. */
    fun onStreamStarted(stream: SharedCameraStream) {
        if (registeredOn === stream) return
        registeredOn = stream
        stream.addPcmDataListener(this)
    }

    /** Called from the shared camera callback thread -- must never block. */
    override fun inputPCMData(frame: Frame) {
        hasAudio = true
        if (viewers.isEmpty()) return
        viewers.forEach { it.enqueue(frame.buffer.copyOfRange(frame.offset, frame.offset + frame.size)) }
    }

    fun attach(socket: AudioStreamSocket) {
        viewers.add(socket)
    }

    fun detach(socket: AudioStreamSocket) {
        viewers.remove(socket)
    }

    /** Only called from the service's `onDestroy()`. */
    fun release() {
        registeredOn?.removePcmDataListener(this)
        registeredOn = null
        viewers.toList().forEach { it.closeQuietly("Server shutting down") }
    }
}
