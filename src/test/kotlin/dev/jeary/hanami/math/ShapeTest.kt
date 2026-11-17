package dev.jeary.hanami.math

import dev.jeary.hanami.math.shape.Axis
import dev.jeary.hanami.math.shape.BezierPath
import dev.jeary.hanami.math.shape.BezierSegment
import dev.jeary.hanami.math.shape.Circle
import dev.jeary.hanami.math.shape.Glyph
import dev.jeary.hanami.math.shape.GlyphUnavailableException
import dev.jeary.hanami.math.shape.Helix
import dev.jeary.hanami.math.shape.Line
import dev.jeary.hanami.math.shape.Lissajous
import dev.jeary.hanami.math.shape.Mirror
import dev.jeary.hanami.math.shape.Plane
import dev.jeary.hanami.math.shape.PointShape
import dev.jeary.hanami.math.shape.Radial
import dev.jeary.hanami.math.shape.Resample
import dev.jeary.hanami.math.shape.ShapeCache
import dev.jeary.hanami.math.shape.Sphere
import dev.jeary.hanami.math.shape.Spiral
import dev.jeary.hanami.math.shape.WithFallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ShapeTest {

    @Test
    fun `circle has the requested count, all on the radius, and no duplicated seam`() {
        val cloud = Circle(radius = 1.5, points = 24, plane = Plane.XZ).sample()
        assertEquals(24, cloud.size)
        cloud.toList().forEach {
            assertEquals(1.5, kotlin.math.hypot(it.x, it.z), 1e-9)
            assertEquals(0.0, it.y, 1e-12)
        }
        assertTrue(cloud[0].distanceTo(cloud[23]) > 0.1, "first and last points must differ on a closed loop")
    }

    @Test
    fun `an open arc includes both ends`() {
        val cloud = Circle(radius = 1.0, points = 5, plane = Plane.XY, arcDegrees = 180.0).sample()
        assertEquals(0.0, cloud[0].x, 1e-9)
        assertEquals(1.0, cloud[0].y, 1e-9)
        assertEquals(-1.0, cloud[4].y, 1e-9)
    }

    @Test
    fun `plane xy puts a circle upright`() {
        Circle(1.0, 12, Plane.XY).sample().toList().forEach { assertEquals(0.0, it.z, 1e-12) }
    }

    @Test
    fun `helix strands interleave and climb to the full height`() {
        val helix = Helix(radius = 0.5, height = 2.0, turns = 1.0, strands = 3, points = 10).sample()
        assertEquals(30, helix.size)
        val (min, max) = helix.bounds()
        assertEquals(0.0, min.y, 1e-9)
        assertEquals(2.0, max.y, 1e-9)
    }

    @Test
    fun `spiral grows from inner to outer radius`() {
        val spiral = Spiral(turns = 2.0, radiusStart = 0.1, radiusEnd = 1.0, height = 0.0, points = 20).sample()
        assertEquals(0.1, spiral[0].length, 1e-9)
        assertEquals(1.0, spiral[19].length, 1e-9)
    }

    @Test
    fun `lissajous stays inside its amplitude box`() {
        val amp = Vec3(1.0, 0.25, 0.8)
        Lissajous(1.0, 2.0, 0.0, 90.0, amp, 200).sample().toList().forEach {
            assertTrue(abs(it.x) <= amp.x + 1e-9 && abs(it.y) <= amp.y + 1e-9 && abs(it.z) <= amp.z + 1e-9)
        }
    }

    @Test
    fun `sphere shell points sit on the radius, volume points inside it`() {
        Sphere(2.0, 64, shell = true).sample().toList().forEach { assertEquals(2.0, it.length, 1e-9) }
        Sphere(2.0, 64, shell = false).sample().toList().forEach { assertTrue(it.length <= 2.0 + 1e-9) }
    }

    @Test
    fun `bezier passes through its end points`() {
        val seg = BezierSegment(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val cloud = BezierPath(listOf(seg), 20).sample()
        assertEquals(20, cloud.size)
        assertEquals(0.0, cloud[0].distanceTo(seg.p0), 1e-9)
        assertEquals(0.0, cloud[19].distanceTo(seg.p1), 1e-9)
    }

    @Test
    fun `arc-length resampling spaces points evenly even when the parameter does not`() {
        // Control points stacked at the start bunch the parametric samples there.
        val seg = BezierSegment(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3(3.0, 0.0, 0.0))
        val pts = BezierPath(listOf(seg), 31).sample().toList()
        val gaps = pts.zipWithNext { a, b -> a.distanceTo(b) }
        val mean = gaps.average()
        gaps.forEach { assertEquals(mean, it, mean * 0.05) }
    }

    @Test
    fun `resample distributes points across paths by length`() {
        val short = listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0))
        val long = listOf(Vec3.ZERO, Vec3(3.0, 0.0, 0.0))
        val pts = Resample.uniform(listOf(short, long), 40)
        assertEquals(40, pts.size)
        assertEquals(Resample.along(short, 10), pts.take(10)) // a quarter of the length → a quarter of the points
    }

    @Test
    fun `mirror doubles the points and reflects them`() {
        val line = Line(Vec3(0.2, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), 5)
        val mirrored = Mirror(Axis.X, line).sample()
        assertEquals(10, mirrored.size)
        assertEquals(-mirrored[0].x, mirrored[5].x, 1e-9)
    }

    @Test
    fun `radial places copies around a ring`() {
        val cloud = Radial(radius = 2.0, count = 4, shapes = listOf(PointShape)).sample()
        assertEquals(4, cloud.size)
        cloud.toList().forEach { assertEquals(2.0, it.length, 1e-9) }
    }

    @Test
    fun `glyph turns text outlines into evenly spaced points of the requested height`() {
        val glyph = Glyph(text = "A", font = "SansSerif", height = 1.0, points = 80).sample()
        assertEquals(80, glyph.size)
        val (min, max) = glyph.bounds()
        assertEquals(1.0, max.y - min.y, 0.05)
        assertEquals(0.0, (max.y + min.y) / 2, 0.05) // centred on the origin
    }

    @Test
    fun `glyph without a usable font throws, and a fallback recovers`() {
        // U+1FFFE is a permanent noncharacter: no font can draw it.
        val impossible = Glyph(text = String(Character.toChars(0x1FFFE)), font = "NoSuchFont", height = 1.0, points = 10)
        assertThrows(GlyphUnavailableException::class.java) { impossible.sample() }
        assertEquals(6, WithFallback(impossible, Circle(1.0, 6)).sample().size)
    }

    @Test
    fun `equal shapes share one cached cloud`() {
        val cache = ShapeCache()
        val a = cache[Circle(1.0, 16)]
        val b = cache[Circle(1.0, 16)]
        assertSame(a, b)
        assertEquals(1, cache.size)
        cache.retainOnly(emptySet())
        assertEquals(0, cache.size)
    }
}
