package com.example.sysmonitor.pbb1c5130.content

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Code-built instrument parts. Generated housing models came out with blotchy, near-mirror
 * materials, so housings, bezels, caps and needles are lathed and extruded here instead: clean
 * geometry with materials set directly, identical on every gauge. All meshes are unit-sized
 * (dial radius = 1, +Z toward the viewer, dial plane at z = 0) and scaled per gauge.
 */

/** One revolved surface: a (radius, z) profile, ordered so the visible side lies on its left. */
internal class LatheProfile(
    val points: List<Pair<Float, Float>>,
    /** Smooth normals along the profile (domes); otherwise every profile segment is a crisp facet. */
    val smooth: Boolean = false,
    /** Straight knurling: number of flutes around the circle, and their depth as a fraction of the radius. */
    val flutes: Int = 0,
    val fluteDepth: Float = 0f,
)

internal object ClusterGeometry {
    private const val SEGMENTS = 160

    /** Revolves [profile] around +Z. Normals point to the profile's left: inner walls inward, outer walls out. */
    fun lathe(name: String, profile: LatheProfile): MeshResource {
        val segments = if (profile.flutes > 0) profile.flutes * 8 else SEGMENTS
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val pts = profile.points

        // Each ring: (radius, z, profile-normal r, profile-normal z).
        val rings: List<List<FloatArray>> = if (profile.smooth) {
            listOf(pts.indices.map { j ->
                val a = pts[maxOf(j - 1, 0)]
                val b = pts[minOf(j + 1, pts.lastIndex)]
                val n = profileNormal(b.first - a.first, b.second - a.second)
                floatArrayOf(pts[j].first, pts[j].second, n[0], n[1])
            })
        } else {
            (0 until pts.lastIndex).map { j ->
                val n = profileNormal(pts[j + 1].first - pts[j].first, pts[j + 1].second - pts[j].second)
                listOf(
                    floatArrayOf(pts[j].first, pts[j].second, n[0], n[1]),
                    floatArrayOf(pts[j + 1].first, pts[j + 1].second, n[0], n[1]),
                )
            }
        }

        for (strip in rings) {
            val base = positions.size
            for (i in 0..segments) {
                val t = (2 * PI * i / segments).toFloat()
                val c = cos(t)
                val s = sin(t)
                // Flute modulation only on walls (where the profile normal is mostly radial).
                for (row in strip) {
                    val (r0, z, nr, nz) = row.let { listOf(it[0], it[1], it[2], it[3]) }
                    var r = r0
                    var tangential = 0f
                    if (profile.flutes > 0 && abs(nr) > 0.7f) {
                        val phase = profile.flutes * t
                        r = r0 * (1f + profile.fluteDepth * cos(phase))
                        // d r / d theta, relative to r: tilts the normal around the circle across each flute.
                        tangential = -profile.fluteDepth * profile.flutes * sin(phase) * nr
                    }
                    positions += Vector3(r * c, r * s, z)
                    normals += normalize(nr * c + tangential * s, nr * s - tangential * c, nz)
                }
            }
            val rows = strip.size
            for (i in 0 until segments) for (k in 0 until rows - 1) {
                val a = base + i * rows + k
                val b = base + (i + 1) * rows + k
                indices += listOf(a, b, b + 1, a, b + 1, a + 1)
            }
        }
        return build(name, positions, normals, indices)
    }

    /**
     * A bevelled watch-style needle, unit length from pivot to tip along +Y, lying on z = 0:
     * flat underside, raised centre spine so each side catches light differently, and a short
     * wider counterweight tail.
     */
    fun needle(name: String): MeshResource {
        // (y, half-width) from the tail end to the tip.
        val outline = listOf(-0.28f to 0.050f, -0.16f to 0.040f, -0.04f to 0.030f, 0.40f to 0.019f, 0.80f to 0.010f, 1.0f to 0.0015f)
        val edgeZ = 0.010f
        val spineZ = 0.024f
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val centre = Vector3(0f, 0.36f, 0.01f)

        fun tri(a: Vector3, b: Vector3, c: Vector3) {
            val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
            val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
            var n = normalize(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            val fx = (a.x + b.x + c.x) / 3f - centre.x
            val fy = (a.y + b.y + c.y) / 3f - centre.y
            val fz = (a.z + b.z + c.z) / 3f - centre.z
            if (n.x * fx + n.y * fy + n.z * fz < 0f) n = Vector3(-n.x, -n.y, -n.z)
            val start = positions.size
            positions += listOf(a, b, c)
            normals += listOf(n, n, n)
            indices += listOf(start, start + 1, start + 2)
        }

        for (k in 0 until outline.lastIndex) {
            val (y0, w0) = outline[k]
            val (y1, w1) = outline[k + 1]
            val l0 = Vector3(-w0, y0, edgeZ); val l1 = Vector3(-w1, y1, edgeZ)
            val r0 = Vector3(w0, y0, edgeZ); val r1 = Vector3(w1, y1, edgeZ)
            val s0 = Vector3(0f, y0, spineZ); val s1 = Vector3(0f, y1, spineZ)
            val bl0 = Vector3(-w0, y0, 0f); val bl1 = Vector3(-w1, y1, 0f)
            val br0 = Vector3(w0, y0, 0f); val br1 = Vector3(w1, y1, 0f)
            tri(l0, l1, s1); tri(l0, s1, s0) // left bevel
            tri(s0, s1, r1); tri(s0, r1, r0) // right bevel
            tri(bl0, bl1, l1); tri(bl0, l1, l0) // left wall
            tri(br0, r1, br1); tri(br0, r0, r1) // right wall
            tri(bl0, br0, br1); tri(bl0, br1, bl1) // underside
        }
        val (yt, wt) = outline.first()
        tri(Vector3(-wt, yt, 0f), Vector3(-wt, yt, edgeZ), Vector3(0f, yt, spineZ))
        tri(Vector3(-wt, yt, 0f), Vector3(0f, yt, spineZ), Vector3(wt, yt, edgeZ))
        tri(Vector3(-wt, yt, 0f), Vector3(wt, yt, edgeZ), Vector3(wt, yt, 0f))
        return build(name, positions, normals, indices)
    }

    private fun profileNormal(dr: Float, dz: Float): FloatArray {
        val len = max(sqrt(dr * dr + dz * dz), 1e-6f)
        return floatArrayOf(-dz / len, dr / len)
    }

    private fun normalize(x: Float, y: Float, z: Float): Vector3 {
        val len = max(sqrt(x * x + y * y + z * z), 1e-6f)
        return Vector3(x / len, y / len, z / len)
    }

    private fun build(name: String, positions: List<Vector3>, normals: List<Vector3>, indices: List<Int>): MeshResource {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (p in positions) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
        }
        val bounds = BoundingBox(
            Vector3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f),
            Vector3((maxX - minX) / 2f, (maxY - minY) / 2f, (maxZ - minZ) / 2f),
        )
        return MeshResource.createWithMeshModel(MeshModel(positions, indices, normals), bounds, name)
    }
}

