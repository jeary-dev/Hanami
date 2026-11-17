package dev.jeary.hanami.core

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.event.CosmeticEquipEvent
import dev.jeary.hanami.api.event.CosmeticPlayEvent
import dev.jeary.hanami.api.event.CosmeticUnequipEvent
import dev.jeary.hanami.api.event.PlayCause
import dev.jeary.hanami.core.effect.ActiveEffect
import dev.jeary.hanami.core.effect.EffectKind
import dev.jeary.hanami.core.effect.EffectManager
import dev.jeary.hanami.core.effect.PreviewService
import dev.jeary.hanami.core.profile.ProfileService
import dev.jeary.hanami.core.unlock.UnlockService
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.CosmeticRegistry
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginManager
import java.util.UUID

/** Outcome of an equip attempt — commands and menus map these to messages. */
sealed interface EquipResult {
    data class Equipped(val cosmetic: CosmeticDefinition) : EquipResult
    data object AlreadyEquipped : EquipResult
    data object Locked : EquipResult
    data object Cancelled : EquipResult
    data object ProfileLoading : EquipResult
}

sealed interface UnequipResult {
    data class Unequipped(val cosmetic: CosmeticDefinition?) : UnequipResult
    data object NothingEquipped : UnequipResult
    data object Cancelled : UnequipResult
    data object ProfileLoading : UnequipResult
}

sealed interface PlayResult {
    data object Played : PlayResult
    data class OnCooldown(val secondsLeft: Long) : PlayResult
    data object Cancelled : PlayResult
}

/**
 * The game rules for wearing cosmetics: equipping (with the public, cancellable events),
 * previews and one-shot emotes. Main thread only.
 */
class CosmeticService(
    private val profiles: ProfileService,
    private val unlocks: UnlockService,
    private val previews: PreviewService,
    private val effects: EffectManager,
    private val registry: () -> CosmeticRegistry,
    private val currentTick: () -> Long,
    private val events: PluginManager,
) {
    private val emoteCooldowns = HashMap<UUID, Long>()

    fun equip(player: Player, cosmetic: CosmeticDefinition, bypassLock: Boolean = false): EquipResult {
        val profile = profiles[player] ?: return EquipResult.ProfileLoading
        if (!bypassLock && !unlocks.isUnlocked(player, cosmetic, profile)) return EquipResult.Locked
        val previousId = profile.equippedId(cosmetic.type)
        if (previousId == cosmetic.id) return EquipResult.AlreadyEquipped

        val previous = previousId?.let(registry()::get)
        val event = CosmeticEquipEvent(player, cosmetic, previous)
        events.callEvent(event)
        if (event.isCancelled) return EquipResult.Cancelled

        profile.equip(cosmetic.type, cosmetic.id)
        previews.stop(player.uniqueId)
        return EquipResult.Equipped(cosmetic)
    }

    fun unequip(player: Player, type: CosmeticType): UnequipResult {
        val profile = profiles[player] ?: return UnequipResult.ProfileLoading
        val currentId = profile.equippedId(type) ?: return UnequipResult.NothingEquipped
        val current = registry()[currentId]
        if (current != null) {
            val event = CosmeticUnequipEvent(player, current)
            events.callEvent(event)
            if (event.isCancelled) return UnequipResult.Cancelled
        }
        profile.unequip(type)
        return UnequipResult.Unequipped(current)
    }

    /**
     * Shows [cosmetic] to [player] only, for [seconds]. Attached types replace the equipped one
     * temporarily; projectiles fly a simulated arc; emotes play in front of the player so they
     * can actually watch them from first person.
     */
    fun preview(player: Player, cosmetic: CosmeticDefinition, seconds: Int) {
        val tick = currentTick()
        val duration = seconds * 20
        val loc = player.location
        when (cosmetic.behavior) {
            is Behavior.Trail, is Behavior.Aura, is Behavior.Wings ->
                previews.start(player.uniqueId, cosmetic, tick + duration)

            is Behavior.Projectile -> {
                previews.start(player.uniqueId, cosmetic, tick + duration)
                val eye = player.eyeLocation
                val dir = eye.direction.multiply(ARC_SPEED)
                effects.spawn(
                    ActiveEffect(
                        EffectKind.ARC, cosmetic, player.uniqueId, player.uniqueId, player.world.uid,
                        eye.x, eye.y - 0.1, eye.z, loc.yaw, tick, duration, dir.x, dir.y + ARC_LIFT, dir.z,
                    ),
                )
            }

            is Behavior.Emote -> {
                val front = loc.clone().add(loc.direction.setY(0).normalize().multiply(PREVIEW_DISTANCE))
                play(player, cosmetic, front, PlayCause.PREVIEW, privateTo = player.uniqueId)
            }
        }
    }

    /** Plays a one-shot emote at [location], firing [CosmeticPlayEvent]. */
    fun play(
        player: Player,
        cosmetic: CosmeticDefinition,
        location: Location,
        cause: PlayCause,
        privateTo: UUID? = null,
    ): PlayResult {
        val emote = cosmetic.behavior as? Behavior.Emote ?: return PlayResult.Cancelled
        if (cause == PlayCause.COMMAND) {
            val readyAt = emoteCooldowns[player.uniqueId] ?: 0L
            val now = System.currentTimeMillis()
            if (now < readyAt) return PlayResult.OnCooldown((readyAt - now + 999) / 1000)
        }
        val event = CosmeticPlayEvent(player, cosmetic, location.clone(), cause)
        events.callEvent(event)
        if (event.isCancelled) return PlayResult.Cancelled

        if (cause == PlayCause.COMMAND) {
            emoteCooldowns[player.uniqueId] = System.currentTimeMillis() + emote.cooldownSeconds * 1000L
        }
        val at = event.location
        val world = at.world ?: player.world
        // Turn the effect's front towards whoever triggered it.
        val yaw = player.location.yaw + 180f
        effects.spawn(
            ActiveEffect(
                EffectKind.EMOTE, cosmetic, player.uniqueId, privateTo, world.uid,
                at.x, at.y, at.z, yaw, currentTick(), emote.durationTicks,
            ),
        )
        return PlayResult.Played
    }

    fun forget(uuid: UUID) {
        emoteCooldowns.remove(uuid)
        previews.stop(uuid)
    }

    private companion object {
        const val ARC_SPEED = 1.6
        const val ARC_LIFT = 0.15
        const val PREVIEW_DISTANCE = 3.5
    }
}
