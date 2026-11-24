package com.example.sysmonitor.pbb1c5130.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val GRID_DIVISIONS = 10

/** Samples between two vertical grid lines; the grid scrolls one sample per tick. */
private const val SAMPLES_PER_COLUMN = HISTORY_SIZE / GRID_DIVISIONS

/**
 * Task Manager-style utilisation graph: framed plot with a 10x10 grid that scrolls
 * left as time passes, a filled area under [primary], and an optional dashed
 * [secondary] series (used for network upload). Newest sample is at the right edge.
 */
@Composable
fun UsageGraph(
    primary: List<Float>,
    maxValue: Float,
    accent: Color,
    tick: Int,
    modifier: Modifier = Modifier,
    secondary: List<Float>? = null,
    showGrid: Boolean = true,
    minValue: Float = 0f,
) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val stepX = size.width / (HISTORY_SIZE - 1)

        if (showGrid) {
            val grid = accent.copy(alpha = 0.28f)
            for (row in 1 until GRID_DIVISIONS) {
                val y = size.height * row / GRID_DIVISIONS
                drawLine(grid, Offset(0f, y), Offset(size.width, y), stroke)
            }
            val shift = (tick % SAMPLES_PER_COLUMN) * stepX
            val columnWidth = SAMPLES_PER_COLUMN * stepX
            var x = columnWidth - shift
            while (x < size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), stroke)
                x += columnWidth
            }
        }

        drawSeries(primary, minValue, maxValue, stepX, accent, filled = true, dashed = false)
        secondary?.let { drawSeries(it, minValue, maxValue, stepX, accent, filled = false, dashed = true) }

        drawRect(accent.copy(alpha = 0.7f), style = Stroke(stroke))
    }
}

private fun DrawScope.drawSeries(
    values: List<Float>,
    minValue: Float,
    maxValue: Float,
    stepX: Float,
    accent: Color,
    filled: Boolean,
    dashed: Boolean,
) {
    val range = maxValue - minValue
    if (values.size < 2 || range <= 0f) return
    val startIndex = HISTORY_SIZE - values.size
    val line = Path()
    values.forEachIndexed { i, v ->
        val x = (startIndex + i) * stepX
        val y = size.height * (1f - ((v - minValue) / range).coerceIn(0f, 1f))
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    if (filled) {
        val area = Path().apply {
            addPath(line)
            lineTo((HISTORY_SIZE - 1) * stepX, size.height)
            lineTo(startIndex * stepX, size.height)
            close()
        }
        drawPath(area, accent.copy(alpha = 0.14f))
    }
    drawPath(
        line,
        accent,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

/** Rounds a throughput peak up to a power-of-two axis maximum, starting at 8 KB/s. */
fun niceThroughputMax(peakBps: Float): Float {
    var step = 8 * 1024f // 8 KB/s minimum so an idle link doesn't look busy
    while (step < peakBps) {
        step *= 2f
    }
    return step
}

/** Rounds a positive peak up to the next value of a 1-2-5 ladder (e.g. 3.4 W -> 5 W). */
fun niceMax(peak: Float, floor: Float): Float {
    var magnitude = 1f
    while (magnitude * 10 <= peak) magnitude *= 10
    while (magnitude > peak && magnitude > 0.01f) magnitude /= 10
    for (step in floatArrayOf(1f, 2f, 5f, 10f)) {
        val candidate = step * magnitude
        if (candidate >= peak) return maxOf(candidate, floor)
    }
    return maxOf(10 * magnitude, floor)
}
