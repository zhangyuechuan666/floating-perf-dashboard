package com.example.sysmonitor.pbb1c5130.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [KeepAliveService] after a reboot or an app update. Android delivers these only after
 * the user has opened the app once since install (and not after a Force stop). Android 15 does
 * not let the app open its window from here; the notification reopens it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> KeepAliveService.start(context)
        }
    }
}
