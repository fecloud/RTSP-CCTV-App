package com.zektopic.cctvapp.settings

import android.content.Context

/**
 * The stream/encoder/dashboard settings for one running [CctvServerService] instance.
 *
 * Immutable snapshot held in a `MutableStateFlow` on the service: every change produces
 * a new instance via `.copy(...)`, which is what lets a single reactive collector diff
 * old vs. new state to decide which side effects to run, instead of each call site
 * pairing a field write with an inline effect call. `data class` equality is also what
 * lets `StateFlow` conflate a no-op update (new value structurally equal to the old one)
 * for free, without every write site needing its own "did this actually change" guard.
 */
data class ServiceSettings(
    val videoWidth: Int = 640,
    val videoHeight: Int = 480,
    val videoCodec: String = "H264",
    /**
     * The codec actually negotiated with the encoder, which differs from [videoCodec] when
     * the requested one could not be prepared. Surfaced in /status so the dashboard can
     * show the truth without destroying the user's stored preference.
     */
    val activeCodec: String = "H264",
    val authEnabled: Boolean = false,
    val authUsername: String = "",
    val authPassword: String = "",
    val webAuthEnabled: Boolean = true,
    val audioEnabled: Boolean = false,
    val showTimestamp: Boolean = false,
    val timestampPosition: String = "Top Left",
    val timestampSize: String = "Medium",
    val flashlightEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val verticalFlipEnabled: Boolean = false,
    val zoomLevel: Float = AppPreferences.DEFAULT_ZOOM_LEVEL,
    val bitrateKbps: Int = AppPreferences.DEFAULT_BITRATE_KBPS,
    val recordToGalleryEnabled: Boolean = false,
    val recordSegmentMinutes: Int = AppPreferences.DEFAULT_RECORD_SEGMENT_MINUTES,
    val recordStorageThresholdPercent: Int = AppPreferences.DEFAULT_RECORD_STORAGE_THRESHOLD_PERCENT,
) {
    companion object {
        /**
         * Loads every persisted setting from [AppPreferences] as a fresh snapshot.
         * [activeCodec] is deliberately excluded -- it's not a stored preference, just
         * the codec actually negotiated with the encoder once a stream starts.
         */
        fun loadFromPreferences(context: Context): ServiceSettings = ServiceSettings(
            videoCodec = AppPreferences.getVideoCodec(context),
            videoWidth = AppPreferences.getVideoWidth(context),
            videoHeight = AppPreferences.getVideoHeight(context),
            authEnabled = AppPreferences.getAuthEnabled(context),
            authUsername = AppPreferences.getUsername(context),
            authPassword = AppPreferences.getPassword(context),
            showTimestamp = AppPreferences.getShowTimestamp(context),
            timestampPosition = AppPreferences.getTimestampPosition(context),
            timestampSize = AppPreferences.getTimestampSize(context),
            flashlightEnabled = AppPreferences.getFlashlightEnabled(context),
            nightModeEnabled = AppPreferences.getNightModeEnabled(context),
            verticalFlipEnabled = AppPreferences.getVerticalFlipEnabled(context),
            zoomLevel = AppPreferences.getZoomLevel(context),
            bitrateKbps = AppPreferences.getBitrateKbps(context),
            webAuthEnabled = AppPreferences.getWebAuthEnabled(context),
            audioEnabled = AppPreferences.getAudioEnabled(context),
            recordToGalleryEnabled = AppPreferences.getRecordToGalleryEnabled(context),
            recordSegmentMinutes = AppPreferences.getRecordSegmentMinutes(context),
            recordStorageThresholdPercent = AppPreferences.getRecordStorageThresholdPercent(context),
        )
    }
}

/**
 * Runtime status/commands published by [com.zektopic.cctvapp.service.CctvServerService],
 * not user prefs. Held in its own `MutableStateFlow` in [ServiceStateRepository] -- deliberately
 * *not* a field on [ServiceSettings] -- so live camera state and one-shot dashboard
 * commands never trigger [ServiceSettings]'s settings-effects diff/persist path, and vice
 * versa: a settings change never gets conflated with a runtime one.
 */
data class ServiceRuntimeState(
    /** True only once the camera exists and is actively streaming. */
    val isStreaming: Boolean = false,
    /**
     * True only while [com.zektopic.cctvapp.service.RecordingManager] has a segment
     * actively being written -- distinct from [ServiceSettings.recordToGalleryEnabled],
     * which just reflects whether the user turned the feature on (e.g. still true while the
     * stream itself is stopped, or during the brief gap between one segment finishing and the
     * next starting).
     */
    val isRecordingToGallery: Boolean = false,
    val zoomRange: Pair<Float, Float> = 1f to 1f,
    /**
     * Bumped by `WebServer` (via [ServiceStateRepository.updateRuntime]) to request an
     * action; [com.zektopic.cctvapp.service.CctvServerService] reacts to *any* change,
     * including a value that looks the same as last time -- these are one-shot
     * commands, not persisted state, so a plain boolean would get conflated away by
     * `StateFlow`'s no-op equality check.
     */
    val startStreamRequest: Int = 0,
    val stopStreamRequest: Int = 0,
    val switchCameraRequest: Int = 0,
)
