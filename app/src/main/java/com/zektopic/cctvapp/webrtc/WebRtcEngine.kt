package com.zektopic.cctvapp.webrtc

import android.content.Context
import android.view.Surface
import com.pedro.library.view.preview.MultiPreviewConfig
import com.zektopic.cctvapp.log.AppLog as Log
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Owns the actual WebRTC resources for exactly one [WebRtcPreviewBridge] "at least one viewer
 * is attached" span: the [PeerConnectionFactory], the shared video/audio source+track pair, and
 * the [com.pedro.library.view.GlStreamInterface] preview surface feeding the video source. A
 * fresh instance is created when [WebRtcPreviewBridge] gets its first viewer and torn down via
 * [stop] once its last one disconnects -- never reused across that span, since starting one does
 * real, non-idempotent work (allocates a `Surface`, registers it with `GlStreamInterface`, opens
 * a microphone via [JavaAudioDeviceModule]).
 */
class WebRtcEngine(context: Context, private val stream: SharedCameraStream) {

    private companion object {
        private const val TAG = "WebRtcEngine"
        private const val VIDEO_TRACK_ID = "cctv-video"
        private const val AUDIO_TRACK_ID = "cctv-audio"
    }

    val factory: PeerConnectionFactory
    val videoTrack: VideoTrack
    val audioTrack: AudioTrack

    private val eglBase: EglBase
    private val surfaceTextureHelper: SurfaceTextureHelper
    private val previewSurface: Surface
    private val videoSource: VideoSource
    private val audioSource: AudioSource

    init {
        Log.d(TAG, "Starting engine")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )

        val base = EglBase.create()
        eglBase = base

        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    // libwebrtc's NetworkMonitorAutoDetect doesn't recognize a phone's own
                    // SoftAP interface -- without this, a viewer connecting over the phone's
                    // own hotspot can silently fail ICE.
                    disableNetworkMonitor = true
                }
            )
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
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

        val aSource = factory.createAudioSource(MediaConstraints())
        audioSource = aSource
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, aSource)

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
        audioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
        eglBase.release()
    }
}
