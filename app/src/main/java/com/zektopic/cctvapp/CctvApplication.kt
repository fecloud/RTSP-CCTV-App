package com.zektopic.cctvapp

import android.app.Application
import android.content.pm.ApplicationInfo
import com.tencent.bugly.crashreport.CrashReport
import com.zektopic.cctvapp.log.AppLog

class CctvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No buildConfig feature enabled in this module, so debuggable-ness is read
        // straight off the manifest flag instead of BuildConfig.DEBUG.
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AppLog.init(applicationContext, isDebuggable)
        CrashReport.initCrashReport(applicationContext, "9aaf2be4be", isDebuggable)
    }
}
