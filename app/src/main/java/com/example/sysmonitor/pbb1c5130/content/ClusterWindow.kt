package com.example.sysmonitor.pbb1c5130.content

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sysmonitor.pbb1c5130.R
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.AttachmentPanelComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.panelSize
import com.pico.spatial.ui.foundation.content.setAccessibilityActions
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.platform.LengthUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/*
 * GT-row instrument cluster (picked by the user): six gauges in a gently curved row, the large
 * CPU/GPU pair in the middle, medium Network/Display, small Temperature/Power at the ends.
 * Finishes: aviation chronograph (centre pair), vintage GT cream (medium), aircraft black (ends).
 * Dial plates are generated images (`plate_*.jpg`); bezels, bodies, caps and needles are built in
 * code (`ClusterGeometry.kt`). The app prints every scale live on the plates.
 */

/** Stable semantic IDs of the read-only gauge faces (literal so the A11Y inventory resolves them). */
private const val CLUSTER_TEMPERATURE_ID = "cluster.temperature"
private const val CLUSTER_NETWORK_ID = "cluster.network"
private const val CLUSTER_CPU_ID = "cluster.cpu"
private const val CLUSTER_GPU_ID = "cluster.gpu"
private const val CLUSTER_DISPLAY_ID = "cluster.display"
private const val CLUSTER_POWER_ID = "cluster.power"

/** Printing inks; physical paint on the dial plates, not UI colours. */
private object Ink {
    val Lume = Color(0xFFEDEBE2) // design-style: fixed-figma-color aircraft dial lume print
    val Black = Color(0xFF15171A) // design-style: fixed-figma-color black dial print
    val VintageBlack = Color(0xFF1E1B17) // design-style: fixed-figma-color vintage dial print
    val Red = Color(0xFFC0261F) // design-style: fixed-figma-color redline paint
    val Yellow = Color(0xFFE2B320) // design-style: fixed-figma-color caution arc paint
    val Green = Color(0xFF2E9A4A) // design-style: fixed-figma-color normal arc paint
    val Shadow = Color(0x52000000) // design-style: fixed-figma-color needle shadow on the dial
}

/** One dial style: plate image, printing, needle and bezel construction. */
private enum class DialFinish(
    @DrawableRes val plate: Int,
    val ink: Color,
    /** Railway-track minute ring at the edge (chronograph). */
    val railway: Boolean,
    val numeralWeight: FontWeight,
    val tickWidth: Float,
    val needle: Finish,
    val cap: Finish,
    val bezel: Finish,
    val chamfer: Finish,
    val body: Finish,
    val bezelHeight: Float,
    val bezelOuter: Float,
    val flutes: Int,
    val fluteDepth: Float,
) {
    Chronograph(
        R.drawable.plate_opaline, Ink.Black, railway = true, numeralWeight = FontWeight.SemiBold, tickWidth = 1f,
        needle = Finishes.BluedSteel, cap = Finishes.PolishedSteel,
        bezel = Finishes.TitaniumSatin, chamfer = Finishes.TitaniumPolished, body = Finishes.TitaniumSatin,
        bezelHeight = 0.13f, bezelOuter = 1.16f, flutes = 150, fluteDepth = 0.006f,
    ),
    VintageCream(
        R.drawable.plate_cream, Ink.VintageBlack, railway = false, numeralWeight = FontWeight.Normal, tickWidth = 0.8f,
        needle = Finishes.GlossBlack, cap = Finishes.Chrome,
        bezel = Finishes.Chrome, chamfer = Finishes.Chrome, body = Finishes.DarkChrome,
        bezelHeight = 0.09f, bezelOuter = 1.11f, flutes = 90, fluteDepth = 0.012f,
    ),
    AircraftBlack(
        R.drawable.plate_black, Ink.Lume, railway = false, numeralWeight = FontWeight.Bold, tickWidth = 1.4f,
        needle = Finishes.LumeWhite, cap = Finishes.AnodisedBlack,
        bezel = Finishes.AnodisedBlack, chamfer = Finishes.DiamondCutAluminium, body = Finishes.AnodisedBlack,
        bezelHeight = 0.12f, bezelOuter = 1.17f, flutes = 120, fluteDepth = 0.010f,
    ),
}

