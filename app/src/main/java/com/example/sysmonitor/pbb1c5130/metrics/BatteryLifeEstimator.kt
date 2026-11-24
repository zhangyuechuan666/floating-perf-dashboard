package com.example.sysmonitor.pbb1c5130.metrics

import kotlin.math.abs

/** Live battery-life prediction shown on the strip and the Power tab. */
sealed interface BatteryEstimate {
    /** On battery: predicted time until empty at the recent average draw. */
    data class TimeLeft(val ms: Long) : BatteryEstimate

    /** Plugged in: Android's own time-to-full prediction; null while it is unknown. */
    data class TimeToFull(val ms: Long?) : BatteryEstimate

    data object Full : BatteryEstimate

    /** On battery, but not enough samples yet for a stable average. */
    data object Estimating : BatteryEstimate

    /** The fuel gauge reports no usable remaining charge or current. */
    data object Unavailable : BatteryEstimate
}

/**
 * Predicts remaining battery time as `remaining charge (mAh) / average discharge current (mA)`.
 * Android has no public discharge-prediction API (PowerManager.getBatteryDischargePrediction is a
 * system API), so the app averages its own 1 s current samples over a rolling window. The window
 * resets when the device is plugged in or sampling pauses, so stale load never skews the result.
 * Not thread-safe; call from one thread.
 */
class BatteryLifeEstimator(
    private val windowSamples: Int = 120,
    private val minSamples: Int = 10,
    private val maxGapMs: Long = 5_000,
) {
    private val drawMa = ArrayDeque<Float>()
    private var lastUptimeMs: Long? = null

    fun update(power: PowerStats, uptimeMs: Long): BatteryEstimate {
        val previous = lastUptimeMs
        lastUptimeMs = uptimeMs

        if (power.pluggedSource != null) {
            drawMa.clear()
            return if (power.levelPercent == 100) BatteryEstimate.Full
            else BatteryEstimate.TimeToFull(power.chargeTimeRemainingMs)
        }

        // Sampling stopped (no window open) or the clock jumped: old load no longer applies.
        if (previous == null || uptimeMs - previous !in 0..maxGapMs) drawMa.clear()

        val current = power.currentMa?.let(::abs)?.takeIf { it > 0f } ?: return BatteryEstimate.Unavailable
        drawMa.addLast(current)
        while (drawMa.size > windowSamples) drawMa.removeFirst()

        val remainingMah = plausibleRemainingMah(power) ?: return BatteryEstimate.Unavailable
        if (drawMa.size < minSamples) return BatteryEstimate.Estimating
        val averageMa = drawMa.average()
        return BatteryEstimate.TimeLeft((remainingMah / averageMa * MS_PER_HOUR).toLong())
    }

    /**
     * Rejects gauges whose charge counter doesn't match a real battery (e.g. emulator placeholders):
     * the full capacity implied by counter / level must be within [MIN_CAPACITY_MAH, MAX_CAPACITY_MAH].
     */
    private fun plausibleRemainingMah(power: PowerStats): Float? {
        val remaining = power.chargeCounterMah ?: return null
        val level = power.levelPercent ?: return remaining
        if (level <= 0) return remaining
        val impliedCapacity = remaining * 100f / level
        return remaining.takeIf { impliedCapacity in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
    }

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
        const val MIN_CAPACITY_MAH = 500f
        const val MAX_CAPACITY_MAH = 50_000f
    }
}
