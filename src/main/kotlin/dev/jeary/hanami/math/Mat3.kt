package dev.jeary.hanami.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable row-major 3x3 matrix used for rotations, mirroring and scaling.
 *
 * The hot path is [transformInto], which applies the matrix plus a translation to a packed
 * xyz [DoubleArray] without allocating a single object per point.
 */
class Mat3 private constructor(
    @JvmField val m00: Double, @JvmField val m01: Double, @JvmField val m02: Double,
    @JvmField val m10: Double, @JvmField val m11: Double, @JvmField val m12: Double,
    @JvmField val m20: Double, @JvmField val m21: Double, @JvmField val m22: Double,
) {

    operator fun times(o: Mat3) = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    operator fun times(s: Double) = Mat3(
        m00 * s, m01 * s, m02 * s,
        m10 * s, m11 * s, m12 * s,
        m20 * s, m21 * s, m22 * s,
    )

    fun transform(v: Vec3) = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    /**
     * Writes `this * p + t` for [count] points of [src] (starting at point [srcOffset]) into [dst]
     * starting at point [dstOffset]. Both arrays are packed `x0 y0 z0 x1 y1 z1 ...`.
     */
    fun transformInto(
        src: DoubleArray,
        srcOffset: Int,
        count: Int,
        dst: DoubleArray,
        dstOffset: Int,
        tx: Double,
        ty: Double,
        tz: Double,
    ) {
        var s = srcOffset * 3
        var d = dstOffset * 3
        repeat(count) {
            val x = src[s]
            val y = src[s + 1]
            val z = src[s + 2]
            dst[d] = m00 * x + m01 * y + m02 * z + tx
            dst[d + 1] = m10 * x + m11 * y + m12 * z + ty
            dst[d + 2] = m20 * x + m21 * y + m22 * z + tz
            s += 3
            d += 3
        }
    }

    val determinant: Double
        get() = m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

    fun approxEquals(o: Mat3, eps: Double = 1e-9): Boolean {
        val a = doubleArrayOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)
        val b = doubleArrayOf(o.m00, o.m01, o.m02, o.m10, o.m11, o.m12, o.m20, o.m21, o.m22)
        return a.indices.all { kotlin.math.abs(a[it] - b[it]) <= eps }
    }

    override fun toString() = "Mat3[[$m00, $m01, $m02], [$m10, $m11, $m12], [$m20, $m21, $m22]]"

    companion object {
        val IDENTITY = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Flips the local X axis — used to draw the left wing from the right wing's points. */
        val MIRROR_X = Mat3(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun scale(s: Double) = Mat3(s, 0.0, 0.0, 0.0, s, 0.0, 0.0, 0.0, s)

        fun rotationX(rad: Double): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
        }

        fun rotationY(rad: Double): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
        }

        fun rotationZ(rad: Double): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }

        /** Rotation applying yaw (Y), then pitch (X), then roll (Z), all in degrees. */
        fun euler(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Mat3 {
            var m = IDENTITY
            if (yawDeg != 0.0) m = m * rotationY(Math.toRadians(yawDeg))
            if (pitchDeg != 0.0) m = m * rotationX(Math.toRadians(pitchDeg))
            if (rollDeg != 0.0) m = m * rotationZ(Math.toRadians(rollDeg))
            return m
        }

        /** A matrix whose columns are the given basis vectors (local x, y, z → world). */
        fun fromColumns(x: Vec3, y: Vec3, z: Vec3) = Mat3(
            x.x, y.x, z.x,
            x.y, y.y, z.y,
            x.z, y.z, z.z,
        )

        /**
         * The local frame of an entity with the given Minecraft yaw (degrees):
         * local +x = the entity's right, +y = up, +z = where it is facing.
         */
        fun minecraftYaw(yawDeg: Double): Mat3 {
            val rad = Math.toRadians(yawDeg)
            val s = sin(rad)
            val c = cos(rad)
            return fromColumns(
                Vec3(-c, 0.0, -s),
                Vec3.UP,
                Vec3(-s, 0.0, c),
            )
        }

        /**
         * A frame whose local +z points along [forward]; used to orient effects along a
         * projectile's flight path. Falls back to a stable frame when flying straight up/down.
         */
        fun lookAlong(forward: Vec3): Mat3 {
            val f = forward.normalized()
            if (f == Vec3.ZERO) return IDENTITY
            val reference = if (kotlin.math.abs(f.y) > 0.999) Vec3(1.0, 0.0, 0.0) else Vec3.UP
            val right = (reference cross f).normalized()
            val up = (f cross right).normalized()
            return fromColumns(right, up, f)
        }
    }
}