private const val ROW_CURVE = 0.6f

/** Where a gauge sits in the row. Position in metres from the window centre; radius = dial radius. */
private class GaugeSlot(val faceId: String, val resource: Resource, val finish: DialFinish, val x: Float, val radius: Float) {
    // Gentle GT arc: the ends come toward the viewer and drop a little, each gauge turned to face the centre.
    val z = ROW_CURVE * x * x - 0.04f
    val y = 0.012f - 0.25f * x * x
    val yawRadians = -atan(2f * ROW_CURVE * x)
}

private val SLOTS = listOf(
    GaugeSlot(CLUSTER_TEMPERATURE_ID, Resource.Thermal, DialFinish.AircraftBlack, x = -0.388f, radius = 0.045f),
    GaugeSlot(CLUSTER_NETWORK_ID, Resource.Network, DialFinish.VintageCream, x = -0.259f, radius = 0.056f),
    GaugeSlot(CLUSTER_CPU_ID, Resource.Cpu, DialFinish.Chronograph, x = -0.095f, radius = 0.075f),
    GaugeSlot(CLUSTER_GPU_ID, Resource.Gpu, DialFinish.Chronograph, x = 0.095f, radius = 0.075f),
    GaugeSlot(CLUSTER_DISPLAY_ID, Resource.Display, DialFinish.VintageCream, x = 0.259f, radius = 0.056f),
    GaugeSlot(CLUSTER_POWER_ID, Resource.Power, DialFinish.AircraftBlack, x = 0.388f, radius = 0.045f),
)

/** Needle reach, pivot height and cap size, per unit dial radius. */
private const val NEEDLE_REACH = 0.84f
private const val NEEDLE_Z = 0.035f
private const val CAP_RADIUS = 0.085f

/** Moving key light: one orbit every LIGHT_PERIOD_S seconds so the metal highlights travel. */
private const val LIGHT_PERIOD_S = 12.0

/** A dial scale: sweep geometry, range and printing. */
private class DialScale(
    val startDeg: Float,
    val sweepDeg: Float,
    val min: Float,
    val max: Float,
    val majors: Int,
    val minorsPerMajor: Int,
    val label: (Float) -> String,
    /** Coloured arcs printed along the scale: (from, to, colour). */
    val bands: List<Triple<Float, Float, Color>> = emptyList(),
    val caption: String,
    val readout: String,
    /** Optional marker on the scale (e.g. the display refresh rate). */
    val marker: Float? = null,
) {
    fun fraction(v: Float) = if (max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)
    fun angle(fraction: Float) = startDeg + sweepDeg * fraction.coerceIn(-0.015f, 1.015f)
}

private fun fmt(pattern: String, v: Float) = String.format(Locale.US, pattern, v)

