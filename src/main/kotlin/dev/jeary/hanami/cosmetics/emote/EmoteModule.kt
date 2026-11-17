package dev.jeary.hanami.cosmetics.emote

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.tick.EffectSnapshot
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import dev.jeary.hanami.math.Mat3

/**
 * One-shot effects (kill effects and emotes). An emote lives for `duration-ticks`; its
 * animation runs on the effect's own clock, keyframes scale it over its life, and the
 * `progressive` emit mode draws its shape stroke by stroke — a torii gate inked into the air.
 */
class EmoteModule : CosmeticModule {
    override val type = CosmeticType.EMOTE

    override fun renderEffect(effect: EffectSnapshot, ctx: RenderContext) {
        val progress = effect.progress
        val faced by lazy(LazyThreadSafetyMode.NONE) { Mat3.minecraftYaw(effect.yaw.toDouble()) }
        val emission = ctx.emission(
            effect.ownerUuid, effect.worldId, effect.x, effect.y, effect.z, effect.privateTo, attachedToOwner = false,
        )
        for (layer in effect.definition.layers) {
            val basis = if (layer.animation.followBodyYaw) faced else Mat3.IDENTITY
            ctx.layers.render(
                layer, basis, effect.x, effect.y, effect.z,
                tick = effect.ageTicks, step = ctx.step, salt = 0, random = ctx.random,
                scale = layer.animation.scaleAt(progress), progress = progress,
            )?.let(emission.commands::add)
        }
    }
}
