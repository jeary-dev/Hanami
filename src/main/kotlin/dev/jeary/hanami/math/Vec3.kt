package dev.jeary.hanami.math

import kotlin.math.sqrt

/**
 * A small immutable 3D vector. Used for configuration values and shape authoring;
 * the per-tick hot path works on packed [DoubleArray]s instead (see [PointCloud]).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val lengthSquared: Double get() = x * x + y * y + z * z
    val length: Double get() = sqrt(lengthSquared)

    fun normalized(): Vec3 {
        val len = length
        return if (len < EPSILON) ZERO else Vec3(x / len, y / len, z / len)
    }

    fun lerp(to: Vec3, t: Double) = Vec3(
        x + (to.x - x) * t,
        y + (to.y - y) * t,
        z + (to.z - z) * t,
    )

    fun distanceTo(o: Vec3): Double = (this - o).length

    companion object {
        const val EPSILON = 1.0e-9
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}
