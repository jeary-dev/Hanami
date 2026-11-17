package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.Vec3

/** One cubic bezier segment: start, two control points, end. */
data class BezierSegment(val p0: Vec3, val c1: Vec3, val c2: Vec3, val p1: Vec3) {

    fun at(t: Double): Vec3 {
        val u = 1.0 - t
        val a = u * u * u
        val b = 3.0 * u * u * t
        val c = 3.0 * u * t * t
        val d = t * t * t
        return Vec3(
            a * p0.x + b * c1.x + c * c2.x + d * p1.x,
            a * p0.y + b * c1.y + c * c2.y + d * p1.y,
            a * p0.z + b * c1.z + c * c2.z + d * p1.z,
        )
    }

    /** A dense polyline approximation used for arc-length resampling. */
    fun flatten(steps: Int = FLATTEN_STEPS): List<Vec3> = List(steps + 1) { at(it.toDouble() / steps) }

    private companion object {
        const val FLATTEN_STEPS = 64
    }
}

/**
 * A chain of cubic bezier segments, sampled evenly by arc length. Each segment is its own stroke,
 * so wing outlines and feather lines can be described as several segments in one shape.
 *
 * Control points are authored in 2D (u, v) on [plane], or in full 3D when `z` values are given.
 */
data class BezierPath(
    val segments: List<BezierSegment>,
    val points: Int,
) : Shape {
    override fun sample(): PointCloud =
        PointCloud.of(Resample.uniform(segments.map { it.flatten() }, points))
}
