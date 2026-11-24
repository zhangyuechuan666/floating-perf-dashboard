package com.example.sysmonitor.pbb1c5130.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.example.sysmonitor.pbb1c5130.R

/**
 * Foreground service that keeps the process at foreground priority, so the low-memory killer
 * and background restrictions leave the monitor alone while its windows are closed or the
 * headset is idle. It does no work of its own: the sampler still runs only while a window is
 * composed. `START_STICKY` asks the system to recreate it if the process is killed anyway.
 *
 * Its ongoing notification reopens the compact window when tapped.
 */
class KeepAliveService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run within a few seconds of startForegroundService(), including sticky restarts
        // (intent == null).
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_spatial_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keep_alive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts the service. Allowed while a window is visible, from BOOT_COMPLETED /
         * MY_PACKAGE_REPLACED, or from anywhere once the app is exempt from battery
         * optimisation; other background starts throw and are only logged.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "Could not start keep-alive service", it) }
        }
    }
}
