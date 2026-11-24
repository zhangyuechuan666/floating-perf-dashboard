package com.example.sysmonitor.pbb1c5130.content

import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.Text
import com.example.sysmonitor.pbb1c5130.metrics.BatteryEstimate
import com.pico.spatial.ui.foundation.layout.offset

/**
 * "Performance" page shown in the details window: resource list on the left, the selected
 * resource on the right (Task Manager graphs). The "Theme" switch in the top-left corner picks
 * the app's floating window: Classic = glass strip, Gauge = 3D dial cluster.
 */
@Composable
fun DetailsPage(modifier: Modifier = Modifier) {
    val state = rememberMonitorState()
    val themeState = rememberAppTheme()
    // The theme only swaps the floating window (strip or dial cluster); details always uses the
    // Classic graphs. The 2D gauge pane below is kept for reference but not shown.
    val theme = DetailsTheme.Classic
    var selected by rememberSaveable { mutableStateOf(Resource.Cpu) }

    Row(modifier.fillMaxSize()) {
        SideNavigation(
            modifier = Modifier.fillMaxHeight().width(260.dp),
            header = {
                Column(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 10.dp)) {
                    Text(
                        text = "主题",
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        style = PicoTheme.typography.labelSmall,
                        color = PicoTheme.colorScheme.labelSecondary,
                        maxLines = 1,
                    )
                    ThemeSwitcher(themeState)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "性能",
                        style = PicoTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            },
        ) {
            Resource.entries.forEach { resource ->
                SideNavigationItem(
                    selected = selected == resource,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { selected = resource },
                    leading = {
                        when (theme) {
                            DetailsTheme.Gauge -> MiniGauge(resource, state)
                            DetailsTheme.Classic -> MiniGraph(resource, state)
                        }
                    },
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(resource.zhTitle, maxLines = 1)
                        Text(
                            text = summaryFor(resource, state),
                            style = PicoTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        DetailPane(
            resource = selected,
            state = state,
            theme = theme,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 28.dp, top = 24.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun MiniGraph(resource: Resource, state: MonitorState) {
    val spec = graphSpec(resource, state)
    UsageGraph(
        primary = spec.primary,
        maxValue = spec.max,
        minValue = spec.min,
        accent = resource.accent,
        tick = state.tick,
        modifier = Modifier.padding(end = 4.dp).size(width = 56.dp, height = 36.dp),
        secondary = spec.secondary,
        showGrid = false,
    )
}

@Composable
private fun MiniGauge(resource: Resource, state: MonitorState) {
    val spec = graphSpec(resource, state)
    MiniDial(
        value = gaugeValue(resource, state),
        min = spec.min,
        max = spec.max,
        redlineFrom = redlineFor(resource),
        accent = resource.accent,
        modifier = Modifier.padding(end = 4.dp).size(width = 56.dp, height = 36.dp),
    )
}

/** What the large and mini graphs plot for each resource. */
internal class GraphSpec(
    val primary: List<Float>,
    val min: Float,
    val max: Float,
    val axisTitle: String,
    val axisMin: String,
    val axisMax: String,
    val secondary: List<Float>? = null,
)

internal fun graphSpec(resource: Resource, state: MonitorState): GraphSpec = when (resource) {
    Resource.Cpu -> GraphSpec(state.cpuHistory, 0f, 100f, "利用率 %", "0", "100%")
    Resource.Gpu -> GraphSpec(state.gpuHistory, 0f, 100f, "利用率 %", "0", "100%")
    Resource.Network -> networkScale(state).let { max ->
        GraphSpec(state.downHistory, 0f, max, "吞吐量", "0", formatSpeed(max.toDouble()), state.upHistory)
    }
    Resource.Thermal -> {
        // 20-60 °C covers normal battery temperatures; widen in 10 °C steps if exceeded.
        val peak = state.tempHistory.maxOrNull() ?: 0f
        val max = maxOf(60f, kotlin.math.ceil(peak / 10f) * 10f)
        GraphSpec(state.tempHistory, 20f, max, "电池温度", "20 °C", formatTemp(max))
    }
    Resource.Power -> niceMax(state.powerHistory.maxOrNull() ?: 0f, floor = 1f).let { max ->
        GraphSpec(state.powerHistory, 0f, max, powerAxisTitle(state), "0", formatWatts(max))
    }
    Resource.Display -> {
        val peak = maxOf(state.fpsHistory.maxOrNull() ?: 0f, state.stats.display.refreshHz ?: 60f)
        val max = kotlin.math.ceil(peak * 1.1f / 30f) * 30f
        GraphSpec(state.fpsHistory, 0f, max, "实测帧率 (fps)", "0", formatHz(max))
    }
}

/** Where each gauge's redline starts; null = the resource has no "too high" zone. */
internal fun redlineFor(resource: Resource): Float? = when (resource) {
    Resource.Cpu, Resource.Gpu -> 85f // sustained load near 100 % means the system is saturated
    Resource.Thermal -> 45f // batteries throttle charging and age faster above ~45 °C
    Resource.Network, Resource.Power, Resource.Display -> null
}

/** Current reading shown by the needle, or null when the device doesn't expose it. */
internal fun gaugeValue(resource: Resource, state: MonitorState): Float? {
    val stats = state.stats
    return when (resource) {
        Resource.Cpu -> stats.cpu.usage?.percent
        Resource.Gpu -> stats.gpu?.percent
        Resource.Network -> stats.network.downloadBps?.toFloat()
        Resource.Thermal -> stats.thermal.batteryTempC
        Resource.Power -> stats.power.watts?.let { kotlin.math.abs(it) }
        Resource.Display -> stats.display.measuredFps
    }
}

/** Major divisions per dial, chosen so the numbers land on round values for each scale. */
internal fun majorTicksFor(resource: Resource): Int = when (resource) {
    Resource.Network -> 4 // power-of-two byte scales: 128K, 256K, 384K, 512K
    Resource.Thermal -> 4 // 20-60 °C in 10 °C steps
    Resource.Display -> 6 // 90/120 fps in 15/20 fps steps
    Resource.Cpu, Resource.Gpu, Resource.Power -> 5
}

/** Dial numbers for each resource's scale. */
internal fun gaugeTickLabel(resource: Resource, max: Float): (Float) -> String = when (resource) {
    Resource.Network -> { v -> if (v <= 0f) "0" else formatSpeedCompact(v.toDouble()).removeSuffix("/s") }
    Resource.Power -> { v -> if (max < 10f) String.format(java.util.Locale.US, "%.1f", v) else String.format(java.util.Locale.US, "%.0f", v) }
    else -> { v -> String.format(java.util.Locale.US, "%.0f", v) }
}

/** Big digital readout, its unit caption, and an extra line under the hub. */
internal class GaugeReadout(val value: String, val unit: String, val detail: String?)

internal fun gaugeReadout(resource: Resource, state: MonitorState): GaugeReadout {
    val stats = state.stats
    val v = gaugeValue(resource, state)
    fun fmt(pattern: String) = v?.let { String.format(java.util.Locale.US, pattern, it) } ?: UNAVAILABLE_VALUE
    return when (resource) {
        Resource.Cpu -> GaugeReadout(fmt("%.0f"), "% CPU LOAD", stats.cpu.currentGhz?.let(::formatGhz))
        Resource.Gpu -> GaugeReadout(fmt("%.0f"), "% GPU LOAD", null)
        Resource.Network -> GaugeReadout(
            stats.network.downloadBps?.let(::formatSpeedCompact) ?: UNAVAILABLE_VALUE,
            "RECEIVE",
            stats.network.uploadBps?.let { "Send ${formatSpeedCompact(it)}" },
        )
        Resource.Thermal -> GaugeReadout(fmt("%.1f"), "°C BATTERY", stats.thermal.status?.let(::thermalStatusName))
        Resource.Power -> GaugeReadout(
            fmt("%.2f"),
            if (isChargingFlow(state)) "W CHARGING" else "W DRAW",
            batteryEstimateItem(state.batteryEstimate).let { (label, value) ->
                if (value == UNAVAILABLE_VALUE) null else "$label $value"
            },
        )
        Resource.Display -> GaugeReadout(fmt("%.0f"), "FPS", stats.display.refreshHz?.let { "${formatHz(it)} display" })
    }
}

/** Stats item for the battery-life prediction; label follows the charging state. */
private fun batteryEstimateItem(estimate: BatteryEstimate): Pair<String, String> = when (estimate) {
    is BatteryEstimate.TimeLeft -> "剩余时间" to formatDurationZh(estimate.ms)
    is BatteryEstimate.TimeToFull -> "充满还需" to (estimate.ms?.let(::formatDurationZh) ?: UNAVAILABLE_VALUE)
    BatteryEstimate.Full -> "充满还需" to "已充满"
    BatteryEstimate.Estimating -> "剩余时间" to "估算中…"
    BatteryEstimate.Unavailable -> "剩余时间" to UNAVAILABLE_VALUE
}

private fun powerAxisTitle(state: MonitorState): String =
    if (isChargingFlow(state)) "充入电池的功率" else "电池输出功率"

/** True when net power is flowing into the battery (negative draw). */
private fun isChargingFlow(state: MonitorState): Boolean = (state.stats.power.watts ?: 0f) < 0f

internal fun summaryFor(resource: Resource, state: MonitorState): String {
    val stats = state.stats
    return when (resource) {
        Resource.Cpu -> listOfNotNull(
            stats.cpu.usage?.let { formatPercent(it.percent) } ?: "不可用",
            stats.cpu.currentGhz?.let { formatGhz(it) },
        ).joinToString("  ")
        Resource.Gpu -> stats.gpu?.let { formatPercent(it.percent) } ?: "不可用"
        Resource.Network -> "发送 ${stats.network.uploadBps?.let(::formatSpeed) ?: "—"}  接收 ${stats.network.downloadBps?.let(::formatSpeed) ?: "—"}"
        Resource.Thermal -> listOfNotNull(
            stats.thermal.batteryTempC?.let(::formatTemp) ?: "不可用",
            stats.thermal.status?.let(::thermalStatusName),
        ).joinToString("  ")
        Resource.Power -> listOfNotNull(
            stats.power.watts?.let { w -> if (w < 0f) "充电 ${formatWatts(-w)}" else formatWatts(w) } ?: "不可用",
            (state.batteryEstimate as? BatteryEstimate.TimeLeft)?.let { "剩余 ${formatDurationZh(it.ms)}" },
        ).joinToString("  ")
        Resource.Display -> listOfNotNull(
            stats.display.refreshHz?.let(::formatHz) ?: "不可用",
            stats.display.measuredFps?.let { "实测 ${formatFps(it)}" },
        ).joinToString("  ")
    }
}

@Composable
private fun DetailPane(resource: Resource, state: MonitorState, theme: DetailsTheme, modifier: Modifier = Modifier) {
    val stats = state.stats
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = resource.zhTitle,
                modifier = Modifier.weight(1f),
                style = PicoTheme.typography.titleMedium,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Text(
                text = subtitleFor(resource, state),
                style = PicoTheme.typography.bodySmall,
                color = PicoTheme.colorScheme.labelSecondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))

        val spec = graphSpec(resource, state)
        when (theme) {
            DetailsTheme.Classic -> {
                AxisLabels(start = spec.axisTitle, end = spec.axisMax)
                Spacer(Modifier.height(4.dp))
                val graphDescription = "${resource.zhTitle} 最近 60 秒趋势图，当前 ${summaryFor(resource, state)}"
                UsageGraph(
                    primary = spec.primary,
                    maxValue = spec.max,
                    minValue = spec.min,
                    accent = resource.accent,
                    tick = state.tick,
                    secondary = spec.secondary,
                    modifier = Modifier
                        // Spatial depth: the live graph floats in front of the window glass.
                        .offset(z = 24.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics { contentDescription = graphDescription },
                )
                Spacer(Modifier.height(4.dp))
                AxisLabels(start = "60 秒", end = spec.axisMin)
            }
            DetailsTheme.Gauge -> {
                val value = gaugeValue(resource, state)
                val redline = redlineFor(resource)
                val label = gaugeTickLabel(resource, spec.max)
                val peak = spec.primary.maxOrNull()?.takeIf { value != null }
                ShiftLights(
                    value = value,
                    min = spec.min,
                    max = spec.max,
                    redlineFrom = redline,
                    accent = resource.accent,
                    modifier = Modifier.offset(z = 24.dp).fillMaxWidth().height(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                val readout = gaugeReadout(resource, state)
                val dialDescription = "${resource.title} gauge, current ${summaryFor(resource, state)}" +
                    (peak?.let { ", 60 second peak ${label(it)}" } ?: "")
                GaugeDial(
                    value = value,
                    min = spec.min,
                    max = spec.max,
                    redlineFrom = redline,
                    peak = peak,
                    accent = resource.accent,
                    tickLabel = label,
                    majorTicks = majorTicksFor(resource),
                    readout = readout.value,
                    unit = readout.unit,
                    detail = readout.detail,
                    modifier = Modifier
                        // Spatial depth: the dial floats in front of the window glass.
                        .offset(z = 24.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .clearAndSetSemantics { contentDescription = dialDescription },
                )
                Spacer(Modifier.height(4.dp))
                AxisLabels(
                    start = peak?.let { "Peak (60 s)  ${label(it)}" } ?: "Peak (60 s)  $UNAVAILABLE_VALUE",
                    end = redline?.let { "Redline  ${label(it)}" } ?: "",
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        when (resource) {
            Resource.Cpu -> StatsBlock(
                listOf(
                    "利用率" to (stats.cpu.usage?.let { formatPercent(it.percent) } ?: UNAVAILABLE_VALUE),
                    "主频" to (stats.cpu.currentGhz?.let(::formatGhz) ?: UNAVAILABLE_VALUE),
                    "最高主频" to (stats.cpu.maxGhz?.let(::formatGhz) ?: UNAVAILABLE_VALUE),
                    "核心数" to stats.cpu.cores.toString(),
                    "运行时间" to formatUptime(stats.uptimeMs),
                ),
                footnote = stats.cpu.usage?.let { "数据来源：${sourceZh(it.source)}" },
            )
            Resource.Gpu -> StatsBlock(
                listOf(
                    "利用率" to (stats.gpu?.let { formatPercent(it.percent) } ?: UNAVAILABLE_VALUE),
                    "运行时间" to formatUptime(stats.uptimeMs),
                ),
                footnote = stats.gpu?.let { "数据来源：${sourceZh(it.source)}" }
                    ?: "此设备没有可读取的 GPU 负载节点（Adreno kgsl / Mali），Android 也没有公开的 GPU 负载 API。" +
                        "PICO 模拟器使用电脑的 GPU 渲染，因此始终无法读取。",
            )
            Resource.Network -> StatsBlock(
                listOf(
                    "发送" to (stats.network.uploadBps?.let(::formatSpeed) ?: UNAVAILABLE_VALUE),
                    "接收" to (stats.network.downloadBps?.let(::formatSpeed) ?: UNAVAILABLE_VALUE),
                    "累计发送" to (stats.network.totalTxBytes?.let(::formatBytes) ?: UNAVAILABLE_VALUE),
                    "累计接收" to (stats.network.totalRxBytes?.let(::formatBytes) ?: UNAVAILABLE_VALUE),
                ),
                footnote = if (theme == DetailsTheme.Classic) "实线：接收 · 虚线：发送" else "Dial: receive",
            )
            Resource.Thermal -> StatsBlock(
                listOf(
                    "电池" to (stats.thermal.batteryTempC?.let(::formatTemp) ?: UNAVAILABLE_VALUE),
                    "散热状态" to (stats.thermal.status?.let(::thermalStatusName) ?: UNAVAILABLE_VALUE),
                    "散热余量占用" to (stats.thermal.headroom?.let { formatPercent(it * 100f) } ?: UNAVAILABLE_VALUE),
                ),
                footnote = "数据来自电池传感器（BatteryManager）和 PowerManager 散热状态。" +
                    "CPU、GPU 和机身温度不对应用开放。",
            )
            Resource.Power -> StatsBlock(
                listOf(
                    (if (isChargingFlow(state)) "充电功率" else "功耗") to
                        (stats.power.watts?.let { formatWatts(kotlin.math.abs(it)) } ?: UNAVAILABLE_VALUE),
                    "电流" to (stats.power.currentMa?.let { formatMilliamps(kotlin.math.abs(it)) } ?: UNAVAILABLE_VALUE),
                    "电压" to (stats.power.voltageV?.let(::formatVolts) ?: UNAVAILABLE_VALUE),
                    "电池" to (stats.power.levelPercent?.let { "$it%" } ?: UNAVAILABLE_VALUE),
                    batteryEstimateItem(state.batteryEstimate),
                    // Power source is shown in the pane subtitle ("On battery" / "Plugged in (AC)").
                ),
                footnote = if (stats.power.pluggedSource != null) {
                    "已接电源：显示的是流入或流出电池的净功率，并非整机总功耗。" +
                        "充满时间来自 Android 的充电预测。单个部件的功耗不对应用开放。"
                } else {
                    "整机电池功率（电压 × 电流）。剩余时间 = 剩余电量 ÷ 最近 2 分钟的平均耗电，" +
                        "会随负载变化。单个部件的功耗不对应用开放。"
                },
            )
            Resource.Display -> StatsBlock(
                listOf(
                    "刷新率" to (stats.display.refreshHz?.let(::formatHz) ?: UNAVAILABLE_VALUE),
                    "实测" to (stats.display.measuredFps?.let(::formatFps) ?: UNAVAILABLE_VALUE),
                    "支持的刷新率" to (
                        stats.display.supportedHz.takeIf { it.isNotEmpty() }
                            ?.joinToString("/", postfix = " Hz") { String.format(java.util.Locale.US, "%.0f", it) }
                            ?: UNAVAILABLE_VALUE
                        ),
                ),
                footnote = "本应用看到的 Android 显示模式和垂直同步频率。头显面板的实际刷新率由 PICO 合成器决定，" +
                    "Spatial SDK 应用无法读取。",
            )
        }
    }
}

@Composable
private fun AxisLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = start,
            modifier = Modifier.weight(1f),
            style = PicoTheme.typography.labelSmall,
            color = PicoTheme.colorScheme.labelSecondary,
        )
        Text(
            text = end,
            style = PicoTheme.typography.labelSmall,
            color = PicoTheme.colorScheme.labelSecondary,
        )
    }
}

/** Columns in every stats row; fixed so values sit in the same place on every tab. */
private const val STATS_COLUMNS = 5

/**
 * Task Manager-style statistics: small grey caption over a larger value. Every item gets an
 * equal fixed-width column, so a value changing length never shifts its neighbours.
 */
@Composable
private fun StatsBlock(items: List<Pair<String, String>>, footnote: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(STATS_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                            .semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            text = label,
                            style = PicoTheme.typography.labelSmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = value,
                            style = tabularNumbers(PicoTheme.typography.titleSmall),
                            color = if (value == UNAVAILABLE_VALUE) {
                                PicoTheme.colorScheme.labelTertiary
                            } else {
                                PicoTheme.colorScheme.labelPrimary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Keep column positions identical when a row has fewer items.
                repeat(STATS_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        footnote?.let {
            Text(
                text = it,
                style = PicoTheme.typography.labelSmall,
                color = PicoTheme.colorScheme.labelTertiary,
                // Same height on every tab, so the graph above doesn't resize when switching.
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val UNAVAILABLE_VALUE = "—"

private fun subtitleFor(resource: Resource, state: MonitorState): String = when (resource) {
    Resource.Cpu -> socName() ?: "${state.stats.cpu.cores} 核"
    Resource.Gpu -> state.stats.gpu?.source ?: "不可读取"
    Resource.Network -> "全部网络接口"
    Resource.Thermal -> "电池传感器"
    Resource.Power -> state.stats.power.pluggedSource?.let { "已接电源（$it）" } ?: "使用电池"
    Resource.Display -> "默认显示器"
}

private fun thermalStatusName(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "正常"
    PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
    PowerManager.THERMAL_STATUS_MODERATE -> "中等"
    PowerManager.THERMAL_STATUS_SEVERE -> "严重"
    PowerManager.THERMAL_STATUS_CRITICAL -> "危急"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "关机"
    else -> "未知"
}

private fun socName(): String? {
    val model = Build.SOC_MODEL.takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, ignoreCase = true) }
        ?: return null
    val vendor = Build.SOC_MANUFACTURER.takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, ignoreCase = true) }
    return listOfNotNull(vendor, model).joinToString(" ")
}

private fun networkScale(state: MonitorState): Float =
    niceThroughputMax(maxOf(state.downHistory.maxOrNull() ?: 0f, state.upHistory.maxOrNull() ?: 0f))

/** Chinese resource names for the details page; the strip and the gauge cluster stay English. */
internal val Resource.zhTitle: String
    get() = when (this) {
        Resource.Cpu -> "CPU"
        Resource.Gpu -> "GPU"
        Resource.Network -> "网络"
        Resource.Thermal -> "温度"
        Resource.Power -> "功耗"
        Resource.Display -> "显示"
    }

/** Chinese label for a data-source name; raw kernel paths and node names stay as they are. */
private fun sourceZh(source: String): String = when (source) {
    "Frequency estimate" -> "主频估算"
    else -> source
}

/** Battery duration for the details page: "45 分钟", "3 小时 05 分钟", "不到 1 分钟", "超过 99 小时". */
private fun formatDurationZh(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "不到 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes >= 99 * 60 -> "超过 99 小时"
        else -> String.format(java.util.Locale.US, "%d 小时 %02d 分钟", minutes / 60, minutes % 60)
    }
}
