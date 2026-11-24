package com.example.sysmonitor.pbb1c5130.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Keep-alive setup run each time the compact window appears (the app is in the foreground). */
object KeepAlive {
    private const val TAG = "KeepAlive"
    private const val PREFS = "keep_alive"
    private const val KEY_ASKED_BATTERY = "asked_battery_exemption"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

    fun onAppVisible(context: Context) {
        KeepAliveService.start(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Each system prompt is shown at most once, and at most one per launch; the user's
        // choice is respected afterwards.
        val notificationsGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted && !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            if (requestNotifications(context)) {
                prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                return
            }
        }
        if (!prefs.getBoolean(KEY_ASKED_BATTERY, false) && requestBatteryExemption(context)) {
            prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        }
    }

    /** The foreground-service notification is hidden until POST_NOTIFICATIONS is granted. */
    private fun requestNotifications(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        return true
    }

    /**
     * Doze/App Standby exemption: keeps the app out of idle restrictions and lets it restart its
     * foreground service from the background. Shows the system "Allow?" dialog.
     */
    private fun requestBatteryExemption(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return true
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.i(TAG, "No battery-optimisation settings screen on this device")
            return true // nothing to ask for; don't retry
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not request battery exemption", it) }
            .isSuccess
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
