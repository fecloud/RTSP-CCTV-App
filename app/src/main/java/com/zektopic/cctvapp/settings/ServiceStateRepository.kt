package com.zektopic.cctvapp.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * The single in-memory source of truth for [ServiceSettings], shared by [MainActivity],
 * [WebServer] (via [SettingUpdateHandler]), and [CctvServerService] -- all three run in
 * the same process (the service has no `android:process` of its own), so there's no need
 * to round-trip a setting change through an `Intent` or HTTP round trip for a live
 * service to notice it: writing here is enough. `CctvServerService`'s settings-effects
 * collector observes [settings] directly and reacts to whatever actually changed.
 *
 * Also holds [runtimeFlow]/[ServiceRuntimeState] -- a second, unrelated `StateFlow` for
 * live camera status and one-shot dashboard commands, deliberately not part of
 * [ServiceSettings] (see [ServiceRuntimeState]'s kdoc).
 */
object ServiceStateRepository {
    private val _settings = MutableStateFlow(ServiceSettings())
    val settings: StateFlow<ServiceSettings> = _settings.asStateFlow()

    /** Convenience synchronous snapshot, equivalent to `settings.value`. */
    val current: ServiceSettings get() = _settings.value

    /**
     * Separate from [settings] on purpose -- see [ServiceRuntimeState]'s kdoc. Nothing
     * here is persisted, so unlike [updateSettings] this needs no `Context`.
     */
    private val _runtime = MutableStateFlow(ServiceRuntimeState())
    val runtimeFlow: StateFlow<ServiceRuntimeState> = _runtime.asStateFlow()

    /** Convenience synchronous snapshot, equivalent to `runtimeFlow.value`. */
    val runtime: ServiceRuntimeState get() = _runtime.value

    /** Applies [transform] to the current runtime snapshot and publishes the result. */
    fun updateRuntime(transform: (ServiceRuntimeState) -> ServiceRuntimeState) {
        _runtime.updateAndGet(transform)
    }

    @Volatile private var loaded = false

    /** Loads from [AppPreferences] once per process; a no-op on every call after the first. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _settings.value = ServiceSettings.loadFromPreferences(context.applicationContext)
            loaded = true
        }
    }

    /**
     * Applies [transform] to the current snapshot, persists every field to
     * [AppPreferences], and publishes the result to every collector. The one call site a
     * new setting needs wiring into, instead of every write site pairing its own
     * `AppPreferences.setX` call with pushing the change somewhere else.
     */
    fun updateSettings(context: Context, transform: (ServiceSettings) -> ServiceSettings) {
        ensureLoaded(context)
        val new = _settings.updateAndGet(transform)
        persist(context.applicationContext, new)
    }

    private fun persist(context: Context, s: ServiceSettings) {
        AppPreferences.setVideoCodec(context, s.videoCodec)
        AppPreferences.setResolution(context, s.videoWidth, s.videoHeight)
        AppPreferences.setAuthEnabled(context, s.authEnabled)
        AppPreferences.setUsername(context, s.authUsername)
        AppPreferences.setPassword(context, s.authPassword)
        AppPreferences.setWebAuthEnabled(context, s.webAuthEnabled)
        AppPreferences.setAudioEnabled(context, s.audioEnabled)
        AppPreferences.setShowTimestamp(context, s.showTimestamp)
        AppPreferences.setTimestampPosition(context, s.timestampPosition)
        AppPreferences.setTimestampSize(context, s.timestampSize)
        AppPreferences.setFlashlightEnabled(context, s.flashlightEnabled)
        AppPreferences.setNightModeEnabled(context, s.nightModeEnabled)
        AppPreferences.setVerticalFlipEnabled(context, s.verticalFlipEnabled)
        AppPreferences.setZoomLevel(context, s.zoomLevel)
        AppPreferences.setBitrateKbps(context, s.bitrateKbps)
        AppPreferences.setRecordToGalleryEnabled(context, s.recordToGalleryEnabled)
        AppPreferences.setRecordSegmentMinutes(context, s.recordSegmentMinutes)
        AppPreferences.setRecordStorageThresholdPercent(context, s.recordStorageThresholdPercent)
        // activeCodec deliberately excluded -- not a stored preference, see ServiceSettings.
    }
}