/** Face per metric: tach for CPU/GPU, twin-needle network, short-arc temperature, centre-zero power, speedometer fps. */
private fun dialScale(resource: Resource, state: MonitorState): DialScale {
    val stats = state.stats
    return when (resource) {
        Resource.Cpu, Resource.Gpu -> DialScale(
            150f, 240f, 0f, 100f, majors = 10, minorsPerMajor = 5,
            label = { (it / 10f).toInt().toString() },
            bands = listOf(Triple(85f, 100f, Ink.Red)),
            caption = "${resource.title}  ×10 %",
            readout = gaugeValue(resource, state)?.let { fmt("%.0f %%", it) } ?: "—",
        )
        Resource.Network -> DialScale(
            150f, 240f, 0f, graphSpec(resource, state).max, majors = 4, minorsPerMajor = 5,
            label = { if (it <= 0f) "0" else formatSpeedCompact(it.toDouble()).removeSuffix("/s") },
            caption = "NET  B/s",
            readout = "↓${stats.network.downloadBps?.let(::formatSpeedCompact) ?: "—"}  ↑${stats.network.uploadBps?.let(::formatSpeedCompact) ?: "—"}",
        )
        Resource.Thermal -> DialScale(
            225f, 90f, 20f, 60f, majors = 4, minorsPerMajor = 5,
            label = { fmt("%.0f", it) },
            bands = listOf(Triple(20f, 40f, Ink.Green), Triple(40f, 45f, Ink.Yellow), Triple(45f, 60f, Ink.Red)),
            caption = "BATT  °C",
            readout = stats.thermal.batteryTempC?.let { fmt("%.1f °C", it) } ?: "—",
        )
        Resource.Power -> {
            val m = niceMax(maxOf(state.powerHistory.maxOrNull() ?: 0f, abs(stats.power.watts ?: 0f)), floor = 1f)
            DialScale(
                200f, 140f, -m, m, majors = 4, minorsPerMajor = 4,
                label = { if (it == 0f) "0" else fmt(if (m < 10f) "%+.1f" else "%+.0f", it) },
                bands = listOf(Triple(-m, -0.85f * m, Ink.Red)),
                caption = "DISCH  W  CHG",
                readout = stats.power.watts?.let { w -> if (w < 0f) "+" + formatWatts(-w) else formatWatts(w) } ?: "—",
            )
        }
        Resource.Display -> DialScale(
            150f, 240f, 0f, 120f, majors = 6, minorsPerMajor = 10,
            label = { fmt("%.0f", it) },
            caption = "FPS",
            readout = listOfNotNull(stats.display.measuredFps?.let { fmt("%.0f fps", it) }, stats.display.refreshHz?.let { fmt("%.0f Hz", it) })
                .joinToString(" · ").ifEmpty { "—" },
            marker = stats.display.refreshHz,
        )
    }
}

/** Value the main needle shows, on the dial's own scale (power: charging to the right). */
private fun needleValue(resource: Resource, state: MonitorState): Float? = when (resource) {
    Resource.Power -> state.stats.power.watts?.let { -it }
    else -> gaugeValue(resource, state)
}

private class ClusterScene {
    val pivots = arrayOfNulls<Entity>(SLOTS.size)
    var sendPivot: Entity? = null
    val faces = arrayOfNulls<Entity>(SLOTS.size)
    var describedTick = -1
    var light: Entity? = null
}

/**
 * Volumetric GT-row instrument cluster. Each dial is an `AttachmentPanel` (plate + live printing)
 * set into a code-built bezel; needles and caps are 3D and lit by the renderer.
 *
 * No 3D glass lens: in SDK 6.2.8 any entity in front of an attachment panel hid the panel at
 * oblique angles (tried alpha-blended and additive materials), so the faces stay uncovered.
 */
