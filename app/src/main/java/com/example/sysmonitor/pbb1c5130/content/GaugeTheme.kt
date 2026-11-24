package com.example.sysmonitor.pbb1c5130.content

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.layout.offset
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * App-wide theme. Classic: the glass strip is the floating window and details uses graphs.
 * Gauge: the 3D dial cluster replaces the strip and details uses the gauge pane.
 */
enum class DetailsTheme(val title: String) {
    Classic("经典"),
    Gauge("表盘"),
}

/**
 * Process-wide theme shared by every window, remembered across launches because it decides
 * which floating window (strip or cluster) the app opens with. Classic until the user changes it.
 */
object AppTheme {
    private const val PREFS = "details_ui"
    private const val KEY = "theme"
    private var prefs: SharedPreferences? = null

    var theme by mutableStateOf(DetailsTheme.Classic)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        theme = p.getString(KEY, null)?.let { name -> DetailsTheme.entries.firstOrNull { it.name == name } }
            ?: DetailsTheme.Classic
    }

    fun select(next: DetailsTheme) {
        theme = next
        prefs?.edit()?.putString(KEY, next.name)?.apply()
    }
}

/** The shared [AppTheme], loaded from storage on first use. */
@Composable
fun rememberAppTheme(): AppTheme {
    val context = LocalContext.current
    remember(context) { AppTheme.init(context) }
    return AppTheme
}

/** Native SpatialUI segmented switch shown in the top-left corner of the details window. */
@Composable
fun ThemeSwitcher(state: AppTheme, modifier: Modifier = Modifier) {
    SegmentControl(modifier) {
        DetailsTheme.entries.forEach { theme ->
            SegmentItem(
                selected = state.theme == theme,
                onClick = { state.select(theme) },
                title = { Text(theme.title) },
            )
        }
    }
}

/**
 * Physical materials of the skeuomorphic instrument. These are not UI colours: they paint the
 * chrome bezel, the black dial face, shadows and the glass sheen inside the gauge only.
 */
private object GaugeMaterial {
    val ChromeLight = Color(0xFFF2F4F6) // design-style: fixed-figma-color skeuomorphic chrome highlight
    val ChromeMid = Color(0xFFA9AFB6) // design-style: fixed-figma-color skeuomorphic chrome mid-tone
    val ChromeDark = Color(0xFF50565D) // design-style: fixed-figma-color skeuomorphic chrome shadow
    val FaceCenter = Color(0xFF2C3036) // design-style: fixed-figma-color black dial face centre
    val FaceEdge = Color(0xFF0C0E11) // design-style: fixed-figma-color black dial face edge
    val LcdGlass = Color(0xFF050607) // design-style: fixed-figma-color LCD readout window
    val Shadow = Color(0x73000000) // design-style: fixed-figma-color needle drop shadow
    val Sheen = Color(0x2EFFFFFF) // design-style: fixed-figma-color glass cover reflection
    val Glint = Color(0x52FFFFFF) // design-style: fixed-figma-color moving light band on the glass
}

/** Needle spring: low damping gives a visible overshoot and settle, like a real gauge needle. */
private const val NEEDLE_DAMPING = 0.42f
private const val NEEDLE_STIFFNESS = 55f

/** How far past the end stops the needle may bounce (fraction of the scale). */
private const val NEEDLE_BOUNCE = 0.015f

/** Idle time between light sweeps across the glass, and the sweep's own duration. */
private const val GLINT_INTERVAL_MS = 8_000L
private const val GLINT_DURATION_MS = 1_600

/** A reading change larger than this fraction of the scale makes the backlight flare. */
private const val FLARE_THRESHOLD = 0.10f

/**
 * Depth of each layer above the dial face. The headset shows these as real stereo depth,
 * so the needle visibly floats over the face and the glass and bezel sit in front of both.
 */
private val NEEDLE_DEPTH = 6.dp
private val CAP_DEPTH = 9.dp
private val GLASS_DEPTH = 14.dp

/** Dial scale: a 240° sweep from lower-left to lower-right, like a rev counter. */
internal const val START_DEGREES = 150f
internal const val SWEEP_DEGREES = 240f
private const val MINOR_PER_MAJOR = 5

private fun fraction(value: Float, min: Float, max: Float): Float =
    if (max <= min) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

private fun polar(center: Offset, degrees: Float, distance: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + distance * cos(rad).toFloat(), center.y + distance * sin(rad).toFloat())
}

