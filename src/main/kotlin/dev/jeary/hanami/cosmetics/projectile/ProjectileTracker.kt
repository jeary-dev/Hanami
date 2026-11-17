package dev.jeary.hanami.cosmetics.projectile

import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.ProjectileSnapshot
import dev.jeary.hanami.cosmetics.Behavior
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import java.util.UUID

/**
 * Remembers projectiles launched by players wearing a projectile trail. Only tracked entities are
 * looked at each tick — there is no scan over every entity in the world.
 */
class ProjectileTracker(
    /** The projectile cosmetic a player is currently wearing (respects previews and visibility). */
    private val cosmeticFor: (Player) -> ActiveCosmetic?,
    private val currentTick: () -> Long,
    private val maxTracked: Int = 256,
) : Listener {

    private class Tracked(
        val entity: Projectile,
        val ownerUuid: UUID,
        val cosmetic: ActiveCosmetic,
        val behavior: Behavior.Projectile,
        val startTick: Long,
    )

    private val tracked = ArrayList<Tracked>()

    val size: Int get() = tracked.size

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val shooter = projectile.shooter as? Player ?: return
        val cosmetic = cosmeticFor(shooter) ?: return
        val behavior = cosmetic.definition.behavior as? Behavior.Projectile ?: return
        if (behavior.appliesTo.isNotEmpty() && projectile.type !in behavior.appliesTo) return
        if (tracked.size >= maxTracked) tracked.removeAt(0)
        tracked += Tracked(projectile, shooter.uniqueId, cosmetic, behavior, currentTick())
    }

    /** Main thread: drops finished projectiles and snapshots the rest. */
    fun snapshot(tick: Long): List<ProjectileSnapshot> {
        tracked.removeIf { t ->
            val entity = t.entity
            !entity.isValid ||
                (entity is AbstractArrow && entity.isInBlock) ||
                entity.isOnGround ||
                tick - t.startTick > t.behavior.maxLifetimeTicks
        }
        if (tracked.isEmpty()) return emptyList()
        return tracked.map { t ->
            val loc = t.entity.location
            val vel = t.entity.velocity
            ProjectileSnapshot(
                ownerUuid = t.ownerUuid,
                worldId = t.entity.world.uid,
                x = loc.x, y = loc.y, z = loc.z,
                vx = vel.x, vy = vel.y, vz = vel.z,
                ageTicks = (tick - t.startTick).toInt(),
                definition = t.cosmetic.definition,
                privateView = t.cosmetic.privateView,
                salt = t.entity.entityId,
            )
        }
    }

    fun clear() = tracked.clear()
}
