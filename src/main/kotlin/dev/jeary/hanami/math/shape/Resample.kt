package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.Vec3
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Arc-length resampling. Parametric curves (beziers, glyph outlines) bunch their points up where
 * the parameter moves slowly; resampling by distance makes particles evenly spaced, which is
 * the difference between "drawn" and "dotted" lines.
 */
object Resample {

    /**
     * Distributes [total] points over all [paths] proportionally to each path's length, evenly
     * spaced along each path. Paths with fewer than two vertices are kept as single points.
     */
    fun uniform(paths: List<List<Vec3>>, total: Int): List<Vec3> {
        if (total <= 0 || paths.isEmpty()) return emptyList()
        val lengths = paths.map(::lengthOf)
        val sum = lengths.sum()
        if (sum <= Vec3.EPSILON) return paths.mapNotNull { it.firstOrNull() }.take(total)

        val out = ArrayList<Vec3>(total)
        var remaining = total
        paths.forEachIndexed { index, path ->
            val share = if (index == paths.lastIndex) remaining
            else (total * lengths[index] / sum).roundToInt().coerceAtMost(remaining)
            remaining -= share
            if (share > 0) out += along(path, share)
        }
        return out
    }

    fun lengthOf(path: List<Vec3>): Double =
        path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

    /** [count] points evenly spaced along one path, both endpoints included. */
    fun along(path: List<Vec3>, count: Int): List<Vec3> {
        if (path.isEmpty() || count <= 0) return emptyList()
        if (path.size == 1 || count == 1) return List(count) { path[0] }

        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) cumulative[i] = cumulative[i - 1] + path[i - 1].distanceTo(path[i])
        val length = cumulative.last()
        if (length <= Vec3.EPSILON) return List(count) { path[0] }

        val out = ArrayList<Vec3>(count)
        var segment = 1
        for (i in 0 until count) {
            val target = length * i / max(1, count - 1)
            while (segment < path.lastIndex && cumulative[segment] < target) segment++
            val start = cumulative[segment - 1]
            val span = cumulative[segment] - start
            val t = if (span <= Vec3.EPSILON) 0.0 else ((target - start) / span).coerceIn(0.0, 1.0)
            out += path[segment - 1].lerp(path[segment], t)
        }
        return out
    }
}
