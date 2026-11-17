package dev.jeary.hanami.core.tick

import dev.jeary.hanami.core.render.DrawCommand
import dev.jeary.hanami.core.render.Emission
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps particle output bounded no matter how many players stack effects in one spot.
 *
 * 1. **Per player**: each owner may produce at most `perPlayer` points per tick, summed over
 *    all their cosmetics and projectiles. Over budget → every one of their commands is thinned
 *    by the same factor, so all their cosmetics stay visible, just sparser.
 * 2. **Global**: the real cost of particles is packets, i.e. points × viewers. If the total
 *    exceeds `global`, everything is thinned by one fair factor.
 *
 * Thinning keeps every cosmetic's outline (just sparser) rather than dropping whole cosmetics.
 */
object ParticleBudget {

    data class Outcome(val pointsBefore: Int, val pointsAfter: Int, val thinnedEmissions: Int)

    fun apply(emissions: List<Emission>, perPlayer: Int, global: Int, tick: Long): Outcome {
        val before = emissions.sumOf { it.points }
        var thinned = 0

        // 1) per-owner caps
        val byOwner = LinkedHashMap<UUID, MutableList<Emission>>()
        for (e in emissions) byOwner.getOrPut(e.ownerUuid) { ArrayList() }.add(e)
        for (owned in byOwner.values) {
            val demand = owned.sumOf { it.points }
            if (demand > perPlayer) {
                distribute(owned, perPlayer.toDouble() / demand, tick) { 1 }
                thinned += owned.size
            }
        }

        // 2) global cap on points × viewers
        val sends = emissions.sumOf { it.points.toLong() * it.viewers.size }
        val factor = globalFactor(sends, global)
        if (factor < 1.0) {
            val visible = emissions.filter { it.viewers.isNotEmpty() }
            distribute(visible, factor, tick) { it.viewers.size }
            thinned += visible.size
        }
        return Outcome(before, emissions.sumOf { it.points }, thinned)
    }

    /** Fraction of output to keep so [sends] fits in [budget]. */
    fun globalFactor(sends: Long, budget: Int): Double =
        if (sends <= budget) 1.0 else budget.toDouble() / sends

    /**
     * Thins every command in [group] to about `fraction` of its points, so that the *total*
     * weighted cost (points × [weight]) lands within half of one command's weight of the target.
     *
     * Rounding each command on its own (even stochastically) only holds on average: when one
     * point costs ~60 sends, a few unlucky round-ups blow the cap by tens of percent. Here the
     * rounding error carries from one command to the next (error diffusion), so it never builds up.
     * The walk starts at a tick-dependent offset so the command that gets the extra point changes
     * from frame to frame.
     */
    private inline fun distribute(group: List<Emission>, fraction: Double, tick: Long, weight: (Emission) -> Int) {
        val cmds = ArrayList<Pair<DrawCommand, Int>>()
        for (e in group) {
            val w = weight(e)
            for (c in e.commands) if (c.points > 0) cmds += c to w
        }
        if (cmds.isEmpty()) return
        val start = Math.floorMod(tick, cmds.size.toLong()).toInt()
        var target = 0.0
        var sent = 0.0
        for (k in cmds.indices) {
            val (cmd, w) = cmds[(start + k) % cmds.size]
            val n = cmd.points
            target += n * fraction * w
            val keep = ((target - sent) / w).roundToInt().coerceIn(0, n)
            sent += keep.toDouble() * w
            cmd.replaceCoords(thinTo(cmd.coords, keep, tick))
        }
    }

    /**
     * Keeps `fraction` of the points in [coords] *on average*, evenly spread.
     *
     * The fractional part is rounded stochastically (but deterministically per tick and salt):
     * a 3-point command at fraction 0.1 keeps one point on ~30% of frames instead of always one.
     * Without that, a crowd of tiny commands could never be brought under the global budget.
     *
     * The starting offset rotates with [tick] so different points survive on consecutive frames —
     * at a low budget the eye still sees the whole shape shimmer, not the same half every time.
     */
    fun thin(coords: DoubleArray, fraction: Double, tick: Long, salt: Int = 0): DoubleArray {
        val n = coords.size / 3
        if (n == 0 || fraction >= 1.0) return coords
        val exact = n * fraction.coerceAtLeast(0.0)
        val whole = exact.toInt()
        val keep = min(n, whole + if (exact - whole > unitHash(tick, salt)) 1 else 0)
        return thinTo(coords, keep, tick)
    }

    /** Keeps exactly [keep] evenly spread points of [coords], rotating which ones with [tick]. */
    fun thinTo(coords: DoubleArray, keep: Int, tick: Long): DoubleArray {
        val n = coords.size / 3
        if (keep >= n) return coords
        if (keep <= 0) return EMPTY
        val out = DoubleArray(keep * 3)
        val stride = n.toDouble() / keep
        val phase = (tick % 7) / 7.0
        for (i in 0 until keep) {
            val src = min(n - 1, ((i + phase) * stride).toInt())
            coords.copyInto(out, i * 3, src * 3, src * 3 + 3)
        }
        return out
    }

    /** A deterministic pseudo-random number in [0, 1) from (tick, salt) — SplitMix64 finaliser. */
    internal fun unitHash(tick: Long, salt: Int): Double {
        var z = tick * -0x61c8864680b583ebL + salt
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11) * (1.0 / (1L shl 53))
    }

    private val EMPTY = DoubleArray(0)
}
