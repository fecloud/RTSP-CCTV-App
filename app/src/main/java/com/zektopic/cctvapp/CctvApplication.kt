package com.zektopic.cctvapp

import android.app.Application
import android.content.pm.ApplicationInfo
import com.zektopic.cctvapp.log.AppLog as Log
import com.tencent.bugly.crashreport.CrashReport
import com.zektopic.cctvapp.log.AppLog
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
     * along with it. Every cross-cutting concern (settings, start/stop/switch-camera
     * commands, zoom range) goes through [ServiceStateRepository], which `WebServer`
     * already reads/writes directly; the live camera/mic tap for the MSE/WebRTC previews goes
     * through `PreviewBus` instead (see `MseStreamSocket`'s kdoc), for the same reason
     * `CameraRuntimeBus` used to exist for the old JPEG snapshot feed. `WebServer` reads
     * its own IP address live off [com.zektopic.cctvapp.device.DeviceStatsUtil] on every
     * request rather than taking one here, since it can change at any point over this
     * long-lived server's lifetime.
     */
    private val webServer: WebServer by lazy {
        WebServer(this)
    }

    override fun onCreate() {
        super.onCreate()
        // No buildConfig feature enabled in this module, so debuggable-ness is read
        // straight off the manifest flag instead of BuildConfig.DEBUG.
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AppLog.init(applicationContext)
        Log.w(TAG, "onCreate: pid=${android.os.Process.myPid()}")
        CrashReport.initCrashReport(applicationContext, "9aaf2be4be", isDebuggable)

        // Needed here now too: WebServer's dashboard (auth credentials, etc.) can be hit
        // before CctvServerService/MainActivity ever run. Idempotent -- see
        // SettingsRepository.ensureLoaded.
        ServiceStateRepository.ensureLoaded(this)
        webServer.start()
    }
}
