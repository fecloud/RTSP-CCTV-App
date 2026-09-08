package com.zektopic.cctvapp.settings

import android.content.Context
import android.content.SharedPreferences
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.web.WebAuth
import androidx.core.content.edit

/**
 * Centralized SharedPreferences helper for persisting user settings
 * across app restarts, service restarts, and device reboots.
 */
object AppPreferences {

    private const val PREFS_NAME = "cctv_app_prefs"

    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_VIDEO_WIDTH = "video_width"
    private const val KEY_VIDEO_HEIGHT = "video_height"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Video Codec ---
    fun getVideoCodec(context: Context): String =
        prefs(context).getString(KEY_VIDEO_CODEC, "H264") ?: "H264"

    fun setVideoCodec(context: Context, codec: String) {
        prefs(context).edit { putString(KEY_VIDEO_CODEC, codec) }
    }

    // --- Resolution ---
    fun getVideoWidth(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_WIDTH, 1920)

    fun getVideoHeight(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_HEIGHT, 1080)

    fun setResolution(context: Context, width: Int, height: Int) {
        prefs(context).edit {
            putInt(KEY_VIDEO_WIDTH, width)
                .putInt(KEY_VIDEO_HEIGHT, height)
        }
    }

    // --- RTSP Authentication ---
    private const val KEY_AUTH_ENABLED = "rtsp_auth_enabled"
    private const val KEY_AUTH_USERNAME = "rtsp_username"
    private const val KEY_AUTH_PASSWORD = "rtsp_password"

