package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.PointCloud
import java.util.concurrent.ConcurrentHashMap

/**
 * Shapes are value objects, so equal definitions share one sampled [PointCloud] — two cosmetics
 * using the same 32-point circle sample it once. Safe to read from the compute thread.
 */
class ShapeCache {
    private val clouds = ConcurrentHashMap<Shape, PointCloud>()

    operator fun get(shape: Shape): PointCloud = clouds.computeIfAbsent(shape) { it.sample() }

    val size: Int get() = clouds.size

    val totalPoints: Int get() = clouds.values.sumOf { it.size }

    /** Drops cached clouds that no longer belong to any loaded cosmetic. */
    fun retainOnly(live: Set<Shape>) {
        clouds.keys.retainAll(live)
    }

    fun clear() = clouds.clear()
}