/** Tapered needle with a short counterweight tail, pointing at [degrees]. */
private fun needlePath(center: Offset, degrees: Float, length: Float, tail: Float, halfWidth: Float): Path {
    val side = degrees + 90f
    return Path().apply {
        val tip = polar(center, degrees, length)
        val baseL = polar(center, side, halfWidth)
        val baseR = polar(center, side + 180f, halfWidth)
        val tailCenter = polar(center, degrees + 180f, tail)
        moveTo(tip.x, tip.y)
        lineTo(baseL.x, baseL.y)
        polar(tailCenter, side, halfWidth * 1.4f).let { lineTo(it.x, it.y) }
        polar(tailCenter, side + 180f, halfWidth * 1.4f).let { lineTo(it.x, it.y) }
        lineTo(baseR.x, baseR.y)
        close()
    }
}

/**
 * Skeuomorphic instrument built as stacked spatial layers: black dial face (printed scale,
 * backlit sweep, redline, needle shadow, LCD readout) at the back; needle, chrome centre cap,
 * then glass sheen and chrome bezel progressively closer to the user. Past the redline the face
 * rim pulses red. Pointing at the gauge makes it swell slightly (SpatialUI hover effect).
 *
 * Motion: the needle is spring-driven (overshoot and settle) and does an ignition sweep to full
 * scale the first time the gauge appears; a light band periodically crosses the glass while the
 * bezel and cap highlights turn with it; the backlight and LCD flare when the reading jumps.
 * Animated values are read only inside draw blocks where possible, so they redraw without
 * recomposing, and every effect runs in short bursts rather than continuously.
 */
/**
 * Spring-driven needle position as a fraction of the scale, shared by everything that shows it
 * (the drawn sweep and shadow, and the 3D needle in the cluster window). Starts with an ignition
 * sweep to full scale, then follows [target].
 */
class NeedleState internal constructor() {
    val position = Animatable(0f)
    internal val ignitionDone = mutableStateOf(false)

    /** Needle direction on the dial in canvas degrees (clockwise from +X, y down). */
    val canvasDegrees: Float
        get() = START_DEGREES + SWEEP_DEGREES * position.value.coerceIn(-NEEDLE_BOUNCE, 1f + NEEDLE_BOUNCE)
}

@Composable
fun rememberNeedleState(target: Float): NeedleState {
    val state = remember { NeedleState() }
    LaunchedEffect(Unit) {
        // Ignition self-test: sweep to full scale and back, like a car's cluster at start-up.
        state.position.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 40f))
        state.ignitionDone.value = true
    }
    LaunchedEffect(target, state.ignitionDone.value) {
        if (state.ignitionDone.value) state.position.animateTo(target, spring(NEEDLE_DAMPING, NEEDLE_STIFFNESS))
    }
    return state
}

/** Position of [value] on a [min]..[max] scale, clamped to 0..1. */
fun scaleFraction(value: Float?, min: Float, max: Float): Float = fraction(value ?: min, min, max)

