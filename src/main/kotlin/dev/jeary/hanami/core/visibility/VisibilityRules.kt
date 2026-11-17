package dev.jeary.hanami.core.visibility

import dev.jeary.hanami.config.VisibilitySettings
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/** Why a player's cosmetics are currently hidden; `null` from [VisibilityRules.hiddenReason] = shown. */
enum class HiddenReason { DEAD, SPECTATOR, INVISIBLE, VANISHED, WORLD, COMBAT }

/** Decides whether a player's cosmetics are drawn at all this tick. */
class VisibilityRules(
    private val settings: () -> VisibilitySettings,
    private val combat: CombatTracker,
    /** Vanish detection; the hook layer installs a richer check when vanish plugins exist. */
    var isVanished: (Player) -> Boolean = { hasVanishedMetadata(it) },
) {
    fun hiddenReason(player: Player): HiddenReason? {
        val s = settings()
        return when {
            player.isDead -> HiddenReason.DEAD
            s.hideSpectators && player.gameMode == GameMode.SPECTATOR -> HiddenReason.SPECTATOR
            s.hideInvisible && player.isInvisible -> HiddenReason.INVISIBLE
            s.hideInvisible && player.hasPotionEffect(PotionEffectType.INVISIBILITY) -> HiddenReason.INVISIBLE
            s.hideVanished && isVanished(player) -> HiddenReason.VANISHED
            player.world.name.lowercase() in s.worldBlacklist -> HiddenReason.WORLD
            s.hideInCombat && combat.inCombat(player.uniqueId, s.combatSeconds) -> HiddenReason.COMBAT
            else -> null
        }
    }

    companion object {
        /**
         * The de-facto vanish convention: Essentials, SuperVanish, PremiumVanish and others set a
         * boolean `vanished` metadata value on hidden players.
         */
        fun hasVanishedMetadata(player: Player): Boolean =
            player.getMetadata("vanished").any { it.asBoolean() }
    }
}

/** Tags players who dealt or took PvP damage recently. */
class CombatTracker : Listener {
    private val lastCombat = HashMap<UUID, Long>()

    fun inCombat(uuid: UUID, seconds: Int): Boolean {
        val last = lastCombat[uuid] ?: return false
        return System.currentTimeMillis() - last < seconds * 1000L
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        if (attacker == victim) return
        val now = System.currentTimeMillis()
        lastCombat[victim.uniqueId] = now
        lastCombat[attacker.uniqueId] = now
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastCombat.remove(event.player.uniqueId)
    }
}
