package dev.jeary.hanami.cosmetics.wings

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.PlayerSnapshot
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import dev.jeary.hanami.math.Mat3
import dev.jeary.hanami.math.Vec3

/**
 * Particle wings. The config describes only the RIGHT wing (on the XY plane, +x outward from
 * the spine); this module draws it twice — once as-is, once mirrored — and animates both.
 *
 * Per frame, each side is transformed by
 * ```
 *   bodyYaw · tilt · [mirror] · sweep(θ)        θ = rest + flap.amplitude · curve.wave(t)
 * ```
 * where `sweep` swings the wing back around the spine. The flap phase is offset per player, so a
 * crowd of winged players doesn't beat in eerie unison. Sneaking folds the wings back.
 */
class WingsModule : CosmeticModule {
    override val type = CosmeticType.WINGS

    override fun renderOnPlayer(player: PlayerSnapshot, cosmetic: ActiveCosmetic, ctx: RenderContext) {
        val def = cosmetic.definition
        val wings = def.behavior as? Behavior.Wings ?: return

        val basis = Mat3.minecraftYaw(player.bodyYaw.toDouble())
        val tucked = wings.sneakTuck && player.sneaking
        val height = wings.height - if (tucked) SNEAK_DROP else 0.0
        // shoulders, pushed a little behind the spine
        val back = basis.transform(Vec3(0.0, 0.0, -wings.back))
        val ax = player.x + back.x
        val ay = player.y + height
        val az = player.z + back.z
        val tilt = Mat3.rotationX(Math.toRadians(-(wings.tilt + if (tucked) SNEAK_EXTRA_TILT else 0.0)))
        val phase = (player.salt % 1000) / 1000.0

        val emission = ctx.emission(player.uuid, player.worldId, ax, ay, az, player.uuid.takeIf { cosmetic.privateView })
        for (layer in def.layers) {
            val flap = layer.animation.flap
            val sweepDeg = when {
                tucked -> TUCKED_SWEEP
                player.gliding -> GLIDE_SWEEP
                else -> REST_SWEEP + (flap?.at(ctx.tick, phase) ?: 0.0)
            }
            val sweep = Mat3.rotationY(Math.toRadians(sweepDeg))
            val right = tilt * sweep
            val left = tilt * Mat3.MIRROR_X * sweep

            for (side in arrayOf(right, left)) {
                ctx.layers.render(
                    layer, basis, ax, ay, az, ctx.tick, ctx.step, player.salt, ctx.random,
                    scale = wings.scale, extraLocal = side,
                )?.let(emission.commands::add)
            }
        }
    }

    private companion object {
        /** Resting angle (degrees) the wings are swept back from flat. */
        const val REST_SWEEP = 22.0
        const val GLIDE_SWEEP = 4.0
        const val TUCKED_SWEEP = 68.0
        const val SNEAK_DROP = 0.22
        const val SNEAK_EXTRA_TILT = 12.0
    }
}
