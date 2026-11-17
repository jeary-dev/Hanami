package dev.jeary.hanami.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.PI
import kotlin.math.abs

private const val EPS = 1e-9

private fun assertVec(expected: Vec3, actual: Vec3, eps: Double = EPS) {
    assertTrue(
        abs(expected.x - actual.x) < eps && abs(expected.y - actual.y) < eps && abs(expected.z - actual.z) < eps,
        "expected $expected but was $actual",
    )
}

class MatrixTest {

    @Nested
    inner class Rotations {
        @Test
        fun `rotationY by 90 degrees turns +x into -z`() {
            assertVec(Vec3(0.0, 0.0, -1.0), Mat3.rotationY(PI / 2).transform(Vec3(1.0, 0.0, 0.0)))
        }

        @Test
        fun `rotationX by 90 degrees turns +y into +z`() {
            assertVec(Vec3(0.0, 0.0, 1.0), Mat3.rotationX(PI / 2).transform(Vec3.UP))
        }

        @ParameterizedTest
        @ValueSource(doubles = [0.0, 0.3, 1.0, 2.5, -4.0])
        fun `rotations preserve length and are proper (det = 1)`(angle: Double) {
            for (m in listOf(Mat3.rotationX(angle), Mat3.rotationY(angle), Mat3.rotationZ(angle), Mat3.euler(angle * 30, angle * 10, angle * 5))) {
                assertEquals(1.0, m.determinant, 1e-9)
                assertEquals(Vec3(1.0, 2.0, 3.0).length, m.transform(Vec3(1.0, 2.0, 3.0)).length, 1e-9)
            }
        }

        @Test
        fun `matrix product composes right to left`() {
            val a = Mat3.rotationY(0.7)
            val b = Mat3.rotationX(0.3)
            val v = Vec3(0.4, -1.2, 2.0)
            assertVec(a.transform(b.transform(v)), (a * b).transform(v))
        }

        @Test
        fun `mirror flips x and has det -1`() {
            assertVec(Vec3(-1.0, 2.0, 3.0), Mat3.MIRROR_X.transform(Vec3(1.0, 2.0, 3.0)))
            assertEquals(-1.0, Mat3.MIRROR_X.determinant, EPS)
        }
    }

    @Nested
    inner class MinecraftFrames {
        @Test
        fun `yaw 0 faces south (+z) with right pointing west (-x)`() {
            val m = Mat3.minecraftYaw(0.0)
            assertVec(Vec3(0.0, 0.0, 1.0), m.transform(Vec3(0.0, 0.0, 1.0)))
            assertVec(Vec3(-1.0, 0.0, 0.0), m.transform(Vec3(1.0, 0.0, 0.0)))
        }

        @Test
        fun `yaw 90 faces west (-x)`() {
            assertVec(Vec3(-1.0, 0.0, 0.0), Mat3.minecraftYaw(90.0).transform(Vec3(0.0, 0.0, 1.0)))
        }

        @Test
        fun `lookAlong maps local +z onto the direction of travel`() {
            val dir = Vec3(1.0, 0.5, -2.0).normalized()
            val m = Mat3.lookAlong(dir)
            assertVec(dir, m.transform(Vec3(0.0, 0.0, 1.0)))
            assertEquals(1.0, abs(m.determinant), 1e-9)
        }

        @Test
        fun `lookAlong is stable when flying straight up`() {
            val m = Mat3.lookAlong(Vec3.UP)
            assertVec(Vec3.UP, m.transform(Vec3(0.0, 0.0, 1.0)))
        }
    }

    @Test
    fun `transformInto applies rotation and translation to packed points`() {
        val src = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val dst = DoubleArray(6)
        Mat3.rotationZ(PI / 2).transformInto(src, 0, 2, dst, 0, 10.0, 20.0, 30.0)
        assertVec(Vec3(10.0, 21.0, 30.0), Vec3(dst[0], dst[1], dst[2]))
        assertVec(Vec3(9.0, 20.0, 30.0), Vec3(dst[3], dst[4], dst[5]))
    }
}

class EasingTest {
    @Test
    fun `every curve starts at 0 and ends at 1`() {
        for (easing in Easing.ALL) {
            assertEquals(0.0, easing.ease(0.0), 1e-9, "${easing.id}(0)")
            assertEquals(1.0, easing.ease(1.0), 1e-9, "${easing.id}(1)")
        }
    }

    @Test
    fun `waves stay within -1 and 1`() {
        for (easing in Easing.ALL) {
            for (i in 0..200) {
                val w = easing.wave(i / 37.0)
                assertTrue(w in -1.0 - 1e-9..1.0 + 1e-9, "${easing.id}.wave = $w")
            }
        }
    }

    @Test
    fun `sine wave is a cosine`() {
        for (i in 0..20) {
            val p = i / 20.0
            assertEquals(-kotlin.math.cos(2 * PI * p), Easing.Sine.wave(p), 1e-9)
        }
    }

    @Test
    fun `curves are looked up by id, tolerant of dashes and case`() {
        assertEquals(Easing.EaseInOut, Easing.byId("Ease-In-Out"))
        assertEquals(null, Easing.byId("wobbly"))
    }
}
