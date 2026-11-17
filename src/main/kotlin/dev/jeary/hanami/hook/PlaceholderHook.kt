package dev.jeary.hanami.hook

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.HanamiCore
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/** Registers the `%hanami_...%` PlaceholderAPI expansion. */
class PlaceholderHook : Hook {
    override val plugins = listOf("PlaceholderAPI")

    private var expansion: HanamiExpansion? = null

    override fun enable(core: HanamiCore): String? {
        val created = HanamiExpansion(core)
        if (!created.register()) return null
        expansion = created
        return "PlaceholderAPI (%hanami_...%)"
    }

    override fun disable() {
        expansion?.unregister()
        expansion = null
    }
}

/**
 * | Placeholder                        | Value                                    |
 * |------------------------------------|------------------------------------------|
 * | `%hanami_<type>%`                  | name of the equipped cosmetic, or "None" |
 * | `%hanami_<type>_id%`               | its id, or empty                         |
 * | `%hanami_unlocked_count%`          | cosmetics the player can use             |
 * | `%hanami_unlocked_count_<type>%`   | same, for one type                       |
 * | `%hanami_total_count%`             | cosmetics loaded                         |
 * | `%hanami_density%`                 | low / medium / high                      |
 * | `%hanami_show_others%`             | true / false                             |
 *
 * `<type>` is one of trail, aura, wings, projectile, emote.
 */
class HanamiExpansion(private val core: HanamiCore) : PlaceholderExpansion() {

    override fun getIdentifier() = "hanami"
    override fun getAuthor() = "jeary-dev"

    @Suppress("DEPRECATION") // getDescription is the only accessor available on Spigot
    override fun getVersion(): String = core.plugin.description.version

    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val key = params.lowercase()
        if (key == "total_count") return core.registry.size.toString()

        val online = player?.player ?: return ""
        val profile = core.profiles[online] ?: return ""

        CosmeticType.byId(key)?.let { type ->
            return profile.equippedId(type)?.let(core.registry::get)?.plainName ?: NONE
        }
        if (key.endsWith("_id")) {
            CosmeticType.byId(key.removeSuffix("_id"))?.let { type -> return profile.equippedId(type)?.value ?: "" }
        }
        if (key == "unlocked_count") {
            return core.registry.all.count { core.unlocks.isUnlocked(online, it, profile) }.toString()
        }
        if (key.startsWith("unlocked_count_")) {
            val type = CosmeticType.byId(key.removePrefix("unlocked_count_")) ?: return null
            return core.registry.ofType(type).count { core.unlocks.isUnlocked(online, it, profile) }.toString()
        }
        return when (key) {
            "density" -> profile.density.id
            "show_others" -> profile.showOthers.toString()
            else -> null
        }
    }

    private companion object {
        const val NONE = "None"
    }
}
