package dev.jeary.hanami.core.tick

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.HanamiSettings
import dev.jeary.hanami.core.compat.PlatformAdapter
import dev.jeary.hanami.core.effect.EffectManager
import dev.jeary.hanami.core.effect.PreviewService
import dev.jeary.hanami.core.profile.PlayerProfile
import dev.jeary.hanami.core.profile.ProfileService
import dev.jeary.hanami.core.visibility.VisibilityRules
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticRegistry
import dev.jeary.hanami.cosmetics.TrailMode
import dev.jeary.hanami.cosmetics.projectile.ProjectileTracker
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Main-thread stage of the pipeline: copies everything the maths needs out of Bukkit into an
 * immutable [TickSnapshot]. This is the only per-tick work that touches the server's state.
 */
class Snapshotter(
    private val profiles: ProfileService,
    private val registry: () -> CosmeticRegistry,
    private val settings: () -> HanamiSettings,
    private val visibility: VisibilityRules,
    private val platform: PlatformAdapter,
    private val previews: PreviewService,
    private val projectiles: ProjectileTracker,
    private val effects: EffectManager,
) {
    private val lastPositions = HashMap<UUID, DoubleArray>()
    private val bodyYaw = BodyYawEstimator()

    fun capture(tick: Long): TickSnapshot {
        val online = Bukkit.getOnlinePlayers()
        val players = ArrayList<PlayerSnapshot>(online.size)
        val defaults = settings().defaults
        for (player in online) {
            val profile = profiles[player]
            players += snapshotPlayer(players.size, player, profile, tick, defaults.showOthers, defaults.density)
        }
        return TickSnapshot(
            tick = tick,
            settings = settings().render,
            players = players,
            projectiles = projectiles.snapshot(tick),
            effects = effects.snapshot(tick),
        )
    }

    private fun snapshotPlayer(
        index: Int,
        player: Player,
        profile: PlayerProfile?,
        tick: Long,
        defaultShowOthers: Boolean,
        defaultDensity: dev.jeary.hanami.api.player.DensityLevel,
    ): PlayerSnapshot {
        val loc = player.location
        val uuid = player.uniqueId

        // Movement from our own position history: the server-side velocity of a walking player
        // is ~0, so Entity#getVelocity cannot tell us whether they are moving.
        val last = lastPositions.getOrPut(uuid) { doubleArrayOf(loc.x, loc.y, loc.z) }
        var dx = loc.x - last[0]
        var dy = loc.y - last[1]
        var dz = loc.z - last[2]
        if (dx * dx + dy * dy + dz * dz > TELEPORT_SQ) {
            dx = 0.0; dy = 0.0; dz = 0.0
        }
        val step = settings().render.updateInterval.toDouble()
        dx /= step; dy /= step; dz /= step
        last[0] = loc.x; last[1] = loc.y; last[2] = loc.z

        val body = platform.bodyYaw(player) ?: bodyYaw.update(uuid, loc.yaw, dx, dz)
        val cosmetics = activeCosmetics(player, profile, tick)
        val needsWater = cosmetics.any { (it.definition.behavior as? Behavior.Trail)?.mode == TrailMode.WATER_SURFACE }

        return PlayerSnapshot(
            index = index,
            player = player,
            uuid = uuid,
            worldId = player.world.uid,
            x = loc.x, y = loc.y, z = loc.z,
            yaw = loc.yaw,
            bodyYaw = body,
            pitch = loc.pitch,
            dx = dx, dy = dy, dz = dz,
            sneaking = player.isSneaking,
            gliding = player.isGliding,
            waterSurfaceY = if (needsWater && player.isInWater) waterSurface(player) else Double.NaN,
            showOthers = profile?.showOthers ?: defaultShowOthers,
            density = profile?.density ?: defaultDensity,
            cosmetics = cosmetics,
        )
    }

    /** What [player] is wearing right now, with previews applied; empty when hidden. */
    fun activeCosmetics(player: Player, profile: PlayerProfile?, tick: Long): List<ActiveCosmetic> {
        if (profile == null) return emptyList()
        if (visibility.hiddenReason(player) != null) return emptyList()
        val registry = registry()
        val result = ArrayList<ActiveCosmetic>(3)
        for (type in ATTACHED_TYPES) {
            val preview = previews.active(player.uniqueId, type, tick)
            if (preview != null) {
                result += ActiveCosmetic(preview, privateView = true)
                continue
            }
            val equipped = profile.equippedId(type)?.let(registry::get) ?: continue
            result += ActiveCosmetic(equipped, privateView = false)
        }
        return result
    }

    /** The projectile trail [player] would launch now (preview first, then equipped). */
    fun projectileCosmetic(player: Player, tick: Long): ActiveCosmetic? {
        val profile = profiles[player] ?: return null
        if (visibility.hiddenReason(player) != null) return null
        previews.active(player.uniqueId, CosmeticType.PROJECTILE, tick)?.let { return ActiveCosmetic(it, privateView = true) }
        return profile.equippedId(CosmeticType.PROJECTILE)?.let(registry()::get)?.let { ActiveCosmetic(it, privateView = false) }
    }

    fun forget(uuid: UUID) {
        lastPositions.remove(uuid)
        bodyYaw.forget(uuid)
    }

    private fun waterSurface(player: Player): Double {
        var block = player.location.block
        repeat(MAX_WATER_SCAN) {
            val above = block.getRelative(0, 1, 0)
            if (above.type != Material.WATER) return block.y + WATER_HEIGHT
            block = above
        }
        return Double.NaN // deep under water: no ripples
    }

    private companion object {
        val ATTACHED_TYPES = listOf(CosmeticType.TRAIL, CosmeticType.AURA, CosmeticType.WINGS)
        const val TELEPORT_SQ = 16.0
        const val MAX_WATER_SCAN = 2
        const val WATER_HEIGHT = 0.9
    }
}
