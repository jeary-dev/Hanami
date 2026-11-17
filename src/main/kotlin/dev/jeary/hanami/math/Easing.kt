package dev.jeary.hanami.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Animation curves. Every curve maps `t ∈ [0, 1] → [0, 1]` with `ease(0) = 0` and `ease(1) = 1`.
 *
 * The same curve also drives periodic motion via [wave]: a phase in `[0, 1)` goes 0 → 1 → 0 through
 * the curve and is mapped to `[-1, 1]`. For [Sine] that is exactly `-cos(2πp)`, so a single
 * `curve:` key in the config works for both keyframes and oscillations.
 */
sealed class Easing(val id: String) {

    abstract fun ease(t: Double): Double

    /** Periodic value in `[-1, 1]` for a phase in `[0, 1)`. */
    fun wave(phase: Double): Double {
        val p = phase - kotlin.math.floor(phase)
        val tri = if (p < 0.5) p * 2.0 else 2.0 - p * 2.0
        return ease(tri) * 2.0 - 1.0
    }

    data object Linear : Easing("linear") {
        override fun ease(t: Double) = t
    }

    data object Sine : Easing("sine") {
        override fun ease(t: Double) = (1.0 - cos(PI * t)) / 2.0
    }

    data object EaseIn : Easing("ease_in") {
        override fun ease(t: Double) = t * t * t
    }

    data object EaseOut : Easing("ease_out") {
        override fun ease(t: Double) = 1.0 - (1.0 - t).pow(3)
    }

    data object EaseInOut : Easing("ease_in_out") {
        override fun ease(t: Double) =
            if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
    }

    /** Quick swell then a long, soft decay — breathing lights, fireflies. */
    data object Pulse : Easing("pulse") {
        override fun ease(t: Double) = sin(PI / 2.0 * t).pow(0.35)
    }

    data object Bounce : Easing("bounce") {
        override fun ease(t: Double): Double {
            val n = 7.5625
            val d = 2.75
            return when {
                t < 1 / d -> n * t * t
                t < 2 / d -> (t - 1.5 / d).let { n * it * it + 0.75 }
                t < 2.5 / d -> (t - 2.25 / d).let { n * it * it + 0.9375 }
                else -> (t - 2.625 / d).let { n * it * it + 0.984375 }
            }
        }
    }

    companion object {
        val ALL: List<Easing> by lazy { listOf(Linear, Sine, EaseIn, EaseOut, EaseInOut, Pulse, Bounce) }

        fun byId(id: String): Easing? = ALL.firstOrNull { it.id.equals(id.trim().replace('-', '_'), ignoreCase = true) }
    }
}
