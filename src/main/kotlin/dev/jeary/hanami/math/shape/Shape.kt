package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.Vec3

/**
 * A parametric shape. Shapes are plain data (so they can be compared and used as cache keys) and
 * know how to [sample] themselves into a [PointCloud] once. They are never sampled per tick.
 *
 * Local space convention: +x = right, +y = up, +z = forward (towards the viewer for flat shapes).
 */
sealed interface Shape {
    fun sample(): PointCloud
}

/** Orientation of a flat (2D) shape before any rotation is applied. */
enum class Plane {
    /** Horizontal, like a ring on the floor. */
    XZ,

    /** Vertical, facing forward (+z). Text and wings are authored here. */
    XY,

    /** Vertical, facing sideways (+x). */
    YZ;

    /** Maps 2D shape coordinates (u, v) and an optional depth onto this plane. */
    fun place(u: Double, v: Double, depth: Double = 0.0): Vec3 = when (this) {
        XZ -> Vec3(u, depth, v)
        XY -> Vec3(u, v, depth)
        YZ -> Vec3(depth, v, u)
    }

    companion object {
        fun byId(id: String): Plane? = entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
    }
}

/** A single point at the origin. Combine with `emit.spread` for soft trails. */
data object PointShape : Shape {
    override fun sample() = PointCloud.of(listOf(Vec3.ZERO))
}
