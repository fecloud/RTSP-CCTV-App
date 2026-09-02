package com.zektopic.cctvapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.base.recording.RecordController
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera1
import com.pedro.rtspserver.RtspServerCamera2
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CctvServerChannel"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"

        /** Capture cadence while the dashboard is open. */
        private const val ACTIVE_SNAPSHOT_INTERVAL_MS = 500L
        /** Heartbeat while nothing needs frames -- just enough to notice a new viewer. */
        private const val IDLE_SNAPSHOT_INTERVAL_MS = 3000L
        /** How long after the last /shot.jpg we keep treating a viewer as present. */
        private const val VIEWER_IDLE_TIMEOUT_MS = 10_000L

        /** Where gallery recording segments are saved, relative to the shared Movies collection. */
        private const val RECORDING_RELATIVE_PATH = "Movies/CCTVApp/"

        /**
         * True when a kernel thermal-zone `type` string looks like it belongs to the
         * CPU/SoC die rather than the battery, PMIC, or a static throttling threshold.
         *
         * Zone naming for the SoC/CPU die is not standardized across vendors -- newer
         * Qualcomm SoCs use "cpu0"/"cpu-thermal", but older ones (e.g. Snapdragon 820,
         * "capricorn") expose only "tsens_tz_sensorN" zones with no "cpu" substring at
         * all, which silently produced zero readings. "-hw-trip-" zones (seen on
         * Qualcomm SoCs, e.g. "cpu-hw-trip-0") report the configured throttling
         * threshold, not a live sensor reading -- a static ~95 C that swamped every
         * real core reading (~48-51 C) once both matched "cpu".
         */
        internal fun isCpuThermalZone(type: String): Boolean {
            val normalized = type.lowercase(Locale.ROOT)
            return ("cpu" in normalized || "tsens" in normalized) && "trip" !in normalized
        }

        /**
         * Converts a raw kernel thermal-zone reading for zone [type] to Celsius.
         *
         * The kernel thermal sysfs ABI specifies millidegrees Celsius, and that holds
         * for everything here except "tsens_tz_sensorN" zones, where the scale varies
         * *by SoC generation under the identical zone name*: Snapdragon 820/821
         * ("capricorn") reports decidegrees (raw 502 == 50.2 C), while Snapdragon
         * 410-class chips (e.g. "cancro"/MI 4LTE) report the very same zone name in
         * plain whole-degree Celsius (raw 61 == 61 C -- confirmed against that
         * device's `ThermalEngine` logcat reporting the identical sensor at the same
         * moment as 60000 mC). There is no vendor identifier in the zone name to key
         * off, so both interpretations are computed and whichever lands in a
         * plausible *running* CPU range wins: a phone CPU is essentially never below
         * 15 C, so a deci-Celsius reading under that -- while the whole-degree
         * interpretation of the same raw value is plausible -- is a strong signal the
         * zone is already whole-degree Celsius, not decidegrees.
         */
        internal fun scaleThermalZoneCelsius(type: String, raw: Float): Float {
            if ("tsens" !in type.lowercase(Locale.ROOT)) return raw / 1000f
            val asDeciCelsius = raw / 10f
            return if (asDeciCelsius < 15f && raw in 15f..120f) raw else asDeciCelsius
        }
    }

    private lateinit var cameraStreamer: CameraStreamer
    /**
     * Whether this device's back camera is `INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY`.
     * On those devices Camera2's `StreamConfigurationMap` for `SurfaceTexture` outputs
     * exposes a smaller size list than the same camera's underlying Camera1 HAL
     * actually supports (confirmed on a Snapdragon 410 device: Camera2 capped at
     * 1440x1080 while Camera1's `video-size-values` went up to 1920x1080/4K), so this
     * routes those devices through [Camera1Streamer]/[RtspServerCamera1] instead.
     * Computed once and cached for the service's lifetime -- the whole camera pipeline
     * is torn down in [onDestroy] anyway, so there's nothing to invalidate mid-session.
     */
    private val useCamera1Fallback: Boolean by lazy { isLegacyHardwareLevel() }
    private lateinit var openGlView: OpenGlView
    private lateinit var webServer: WebServer
    private lateinit var windowManager: WindowManager
    private var isSurfaceCreated = false

    // These are read and written from both the main thread and NanoHTTPD worker
    // threads, so every one of them has to be @Volatile.
    @Volatile private var videoWidth = 640
    @Volatile private var videoHeight = 480
    @Volatile private var videoCodec = "H264"
    /**
     * The codec actually negotiated with the encoder, which differs from [videoCodec] when
     * the requested one could not be prepared. Surfaced in /status so the dashboard can
     * show the truth without destroying the user's stored preference.
     */
    @Volatile private var activeCodec = "H264"
    @Volatile private var forceSoftware = false
    @Volatile private var showPreview = false
    @Volatile private var authEnabled = false
    @Volatile private var authUsername = ""
    @Volatile private var authPassword = ""
    @Volatile private var webAuthEnabled = true
    @Volatile private var audioEnabled = false
    @Volatile private var showTimestamp = false
    @Volatile private var timestampPosition = "Top Left"
    @Volatile private var timestampSize = "Medium"
    @Volatile private var flashlightEnabled = false
    @Volatile private var nightModeEnabled = false
    @Volatile private var verticalFlipEnabled = false
    /**
     * Guards [restartServiceFully] against being triggered more than once on the same
     * instance. Without this, e.g. a resolution change and a stop/start toggle
     * arriving close together each independently call `stopSelf()` and schedule their
     * own delayed relaunch -- the resulting overlapping teardown/relaunch churn (two
     * instances both trying to open the same physical camera) crashed the whole
     * process with a native SIGSEGV on this device, not just the original silent-dead-
     * stream bug a single restart fixes.
     */
    @Volatile private var isRestartingService = false
    @Volatile private var zoomLevel: Float = AppPreferences.DEFAULT_ZOOM_LEVEL
    @Volatile private var bitrateKbps: Int = AppPreferences.DEFAULT_BITRATE_KBPS
    @Volatile private var recordToGalleryEnabled = false
    @Volatile private var recordSegmentMinutes = AppPreferences.DEFAULT_RECORD_SEGMENT_MINUTES
    @Volatile private var recordStorageThresholdPercent = AppPreferences.DEFAULT_RECORD_STORAGE_THRESHOLD_PERCENT
    /** Read-only status surfaced in /status -- true only while a segment is actively being written. */
    @Volatile private var isRecordingToGallery = false
    private var isLanternOn = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runs [block] on the main thread.
     *
     * Every WebServer callback arrives on a NanoHTTPD worker thread, but touching the
     * camera or the overlay view off the main thread throws (`updateViewLayout` raises
     * CalledFromWrongThreadException). Only the *side effects* are posted -- the state
     * fields themselves are assigned synchronously by the caller, so a request that
     * changes a setting still reflects the new value by the time it responds.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var textFilter: TextObjectFilterRender? = null
    private val timestampHandler = Handler(Looper.getMainLooper())
    private val timestampRunnable = object : Runnable {
        override fun run() {
            updateTimestampText()
            timestampHandler.postDelayed(this, 1000)
        }
    }
    private val currentSnapshot = AtomicReference<ByteArray>(null)
    private val snapshotHandler = Handler(Looper.getMainLooper())

    /** When a dashboard client last asked for /shot.jpg, for idle throttling. */
    @Volatile private var lastSnapshotRequestMs = 0L

    private val snapshotRunnable = object : Runnable {
        override fun run() {
            val streaming = ::cameraStreamer.isInitialized &&
                cameraStreamer.isStreaming && isSurfaceCreated

            // Capturing + JPEG-encoding twice a second around the clock is the single
            // biggest battery cost in the app, and most of the time nothing consumes the
            // result. Only run at full rate while somebody is watching the dashboard;
            // otherwise idle at a slow heartbeat.
            val viewerActive =
                System.currentTimeMillis() - lastSnapshotRequestMs < VIEWER_IDLE_TIMEOUT_MS
            val wanted = streaming && viewerActive

            if (wanted) takeSnapshot()

            snapshotHandler.postDelayed(this, if (wanted) ACTIVE_SNAPSHOT_INTERVAL_MS else IDLE_SNAPSHOT_INTERVAL_MS)
        }
    }

    // --- Record to Gallery ---
    // Schedules the one-shot "stop the current segment" tick; the *next* segment is only
    // started from recordListener once RecordController confirms the previous one actually
    // stopped (see beginNewRecordingSegment/finalizeCurrentSegment).
    private val recordRotationHandler = Handler(Looper.getMainLooper())
    private val mediaStoreExecutor = Executors.newSingleThreadExecutor()

    // Main-thread-only bookkeeping for the segment currently being written -- never touched
    // from mediaStoreExecutor or a NanoHTTPD thread, so unlike the fields above these don't
    // need to be @Volatile.
    private var currentRecordingUri: Uri? = null
    private var currentRecordingPfd: ParcelFileDescriptor? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            if (status == RecordController.Status.STOPPED) {
                onMain { finalizeCurrentSegment(rotateNext = recordToGalleryEnabled) }
            }
        }

        override fun onError(e: Exception?) {
            android.util.Log.e("CctvServerService", "Gallery recording error", e)
            onMain { finalizeCurrentSegment(rotateNext = recordToGalleryEnabled) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Load saved settings as defaults
        videoCodec = AppPreferences.getVideoCodec(this)
        videoWidth = AppPreferences.getVideoWidth(this)
        videoHeight = AppPreferences.getVideoHeight(this)
        forceSoftware = AppPreferences.getForceSoftware(this)
        showPreview = AppPreferences.getShowPreview(this)
        authEnabled = AppPreferences.getAuthEnabled(this)
        authUsername = AppPreferences.getUsername(this)
        authPassword = AppPreferences.getPassword(this)
        showTimestamp = AppPreferences.getShowTimestamp(this)
        timestampPosition = AppPreferences.getTimestampPosition(this)
        timestampSize = AppPreferences.getTimestampSize(this)
        flashlightEnabled = AppPreferences.getFlashlightEnabled(this)
        nightModeEnabled = AppPreferences.getNightModeEnabled(this)
        verticalFlipEnabled = AppPreferences.getVerticalFlipEnabled(this)
        zoomLevel = AppPreferences.getZoomLevel(this)
        bitrateKbps = AppPreferences.getBitrateKbps(this)
        webAuthEnabled = AppPreferences.getWebAuthEnabled(this)
        audioEnabled = AppPreferences.getAudioEnabled(this)
        recordToGalleryEnabled = AppPreferences.getRecordToGalleryEnabled(this)
        recordSegmentMinutes = AppPreferences.getRecordSegmentMinutes(this)
        recordStorageThresholdPercent = AppPreferences.getRecordStorageThresholdPercent(this)

        // Setup light sensor for night mode
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        openGlView = OpenGlView(applicationContext)
        
        @Suppress("DEPRECATION")
        val layoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(openGlView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to add view. Permission issue?", e)
            e.printStackTrace()
        }
        openGlView.holder.addCallback(this)
        openGlView.holder.setFixedSize(640, 480)
        applyVerticalFlip()

        webServer = WebServer(this, getIpAddress(),
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
                    if (::cameraStreamer.isInitialized) {
                        try {
                            cameraStreamer.switchCamera()
                        } catch (e: Exception) {
                            android.util.Log.e("CctvServerService", "switchCamera failed", e)
                        }
                    }
                }
            },
            onStartStream = {
                onMain {
                    if (isSurfaceCreated && (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming)) {
                        if (useCamera1Fallback && ::cameraStreamer.isInitialized) {
                            // A Camera1Streamer that already streamed once and was
                            // stopped can't cleanly restart in place on this hardware
                            // -- confirmed to fail the same way even with a multi-second
                            // gap between stop and start, not just a fast automatic
                            // restart. See restartServiceFully()'s doc comment.
                            restartServiceFully()
                        } else {
                            startStream()
                        }
                    }
                }
            },
            onStopStream = {
                onMain {
                    if (::cameraStreamer.isInitialized && cameraStreamer.isStreaming) {
                        stopStreamAndRecording()
                    }
                }
            },
            isStreaming = {
                if (::cameraStreamer.isInitialized) cameraStreamer.isStreaming else false
            },
            onCodecUpdate = { newCodec ->
                if (videoCodec != newCodec) {
                    videoCodec = newCodec
                    AppPreferences.setVideoCodec(this, newCodec)
                    onMain { restartStreamIfRunning() }
                }
            },
            getCurrentCodec = { videoCodec },
            getActiveCodec = { activeCodec },
            onResolutionUpdate = { w, h ->
                if (videoWidth != w || videoHeight != h) {
                    videoWidth = w
                    videoHeight = h
                    AppPreferences.setResolution(this, w, h)
                    onMain { restartStreamIfRunning() }
                }
            },
            getCurrentResolution = { 
                if (videoWidth == 0 && videoHeight == 0) "0x0" else "${videoWidth}x${videoHeight}"
            },
            getAuthEnabled = { authEnabled },
            // Read straight from preferences rather than the cached fields. The service
            // caches these at onCreate, but MainActivity seeds the generated password
            // afterwards on first run -- so the cached copy stayed empty, and an empty
            // expected password makes isAuthorized() fall open to avoid locking the
            // owner out. Net effect: a brand-new install served the dashboard
            // unauthenticated while telling the user it was protected.
            getUsername = { AppPreferences.getUsername(this) },
            getPassword = { AppPreferences.getPassword(this) },
            // Each branch assigns and persists synchronously on the calling thread, then
            // posts only the camera/view work. That ordering matters: the dashboard polls
            // /status immediately after a change, and an async assignment would make the
            // toggle snap back to its old value.
            onSettingUpdate = ::handleSettingUpdate,
            getShowTimestamp = { showTimestamp },
            getTimestampPosition = { timestampPosition },
            getTimestampSize = { timestampSize },
            getFlashlightEnabled = { flashlightEnabled },
            getNightModeEnabled = { nightModeEnabled },
            getVerticalFlipEnabled = { verticalFlipEnabled },
            getZoomLevel = { zoomLevel },
            getZoomRange = { currentZoomRangePair() },
            getBitrateKbps = { bitrateKbps },
            getForceSoftware = { forceSoftware },
            getShowPreview = { showPreview },
            onAuthUpdate = { enabled, username, password ->
                authEnabled = enabled
                authUsername = username
                authPassword = password
                AppPreferences.setAuthEnabled(this, enabled)
                AppPreferences.setUsername(this, username)
                AppPreferences.setPassword(this, password)
                onMain {
                    if (::cameraStreamer.isInitialized && cameraStreamer.isStreaming) {
                        if (enabled && username.isNotEmpty() && password.isNotEmpty()) {
                            cameraStreamer.getStreamClient().setAuthorization(username, password)
                        } else {
                            cameraStreamer.getStreamClient().setAuthorization("", "")
                        }
                    }
                }
            },
            getBatteryLevel = { getBatteryLevel() },
            getWifiStrength = { getWifiStrength() },
            getWebAuthEnabled = { AppPreferences.getWebAuthEnabled(this) },
            getRecordToGalleryEnabled = { recordToGalleryEnabled },
            getRecordSegmentMinutes = { recordSegmentMinutes },
            getIsRecordingToGallery = { isRecordingToGallery },
            getRecordings = { listRecordings() },
            openRecordingStream = { id -> openRecordingStream(id) }
        )
        webServer.start()
        snapshotHandler.post(snapshotRunnable)
    }

    /**
     * Applies a single named setting, by key/value string -- shared by the dashboard's
     * `/action/set-setting` (arrives on a NanoHTTPD worker thread) and the app's
     * `ACTION_SET_SETTING` Intent (arrives on the main thread via onStartCommand).
     * Each branch assigns and persists synchronously on the calling thread, then posts
     * only the camera/view work. That ordering matters: the dashboard polls /status
     * immediately after a change, and an async assignment would make the toggle snap
     * back to its old value.
     */
    private fun handleSettingUpdate(key: String, value: String) {
        when (key) {
            "show_timestamp" -> {
                showTimestamp = value.toBoolean()
                AppPreferences.setShowTimestamp(this, showTimestamp)
                onMain { applyTimestampOverlay() }
            }
            "timestamp_position" -> {
                timestampPosition = value
                AppPreferences.setTimestampPosition(this, timestampPosition)
                onMain { applyTimestampOverlay() }
            }
            "timestamp_size" -> {
                timestampSize = value
                AppPreferences.setTimestampSize(this, timestampSize)
                onMain { applyTimestampOverlay() }
            }
            "flashlight_enabled" -> {
                flashlightEnabled = value.toBoolean()
                AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
                onMain { applyFlashlight() }
            }
            "night_mode_enabled" -> {
                nightModeEnabled = value.toBoolean()
                AppPreferences.setNightModeEnabled(this, nightModeEnabled)
                onMain { updateNightModeSensor() }
            }
            "vertical_flip_enabled" -> {
                verticalFlipEnabled = value.toBoolean()
                AppPreferences.setVerticalFlipEnabled(this, verticalFlipEnabled)
                onMain { applyVerticalFlip() }
            }
            "zoom_level" -> {
                value.toFloatOrNull()?.let { requested ->
                    AppPreferences.setZoomLevel(this, requested)
                    zoomLevel = AppPreferences.getZoomLevel(this)
                    onMain { applyZoom() }
                }
            }
            "bitrate_kbps" -> {
                value.toIntOrNull()?.let { requested ->
                    AppPreferences.setBitrateKbps(this, requested)
                    bitrateKbps = AppPreferences.getBitrateKbps(this)
                    onMain { applyBitrate() }
                }
            }
            "force_software" -> {
                forceSoftware = value.toBoolean()
                AppPreferences.setForceSoftware(this, forceSoftware)
                onMain { restartStreamIfRunning() }
            }
            "show_preview" -> {
                showPreview = value.toBoolean()
                AppPreferences.setShowPreview(this, showPreview)
                onMain { updateOverlaySize() }
            }
            "audio_enabled" -> {
                audioEnabled = value.toBoolean()
                AppPreferences.setAudioEnabled(this, audioEnabled)
                // Re-declare the type BEFORE the stream comes back with audio:
                // the restart would otherwise open the microphone while the
                // service is still declared camera-only, which is the very
                // SecurityException the type is there to prevent.
                onMain {
                    applyForegroundServiceType()
                    restartStreamIfRunning()
                }
            }
            "web_auth_enabled" -> {
                webAuthEnabled = value.toBoolean()
                AppPreferences.setWebAuthEnabled(this, webAuthEnabled)
            }
            "record_to_gallery_enabled" -> {
                recordToGalleryEnabled = value.toBoolean()
                AppPreferences.setRecordToGalleryEnabled(this, recordToGalleryEnabled)
                onMain {
                    if (recordToGalleryEnabled) startGalleryRecordingIfNeeded() else stopGalleryRecording()
                }
            }
            "record_segment_minutes" -> {
                value.toIntOrNull()?.let { requested ->
                    AppPreferences.setRecordSegmentMinutes(this, requested)
                    recordSegmentMinutes = AppPreferences.getRecordSegmentMinutes(this)
                }
            }
            "record_storage_threshold_percent" -> {
                value.toIntOrNull()?.let { requested ->
                    AppPreferences.setRecordStorageThresholdPercent(this, requested)
                    recordStorageThresholdPercent = AppPreferences.getRecordStorageThresholdPercent(this)
                }
            }
        }
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private fun restartStreamIfRunning() {
        if (::cameraStreamer.isInitialized && cameraStreamer.isStreaming) {
            if (useCamera1Fallback) {
                // See restartServiceFully()'s doc comment -- an in-place stop+start
                // races the Camera1 legacy path's GL teardown/reinit on this device.
                restartServiceFully()
                return
            }
            stopStreamAndRecording()
            startStream()
            applyZoom()
        }
    }

    /**
     * Fully restarts the service (destroy, then a fresh relaunch) instead of
     * restarting the stream in-place, for the [useCamera1Fallback] path only.
     *
     * Confirmed on a Snapdragon 410 device: an in-process `stopStream()` immediately
     * followed by a reconfigured `startStream()` on the same [Camera1Streamer]/
     * `OpenGlView` races Camera1Base's GL context teardown against its reinit --
     * `OpenGlView.start()` threw `Could not compile shader` and `eglMakeCurrent`
     * failed with `EGL_BAD_MATCH`, leaving the stream dead (`/shot.jpg` 404) while
     * `/status` still reported `streaming: true`. A cold app relaunch never hit this
     * -- a brand new `OpenGlView` gets a brand new GL context instead of reusing one
     * that just tore down -- so this trades the instant in-place restart for a ~1s
     * interruption while a fresh instance spins up. The caller must have already
     * persisted whatever setting triggered this to [AppPreferences] -- the relaunch
     * intent carries no extras and relies entirely on the fresh `onCreate()` reading
     * current values back out of preferences, exactly like a boot-time start does.
     */
    private fun restartServiceFully() {
        if (isRestartingService) return
        isRestartingService = true
        val relaunchIntent = Intent(applicationContext, CctvServerService::class.java)
        val appContext = applicationContext
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(relaunchIntent)
            } else {
                appContext.startService(relaunchIntent)
            }
        }, 1000)
        stopSelf()
    }

    /**
     * Stops the RTSP stream and, if a gallery segment is in flight, the recording too --
     * recording must never survive a `prepareVideo()` call, which `startStream()` may make
     * next. Replaces the bare `cameraStreamer.stopStream()` call so every stop site tears
     * down recording the same way instead of each needing its own reminder to do so.
     */
    private fun stopStreamAndRecording() {
        stopGalleryRecording()
        cameraStreamer.stopStream()
    }

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Battery percentage, or -1 if unavailable.
     *
     * Uses BatteryManager directly rather than a sticky-broadcast registration: /status
     * is polled every few seconds by every connected dashboard, and registering a
     * receiver per request is needless work.
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (capacity in 0..100) capacity else -1
    }

    /**
     * Battery temperature in Celsius, or null if unavailable.
     *
     * Unlike CPU temperature, this is a proper documented API: `EXTRA_TEMPERATURE` on
     * the `ACTION_BATTERY_CHANGED` sticky broadcast, in tenths of a degree. Passing a
     * null receiver to `registerReceiver` just reads the last sticky broadcast instead
     * of actually registering a listener, so there's nothing to unregister later.
     */
    private fun getBatteryTemperatureCelsius(): Float? {
        val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    /**
     * Best-effort peak CPU core temperature in Celsius, or null when unavailable.
     *
     * There is no permission-free public API for this: `HardwarePropertiesManager`'s
     * `getDeviceTemperatures()` still requires the system-signature `DEVICE_POWER`
     * permission despite being nominally "open" since API 29, and `PowerManager` only
     * exposes a coarse thermal-status enum, not a number. This reads the kernel's
     * thermal-zone sysfs nodes directly instead -- no Android permission needed, but
     * whether a zone is even readable, and which zone (if any) is actually the CPU, is
     * entirely up to the device's kernel/SELinux policy, so this fails silently (and
     * unpredictably device-to-device) rather than being a reliable cross-device API.
     */
    private fun getCpuTemperatureCelsius(): Float? {
        val zones = java.io.File("/sys/class/thermal").listFiles { f ->
            f.name.startsWith("thermal_zone")
        } ?: return null

        val readings = zones.mapNotNull { zone ->
            try {
                val type = java.io.File(zone, "type").readText().trim()
                if (!isCpuThermalZone(type)) return@mapNotNull null
                val raw = java.io.File(zone, "temp").readText().trim().toFloatOrNull()
                    ?: return@mapNotNull null
                // The sanity range below only guards against garbage far outside
                // anything physically plausible; taking the max across every matched
                // zone is what lets a genuine CPU zone's reading win over a dummy
                // zone's whenever both exist.
                scaleThermalZoneCelsius(type, raw).takeIf { it in -20f..120f }
            } catch (e: Exception) {
                null
            }
        }

        if (readings.isEmpty()) {
            android.util.Log.d(
                "CctvServerService",
                "No plausible CPU thermal zone reading; zones seen: " + zones.joinToString {
                    runCatching { java.io.File(it, "type").readText().trim() }.getOrDefault(it.name)
                }
            )
        }
        return readings.maxOrNull()
    }

    /**
     * Wi-Fi signal as a percentage, or -1 when it cannot be determined.
     *
     * `WifiManager.connectionInfo` is deprecated and, on modern Android, returns a
     * placeholder RSSI to apps without location permission. Reporting -1 (rendered as
     * a dash) is honest; reporting a number derived from -127 is not.
     */
    @Suppress("DEPRECATION")
    private fun getWifiStrength(): Int {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return -1
            val rssi = wifiManager.connectionInfo?.rssi ?: return -1
            if (rssi == Int.MIN_VALUE || rssi <= -127) return -1
            WifiManager.calculateSignalLevel(rssi, 100).coerceIn(0, 100)
        } catch (e: Exception) {
            android.util.Log.w("CctvServerService", "Wi-Fi strength unavailable", e)
            -1
        }
    }

    private fun getIpAddress(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Address = linkProperties?.linkAddresses?.firstOrNull { 
            it.address is Inet4Address && !it.address.isLoopbackAddress 
        }?.address?.hostAddress
        return ipv4Address ?: "0.0.0.0"
    }

    private fun takeSnapshot() {
        if (!isSurfaceCreated || !::openGlView.isInitialized || !openGlView.holder.surface.isValid) return
        try {
            openGlView.takePhoto { bitmap -> 
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

        if (intent?.action == "ACTION_SET_SETTING") {
            val key = intent.getStringExtra("setting_key")
            val value = intent.getStringExtra("setting_value")
            if (key != null && value != null) {
                handleSettingUpdate(key, value)
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_SWITCH_CAMERA") {
            if (::cameraStreamer.isInitialized) {
                try {
                    cameraStreamer.switchCamera()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_PREVIEW") {
            val show = intent.getBooleanExtra("show_preview", false)
            if (showPreview != show) {
                showPreview = show
                updateOverlaySize()
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_FLASHLIGHT") {
            flashlightEnabled = intent.getBooleanExtra("flashlight_enabled", false)
            AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
            applyFlashlight()
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_NIGHT_MODE") {
            nightModeEnabled = intent.getBooleanExtra("night_mode_enabled", false)
            AppPreferences.setNightModeEnabled(this, nightModeEnabled)
            updateNightModeSensor()
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_VERTICAL_FLIP") {
            verticalFlipEnabled = intent.getBooleanExtra("vertical_flip_enabled", false)
            AppPreferences.setVerticalFlipEnabled(this, verticalFlipEnabled)
            applyVerticalFlip()
            return START_STICKY
        }

        val newVideoCodec = intent?.getStringExtra("video_codec") ?: AppPreferences.getVideoCodec(this)
        val newShowPreview = intent?.getBooleanExtra("show_preview", AppPreferences.getShowPreview(this)) ?: false
        val newWidth = intent?.getIntExtra("width", AppPreferences.getVideoWidth(this)) ?: 640
        val newHeight = intent?.getIntExtra("height", AppPreferences.getVideoHeight(this)) ?: 480
        val newForceSoftware = intent?.getBooleanExtra("force_software", AppPreferences.getForceSoftware(this)) ?: false
        val newAuthEnabled = intent?.getBooleanExtra("auth_enabled", AppPreferences.getAuthEnabled(this)) ?: false
        val newAuthUsername = intent?.getStringExtra("auth_username") ?: AppPreferences.getUsername(this)
        val newAuthPassword = intent?.getStringExtra("auth_password") ?: AppPreferences.getPassword(this)
        val newShowTimestamp = intent?.getBooleanExtra("show_timestamp", AppPreferences.getShowTimestamp(this)) ?: false
        val newTimestampPosition = intent?.getStringExtra("timestamp_position") ?: AppPreferences.getTimestampPosition(this)
        val newTimestampSize = intent?.getStringExtra("timestamp_size") ?: AppPreferences.getTimestampSize(this)
        audioEnabled = intent?.getBooleanExtra("audio_enabled", AppPreferences.getAudioEnabled(this))
            ?: AppPreferences.getAudioEnabled(this)
        AppPreferences.setAudioEnabled(this, audioEnabled)
        // Not carried as an Intent extra like the other camera controls -- always
        // re-read from prefs so this path (and the dashboard/app slider that wrote it)
        // stay in sync across a full-intent restart.
        zoomLevel = AppPreferences.getZoomLevel(this)
        bitrateKbps = AppPreferences.getBitrateKbps(this)
        recordToGalleryEnabled = intent?.getBooleanExtra(
            "record_to_gallery_enabled", AppPreferences.getRecordToGalleryEnabled(this)
        ) ?: AppPreferences.getRecordToGalleryEnabled(this)
        AppPreferences.setRecordToGalleryEnabled(this, recordToGalleryEnabled)
        recordSegmentMinutes = intent?.getIntExtra(
            "record_segment_minutes", AppPreferences.getRecordSegmentMinutes(this)
        ) ?: AppPreferences.getRecordSegmentMinutes(this)
        AppPreferences.setRecordSegmentMinutes(this, recordSegmentMinutes)
        recordStorageThresholdPercent = intent?.getIntExtra(
            "record_storage_threshold_percent", AppPreferences.getRecordStorageThresholdPercent(this)
        ) ?: AppPreferences.getRecordStorageThresholdPercent(this)
        AppPreferences.setRecordStorageThresholdPercent(this, recordStorageThresholdPercent)

        applyForegroundServiceType()

        // Initialize wrapper if needed
        ensureCameraStreamer()

        // If already streaming, check if we need to restart due to config change
        var needsFullServiceRestart = false
        if (cameraStreamer.isStreaming) {
            if (videoCodec != newVideoCodec || videoWidth != newWidth || videoHeight != newHeight || forceSoftware != newForceSoftware) {
                if (useCamera1Fallback) {
                    // See restartServiceFully()'s doc comment -- don't touch the live
                    // stream in-place here; let it keep running until stopSelf() tears
                    // it down properly in onDestroy(), then relaunch fresh below.
                    needsFullServiceRestart = true
                } else {
                    stopStreamAndRecording()
                }
            } else {
                if (showPreview != newShowPreview) {
                     showPreview = newShowPreview
                     updateOverlaySize()
                }
                // Update overlay settings even without full restart
                showTimestamp = newShowTimestamp
                timestampPosition = newTimestampPosition
                timestampSize = newTimestampSize
                AppPreferences.setShowTimestamp(this, showTimestamp)
                AppPreferences.setTimestampPosition(this, timestampPosition)
                AppPreferences.setTimestampSize(this, timestampSize)
                applyTimestampOverlay()
                applyZoom()
                return START_STICKY
            }
        }
        
        videoCodec = newVideoCodec
        videoWidth = newWidth
        videoHeight = newHeight
        forceSoftware = newForceSoftware
        
        // Persist the settings
        AppPreferences.setVideoCodec(this, videoCodec)
        AppPreferences.setResolution(this, videoWidth, videoHeight)
        AppPreferences.setForceSoftware(this, forceSoftware)

        // Update auth settings
        authEnabled = newAuthEnabled
        authUsername = newAuthUsername
        authPassword = newAuthPassword
        AppPreferences.setAuthEnabled(this, authEnabled)
        AppPreferences.setUsername(this, authUsername)
        AppPreferences.setPassword(this, authPassword)

        // Update overlay settings
        showTimestamp = newShowTimestamp
        timestampPosition = newTimestampPosition
        timestampSize = newTimestampSize
        AppPreferences.setShowTimestamp(this, showTimestamp)
        AppPreferences.setTimestampPosition(this, timestampPosition)
        AppPreferences.setTimestampSize(this, timestampSize)

        // Update flashlight & night mode settings
        val newFlashlightEnabled = intent?.getBooleanExtra("flashlight_enabled", AppPreferences.getFlashlightEnabled(this)) ?: false
        val newNightModeEnabled = intent?.getBooleanExtra("night_mode_enabled", AppPreferences.getNightModeEnabled(this)) ?: false
        val newVerticalFlipEnabled = intent?.getBooleanExtra("vertical_flip_enabled", AppPreferences.getVerticalFlipEnabled(this)) ?: false
        flashlightEnabled = newFlashlightEnabled
        nightModeEnabled = newNightModeEnabled
        verticalFlipEnabled = newVerticalFlipEnabled
        AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
        AppPreferences.setNightModeEnabled(this, nightModeEnabled)
        AppPreferences.setVerticalFlipEnabled(this, verticalFlipEnabled)

        if (showPreview != newShowPreview) {
             showPreview = newShowPreview
             AppPreferences.setShowPreview(this, showPreview)
             updateOverlaySize()
        }

        if (needsFullServiceRestart) {
            // Every setting above is already persisted, so the fresh instance's
            // onCreate() picks all of it back up on its own.
            restartServiceFully()
            return START_STICKY
        }

        if (isSurfaceCreated) {
            startStream()
        }

        // Apply flashlight, night mode, vertical flip and zoom after stream starts
        Handler(Looper.getMainLooper()).postDelayed({
            applyFlashlight()
            updateNightModeSensor()
            applyVerticalFlip()
            applyZoom()
        }, 1000)

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !openGlView.holder.surface.isValid) return
        
        try {
            ensureCameraStreamer()

            if (!cameraStreamer.isStreaming) {
                // Resolve max resolution if needed
                if (videoWidth == 0 || videoHeight == 0) {
                    val maxRes = getMaxCameraResolution()
                    videoWidth = maxRes.first
                    videoHeight = maxRes.second
                    android.util.Log.d("CctvServerService", "Max resolution detected: ${videoWidth}x${videoHeight}")
                } else {
                    // The default is 1080p, but not every sensor (especially a front
                    // camera) can do that. Clamp down to what the camera actually
                    // supports rather than handing prepareVideo() a size it will just
                    // reject -- this session only, the user's stored choice is left
                    // alone in case a later attempt (or a camera switch) can honour it.
                    val maxRes = getMaxCameraResolution()
                    if (videoWidth.toLong() * videoHeight > maxRes.first.toLong() * maxRes.second) {
                        android.util.Log.w(
                            "CctvServerService",
                            "Requested ${videoWidth}x${videoHeight} exceeds camera capability; using ${maxRes.first}x${maxRes.second}"
                        )
                        videoWidth = maxRes.first
                        videoHeight = maxRes.second
                    }
                }

                // User-configured via the bitrate slider (AppPreferences default 4000
                // kbps) -- used to be auto-calculated from resolution alone.
                val bitrate = bitrateKbps * 1024

                // Audio is opt-in. Recording it forces the microphone foreground-service
                // type and the RECORD_AUDIO grant; a camera-only stream needs neither.
                if (audioEnabled && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    cameraStreamer.prepareAudio(64 * 1024, 44100, true, false, false)
                } else {
                    cameraStreamer.disableAudio()
                }

                // Check and set Codec
                val selectedCodec = when (videoCodec) {
                    "H265" -> VideoCodec.H265
                    "AV1" -> VideoCodec.AV1
                    // "VP9" was offered in both UIs but silently fell back to H.264.
                    // It is gone from the pickers; this branch only catches stale prefs.
                    else -> VideoCodec.H264
                }
                
                cameraStreamer.setVideoCodec(selectedCodec)
                android.util.Log.d("CctvServerService", "Selected codec: $selectedCodec ($videoCodec)")

                // The "Force Software Codec" switch was previously persisted and even
                // restarted the stream, but was never applied to the encoder. Wire it to
                // the API that actually selects the codec implementation.
                val codecType = if (forceSoftware) {
                    CodecUtil.CodecType.SOFTWARE
                } else {
                    CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                }
                cameraStreamer.forceCodecType(codecType, codecType)
                android.util.Log.d("CctvServerService", "Codec type: $codecType")

                // Set authentication
                if (authEnabled && authUsername.isNotEmpty() && authPassword.isNotEmpty()) {
                    cameraStreamer.getStreamClient().setAuthorization(authUsername, authPassword)
                    android.util.Log.d("CctvServerService", "RTSP auth enabled for user: $authUsername")
                } else {
                    cameraStreamer.getStreamClient().setAuthorization("", "")
                    android.util.Log.d("CctvServerService", "RTSP auth disabled")
                }

                if (cameraStreamer.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                    cameraStreamer.startStream()
                    if (useCamera1Fallback && cameraStreamerJustConstructed) correctCamera1DefaultFacing()
                    applyTimestampOverlay()
                    activeCodec = videoCodec
                    startGalleryRecordingIfNeeded()
                } else {
                    android.util.Log.w("CctvServerService", "Codec $selectedCodec preparation failed, falling back to H264")
                    cameraStreamer.setVideoCodec(VideoCodec.H264)
                    if (cameraStreamer.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                         cameraStreamer.startStream()
                         if (useCamera1Fallback && cameraStreamerJustConstructed) correctCamera1DefaultFacing()
                         applyTimestampOverlay()
                         // Record that THIS session fell back, but do NOT overwrite the
                         // user's stored choice. prepareVideo can fail transiently -- a
                         // quick stop/start was enough to drop a working H.265 stream --
                         // and persisting H264 silently threw away a setting the device
                         // is perfectly capable of honouring on the next attempt.
                         activeCodec = "H264"
                         startGalleryRecordingIfNeeded()
                    } else {
                         android.util.Log.e("CctvServerService", "H264 fallback preparation also failed.")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Record to Gallery ---

    /**
     * Begins a new segment if recording is turned on, the stream is actually up, and we
     * aren't already recording. Called both right after the stream starts and whenever a
     * finished segment's [RecordController.Listener] confirms it's safe to start the next
     * one -- see the class-level note on [recordRotationHandler].
     */
    private fun startGalleryRecordingIfNeeded() {
        if (!recordToGalleryEnabled) return
        if (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming) return
        if (cameraStreamer.isRecording()) return
        beginNewRecordingSegment()
    }

    /**
     * One finished (or about-to-be-inserted) gallery segment. Exactly one of [pfd] / [path]
     * is set: scoped storage (API 29+) has no filesystem path and must write through a
     * MediaStore-opened [ParcelFileDescriptor]; pre-29 has a real [File] path and uses
     * RootEncoder's string-path `startRecord` overload instead -- the `FileDescriptor`
     * overload calls `MediaMuxer(FileDescriptor, int)`, which requires API 26 and would
     * violate this app's minSdk 23 if it were the only path taken.
     */
    private class GalleryEntry(val uri: Uri, val pfd: ParcelFileDescriptor?, val path: String?)

    private fun beginNewRecordingSegment() {
        mediaStoreExecutor.execute {
            val entry = try {
                insertGalleryVideoEntry()
            } catch (e: Exception) {
                android.util.Log.e("CctvServerService", "Failed to create gallery recording entry", e)
                null
            }
            if (entry == null) return@execute

            onMain {
                if (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming || !recordToGalleryEnabled) {
                    // Setting was turned off or the stream stopped while the insert was
                    // in flight -- discard the just-created (empty) MediaStore row instead
                    // of leaving a stuck IS_PENDING entry in the gallery.
                    mediaStoreExecutor.execute { discardGalleryEntry(entry) }
                    return@onMain
                }
                try {
                    val pfd = entry.pfd
                    if (pfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        cameraStreamer.startRecord(pfd.fileDescriptor, recordListener)
                    } else if (entry.path != null) {
                        cameraStreamer.startRecord(entry.path, recordListener)
                    } else {
                        throw IllegalStateException("Gallery entry has neither a usable fd nor a path")
                    }
                    currentRecordingUri = entry.uri
                    currentRecordingPfd = pfd
                    isRecordingToGallery = true
                    recordRotationHandler.postDelayed(
                        { if (cameraStreamer.isRecording()) cameraStreamer.stopRecord() },
                        recordSegmentMinutes * 60_000L
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CctvServerService", "startRecord failed", e)
                    mediaStoreExecutor.execute { discardGalleryEntry(entry) }
                }
            }
        }
    }

    /** Cancels any pending rotation and stops the in-flight segment without starting another. */
    private fun stopGalleryRecording() {
        recordRotationHandler.removeCallbacksAndMessages(null)
        if (::cameraStreamer.isInitialized && cameraStreamer.isRecording()) {
            cameraStreamer.stopRecord()
        }
    }

    /**
     * Runs once [RecordController] confirms the current segment actually stopped writing.
     * Finalizes the gallery entry on [mediaStoreExecutor] and, if still wanted, chains
     * straight into the next segment -- the only place a new segment is started besides
     * the initial call from [startStream].
     */
    private fun finalizeCurrentSegment(rotateNext: Boolean) {
        val uri = currentRecordingUri
        val pfd = currentRecordingPfd
        currentRecordingUri = null
        currentRecordingPfd = null
        isRecordingToGallery = false

        if (uri == null) {
            if (rotateNext) startGalleryRecordingIfNeeded()
            return
        }

        mediaStoreExecutor.execute {
            // Only set for the scoped-storage (API 29+) path -- the pre-29 path records
            // straight through RootEncoder's string-path overload and never opens one.
            try {
                pfd?.close()
            } catch (e: Exception) {
                android.util.Log.e("CctvServerService", "Failed to close recording fd", e)
            }
            finalizeGalleryVideoEntry(uri)
            enforceRetention()
            if (rotateNext) onMain { startGalleryRecordingIfNeeded() }
        }
    }

    /** Runs on [mediaStoreExecutor]. Creates the MediaStore row and opens it for writing. */
    @Suppress("DEPRECATION") // MediaStore.Video.Media.DATA / Environment.getExternalStoragePublicDirectory: pre-Q only path
    private fun insertGalleryVideoEntry(): GalleryEntry? {
        val fileName = "CCTV_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())}.mp4"
        val resolver = contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RECORDING_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
                android.util.Log.e("CctvServerService", "MediaStore insert returned null")
                return null
            }
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
                resolver.delete(uri, null, null)
                android.util.Log.e("CctvServerService", "openFileDescriptor returned null")
                return null
            }
            return GalleryEntry(uri, pfd, path = null)
        }

        // Pre-API 29: no scoped storage, no RELATIVE_PATH/IS_PENDING columns. Requires
        // WRITE_EXTERNAL_STORAGE, which MainActivity only requests on these OS versions.
        if (!hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            android.util.Log.w("CctvServerService", "Gallery recording needs WRITE_EXTERNAL_STORAGE on this OS version")
            return null
        }
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CCTVApp")
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            android.util.Log.e("CctvServerService", "Failed to create $moviesDir")
            return null
        }
        val file = File(moviesDir, fileName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATA, file.absolutePath)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            android.util.Log.e("CctvServerService", "MediaStore insert returned null")
            return null
        }
        // RootEncoder's string-path startRecord overload creates and writes the file
        // itself (via MediaMuxer(String, int), available since API 18), so no
        // ParcelFileDescriptor needs to be opened on this pre-Q path.
        return GalleryEntry(uri, pfd = null, path = file.absolutePath)
    }

    /** Runs on [mediaStoreExecutor]. Marks a finished segment visible in the gallery. */
    private fun finalizeGalleryVideoEntry(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            } else {
                val path = queryDataPath(uri)
                if (path != null) {
                    MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("video/mp4"), null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to finalize gallery entry $uri", e)
        }
    }

    /** Runs on [mediaStoreExecutor]. Removes a row that never got any recorded data. */
    private fun discardGalleryEntry(entry: GalleryEntry) {
        try {
            entry.pfd?.close()
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to close discarded recording fd", e)
        }
        try {
            contentResolver.delete(entry.uri, null, null)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to discard gallery entry ${entry.uri}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryDataPath(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /**
     * Loop recording: once local storage usage crosses [recordStorageThresholdPercent],
     * deletes the oldest finished segments under [RECORDING_RELATIVE_PATH] one at a
     * time -- rechecking usage after each delete -- until it drops back under the
     * threshold or there's nothing left to delete. Runs on [mediaStoreExecutor] after
     * each segment finalizes, so a server left running indefinitely doesn't fill the
     * device's storage.
     */
    private fun enforceRetention() {
        try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"
            val (selection, selectionArgs) = recordingsSelection()

            val ids = ArrayDeque<Long>()
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) ids.add(cursor.getLong(idIndex))
            }

            val statFs = StatFs(Environment.getExternalStorageDirectory().path)
            while (ids.isNotEmpty() && usedPercent(statFs) > recordStorageThresholdPercent) {
                val id = ids.removeFirst()
                val itemUri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                contentResolver.delete(itemUri, null, null)
                statFs.restat(Environment.getExternalStorageDirectory().path)
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to enforce recording retention", e)
        }
    }

    /** Selects rows under [RECORDING_RELATIVE_PATH] -- shared by retention and listing. */
    @Suppress("DEPRECATION") // MediaStore.Video.Media.DATA / Environment.getExternalStoragePublicDirectory: pre-Q only path
    private fun recordingsSelection(): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0" to
                arrayOf(RECORDING_RELATIVE_PATH)
        } else {
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CCTVApp")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("${moviesDir.absolutePath}/%")
        }
    }

    /** Lists finished recordings under [RECORDING_RELATIVE_PATH], newest first. */
    private fun listRecordings(): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
            val (selection, selectionArgs) = recordingsSelection()
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    entries.add(
                        RecordingEntry(
                            id = cursor.getLong(idIdx),
                            displayName = cursor.getString(nameIdx) ?: "recording.mp4",
                            sizeBytes = cursor.getLong(sizeIdx),
                            dateAddedMillis = cursor.getLong(dateIdx) * 1000L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to list recordings", e)
        }
        return entries
    }

    /**
     * Opens a recording for serving over HTTP. Re-validates [id] against
     * [recordingsSelection] rather than trusting the caller, so this can't be used to
     * read arbitrary gallery content the app didn't itself record.
     */
    private fun openRecordingStream(id: Long): InputStream? {
        return try {
            val (baseSelection, baseArgs) = recordingsSelection()
            val selection = "$baseSelection AND ${MediaStore.Video.Media._ID} = ?"
            val selectionArgs = baseArgs + id.toString()
            var found = false
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                selection, selectionArgs, null
            )?.use { cursor -> found = cursor.moveToFirst() }
            if (!found) return null
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to open recording $id for serving", e)
            null
        }
    }

    private fun usedPercent(statFs: StatFs): Int {
        val total = statFs.totalBytes
        if (total <= 0L) return 0
        return (((total - statFs.availableBytes) * 100) / total).toInt()
    }

    private fun applyTimestampOverlay() {
        if (!showTimestamp) {
            timestampHandler.removeCallbacks(timestampRunnable)
            textFilter = null
            return
        }

        try {
            val filter = TextObjectFilterRender()
            if (::cameraStreamer.isInitialized) {
                cameraStreamer.getGlInterface().setFilter(filter)
            }

            val fontSize = getOverlayFontSize()
            filter.setText(buildTimestampString(), fontSize, Color.WHITE, Typeface.DEFAULT_BOLD)

            // setScale() sizes the overlay box as a percentage of the FULL FRAME
            // width/height -- it has nothing to do with font size in pixels. The
            // previous values (25-45% wide, 6-14% tall) covered up to half the frame,
            // so the small text bitmap was stretched into a huge box: blown up,
            // distorted, and -- since even "Top Left" left the box spanning almost to
            // the horizontal centre -- reading as parked in the middle of the screen.
            // These are small enough to hug a corner instead.
            // Widened from the original 8/11/15: the overlay text now also carries
            // battery/CPU readouts, roughly doubling its typical character count, and
            // the box is a fixed percentage of the frame regardless of string length
            // (see applyTimestampOverlay), so it needs more room or the extra text
            // just gets squeezed into the same width.
            val scaleW = when (timestampSize) {
                "Small" -> 14f
                "Large" -> 26f
                else -> 19f
            }
            val scaleH = when (timestampSize) {
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
            when (timestampPosition) {
                "Top Left" -> filter.setPosition(margin, margin)
                "Top Right" -> filter.setPosition(100f - scaleW - margin, margin)
                "Bottom Left" -> filter.setPosition(margin, 100f - scaleH - margin)
                "Bottom Right" -> filter.setPosition(100f - scaleW - margin, 100f - scaleH - margin)
            }

            textFilter = filter
            timestampHandler.removeCallbacks(timestampRunnable)
            timestampHandler.post(timestampRunnable)
            android.util.Log.d("CctvServerService", "Timestamp overlay applied at $timestampPosition, size=$timestampSize")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to apply timestamp overlay", e)
        }
    }

    private fun updateTimestampText() {
        val filter = textFilter ?: return
        if (!showTimestamp) return
        try {
            filter.setText(buildTimestampString(), getOverlayFontSize(), Color.WHITE, Typeface.DEFAULT_BOLD)
        } catch (e: Exception) {
            // Ignore - filter may not be ready
        }
    }

    private fun getOverlayFontSize(): Float {
        return when (timestampSize) {
            "Small" -> 16f
            "Large" -> 30f
            else -> 22f  // Medium
        }
    }

    private fun buildTimestampString(): String {
        val now = Date()
        val parts = mutableListOf<String>()
        if (showTimestamp) {
            parts.add(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
            parts.add(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now))
        }
        val batteryLevel = getBatteryLevel()
        if (batteryLevel >= 0) {
            // Battery temp rides in the same part as the charge level (no separate
            // "BATT" label) -- position alone makes the grouping obvious, and every
            // character here was making the fixed-width overlay box more cramped.
            val batteryTemp = getBatteryTemperatureCelsius()?.let { " %.0f°C".format(Locale.getDefault(), it) } ?: ""
            parts.add("$batteryLevel%$batteryTemp")
        }
        getCpuTemperatureCelsius()?.let { temp ->
            parts.add("%.0f°C".format(Locale.getDefault(), temp))
        }
        return parts.joinToString(" ")
    }

    /**
     * Picks the camera id [getMaxCameraResolutionCamera2] and [isLegacyHardwareLevel]
     * both need to agree on -- prefer the back camera rather than whichever id happens
     * to be first, since on many devices id 0 is not the sensor actually being
     * streamed, so a capability query could resolve against the wrong camera entirely.
     */
    private fun pickCameraId(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    /**
     * True when the back camera is `INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY` -- see
     * [useCamera1Fallback]'s doc comment for why that routes streaming through
     * [Camera1Streamer] instead of the normal Camera2 path. Any failure here (missing
     * camera service, no cameras, etc.) returns false so the device falls onto the
     * already-shipped Camera2 path rather than risking a new, unverified one.
     */
    private fun isLegacyHardwareLevel(): Boolean {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = pickCameraId(cameraManager) ?: return false
            val level = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to read hardware level", e)
            false
        }
    }

    /**
     * Set by [ensureCameraStreamer] when it just constructed a new [cameraStreamer],
     * and consumed by [startStream] to know whether [correctCamera1DefaultFacing]
     * needs to run. A field rather than a local/return value because construction
     * (in [onStartCommand], to check [CameraStreamer.isStreaming]) and the first
     * `startStream()` call that actually needs to know about it happen in different
     * functions within the same onStartCommand invocation.
     */
    @Volatile private var cameraStreamerJustConstructed = false

    /** Lazily creates [cameraStreamer], routed to Camera1 or Camera2 per [useCamera1Fallback]. */
    private fun ensureCameraStreamer() {
        if (::cameraStreamer.isInitialized) return
        cameraStreamer = if (useCamera1Fallback) {
            Camera1Streamer(RtspServerCamera1(openGlView, this, 8554))
        } else {
            Camera2Streamer(RtspServerCamera2(openGlView, this, 8554))
        }
        cameraStreamerJustConstructed = true
    }

    /**
     * Works around a RootEncoder 2.7.2 bug in `Camera1ApiManager`'s constructor: it
     * probes both cameras back-to-back to cache their preview-size lists --
     * `cameraSelect = selectCameraBack(); ...; cameraSelect = selectCameraFront(); ...`
     * -- and never resets `cameraSelect` afterward, leaving it pointing at the FRONT
     * camera's index. `Camera1Base.startStream()`'s normal path opens whatever
     * `cameraSelect` already holds, so every freshly-constructed [Camera1Streamer]
     * opens on the front camera, confirmed via `dumpsys media.camera`'s "Active Camera
     * Clients" showing camera ID 1 (FRONT) instead of 0 (BACK) right after a cold
     * start. `getCameraFacing()`/`isLanternEnabled()`-style introspection can't detect
     * this from the app side either: a *separate* `facing` field defaults correctly to
     * BACK and is never touched by the buggy `cameraSelect` path, so it keeps
     * (incorrectly) reporting BACK even while the front camera is actually live.
     *
     * The fix is a single unconditional [CameraStreamer.switchCamera] call right after
     * the very first successful `startStream()` on a freshly-built [Camera1Streamer]
     * -- by then the camera is actually open (`onPreview == true` internally), so
     * `switchCamera()` takes the properly-implemented close-and-reopen-the-other-index
     * path rather than the no-op-on-the-wrong-field path it'd take before the camera
     * opens. This mirrors the Camera2 path's existing, unremarkable behavior (always
     * opens on the back camera after a fresh construction), rather than introducing
     * new behavior.
     */
    private fun correctCamera1DefaultFacing() {
        // Consume the flag regardless of outcome -- this must only ever be attempted
        // once per freshly-constructed streamer, or a later legitimate startStream()
        // call on the same (already-corrected) instance would toggle it right back to
        // the front camera.
        cameraStreamerJustConstructed = false
        try {
            cameraStreamer.switchCamera()
            android.util.Log.d("CctvServerService", "Corrected Camera1 default facing (front -> back)")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to correct Camera1 default facing", e)
        }
    }

    private fun getMaxCameraResolution(): Pair<Int, Int> =
        if (useCamera1Fallback) getMaxCameraResolutionLegacyCamera1() else getMaxCameraResolutionCamera2()

    private fun getMaxCameraResolutionCamera2(): Pair<Int, Int> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = pickCameraId(cameraManager) ?: return Pair(1920, 1080)

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Use SurfaceTexture sizes — these are video-encoder compatible
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?: return Pair(1920, 1080)
            // Cap at 4K (3840x2160) to avoid encoder failures
            val maxPixels = 3840 * 2160
            val validSizes = sizes.filter { it.width * it.height <= maxPixels }
            val maxSize = (if (validSizes.isNotEmpty()) validSizes else sizes.toList())
                .maxByOrNull { it.width * it.height } ?: return Pair(1920, 1080)
            android.util.Log.d("CctvServerService", "Camera max video size: ${maxSize.width}x${maxSize.height}")
            android.util.Log.d(
                "CctvServerService",
                "All SurfaceTexture-compatible sizes: " +
                    sizes.sortedByDescending { it.width * it.height }
                        .joinToString { "${it.width}x${it.height}" }
            )
            return Pair(maxSize.width, maxSize.height)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to get max resolution", e)
            return Pair(1920, 1080)
        }
    }

    /**
     * [getMaxCameraResolutionCamera2]'s counterpart for `INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY`
     * devices -- queries the legacy `android.hardware.Camera` API directly (distinct
     * from RootEncoder's own internal Camera1 capture pipeline), since that's the API
     * whose reported sizes actually match what [Camera1Streamer] can stream, unlike
     * Camera2's `StreamConfigurationMap` on these devices (see [useCamera1Fallback]).
     */
    @Suppress("DEPRECATION")
    private fun getMaxCameraResolutionLegacyCamera1(): Pair<Int, Int> {
        var camera: android.hardware.Camera? = null
        try {
            camera = android.hardware.Camera.open()
            val params = camera.parameters
            val sizes = params.supportedVideoSizes ?: params.supportedPreviewSizes
                ?: return Pair(1920, 1080)
            val maxPixels = 3840 * 2160
            val validSizes = sizes.filter { it.width.toLong() * it.height <= maxPixels }
            val maxSize = (if (validSizes.isNotEmpty()) validSizes else sizes.toList())
                .maxByOrNull { it.width * it.height } ?: return Pair(1920, 1080)
            android.util.Log.d("CctvServerService", "Legacy Camera1 max video size: ${maxSize.width}x${maxSize.height}")
            return Pair(maxSize.width, maxSize.height)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to get max resolution (Camera1)", e)
            return Pair(1920, 1080)
        } finally {
            // android.hardware.Camera.open() is an exclusive lock -- must release before
            // RtspServerCamera1 opens the same physical camera for real streaming.
            camera?.release()
        }
    }

    private fun applyFlashlight() {
        if (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming) return
        try {
            if (flashlightEnabled && !isLanternOn) {
                cameraStreamer.enableLantern()
                isLanternOn = true
                android.util.Log.d("CctvServerService", "Flashlight ON")
            } else if (!flashlightEnabled && isLanternOn) {
                cameraStreamer.disableLantern()
                isLanternOn = false
                android.util.Log.d("CctvServerService", "Flashlight OFF")
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to toggle flashlight", e)
        }
    }

    /**
     * Flips preview, RTSP stream and dashboard snapshot vertically -- for a camera
     * mounted upside-down (e.g. a ceiling bracket). All three render off the same GL
     * surface ([openGlView]), so one flag on it covers every output. Unlike
     * [applyZoom]/[applyFlashlight], this doesn't need a live camera session: it's a
     * render-time flag on the view itself, so it's safe to call as soon as
     * [openGlView] exists.
     *
     * Uses `setCameraFlip`, not `setIs{Preview,Stream}VerticalFlip`: the latter pair
     * flips the *final composited* frame -- after the timestamp overlay filter has
     * already been drawn onto it -- so the date/time text flipped upside-down along
     * with the picture. `setCameraFlip` flips only the raw camera texture before
     * filters are composited, so the picture flips but the overlay text stays upright
     * with no extra work needed. Its two parameters are `(horizontal, vertical)`, but
     * on the Camera2 path -- confirmed on-device via /shot.jpg -- a phone's camera
     * sensor is mounted rotated relative to the display, and RootEncoder's
     * `Camera2Base` applies that as a real 90/270 rotation in the GL matrix *after*
     * the flip (scale) matrix, so what this method calls "vertical" shows up as a
     * *horizontal* mirror in the rendered frame; [verticalFlipEnabled] is passed as
     * the first (horizontal) argument there to compensate.
     *
     * [useCamera1Fallback] does NOT get that swap: RootEncoder's `Camera1Base` always
     * calls `GlInterface.setRotation(0)` (decompiled from RootEncoder 2.7.2's
     * `library-2.7.2.aar` to confirm) -- Camera1 instead bakes the sensor rotation
     * into the raw preview buffer itself via `Camera.setDisplayOrientation()` before
     * GL ever sees it, so there's no axis-swapping rotation for the flip to land
     * before. Applying the Camera2 swap here mirrored left-right instead of flipping
     * top-to-bottom on a Camera1-fallback device (confirmed on a Camera1-fallback
     * device: Xiaomi Mi 4).
     */
    private fun applyVerticalFlip() {
        if (!::openGlView.isInitialized) return
        try {
            if (useCamera1Fallback) {
                openGlView.setCameraFlip(false, verticalFlipEnabled)
            } else {
                openGlView.setCameraFlip(verticalFlipEnabled, false)
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to set vertical flip", e)
        }
    }

    /**
     * Applies [zoomLevel] to the live camera, clamped to the hardware's actual zoom
     * range -- [AppPreferences]'s generic 1.0-8.0 bounds are only a UI-layer default;
     * some devices support less, a few support more.
     */
    private fun applyZoom() {
        if (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming) return
        try {
            val (lower, upper) = cameraStreamer.getZoomRange()
            val clamped = zoomLevel.coerceIn(lower, upper)
            cameraStreamer.setZoom(clamped)
            android.util.Log.d("CctvServerService", "Zoom set to $clamped (requested $zoomLevel, range=[$lower, $upper])")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to set zoom", e)
        }
    }

    /** Real hardware bounds once the camera is open, else the generic AppPreferences bounds. */
    private fun currentZoomRangePair(): Pair<Float, Float> {
        return try {
            if (::cameraStreamer.isInitialized && cameraStreamer.isStreaming) {
                cameraStreamer.getZoomRange()
            } else {
                Pair(AppPreferences.ZOOM_MIN, AppPreferences.ZOOM_MAX)
            }
        } catch (e: Exception) {
            Pair(AppPreferences.ZOOM_MIN, AppPreferences.ZOOM_MAX)
        }
    }

    /**
     * Pushes [bitrateKbps] to the live encoder without a stream restart.
     *
     * Unlike zoom, bitrate is also a direct `prepareVideo()` parameter (see
     * `startStream()`), so a full codec/resolution-triggered restart already picks up
     * the current value on its own -- this on-the-fly setter only matters for the
     * dashboard/app slider changing bitrate while already streaming.
     */
    private fun applyBitrate() {
        if (!::cameraStreamer.isInitialized || !cameraStreamer.isStreaming) return
        try {
            cameraStreamer.setVideoBitrateOnFly(bitrateKbps * 1024)
            android.util.Log.d("CctvServerService", "Bitrate set to ${bitrateKbps}kbps")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to set bitrate", e)
        }
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!nightModeEnabled) return
            val lux = event?.values?.get(0) ?: return
            val shouldEnableFlash = lux < 10f
            if (shouldEnableFlash != isLanternOn) {
                flashlightEnabled = shouldEnableFlash
                applyFlashlight()
                android.util.Log.d("CctvServerService", "Night mode: lux=$lux, flash=${if (shouldEnableFlash) "ON" else "OFF"}")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateNightModeSensor() {
        sensorManager?.unregisterListener(lightSensorListener)
        if (nightModeEnabled && lightSensor != null) {
            sensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            android.util.Log.d("CctvServerService", "Night mode sensor registered")
        } else {
            android.util.Log.d("CctvServerService", "Night mode sensor unregistered")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        startStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (::cameraStreamer.isInitialized && !cameraStreamer.isStreaming) {
             startStream()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        if (::cameraStreamer.isInitialized && cameraStreamer.isStreaming) {
            stopStreamAndRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        snapshotHandler.removeCallbacks(snapshotRunnable)
        timestampHandler.removeCallbacks(timestampRunnable)
        sensorManager?.unregisterListener(lightSensorListener)
        recordRotationHandler.removeCallbacksAndMessages(null)
        stopGalleryRecording()
        mediaStoreExecutor.shutdown()

        webServer.stop()

        if (::cameraStreamer.isInitialized) {
            try {
                if (cameraStreamer.isStreaming) {
                    cameraStreamer.stopStream()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (::openGlView.isInitialized) {
            try {
                windowManager.removeView(openGlView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // STOP_FOREGROUND_REMOVE needs API 24; the boolean overload covers API 23 too.
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun updateOverlaySize() {
        if (!::openGlView.isInitialized) return
        
        val layoutParams = openGlView.layoutParams as WindowManager.LayoutParams
        if (showPreview) {
             // Half the screen width, height kept at a 1080p (16:9) ratio, centered
             // horizontally and pinned to the top.
             val screenWidth = resources.displayMetrics.widthPixels
             val previewWidth = screenWidth / 2
             layoutParams.width = previewWidth
             layoutParams.height = previewWidth * 1080 / 1920
             layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
             layoutParams.width = 1
             layoutParams.height = 1
             layoutParams.gravity = Gravity.TOP or Gravity.START
        }
        windowManager.updateViewLayout(openGlView, layoutParams)
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
            if (audioEnabled) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = android.app.PendingIntent.getService(
            this,
            1,
            Intent(this, CctvServerService::class.java).setAction(ACTION_STOP_SERVER),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "CCTV Server Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // ConnectChecker methods
    override fun onConnectionStarted(url: String) {
        android.util.Log.d("CctvServerService", "Connection started: $url")
    }

    override fun onConnectionSuccess() {
        android.util.Log.d("CctvServerService", "Connection success")
    }

    override fun onConnectionFailed(reason: String) {
        android.util.Log.e("CctvServerService", "Connection failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {
        android.util.Log.d("CctvServerService", "New bitrate: $bitrate")
    }

    override fun onDisconnect() {
        android.util.Log.d("CctvServerService", "Disconnected")
    }

    override fun onAuthError() {
        android.util.Log.e("CctvServerService", "Auth error")
    }

    override fun onAuthSuccess() {
        android.util.Log.d("CctvServerService", "Auth success")
    }
}