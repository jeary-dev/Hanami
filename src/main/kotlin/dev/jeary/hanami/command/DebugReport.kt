package dev.jeary.hanami.command

import dev.jeary.hanami.command.HanamiCommands.Companion.formatMs
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.Messenger.Companion.tag
import org.bukkit.command.CommandSender
import java.util.Locale

/**
 * `/hanami debug` — the numbers that prove the cosmetics are cheap. "Main thread" is everything
 * Hanami does on the server thread per tick (snapshot + particle sends); "compute" is the maths
 * that runs on Hanami's own thread in parallel.
 */
class DebugReport(private val core: HanamiCore) {

    fun send(sender: CommandSender) {
        val m = core.metrics.summary()
        val settings = core.config.settings.render
        val tickBudgetPercent = m.mainAvgMs / TICK_MS * 100.0
        val messenger = core.messenger

        messenger.send(sender, "debug.header", tag("samples", m.samples))
        fun line(label: String, value: String) = messenger.send(sender, "debug.line", tag("label", label), tag("value", value))

        val platform = core.platform
        line("Platform", platform.platform.displayName + platform.features.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty())
        line(
            "Main thread",
            "avg ${formatMs(m.mainAvgMs)} ms · p95 ${formatMs(m.mainP95Ms)} ms · max ${formatMs(m.mainMaxMs)} ms " +
                "(${String.format(Locale.ROOT, "%.2f", tickBudgetPercent)}% of a tick)",
        )
        line(
            "Compute",
            "avg ${formatMs(m.computeAvgMs)} ms · p95 ${formatMs(m.computeP95Ms)} ms " +
                if (settings.asyncCompute) "(off-thread)" else "(main thread)",
        )
        line("Particles/tick", "avg ${m.particlesAvg.fmt()} · peak ${m.particlesMax} · budget ${settings.globalBudget}")
        line("Points/tick", "requested ${m.requestedAvg.fmt()} → drawn ${m.pointsAvg.fmt()} (per-player cap ${settings.perPlayerBudget})")
        line("Active", "${core.metrics.lastSources} players · ${core.metrics.lastProjectiles} projectiles · ${core.metrics.lastEffects} effects")
        line("Late frames", "${m.lateFrames} (frames skipped because compute overran a tick)")
        line("Shape cache", "${core.shapes.size} shapes · ${core.shapes.totalPoints} points")
        line("Cosmetics", "${core.registry.size} loaded")
        line("Storage", "${core.profiles.backendName} · ${core.profiles.cachedCount} profiles cached")
        core.platform.averageTickTimeMs(core.plugin.server)?.let { line("Server MSPT", formatMs(it) + " ms") }
    }

    private fun Double.fmt() = String.format(Locale.ROOT, "%.1f", this)

    private companion object {
        const val TICK_MS = 50.0
    }
}
