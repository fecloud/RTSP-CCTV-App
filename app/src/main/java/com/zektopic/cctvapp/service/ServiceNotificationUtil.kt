package com.zektopic.cctvapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zektopic.cctvapp.MainActivity
import com.zektopic.cctvapp.R

/**
 * Shared notification channel/notification building for [CctvServerService] (the
 * persistent "server running" notification) and [BootReceiver] (the tap-to-resume
 * fallback notification posted when Android blocks a foreground-service start after
 * reboot). Both previously created their own copy of the same channel id -- with the
 * channel *name* drifting between them (one used the `notification_channel_name`
 * string resource, the other a hardcoded literal) -- so whichever ran first silently
 * won on real devices.
 */
object ServiceNotificationUtil {
    const val CHANNEL_ID = "CctvServerChannel"
    const val SERVER_NOTIFICATION_ID = 1
    const val RESUME_NOTIFICATION_ID = 2

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** The persistent "server is running" notification, with a Stop action. */
    fun buildServerNotification(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, CctvServerService::class.java).setAction(CctvServerService.ACTION_STOP_SERVER),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** Tap-to-resume notification posted when a background-started foreground service was blocked. */
    fun buildResumeNotification(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(context.getString(R.string.boot_resume_title))
            .setContentText(context.getString(R.string.boot_resume_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }
}