@Composable
fun InstrumentCluster(onOpenDetails: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberMonitorState()
    val scene = remember { ClusterScene() }
    val scales = SLOTS.map { dialScale(it.resource, state) }
    val needles = SLOTS.mapIndexed { i, slot ->
        rememberNeedleState(scales[i].fraction(needleValue(slot.resource, state) ?: scales[i].min))
    }
    val networkIndex = SLOTS.indexOfFirst { it.resource == Resource.Network }
    val sendNeedle = rememberNeedleState(scales[networkIndex].fraction(state.stats.network.uploadBps?.toFloat() ?: 0f))
    val faceSizes = SLOTS.map { panelSize(2f * it.radius, 2f * it.radius, LengthUnit.Meters) }

    // Drive the 3D needles from the springs, keep spoken values current, and move the key light.
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                SLOTS.indices.forEach { i -> scene.pivots[i]?.pointAt(scales[i].angle(needles[i].position.value)) }
                scene.sendPivot?.pointAt(scales[networkIndex].angle(sendNeedle.position.value))
                if (scene.describedTick != state.tick) {
                    scene.describedTick = state.tick
                    SLOTS.forEachIndexed { i, slot ->
                        scene.faces[i]?.components?.get<AccessibilityComponent>()?.value = summaryFor(slot.resource, state)
                    }
                }
                scene.light?.let { light ->
                    val t = ((now - start) / 1e9 / LIGHT_PERIOD_S * 2 * PI)
                    light.components.get<TransformComponent>()?.setPosition(
                        Vector3(0.5f * cos(t).toFloat(), 0.16f + 0.05f * sin(2 * t).toFloat(), 0.32f),
                    )
                }
            }
        }
    }

    SpatialView(
        modifier = modifier.fillMaxSize(),
        attachments = {
            SLOTS.forEachIndexed { i, slot ->
                AttachmentPanel(slot.faceId, faceSizes[i], AttachmentPanelComponent.Alignment.CENTER) {
                    val twin = if (slot.resource == Resource.Network) sendNeedle else null
                    PrintedDial(slot.resource, slot.finish, scales[i], needles[i], twin, onOpenDetails)
                }
            }
        },
        initial = { content, attachments ->
            // Shared meshes: built once, reused by every gauge of the same finish.
            val body = ClusterGeometry.lathe("gauge-body", Profiles.body)
            val cap = ClusterGeometry.lathe("gauge-cap", Profiles.cap)
            val needleMesh = ClusterGeometry.needle("gauge-needle")
            class Parts(val inner: MeshResource, val top: MeshResource, val outer: MeshResource, val knurl: MeshResource)
            val parts = DialFinish.entries.associateWith { f ->
                Parts(
                    ClusterGeometry.lathe("bezel-inner-${f.name}", Profiles.innerChamfer(f.bezelHeight)),
                    ClusterGeometry.lathe("bezel-top-${f.name}", Profiles.bezelTop(f.bezelHeight, f.bezelOuter)),
                    ClusterGeometry.lathe("bezel-outer-${f.name}", Profiles.outerChamfer(f.bezelHeight, f.bezelOuter)),
                    ClusterGeometry.lathe("bezel-knurl-${f.name}", Profiles.knurl(f.bezelHeight, f.bezelOuter, f.flutes, f.fluteDepth)),
                )
            }

            val mounts = arrayOfNulls<Entity>(SLOTS.size)
            SLOTS.forEachIndexed { i, slot ->
                val f = slot.finish
                val r = slot.radius
                val mount = Entity().placed(Vector3(slot.x, slot.y, slot.z), Quat(Vector3(0f, 1f, 0f), slot.yawRadians))
                content.addEntity(mount)
                mounts[i] = mount

                val housing = Entity().placed(Vector3.ZERO, scale = Vector3(r, r, r))
                val p = parts.getValue(f)
                housing.addChild(ModelEntity(body, f.body.material()))
                housing.addChild(ModelEntity(p.inner, f.chamfer.material()))
                housing.addChild(ModelEntity(p.top, f.bezel.material()))
                housing.addChild(ModelEntity(p.outer, f.chamfer.material()))
                housing.addChild(ModelEntity(p.knurl, f.bezel.material()))
                mount.addChild(housing)

                fun addNeedle(finish: Finish, lift: Float): Entity {
                    val pivot = Entity().placed(Vector3(0f, 0f, (NEEDLE_Z + lift) * r))
                    val reach = NEEDLE_REACH * r
                    pivot.addChild(ModelEntity(needleMesh, finish.material()).placed(Vector3.ZERO, scale = Vector3(reach, reach, reach)))
                    mount.addChild(pivot)
                    return pivot
                }
                scene.pivots[i] = addNeedle(f.needle, lift = 0f)
                if (slot.resource == Resource.Network) scene.sendPivot = addNeedle(Finishes.RedLacquer, lift = 0.03f)
                val c = CAP_RADIUS * r
                mount.addChild(
                    ModelEntity(cap, f.cap.material()).placed(Vector3(0f, 0f, (NEEDLE_Z + 0.045f) * r), scale = Vector3(c, c, c)),
                )
            }

            // Live dial faces at each housing's dial plane. Each is a read-only Spatial accessibility
            // node (label + live value, no actions), declared with literal IDs for the A11Y inventory.
            fun mountFace(i: Int, face: Entity) {
                face.placed(Vector3(0f, 0f, 0.002f * SLOTS[i].radius))
                mounts[i]?.addChild(face)
                scene.faces[i] = face
            }
            attachments.entity(CLUSTER_TEMPERATURE_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "Battery temperature gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_TEMPERATURE_ID) {}
                mountFace(0, face)
            }
            attachments.entity(CLUSTER_NETWORK_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "Network gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_NETWORK_ID) {}
                mountFace(1, face)
            }
            attachments.entity(CLUSTER_CPU_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "CPU gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_CPU_ID) {}
                mountFace(2, face)
            }
            attachments.entity(CLUSTER_GPU_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "GPU gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_GPU_ID) {}
                mountFace(3, face)
            }
            attachments.entity(CLUSTER_DISPLAY_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "Display gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_DISPLAY_ID) {}
                mountFace(4, face)
            }
            attachments.entity(CLUSTER_POWER_ID)?.let { face ->
                face.components.set(AccessibilityComponent(label = "Power gauge", value = "").apply { isAccessibilityElement = true })
                content.setAccessibilityActions(face, CLUSTER_POWER_ID) {}
                mountFace(5, face)
            }

            val light = Entity().placed(Vector3(0.5f, 0.16f, 0.32f))
            light.components.set(PointLightComponent(Color4.WHITE, 2000f, 2.0f))
            content.addEntity(light)
            scene.light = light
        },
    )
}

