package com.example.sysmonitor.pbb1c5130

import com.example.sysmonitor.pbb1c5130.metrics.BatteryEstimate
import com.example.sysmonitor.pbb1c5130.metrics.BatteryLifeEstimator
import com.example.sysmonitor.pbb1c5130.metrics.PowerStats
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryLifeEstimatorTest {
    private val onBattery = PowerStats(currentMa = -1000f, levelPercent = 50, chargeCounterMah = 2500f)

    private fun BatteryLifeEstimator.feed(power: PowerStats, count: Int, startMs: Long = 0L): BatteryEstimate {
        var result: BatteryEstimate = BatteryEstimate.Estimating
        repeat(count) { result = update(power, startMs + it * 1000L) }
        return result
    }

    @Test
    fun estimatingUntilEnoughSamples() {
        assertEquals(BatteryEstimate.Estimating, BatteryLifeEstimator().feed(onBattery, 9))
    }

    @Test
    fun remainingChargeOverAverageDraw() {
        // 2500 mAh / 1000 mA = 2.5 h, whatever sign the vendor uses for current.
        assertEquals(BatteryEstimate.TimeLeft(9_000_000L), BatteryLifeEstimator().feed(onBattery, 10))
    }

    @Test
    fun averagesLoadOverWindow() {
        val estimator = BatteryLifeEstimator(windowSamples = 20, minSamples = 10)
        estimator.feed(onBattery.copy(currentMa = 500f), 10)
        // Average of 10 × 500 mA and 10 × 1500 mA = 1000 mA.
        val result = estimator.feed(onBattery.copy(currentMa = 1500f), 10, startMs = 10_000L)
        assertEquals(BatteryEstimate.TimeLeft(9_000_000L), result)
    }

    @Test
    fun gapInSamplingRestartsAverage() {
        val estimator = BatteryLifeEstimator()
        estimator.feed(onBattery, 10)
        assertEquals(BatteryEstimate.Estimating, estimator.update(onBattery, 60_000L))
    }

    @Test
    fun pluggedInReportsTimeToFullOrFull() {
        val estimator = BatteryLifeEstimator()
        val charging = onBattery.copy(pluggedSource = "USB", chargeTimeRemainingMs = 1_800_000L)
        assertEquals(BatteryEstimate.TimeToFull(1_800_000L), estimator.update(charging, 0L))
        assertEquals(BatteryEstimate.Full, estimator.update(charging.copy(levelPercent = 100), 1000L))
    }

    @Test
    fun implausibleChargeCounterIsUnavailable() {
        // 10 mAh at 100 % (an emulator-style placeholder) is not a real headset battery.
        val fake = onBattery.copy(levelPercent = 100, chargeCounterMah = 10f)
        assertEquals(BatteryEstimate.Unavailable, BatteryLifeEstimator().feed(fake, 10))
    }

    @Test
    fun missingGaugeIsUnavailable() {
        assertEquals(BatteryEstimate.Unavailable, BatteryLifeEstimator().feed(onBattery.copy(chargeCounterMah = null), 10))
    }
}