    fun getAuthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTH_ENABLED, false)

    fun setAuthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTH_ENABLED, enabled) }
    }

    fun getUsername(context: Context): String =
        prefs(context).getString(KEY_AUTH_USERNAME, "") ?: ""

    fun setUsername(context: Context, username: String) {
        prefs(context).edit { putString(KEY_AUTH_USERNAME, username) }
    }

    fun getPassword(context: Context): String =
        prefs(context).getString(KEY_AUTH_PASSWORD, "") ?: ""

    fun setPassword(context: Context, password: String) {
        prefs(context).edit { putString(KEY_AUTH_PASSWORD, password) }
    }

    // --- Timestamp Overlay ---
    // A single switch now controls the whole overlay (date+time together) --
    // it used to be two independent switches (KEY_SHOW_DATE was the other).
    private const val KEY_SHOW_TIMESTAMP = "show_timestamp"
    private const val KEY_TIMESTAMP_POSITION = "timestamp_position"

    fun getShowTimestamp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_TIMESTAMP, true)

    fun setShowTimestamp(context: Context, show: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHOW_TIMESTAMP, show) }
    }

    fun getTimestampPosition(context: Context): String =
        prefs(context).getString(KEY_TIMESTAMP_POSITION, "Top Left") ?: "Top Left"

    fun setTimestampPosition(context: Context, position: String) {
        prefs(context).edit { putString(KEY_TIMESTAMP_POSITION, position) }
    }

    private const val KEY_TIMESTAMP_SIZE = "timestamp_size"

    fun getTimestampSize(context: Context): String =
        prefs(context).getString(KEY_TIMESTAMP_SIZE, "Large") ?: "Large"

    fun setTimestampSize(context: Context, size: String) {
        prefs(context).edit { putString(KEY_TIMESTAMP_SIZE, size) }
    }

    // --- Flashlight & Night Mode ---
    private const val KEY_FLASHLIGHT_ENABLED = "flashlight_enabled"
    private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"

    fun getFlashlightEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLASHLIGHT_ENABLED, false)

    fun setFlashlightEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FLASHLIGHT_ENABLED, enabled) }
    }

    fun getNightModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NIGHT_MODE_ENABLED, false)

    fun setNightModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_NIGHT_MODE_ENABLED, enabled) }
    }

    // --- Vertical Flip ---
    // For cameras mounted upside-down (e.g. hung from a ceiling bracket). Flips
    // preview, RTSP stream and dashboard snapshot together since all three render
    // off the same GL surface.
    private const val KEY_VERTICAL_FLIP_ENABLED = "vertical_flip_enabled"

    fun getVerticalFlipEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERTICAL_FLIP_ENABLED, false)

    fun setVerticalFlipEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_VERTICAL_FLIP_ENABLED, enabled) }
    }

    // --- Zoom ---
    private const val KEY_ZOOM_LEVEL = "zoom_level"

    const val DEFAULT_ZOOM_LEVEL = 1.0f

    // The real Camera2 zoom range varies per device -- read straight from
    // CameraCharacteristics via CameraResolutionUtil.getZoomRange() rather than a
    // hardcoded constant, so a stored value can never be clamped tighter (or looser)
    // than what the hardware actually supports. CctvServerService clamps again against
    // the live camera session's range before calling setZoom(), which can differ
    // slightly once a session is actually open.
    fun getZoomLevel(context: Context): Float {
        val (min, max) = CameraResolutionUtil.getZoomRange(context)
        return prefs(context).getFloat(KEY_ZOOM_LEVEL, DEFAULT_ZOOM_LEVEL).coerceIn(min, max)
    }

    fun setZoomLevel(context: Context, zoom: Float) {
        val (min, max) = CameraResolutionUtil.getZoomRange(context)
        prefs(context).edit { putFloat(KEY_ZOOM_LEVEL, zoom.coerceIn(min, max)) }
    }

    // --- Bitrate ---
    private const val KEY_BITRATE_KBPS = "bitrate_kbps"

    const val BITRATE_MIN_KBPS = 500
    const val BITRATE_MAX_KBPS = 8000
    const val DEFAULT_BITRATE_KBPS = 4000

    fun getBitrateKbps(context: Context): Int =
        prefs(context).getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS).coerceIn(BITRATE_MIN_KBPS, BITRATE_MAX_KBPS)

    fun setBitrateKbps(context: Context, kbps: Int) {
        prefs(context).edit {
            putInt(KEY_BITRATE_KBPS, kbps.coerceIn(BITRATE_MIN_KBPS, BITRATE_MAX_KBPS))
        }
    }

    // --- Web dashboard security ---
    private const val KEY_WEB_AUTH_ENABLED = "web_auth_enabled"
    private const val KEY_CREDENTIALS_SEEDED = "credentials_seeded"

    /**
     * Whether the dashboard on port 8081 requires HTTP Basic auth.
     *
     * Defaults to true: the dashboard exposes the live camera and every setting, so
     * "open to the whole LAN" is not a safe default. Users who want the old behaviour
     * can turn it off in the app or from the dashboard itself.
     */
    fun getWebAuthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_AUTH_ENABLED, false)

    fun setWebAuthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WEB_AUTH_ENABLED, enabled) }
    }

    /**
     * Seeds a username and a strong generated password the first time the app runs with
     * dashboard auth enabled, and returns the generated password so the caller can show
     * it. Returns null if credentials already exist -- without this, enabling auth by
     * default would lock users out of their own camera with no way back in.
     */
    fun seedCredentialsIfMissing(context: Context): String? {
        val preferences = prefs(context)
        if (preferences.getBoolean(KEY_CREDENTIALS_SEEDED, false)) return null
        if (getUsername(context).isNotEmpty() && getPassword(context).isNotEmpty()) {
            preferences.edit { putBoolean(KEY_CREDENTIALS_SEEDED, true) }
            return null
        }

        val password = WebAuth.generatePassword(16)
        preferences.edit {
            putString(KEY_AUTH_USERNAME, "admin")
                .putString(KEY_AUTH_PASSWORD, password)
                .putBoolean(KEY_CREDENTIALS_SEEDED, true)
        }
        return password
    }

    // --- Audio ---
    private const val KEY_AUDIO_ENABLED = "audio_enabled"

    /**
     * Off by default. Enabling it makes the service claim the microphone
     * foreground-service type and require the RECORD_AUDIO grant.
     */
    fun getAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_ENABLED, false)

    fun setAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUDIO_ENABLED, enabled) }
    }

    // --- Startup behaviour ---
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_AUTO_START_ON_LAUNCH = "auto_start_on_launch"

    /** Off by default: a camera server should not silently start itself after a reboot. */
    fun getStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_START_ON_BOOT, enabled) }
    }

    /** Off by default: opening the app should not immediately begin streaming. */
    fun getAutoStartOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_LAUNCH, false)

    fun setAutoStartOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_START_ON_LAUNCH, enabled) }
    }

    // --- Record to Gallery ---
    private const val KEY_RECORD_TO_GALLERY_ENABLED = "record_to_gallery_enabled"
    private const val KEY_RECORD_SEGMENT_MINUTES = "record_segment_minutes"
    private const val KEY_RECORD_STORAGE_THRESHOLD_PERCENT = "record_storage_threshold_percent"

    const val RECORD_SEGMENT_MINUTES_MIN = 1
    const val RECORD_SEGMENT_MINUTES_MAX = 60
    const val DEFAULT_RECORD_SEGMENT_MINUTES = 5

    /**
     * Loop-recording retention: once local storage usage crosses this percentage,
     * starting a new segment deletes the oldest finished ones -- one at a time, until
     * usage drops back under it -- otherwise a server left running would fill the
     * device's storage with 5-minute clips indefinitely.
     */
    const val RECORD_STORAGE_THRESHOLD_PERCENT_MIN = 50
    const val RECORD_STORAGE_THRESHOLD_PERCENT_MAX = 95
    const val DEFAULT_RECORD_STORAGE_THRESHOLD_PERCENT = 90

    /** Off by default: recording to the gallery uses storage the user should opt into. */
    fun getRecordToGalleryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECORD_TO_GALLERY_ENABLED, false)

    fun setRecordToGalleryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_RECORD_TO_GALLERY_ENABLED, enabled) }
    }

    fun getRecordSegmentMinutes(context: Context): Int =
        prefs(context).getInt(KEY_RECORD_SEGMENT_MINUTES, DEFAULT_RECORD_SEGMENT_MINUTES)
            .coerceIn(RECORD_SEGMENT_MINUTES_MIN, RECORD_SEGMENT_MINUTES_MAX)

    fun setRecordSegmentMinutes(context: Context, minutes: Int) {
        prefs(context).edit {
            putInt(
                KEY_RECORD_SEGMENT_MINUTES,
                minutes.coerceIn(RECORD_SEGMENT_MINUTES_MIN, RECORD_SEGMENT_MINUTES_MAX)
            )
        }
    }

    fun getRecordStorageThresholdPercent(context: Context): Int =
        prefs(context).getInt(KEY_RECORD_STORAGE_THRESHOLD_PERCENT, DEFAULT_RECORD_STORAGE_THRESHOLD_PERCENT)
            .coerceIn(RECORD_STORAGE_THRESHOLD_PERCENT_MIN, RECORD_STORAGE_THRESHOLD_PERCENT_MAX)

    fun setRecordStorageThresholdPercent(context: Context, percent: Int) {
        prefs(context).edit {
            putInt(
                KEY_RECORD_STORAGE_THRESHOLD_PERCENT,
                percent.coerceIn(
                    RECORD_STORAGE_THRESHOLD_PERCENT_MIN,
                    RECORD_STORAGE_THRESHOLD_PERCENT_MAX
                )
            )
        }
    }
}