/** Rotates a needle pivot so its +Y needle points at [canvasDegrees] (clockwise from +X, y down). */
private fun Entity.pointAt(canvasDegrees: Float) {
    val radians = -Math.toRadians((canvasDegrees + 90f).toDouble()).toFloat()
    components.get<TransformComponent>()?.setQuaternion(Quat(Vector3(0f, 0f, 1f), radians))
}

/**
 * One dial plate with everything a real instrument prints on it: scale, numerals, coloured arcs,
 * caption and a small readout, plus the soft shadows of the 3D needle(s), which follow the same
 * springs as the needles.
 */
@Composable
private fun PrintedDial(
    resource: Resource,
    finish: DialFinish,
    scale: DialScale,
    needle: NeedleState,
    twin: NeedleState?,
    onOpenDetails: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    // The whole dial is a button that opens the details window, like the strip it replaces.
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .spatialHoverEffect()
            .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onOpenDetails)
            .clearAndSetSemantics {
                contentDescription = "${resource.title} gauge"
                role = Role.Button
                onClick(label = "Open details") { onOpenDetails(); true }
            },
    ) {
        Image(
            painter = painterResource(finish.plate),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            fun at(deg: Float, f: Float) = polar(c, deg, r * f)
            val unit = r / 100.dp.toPx()
            val ink = finish.ink
            val short = scale.sweepDeg < 180f

            val tickOuter = if (finish.railway) 0.86f else 0.92f
            val majorLen = if (short) 0.16f else 0.12f
            val numberR = tickOuter - majorLen - 0.15f

            if (finish.railway) {
                drawCircle(ink, r * 0.965f, c, style = Stroke(r * 0.008f))
                drawCircle(ink, r * 0.905f, c, style = Stroke(r * 0.008f))
                for (k in 0 until 120) {
                    val a = k * 3f
                    drawLine(ink, at(a, 0.905f), at(a, 0.965f), r * 0.006f)
                }
            }

            scale.bands.forEach { (from, to, color) ->
                val a0 = scale.angle(scale.fraction(from))
                val a1 = scale.angle(scale.fraction(to))
                arc(color, c, r * (tickOuter - 0.03f), a0, a1 - a0, r * (if (short) 0.06f else 0.045f))
            }

            val total = scale.majors * scale.minorsPerMajor
            for (k in 0..total) {
                val major = k % scale.minorsPerMajor == 0
                val half = scale.minorsPerMajor % 2 == 0 && k % scale.minorsPerMajor == scale.minorsPerMajor / 2
                val v = scale.min + (scale.max - scale.min) * k / total
                val a = scale.angle(k.toFloat() / total)
                val len = when { major -> majorLen; half -> majorLen * 0.65f; else -> majorLen * 0.4f }
                val w = r * finish.tickWidth * if (major) 0.02f else 0.008f
                drawLine(ink, at(a, tickOuter), at(a, tickOuter - len), w, cap = StrokeCap.Butt)
                if (major) {
                    printCentered(measurer, scale.label(v), at(a, numberR), ink, (if (short) 24f else 20f) * unit, finish.numeralWeight)
                }
            }

            scale.marker?.let { m ->
                val a = scale.angle(scale.fraction(m))
                val tip = at(a, tickOuter + 0.02f)
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    at(a - 3f, tickOuter + 0.09f).let { lineTo(it.x, it.y) }
                    at(a + 3f, tickOuter + 0.09f).let { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Ink.Red)
            }

            // Centre-zero dial: big − and + at the ends, like a charge/discharge ammeter.
            if (resource == Resource.Power) {
                printCentered(measurer, "−", at(scale.startDeg - 6f, 0.62f), ink, 22f * unit, FontWeight.Bold)
                printCentered(measurer, "+", at(scale.startDeg + scale.sweepDeg + 6f, 0.62f), ink, 22f * unit, FontWeight.Bold)
            }

            // Caption and readout in the open part of the dial.
            val captionY = if (short) 0.30f else 0.36f
            printCentered(measurer, scale.caption, Offset(c.x, c.y + r * captionY), ink.copy(alpha = 0.8f), 12f * unit, FontWeight.Medium)
            printCentered(measurer, scale.readout, Offset(c.x, c.y + r * (captionY + 0.2f)), ink, 16f * unit, FontWeight.SemiBold, tabular = true)
            if (twin != null) {
                printCentered(measurer, "RX", Offset(c.x - r * 0.22f, c.y - r * 0.32f), ink, 12f * unit, FontWeight.Bold)
                printCentered(measurer, "TX", Offset(c.x + r * 0.22f, c.y - r * 0.32f), Ink.Red, 12f * unit, FontWeight.Bold)
            }

            // Soft cast shadows of the needles, offset down-right as if lit from the upper left.
            fun shadow(state: NeedleState) {
                val a = scale.angle(state.position.value)
                drawPath(needleShadow(c + Offset(r * 0.02f, r * 0.035f), a, r * NEEDLE_REACH, r * 0.24f, r * 0.03f), Ink.Shadow)
            }
            shadow(needle)
            twin?.let { shadow(it) }
        }
    }
}

