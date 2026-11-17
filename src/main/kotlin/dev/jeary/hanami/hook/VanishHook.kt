package dev.jeary.hanami.hook

import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.visibility.VisibilityRules
import org.bukkit.entity.Player

/**
 * Hides cosmetics of vanished players. Essentials, SuperVanish and PremiumVanish all expose
 * vanish state through the shared `vanished` metadata key, so no compile-time dependency on any
 * of them is needed; this hook just reports what it found and installs the check.
 *
 * Viewers who can't see a player (Bukkit `hidePlayer`, which these plugins also use) never
 * receive that player's particles anyway — see `ParticleEmitter`.
 */
class VanishHook : Hook {
    override val plugins = PLUGINS

    override fun enable(core: HanamiCore): String {
        val found = PLUGINS.filter(core.plugin.server.pluginManager::isPluginEnabled)
        core.visibility.isVanished = ::isVanished
        return "vanish detection (${found.joinToString()})"
    }

    private fun isVanished(player: Player): Boolean = VisibilityRules.hasVanishedMetadata(player)

    companion object {
        val PLUGINS = listOf("Essentials", "SuperVanish", "PremiumVanish")
    }
}
