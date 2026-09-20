package com.zektopic.cctvapp.webrtc

import android.content.Context
import android.view.Surface
import com.pedro.library.view.preview.MultiPreviewConfig
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the actual WebRTC resources for exactly one [WebRtcPreviewBridge] "at least one viewer
 * is attached" span: the [PeerConnectionFactory], the shared video source+track, and the
 * [com.pedro.library.view.GlStreamInterface] preview surface feeding it. A fresh instance is
 * created when [WebRtcPreviewBridge] gets its first viewer and torn down via [stop] once its
 * last one disconnects -- never reused across that span, since starting one does real,
 * non-idempotent work (allocates a `Surface`, registers it with `GlStreamInterface`).
 *
 * Deliberately video-only: this app never creates a WebRTC audio track/`AudioSource` at all --
 * see [WebRtcPreviewBridge]'s kdoc for why (this device's mic hardware only tolerates one
 * concurrent `AudioRecord`, and WebRTC's Java SDK has no way to feed a send track from anything
 * other than its own `AudioDeviceModule`-owned `AudioRecord` -- confirmed on-device: wiring
 * `JavaAudioDeviceModule.Builder.setSamplesReadyCallback`/`AudioRecordStateCallback` and manually
 * invoking the stored callback with externally-sourced PCM does not feed WebRTC's encoder or stop
 * `WebRtcAudioRecord`'s own independent `AudioRecord.startRecording()` attempt -- that callback
 * fires only as a passive notification *after* a real capture already succeeded, per its own
 * javadoc: "This should only be set for debug purposes"). Audio instead rides a `RTCDataChannel`
 * relaying RootEncoder's own mic capture, entirely outside this class.
 */
class WebRtcEngine(context: Context, private val stream: SharedCameraStream) {

    private companion object {
        private const val TAG = "WebRtcEngine"
        private const val VIDEO_TRACK_ID = "cctv-video"
    }

    val factory: PeerConnectionFactory
    val videoTrack: VideoTrack

    private val eglBase: EglBase
    private val surfaceTextureHelper: SurfaceTextureHelper
    private val previewSurface: Surface
    private val videoSource: VideoSource

    init {
        Log.d(TAG, "Starting engine")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )

        val base = EglBase.create()
        eglBase = base

        factory = PeerConnectionFactory.builder()
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    // libwebrtc's NetworkMonitorAutoDetect doesn't recognize a phone's own
                    // SoftAP interface -- without this, a viewer connecting over the phone's
                    // own hotspot can silently fail ICE.
                    disableNetworkMonitor = true
                }
            )
            // No .setAudioDeviceModule(...) -- PeerConnectionFactory.Builder auto-creates a
            // default one internally when none is set (confirmed in its source), and since this
            // app never creates an audio source/track from this factory, that default ADM's
            // AudioRecord is never started. Explicitly building one here would be pointless and
            // would invite someone to "helpfully" wire it into an audio track later, recreating
            // the mic-contention crash this design avoids.
            .setVideoEncoderFactory(HardwareH26xVideoEncoderFactory(base.eglBaseContext))
            // This app never receives video (the browser's transceiver is recvonly), so this
            // looked safe to leave unset -- it isn't. Leaving it null crashes the whole process
            // (SIGABRT, "front() called on an empty vector" deep in libjingle_peerconnection_so's
            // worker_thread) the instant a PeerConnection negotiates ANY video m-line, regardless
            // of codec -- confirmed on-device: an audio-only offer negotiates fine with this
            // unset, a video offer reliably crashes, and setting a real decoder factory here
            // (even though it's never actually invoked) makes the identical video offer negotiate
            // cleanly. Root-caused against a stripped, symbol-less prebuilt native lib
            // (io.getstream:stream-webrtc-android:1.3.10, no newer release exists) via `adb
            // logcat` tombstones, not source -- so treat "always set both factories" as a load-
            // bearing rule for this library, not just a style preference.
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory()

        val helper = SurfaceTextureHelper.create("WebRtcCaptureThread", base.eglBaseContext)
        surfaceTextureHelper = helper
        // Without this, the SurfaceTexture's buffer queue never gets a real size and
        // SurfaceTextureHelper silently drops every frame ("Texture size has not been
        // set" in logcat) -- confirmed on-device as the actual cause of a "connected but
        // zero RTP" preview. Camera2Capturer et al. always call this before the first
        // frame for the same reason; addMultiPreviewSurface -> GlStreamInterface renders
        // at this same size by default (MultiPreviewConfig's width/height default to 0,
        // which it resolves to the encoder's own size), so match that here explicitly.
        val settings = ServiceStateRepository.settings
        helper.setTextureSize(settings.videoWidth, settings.videoHeight)

        val source = factory.createVideoSource(false)
        videoSource = source
        val firstFrameLogged = AtomicBoolean(false)
        helper.startListening { frame ->
            if (firstFrameLogged.compareAndSet(false, true)) {
                Log.d(TAG, "First camera frame captured for WebRTC preview (${frame.rotatedWidth}x${frame.rotatedHeight})")
            }
            source.capturerObserver.onFrameCaptured(frame)
        }
        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source)

        // Defaults (width/height 0, isPortrait false) match the primary encoder surface's own
        // GlStreamInterface config in SharedCameraStream.prepareVideo (always called with
        // rotation=0, so isPortrait ends up false there too) -- the camera texture is already
        // orientation-corrected upstream of this render (see CctvServerService.applyVerticalFlip's
        // kdoc), so this preview surface just needs to match that same config, not re-derive it.
        val surface = Surface(helper.surfaceTexture)
        previewSurface = surface
        val glInterface = stream.getGlInterface()
        glInterface.addMultiPreviewSurface(surface, MultiPreviewConfig())
        // addMultiPreviewSurface() silently no-ops if GlStreamInterface's own EGL surface
        // isn't ready yet -- this confirms whether it actually took, since there's no
        // return value to check otherwise.
        Log.d(TAG, "Preview surface registered with GlStreamInterface: ${glInterface.hasMultiPreviewSurface(surface)}")
    }

    /** Releases everything [init] allocated, in reverse order. Must be called exactly once. */
    fun stop() {
        Log.d(TAG, "Stopping engine")
        stream.getGlInterface().removeMultiPreviewSurface(previewSurface)
        previewSurface.release()
        surfaceTextureHelper.stopListening()
        surfaceTextureHelper.dispose()
        videoTrack.dispose()
        videoSource.dispose()
        factory.dispose()
        eglBase.release()
    }
}

/**
 * [DefaultVideoEncoderFactory] (hardware + software fallback) will happily negotiate VP8/VP9 --
 * Chrome's default offer puts VP8 first, and on chipsets without a hardware VP8 path libwebrtc
 * falls back to a software (CPU) encoder, which defeats the point of this app re-encoding raw
 * camera frames just for a browser preview. RTSP/MSE already commit this app to H264 (and this
 * app's own hardware encoder never touches VP8/VP9 either), so pin WebRTC to hardware H264/H265
 * only by filtering [HardwareVideoEncoderFactory]'s codec list down to those two and never
 * falling back to software VP8/VP9 at all.
 */
private class HardwareH26xVideoEncoderFactory(eglContext: EglBase.Context) : VideoEncoderFactory {
    private companion object {
        private val ALLOWED_CODEC_NAMES = setOf("H264", "H265")
    }

    private val hardware = HardwareVideoEncoderFactory(eglContext, false, true)

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = hardware.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        hardware.supportedCodecs.filter { codec ->
            ALLOWED_CODEC_NAMES.any { it.equals(codec.name, ignoreCase = true) }
        }.toTypedArray()
}
