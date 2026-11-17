package dev.jeary.hanami.core.render

import dev.jeary.hanami.cosmetics.EmitMode
import dev.jeary.hanami.cosmetics.Layer
import dev.jeary.hanami.math.Mat3
import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.shape.ShapeCache
import java.util.random.RandomGenerator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Decides whether something with an emission interval fires on a given tick. */
object Cadence {
    /**
     * True on one tick out of every [everyTicks]. [salt] offsets the phase per player, so a
     * hundred players with 4-tick auras spread their work over four ticks instead of spiking one.
     * [step] is the ticker's update interval (it may skip ticks).
     */
    fun fires(tick: Long, everyTicks: Int, salt: Int, step: Int): Boolean {
        val period = max(1, everyTicks / max(1, step))
        if (period == 1) return true
        return ((tick / max(1, step)) + salt) % period == 0L
    }
}

/**
 * Turns one [Layer] into a [DrawCommand]: picks which cached points to draw this frame, then
 * applies the frame's rotation/scale/translation with a single matrix multiply per point.
 */
class LayerRenderer(private val shapes: ShapeCache) {

    /**
     * @param basis local → world orientation of the anchor (body yaw, flight direction, ...).
     * @param ax anchor position in world space.
     * @param scale extra scale (keyframes, wing size).
     * @param progress effect progress ∈ [0, 1] for [EmitMode.Progressive].
     * @param extraLocal additional local transform applied before the animation (wing flap).
     * @return the command, or null when the layer does not fire this tick or has nothing to draw.
     */
    fun render(
        layer: Layer,
        basis: Mat3,
        ax: Double,
        ay: Double,
        az: Double,
        tick: Long,
        step: Int,
        salt: Int,
        random: RandomGenerator,
        scale: Double = 1.0,
        progress: Double = 1.0,
        extraLocal: Mat3? = null,
    ): DrawCommand? {
        val emit = layer.emit
        if (!Cadence.fires(tick, emit.intervalTicks, salt, step)) return null

        val cloud = shapes[layer.shape]
        if (cloud.isEmpty()) return null

        val anim = layer.animation
        val seconds = tick / 20.0

        // local → world: basis · (animated rotation) · (extra, e.g. flap) · scale
        var m = basis
        val r = anim.rotate
        if (r.x != 0.0 || r.y != 0.0 || r.z != 0.0) m = m * Mat3.euler(r.y * seconds, r.x * seconds, r.z * seconds)
        if (extraLocal != null) m = m * extraLocal
        val totalScale = scale * anim.scale
        if (totalScale != 1.0) m = m * Mat3.scale(totalScale)

        val offset = basis.transform(layer.offset)
        val bob = anim.bob?.at(tick, (salt % 100) / 100.0) ?: 0.0
        val tx = ax + offset.x
        val ty = ay + offset.y + bob
        val tz = az + offset.z

        val coords = select(cloud, emit.mode, emit.maxPoints, seconds, progress, random, m, tx, ty, tz)
        if (coords.isEmpty()) return null

        val tint = layer.palette?.colorAt(tick + salt % 40)
        val direction = emit.direction
        return if (direction != null) {
            val d = basis.transform(direction).normalized()
            DrawCommand(layer.particle.particle, layer.particle.data(tint), coords, 0, d.x, d.y, d.z, emit.speed)
        } else {
            DrawCommand(
                layer.particle.particle, layer.particle.data(tint), coords,
                emit.count, emit.spread.x, emit.spread.y, emit.spread.z, emit.speed,
            )
        }
    }

    private fun select(
        cloud: PointCloud,
        mode: EmitMode,
        maxPoints: Int,
        seconds: Double,
        progress: Double,
        random: RandomGenerator,
        m: Mat3,
        tx: Double,
        ty: Double,
        tz: Double,
    ): DoubleArray {
        val src = cloud.raw()
        val n = cloud.size
        return when (mode) {
            EmitMode.All -> strided(src, n, n, maxPoints, m, tx, ty, tz)

            EmitMode.Progressive -> {
                val revealed = min(n, ceil(progress.coerceIn(0.0, 1.0) * n).toInt())
                strided(src, n, revealed, maxPoints, m, tx, ty, tz)
            }

            is EmitMode.Sweep -> {
                val perHead = mode.tail + 1
                val total = min(maxPoints, mode.heads * perHead)
                val out = DoubleArray(total * 3)
                var written = 0
                val base = seconds * mode.cyclesPerSecond
                outer@ for (h in 0 until mode.heads) {
                    val phase = base + h.toDouble() / mode.heads
                    val head = floor((phase - floor(phase)) * n).toInt()
                    for (k in 0 until perHead) {
                        if (written >= total) break@outer
                        val idx = Math.floorMod(head - k, n)
                        m.transformInto(src, idx, 1, out, written, tx, ty, tz)
                        written++
                    }
                }
                out
            }

            is EmitMode.Random -> {
                val total = min(maxPoints, mode.count)
                val out = DoubleArray(total * 3)
                for (i in 0 until total) m.transformInto(src, random.nextInt(n), 1, out, i, tx, ty, tz)
                out
            }
        }
    }

    /** The first [limit] points, evenly subsampled down to [maxPoints] if needed. */
    private fun strided(
        src: DoubleArray, n: Int, limit: Int, maxPoints: Int,
        m: Mat3, tx: Double, ty: Double, tz: Double,
    ): DoubleArray {
        val count = min(limit, n)
        if (count <= 0) return EMPTY
        if (count <= maxPoints) {
            val out = DoubleArray(count * 3)
            m.transformInto(src, 0, count, out, 0, tx, ty, tz)
            return out
        }
        val out = DoubleArray(maxPoints * 3)
        val stride = count.toDouble() / maxPoints
        for (i in 0 until maxPoints) m.transformInto(src, (i * stride).toInt(), 1, out, i, tx, ty, tz)
        return out
    }

    private companion object {
        val EMPTY = DoubleArray(0)
    }
}
