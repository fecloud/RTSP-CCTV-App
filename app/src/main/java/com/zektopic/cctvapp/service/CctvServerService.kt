package com.zektopic.cctvapp.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.CodecUtil
import com.pedro.rtspserver.RtspServerCamera2
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.settings.ServiceRuntimeState
import com.zektopic.cctvapp.settings.ServiceSettings
import com.zektopic.cctvapp.settings.ServiceStateRepository
import java.io.ByteArrayOutputStream
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

        /** Capture cadence while the dashboard is open. */
        private const val ACTIVE_SNAPSHOT_INTERVAL_MS = 500L
        /** Heartbeat while nothing needs frames -- just enough to notice a new viewer. */
        private const val IDLE_SNAPSHOT_INTERVAL_MS = 3000L
        /** How long after the last /shot.jpg we keep treating a viewer as present. */
        private const val VIEWER_IDLE_TIMEOUT_MS = 10_000L
    }

    /** Null before the first [startStream] call. */
    private var rtspServerCamera: RtspServerCamera2? = null

    /** True only once the camera exists and is actively streaming. */
    private val isCameraStreaming: Boolean
        get() = rtspServerCamera?.isStreaming == true

    private val settings: ServiceSettings get() = ServiceStateRepository.settings

    private var isLanternOn = false

    /**
     * Backs [onMain] and every periodic/delayed loop in this service. Cancelled as a
     * whole in [onDestroy] instead of tracking every job individually.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Runs [block] on the main thread.
     *
     * Every WebServer callback arrives on a NanoHTTPD worker thread, but the camera
     * calls this serializes onto the main thread are kept there out of caution rather
     * than a documented requirement. Only the *side effects* are posted -- the state
     * fields themselves are assigned synchronously by the caller, so a request that
     * changes a setting still reflects the new value by the time it responds.
     * `Dispatchers.Main.immediate` reproduces that exactly: launched from the main
     * thread it runs synchronously (no dispatch), launched from a worker thread it posts
     * to the main looper.
     */
    private fun onMain(block: () -> Unit) {
        serviceScope.launch { block() }
    }
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var textFilter: TextObjectFilterRender? = null

    /** Ticks [updateTimestampText] every second while the timestamp overlay is enabled. */
    private var timestampJob: Job? = null

    /** Runs [startSnapshotLoop]'s loop only while the camera is actually streaming. */
    private var snapshotJob: Job? = null

    /** Owned by this service alone -- constructed on first use, torn down in [onDestroy]. */
    private val galleryRecordingManager by lazy {
        GalleryRecordingManager(
            context = this,
            onMain = ::onMain,
            camera = { rtspServerCamera },
            recordToGalleryEnabled = { settings.recordToGalleryEnabled },
            recordSegmentMinutes = { settings.recordSegmentMinutes },
            recordStorageThresholdPercent = { settings.recordStorageThresholdPercent }
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

    /**
     * Reused across every [takeSnapshot] call instead of allocating a fresh
     * [ByteArrayOutputStream] every 500ms-3s -- pre-sized to a typical JPEG-at-quality-50
     * size to also avoid the internal buffer's own repeated grow-and-copy. Safe to reuse
     * because `glInterface.takePhoto`'s callback runs on RootEncoder's single-thread GL
     * executor, i.e. never concurrently with itself.
     */
    private val snapshotBuffer = ByteArrayOutputStream(64 * 1024)

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

    /**
     * Only runs while the camera is streaming -- there is nothing to snapshot otherwise,
     * so polling on a timer while stopped (previously done for the service's entire
     * lifetime, since [stopStreamAndRecording] does not stop the service itself) was a
     * pure waste of wakeups. Started from [publishStreamingStatus], stopped from
     * [stopStreamAndRecording].
     */
    private fun startSnapshotLoop() {
        snapshotJob?.cancel()
        snapshotJob = serviceScope.launch {
            while (isActive) {
                val viewerActive =
                    System.currentTimeMillis() - CameraRuntimeBus.lastSnapshotRequestMs < VIEWER_IDLE_TIMEOUT_MS

                if (viewerActive) takeSnapshot()

                delay((if (viewerActive) ACTIVE_SNAPSHOT_INTERVAL_MS else IDLE_SNAPSHOT_INTERVAL_MS).milliseconds)
            }
        }
    }

    private fun stopSnapshotLoop() {
        snapshotJob?.cancel()
        snapshotJob = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        ServiceNotificationUtil.createNotificationChannel(this)

        // Load saved settings as defaults (a no-op if MainActivity or CctvApplication
        // already did).
        ServiceStateRepository.ensureLoaded(this)

        // Before any new segment can start: sweep away rows left by a segment the
        // previous process never got to finish/discard (crash, OOM-kill, force-stop).
        galleryRecordingManager.cleanupOrphanedSegments()

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
                if (first || prev.audioEnabled != curr.audioEnabled) applyForegroundServiceType()

                // State-transition triggers: pure diff only, never on `first` (see doc).
                if (!first && (prev.videoCodec != curr.videoCodec || prev.videoWidth != curr.videoWidth ||
                        prev.videoHeight != curr.videoHeight || prev.audioEnabled != curr.audioEnabled)
                ) {
                    restartStreamIfRunning()
                }
                if (!first && prev.recordToGalleryEnabled != curr.recordToGalleryEnabled) {
                    if (curr.recordToGalleryEnabled) galleryRecordingManager.startIfNeeded() else galleryRecordingManager.stop()
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
                        rtspServerCamera?.switchCamera()
                    } catch (e: Exception) {
                        Log.e(TAG, "switchCamera failed", e)
                    }
                }
            }
        }
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private fun restartStreamIfRunning() {
        if (isCameraStreaming) {
            stopStreamAndRecording()
            startStream()
        }
    }

    private fun applyAuthorization() {
        val camera = rtspServerCamera?.takeIf { it.isStreaming } ?: return
        if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
            camera.getStreamClient().setAuthorization(settings.authUsername, settings.authPassword)
        } else {
            camera.getStreamClient().setAuthorization("", "")
        }
    }

    /**
     * Stops the RTSP stream and, if a gallery segment is in flight, the recording too --
     * recording must never survive a `prepareVideo()` call, which `startStream()` may make
     * next. Replaces the bare `rtspServerCamera.stopStream()` call so every stop site tears
     * down recording the same way instead of each needing its own reminder to do so.
     */
    private fun stopStreamAndRecording() {
        galleryRecordingManager.stop()
        rtspServerCamera?.stopStream()
        // The ticker (and the filter it updates) is tied to this stream's camera/GL
        // session -- without this it keeps calling setText on a filter belonging to a
        // now-torn-down session every second until the stream restarts.
        // applyTimestampOverlay() recreates both from scratch on the next start.
        stopTimestampTicker()
        stopSnapshotLoop()
        textFilter = null
        ServiceStateRepository.updateRuntime { it.copy(isStreaming = false) }
    }

    /** Publishes live status right after a successful `startStream()`. */
    private fun publishStreamingStatus(camera: RtspServerCamera2, activeCodec: String) {
        val range = camera.zoomRange
        ServiceStateRepository.updateSettings(this) { it.copy(activeCodec = activeCodec) }
        ServiceStateRepository.updateRuntime { it.copy(isStreaming = true, zoomRange = range.lower to range.upper) }
        startSnapshotLoop()
    }

    private fun takeSnapshot() {
        if (!isCameraStreaming) return
        try {
            rtspServerCamera?.glInterface?.takePhoto(::onSnapshotBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "takeSnapshot failed", e)
        }
    }

    /**
     * `takePhoto()` itself only stores this as a callback and returns immediately -- this
     * runs later, asynchronously, on RootEncoder's single-thread GL executor whenever it
     * next renders a frame. Needs its own try/catch separate from [takeSnapshot]'s (which
     * only covers registering the callback): that one can't see anything thrown from here.
     */
    private fun onSnapshotBitmap(bitmap: Bitmap) {
        try {
            snapshotBuffer.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, snapshotBuffer)
            val jpeg = snapshotBuffer.toByteArray()
            CameraRuntimeBus.currentSnapshot.set(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "takeSnapshot callback failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyForegroundServiceType()

        // startStream() does its own lazy-init of rtspServerCamera; no need to duplicate
        // that check here. It also applies flashlight/vertical-flip/zoom itself once the
        // stream is actually up -- nightModeEnabled needs no equivalent call here since
        // updateNightModeSensor() only touches the sensor, already registered by
        // startSettingsEffectsCollector's first emission.
        startStream()

        return START_STICKY
    }

    private fun startStream() {
        try {
            val camera = rtspServerCamera ?: RtspServerCamera2(this, this, 8554).also { rtspServerCamera = it }

            if (!camera.isStreaming) {
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

                // Audio is opt-in. Recording it forces the microphone foreground-service
                // type and the RECORD_AUDIO grant; a camera-only stream needs neither.
                if (settings.audioEnabled &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    camera.prepareAudio(64 * 1024, 44100, true, false, false)
                } else {
                    camera.disableAudio()
                }

                // Check and set Codec
                val selectedCodec = when (settings.videoCodec) {
                    "H265" -> VideoCodec.H265
                    // "VP9" and "AV1" were offered in both UIs but are now gone from the
                    // pickers; this branch only catches stale prefs, falling back to H.264.
                    else -> VideoCodec.H264
                }

                camera.setVideoCodec(selectedCodec)
                Log.d(TAG, "Selected codec: $selectedCodec (${settings.videoCodec})")

                val codecType = CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                camera.forceCodecType(codecType, codecType)
                Log.d(TAG, "Codec type: $codecType")

                // Set authentication
                if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
                    camera.getStreamClient().setAuthorization(settings.authUsername, settings.authPassword)
                    Log.d(TAG, "RTSP auth enabled for user: ${settings.authUsername}")
                } else {
                    camera.getStreamClient().setAuthorization("", "")
                    Log.d(TAG, "RTSP auth disabled")
                }

                val activeCodec = if (camera.prepareVideo(settings.videoWidth, settings.videoHeight, 30, bitrate, 0)) {
                    settings.videoCodec
                } else {
                    Log.w(TAG, "Codec $selectedCodec preparation failed, falling back to H264")
                    camera.setVideoCodec(VideoCodec.H264)
                    if (camera.prepareVideo(settings.videoWidth, settings.videoHeight, 30, bitrate, 0)) "H264" else null
                }

                if (activeCodec != null) {
                    camera.startStream()
                    applyTimestampOverlay()
                    applyVerticalFlip()
                    applyFlashlight()
                    applyZoom()
                    publishStreamingStatus(camera, activeCodec = activeCodec)
                    galleryRecordingManager.startIfNeeded()
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
            val filter = TextObjectFilterRender()
            rtspServerCamera?.glInterface?.setFilter(filter)

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
        val camera = rtspServerCamera?.takeIf { it.isStreaming } ?: return
        try {
            if (settings.flashlightEnabled && !isLanternOn) {
                camera.enableLantern()
                isLanternOn = true
                Log.d(TAG, "Flashlight ON")
            } else if (!settings.flashlightEnabled && isLanternOn) {
                camera.disableLantern()
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
            rtspServerCamera?.glInterface?.setRotation(if (settings.verticalFlipEnabled) 90 else 270)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set vertical flip", e)
        }
    }

    private fun applyZoom() {
        val camera = rtspServerCamera?.takeIf { it.isStreaming } ?: return
        try {
            val range = camera.zoomRange
            val clamped = settings.zoomLevel.coerceIn(range.lower, range.upper)
            camera.zoom = clamped
            Log.d(TAG, "Zoom set to $clamped (requested ${settings.zoomLevel}, range=$range)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom", e)
        }
    }

    private fun applyBitrate() {
        val camera = rtspServerCamera?.takeIf { it.isStreaming } ?: return
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
        serviceScope.cancel()
        sensorManager?.unregisterListener(lightSensorListener)
        galleryRecordingManager.shutdown()

        try {
            if (isCameraStreaming) {
                rtspServerCamera?.stopStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopStream on destroy failed", e)
        }
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
     * camera-only type throws SecurityException on Android 14+. Calling
     * startForeground again on an already-foreground service updates the type in
     * place, which is what lets the audio toggle take effect without a restart.
     */
    private fun applyForegroundServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (settings.audioEnabled) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
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