package com.zektopic.cctvapp.webrtc

import android.content.Context
import android.view.Surface
import com.pedro.library.view.preview.MultiPreviewConfig
import com.pedro.rtspserver.RtspServerStream
import com.zektopic.cctvapp.log.AppLog as Log
import java.util.concurrent.CopyOnWriteArrayList
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Bridges RootEncoder's shared camera GL pipeline into WebRTC.
 *
 * One instance is created lazily the first time
 * [CctvServerService][com.zektopic.cctvapp.service.CctvServerService] starts streaming and
 * lives for as long as the service does, published via [WebRtcBus] -- [attachToStream] is
 * idempotent and re-runs after every stream restart (codec/resolution change), while the
 * underlying [PeerConnectionFactory]/tracks are only created once and torn down once, in
 * [release] (service `onDestroy()` only).
 *
 * Every viewer (one browser tab = one WebSocket = one [PeerConnection], see
 * `.web`/`.webrtc`'s signaling code) shares the same [videoTrack]/[audioTrack] -- there is
 * exactly one camera/mic tap regardless of how many browsers are watching.
 */
class WebRtcVideoBridge(appContext: Context) {

    companion object {
        private const val TAG = "WebRtcVideoBridge"
    }

    private val eglBase: EglBase by lazy { EglBase.create() }

    private val audioDeviceModule: JavaAudioDeviceModule by lazy {
        JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
    }

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /** Its own dedicated GL thread/EGL context -- independent of RootEncoder's, see [attachToStream]. */
    private val surfaceTextureHelper: SurfaceTextureHelper by lazy {
        SurfaceTextureHelper.create("WebRtcCameraTap", eglBase.eglBaseContext)
    }

    private val videoSourceInternal: VideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val audioSourceInternal: AudioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }

    /** Shared by every viewer's [PeerConnection] -- add the same instance to each one. */
    val videoTrack: VideoTrack by lazy { peerConnectionFactory.createVideoTrack("cctv_video0", videoSourceInternal) }
    val audioTrack: AudioTrack by lazy { peerConnectionFactory.createAudioTrack("cctv_audio0", audioSourceInternal) }

    private var previewSurface: Surface? = null
    private val activePeerConnections = CopyOnWriteArrayList<PeerConnection>()

    /**
     * `RtspServerStream`'s single "primary" preview slot (attached via `startPreview()`)
     * never actually delivers a frame to a headless (non-display) `Surface` -- confirmed
     * on-device: attaching a WebRTC `SurfaceTextureHelper`-backed `Surface` there produces
     * zero frames despite every setup step succeeding without error, while the exact same
     * kind of `Surface` DOES receive frames via `addPreviewSurface`'s multi-preview path.
     * `startPreview()` still has to be called once (on some surface) to flip `isOnPreview`
     * true -- `addPreviewSurface` throws otherwise -- so this tiny 1x1 sink exists purely to
     * satisfy that precondition.
     *
     * An `ImageReader`-backed sink used to fill this role, but it logged a continuous
     * `ImageReader_JNI: Acquire image failed with error: Unknown error -2` flood, one per
     * frame, for as long as the stream ran (confirmed on-device via logcat) -- `ImageReader`
     * defaults to CPU-readable usage, which conflicts with the opaque/GPU-only buffers
     * RootEncoder's GL renderer actually writes here, so every `acquireLatestImage()` failed
     * at the native layer even though the precondition-flipping itself worked. A
     * `SurfaceTexture`-backed sink (via its own dedicated `SurfaceTextureHelper`) matches the
     * GPU-only usage the producer expects, so frames drain cleanly with no log spam. The
     * listener below must NOT call `frame.release()` itself --
     * `SurfaceTextureHelper.tryDeliverTextureFrame` already releases the frame once the
     * listener returns (a listener only needs `frame.retain()` if it wants to keep the frame
     * past that point); calling `release()` again here double-releases it and crashes with
     * `IllegalStateException: release() called on an object with refcount < 1` (confirmed
     * on-device).
     */
    private var dummyPreviewHelper: SurfaceTextureHelper? = null
    private var dummyPreviewSurface: Surface? = null

    fun factory(): PeerConnectionFactory = peerConnectionFactory

    /** Called by each viewer's session right after creating its [PeerConnection]. */
    fun registerPeerConnection(pc: PeerConnection) {
        activePeerConnections.add(pc)
    }

    /** Called by each viewer's session on WebSocket close. */
    fun unregisterPeerConnection(pc: PeerConnection) {
        activePeerConnections.remove(pc)
    }

    /**
     * Idempotent -- safe to call after every successful `RtspServerStream.startStream()`,
     * including on a settings-triggered restart.
     *
     * Taps the camera feed via `addPreviewSurface`'s multi-preview mechanism (see
     * [dummyPreviewHelper]'s kdoc for why not the primary preview slot).
     * [SurfaceTextureHelper.startListening] delivers each frame back as a ready-made
     * [org.webrtc.VideoFrame], handed straight to [VideoSource.getCapturerObserver] -- no
     * manual texture-to-frame conversion needed.
     */
    fun attachToStream(stream: RtspServerStream, width: Int, height: Int) {
        // SurfaceTextureHelper's own javadoc: "do not call setDefaultBufferSize() yourself
        // since this class needs to be aware of the texture size" -- calling
        // surfaceTexture.setDefaultBufferSize() directly instead of this leaves its internal
        // textureWidth/textureHeight at 0, and tryDeliverTextureFrame() silently drops every
        // frame ("Texture size has not been set.") forever. Confirmed on-device: this was the
        // entire reason zero frames ever reached WebRTC despite the GL tap being attached
        // correctly.
        surfaceTextureHelper.setTextureSize(width, height)
        val surface = previewSurface ?: Surface(surfaceTextureHelper.surfaceTexture).also { previewSurface = it }

        try {
            if (!stream.isOnPreview) {
                val helper = dummyPreviewHelper
                    ?: SurfaceTextureHelper.create("WebRtcDummyPrimaryPreview", eglBase.eglBaseContext).also {
                        it.setTextureSize(1, 1)
                        it.startListening {}
                        dummyPreviewHelper = it
                    }
                val dummySurface = dummyPreviewSurface ?: Surface(helper.surfaceTexture).also { dummyPreviewSurface = it }
                stream.startPreview(dummySurface, 1, 1)
            }
            if (!stream.hasMultiPreviewSurface(surface)) {
                stream.addPreviewSurface(surface, MultiPreviewConfig(width = width, height = height))
                surfaceTextureHelper.startListening { frame ->
                    videoSourceInternal.capturerObserver.onFrameCaptured(frame)
                }
                videoSourceInternal.capturerObserver.onCapturerStarted(true)
                Log.d(TAG, "WebRTC preview tap attached (${width}x$height)")
            } else {
                // Already attached from a previous startStream() -- just keep the
                // multi-preview config current across a resolution change.
                stream.updateMultiPreviewConfig(surface, MultiPreviewConfig(width = width, height = height))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach WebRTC preview tap", e)
        }
    }

    /**
     * Must run *before* `RtspServerStream.stopStream()`, while it's still streaming --
     * `StreamBase.prepareVideo()` (called again by the next `startStream()`, e.g. after a
     * codec/resolution change) throws unless `isOnPreview` is false, so this has to clear
     * that flag ahead of time. Calling `stopPreview()` while still streaming only flips the
     * flag and detaches the preview surface -- it does NOT stop the shared GL pipeline or
     * camera (`StreamBase.stopSources()`'s own `glInterface.stop()`/`videoSource.stop()`
     * calls are what would tear that down, and those are skipped while still streaming), so
     * no camera/GL churn happens here, just the flag reset that unblocks the next
     * `prepareVideo()`.
     */
    fun detachBeforeStop(stream: RtspServerStream) {
        try {
            if (stream.isOnPreview) {
                videoSourceInternal.capturerObserver.onCapturerStopped()
                surfaceTextureHelper.stopListening()
                previewSurface?.let { stream.removeMultiPreviewSurface(it) }
                stream.stopPreview()
                Log.d(TAG, "WebRTC preview tap detached ahead of stopStream()")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach WebRTC preview tap before stopStream", e)
        }
    }

    /** Only called from the service's `onDestroy()` -- see class kdoc. */
    fun release() {
        activePeerConnections.forEach { runCatching { it.close() } }
        activePeerConnections.clear()
        runCatching { surfaceTextureHelper.stopListening() }
        runCatching { videoTrack.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { videoSourceInternal.dispose() }
        runCatching { audioSourceInternal.dispose() }
        runCatching { surfaceTextureHelper.dispose() }
        runCatching { peerConnectionFactory.dispose() }
        runCatching { audioDeviceModule.release() }
        runCatching { eglBase.release() }
        runCatching { dummyPreviewHelper?.stopListening() }
        runCatching { dummyPreviewHelper?.dispose() }
        runCatching { dummyPreviewSurface?.release() }
        dummyPreviewHelper = null
        dummyPreviewSurface = null
        previewSurface = null
    }
}
