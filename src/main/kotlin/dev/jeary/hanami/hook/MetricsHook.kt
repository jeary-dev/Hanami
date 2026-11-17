package dev.jeary.hanami.hook

import dev.jeary.hanami.core.HanamiCore
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart

/** Anonymous bStats metrics (relocated into Hanami's jar). Disabled with `metrics: false`. */
object MetricsHook {

    /**
     * The plugin id from https://bstats.org/what-is-my-plugin-id.
     * TODO(jeary-dev): register Hanami on bStats and put the real id here; 0 disables metrics.
     */
    private const val BSTATS_PLUGIN_ID = 0

    fun start(core: HanamiCore): Metrics? {
        if (!core.config.settings.metrics || BSTATS_PLUGIN_ID <= 0) return null
        return Metrics(core.plugin, BSTATS_PLUGIN_ID).apply {
            addCustomChart(SimplePie("storage_backend") { core.profiles.backendName })
            addCustomChart(SimplePie("server_platform") { core.platform.platform.displayName })
            addCustomChart(SingleLineChart("loaded_cosmetics") { core.registry.size })
            addCustomChart(SimplePie("economy") { core.economy?.name ?: "none" })
        }
    }
}