private fun DrawScope.printCentered(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    fontSizePx: Float,
    weight: FontWeight,
    tabular: Boolean = false,
) {
    val style = TextStyle(color = color, fontSize = (fontSizePx / density / fontScale).sp, fontWeight = weight)
    val layout = measurer.measure(text, if (tabular) tabularNumbers(style) else style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

private fun DrawScope.arc(color: Color, center: Offset, radius: Float, start: Float, sweep: Float, width: Float) {
    drawArc(
        color, start, sweep, false,
        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Butt),
    )
}

private fun polar(center: Offset, degrees: Float, distance: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + distance * cos(rad).toFloat(), center.y + distance * sin(rad).toFloat())
}

private fun needleShadow(center: Offset, degrees: Float, length: Float, tail: Float, halfWidth: Float) = Path().apply {
    val tip = polar(center, degrees, length)
    val back = polar(center, degrees + 180f, tail)
    val l = polar(center, degrees + 90f, halfWidth)
    val rr = polar(center, degrees - 90f, halfWidth)
    moveTo(tip.x, tip.y)
    lineTo(l.x, l.y)
    polar(back, degrees + 90f, halfWidth * 1.5f).let { lineTo(it.x, it.y) }
    polar(back, degrees - 90f, halfWidth * 1.5f).let { lineTo(it.x, it.y) }
    lineTo(rr.x, rr.y)
    close()
}

/** Sets this entity's parent-local transform and returns it. */
private fun <T : Entity> T.placed(
    position: Vector3,
    rotation: Quat = Quat.identity(),
    scale: Vector3 = Vector3(1f, 1f, 1f),
): T = apply { components.set(TransformComponent(position, rotation, scale)) }
