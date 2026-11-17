package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.Mat3
import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Several shapes drawn together. */
data class Union(val shapes: List<Shape>) : Shape {
    override fun sample(): PointCloud = PointCloud.concat(shapes.map { it.sample() })
}

enum class Axis { X, Y, Z }

/** The shape plus its reflection across the plane perpendicular to [axis]. */
data class Mirror(val axis: Axis, val shape: Shape) : Shape {
    override fun sample(): PointCloud {
        val original = shape.sample()
        val mirror = when (axis) {
            Axis.X -> Mat3.MIRROR_X
            Axis.Y -> Mat3.rotationZ(PI) * Mat3.MIRROR_X
            Axis.Z -> Mat3.rotationY(PI) * Mat3.MIRROR_X
        }
        return original + original.transformed(mirror)
    }
}

/** Scales, rotates (yaw/pitch/roll in degrees) then translates a child shape. */
data class Transform(
    val shape: Shape,
    val translate: Vec3 = Vec3.ZERO,
    val rotate: Vec3 = Vec3.ZERO,
    val scale: Double = 1.0,
) : Shape {
    override fun sample(): PointCloud {
        val m = Mat3.euler(rotate.y, rotate.x, rotate.z) * Mat3.scale(scale)
        return shape.sample().transformed(m, translate)
    }
}

/**
 * [count] copies arranged evenly around a circle on the XZ plane, each turned to face outward.
 * When several [shapes] are given they alternate around the ring (e.g. 花 · 風 · 月).
 */
data class Radial(
    val radius: Double,
    val count: Int,
    val shapes: List<Shape>,
) : Shape {
    override fun sample(): PointCloud {
        val clouds = shapes.map { it.sample() }
        return PointCloud.concat(List(count) { i ->
            val angle = 2.0 * PI * i / count
            val cloud = clouds[i % clouds.size]
            cloud.transformed(Mat3.rotationY(angle), Vec3(sin(angle) * radius, 0.0, cos(angle) * radius))
        })
    }
}

/** Uses [primary] when it can be sampled, otherwise [fallback] (e.g. a glyph without a font). */
data class WithFallback(val primary: Shape, val fallback: Shape) : Shape {
    override fun sample(): PointCloud = try {
        primary.sample().takeUnless { it.isEmpty() } ?: fallback.sample()
    } catch (_: GlyphUnavailableException) {
        fallback.sample()
    }
}
