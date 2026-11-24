package com.example.sysmonitor.pbb1c5130.metrics

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import java.io.File

/** One snapshot of the device metrics shown in the monitor. */
data class SystemStats(
    val network: NetworkStats = NetworkStats(),
    val cpu: CpuStats = CpuStats(),
    /** GPU utilisation, or null when the device exposes no readable GPU load node. */
    val gpu: Reading? = null,
    val thermal: ThermalStats = ThermalStats(),
    val power: PowerStats = PowerStats(),
    val display: DisplayStats = DisplayStats(),
    val uptimeMs: Long = 0L,
)

data class NetworkStats(
    /** Device-wide download speed in bytes/second, or null when unsupported. */
    val downloadBps: Double? = null,
    /** Device-wide upload speed in bytes/second, or null when unsupported. */
    val uploadBps: Double? = null,
    /** Bytes received / sent since boot, or null when unsupported. */
    val totalRxBytes: Long? = null,
    val totalTxBytes: Long? = null,
)

data class CpuStats(
    /** CPU utilisation, or null when neither /proc/stat nor cpufreq is readable. */
    val usage: Reading? = null,
    /** Average current core clock in GHz, or null when the kernel reports no plausible value. */
    val currentGhz: Float? = null,
    /** Highest core max clock in GHz, or null when unknown. */
    val maxGhz: Float? = null,
    val cores: Int = Runtime.getRuntime().availableProcessors(),
)

/**
 * A utilisation value in 0..100.
 * [source] tells the user where the number came from, because Android does not
 * offer a single public API for system-wide CPU/GPU load and the readable kernel
 * nodes differ per device and OS build.
 */
data class Reading(val percent: Float, val source: String)

/**
 * Samples network, CPU and GPU usage. Call [sample] periodically from a background
 * thread; rates and CPU load are computed from the delta to the previous call, so
 * the first call only primes the counters.
 */
class SystemStatsSampler(context: Context) {
    private val network = NetworkSpeedReader()
    private val cpu = CpuUsageReader()
    private val gpu = GpuUsageReader()
    private val powerThermal = PowerThermalReader(context)
    private val display = DisplayReader(context)

    /** [measuredFps] comes from a [FrameRateMeter] running on the main thread. */
    fun sample(measuredFps: Float?): SystemStats {
        val (thermal, power) = powerThermal.read()
        return SystemStats(
            network = network.read(),
            cpu = cpu.read(),
            gpu = gpu.read(),
            thermal = thermal,
            power = power,
            display = display.read(measuredFps),
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }
}

private class NetworkSpeedReader {
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTimeMs = 0L

    fun read(): NetworkStats {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return NetworkStats()
        }
        val primed = lastTimeMs > 0 && now > lastTimeMs
        val seconds = (now - lastTimeMs) / 1000.0
        // Counters can reset (e.g. interface teardown); clamp negative deltas to 0.
        val down = if (primed) (rx - lastRx).coerceAtLeast(0) / seconds else 0.0
        val up = if (primed) (tx - lastTx).coerceAtLeast(0) / seconds else 0.0
        lastRx = rx
        lastTx = tx
        lastTimeMs = now
        return NetworkStats(down, up, rx, tx)
    }
}

/**
 * CPU load. Preferred source is the aggregate `cpu` line of /proc/stat. Many
 * Android builds deny apps access to it via SELinux; in that case fall back to the
 * average of per-core current/max frequency, which is a coarse load proxy and is
 * labelled as such.
 */
private class CpuUsageReader {
    private var procStatReadable = true
    private var prevTotal = 0L
    private var prevIdle = 0L

    fun read(): CpuStats {
        val freqs = readFrequencies()
        val usage = readProcStat() ?: freqs.takeIf { it.isNotEmpty() }?.let { list ->
            val ratio = list.map { it.first.toFloat() / it.second }.average().toFloat()
            Reading((ratio * 100f).coerceIn(0f, 100f), SOURCE_CPU_FREQ)
        }
        // Emulators report placeholder clocks (a few kHz); hide anything below 100 MHz.
        val plausible = freqs.filter { it.second >= MIN_PLAUSIBLE_KHZ }
        return CpuStats(
            usage = usage,
            currentGhz = plausible.takeIf { it.isNotEmpty() }
                ?.map { it.first }?.average()?.let { (it / 1_000_000.0).toFloat() },
            maxGhz = plausible.maxOfOrNull { it.second }?.let { it / 1_000_000f },
        )
    }

