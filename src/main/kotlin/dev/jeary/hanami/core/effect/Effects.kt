package dev.jeary.hanami.core.effect

import dev.jeary.hanami.core.tick.EffectSnapshot
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import java.util.UUID

/** What kind of transient effect is playing. */
enum class EffectKind {
    /** A one-shot emote / kill effect. */
    EMOTE,

    /** A trail imprint that keeps animating where it was left (koi ripples). */
    LINGER,

    /** A simulated arrow flight, used to preview projectile trails. */
    ARC,
}

/** A transient effect living in the world for a fixed number of ticks. Main-thread owned. */
class ActiveEffect(
    val kind: EffectKind,
    val definition: CosmeticDefinition,
    val ownerUuid: UUID,
    val privateTo: UUID?,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val startTick: Long,
    val durationTicks: Int,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val vz: Double = 0.0,
) {
    private val salt = (ownerUuid.hashCode() xor startTick.toInt()) and 0x7fffffff

    fun isExpired(tick: Long) = tick - startTick > durationTicks

    fun snapshot(tick: Long) = EffectSnapshot(
        kind, definition, ownerUuid, privateTo, worldId, x, y, z, yaw, vx, vy, vz,
        ageTicks = tick - startTick,
        durationTicks = durationTicks,
        salt = salt,
    )
}

/**
 * A request from the compute stage to start an effect. The compute thread cannot mutate
 * main-thread state, so it returns these with the frame and the main thread applies them.
 */
sealed interface SpawnRequest {
    data class Linger(
        val definition: CosmeticDefinition,
        val ownerUuid: UUID,
        val privateTo: UUID?,
        val worldId: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val durationTicks: Int,
    ) : SpawnRequest
}

/** Owns every live transient effect. Only touched from the main thread. */
class EffectManager(private val maxEffects: Int = 512) {
    private val effects = ArrayList<ActiveEffect>()

    val size: Int get() = effects.size

    fun spawn(effect: ActiveEffect) {
        if (effects.size >= maxEffects) effects.removeAt(0)
        effects += effect
    }

    fun apply(request: SpawnRequest, tick: Long) = when (request) {
        is SpawnRequest.Linger -> spawn(
            ActiveEffect(
                EffectKind.LINGER, request.definition, request.ownerUuid, request.privateTo, request.worldId,
                request.x, request.y, request.z, request.yaw, tick, request.durationTicks,
            ),
        )
    }

    /** Drops expired effects and returns snapshots of the rest. */
    fun snapshot(tick: Long): List<EffectSnapshot> {
        effects.removeIf { it.isExpired(tick) }
        return effects.map { it.snapshot(tick) }
    }

    fun removeOwnedBy(owner: UUID) {
        effects.removeIf { it.ownerUuid == owner }
    }

    fun clear() = effects.clear()
}
