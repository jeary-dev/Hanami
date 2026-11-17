package dev.jeary.hanami.cosmetics.emote

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.event.PlayCause
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.EmoteTrigger
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

/** Plays the killer's equipped emote where their victim fell. */
class EmoteListener(private val core: HanamiCore) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: EntityDeathEvent) {
        val victim = event.entity
        val killer = victim.killer ?: return
        val settings = core.config.settings.emotes
        val allowed = if (victim is Player) settings.onPlayerKill else settings.onMobKill
        if (!allowed || killer == victim) return
        if (core.visibility.hiddenReason(killer) != null) return

        val emote = core.profiles[killer]?.equippedId(CosmeticType.EMOTE)?.let(core.registry::get) ?: return
        val behavior = emote.behavior as? Behavior.Emote ?: return
        if (EmoteTrigger.KILL !in behavior.triggers) return
        core.cosmetics.play(killer, emote, victim.location, PlayCause.KILL)
    }
}
