package com.zektopic.cctvapp.settings

import android.content.Context
import com.zektopic.cctvapp.camera.CameraResolutionUtil

/**
 * Handles a single dashboard setting change: `key`/`value` come straight from
 * [WebServer]'s `onSettingUpdate` callback (a NanoHTTPD worker thread). Each branch
 * calls [ServiceStateRepository.updateSettings], which persists to [AppPreferences] and publishes
 * the new snapshot synchronously on the calling thread -- so the dashboard's next
 * `/status` poll reflects the change with no extra handoff needed, and (since
 * [ServiceStateRepository] is the same data source [MainActivity] and [CctvServerService]
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
            "video_codec" ->
                ServiceStateRepository.updateSettings(context) { it.copy(videoCodec = value) }
            "show_timestamp" ->
                ServiceStateRepository.updateSettings(context) { it.copy(showTimestamp = value.toBoolean()) }
            "timestamp_position" ->
                ServiceStateRepository.updateSettings(context) { it.copy(timestampPosition = value) }
            "timestamp_size" ->
                ServiceStateRepository.updateSettings(context) { it.copy(timestampSize = value) }
            "flashlight_enabled" ->
                ServiceStateRepository.updateSettings(context) { it.copy(flashlightEnabled = value.toBoolean()) }
            "night_mode_enabled" ->
                ServiceStateRepository.updateSettings(context) { it.copy(nightModeEnabled = value.toBoolean()) }
            "vertical_flip_enabled" ->
                ServiceStateRepository.updateSettings(context) { it.copy(verticalFlipEnabled = value.toBoolean()) }
            "zoom_level" -> value.toFloatOrNull()?.let { requested ->
                val (min, max) = CameraResolutionUtil.getZoomRange(context)
                val coerced = requested.coerceIn(min, max)
                ServiceStateRepository.updateSettings(context) { it.copy(zoomLevel = coerced) }
            }
            "bitrate_kbps" -> value.toIntOrNull()?.let { requested ->
                val coerced = requested.coerceIn(AppPreferences.BITRATE_MIN_KBPS, AppPreferences.BITRATE_MAX_KBPS)
                ServiceStateRepository.updateSettings(context) { it.copy(bitrateKbps = coerced) }
            }
            "web_auth_enabled" ->
                ServiceStateRepository.updateSettings(context) { it.copy(webAuthEnabled = value.toBoolean()) }
            "record_to_gallery_enabled" ->
                ServiceStateRepository.updateSettings(context) { it.copy(recordToGalleryEnabled = value.toBoolean()) }
            "record_segment_minutes" -> value.toIntOrNull()?.let { requested ->
                val coerced = requested.coerceIn(
                    AppPreferences.RECORD_SEGMENT_MINUTES_MIN, AppPreferences.RECORD_SEGMENT_MINUTES_MAX
                )
                ServiceStateRepository.updateSettings(context) { it.copy(recordSegmentMinutes = coerced) }
            }
            "record_storage_threshold_percent" -> value.toIntOrNull()?.let { requested ->
                val coerced = requested.coerceIn(
                    AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MIN,
                    AppPreferences.RECORD_STORAGE_THRESHOLD_PERCENT_MAX
                )
                ServiceStateRepository.updateSettings(context) { it.copy(recordStorageThresholdPercent = coerced) }
            }
        }
    }

    /** Handles `/action/set-resolution`, which isn't part of the generic key/value scheme above. */
    fun handleResolution(width: Int, height: Int) =
        ServiceStateRepository.updateSettings(context) { it.copy(videoWidth = width, videoHeight = height) }

    /** Handles `/action/set-auth`, which isn't part of the generic key/value scheme above. */
    fun handleAuth(enabled: Boolean, username: String, password: String) =
        ServiceStateRepository.updateSettings(context) {
            it.copy(authEnabled = enabled, authUsername = username, authPassword = password)
        }
}