@Composable
fun GaugeDial(
    value: Float?,
    min: Float,
    max: Float,
    redlineFrom: Float?,
    peak: Float?,
    accent: Color,
    tickLabel: (Float) -> String,
    readout: String,
    unit: String,
    detail: String?,
    modifier: Modifier = Modifier,
    majorTicks: Int = 5,
    needle: NeedleState = rememberNeedleState(scaleFraction(value, min, max)),
    /** Draw only the face (scale, sweep, LCD, needle shadow); the caller supplies needle, cap, glass and bezel. */
    faceOnly: Boolean = false,
) {
    val colors = PicoTheme.colorScheme
    val onFace = colors.labelPrimaryLight
    val red = colors.error
    val scaleStyle = PicoTheme.typography.labelMedium.copy(color = onFace, textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val inRedline = value != null && redlineFrom != null && value >= redlineFrom
    fun angle(v: Float) = START_DEGREES + SWEEP_DEGREES * fraction(v, min, max)

    // Needle position as a fraction of the scale, so switching tabs swings it to the new reading.
    val target = scaleFraction(value, min, max)
    val ignitionDone = needle.ignitionDone
    fun needleAngleNow() = needle.canvasDegrees

    // Backlight flare when the reading jumps.
    val flare = remember { Animatable(0f) }
    val lastTarget = remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        val jump = abs(target - lastTarget.value)
        lastTarget.value = target
        if (jump >= FLARE_THRESHOLD) {
            flare.snapTo((jump * 3f).coerceAtMost(1f))
            flare.animateTo(0f, tween(900))
        }
    }

    // Periodic light sweep across the glass; -1 = idle (no frames drawn for it).
    val glint = remember { Animatable(-1f) }
    if (!faceOnly) LaunchedEffect(Unit) {
        while (true) {
            delay(GLINT_INTERVAL_MS)
            glint.snapTo(0f)
            glint.animateTo(1f, tween(GLINT_DURATION_MS, easing = FastOutSlowInEasing))
            glint.snapTo(-1f)
        }
    }

    BoxWithConstraints(
        modifier.spatialHoverEffect { scale(if (it.isActive) 1.03f else 1f) },
        contentAlignment = Alignment.TopCenter,
    ) {
        val radius = minOf(maxWidth, maxHeight) / 2 * 0.97f
        val centerY = maxHeight / 2

        // Layer 0: the dial face, everything printed on it, and the needle's shadow.
        Canvas(Modifier.fillMaxSize()) {
            val r = radius.toPx()
            val c = Offset(size.width / 2f, centerY.toPx())
            val face = r * 0.92f

            drawCircle(
                brush = Brush.radialGradient(listOf(GaugeMaterial.FaceCenter, GaugeMaterial.FaceEdge), c, face),
                radius = face,
                center = c,
            )

            // Printed redline band.
            redlineFrom?.takeIf { it < max }?.let { from ->
                val a = angle(from)
                drawRing(red, c, face * 0.92f, a, START_DEGREES + SWEEP_DEGREES - a, face * 0.05f)
            }

            // Backlit sweep up to the value, with a soft glow like an illuminated ring.
            val ring = face * 0.84f
            drawRing(onFace.copy(alpha = 0.10f), c, ring, START_DEGREES, SWEEP_DEGREES, face * 0.04f)
            if (value != null || !ignitionDone.value) {
                val sweep = needleAngleNow() - START_DEGREES
                val f = flare.value
                if (sweep > 0f) {
                    drawRing(accent.copy(alpha = 0.18f + 0.22f * f), c, ring, START_DEGREES, sweep, face * (0.12f + 0.08f * f))
                    drawRing(accent.copy(alpha = 0.35f + 0.3f * f), c, ring, START_DEGREES, sweep, face * 0.07f)
                    drawRing(accent, c, ring, START_DEGREES, sweep, face * 0.035f)
                }
            }

            // Printed ticks and numbers.
            val totalTicks = majorTicks * MINOR_PER_MAJOR
            val tickOuter = face * 0.78f
            for (i in 0..totalTicks) {
                val major = i % MINOR_PER_MAJOR == 0
                val v = min + (max - min) * i / totalTicks
                val a = START_DEGREES + SWEEP_DEGREES * i / totalTicks
                val length = if (major) face * 0.11f else face * 0.05f
                val tickColor = if (redlineFrom != null && v >= redlineFrom) red else onFace.copy(alpha = if (major) 0.95f else 0.6f)
                drawLine(tickColor, polar(c, a, tickOuter), polar(c, a, tickOuter - length), if (major) 2.5.dp.toPx() else 1.dp.toPx())
                if (major) {
                    val text = measurer.measure(tickLabel(v), scaleStyle)
                    val at = polar(c, a, tickOuter - length - 6.dp.toPx() - text.size.width / 2f)
                    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
                }
            }

            // Peak tell-tale: a small marker just inside the redline band.
            peak?.let {
                val a = angle(it)
                drawLine(onFace, polar(c, a, face * 0.89f), polar(c, a, face * 0.97f), 2.dp.toPx(), cap = StrokeCap.Round)
            }

            // LCD window below the hub.
            val lcdWidth = face * 0.84f
            val lcdHeight = face * 0.44f
            val lcdTop = c.y + face * 0.30f
            drawRoundRect(
                color = GaugeMaterial.LcdGlass,
                topLeft = Offset(c.x - lcdWidth / 2f, lcdTop),
                size = Size(lcdWidth, lcdHeight),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawRoundRect(
                color = onFace.copy(alpha = 0.12f),
                topLeft = Offset(c.x - lcdWidth / 2f, lcdTop),
                size = Size(lcdWidth, lcdHeight),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
            // LCD edge lights up in the accent colour during a flare.
            flare.value.takeIf { it > 0.01f }?.let { f ->
                drawRoundRect(
                    color = accent.copy(alpha = 0.7f * f),
                    topLeft = Offset(c.x - lcdWidth / 2f, lcdTop),
                    size = Size(lcdWidth, lcdHeight),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
            }

            // Needle shadow cast onto the face; the needle itself floats NEEDLE_DEPTH above it.
            drawPath(
                needlePath(c + Offset(3.dp.toPx(), 4.dp.toPx()), needleAngleNow(), face * 0.80f, face * 0.16f, 3.5.dp.toPx()),
                GaugeMaterial.Shadow,
            )
        }

        // LCD digits, printed on the face layer.
        Column(
            modifier = Modifier.offset(y = centerY + radius * 0.92f * 0.32f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Backlit LCD digits: a constant soft glow that brightens during a flare.
            val glow = 6f + 14f * flare.value
            Text(
                text = readout,
                style = tabularNumbers(PicoTheme.typography.headlineMedium).copy(
                    shadow = if (value != null) Shadow(accent.copy(alpha = 0.55f + 0.4f * flare.value), blurRadius = glow) else null,
                ),
                color = if (value != null) onFace else onFace.copy(alpha = 0.5f),
                maxLines = 1,
            )
            Text(text = unit, style = PicoTheme.typography.labelSmall, color = onFace.copy(alpha = 0.75f), maxLines = 1)
            detail?.let {
                Text(text = it, style = PicoTheme.typography.labelSmall, color = onFace.copy(alpha = 0.55f), maxLines = 1)
            }
        }

        // Redline warning: the face rim pulses red while the value is past the redline.
        if (inRedline) {
            val pulse by rememberInfiniteTransition(label = "redline").animateFloat(
                initialValue = 0.25f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "redlinePulse",
            )
            Canvas(Modifier.fillMaxSize().offset(z = 1.dp)) {
                val r = radius.toPx()
                drawCircle(red.copy(alpha = pulse), radius = r * 0.90f, center = Offset(size.width / 2f, centerY.toPx()), style = Stroke(r * 0.04f))
            }
        }

        if (!faceOnly) {
        // Layer 1: the needle.
        val needleColor = when {
            value == null && ignitionDone.value -> onFace.copy(alpha = 0.4f)
            value == null -> accent
            inRedline -> red
            else -> accent
        }
        Canvas(Modifier.fillMaxSize().offset(z = NEEDLE_DEPTH)) {
            val r = radius.toPx()
            val c = Offset(size.width / 2f, centerY.toPx())
            val face = r * 0.92f
            val needleAngle = needleAngleNow()
            drawPath(needlePath(c, needleAngle, face * 0.80f, face * 0.16f, 3.5.dp.toPx()), needleColor)
            // Thin highlight along the needle's spine.
            drawLine(
                onFace.copy(alpha = 0.45f),
                c,
                polar(c, needleAngle, face * 0.74f),
                1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Layer 2: chrome centre cap.
        Canvas(Modifier.fillMaxSize().offset(z = CAP_DEPTH)) {
            val c = Offset(size.width / 2f, centerY.toPx())
            val capRadius = radius.toPx() * 0.09f
            // The cap's hot spot slides from upper-left to lower-right as the light band passes.
            val g = glint.value.takeIf { it >= 0f } ?: 0f
            val hot = -0.35f + 0.7f * g
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(GaugeMaterial.ChromeLight, GaugeMaterial.ChromeMid, GaugeMaterial.ChromeDark),
                    c + Offset(capRadius * hot, capRadius * hot),
                    capRadius * 1.3f,
                ),
                radius = capRadius,
                center = c,
            )
        }

        // Layer 3: glass cover reflection and the chrome bezel, closest to the user.
        Canvas(Modifier.fillMaxSize().offset(z = GLASS_DEPTH)) {
            val r = radius.toPx()
            val c = Offset(size.width / 2f, centerY.toPx())
            val face = r * 0.92f

            // Curved reflection across the upper part of the glass.
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(GaugeMaterial.Sheen, Color.Transparent),
                    startY = c.y - face * 0.92f,
                    endY = c.y - face * 0.05f,
                ),
                topLeft = Offset(c.x - face * 0.72f, c.y - face * 0.92f),
                size = Size(face * 1.44f, face * 0.95f),
            )

            // Moving light band across the glass, clipped to the face.
            glint.value.takeIf { it >= 0f }?.let { g ->
                val dir = Offset(0.7071f, 0.7071f)
                val p = (-1.4f + 2.8f * g) * face
                val half = face * 0.28f
                clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, face)) }) {
                    drawCircle(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, GaugeMaterial.Glint, Color.Transparent),
                            start = c + dir * (p - half),
                            end = c + dir * (p + half),
                        ),
                        radius = face,
                        center = c,
                    )
                }
            }

            // Chrome bezel: a turned-metal ring with an inner bevel. Its highlights turn with the
            // light band, as if the light source were moving across the metal.
            val bezel = r - face
            val bezelTurn = (glint.value.takeIf { it >= 0f } ?: 0f) * 70f
            rotate(bezelTurn, pivot = c) { drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        GaugeMaterial.ChromeLight, GaugeMaterial.ChromeMid, GaugeMaterial.ChromeDark,
                        GaugeMaterial.ChromeMid, GaugeMaterial.ChromeLight, GaugeMaterial.ChromeMid,
                        GaugeMaterial.ChromeDark, GaugeMaterial.ChromeMid, GaugeMaterial.ChromeLight,
                    ),
                    c,
                ),
                radius = face + bezel / 2f,
                center = c,
                style = Stroke(bezel),
            ) }
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(GaugeMaterial.ChromeDark, GaugeMaterial.ChromeLight),
                    start = c + Offset(-face, -face),
                    end = c + Offset(face, face),
                ),
                radius = face,
                center = c,
                style = Stroke(1.5.dp.toPx()),
            )
        }
        }
    }
}

