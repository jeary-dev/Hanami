package dev.jeary.hanami.core.render

import dev.jeary.hanami.core.effect.SpawnRequest
import org.bukkit.Particle
import java.util.UUID

/**
 * One batch of identical particles at many positions: a layer of one cosmetic on one frame.
 * [coords] is packed xyz in world space, with exactly [points] points.
 */
class DrawCommand(
    val particle: Particle,
    val data: Any?,
    coords: DoubleArray,
    /** Particles per point (0 = directional: the offsets below are a velocity). */
    val count: Int,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val extra: Double,
) {
    var coords: DoubleArray = coords
        private set

    val points: Int get() = coords.size / 3

    /** Replaces the points with a thinned subset (budget enforcement). */
    fun replaceCoords(thinned: DoubleArray) {
        coords = thinned
    }
}

/**
 * Everything one owner draws from one origin this frame, plus who may see it.
 * [viewers] are indices into the tick's player snapshot list, filled in by the compute stage.
 */
class Emission(
    val ownerUuid: UUID,
    val worldId: UUID,
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    /** Only this player may see it (previews). */
    val privateTo: UUID?,
    /** Whether viewers must be tracking the owner entity (false for detached effects like arrows). */
    val attachedToOwner: Boolean,
) {
    val commands = ArrayList<DrawCommand>(4)
    var viewers: IntArray = EMPTY_VIEWERS

    val points: Int get() = commands.sumOf { it.points }

    private companion object {
        val EMPTY_VIEWERS = IntArray(0)
    }
}

/** The result of one compute pass, consumed by the main thread on the next tick. */
class Frame(
    val tick: Long,
    val emissions: List<Emission>,
    val spawnRequests: List<SpawnRequest>,
    val computeNanos: Long,
    /** Points produced before budgets were applied. */
    val pointsRequested: Int,
    /** Points remaining after budgets. */
    val pointsAfterBudget: Int,
    val thinnedEmissions: Int,
) {
    companion object {
        val EMPTY = Frame(0, emptyList(), emptyList(), 0, 0, 0, 0)
    }
}
