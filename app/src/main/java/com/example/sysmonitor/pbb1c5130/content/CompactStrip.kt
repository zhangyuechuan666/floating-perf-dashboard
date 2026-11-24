package com.example.sysmonitor.pbb1c5130.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sysmonitor.pbb1c5130.metrics.BatteryEstimate
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** Height of the visible glass strip. */
val STRIP_HEIGHT = 72.dp

/** Shown in place of a value the device does not expose. */
private const val UNAVAILABLE = "—"

/**
 * The app's main floating window content: one cell per metric (label on top, value below).
 * Each cell is exactly as wide as its widest possible value (see [MetricCell]) and the cells are
 * spread with equal gaps, so spacing looks even and a value changing never moves the others. The whole strip is one
 * button; clicking it calls [onOpenDetails] to open the Task Manager-style details window.
 *
 * PICO enforces a 320x180 dp minimum planar window, so the window itself has no glass
 * (manifest `materialbackground = 0`) and the strip draws its own rounded glass, vertically
 * centred so the details window (placed above) and the drag handle (below) stay close to it;
 * the rest of the window stays transparent.
 */
@Composable
fun CompactStrip(onOpenDetails: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberMonitorState()
    val stats = state.stats
    val cpu = stats.cpu.usage?.let { formatPercent(it.percent) }
    val gpu = stats.gpu?.let { formatPercent(it.percent) }
    val net = stats.network.downloadBps?.let { down ->
        "↓${formatSpeedCompact(down)} ↑${formatSpeedCompact(stats.network.uploadBps ?: 0.0)}"
    }
    val temp = stats.thermal.batteryTempC?.let(::formatTempCompact)
    val hz = stats.display.refreshHz?.let(::formatHz)
    val battery = batteryValue(state.batteryEstimate)

    val shape = RoundedCornerShape(STRIP_HEIGHT / 2)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT)
                // clip before material and hover so both follow the pill shape
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .spatialHoverEffect()
                .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onOpenDetails)
                .semantics(mergeDescendants = true) {
                    contentDescription = "CPU ${cpu ?: "unavailable"}, GPU ${gpu ?: "unavailable"}, " +
                        "network ${net ?: "unavailable"}, battery temperature ${temp ?: "unavailable"}, " +
                        "battery ${battery?.spoken ?: "unavailable"}, refresh ${hz ?: "unavailable"}. Open details"
                }
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Widest strings each value can take ("8" = widest tabular digit).
            MetricCell(Resource.Cpu.accent, "CPU", cpu, widest = listOf("100%"))
            MetricCell(Resource.Gpu.accent, "GPU", gpu, widest = listOf("100%"))
            MetricCell(Resource.Network.accent, "NET", net, widest = listOf("↓888K/s ↑888K/s"))
            MetricCell(Resource.Thermal.accent, "TEMP", temp, widest = listOf("88.8°C"))
            MetricCell(
                Resource.Power.accent,
                "BATTERY",
                battery?.shown,
                widest = batteryWidest(pluggedIn = stats.power.pluggedSource != null),
            )
            MetricCell(Resource.Display.accent, "REFRESH", hz, widest = listOf("888 Hz"))
            Text(
                text = "›",
                style = PicoTheme.typography.titleSmall,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        }
    }
}

/** Placeholder while the first ~10 s of current readings are averaged. */
private const val ESTIMATING = "Estimating…"

/** The BATTERY cell's text on screen and for screen readers. */
private class BatteryValue(val shown: String, val spoken: String)

/**
 * Battery life only: "3h 12m left" on battery, "0h 45m to full" while charging, "Estimating…"
 * until enough readings exist. Null when the device can't provide an estimate.
 */
private fun batteryValue(estimate: BatteryEstimate): BatteryValue? = when (estimate) {
    is BatteryEstimate.TimeLeft -> formatDurationCompact(estimate.ms).let { BatteryValue("$it left", "$it left") }
    is BatteryEstimate.TimeToFull -> estimate.ms?.let(::formatDurationCompact)
        ?.let { BatteryValue("$it to full", "$it to full") }
        ?: BatteryValue(ESTIMATING, "estimating time to full")
    BatteryEstimate.Full -> BatteryValue("Full", "full")
    BatteryEstimate.Estimating -> BatteryValue(ESTIMATING, "estimating time left")
    BatteryEstimate.Unavailable -> null
}

/**
 * Widest texts the BATTERY cell can show in the current power state ("8" = widest digit). The
 * cell keeps this width from "Estimating…" through every estimate; it only changes on plug-in.
 */
private fun batteryWidest(pluggedIn: Boolean): List<String> =
    if (pluggedIn) listOf(ESTIMATING, "88h 88m to full") else listOf(ESTIMATING, "88h 88m left")

/**
 * One metric column: coloured dot + small caption, value underneath. Value null = unavailable.
 * Invisible copies of the [widest] texts reserve the column width, so the cell's size depends only on
 * what it can show, never on the current value.
 */
@Composable
private fun MetricCell(accent: Color, label: String, value: String?, widest: List<String>) {
    val valueStyle = tabularNumbers(PicoTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(6.dp)) { drawCircle(accent) }
            Text(
                text = label,
                style = PicoTheme.typography.labelSmall,
                color = PicoTheme.colorScheme.labelSecondary,
                maxLines = 1,
            )
        }
        Box {
            widest.forEach { template ->
                Text(
                    text = template,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                    style = valueStyle,
                    maxLines = 1,
                )
            }
            Text(
                text = value ?: UNAVAILABLE,
                style = valueStyle,
                color = if (value != null) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
