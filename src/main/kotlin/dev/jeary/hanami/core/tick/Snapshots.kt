package dev.jeary.hanami.core.tick

import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.config.RenderSettings
import dev.jeary.hanami.core.effect.EffectKind
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.sqrt

/*
 * Immutable per-tick snapshots. The main thread copies everything the maths needs out of Bukkit
 * into these plain objects; the compute stage then runs on another thread without touching a
 * single Bukkit API. Player references are carried only so the *main* thread can send packets.
 */

/** A cosmetic to draw this tick. [privateView] = only its owner sees it (previews). */
class ActiveCosmetic(val definition: CosmeticDefinition, val privateView: Boolean)

class PlayerSnapshot(
    val index: Int,
    val player: Player,
    val uuid: UUID,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val bodyYaw: Float,
    val pitch: Float,
    /** Movement since the previous snapshot, in blocks per tick. */
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val sneaking: Boolean,
    val gliding: Boolean,
    /** Y of the water surface the player is swimming in, or NaN when not in water. */
    val waterSurfaceY: Double,
    val showOthers: Boolean,
    val density: DensityLevel,
    /** What this player is wearing; empty when they are hidden (vanished, in combat, ...). */
    val cosmetics: List<ActiveCosmetic>,
) {
    /** Stable per-player number used to stagger emission across ticks. */
    val salt: Int = uuid.hashCode() and 0x7fffffff

    val horizontalSpeed: Double get() = sqrt(dx * dx + dz * dz)
    val speed: Double get() = sqrt(dx * dx + dy * dy + dz * dz)
    val inWater: Boolean get() = !waterSurfaceY.isNaN()
}

class ProjectileSnapshot(
    val ownerUuid: UUID,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val ageTicks: Int,
    val definition: CosmeticDefinition,
    val privateView: Boolean,
    val salt: Int,
)

class EffectSnapshot(
    val kind: EffectKind,
    val definition: CosmeticDefinition,
    val ownerUuid: UUID,
    val privateTo: UUID?,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    /** Launch velocity for [EffectKind.ARC] previews; zero otherwise. */
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val ageTicks: Long,
    val durationTicks: Int,
    val salt: Int,
) {
    val progress: Double get() = (ageTicks.toDouble() / durationTicks).coerceIn(0.0, 1.0)
}

class TickSnapshot(
    val tick: Long,
    val settings: RenderSettings,
    val players: List<PlayerSnapshot>,
    val projectiles: List<ProjectileSnapshot>,
    val effects: List<EffectSnapshot>,
) {
    val step: Int get() = settings.updateInterval
}
