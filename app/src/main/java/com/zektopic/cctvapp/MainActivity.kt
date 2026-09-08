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
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.databinding.ActivityMainBinding
import com.zektopic.cctvapp.service.CctvServerService
import com.zektopic.cctvapp.settings.AppPreferences
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.web.WebAuth
import com.zektopic.cctvapp.web.WebServer
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * CAMERA is the only hard requirement. RECORD_AUDIO is requested alongside it so the
     * optional audio toggle works without a second prompt, POST_NOTIFICATIONS is needed
     * from API 33 for the foreground-service notification to appear at all, and
     * ACCESS_FINE_LOCATION is requested because Android 8.1+ won't hand back a real
     * Wi-Fi RSSI to an app without it (see DeviceStatsUtil.getWifiStrength) -- without
     * this the dashboard's Wi-Fi signal readout is permanently unavailable.
     */
    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Only needed pre-scoped-storage: API 29+ writes its own gallery recordings
            // via MediaStore without any permission at all.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    /** Only these block the server from running. */
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

    private val permissionRequestCode = 100

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Debounces the zoom slider's live push to SettingsRepository: a drag fires
    // addOnChangeListener far more often than a toggle fires its listener -- see
    // setupZoomSlider().
    private var zoomDebounceJob: Job? = null

    // Populated in onCreate() from the camera's actual supported sizes; this is only
    // the fallback used if that query comes back empty.
    private var resolutions: List<String> = DEFAULT_RESOLUTIONS
    private val codecs = arrayOf("H264", "H265")
    private val overlayPositions = arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
    private val overlaySizes = arrayOf("Small", "Medium", "Large")
    private val bitrateLabels = arrayOf("500 Kbps", "1 Mbps", "2 Mbps", "4 Mbps", "6 Mbps", "8 Mbps")
    private val bitrateKbpsValues = intArrayOf(500, 1000, 2000, 4000, 6000, 8000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceStateRepository.ensureLoaded(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resolutions = CameraResolutionUtil.getSupportedResolutions(this)
            .map { "${it.first}x${it.second}" }
            .ifEmpty { DEFAULT_RESOLUTIONS }

        val (zoomMin, zoomMax) = CameraResolutionUtil.getZoomRange(this)
        binding.sliderZoomLevel.valueFrom = zoomMin
        binding.sliderZoomLevel.valueTo = zoomMax

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

        (binding.spinnerBitrate as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitrateLabels)
        )
    }

    private fun loadSavedSettings() {
        val s = ServiceStateRepository.current

        // Load saved codec
        (binding.spinnerCodec as? AutoCompleteTextView)?.setText(
            if (s.videoCodec in codecs) s.videoCodec else codecs.first(), false
        )

        // Load saved resolution
        val savedResolution = "${s.videoWidth}x${s.videoHeight}"
        (binding.spinnerResolution as? AutoCompleteTextView)?.setText(
            if (savedResolution in resolutions) savedResolution else resolutions.first(), false
        )

        // Load saved bitrate
        val savedBitrateIndex = bitrateKbpsValues.indexOf(s.bitrateKbps)
            .let { if (it >= 0) it else bitrateKbpsValues.indexOf(AppPreferences.DEFAULT_BITRATE_KBPS) }
        (binding.spinnerBitrate as? AutoCompleteTextView)?.setText(bitrateLabels[savedBitrateIndex], false)

        // Load saved auth settings
        binding.switchAuth.isChecked = s.authEnabled
        binding.editUsername.setText(s.authUsername)
        binding.editPassword.setText(s.authPassword)
        setAuthFieldsEnabled(s.authEnabled)

        // Load saved overlay settings
        binding.switchTimestamp.isChecked = s.showTimestamp
        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setText(
            if (s.timestampPosition in overlayPositions) s.timestampPosition else overlayPositions.first(), false
        )
        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setText(
            if (s.timestampSize in overlaySizes) s.timestampSize else overlaySizes[1], false
        )

        // Load saved flashlight & night mode settings
        binding.switchFlashlight.isChecked = s.flashlightEnabled
        binding.switchNightMode.isChecked = s.nightModeEnabled
        binding.switchVerticalFlip.isChecked = s.verticalFlipEnabled
        binding.sliderZoomLevel.value = s.zoomLevel
            .coerceIn(binding.sliderZoomLevel.valueFrom, binding.sliderZoomLevel.valueTo)

        // Load security settings
        binding.switchWebAuth.isChecked = s.webAuthEnabled
        binding.switchAudio.isChecked = s.audioEnabled

        // Startup behavior flags aren't part of ServiceSettings -- the service never
        // reads them, so they stay direct AppPreferences reads.
        binding.switchAutoStart.isChecked = AppPreferences.getAutoStartOnLaunch(this)

        // Load saved recording settings
        binding.switchRecordToGallery.isChecked = s.recordToGalleryEnabled
        binding.editRecordSegmentMinutes.setText(s.recordSegmentMinutes.toString())
        binding.editRecordStorageThresholdPercent.setText(s.recordStorageThresholdPercent.toString())
    }

    private fun setAuthFieldsEnabled(enabled: Boolean) {
        binding.layoutUsername.isEnabled = enabled
        binding.editUsername.isEnabled = enabled
        binding.layoutPassword.isEnabled = enabled
        binding.editPassword.isEnabled = enabled
        binding.btnGeneratePassword.isEnabled = enabled
    }

    // startServer() decides the resulting state itself, because it can refuse (missing
    // camera permission). The listener must not assert `true` afterwards or it overwrites
    // that refusal and leaves the switch on with no service behind it.
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
                ServiceStateRepository.updateRuntime { it.copy(switchCameraRequest = it.switchCameraRequest + 1) }
            }
        }

        (binding.spinnerResolution as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val (width, height) = parseResolution(resolutions[position])
            ServiceStateRepository.updateSettings(this) { it.copy(videoWidth = width, videoHeight = height) }
        }

        (binding.spinnerCodec as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            ServiceStateRepository.updateSettings(this) { it.copy(videoCodec = codecs[position]) }
        }

        (binding.spinnerBitrate as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val kbps = bitrateKbpsValues[position]
            ServiceStateRepository.updateSettings(this) { it.copy(bitrateKbps = kbps) }
        }

        // Auth listeners
        binding.switchAuth.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(authEnabled = isChecked) }
            setAuthFieldsEnabled(isChecked)
            updateNetworkInfo()
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
            ServiceStateRepository.updateSettings(this) { it.copy(showTimestamp = isChecked) }
        }

        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            ServiceStateRepository.updateSettings(this) { it.copy(timestampPosition = overlayPositions[position]) }
        }

        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            ServiceStateRepository.updateSettings(this) { it.copy(timestampSize = overlaySizes[position]) }
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
            ServiceStateRepository.updateSettings(this) { it.copy(flashlightEnabled = isChecked) }
        }

        binding.switchNightMode.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(nightModeEnabled = isChecked) }
        }

        binding.switchVerticalFlip.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(verticalFlipEnabled = isChecked) }
        }

        setupZoomSlider()

        binding.switchWebAuth.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(webAuthEnabled = isChecked) }
            if (isChecked) showGeneratedPasswordIfAny()
        }

        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(audioEnabled = isChecked) }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAutoStartOnLaunch(this, isChecked)
        }

        binding.switchRecordToGallery.setOnCheckedChangeListener { _, isChecked ->
            ServiceStateRepository.updateSettings(this) { it.copy(recordToGalleryEnabled = isChecked) }
        }

        binding.editRecordSegmentMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val minutes = binding.editRecordSegmentMinutes.text.toString().toIntOrNull()
                ?.coerceIn(AppPreferences.RECORD_SEGMENT_MINUTES_MIN, AppPreferences.RECORD_SEGMENT_MINUTES_MAX)
                ?: AppPreferences.DEFAULT_RECORD_SEGMENT_MINUTES
            binding.editRecordSegmentMinutes.setText(minutes.toString())
            ServiceStateRepository.updateSettings(this) { it.copy(recordSegmentMinutes = minutes) }
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
            ServiceStateRepository.updateSettings(this) { it.copy(recordStorageThresholdPercent = percent) }
        }
    }

    /**
     * Debounces the push to [ServiceStateRepository] (and, transitively, to a running
     * service's live camera zoom) -- otherwise a drag would call it dozens of times a
     * second. The debounce is flushed immediately on release so the final value is never
     * delayed.
     */
    private fun setupZoomSlider() {
        binding.sliderZoomLevel.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            zoomDebounceJob?.cancel()
            zoomDebounceJob = activityScope.launch {
                delay(120)
                ServiceStateRepository.updateSettings(this@MainActivity) { it.copy(zoomLevel = value) }
            }
        }
        binding.sliderZoomLevel.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                zoomDebounceJob?.cancel()
                ServiceStateRepository.updateSettings(this@MainActivity) { it.copy(zoomLevel = slider.value) }
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

        ensureServiceStarted()
        updateServerStatus(true)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Makes sure the service process is actually running. No settings are carried on
     * this intent -- [CctvServerService] loads them straight from [ServiceStateRepository]
     * (the same data source every setting listener in this activity writes to directly),
     * so a live service already has whatever the current values are.
     */
    private fun ensureServiceStarted() {
        if (!binding.switchServer.isChecked) return

        val intent = Intent(this, CctvServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private val DEFAULT_RESOLUTION = Pair(640, 480)

        /** Used only if querying the camera's supported sizes comes back empty. */
        private val DEFAULT_RESOLUTIONS = listOf("640x480", "1280x720", "1920x1080")

        /**
         * Parses a "WIDTHxHEIGHT" label.
         *
         * The picker is an AutoCompleteTextView, so its contents are whatever the user
         * typed -- `parts[0].toInt()` on that threw NumberFormatException and took the
         * app down. Anything unparseable now falls back to the default resolution.
         */
        fun parseResolution(value: String): Pair<Int, Int> {
            val trimmed = value.trim()

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
            val s = ServiceStateRepository.current
            if (s.authEnabled && s.authUsername.isNotEmpty() && s.authPassword.isNotEmpty()) {
                binding.textRtspUrl.text = "rtsp://${s.authUsername}:${s.authPassword}@$ip:8554/stream"
            } else {
                binding.textRtspUrl.text = "rtsp://$ip:8554/stream"
            }
            binding.textWebUrl.text = "http://$ip:${WebServer.PORT}"
        } else {
            binding.textRtspUrl.text = getString(R.string.ip_not_available)
            binding.textWebUrl.text = getString(R.string.ip_not_available)
        }
    }

    /**
     * Saves the credential fields and refreshes the displayed URLs. `SettingsRepository`'s
     * data-class equality already makes this a no-op if neither field actually changed,
     * so there's no need for a manual "did it change" check here anymore.
     */
    private fun applyCredentialsIfChanged() {
        val username = binding.editUsername.text.toString()
        val password = binding.editPassword.text.toString()
        ServiceStateRepository.updateSettings(this) { it.copy(authUsername = username, authPassword = password) }
        updateNetworkInfo()
    }

    /**
     * Shows the password generated on first run so the user is not locked out of the
     * now-authenticated dashboard.
     */
    private fun showGeneratedPasswordIfAny() {
        val generated = AppPreferences.seedCredentialsIfMissing(this) ?: return

        // seedCredentialsIfMissing writes straight to AppPreferences (it runs before
        // SettingsRepository is necessarily even loaded) -- pull the username it picked
        // back through SettingsRepository.update so the shared repository doesn't go
        // stale relative to what's actually persisted.
        val username = AppPreferences.getUsername(this)
        ServiceStateRepository.updateSettings(this) { it.copy(authUsername = username, authPassword = generated) }

        binding.editUsername.setText(username)
        binding.editPassword.setText(generated)

        // Only claim the dashboard is protected when it actually is. The password is
        // seeded regardless of the toggle, so with it off the old wording told the user
        // they were covered while the dashboard stayed reachable by anyone on the network.
        val message = if (ServiceStateRepository.current.webAuthEnabled) {
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

    private fun requestPermissionsIfNeeded() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
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
        if (!allPermissionsGranted() || !isIgnoringBatteryOptimizations()) return
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

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
