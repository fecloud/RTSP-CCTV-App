package com.zektopic.cctvapp

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.zektopic.cctvapp.databinding.ActivityMainBinding
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * CAMERA is the only hard requirement. RECORD_AUDIO is requested alongside it so the
     * optional audio toggle works without a second prompt, and POST_NOTIFICATIONS is
     * needed from API 33 for the foreground-service notification to appear at all.
     */
    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Only needed pre-scoped-storage: API 29+ writes its own gallery recordings,
            // and queries/streams them back out for the /recordings page, via MediaStore
            // without any permission at all.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    /** Only these block the server from running. */
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

    private val permissionRequestCode = 100

    // Debounces the zoom slider's live push to the service: a drag fires
    // addOnChangeListener far more often than a toggle fires its listener, and each
    // push is a startService() call.
    private val zoomDebounceHandler = Handler(Looper.getMainLooper())
    private var zoomDebounceRunnable: Runnable? = null

    // Same debouncing, for the bitrate slider.
    private val bitrateDebounceHandler = Handler(Looper.getMainLooper())
    private var bitrateDebounceRunnable: Runnable? = null

    private val resolutions = arrayOf("640x480", "1280x720", "1920x1080", "Max")
    private val codecs = arrayOf("H264", "H265", "AV1")
    private val overlayPositions = arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
    private val overlaySizes = arrayOf("Small", "Medium", "Large")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        requestPermissionsIfNeeded()
        updateNetworkInfo()
        showGeneratedPasswordIfAny()
        autoStartServerIfNeeded()
    }

    private fun setupViews() {
        setupSpinners()
        loadSavedSettings()
        setupListeners()
        updateServerStatus(isServiceRunning(CctvServerService::class.java))
    }

    private fun setupSpinners() {
        (binding.spinnerResolution as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        )

        (binding.spinnerCodec as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecs)
        )

        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, overlayPositions)
        )

        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, overlaySizes)
        )
    }

    private fun loadSavedSettings() {
        // Load saved codec
        val savedCodec = AppPreferences.getVideoCodec(this)
        (binding.spinnerCodec as? AutoCompleteTextView)?.setText(
            if (savedCodec in codecs) savedCodec else codecs.first(), false
        )

        // Load saved resolution
        val savedWidth = AppPreferences.getVideoWidth(this)
        val savedHeight = AppPreferences.getVideoHeight(this)
        val savedResolution = if (savedWidth == 0 && savedHeight == 0) "Max" else "${savedWidth}x${savedHeight}"
        (binding.spinnerResolution as? AutoCompleteTextView)?.setText(
            if (savedResolution in resolutions) savedResolution else resolutions.first(), false
        )

        // Load saved bitrate
        binding.sliderBitrate.value = AppPreferences.getBitrateKbps(this).toFloat()
            .coerceIn(binding.sliderBitrate.valueFrom, binding.sliderBitrate.valueTo)

        // Load saved toggles
        binding.switchForceSoftware.isChecked = AppPreferences.getForceSoftware(this)
        binding.switchPreview.isChecked = AppPreferences.getShowPreview(this)

        // Load saved auth settings
        val authEnabled = AppPreferences.getAuthEnabled(this)
        binding.switchAuth.isChecked = authEnabled
        binding.editUsername.setText(AppPreferences.getUsername(this))
        binding.editPassword.setText(AppPreferences.getPassword(this))
        setAuthFieldsEnabled(authEnabled)

        // Load saved overlay settings
        binding.switchTimestamp.isChecked = AppPreferences.getShowTimestamp(this)
        val savedPosition = AppPreferences.getTimestampPosition(this)
        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setText(
            if (savedPosition in overlayPositions) savedPosition else overlayPositions.first(), false
        )
        val savedSize = AppPreferences.getTimestampSize(this)
        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setText(
            if (savedSize in overlaySizes) savedSize else overlaySizes[1], false
        )

        // Load saved flashlight & night mode settings
        binding.switchFlashlight.isChecked = AppPreferences.getFlashlightEnabled(this)
        binding.switchNightMode.isChecked = AppPreferences.getNightModeEnabled(this)
        binding.switchVerticalFlip.isChecked = AppPreferences.getVerticalFlipEnabled(this)
        binding.sliderZoomLevel.value = AppPreferences.getZoomLevel(this)
            .coerceIn(binding.sliderZoomLevel.valueFrom, binding.sliderZoomLevel.valueTo)

        // Load security & startup settings
        binding.switchWebAuth.isChecked = AppPreferences.getWebAuthEnabled(this)
        binding.switchAudio.isChecked = AppPreferences.getAudioEnabled(this)
        binding.switchStartOnBoot.isChecked = AppPreferences.getStartOnBoot(this)
        binding.switchAutoStart.isChecked = AppPreferences.getAutoStartOnLaunch(this)

        // Load saved recording settings
        binding.switchRecordToGallery.isChecked = AppPreferences.getRecordToGalleryEnabled(this)
        binding.editRecordSegmentMinutes.setText(AppPreferences.getRecordSegmentMinutes(this).toString())
        binding.editRecordStorageThresholdPercent.setText(
            AppPreferences.getRecordStorageThresholdPercent(this).toString()
        )
    }

    private fun setAuthFieldsEnabled(enabled: Boolean) {
        binding.layoutUsername.isEnabled = enabled
        binding.editUsername.isEnabled = enabled
        binding.layoutPassword.isEnabled = enabled
        binding.editPassword.isEnabled = enabled
        binding.btnGeneratePassword.isEnabled = enabled
    }

    // startServer() decides the resulting state itself, because it can refuse (missing
    // overlay or camera permission). The listener must not assert `true` afterwards or
    // it overwrites that refusal and leaves the switch on with no service behind it.
    private val serverSwitchListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startServer()
            } else {
                stopService(Intent(this, CctvServerService::class.java))
                updateServerStatus(false)
            }
        }

    private fun setupListeners() {
        binding.switchServer.setOnCheckedChangeListener(serverSwitchListener)

        binding.btnSwitchCamera.setOnClickListener {
            if (binding.switchServer.isChecked) {
                sendServiceAction("ACTION_SWITCH_CAMERA")
            }
        }

        binding.switchPreview.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setShowPreview(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_PREVIEW"
                    putExtra("show_preview", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchForceSoftware.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setForceSoftware(this, isChecked)
            restartServer()
        }

        (binding.spinnerResolution as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val (width, height) = parseResolution(resolutions[position])
            AppPreferences.setResolution(this, width, height)
            restartServer()
        }

        (binding.spinnerCodec as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setVideoCodec(this, codecs[position])
            restartServer()
        }

        // Auth listeners
        binding.switchAuth.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAuthEnabled(this, isChecked)
            setAuthFieldsEnabled(isChecked)
            updateNetworkInfo()
            restartServer()
        }

        // Persist credentials on focus loss, but only restart the stream when they
        // actually changed -- tabbing through the fields used to tear the stream down
        // and bring it back up each time.
        binding.editUsername.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCredentialsIfChanged()
        }

        binding.editPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCredentialsIfChanged()
        }

        binding.btnGeneratePassword.setOnClickListener {
            binding.editPassword.setText(WebAuth.generatePassword(16))
            applyCredentialsIfChanged()
            Toast.makeText(this, R.string.password_generated, Toast.LENGTH_SHORT).show()
        }

        // Overlay listeners
        binding.switchTimestamp.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setShowTimestamp(this, isChecked)
            restartServer()
        }

        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setTimestampPosition(this, overlayPositions[position])
            restartServer()
        }

        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setTimestampSize(this, overlaySizes[position])
            restartServer()
        }

        // Copy buttons
        binding.btnCopyRtsp.setOnClickListener {
            copyToClipboard("RTSP URL", binding.textRtspUrl.text.toString())
        }

        binding.btnCopyWeb.setOnClickListener {
            copyToClipboard("Web URL", binding.textWebUrl.text.toString())
        }

        // Flashlight & Night Mode listeners
        binding.switchFlashlight.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setFlashlightEnabled(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_FLASHLIGHT"
                    putExtra("flashlight_enabled", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchNightMode.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setNightModeEnabled(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_NIGHT_MODE"
                    putExtra("night_mode_enabled", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchVerticalFlip.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setVerticalFlipEnabled(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_VERTICAL_FLIP"
                    putExtra("vertical_flip_enabled", isChecked)
                }
                startService(intent)
            }
        }

        setupZoomSlider()
        setupBitrateSlider()

        binding.switchWebAuth.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setWebAuthEnabled(this, isChecked)
            if (isChecked) showGeneratedPasswordIfAny()
            sendSettingToService("web_auth_enabled", isChecked.toString())
        }

        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAudioEnabled(this, isChecked)
            restartServer()
        }

        binding.switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setStartOnBoot(this, isChecked)
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAutoStartOnLaunch(this, isChecked)
        }

        binding.switchRecordToGallery.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setRecordToGalleryEnabled(this, isChecked)
            sendSettingToService("record_to_gallery_enabled", isChecked.toString())
        }

        binding.editRecordSegmentMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val minutes = binding.editRecordSegmentMinutes.text.toString().toIntOrNull()
                ?.coerceIn(AppPreferences.RECORD_SEGMENT_MINUTES_MIN, AppPreferences.RECORD_SEGMENT_MINUTES_MAX)
                ?: AppPreferences.DEFAULT_RECORD_SEGMENT_MINUTES
            binding.editRecordSegmentMinutes.setText(minutes.toString())
            AppPreferences.setRecordSegmentMinutes(this, minutes)
            sendSettingToService("record_segment_minutes", minutes.toString())
        }

        binding.editRecordStorageThresholdPercent.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val percent = binding.editRecordStorageThresholdPercent.text.toString().toIntOrNull()
                ?.coerceIn(
                    AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MIN,
                    AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MAX
                )
                ?: AppPreferences.DEFAULT_RECORD_STORAGE_THRESHOLD_PERCENT
            binding.editRecordStorageThresholdPercent.setText(percent.toString())
            AppPreferences.setRecordStorageThresholdPercent(this, percent)
            sendSettingToService("record_storage_threshold_percent", percent.toString())
        }
    }

    /**
     * Writes every tick to [AppPreferences] (cheap, matches [binding.sliderMotionSensitivity]'s
     * existing per-tick write), but debounces the actual push to the running service --
     * otherwise a drag would fire a startService() call dozens of times a second. The
     * debounce is flushed immediately on release so the final value is never delayed.
     */
    private fun setupZoomSlider() {
        binding.sliderZoomLevel.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            AppPreferences.setZoomLevel(this, value)
            zoomDebounceRunnable?.let { zoomDebounceHandler.removeCallbacks(it) }
            val runnable = Runnable { sendSettingToService("zoom_level", value.toString()) }
            zoomDebounceRunnable = runnable
            zoomDebounceHandler.postDelayed(runnable, 120)
        }
        binding.sliderZoomLevel.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                zoomDebounceRunnable?.let { zoomDebounceHandler.removeCallbacks(it) }
                sendSettingToService("zoom_level", slider.value.toString())
            }
        })
    }

    /** Same debounced push-while-dragging pattern as [setupZoomSlider]. */
    private fun setupBitrateSlider() {
        binding.sliderBitrate.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            AppPreferences.setBitrateKbps(this, value.toInt())
            bitrateDebounceRunnable?.let { bitrateDebounceHandler.removeCallbacks(it) }
            val runnable = Runnable { sendSettingToService("bitrate_kbps", value.toInt().toString()) }
            bitrateDebounceRunnable = runnable
            bitrateDebounceHandler.postDelayed(runnable, 120)
        }
        binding.sliderBitrate.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                bitrateDebounceRunnable?.let { bitrateDebounceHandler.removeCallbacks(it) }
                sendSettingToService("bitrate_kbps", slider.value.toInt().toString())
            }
        })
    }

    /**
     * Reflects the real service state in the UI.
     *
     * The listener is detached first: assigning `isChecked` fires the change listener,
     * which starts the service -- so simply *displaying* the current state used to
     * start the server, including during initial setup.
     */
    private fun updateServerStatus(isRunning: Boolean) {
        binding.switchServer.setOnCheckedChangeListener(null)
        binding.switchServer.isChecked = isRunning
        binding.switchServer.setOnCheckedChangeListener(serverSwitchListener)

        binding.statusDot.setBackgroundResource(
            if (isRunning) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun startServer() {
        if (!allPermissionsGranted()) {
            requestPermissionsIfNeeded()
            updateServerStatus(false)
            Toast.makeText(this, R.string.camera_permission_toast, Toast.LENGTH_LONG).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            updateServerStatus(false)
            Toast.makeText(this, R.string.overlay_permission_toast, Toast.LENGTH_LONG).show()
            return
        }

        if (!isIgnoringBatteryOptimizations()) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            updateServerStatus(false)
            Toast.makeText(this, R.string.battery_optimization_toast, Toast.LENGTH_LONG).show()
            return
        }

        restartServer()
        updateServerStatus(true)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun restartServer() {
        if (!binding.switchServer.isChecked) return

        val (width, height) = getSelectedResolution()
        val intent = Intent(this, CctvServerService::class.java).apply {
            putExtra("video_codec", binding.spinnerCodec.text.toString())
            putExtra("force_software", binding.switchForceSoftware.isChecked)
            putExtra("show_preview", binding.switchPreview.isChecked)
            putExtra("width", width)
            putExtra("height", height)
            putExtra("auth_enabled", binding.switchAuth.isChecked)
            putExtra("auth_username", binding.editUsername.text.toString())
            putExtra("auth_password", binding.editPassword.text.toString())
            putExtra("show_timestamp", binding.switchTimestamp.isChecked)
            putExtra("timestamp_position", binding.spinnerOverlayPosition.text.toString())
            putExtra("timestamp_size", binding.spinnerOverlaySize.text.toString())
            putExtra("flashlight_enabled", binding.switchFlashlight.isChecked)
            putExtra("night_mode_enabled", binding.switchNightMode.isChecked)
            putExtra("vertical_flip_enabled", binding.switchVerticalFlip.isChecked)
            putExtra("record_to_gallery_enabled", binding.switchRecordToGallery.isChecked)
            putExtra("record_segment_minutes", AppPreferences.getRecordSegmentMinutes(this@MainActivity))
            putExtra(
                "record_storage_threshold_percent",
                AppPreferences.getRecordStorageThresholdPercent(this@MainActivity)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Pushes a single setting to a running service without restarting the stream. */
    private fun sendSettingToService(key: String, value: String) {
        if (!binding.switchServer.isChecked) return
        val intent = Intent(this, CctvServerService::class.java).apply {
            action = "ACTION_SET_SETTING"
            putExtra("setting_key", key)
            putExtra("setting_value", value)
        }
        startService(intent)
    }

    private fun sendServiceAction(action: String) {
        Intent(this, CctvServerService::class.java).also { intent ->
            intent.action = action
            startService(intent)
        }
    }

    private fun getSelectedResolution(): Pair<Int, Int> =
        parseResolution(binding.spinnerResolution.text.toString())

    companion object {
        /** Sentinel meaning "ask the camera for its maximum"; resolved in the service. */
        val MAX_RESOLUTION = Pair(0, 0)
        private val DEFAULT_RESOLUTION = Pair(640, 480)

        /**
         * Parses a "WIDTHxHEIGHT" label, or "Max".
         *
         * The picker is an AutoCompleteTextView, so its contents are whatever the user
         * typed -- `parts[0].toInt()` on that threw NumberFormatException and took the
         * app down. Anything unparseable now falls back to the default resolution.
         */
        fun parseResolution(value: String): Pair<Int, Int> {
            val trimmed = value.trim()
            if (trimmed.equals("Max", ignoreCase = true)) return MAX_RESOLUTION

            val parts = trimmed.split("x", "X")
            if (parts.size != 2) return DEFAULT_RESOLUTION

            val width = parts[0].trim().toIntOrNull() ?: return DEFAULT_RESOLUTION
            val height = parts[1].trim().toIntOrNull() ?: return DEFAULT_RESOLUTION
            if (width <= 0 || height <= 0) return DEFAULT_RESOLUTION

            return Pair(width, height)
        }
    }

    private fun updateNetworkInfo() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Address = linkProperties?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress

        val ip = ipv4Address ?: getString(R.string.ip_not_available)
        binding.textIpAddress.text = ip

        // Set RTSP and Web URLs
        if (ipv4Address != null) {
            val authEnabled = AppPreferences.getAuthEnabled(this)
            val username = AppPreferences.getUsername(this)
            val password = AppPreferences.getPassword(this)
            if (authEnabled && username.isNotEmpty() && password.isNotEmpty()) {
                binding.textRtspUrl.text = "rtsp://$username:$password@$ip:8554/stream"
            } else {
                binding.textRtspUrl.text = "rtsp://$ip:8554/stream"
            }
            binding.textWebUrl.text = "http://$ip:${WebServer.PORT}"
        } else {
            binding.textRtspUrl.text = getString(R.string.ip_not_available)
            binding.textWebUrl.text = getString(R.string.ip_not_available)
        }
    }

    /** Saves the credential fields, refreshing the URLs and the stream only if they changed. */
    private fun applyCredentialsIfChanged() {
        val username = binding.editUsername.text.toString()
        val password = binding.editPassword.text.toString()

        val changed = username != AppPreferences.getUsername(this) ||
            password != AppPreferences.getPassword(this)
        if (!changed) return

        AppPreferences.setUsername(this, username)
        AppPreferences.setPassword(this, password)
        updateNetworkInfo()
        restartServer()
    }

    /**
     * Shows the password generated on first run so the user is not locked out of the
     * now-authenticated dashboard.
     */
    private fun showGeneratedPasswordIfAny() {
        val generated = AppPreferences.seedCredentialsIfMissing(this) ?: return

        binding.editUsername.setText(AppPreferences.getUsername(this))
        binding.editPassword.setText(generated)

        // Only claim the dashboard is protected when it actually is. The password is
        // seeded regardless of the toggle, so with it off the old wording told the user
        // they were covered while the dashboard stayed reachable by anyone on the network.
        val message = if (AppPreferences.getWebAuthEnabled(this)) {
            getString(R.string.generated_password_message, generated)
        } else {
            getString(R.string.generated_password_message_unprotected, generated)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.generated_password_title)
            .setMessage(message)
            .setPositiveButton(R.string.generated_password_ok, null)
            .show()
    }

    /**
     * Requests whatever in [permissions] isn't granted yet -- not gated on
     * [allPermissionsGranted], which only checks [requiredPermissions] (CAMERA). An
     * install that already granted CAMERA in an earlier version satisfies that gate
     * forever, so a later update adding e.g. storage permissions to [permissions] would
     * otherwise never re-prompt for them.
     */
    private fun requestPermissionsIfNeeded() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(baseContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the server on launch only when the user has explicitly asked for it.
     *
     * This used to fire unconditionally, so merely opening the app switched the camera
     * on and published a stream to the network without any confirmation.
     */
    private fun autoStartServerIfNeeded() {
        if (!AppPreferences.getAutoStartOnLaunch(this)) return
        if (binding.switchServer.isChecked) return
        if (!allPermissionsGranted() || !Settings.canDrawOverlays(this) || !isIgnoringBatteryOptimizations()) return
        binding.switchServer.isChecked = true
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
