package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = PI * 2.0

/** A circle (or arc) of [points] evenly spaced points. */
data class Circle(
    val radius: Double,
    val points: Int,
    val plane: Plane = Plane.XZ,
    val arcDegrees: Double = 360.0,
) : Shape {
    override fun sample(): PointCloud {
        val arc = Math.toRadians(arcDegrees)
        val closed = arcDegrees >= 360.0
        // A closed loop must not repeat its first point; an open arc includes both ends.
        val divisor = if (closed || points < 2) points.toDouble() else (points - 1).toDouble()
        return PointCloud.of(List(points) { i ->
            val a = arc * i / divisor
            plane.place(radius * sin(a), radius * cos(a))
        })
    }
}

/** An Archimedean spiral, optionally rising along the plane's normal. */
data class Spiral(
    val turns: Double,
    val radiusStart: Double,
    val radiusEnd: Double,
    val height: Double,
    val points: Int,
    val plane: Plane = Plane.XZ,
) : Shape {
    override fun sample(): PointCloud = PointCloud.of(List(points) { i ->
        val t = if (points == 1) 0.0 else i.toDouble() / (points - 1)
        val a = TAU * turns * t
        val r = radiusStart + (radiusEnd - radiusStart) * t
        plane.place(r * sin(a), r * cos(a), height * t)
    })
}

/** One or more interleaved helix strands rising along +y. */
data class Helix(
    val radius: Double,
    val height: Double,
    val turns: Double,
    val strands: Int,
    val points: Int,
) : Shape {
    override fun sample(): PointCloud = PointCloud.of(buildList {
        for (s in 0 until strands) {
            val phase = TAU * s / strands
            for (i in 0 until points) {
                val t = if (points == 1) 0.0 else i.toDouble() / (points - 1)
                val a = TAU * turns * t + phase
                add(Vec3(radius * sin(a), height * t, radius * cos(a)))
            }
        }
    })
}

/**
 * A 3D Lissajous figure: `(Ax·sin(a·t + δ), Ay·sin(b·t), Az·sin(c·t))`.
 * With `c = 0` the curve is flat and its Y swing drifts gently up and down — ideal for orbits.
 */
data class Lissajous(
    val a: Double,
    val b: Double,
    val c: Double,
    val deltaDegrees: Double,
    val amplitude: Vec3,
    val points: Int,
) : Shape {
    override fun sample(): PointCloud {
        val delta = Math.toRadians(deltaDegrees)
        return PointCloud.of(List(points) { i ->
            val t = TAU * i / points
            Vec3(
                amplitude.x * sin(a * t + delta),
                amplitude.y * sin(b * t),
                // when c == 0 the z axis mirrors x as a cosine, giving a closed orbit around the origin
                if (c == 0.0) amplitude.z * cos(a * t + delta) else amplitude.z * sin(c * t),
            )
        })
    }
}

/**
 * Points on (or inside) a sphere, distributed with the golden-angle Fibonacci lattice so the
 * spacing is even without any randomness — the same input always yields the same cloud.
 */
data class Sphere(
    val radius: Double,
    val points: Int,
    val shell: Boolean = true,
) : Shape {
    override fun sample(): PointCloud {
        val golden = PI * (3.0 - sqrt(5.0))
        return PointCloud.of(List(points) { i ->
            val y = 1.0 - (i + 0.5) / points * 2.0
            val ring = sqrt(1.0 - y * y)
            val theta = golden * i
            // Volume fill: push points outward by the cube root so density is uniform.
            val r = if (shell) radius else radius * Math.cbrt((i + 0.5) / points)
            Vec3(cos(theta) * ring * r, y * r, sin(theta) * ring * r)
        })
    }
}

/** A straight line segment with evenly spaced points (both ends included). */
data class Line(val from: Vec3, val to: Vec3, val points: Int) : Shape {
    override fun sample(): PointCloud = PointCloud.of(List(points) { i ->
        from.lerp(to, if (points == 1) 0.5 else i.toDouble() / (points - 1))
    })
}

/** A path through [vertices], resampled so the points are evenly spaced along its length. */
data class Polyline(val vertices: List<Vec3>, val points: Int) : Shape {
    override fun sample(): PointCloud = PointCloud.of(Resample.uniform(listOf(vertices), points))
}
