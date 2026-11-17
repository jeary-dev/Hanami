package dev.jeary.hanami.core.tick

/**
 * Rolling performance numbers for `/hanami debug`. All recording happens on the main thread
 * (the compute time travels back inside the frame), so no synchronisation is needed.
 */
class TickMetrics(private val window: Int = 200) {

    private val mainNanos = LongArray(window)
    private val computeNanos = LongArray(window)
    private val particles = IntArray(window)
    private val points = IntArray(window)
    private val requested = IntArray(window)
    private var cursor = 0
    private var filled = 0

    var lateFrames: Long = 0
        private set
    var totalTicks: Long = 0
        private set
    var lastSources = 0
        private set
    var lastProjectiles = 0
        private set
    var lastEffects = 0
        private set

    fun recordLateFrame() {
        lateFrames++
    }

    fun record(mainNs: Long, computeNs: Long, sent: Int, pointsAfter: Int, pointsBefore: Int) {
        mainNanos[cursor] = mainNs
        computeNanos[cursor] = computeNs
        particles[cursor] = sent
        points[cursor] = pointsAfter
        requested[cursor] = pointsBefore
        cursor = (cursor + 1) % window
        if (filled < window) filled++
        totalTicks++
    }

    fun recordPopulation(sources: Int, projectiles: Int, effects: Int) {
        lastSources = sources
        lastProjectiles = projectiles
        lastEffects = effects
    }

    fun reset() {
        cursor = 0
        filled = 0
        lateFrames = 0
        totalTicks = 0
    }

    data class Summary(
        val samples: Int,
        val mainAvgMs: Double,
        val mainP95Ms: Double,
        val mainMaxMs: Double,
        val computeAvgMs: Double,
        val computeP95Ms: Double,
        val particlesAvg: Double,
        val particlesMax: Int,
        val pointsAvg: Double,
        val requestedAvg: Double,
        val lateFrames: Long,
    )

    fun summary(): Summary {
        if (filled == 0) return Summary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, lateFrames)
        val main = mainNanos.copyOf(filled).sorted()
        val compute = computeNanos.copyOf(filled).sorted()
        return Summary(
            samples = filled,
            mainAvgMs = main.average() / 1e6,
            mainP95Ms = percentile(main, 0.95) / 1e6,
            mainMaxMs = main.last() / 1e6,
            computeAvgMs = compute.average() / 1e6,
            computeP95Ms = percentile(compute, 0.95) / 1e6,
            particlesAvg = particles.copyOf(filled).average(),
            particlesMax = particles.copyOf(filled).max(),
            pointsAvg = points.copyOf(filled).average(),
            requestedAvg = requested.copyOf(filled).average(),
            lateFrames = lateFrames,
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt()].toDouble()
}
