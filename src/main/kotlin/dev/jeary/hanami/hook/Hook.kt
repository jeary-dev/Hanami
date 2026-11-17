package dev.jeary.hanami.hook

import dev.jeary.hanami.core.HanamiCore
import java.util.logging.Level

/**
 * An optional integration with another plugin. Hooks are only instantiated after their plugin
 * is confirmed enabled, so classes that reference the other plugin's API are never loaded
 * when it's absent — that's what keeps these soft dependencies truly soft.
 */
interface Hook {
    /** The plugin(s) this hook integrates with; enabled when any of them is present. */
    val plugins: List<String>

    /** @return a short description of what was enabled, or null if nothing could be. */
    fun enable(core: HanamiCore): String?

    fun disable() {}
}

/** Discovers installed soft dependencies and enables the matching hooks. */
class HookManager(private val core: HanamiCore) {

    private val factories: List<Pair<List<String>, () -> Hook>> = listOf(
        listOf("Vault") to { VaultHook() },
        listOf("PlaceholderAPI") to { PlaceholderHook() },
        VanishHook.PLUGINS to { VanishHook() },
    )

    private val active = mutableListOf<Hook>()

    fun enableAll() {
        val pm = core.plugin.server.pluginManager
        for ((plugins, factory) in factories) {
            if (plugins.none(pm::isPluginEnabled)) continue
            try {
                val hook = factory()
                val result = hook.enable(core) ?: continue
                active += hook
                core.plugin.logger.info("Hooked into $result")
            } catch (e: LinkageError) {
                core.plugin.logger.log(Level.WARNING, "Incompatible version of ${plugins.joinToString("/")}; integration disabled", e)
            } catch (e: RuntimeException) {
                core.plugin.logger.log(Level.WARNING, "Could not hook into ${plugins.joinToString("/")}", e)
            }
        }
    }

    fun disableAll() {
        active.forEach(Hook::disable)
        active.clear()
    }
}
