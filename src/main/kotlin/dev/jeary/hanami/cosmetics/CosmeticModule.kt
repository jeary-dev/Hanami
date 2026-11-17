package dev.jeary.hanami.cosmetics

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.effect.SpawnRequest
import dev.jeary.hanami.core.render.Emission
import dev.jeary.hanami.core.render.LayerRenderer
import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.EffectSnapshot
import dev.jeary.hanami.core.tick.PlayerSnapshot
import dev.jeary.hanami.core.tick.ProjectileSnapshot
import java.util.UUID
import java.util.random.RandomGenerator

/**
 * The common interface every cosmetic type implements. Modules are stateless and run on the
 * compute thread: they read immutable snapshots and write draw commands into [RenderContext].
 *
 * A module only overrides the anchors it supports — trails/auras/wings attach to players,
 * projectile trails to arrows, emotes to fixed points in the world.
 */
interface CosmeticModule {
    val type: CosmeticType

    fun renderOnPlayer(player: PlayerSnapshot, cosmetic: ActiveCosmetic, ctx: RenderContext) {}

    fun renderOnProjectile(projectile: ProjectileSnapshot, ctx: RenderContext) {}

    fun renderEffect(effect: EffectSnapshot, ctx: RenderContext) {}
}

/** What a module can do while rendering one frame. */
interface RenderContext {
    val tick: Long
    val step: Int
    val random: RandomGenerator
    val layers: LayerRenderer

    /** Starts a group of draw commands sharing an owner, origin and audience. */
    fun emission(
        ownerUuid: UUID,
        worldId: UUID,
        x: Double,
        y: Double,
        z: Double,
        privateTo: UUID? = null,
        attachedToOwner: Boolean = true,
    ): Emission

    /** Asks the main thread to start a transient effect after this frame. */
    fun spawn(request: SpawnRequest)
}
