package dev.jeary.hanami.cosmetics.projectile

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.tick.EffectSnapshot
import dev.jeary.hanami.core.tick.ProjectileSnapshot
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import dev.jeary.hanami.math.Mat3
import dev.jeary.hanami.math.Vec3
import java.util.UUID

/**
 * Trails behind arrows, tridents and other projectiles. Each layer is oriented along the flight
 * path (local +z = direction of travel), so a circle on the XY plane rotated with `roll` becomes a
 * spiral wrapped around the arrow's path.
 *
 * Previews use a simulated ballistic arc instead of a real entity.
 */
class ProjectileModule : CosmeticModule {
    override val type = CosmeticType.PROJECTILE

    override fun renderOnProjectile(projectile: ProjectileSnapshot, ctx: RenderContext) {
        draw(
            projectile.definition, projectile.ownerUuid, projectile.worldId,
            projectile.x, projectile.y, projectile.z,
            Vec3(projectile.vx, projectile.vy, projectile.vz),
            projectile.ownerUuid.takeIf { projectile.privateView }, projectile.salt, ctx,
        )
    }

    /** Preview arcs: an arrow's flight replayed every [ARC_TICKS] ticks from the launch point. */
    override fun renderEffect(effect: EffectSnapshot, ctx: RenderContext) {
        val t = (effect.ageTicks % ARC_TICKS).toDouble()
        val x = effect.x + effect.vx * t
        val y = effect.y + effect.vy * t - 0.5 * GRAVITY * t * t
        val z = effect.z + effect.vz * t
        draw(
            effect.definition, effect.ownerUuid, effect.worldId, x, y, z,
            Vec3(effect.vx, effect.vy - GRAVITY * t, effect.vz),
            effect.privateTo, effect.salt, ctx,
        )
    }

    private fun draw(
        definition: CosmeticDefinition,
        owner: UUID,
        world: UUID,
        x: Double,
        y: Double,
        z: Double,
        velocity: Vec3,
        privateTo: UUID?,
        salt: Int,
        ctx: RenderContext,
    ) {
        val basis = Mat3.lookAlong(velocity)
        val emission = ctx.emission(owner, world, x, y, z, privateTo, attachedToOwner = false)
        for (layer in definition.layers) {
            ctx.layers.render(layer, basis, x, y, z, ctx.tick, ctx.step, salt, ctx.random)
                ?.let(emission.commands::add)
        }
    }

    private companion object {
        const val ARC_TICKS = 30
        const val GRAVITY = 0.05 // arrow gravity, blocks/tick²
    }
}
