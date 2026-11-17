package dev.jeary.hanami.cosmetics.aura

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.PlayerSnapshot
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import dev.jeary.hanami.math.Mat3

/**
 * Ambient effects around a player — orbits, rings, clouds. Usually `idle-only`, so they bloom
 * when a player stands still and get out of the way while they move.
 */
class AuraModule : CosmeticModule {
    override val type = CosmeticType.AURA

    override fun renderOnPlayer(player: PlayerSnapshot, cosmetic: ActiveCosmetic, ctx: RenderContext) {
        val def = cosmetic.definition
        val aura = def.behavior as? Behavior.Aura ?: return
        if (aura.idleOnly && player.horizontalSpeed > IDLE_SPEED) return

        val emission = ctx.emission(player.uuid, player.worldId, player.x, player.y, player.z, player.uuid.takeIf { cosmetic.privateView })
        val bodyBasis by lazy(LazyThreadSafetyMode.NONE) { Mat3.minecraftYaw(player.bodyYaw.toDouble()) }
        for (layer in def.layers) {
            val basis = if (layer.animation.followBodyYaw) bodyBasis else Mat3.IDENTITY
            ctx.layers.render(layer, basis, player.x, player.y, player.z, ctx.tick, ctx.step, player.salt, ctx.random)
                ?.let(emission.commands::add)
        }
    }

    private companion object {
        /** Below this (blocks/tick) a player counts as standing still; ignores tiny drifts. */
        const val IDLE_SPEED = 0.03
    }
}
