package com.example.sysmonitor.pbb1c5130.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.sysmonitor.pbb1c5130.metrics.BatteryEstimate
import com.example.sysmonitor.pbb1c5130.metrics.BatteryLifeEstimator
import com.example.sysmonitor.pbb1c5130.metrics.FrameRateMeter
import com.example.sysmonitor.pbb1c5130.metrics.SystemStats
import com.example.sysmonitor.pbb1c5130.metrics.SystemStatsSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SAMPLE_INTERVAL_MS = 1000L

/** Samples kept per graph: 60 s at one sample per second, like Task Manager. */
const val HISTORY_SIZE = 60

/** Resources listed in the details window, each with its own accent colour. */
enum class Resource(val title: String, val accent: Color) {
    Cpu("CPU", MonitorPalette.CpuAccent),
    Gpu("GPU", MonitorPalette.GpuAccent),
    Network("Network", MonitorPalette.NetworkAccent),
    Thermal("Temperature", MonitorPalette.ThermalAccent),
    Power("Power", MonitorPalette.PowerAccent),
    Display("Display", MonitorPalette.DisplayAccent),
}

/** Latest sample plus 60 s of history. One instance is shared by every window of the app. */
@Stable
class MonitorState {
    var stats by mutableStateOf(SystemStats())
        private set
    var cpuHistory by mutableStateOf(emptyList<Float>())
        private set
    var gpuHistory by mutableStateOf(emptyList<Float>())
        private set
    var downHistory by mutableStateOf(emptyList<Float>())
        private set
    var upHistory by mutableStateOf(emptyList<Float>())
        private set
    var tempHistory by mutableStateOf(emptyList<Float>())
        private set
    var powerHistory by mutableStateOf(emptyList<Float>())
        private set
    var fpsHistory by mutableStateOf(emptyList<Float>())
        private set

    /** Live remaining-time / time-to-full prediction, updated with every sample. */
    var batteryEstimate by mutableStateOf<BatteryEstimate>(BatteryEstimate.Estimating)
        private set
    private val batteryEstimator = BatteryLifeEstimator()

    /** Total samples taken; drives the scrolling grid like Task Manager's moving graph paper. */
    var tick by mutableIntStateOf(0)
        private set

    internal fun push(next: SystemStats) {
        stats = next
        cpuHistory = cpuHistory.pushed(next.cpu.usage?.percent ?: 0f)
        gpuHistory = gpuHistory.pushed(next.gpu?.percent ?: 0f)
        downHistory = downHistory.pushed(next.network.downloadBps?.toFloat() ?: 0f)
        upHistory = upHistory.pushed(next.network.uploadBps?.toFloat() ?: 0f)
        tempHistory = tempHistory.pushed(next.thermal.batteryTempC ?: 0f)
        // Magnitude: draw when on battery, charge power when plugged in (labelled in the UI).
        powerHistory = powerHistory.pushed(next.power.watts?.let { kotlin.math.abs(it) } ?: 0f)
        fpsHistory = fpsHistory.pushed(next.display.measuredFps ?: 0f)
        batteryEstimate = batteryEstimator.update(next.power, next.uptimeMs)
        tick++
    }

    private fun List<Float>.pushed(value: Float): List<Float> = (this + value).takeLast(HISTORY_SIZE)
}

/**
 * Process-wide sampler shared by the compact window and the details window, so both show the
 * same numbers and history survives opening/closing the details. Sampling runs only while at
 * least one window is composed.
 */
private object MonitorHub {
    val state = MonitorState()
    private var sampler: SystemStatsSampler? = null
    private val frameMeter = FrameRateMeter()
    private val scope = MainScope()
    private var users = 0
    private var job: Job? = null

    /** Called on the main thread from composition. */
    fun acquire(context: Context) {
        users++
        if (job?.isActive == true) return
        val activeSampler = sampler ?: SystemStatsSampler(context.applicationContext).also { sampler = it }
        frameMeter.start()
        job = scope.launch {
            while (isActive) {
                state.push(withContext(Dispatchers.IO) { activeSampler.sample(frameMeter.fps) })
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
            frameMeter.stop()
        }
    }
}

/** Returns the shared monitor state and keeps it sampling while this composable is shown. */
@Composable
fun rememberMonitorState(): MonitorState {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        MonitorHub.acquire(context)
        onDispose { MonitorHub.release() }
    }
    return MonitorHub.state
}
