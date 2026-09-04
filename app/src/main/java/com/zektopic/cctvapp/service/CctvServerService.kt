package com.zektopic.cctvapp.service

import android.app.Service
import android.content.Intent
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
import android.util.Log
import android.view.SurfaceHolder
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.CodecUtil
import com.pedro.rtspserver.RtspServerCamera2
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.settings.AppPreferences
import com.zektopic.cctvapp.settings.ServiceSettings
import com.zektopic.cctvapp.settings.SettingsRepository
import com.zektopic.cctvapp.web.WebServer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CctvServerService"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"

        /** Capture cadence while the dashboard is open. */
        private const val ACTIVE_SNAPSHOT_INTERVAL_MS = 500L
        /** Heartbeat while nothing needs frames -- just enough to notice a new viewer. */
        private const val IDLE_SNAPSHOT_INTERVAL_MS = 3000L
        /** How long after the last /shot.jpg we keep treating a viewer as present. */
        private const val VIEWER_IDLE_TIMEOUT_MS = 10_000L
    }

    private lateinit var rtspServerCamera: RtspServerCamera2
    private var isSurfaceCreated = false

    private val overlayWindow by lazy { OverlayWindow(this) }

    /** [rtspServerCamera] once created, or null before the first [startStream] call. */
    private val cameraOrNull: RtspServerCamera2?
        get() = if (::rtspServerCamera.isInitialized) rtspServerCamera else null

    /** True only once the camera exists and is actively streaming. */
    private val isCameraStreaming: Boolean
        get() = cameraOrNull?.isStreaming == true

    private val settings: ServiceSettings get() = SettingsRepository.current
    private var isLanternOn = false

    /**
     * Backs [onMain] and every periodic/delayed loop in this service. Cancelled as a
     * whole in [onDestroy] instead of tracking every job individually.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Runs [block] on the main thread.
     *
     * Every WebServer callback arrives on a NanoHTTPD worker thread, but touching the
     * camera or the overlay view off the main thread throws (`updateViewLayout` raises
     * CalledFromWrongThreadException). Only the *side effects* are posted -- the state
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

    private fun startTimestampTicker() {
        timestampJob?.cancel()
        timestampJob = serviceScope.launch {
            while (isActive) {
                updateTimestampText()
                delay(1000.milliseconds)
            }
        }
    }

    private fun stopTimestampTicker() {
        timestampJob?.cancel()
        timestampJob = null
    }

    private val currentSnapshot = AtomicReference<ByteArray>(null)

    /** When a dashboard client last asked for /shot.jpg, for idle throttling. */
    @Volatile private var lastSnapshotRequestMs = 0L

    private fun startSnapshotLoop() {
        serviceScope.launch {
            while (isActive) {
                val streaming = isCameraStreaming && isSurfaceCreated

                val viewerActive =
                    System.currentTimeMillis() - lastSnapshotRequestMs < VIEWER_IDLE_TIMEOUT_MS
                val wanted = streaming && viewerActive

                if (wanted) takeSnapshot()

                delay((if (wanted) ACTIVE_SNAPSHOT_INTERVAL_MS else IDLE_SNAPSHOT_INTERVAL_MS).milliseconds)
            }
        }
    }

    private val galleryRecordingManager by lazy {
        GalleryRecordingManager(
            context = this,
            onMain = ::onMain,
            camera = { cameraOrNull },
            recordToGalleryEnabled = { settings.recordToGalleryEnabled },
            recordSegmentMinutes = { settings.recordSegmentMinutes },
            recordStorageThresholdPercent = { settings.recordStorageThresholdPercent }
        )
    }

    private val webServer: WebServer by lazy {
        WebServer(this, DeviceStatsUtil.getIpAddress(this),
            imageProvider = {
                // Record the demand so the capture loop keeps running while somebody is
                // actually watching, and kick it immediately -- otherwise the first frame
                // after an idle period comes back as "Camera not ready".
                lastSnapshotRequestMs = System.currentTimeMillis()
                if (currentSnapshot.get() == null) {
                    onMain { takeSnapshot() }
                }
                currentSnapshot.get()
            },
            onSwitchCamera = {
                onMain {
                    try {
                        cameraOrNull?.switchCamera()
                    } catch (e: Exception) {
                        Log.e(TAG, "switchCamera failed", e)
                    }
                }
            },
            onStartStream = {
                onMain {
                    if (isSurfaceCreated && !isCameraStreaming) {
                        startStream()
                    }
                }
            },
            onStopStream = {
                onMain {
                    if (isCameraStreaming) {
                        stopStreamAndRecording()
                    }
                }
            },
            isStreaming = { isCameraStreaming },
            getZoomRange = { currentZoomRangePair() },
            galleryRecordingManager = galleryRecordingManager
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "onCreate: pid=${android.os.Process.myPid()}")
        ServiceNotificationUtil.createNotificationChannel(this)

        // Load saved settings as defaults (a no-op if MainActivity already did).
        SettingsRepository.ensureLoaded(this)

        // Setup light sensor for night mode
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        overlayWindow.attach(this)

        webServer.start()
        startSnapshotLoop()

        // Started last, once everything it might touch (overlayWindow attached, webServer
        // constructed) already exists -- Dispatchers.Main.immediate can run a launch{}
        // body synchronously up through its first suspension point when already on the
        // main thread, so starting this any earlier risks its first (unconditional, see
        // startSettingsEffectsCollector) effects pass running against a half-built service.
        startSettingsEffectsCollector()
    }

    private fun startSettingsEffectsCollector() {
        serviceScope.launch {
            var previous: ServiceSettings? = null
            SettingsRepository.settings.collect { curr ->
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
                if (first || prev.showPreview != curr.showPreview) updateOverlaySize()
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
                    if (curr.recordToGalleryEnabled) startGalleryRecordingIfNeeded() else stopGalleryRecording()
                }
            }
        }
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private fun restartStreamIfRunning() {
        if (isCameraStreaming) {
            stopStreamAndRecording()
            startStream()
            applyZoom()
        }
    }

    private fun applyAuthorization() {
        if (!isCameraStreaming) return
        if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
            rtspServerCamera.getStreamClient().setAuthorization(settings.authUsername, settings.authPassword)
        } else {
            rtspServerCamera.getStreamClient().setAuthorization("", "")
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
        rtspServerCamera.stopStream()
    }

    private fun startGalleryRecordingIfNeeded() = galleryRecordingManager.startIfNeeded()

    private fun stopGalleryRecording() = galleryRecordingManager.stop()

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun takeSnapshot() {
        if (!isSurfaceCreated || !overlayWindow.glView.holder.surface.isValid) return
        try {
            overlayWindow.glView.takePhoto { bitmap ->
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                val jpeg = stream.toByteArray()
                currentSnapshot.set(jpeg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_SWITCH_CAMERA") {
            try {
                cameraOrNull?.switchCamera()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return START_STICKY
        }

        applyForegroundServiceType()

        // Initialize wrapper if needed
        if (!::rtspServerCamera.isInitialized) {
             rtspServerCamera = RtspServerCamera2(overlayWindow.glView, this, 8554)
        }

        if (isSurfaceCreated) {
            startStream()
        }

        // Apply flashlight, night mode, vertical flip and zoom after stream starts
        serviceScope.launch {
            delay(1000.milliseconds)
            applyFlashlight()
            updateNightModeSensor()
            applyVerticalFlip()
            applyZoom()
        }

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !overlayWindow.glView.holder.surface.isValid) return
        
        try {
            if (!::rtspServerCamera.isInitialized) {
                rtspServerCamera = RtspServerCamera2(overlayWindow.glView, this, 8554)
            }
            
            if (!rtspServerCamera.isStreaming) {
                val maxRes = getMaxCameraResolution()
                if (settings.videoWidth <= 0 || settings.videoHeight <= 0 ||
                    settings.videoWidth.toLong() * settings.videoHeight > maxRes.first.toLong() * maxRes.second
                ) {
                    Log.w(
                        TAG,
                        "Requested ${settings.videoWidth}x${settings.videoHeight} exceeds camera capability; using ${maxRes.first}x${maxRes.second}"
                    )
                    SettingsRepository.update(this) { it.copy(videoWidth = maxRes.first, videoHeight = maxRes.second) }
                }

                val bitrate = settings.bitrateKbps * 1024

                // Audio is opt-in. Recording it forces the microphone foreground-service
                // type and the RECORD_AUDIO grant; a camera-only stream needs neither.
                if (settings.audioEnabled && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    rtspServerCamera.prepareAudio(64 * 1024, 44100, true, false, false)
                } else {
                    rtspServerCamera.disableAudio()
                }

                // Check and set Codec
                val selectedCodec = when (settings.videoCodec) {
                    "H265" -> VideoCodec.H265
                    "AV1" -> VideoCodec.AV1
                    // "VP9" was offered in both UIs but silently fell back to H.264.
                    // It is gone from the pickers; this branch only catches stale prefs.
                    else -> VideoCodec.H264
                }
                
                rtspServerCamera.setVideoCodec(selectedCodec)
                Log.d(TAG, "Selected codec: $selectedCodec (${settings.videoCodec})")

                val codecType = CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                rtspServerCamera.forceCodecType(codecType, codecType)
                Log.d(TAG, "Codec type: $codecType")

                // Set authentication
                if (settings.authEnabled && settings.authUsername.isNotEmpty() && settings.authPassword.isNotEmpty()) {
                    rtspServerCamera.getStreamClient().setAuthorization(settings.authUsername, settings.authPassword)
                    Log.d(TAG, "RTSP auth enabled for user: ${settings.authUsername}")
                } else {
                    rtspServerCamera.getStreamClient().setAuthorization("", "")
                    Log.d(TAG, "RTSP auth disabled")
                }

                if (rtspServerCamera.prepareVideo(settings.videoWidth, settings.videoHeight, 30, bitrate, 0)) {
                    rtspServerCamera.startStream()
                    applyTimestampOverlay()
                    SettingsRepository.update(this) { it.copy(activeCodec = it.videoCodec) }
                    galleryRecordingManager.startIfNeeded()
                } else {
                    Log.w(TAG, "Codec $selectedCodec preparation failed, falling back to H264")
                    rtspServerCamera.setVideoCodec(VideoCodec.H264)
                    if (rtspServerCamera.prepareVideo(settings.videoWidth, settings.videoHeight, 30, bitrate, 0)) {
                         rtspServerCamera.startStream()
                         applyTimestampOverlay()

                         SettingsRepository.update(this) { it.copy(activeCodec = "H264") }
                         galleryRecordingManager.startIfNeeded()
                    } else {
                         Log.e(TAG, "H264 fallback preparation also failed.")
                    }
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
            cameraOrNull?.glInterface?.setFilter(filter)

            val fontSize = getOverlayFontSize()
            filter.setText(buildTimestampString(), fontSize, Color.WHITE, Typeface.DEFAULT_BOLD)

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

    private fun updateTimestampText() {
        val filter = textFilter ?: return
        if (!settings.showTimestamp) return
        try {
            filter.setText(buildTimestampString(), getOverlayFontSize(), Color.WHITE, Typeface.DEFAULT_BOLD)
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

    private fun buildTimestampString(): String {
        val now = Date()
        val parts = mutableListOf<String>()
        if (settings.showTimestamp) {
            parts.add(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
            parts.add(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now))
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
        DeviceStatsUtil.getCpuTemperatureCelsius()?.let { temp ->
            parts.add("%.0f°C".format(Locale.getDefault(), temp))
        }
        return parts.joinToString(" ")
    }

    private fun getMaxCameraResolution(): Pair<Int, Int> =
        CameraResolutionUtil.getSupportedResolutions(this).firstOrNull() ?: Pair(1920, 1080)

    private fun applyFlashlight() {
        if (!isCameraStreaming) return
        try {
            if (settings.flashlightEnabled && !isLanternOn) {
                rtspServerCamera.enableLantern()
                isLanternOn = true
                Log.d(TAG, "Flashlight ON")
            } else if (!settings.flashlightEnabled && isLanternOn) {
                rtspServerCamera.disableLantern()
                isLanternOn = false
                Log.d(TAG, "Flashlight OFF")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    private fun applyVerticalFlip() {
        try {
            overlayWindow.setVerticalFlip(settings.verticalFlipEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set vertical flip", e)
        }
    }

    private fun applyZoom() {
        if (!isCameraStreaming) return
        try {
            val range = rtspServerCamera.zoomRange
            val clamped = settings.zoomLevel.coerceIn(range.lower, range.upper)
            rtspServerCamera.zoom = clamped
            Log.d(TAG, "Zoom set to $clamped (requested ${settings.zoomLevel}, range=$range)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set zoom", e)
        }
    }

    /**
     * The live camera session's zoom bounds once streaming (the most authoritative
     * source), else [CameraResolutionUtil.getZoomRange] queried straight from
     * CameraCharacteristics -- real hardware bounds either way, never a generic guess.
     */
    private fun currentZoomRangePair(): Pair<Float, Float> {
        return try {
            if (isCameraStreaming) {
                val r = rtspServerCamera.zoomRange
                Pair(r.lower, r.upper)
            } else CameraResolutionUtil.getZoomRange(this)
        } catch (_: Exception) {
            CameraResolutionUtil.getZoomRange(this)
        }
    }

    private fun applyBitrate() {
        if (!isCameraStreaming) return
        try {
            rtspServerCamera.setVideoBitrateOnFly(settings.bitrateKbps * 1024)
            Log.d(TAG, "Bitrate set to ${settings.bitrateKbps}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitrate", e)
        }
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!settings.nightModeEnabled) return
            val lux = event?.values?.get(0) ?: return
            val shouldEnableFlash = lux < 10f
            if (shouldEnableFlash != isLanternOn) {
                SettingsRepository.update(this@CctvServerService) { it.copy(flashlightEnabled = shouldEnableFlash) }
                applyFlashlight()
                Log.d(TAG, "Night mode: lux=$lux, flash=${if (shouldEnableFlash) "ON" else "OFF"}")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        startStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (cameraOrNull?.isStreaming == false) {
             startStream()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        if (isCameraStreaming) {
            stopStreamAndRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        sensorManager?.unregisterListener(lightSensorListener)
        galleryRecordingManager.shutdown()

        webServer.stop()

        try {
            if (isCameraStreaming) {
                rtspServerCamera.stopStream()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        overlayWindow.detach()

        // STOP_FOREGROUND_REMOVE needs API 24; the boolean overload covers API 23 too.
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun updateOverlaySize() {
        overlayWindow.setPreviewVisible(settings.showPreview)
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