package dev.jeary.hanami.core

import dev.jeary.hanami.core.render.Cadence
import dev.jeary.hanami.core.tick.BodyYawEstimator
import dev.jeary.hanami.core.tick.ParticleBudget
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

class BudgetAndCadenceTest {

    private fun points(n: Int) = DoubleArray(n * 3) { it.toDouble() }

    @Test
    fun `thinning keeps the requested fraction of points`() {
        assertEquals(25, ParticleBudget.thin(points(100), 0.25, 0).size / 3)
    }

    @Test
    fun `fractional points are kept stochastically, so tiny commands average out to the budget`() {
        // 3 points at 10% = 0.3 points per frame on average
        val kept = (0L until 10_000L).sumOf { ParticleBudget.thin(points(3), 0.1, it, salt = 7).size / 3 }
        assertEquals(3_000.0, kept.toDouble(), 150.0)
    }

    @Test
    fun `unit hash is uniform enough`() {
        val samples = (0L until 20_000L).map { ParticleBudget.unitHash(it, 42) }
        assertTrue(samples.all { it >= 0.0 && it < 1.0 })
        assertEquals(0.5, samples.average(), 0.01)
    }

    @Test
    fun `thinning at full budget returns the same array`() {
        val coords = points(10)
        assertSame(coords, ParticleBudget.thin(coords, 1.0, 0))
    }

    @Test
    fun `thinned points are whole xyz triples from the original`() {
        val thinned = ParticleBudget.thin(points(50), 0.3, 3)
        for (i in 0 until thinned.size / 3) {
            val x = thinned[i * 3]
            assertEquals(0.0, x % 3.0, 1e-9)
            assertArrayEquals(doubleArrayOf(x, x + 1, x + 2), thinned.copyOfRange(i * 3, i * 3 + 3))
        }
    }

    @Test
    fun `global factor scales demand down to the budget`() {
        assertEquals(1.0, ParticleBudget.globalFactor(500, 1000))
        assertEquals(0.5, ParticleBudget.globalFactor(2000, 1000))
    }

    @Test
    fun `cadence fires once per interval, staggered by salt`() {
        val firings = (0L until 40L).count { Cadence.fires(it, 4, salt = 0, step = 1) }
        assertEquals(10, firings)
        val a = (0L until 4L).first { Cadence.fires(it, 4, salt = 0, step = 1) }
        val b = (0L until 4L).first { Cadence.fires(it, 4, salt = 1, step = 1) }
        assertTrue(a != b, "different players should fire on different ticks")
    }

    @Test
    fun `cadence with interval 1 always fires`() {
        assertTrue((0L until 10L).all { Cadence.fires(it, 1, 123, 1) })
    }

    @Test
    fun `body yaw follows the direction of travel and never lags the head by more than 50 degrees`() {
        val estimator = BodyYawEstimator()
        val id = UUID.randomUUID()
        var body = 0f
        // walk east (-x is west in Minecraft; +x east = yaw -90) while looking east
        repeat(40) { body = estimator.update(id, -90f, 0.2, 0.0) }
        assertEquals(-90f, body, 1f)
        // stand still and turn the head 120° — the body is dragged along to within 50°
        repeat(5) { body = estimator.update(id, 30f, 0.0, 0.0) }
        assertTrue(abs(BodyYawEstimator.wrap(30f - body)) <= 50f + 1e-3)
    }

    @Test
    fun `angle wrapping lands in -180 to 180`() {
        assertEquals(-170f, BodyYawEstimator.wrap(190f), 1e-4f)
        assertEquals(170f, BodyYawEstimator.wrap(-190f), 1e-4f)
        assertEquals(0f, BodyYawEstimator.wrap(720f), 1e-4f)
    }
}
