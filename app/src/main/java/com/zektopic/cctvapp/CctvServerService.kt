package com.zektopic.cctvapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.io.ByteArrayOutputStream
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
    }

    private lateinit var rtspServerCamera: RtspServerCamera2
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
    @Volatile private var zoomLevel: Float = AppPreferences.DEFAULT_ZOOM_LEVEL
    @Volatile private var bitrateKbps: Int = AppPreferences.DEFAULT_BITRATE_KBPS
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
            val streaming = ::rtspServerCamera.isInitialized &&
                rtspServerCamera.isStreaming && isSurfaceCreated

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
        zoomLevel = AppPreferences.getZoomLevel(this)
        bitrateKbps = AppPreferences.getBitrateKbps(this)
        webAuthEnabled = AppPreferences.getWebAuthEnabled(this)
        audioEnabled = AppPreferences.getAudioEnabled(this)

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
                    if (::rtspServerCamera.isInitialized) {
                        try {
                            rtspServerCamera.switchCamera()
                        } catch (e: Exception) {
                            android.util.Log.e("CctvServerService", "switchCamera failed", e)
                        }
                    }
                }
            },
            onStartStream = {
                onMain {
                    if (isSurfaceCreated && (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming)) {
                        startStream()
                    }
                }
            },
            onStopStream = {
                onMain {
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                    }
                }
            },
            isStreaming = {
                if (::rtspServerCamera.isInitialized) rtspServerCamera.isStreaming else false
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
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        if (enabled && username.isNotEmpty() && password.isNotEmpty()) {
                            rtspServerCamera.getStreamClient().setAuthorization(username, password)
                        } else {
                            rtspServerCamera.getStreamClient().setAuthorization("", "")
                        }
                    }
                }
            },
            getBatteryLevel = { getBatteryLevel() },
            getWifiStrength = { getWifiStrength() },
            getWebAuthEnabled = { AppPreferences.getWebAuthEnabled(this) }
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
        }
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private fun restartStreamIfRunning() {
        if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
            rtspServerCamera.stopStream()
            startStream()
            applyZoom()
        }
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
                val type = java.io.File(zone, "type").readText().trim().lowercase(Locale.ROOT)
                // Zone naming for the SoC/CPU die is not standardized across vendors --
                // newer Qualcomm SoCs use "cpu0"/"cpu-thermal", but older ones (e.g.
                // Snapdragon 820, "capricorn") expose only "tsens_tz_sensorN" zones with
                // no "cpu" substring at all, which silently produced zero readings.
                val looksLikeCpuZone = "cpu" in type || "tsens" in type
                // "-hw-trip-" zones (seen on Qualcomm SoCs, e.g. "cpu-hw-trip-0") report
                // the configured throttling threshold, not a live sensor reading -- a
                // static ~95 C that swamped every real core reading (~48-51 C) once both
                // matched "cpu" and this took the max across zones.
                if (!looksLikeCpuZone || "trip" in type) return@mapNotNull null
                val raw = java.io.File(zone, "temp").readText().trim().toFloatOrNull()
                    ?: return@mapNotNull null
                // The kernel thermal sysfs ABI specifies millidegrees Celsius, and that
                // holds for everything here except one documented vendor quirk: on
                // Snapdragon 820/821 ("tsens_tz_sensorN"), the driver reports
                // decidegrees instead (raw 502 == 50.2 C, not 0.502 C) -- confirmed by
                // cross-checking against this device's other zones (msm_therm,
                // emmc_therm, pa_therm0/1, xo_therm_buf), which all read 44-49 C in
                // whatever's actually normal Celsius while tsens read ~500 raw. This is
                // keyed to the known "tsens" naming, not a magnitude guess: a previous
                // version tried to detect "already in Celsius" from the raw value's
                // size alone and misread an unpopulated/dummy zone's near-zero
                // millidegree reading (raw 95 == 0.095 C) as a literal 95 C. The sanity
                // range below only guards against garbage far outside anything
                // physically plausible; taking the max across every matched zone is
                // what lets a genuine CPU zone's reading win over a dummy zone's
                // whenever both exist.
                val celsius = if ("tsens" in type) raw / 10f else raw / 1000f
                celsius.takeIf { it in -20f..120f }
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
            if (::rtspServerCamera.isInitialized) {
                try {
                    rtspServerCamera.switchCamera()
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

        applyForegroundServiceType()

        // Initialize wrapper if needed
        if (!::rtspServerCamera.isInitialized) {
             rtspServerCamera = RtspServerCamera2(openGlView, this, 8554)
        }

        // If already streaming, check if we need to restart due to config change
        if (rtspServerCamera.isStreaming) {
            if (videoCodec != newVideoCodec || videoWidth != newWidth || videoHeight != newHeight || forceSoftware != newForceSoftware) {
                rtspServerCamera.stopStream()
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
        flashlightEnabled = newFlashlightEnabled
        nightModeEnabled = newNightModeEnabled
        AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
        AppPreferences.setNightModeEnabled(this, nightModeEnabled)

        if (showPreview != newShowPreview) {
             showPreview = newShowPreview
             AppPreferences.setShowPreview(this, showPreview)
             updateOverlaySize()
        }

        if (isSurfaceCreated) {
            startStream()
        }

        // Apply flashlight, night mode and zoom after stream starts
        Handler(Looper.getMainLooper()).postDelayed({
            applyFlashlight()
            updateNightModeSensor()
            applyZoom()
        }, 1000)

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !openGlView.holder.surface.isValid) return
        
        try {
            if (!::rtspServerCamera.isInitialized) {
                rtspServerCamera = RtspServerCamera2(openGlView, this, 8554)
            }
            
            if (!rtspServerCamera.isStreaming) {
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
                    rtspServerCamera.prepareAudio(64 * 1024, 44100, true, false, false)
                } else {
                    rtspServerCamera.disableAudio()
                }

                // Check and set Codec
                val selectedCodec = when (videoCodec) {
                    "H265" -> VideoCodec.H265
                    "AV1" -> VideoCodec.AV1
                    // "VP9" was offered in both UIs but silently fell back to H.264.
                    // It is gone from the pickers; this branch only catches stale prefs.
                    else -> VideoCodec.H264
                }
                
                rtspServerCamera.setVideoCodec(selectedCodec)
                android.util.Log.d("CctvServerService", "Selected codec: $selectedCodec ($videoCodec)")

                // The "Force Software Codec" switch was previously persisted and even
                // restarted the stream, but was never applied to the encoder. Wire it to
                // the API that actually selects the codec implementation.
                val codecType = if (forceSoftware) {
                    CodecUtil.CodecType.SOFTWARE
                } else {
                    CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                }
                rtspServerCamera.forceCodecType(codecType, codecType)
                android.util.Log.d("CctvServerService", "Codec type: $codecType")

                // Set authentication
                if (authEnabled && authUsername.isNotEmpty() && authPassword.isNotEmpty()) {
                    rtspServerCamera.getStreamClient().setAuthorization(authUsername, authPassword)
                    android.util.Log.d("CctvServerService", "RTSP auth enabled for user: $authUsername")
                } else {
                    rtspServerCamera.getStreamClient().setAuthorization("", "")
                    android.util.Log.d("CctvServerService", "RTSP auth disabled")
                }

                if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                    rtspServerCamera.startStream()
                    applyTimestampOverlay()
                    activeCodec = videoCodec
                } else {
                    android.util.Log.w("CctvServerService", "Codec $selectedCodec preparation failed, falling back to H264")
                    rtspServerCamera.setVideoCodec(VideoCodec.H264)
                    if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                         rtspServerCamera.startStream()
                         applyTimestampOverlay()
                         // Record that THIS session fell back, but do NOT overwrite the
                         // user's stored choice. prepareVideo can fail transiently -- a
                         // quick stop/start was enough to drop a working H.265 stream --
                         // and persisting H264 silently threw away a setting the device
                         // is perfectly capable of honouring on the next attempt.
                         activeCodec = "H264"
                    } else {
                         android.util.Log.e("CctvServerService", "H264 fallback preparation also failed.")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyTimestampOverlay() {
        if (!showTimestamp) {
            timestampHandler.removeCallbacks(timestampRunnable)
            textFilter = null
            return
        }

        try {
            val filter = TextObjectFilterRender()
            if (::rtspServerCamera.isInitialized) {
                rtspServerCamera.getGlInterface().setFilter(filter)
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

    private fun getMaxCameraResolution(): Pair<Int, Int> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Prefer the back camera rather than whichever id happens to be first --
            // on many devices id 0 is not the sensor actually being streamed, so the
            // "Max" resolution could be resolved from the wrong camera entirely.
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return Pair(1920, 1080)

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
            return Pair(maxSize.width, maxSize.height)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to get max resolution", e)
            return Pair(1920, 1080)
        }
    }

    private fun applyFlashlight() {
        if (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming) return
        try {
            if (flashlightEnabled && !isLanternOn) {
                rtspServerCamera.enableLantern()
                isLanternOn = true
                android.util.Log.d("CctvServerService", "Flashlight ON")
            } else if (!flashlightEnabled && isLanternOn) {
                rtspServerCamera.disableLantern()
                isLanternOn = false
                android.util.Log.d("CctvServerService", "Flashlight OFF")
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to toggle flashlight", e)
        }
    }

    /**
     * Applies [zoomLevel] to the live camera, clamped to the hardware's actual zoom
     * range -- [AppPreferences]'s generic 1.0-8.0 bounds are only a UI-layer default;
     * some devices support less, a few support more.
     */
    private fun applyZoom() {
        if (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming) return
        try {
            val range = rtspServerCamera.zoomRange
            val clamped = zoomLevel.coerceIn(range.lower, range.upper)
            rtspServerCamera.zoom = clamped
            android.util.Log.d("CctvServerService", "Zoom set to $clamped (requested $zoomLevel, range=$range)")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to set zoom", e)
        }
    }

    /** Real hardware bounds once the camera is open, else the generic AppPreferences bounds. */
    private fun currentZoomRangePair(): Pair<Float, Float> {
        return try {
            if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                val r = rtspServerCamera.zoomRange
                Pair(r.lower, r.upper)
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
        if (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming) return
        try {
            rtspServerCamera.setVideoBitrateOnFly(bitrateKbps * 1024)
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
        if (::rtspServerCamera.isInitialized && !rtspServerCamera.isStreaming) {
             startStream()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
            rtspServerCamera.stopStream()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        snapshotHandler.removeCallbacks(snapshotRunnable)
        timestampHandler.removeCallbacks(timestampRunnable)
        sensorManager?.unregisterListener(lightSensorListener)
        
        webServer.stop()
        
        if (::rtspServerCamera.isInitialized) { 
            try {
                if (rtspServerCamera.isStreaming) {
                    rtspServerCamera.stopStream()
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