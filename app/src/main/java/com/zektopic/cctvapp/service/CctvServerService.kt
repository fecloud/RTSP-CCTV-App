package com.zektopic.cctvapp.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.zektopic.cctvapp.log.AppLog as Log
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.CodecUtil
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.settings.ServiceRuntimeState
import com.zektopic.cctvapp.settings.ServiceSettings
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.mse.MseBus
import com.zektopic.cctvapp.mse.MseVideoBridge
import com.zektopic.cctvapp.streaming.SharedCameraStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class CctvServerService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "CctvServerService"
    }

    /** Null before the first [startStream] call. */
    private var sharedStream: SharedCameraStream? = null

    /**
     * Null until the first successful [startStream] -- created there, never recreated, and
     * torn down only in [onDestroy]. Published through [MseBus] so `WebServer`'s
     * WebSocket handler (a different package, owned by `CctvApplication` rather than this
     * service) can reach it.
     */
    private var mseBridge: MseVideoBridge? = null

    private fun ensureMseBridge(): MseVideoBridge = mseBridge ?: MseVideoBridge().also {
        mseBridge = it
        MseBus.bridge.set(it)
    }

    /** True only once the camera exists and is actively streaming. */
    private val isCameraStreaming: Boolean
        get() = sharedStream?.isStreaming == true

    private val settings: ServiceSettings get() = ServiceStateRepository.settings

    private var isLanternOn = false

    /**
     * Backs every periodic/delayed loop in this service. Cancelled as a whole in
     * [onDestroy] instead of tracking every job individually.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var textFilter: TextFilterRender? = null

    /** Ticks [updateTimestampText] every second while the timestamp overlay is enabled. */
    private var timestampJob: Job? = null

    /** Owned by this service alone -- constructed on first use, torn down in [onDestroy]. */
    private val recordingManager by lazy {
        RecordingManager(
            context = this, camera = { sharedStream }
        )
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!settings.nightModeEnabled) return
            val lux = event?.values?.get(0) ?: return
            val shouldEnableFlash = lux < 10f
            if (shouldEnableFlash != isLanternOn) {
                ServiceStateRepository.updateSettings(this@CctvServerService) { it.copy(flashlightEnabled = shouldEnableFlash) }
                applyFlashlight()
                Log.d(TAG, "Night mode: lux=$lux, flash=${if (shouldEnableFlash) "ON" else "OFF"}")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun startTimestampTicker() {
        timestampJob?.cancel()
        timestampJob = serviceScope.launch {
            while (isActive) {
                // The only blocking part of a tick: DeviceStatsUtil.getCpuTemperatureCelsius()
                // reads kernel thermal-zone sysfs files, which must not happen on this
                // Dispatchers.Main.immediate scope, so it's the one call hopped to IO.
                val cpuTempCelsius = withContext(Dispatchers.IO) { DeviceStatsUtil.getCpuTemperatureCelsius() }
                updateTimestampText(cpuTempCelsius)
                delay(1000.milliseconds)
            }
        }
    }

    private fun stopTimestampTicker() {
        timestampJob?.cancel()
        timestampJob = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "onCreate")
        ServiceNotificationUtil.createNotificationChannel(this)

        // Load saved settings as defaults (a no-op if MainActivity or CctvApplication
        // already did).
        ServiceStateRepository.ensureLoaded(this)

        // Setup light sensor for night mode
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Started last, once everything they might touch already exists --
        // Dispatchers.Main.immediate can run a launch{} body synchronously up through its
        // first suspension point when already on the main thread, so starting this any
        // earlier risks startSettingsEffectsCollector's first (unconditional, see its
        // own doc) effects pass running against a half-built service.
        // startRuntimeCommandsCollector has no such unconditional first pass (it only
        // reacts to actual diffs), but starts here too for the same "everything exists
        // first" reasoning.
        startSettingsEffectsCollector()
        startRuntimeCommandsCollector()
    }

    private fun startSettingsEffectsCollector() {
        serviceScope.launch {
            var previous: ServiceSettings? = null
            ServiceStateRepository.settingsFlow.collect { curr ->
                val prev = previous
                previous = curr
                val first = prev == null

                if (first || prev.showTimestamp != curr.showTimestamp ||
                    prev.timestampPosition != curr.timestampPosition || prev.timestampSize != curr.timestampSize
                ) {
                    applyTimestampOverlay()
                }
                if (first || prev.flashlightEnabled != curr.flashlightEnabled) applyFlashlight()
                if (first || prev.nightModeEnabled != curr.nightModeEnabled) updateNightModeSensor()
                if (first || prev.verticalFlipEnabled != curr.verticalFlipEnabled) applyVerticalFlip()
                if (first || prev.zoomLevel != curr.zoomLevel) applyZoom()
                if (first || prev.bitrateKbps != curr.bitrateKbps) applyBitrate()
                if (first || prev.authEnabled != curr.authEnabled ||
                    prev.authUsername != curr.authUsername || prev.authPassword != curr.authPassword
                ) {
                    applyAuthorization()
                }
                if (first) applyForegroundServiceType()

                // State-transition triggers: pure diff only, never on `first` (see doc).
                if (!first && (prev.videoCodec != curr.videoCodec || prev.videoWidth != curr.videoWidth ||
                        prev.videoHeight != curr.videoHeight || prev.videoFps != curr.videoFps)
                ) {
                    restartStreamIfRunning()
                }
                if (!first && prev.recordToGalleryEnabled != curr.recordToGalleryEnabled) {
                    if (curr.recordToGalleryEnabled) recordingManager.startIfNeeded() else recordingManager.stop()
                }
            }
        }
    }

    /**
     * Separate from [startSettingsEffectsCollector] on purpose -- reacts to
     * [ServiceStateRepository.runtimeFlow] (see [ServiceRuntimeState]'s kdoc), which is its
     * own `StateFlow` so these dashboard-triggered commands never get diffed together
     * with actual persisted settings.
     */
    private fun startRuntimeCommandsCollector() {
        serviceScope.launch {
            var previous: ServiceRuntimeState? = null
            ServiceStateRepository.runtimeFlow.collect { curr ->
                val prev = previous
                previous = curr
                val first = prev == null

                // Counters, not booleans, so a repeat request isn't conflated away by
                // StateFlow's no-op equality check.
                if (!first && prev.startStreamRequest != curr.startStreamRequest && !isCameraStreaming) {
                    startStream()
                }
                if (!first && prev.stopStreamRequest != curr.stopStreamRequest && isCameraStreaming) {
                    stopStreamAndRecording()
                }
                if (!first && prev.switchCameraRequest != curr.switchCameraRequest) {
                    try {
                        sharedStream?.camera2Source?.switchCamera()
                    } catch (e: Exception) {
                        Log.e(TAG, "switchCamera failed", e)
                    }
                }
            }
        }
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private suspend fun restartStreamIfRunning() {
        if (isCameraStreaming) {
            stopStreamAndRecording()
            startStream()
        }
    }

    private fun applyAuthorization() {
        val camera = sharedStream?.takeIf { it.isStreaming } ?: return
        if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
            camera.setRtspAuthorization(settings.authUsername, settings.authPassword)
        } else {
            camera.setRtspAuthorization("", "")
        }
    }

    /**
     * Stops the RTSP stream and, if a gallery segment is in flight, the recording too --
     * recording must never survive a `prepareVideo()` call, which `startStream()` may make
     * next. Replaces the bare `sharedStream.stopStream()` call so every stop site tears
     * down recording the same way instead of each needing its own reminder to do so.
     */
    private suspend fun stopStreamAndRecording() {
        recordingManager.stop()
        sharedStream?.stopStream()
        // The ticker (and the filter it updates) is tied to this stream's camera/GL
        // session -- without this it keeps calling setText on a filter belonging to a
        // now-torn-down session every second until the stream restarts.
        // applyTimestampOverlay() recreates both from scratch on the next start.
        stopTimestampTicker()
        textFilter = null
        ServiceStateRepository.updateRuntime { it.copy(isStreaming = false) }
    }

    /**
     * Publishes live status right after a successful `startStream()`.
     *
     * [source] is [SharedCameraStream.camera2Source], the same instance for the object's
     * whole lifetime (see [SharedCameraStream]'s kdoc).
     */
    private fun publishStreamingStatus(source: Camera2Source, activeCodec: String) {
        val range = source.getZoomRange()
        ServiceStateRepository.updateSettings(this) { it.copy(activeCodec = activeCodec) }
        ServiceStateRepository.updateRuntime { it.copy(isStreaming = true, zoomRange = range.lower to range.upper) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyForegroundServiceType()

        // startStream() does its own lazy-init of sharedStream; no need to duplicate
        // that check here. It also applies flashlight/vertical-flip/zoom itself once the
        // stream is actually up -- nightModeEnabled needs no equivalent call here since
        // updateNightModeSensor() only touches the sensor, already registered by
        // startSettingsEffectsCollector's first emission.
        startStream()

        return START_STICKY
    }

    private fun startStream() {
        try {
            val stream = sharedStream ?: run {
                val newCamera2Source = Camera2Source(this)
                val hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                val audioSource = if (hasAudioPermission) MicrophoneSource() else NoAudioSource()
                SharedCameraStream(this, 8554, this, newCamera2Source, audioSource).also {
                    sharedStream = it
                    recordingManager.attachTo(it)
                }
            }

            if (!stream.isStreaming) {
                val maxRes = getMaxCameraResolution()
                if (settings.videoWidth <= 0 || settings.videoHeight <= 0 ||
                    settings.videoWidth.toLong() * settings.videoHeight > maxRes.first.toLong() * maxRes.second
                ) {
                    Log.w(
                        TAG,
                        "Requested ${settings.videoWidth}x${settings.videoHeight} exceeds camera capability; using ${maxRes.first}x${maxRes.second}"
                    )
                    ServiceStateRepository.updateSettings(this) { it.copy(videoWidth = maxRes.first, videoHeight = maxRes.second) }
                }

                val bitrate = settings.bitrateKbps * 1024

                stream.prepareAudio(sampleRate = 16000, isStereo = false, bitrate = 32 * 1024)

                // Check and set Codec
                val selectedCodec = when (settings.videoCodec) {
                    "H265" -> VideoCodec.H265
                    // "VP9" and "AV1" were offered in both UIs but are now gone from the
                    // pickers; this branch only catches stale prefs, falling back to H.264.
                    else -> VideoCodec.H264
                }

                stream.setVideoCodec(selectedCodec)
                Log.d(TAG, "Selected codec: $selectedCodec (${settings.videoCodec})")

                val codecType = CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                stream.forceCodecType(codecType, codecType)
                Log.d(TAG, "Codec type: $codecType")

                // Set authentication
                if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
                    stream.setRtspAuthorization(settings.authUsername, settings.authPassword)
                    Log.d(TAG, "RTSP auth enabled for user: ${settings.authUsername}")
                } else {
                    stream.setRtspAuthorization("", "")
                    Log.d(TAG, "RTSP auth disabled")
                }

                val activeCodec = if (stream.prepareVideo(
                        width = settings.videoWidth, height = settings.videoHeight, bitrate = bitrate, fps = settings.videoFps, rotation = 0
                    )
                ) {
                    settings.videoCodec
                } else {
                    Log.w(TAG, "Codec $selectedCodec preparation failed, falling back to H264")
                    stream.setVideoCodec(VideoCodec.H264)
                    if (stream.prepareVideo(
                            width = settings.videoWidth, height = settings.videoHeight, bitrate = bitrate, fps = settings.videoFps, rotation = 0
                        )
                    ) "H264" else null
                }

                if (activeCodec != null) {
                    stream.startStream()
                    applyTimestampOverlay()
                    applyVerticalFlip()
                    applyFlashlight()
                    applyZoom()
                    publishStreamingStatus(stream.camera2Source, activeCodec = activeCodec)
                    recordingManager.startIfNeeded()
                    // fmp4/MSE only knows how to describe an `avc1` (H264) bitstream -- H265 can
                    // still stream over RTSP, it just can't feed the dashboard's browser preview.
                    if (activeCodec == "H264") {
                        ensureMseBridge().onStreamStarted(stream)
                    } else {
                        mseBridge?.onUnsupportedCodec()
                    }
                } else {
                    Log.e(TAG, "H264 fallback preparation also failed.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startStream Error", e)
        }
    }

    private fun applyTimestampOverlay() {
        if (!settings.showTimestamp) {
            stopTimestampTicker()
            textFilter = null
            return
        }

        try {
            val filter = TextFilterRender()
            sharedStream?.getGlInterface()?.setFilter(filter)

            val fontSize = getOverlayFontSize()
            // No CPU temp yet: this runs on the main thread, so it can't call
            // DeviceStatsUtil.getCpuTemperatureCelsius() itself (see startTimestampTicker),
            // and DeviceStatsUtil no longer caches a value to peek instead. The overlay
            // shows without one for at most a second, until startTimestampTicker()'s first
            // tick reads one in the background and fills it in.
            filter.setText(buildTimestampString(cpuTempCelsius = null), fontSize, Color.WHITE, Typeface.DEFAULT_BOLD)

            val scaleW = when (settings.timestampSize) {
                "Small" -> 14f
                "Large" -> 26f
                else -> 19f
            }
            val scaleH = when (settings.timestampSize) {
                "Small" -> 2.5f
                "Large" -> 5f
                else -> 3.5f
            }
            filter.setScale(scaleW, scaleH)

            // Position is the box's TOP-LEFT corner as a percentage of the frame, so
            // the right/bottom anchors have to account for the box's own width/height
            // or they drift away from that edge (or, at the old scale, past it
            // entirely). Margin matches the left/top inset used below.
            val margin = 2f
            when (settings.timestampPosition) {
                "Top Left" -> filter.setPosition(margin, margin)
                "Top Right" -> filter.setPosition(100f - scaleW - margin, margin)
                "Bottom Left" -> filter.setPosition(margin, 100f - scaleH - margin)
                "Bottom Right" -> filter.setPosition(100f - scaleW - margin, 100f - scaleH - margin)
            }

            textFilter = filter
            startTimestampTicker()
            Log.d(TAG, "Timestamp overlay applied at ${settings.timestampPosition}, size=${settings.timestampSize}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply timestamp overlay", e)
        }
    }

    private fun updateTimestampText(cpuTempCelsius: Float?) {
        val filter = textFilter ?: return
        if (!settings.showTimestamp) return
        try {
            filter.setText(buildTimestampString(cpuTempCelsius), getOverlayFontSize(), Color.WHITE, Typeface.DEFAULT_BOLD)
        } catch (e: Exception) {
            // Ignore - filter may not be ready
            Log.e(TAG, "updateTimestampText skipped, filter not ready", e)
        }
    }

    private fun getOverlayFontSize(): Float {
        return when (settings.timestampSize) {
            "Small" -> 16f
            "Large" -> 30f
            else -> 22f  // Medium
        }
    }

    /**
     * [cpuTempCelsius] is passed in rather than read here because this is called from the
     * main thread (both [applyTimestampOverlay]'s initial render and every
     * [updateTimestampText] tick) -- unlike [DeviceStatsUtil.getBatteryLevel] and
     * [DeviceStatsUtil.getBatteryTemperatureCelsius] (cheap Binder/sticky-broadcast reads),
     * [DeviceStatsUtil.getCpuTemperatureCelsius] reads kernel sysfs files and must not be
     * called from here -- see [startTimestampTicker].
     */
    private fun buildTimestampString(cpuTempCelsius: Float?): String {
        val now = Date()
        val parts = mutableListOf<String>()
        if (settings.showTimestamp) {
            parts.add(timestampFormat.format(now))
        }
        val batteryLevel = DeviceStatsUtil.getBatteryLevel(this)
        if (batteryLevel >= 0) {
            // Battery temp rides in the same part as the charge level (no separate
            // "BATT" label) -- position alone makes the grouping obvious, and every
            // character here was making the fixed-width overlay box more cramped.
            val batteryTemp = DeviceStatsUtil.getBatteryTemperatureCelsius(this)
                ?.let { " %.0f°C".format(Locale.getDefault(), it) } ?: ""
            parts.add("$batteryLevel%$batteryTemp")
        }
        cpuTempCelsius?.let { temp ->
            parts.add("%.0f°C".format(Locale.getDefault(), temp))
        }
        return parts.joinToString(" ")
    }

    private fun getMaxCameraResolution(): Pair<Int, Int> =
        CameraResolutionUtil.getSupportedResolutions(this).firstOrNull() ?: Pair(1920, 1080)

    private fun applyFlashlight() {
        if (sharedStream?.isStreaming != true) return
        val source = sharedStream?.camera2Source ?: return
        try {
            if (settings.flashlightEnabled && !isLanternOn) {
                source.enableLantern()
                isLanternOn = true
                Log.d(TAG, "Flashlight ON")
            } else if (!settings.flashlightEnabled && isLanternOn) {
                source.disableLantern()
                isLanternOn = false
                Log.d(TAG, "Flashlight OFF")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    /**
     * Rotates the raw camera texture 180 degrees for an upside-down mount.
     *
     * Earlier attempts used `GlInterface.setIsStream{Horizontal,Vertical}Flip`, which
     * flip at the final screen composite -- *after* filters (including the timestamp
     * overlay) are drawn -- so the burned-in text got flipped/mirrored along with the
     * image (confirmed on a real device, through two different wrong fixes). `setRotation`
     * instead reaches `MainRender.setCameraRotation` -> `cameraRender.setRotation`, the
     * same pre-filter stage `OpenGlView.setCameraFlip` used to touch, so anything drawn by
     * a filter afterward is composited onto an already-corrected frame and needs no
     * compensation of its own.
     *
     * `startStream()` always calls `prepareVideo(..., rotation = 0)`, which
     * `Camera2Base.prepareGlView` maps to `glInterface.setRotation(270)` for portrait
     * capture (`rotation == 0 ? 270 : rotation - 90`, decompiled from RootEncoder 2.7.2).
     * This overrides that with the same value plus 180 (mod 360) instead of guessing at
     * a "flip" independently of that mapping.
     */
    private fun applyVerticalFlip() {
        try {
            sharedStream?.getGlInterface()?.setRotation(if (settings.verticalFlipEnabled) 90 else 270)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set vertical flip", e)
        }
    }

    private fun applyZoom() {
        if (sharedStream?.isStreaming != true) return
        val source = sharedStream?.camera2Source ?: return
        try {
            val range = source.getZoomRange()
            val clamped = settings.zoomLevel.coerceIn(range.lower, range.upper)
            source.setZoom(clamped)
            Log.d(TAG, "Zoom set to $clamped (requested ${settings.zoomLevel}, range=$range)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom", e)
        }
    }

    private fun applyBitrate() {
        val camera = sharedStream?.takeIf { it.isStreaming } ?: return
        try {
            camera.setVideoBitrateOnFly(settings.bitrateKbps * 1024)
            Log.d(TAG, "Bitrate set to ${settings.bitrateKbps}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitrate", e)
        }
    }

    private fun updateNightModeSensor() {
        sensorManager?.unregisterListener(lightSensorListener)
        if (settings.nightModeEnabled && lightSensor != null) {
            sensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d(TAG, "Night mode sensor registered")
        } else {
            Log.d(TAG, "Night mode sensor unregistered")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy")
        serviceScope.cancel()
        sensorManager?.unregisterListener(lightSensorListener)
        recordingManager.shutdown()

        try {
            if (isCameraStreaming) {
                sharedStream?.stopStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopStream on destroy failed", e)
        }
        mseBridge?.release()
        mseBridge = null
        MseBus.bridge.set(null)
        ServiceStateRepository.updateRuntime { it.copy(isStreaming = false) }

        // STOP_FOREGROUND_REMOVE needs API 24; the boolean overload covers API 23 too.
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    /**
     * The foreground-service notification. A small icon is mandatory -- without one the
     * notification renders blank or is dropped outright, which for a camera that is
     * recording is both a usability and a transparency problem.
     */
    /**
     * (Re-)declares which restricted resources this foreground service touches.
     *
     * The declared type must cover every one of them: recording audio under a
     * camera-only type throws SecurityException on Android 14+.
     */
    private fun applyForegroundServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(
                ServiceNotificationUtil.SERVER_NOTIFICATION_ID,
                ServiceNotificationUtil.buildServerNotification(this),
                serviceType
            )
        } else {
            startForeground(
                ServiceNotificationUtil.SERVER_NOTIFICATION_ID,
                ServiceNotificationUtil.buildServerNotification(this)
            )
        }
    }

    // ConnectChecker methods
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "Connection success")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "New bitrate: $bitrate")
    }

    override fun onDisconnect() {
        Log.d(TAG, "Disconnected")
    }

    override fun onAuthError() {
        Log.e(TAG, "Auth error")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "Auth success")
    }
}