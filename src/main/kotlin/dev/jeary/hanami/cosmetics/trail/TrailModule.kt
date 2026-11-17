package dev.jeary.hanami.cosmetics.trail

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.effect.SpawnRequest
import dev.jeary.hanami.core.render.Cadence
import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.EffectSnapshot
import dev.jeary.hanami.core.tick.PlayerSnapshot
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import dev.jeary.hanami.cosmetics.TrailMode
import dev.jeary.hanami.math.Mat3

/**
 * Particles left behind a moving player.
 *
 * - `behind` / `feet`: drawn each frame while the player moves faster than `min-speed`.
 * - `water-surface`: only while swimming, flattened onto the water.
 * - `linger-ticks > 0`: instead of drawing in place, each emission leaves an imprint that keeps
 *   animating where it was dropped (keyframes scale it over its life) — koi ripples spreading out.
 */
class TrailModule : CosmeticModule {
    override val type = CosmeticType.TRAIL

    override fun renderOnPlayer(player: PlayerSnapshot, cosmetic: ActiveCosmetic, ctx: RenderContext) {
        val def = cosmetic.definition
        val trail = def.behavior as? Behavior.Trail ?: return
        if (player.speed < trail.minSpeed) return
        if (trail.mode == TrailMode.WATER_SURFACE && !player.inWater) return

        val (x, y, z) = anchor(player, trail.mode)
        val basis = if (def.layers.any { it.animation.followBodyYaw }) Mat3.minecraftYaw(player.bodyYaw.toDouble()) else Mat3.IDENTITY
        val privateTo = player.uuid.takeIf { cosmetic.privateView }

        if (trail.lingerTicks > 0) {
            if (Cadence.fires(ctx.tick, trail.dropIntervalTicks, player.salt, ctx.step)) {
                ctx.spawn(
                    SpawnRequest.Linger(def, player.uuid, privateTo, player.worldId, x, y, z, player.bodyYaw, trail.lingerTicks),
                )
            }
            return
        }

        val emission = ctx.emission(player.uuid, player.worldId, x, y, z, privateTo)
        for (layer in def.layers) {
            val layerBasis = if (layer.animation.followBodyYaw) basis else Mat3.IDENTITY
            ctx.layers.render(layer, layerBasis, x, y, z, ctx.tick, ctx.step, player.salt, ctx.random)
                ?.let(emission.commands::add)
        }
    }

    /** Lingering imprints animate in place, scaled by their keyframes over their lifetime. */
    override fun renderEffect(effect: EffectSnapshot, ctx: RenderContext) {
        val def = effect.definition
        val emission = ctx.emission(effect.ownerUuid, effect.worldId, effect.x, effect.y, effect.z, effect.privateTo, attachedToOwner = false)
        val progress = effect.progress
        for (layer in def.layers) {
            val basis = if (layer.animation.followBodyYaw) Mat3.minecraftYaw(effect.yaw.toDouble()) else Mat3.IDENTITY
            ctx.layers.render(
                layer, basis, effect.x, effect.y, effect.z,
                // effects animate on their own clock, so every imprint starts its animation fresh
                tick = effect.ageTicks, step = ctx.step, salt = 0, random = ctx.random,
                scale = layer.animation.scaleAt(progress), progress = progress,
            )?.let(emission.commands::add)
        }
    }

    private fun anchor(p: PlayerSnapshot, mode: TrailMode): Triple<Double, Double, Double> = when (mode) {
        TrailMode.FEET -> Triple(p.x, p.y + 0.05, p.z)
        TrailMode.WATER_SURFACE -> Triple(p.x, p.waterSurfaceY + 0.02, p.z)
        TrailMode.BEHIND -> {
            val h = p.horizontalSpeed
            if (h < 1e-4) Triple(p.x, p.y + 0.1, p.z)
            else Triple(p.x - p.dx / h * BEHIND_DISTANCE, p.y + 0.1, p.z - p.dz / h * BEHIND_DISTANCE)
        }
    }

    private companion object {
        const val BEHIND_DISTANCE = 0.35
    }
}
