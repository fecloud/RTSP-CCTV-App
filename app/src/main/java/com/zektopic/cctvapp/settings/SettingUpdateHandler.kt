package com.zektopic.cctvapp.settings

import android.content.Context
import com.zektopic.cctvapp.camera.CameraResolutionUtil

/**
 * Handles a single dashboard setting change: `key`/`value` come straight from
 * [WebServer]'s `onSettingUpdate` callback (a NanoHTTPD worker thread). Each branch
 * calls [SettingsRepository.update], which persists to [AppPreferences] and publishes
 * the new snapshot synchronously on the calling thread -- so the dashboard's next
 * `/status` poll reflects the change with no extra handoff needed, and (since
 * [SettingsRepository] is the same data source [MainActivity] and [CctvServerService]
 * use) a live service picks it up immediately too, with no `Intent` involved.
 *
 * No side effect is called here at all: [CctvServerService]'s reactive
 * settings-effects collector (see `startSettingsEffectsCollector`) diffs old vs. new
 * [ServiceSettings] and decides which [SettingEffects] to run. This class's only job is
 * intent -> state.
 */
class SettingUpdateHandler(private val context: Context) {
    fun handle(key: String, value: String) {
        when (key) {
            "show_timestamp" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(showTimestamp = enabled) }
            }
            "timestamp_position" -> {
                SettingsRepository.update(context) { it.copy(timestampPosition = value) }
            }
            "timestamp_size" -> {
                SettingsRepository.update(context) { it.copy(timestampSize = value) }
            }
            "flashlight_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(flashlightEnabled = enabled) }
            }
            "night_mode_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(nightModeEnabled = enabled) }
            }
            "vertical_flip_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(verticalFlipEnabled = enabled) }
            }
            "zoom_level" -> {
                value.toFloatOrNull()?.let { requested ->
                    val (min, max) = CameraResolutionUtil.getZoomRange(context)
                    val coerced = requested.coerceIn(min, max)
                    SettingsRepository.update(context) { it.copy(zoomLevel = coerced) }
                }
            }
            "bitrate_kbps" -> {
                value.toIntOrNull()?.let { requested ->
                    val coerced = requested.coerceIn(AppPreferences.BITRATE_MIN_KBPS, AppPreferences.BITRATE_MAX_KBPS)
                    SettingsRepository.update(context) { it.copy(bitrateKbps = coerced) }
                }
            }
            "show_preview" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(showPreview = enabled) }
            }
            "audio_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(audioEnabled = enabled) }
            }
            "web_auth_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(webAuthEnabled = enabled) }
            }
            "record_to_gallery_enabled" -> {
                val enabled = value.toBoolean()
                SettingsRepository.update(context) { it.copy(recordToGalleryEnabled = enabled) }
            }
            "record_segment_minutes" -> {
                value.toIntOrNull()?.let { requested ->
                    val coerced = requested.coerceIn(
                        AppPreferences.RECORD_SEGMENT_MINUTES_MIN, AppPreferences.RECORD_SEGMENT_MINUTES_MAX
                    )
                    SettingsRepository.update(context) { it.copy(recordSegmentMinutes = coerced) }
                }
            }
            "record_storage_threshold_percent" -> {
                value.toIntOrNull()?.let { requested ->
                    val coerced = requested.coerceIn(
                        AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MIN,
                        AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MAX
                    )
                    SettingsRepository.update(context) { it.copy(recordStorageThresholdPercent = coerced) }
                }
            }
        }
    }

    /** Handles `/action/set-codec`, which isn't part of the generic key/value scheme above. */
    fun handleCodec(codec: String) {
        AppPreferences.setVideoCodec(context, codec)
        SettingsRepository.update(context) { it.copy(videoCodec = codec) }
    }

    /** Handles `/action/set-resolution`, which isn't part of the generic key/value scheme above. */
    fun handleResolution(width: Int, height: Int) {
        AppPreferences.setResolution(context, width, height)
        SettingsRepository.update(context) { it.copy(videoWidth = width, videoHeight = height) }
    }

    /** Handles `/action/set-auth`, which isn't part of the generic key/value scheme above. */
    fun handleAuth(enabled: Boolean, username: String, password: String) {
        AppPreferences.setAuthEnabled(context, enabled)
        AppPreferences.setUsername(context, username)
        AppPreferences.setPassword(context, password)
        SettingsRepository.update(context) {
            it.copy(authEnabled = enabled, authUsername = username, authPassword = password)
        }
    }
}