/**
 * Shift-light bar: LEDs in a dark housing light up from the left as the value rises; LEDs past
 * the redline glow in the theme's `error` red, like the light row above a race car's rev counter.
 */
@Composable
fun ShiftLights(
    value: Float?,
    min: Float,
    max: Float,
    redlineFrom: Float?,
    accent: Color,
    modifier: Modifier = Modifier,
    segments: Int = 15,
) {
    val red = PicoTheme.colorScheme.error
    val off = PicoTheme.colorScheme.labelPrimaryLight.copy(alpha = 0.12f)
    Canvas(modifier) {
        drawRoundRect(GaugeMaterial.FaceEdge, cornerRadius = CornerRadius(size.height / 2f))
        val pad = size.height * 0.25f
        val gap = 4.dp.toPx()
        val inner = size.height - pad * 2
        val width = (size.width - pad * 2 - gap * (segments - 1)) / segments
        val lit = value?.let { fraction(it, min, max) } ?: 0f
        for (i in 0 until segments) {
            val threshold = (i + 0.5f) / segments
            val inRedline = redlineFrom != null && threshold >= fraction(redlineFrom, min, max)
            val on = threshold <= lit
            val color = when {
                !on -> off
                inRedline -> red
                else -> accent
            }
            val topLeft = Offset(pad + i * (width + gap), pad)
            if (on) {
                // LED glow around the lit segment.
                drawRoundRect(
                    color.copy(alpha = 0.3f),
                    topLeft = topLeft - Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(width + 4.dp.toPx(), inner + 4.dp.toPx()),
                    cornerRadius = CornerRadius(inner),
                )
            }
            drawRoundRect(color, topLeft = topLeft, size = Size(width, inner), cornerRadius = CornerRadius(inner / 2f))
        }
    }
}

/** Small half-circle gauge for the side list, replacing the mini line graph in the Gauge theme. */
@Composable
fun MiniDial(
    value: Float?,
    min: Float,
    max: Float,
    redlineFrom: Float?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val track = PicoTheme.colorScheme.labelQuaternary
    val red = PicoTheme.colorScheme.error
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val r = minOf(size.width / 2f, size.height) - stroke
            val center = Offset(size.width / 2f, size.height - stroke / 2f)
            drawRing(track, center, r, 180f, 180f, stroke)
            redlineFrom?.takeIf { it < max }?.let { from ->
                val a = 180f + 180f * fraction(from, min, max)
                drawRing(red, center, r + stroke * 0.75f, a, 360f - a, 1.5.dp.toPx())
            }
            value?.let {
                val sweep = 180f * fraction(it, min, max)
                if (sweep > 0f) drawRing(accent, center, r, 180f, sweep, stroke)
            }
            drawLine(
                track,
                center,
                polar(center, 180f + 180f * fraction(value ?: min, min, max), r - stroke),
                1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawRing(color: Color, center: Offset, radius: Float, start: Float, sweep: Float, width: Float) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Butt),
    )
}