/** A physical surface finish; colours are material albedo, not UI colours. */
internal class Finish(val base: Color4, val metallic: Float, val roughness: Float, val emissive: Color4? = null) {
    fun material(): PhysicallyBasedMaterial = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
        setBaseColor(base)
        setMetallic(metallic)
        setRoughness(roughness)
        emissive?.let { setEmissiveColor(it) }
        // Lathed and extruded meshes are open shells; draw both sides.
        setCullingMode(MaterialCullingMode.NONE)
    }
}

internal object Finishes {
    val TitaniumSatin = Finish(Color4(0.60f, 0.61f, 0.63f, 1f), metallic = 1f, roughness = 0.36f)
    val TitaniumPolished = Finish(Color4(0.78f, 0.79f, 0.81f, 1f), metallic = 1f, roughness = 0.08f)
    val AnodisedBlack = Finish(Color4(0.03f, 0.031f, 0.034f, 1f), metallic = 0.15f, roughness = 0.6f)
    val DiamondCutAluminium = Finish(Color4(0.86f, 0.87f, 0.88f, 1f), metallic = 1f, roughness = 0.12f)
    val Chrome = Finish(Color4(0.95f, 0.95f, 0.96f, 1f), metallic = 1f, roughness = 0.05f)
    val DarkChrome = Finish(Color4(0.32f, 0.32f, 0.34f, 1f), metallic = 1f, roughness = 0.22f)
    val PolishedSteel = Finish(Color4(0.82f, 0.83f, 0.85f, 1f), metallic = 1f, roughness = 0.1f)
    val BluedSteel = Finish(Color4(0.10f, 0.20f, 0.52f, 1f), metallic = 0.9f, roughness = 0.22f)
    val LumeWhite = Finish(Color4(0.92f, 0.91f, 0.86f, 1f), metallic = 0f, roughness = 0.45f, emissive = Color4(0.10f, 0.10f, 0.09f, 1f))
    val GlossBlack = Finish(Color4(0.03f, 0.03f, 0.03f, 1f), metallic = 0.2f, roughness = 0.15f)
    val RedLacquer = Finish(Color4(0.70f, 0.05f, 0.05f, 1f), metallic = 0.1f, roughness = 0.18f, emissive = Color4(0.12f, 0f, 0f, 1f))
}

/** Unit profiles (dial radius 1). */
internal object Profiles {
    /** Deep cup behind the dial; open at the front where the bezel and dial sit. */
    val body = LatheProfile(listOf(1.08f to -0.06f, 1.08f to -0.48f, 1.00f to -0.56f, 0f to -0.56f))

    /** Inner wall and inner chamfer, from the dial edge up to the bezel top. */
    fun innerChamfer(height: Float) = LatheProfile(listOf(0.985f to 0f, 0.985f to height * 0.4f, 1.03f to height))

    /** Flat top of the bezel ring. */
    fun bezelTop(height: Float, outer: Float) = LatheProfile(listOf(1.03f to height, outer - 0.04f to height))

    /** Outer chamfer from the top down to the knurled band. */
    fun outerChamfer(height: Float, outer: Float) =
        LatheProfile(listOf(outer - 0.04f to height, outer to height - 0.04f))

    /** Knurled outer band down to the body. */
    fun knurl(height: Float, outer: Float, flutes: Int, depth: Float) =
        LatheProfile(listOf(outer to height - 0.04f, outer to -0.05f, 1.08f to -0.07f), flutes = flutes, fluteDepth = depth)

    /** Domed centre cap, radius 1, height 0.7. */
    val cap = LatheProfile(
        (0..10).map { k ->
            val a = (PI / 2 * k / 10).toFloat()
            sin(a) to (0.35f + 0.35f * cos(a))
        } + listOf(1f to 0f),
        smooth = true,
    )
}