    private fun readProcStat(): Reading? {
        if (!procStatReadable) return null
        val line = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
        if (line == null || !line.startsWith("cpu ")) {
            procStatReadable = false
            return null
        }
        val fields = line.substring(4).trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) {
            procStatReadable = false
            return null
        }
        // user nice system idle iowait irq softirq steal ... (guest time is already in user)
        val idle = fields[3] + fields.getOrElse(4) { 0L }
        val total = fields.take(8).sum()
        val dTotal = total - prevTotal
        val dIdle = idle - prevIdle
        val primed = prevTotal != 0L
        prevTotal = total
        prevIdle = idle
        if (!primed || dTotal <= 0) return Reading(0f, SOURCE_PROC_STAT)
        val percent = (100f * (dTotal - dIdle) / dTotal).coerceIn(0f, 100f)
        return Reading(percent, SOURCE_PROC_STAT)
    }

    /** (current kHz, max kHz) for every core whose cpufreq nodes are readable. */
    private fun readFrequencies(): List<Pair<Long, Long>> {
        val cores = File("/sys/devices/system/cpu").listFiles { f -> CORE_DIR.matches(f.name) }
            ?: return emptyList()
        return cores.mapNotNull { core ->
            val cur = readLong(File(core, "cpufreq/scaling_cur_freq")) ?: return@mapNotNull null
            val max = readLong(File(core, "cpufreq/cpuinfo_max_freq"))
                ?: readLong(File(core, "cpufreq/scaling_max_freq"))
                ?: return@mapNotNull null
            if (max > 0) cur to max else null
        }
    }

    private companion object {
        val CORE_DIR = Regex("cpu\\d+")
        const val MIN_PLAUSIBLE_KHZ = 100_000L
        const val SOURCE_PROC_STAT = "/proc/stat"
        const val SOURCE_CPU_FREQ = "Frequency estimate"
    }
}

/**
 * GPU load from the kernel driver's sysfs nodes. Qualcomm Adreno (used by PICO
 * headsets) exposes kgsl nodes; Mali paths are kept for other hardware. Returns
 * null when none is readable (e.g. on the emulator or when SELinux denies access).
 */
private class GpuUsageReader {
    private var activeSource: GpuSource? = null

    fun read(): Reading? {
        // Keep probing until a node is readable; a source's read() returns null only
        // when the node is missing or denied, never for an idle GPU.
        val source = activeSource
            ?: SOURCES.firstOrNull { it.read() != null }?.also { activeSource = it }
            ?: return null
        val percent = source.read() ?: return null
        return Reading(percent.coerceIn(0f, 100f), source.label)
    }

    private class GpuSource(val label: String, val read: () -> Float?)

    private companion object {
        val SOURCES = listOf(
            // "45" — instantaneous busy percentage.
            GpuSource("kgsl gpu_busy_percentage") {
                readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
                    ?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
            },
            // "busy total" — busy/total ticks of the last sampling window. The only kgsl
            // node untrusted apps can read on PICO headsets; "0 0" means the GPU is idle
            // (e.g. headset asleep), which is 0 %, not "unavailable".
            GpuSource("kgsl gpubusy") {
                readText("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { text ->
                    val parts = text.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                    when {
                        parts.size < 2 -> null
                        parts[1] > 0 -> 100f * parts[0] / parts[1]
                        else -> 0f
                    }
                }
            },
            GpuSource("kernel gpu_busy") {
                readText("/sys/kernel/gpu/gpu_busy")
                    ?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
            },
            GpuSource("Mali utilization") {
                readText("/sys/class/misc/mali0/device/utilization")?.trim()?.toFloatOrNull()
            },
        )
    }
}

private fun readText(path: String): String? =
    runCatching { File(path).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun readLong(file: File): Long? =
    runCatching { file.readText().trim().toLong() }.getOrNull()
