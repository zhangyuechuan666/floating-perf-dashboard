package com.example.sysmonitor.pbb1c5130.content

import androidx.compose.ui.text.TextStyle
import java.util.Locale

fun formatPercent(percent: Float): String = String.format(Locale.US, "%.0f%%", percent)

fun formatGhz(ghz: Float): String = String.format(Locale.US, "%.2f GHz", ghz)

fun formatSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
    bytesPerSecond >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024)
    else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
}

fun formatSpeedOrNa(bytesPerSecond: Double?): String = bytesPerSecond?.let(::formatSpeed) ?: "N/A"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Windows-style up time: d:hh:mm:ss. */
fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d:%02d:%02d", days, hours, minutes, seconds)
}

fun formatTemp(celsius: Float): String = String.format(Locale.US, "%.1f °C", celsius)

fun formatWatts(watts: Float): String =
    if (watts < 10f) String.format(Locale.US, "%.2f W", watts) else String.format(Locale.US, "%.1f W", watts)

fun formatMilliamps(ma: Float): String = String.format(Locale.US, "%.0f mA", ma)

fun formatVolts(volts: Float): String = String.format(Locale.US, "%.2f V", volts)

fun formatHz(hz: Float): String = String.format(Locale.US, "%.0f Hz", hz)

fun formatFps(fps: Float): String = String.format(Locale.US, "%.0f fps", fps)

/** Short per-second rate for the compact strip: 8.5K/s, 120K/s, 1.2M/s, 15M/s. */
fun formatSpeedCompact(bytesPerSecond: Double): String {
    val kb = bytesPerSecond / 1024
    val mb = kb / 1024
    return when {
        kb < 10 -> String.format(Locale.US, "%.1fK/s", kb)
        mb < 1 -> String.format(Locale.US, "%.0fK/s", kb)
        mb < 10 -> String.format(Locale.US, "%.1fM/s", mb)
        else -> String.format(Locale.US, "%.0fM/s", mb)
    }
}

/** Battery duration for the details window: "45 min", "3 h 05 min", "< 1 min", "> 99 h". */
fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        minutes >= 99 * 60 -> "> 99 h"
        else -> String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
    }
}

/** Short battery duration for the compact strip, always hours and minutes: "0h 45m", "3h 05m", ">99h". */
fun formatDurationCompact(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 99 * 60 -> ">99h"
        else -> String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60)
    }
}

fun formatTempCompact(celsius: Float): String = String.format(Locale.US, "%.1f°C", celsius)

/** Same-width digits so a changing value doesn't jitter; keeps the theme style otherwise. */
fun tabularNumbers(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
