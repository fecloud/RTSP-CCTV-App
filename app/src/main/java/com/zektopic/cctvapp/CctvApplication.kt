package com.zektopic.cctvapp

import android.app.Application
import android.content.pm.ApplicationInfo
import com.zektopic.cctvapp.log.AppLog as Log
import com.tencent.bugly.crashreport.CrashReport
import com.zektopic.cctvapp.camera.CameraResolutionUtil
import com.zektopic.cctvapp.device.DeviceStatsUtil
import com.zektopic.cctvapp.log.AppLog
import com.zektopic.cctvapp.service.CameraRuntimeBus
import com.zektopic.cctvapp.settings.ServiceStateRepository
import com.zektopic.cctvapp.web.WebServer

class CctvApplication : Application() {

    companion object {
        private const val TAG = "CctvApplication"
    }

    /**
     * Moved here from `CctvServerService` so the dashboard survives that service being
     * fully stopped -- previously `WebServer.start()`/`.stop()` were tied to the
     * service's own `onCreate`/`onDestroy`, so stopping the service killed the dashboard
     * along with it.
     *
     * Every callback goes through [ServiceStateRepository] or [CameraRuntimeBus] -- the same
     * shared, neutral singletons `CctvServerService` itself reads and writes -- so
     * neither this class nor the service ever needs to reference the other's concrete
     * type. Commands (`onStartStream`/`onStopStream`/`onSwitchCamera`) bump a request
     * counter in [ServiceStateRepository]'s separate runtime `StateFlow` (see
     * `ServiceRuntimeState`) that the service reacts to *while it's running* -- this does
     * not itself restart a fully-stopped service. Status (`isStreaming`/`getZoomRange`)
     * reads status the service publishes into that same runtime flow.
     */
    private val webServer: WebServer by lazy {
        WebServer(this, DeviceStatsUtil.getIpAddress(this),
            imageProvider = {
                CameraRuntimeBus.lastSnapshotRequestMs = System.currentTimeMillis()
                CameraRuntimeBus.currentSnapshot.get()
            },
            onSwitchCamera = {
                ServiceStateRepository.updateRuntime { it.copy(switchCameraRequest = it.switchCameraRequest + 1) }
            },
            onStartStream = {
                ServiceStateRepository.updateRuntime { it.copy(startStreamRequest = it.startStreamRequest + 1) }
            },
            onStopStream = {
                ServiceStateRepository.updateRuntime { it.copy(stopStreamRequest = it.stopStreamRequest + 1) }
            },
            isStreaming = { ServiceStateRepository.runtime.isStreaming },
            getZoomRange = {
                val r = ServiceStateRepository.runtime
                if (r.isStreaming) r.zoomRange else CameraResolutionUtil.getZoomRange(this)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // No buildConfig feature enabled in this module, so debuggable-ness is read
        // straight off the manifest flag instead of BuildConfig.DEBUG.
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AppLog.init(applicationContext, isDebuggable)
        Log.w(TAG, "onCreate: pid=${android.os.Process.myPid()}")
        CrashReport.initCrashReport(applicationContext, "9aaf2be4be", isDebuggable)

        // Needed here now too: WebServer's dashboard (auth credentials, etc.) can be hit
        // before CctvServerService/MainActivity ever run. Idempotent -- see
        // SettingsRepository.ensureLoaded.
        ServiceStateRepository.ensureLoaded(this)
        webServer.start()
    }
}
