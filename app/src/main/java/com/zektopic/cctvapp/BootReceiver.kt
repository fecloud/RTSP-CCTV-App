package com.zektopic.cctvapp

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.zektopic.cctvapp.log.AppLog as Log
import androidx.core.content.ContextCompat
import com.zektopic.cctvapp.service.CctvServerService
import com.zektopic.cctvapp.service.ServiceNotificationUtil
import com.zektopic.cctvapp.settings.AppPreferences

/**
 * Restarts the camera server after a reboot, when the user has asked for that.
 *
 * Android 14 and later refuse to let a background component start a foreground service
 * whose type is `camera` or `microphone` -- the start throws
 * `ForegroundServiceStartNotAllowedException`. An uncaught throw here crashes the
 * receiver, so the start is guarded and degrades to a tap-to-resume notification.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Opt-in only. Silently re-arming a camera after every reboot is not a
        // reasonable default for a device that might have changed hands or location.
        if (!AppPreferences.getStartOnBoot(context)) {
            Log.d(TAG, "Start-on-boot disabled; ignoring BOOT_COMPLETED")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted; not starting server on boot")
            return
        }

        // No settings extras needed: CctvServerService.onCreate() loads them itself via
        // SettingsRepository.ensureLoaded(context), the same shared data source
        // MainActivity and WebServer's dashboard write to directly.
        val serviceIntent = Intent(context, CctvServerService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Camera server started after boot")
        } catch (e: Exception) {
            // Covers ForegroundServiceStartNotAllowedException (API 31+) without
            // referencing a class that does not exist on older platforms, plus the
            // SecurityException some OEM builds throw instead.
            Log.w(TAG, "Could not start server on boot; prompting the user instead", e)
            notifyResumeRequired(context)
        }
    }

    /** Falls back to a notification the user can tap to start the server by hand. */
    private fun notifyResumeRequired(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = ServiceNotificationUtil.buildResumeNotification(context)

        try {
            ServiceNotificationUtil.createNotificationChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(ServiceNotificationUtil.RESUME_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post resume notification", e)
        }
    }
}
