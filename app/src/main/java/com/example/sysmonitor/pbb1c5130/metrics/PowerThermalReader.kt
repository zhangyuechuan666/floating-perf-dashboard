package com.example.sysmonitor.pbb1c5130.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlin.math.abs

data class ThermalStats(
    /** Battery temperature in °C from the battery broadcast; the only temperature apps can read. */
    val batteryTempC: Float? = null,
    /** PowerManager thermal status (THERMAL_STATUS_NONE..SHUTDOWN), or null if unknown. */
    val status: Int? = null,
    /**
     * PowerManager.getThermalHeadroom forecast: 0 = cool, 1.0 = at the severe-throttling limit,
     * above 1 = already throttled. Null when the device does not support it.
     */
    val headroom: Float? = null,
)

data class PowerStats(
    /**
     * Battery power in watts (voltage × current). Positive = the device is drawing from the
     * battery; negative = net power flowing into the battery while charging. Null if unsupported.
     */
    val watts: Float? = null,
    val currentMa: Float? = null,
    val voltageV: Float? = null,
    val levelPercent: Int? = null,
    val charging: Boolean = false,
    /** "AC", "USB", "Wireless", "Dock" or null when on battery. */
    val pluggedSource: String? = null,
    /** Remaining battery charge in mAh (BATTERY_PROPERTY_CHARGE_COUNTER), or null if unsupported. */
    val chargeCounterMah: Float? = null,
    /** BatteryManager.computeChargeTimeRemaining() while charging, or null when unknown. */
    val chargeTimeRemainingMs: Long? = null,
)

/**
 * Reads battery temperature, thermal status/headroom and battery power through public APIs.
 * Kernel thermal zones and /sys/class/power_supply are blocked for apps by SELinux, and CPU/GPU/
 * skin temperatures from the thermal HAL require a system permission, so they are not used.
 */
class PowerThermalReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private var lastHeadroom: Float? = null

    fun read(): Pair<ThermalStats, PowerStats> {
        // Sticky system broadcast: returns the latest battery state without registering a receiver.
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val batteryTempC = tempTenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        val thermal = ThermalStats(
            batteryTempC = batteryTempC,
            status = runCatching { powerManager?.currentThermalStatus }.getOrNull(),
            headroom = readHeadroom(),
        )

        val voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val currentMa = readCurrentMa()
        val voltageV = voltageMv?.let { normalizeVolts(it) }
        val watts = if (currentMa != null && voltageV != null) {
            val magnitude = abs(currentMa / 1000f * voltageV)
            when {
                // Unplugged: the battery can only be discharging, whatever sign the vendor uses.
                plugged == 0 -> magnitude
                // Plugged in: Android convention is positive current = into the battery.
                currentMa > 0f -> -magnitude
                else -> magnitude
            }
        } else {
            null
        }

        val power = PowerStats(
            watts = watts,
            currentMa = currentMa,
            voltageV = voltageV,
            levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            pluggedSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
                else -> null
            },
            chargeCounterMah = readChargeCounterMah(),
            chargeTimeRemainingMs = if (plugged != 0) {
                runCatching { batteryManager?.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 }
            } else {
                null
            },
        )
        return thermal to power
    }

    /**
     * Instantaneous battery current in mA (positive = into the battery). Android specifies µA,
     * but some vendors report mA; values below 10 000 are treated as mA because a running
     * headset never draws less than 10 mA. Returns null when the gauge doesn't report current.
     */
    private fun readCurrentMa(): Float? {
        val raw = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return null
        if (raw == Long.MIN_VALUE || raw == 0L || raw == Int.MIN_VALUE.toLong()) return null
        return if (abs(raw) < 10_000) raw.toFloat() else raw / 1000f
    }

    /** Remaining charge; Android specifies µAh. Null when the fuel gauge doesn't report it. */
    private fun readChargeCounterMah(): Float? {
        val raw = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: return null
        if (raw <= 0L || raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong()) return null
        return raw / 1000f
    }

    /** EXTRA_VOLTAGE is specified in mV; a few devices report µV. */
    private fun normalizeVolts(raw: Int): Float = if (raw > 100_000) raw / 1_000_000f else raw / 1000f

    /** NaN means "unsupported" or "called too often"; keep the last good value in the latter case. */
    private fun readHeadroom(): Float? {
        val value = runCatching { powerManager?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }.getOrNull()
        if (value != null && !value.isNaN()) lastHeadroom = value
        return lastHeadroom
    }

    private companion object {
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
