package dev.jeary.hanami.core

import dev.jeary.hanami.api.HanamiApi
import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.cosmetic.CosmeticView
import dev.jeary.hanami.api.player.HanamiProfile
import dev.jeary.hanami.config.ConfigError
import dev.jeary.hanami.config.ConfigService
import dev.jeary.hanami.config.LoadedConfig
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.compat.PlatformAdapter
import dev.jeary.hanami.core.effect.EffectManager
import dev.jeary.hanami.core.effect.PreviewService
import dev.jeary.hanami.core.profile.ProfileService
import dev.jeary.hanami.core.render.LayerRenderer
import dev.jeary.hanami.core.render.ParticleEmitter
import dev.jeary.hanami.core.tick.CosmeticTicker
import dev.jeary.hanami.core.tick.FrameCompute
import dev.jeary.hanami.core.tick.Snapshotter
import dev.jeary.hanami.core.unlock.EconomyProvider
import dev.jeary.hanami.core.unlock.UnlockOperations
import dev.jeary.hanami.core.unlock.UnlockService
import dev.jeary.hanami.core.visibility.CombatTracker
import dev.jeary.hanami.core.visibility.VisibilityRules
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.CosmeticRegistry
import dev.jeary.hanami.cosmetics.projectile.ProjectileTracker
import dev.jeary.hanami.math.shape.ShapeCache
import dev.jeary.hanami.storage.ProfileRepository
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicReference

/**
 * The composition root: builds every service once, wires them together, and implements the
 * public [HanamiApi]. No service reaches for a global — everything it needs is passed in here.
 */
class HanamiCore(
    val plugin: JavaPlugin,
    val platform: PlatformAdapter,
    val configService: ConfigService,
    val shapes: ShapeCache,
    initial: LoadedConfig,
    repository: ProfileRepository,
    modules: List<CosmeticModule>,
) : HanamiApi, Listener {

    private val configRef = AtomicReference(initial)
    val config: LoadedConfig get() = configRef.get()
    val registry: CosmeticRegistry get() = config.cosmetics

    val messenger = Messenger(plugin, initial.messages)
    val profiles = ProfileService(plugin, repository) { config.settings.defaults }
    val combat = CombatTracker()
    val visibility = VisibilityRules({ config.settings.visibility }, combat)
    val previews = PreviewService()
    val effects = EffectManager()
    /** Installed by the Vault hook when an economy plugin is present. */
    @Volatile
    var economy: EconomyProvider? = null

    val unlocks = UnlockService { economy != null }
    val unlockOps = UnlockOperations(plugin, profiles, unlocks) { economy }

    private val ticker: CosmeticTicker
    val metrics get() = ticker.metrics
    val currentTick: Long get() = ticker.tick

    val cosmetics = CosmeticService(
        profiles, unlocks, previews, effects, { registry }, { currentTick }, plugin.server.pluginManager,
    )

    val snapshotter: Snapshotter

    val projectiles = ProjectileTracker({ player -> snapshotter.projectileCosmetic(player, currentTick) }, { currentTick })

    /** Callbacks run after a successful reload (menus closing, hooks refreshing, ...). */
    private val reloadListeners = mutableListOf<() -> Unit>()

    init {
        snapshotter = Snapshotter(
            profiles, { registry }, { config.settings }, visibility, platform, previews, projectiles, effects,
        )
        ticker = CosmeticTicker(
            plugin, snapshotter, FrameCompute(modules, LayerRenderer(shapes)), ParticleEmitter(platform), effects,
            { config.settings.render },
        )
    }

    fun enable() {
        val pm = plugin.server.pluginManager
        pm.registerEvents(profiles, plugin)
        pm.registerEvents(combat, plugin)
        pm.registerEvents(projectiles, plugin)
        pm.registerEvents(this, plugin)
        profiles.loadOnlinePlayers()
        ticker.start()

        val autosaveTicks = config.settings.storage.autosaveMinutes * 60L * 20L
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { profiles.saveDirty() }, autosaveTicks, autosaveTicks)
    }

    fun disable() {
        ticker.shutdown()
        effects.clear()
        previews.clear()
        projectiles.clear()
        profiles.shutdown()
        messenger.close()
    }

    fun onReload(listener: () -> Unit) {
        reloadListeners += listener
    }

    /**
     * Re-reads all config files. Nothing changes unless *everything* validates; then the new
     * configuration is swapped in atomically, between ticks.
     */
    fun reload(): ValidationResult<LoadedConfig> {
        val result = configService.load()
        if (result is ValidationResult.Valid) {
            val loaded = result.value
            val previous = configRef.getAndSet(loaded)
            messenger.messages = loaded.messages
            shapes.retainOnly(loaded.cosmetics.shapes())
            ticker.applySettings()
            if (previous.settings.storage != loaded.settings.storage) {
                plugin.logger.warning("Storage settings changed — restart the server to switch storage backends.")
            }
            reloadListeners.forEach { it() }
        }
        return result
    }

    fun logProblems(problems: List<ConfigError>) {
        problems.forEach { problem ->
            if (problem.isError) plugin.logger.severe(problem.toString()) else plugin.logger.warning(problem.toString())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        snapshotter.forget(uuid)
        cosmetics.forget(uuid)
    }

    // ── HanamiApi ────────────────────────────────────────────────────────

    override fun cosmetics(): List<CosmeticView> = registry.all

    override fun cosmetics(type: CosmeticType): List<CosmeticView> = registry.ofType(type)

    override fun cosmetic(id: CosmeticId): CosmeticView? = registry[id]

    override fun profile(player: Player): HanamiProfile? = profiles[player]

    override fun isUnlocked(player: Player, cosmetic: CosmeticView): Boolean =
        definitionOf(cosmetic)?.let { unlocks.isUnlocked(player, it, profiles[player]) } ?: false

    override fun equip(player: Player, cosmetic: CosmeticView, bypassLock: Boolean): Boolean {
        val definition = definitionOf(cosmetic) ?: return false
        return cosmetics.equip(player, definition, bypassLock) is EquipResult.Equipped
    }

    override fun unequip(player: Player, type: CosmeticType): Boolean =
        cosmetics.unequip(player, type) is UnequipResult.Unequipped

    override fun preview(player: Player, cosmetic: CosmeticView, seconds: Int) {
        definitionOf(cosmetic)?.let { cosmetics.preview(player, it, seconds.coerceIn(1, 60)) }
    }

    private fun definitionOf(view: CosmeticView): CosmeticDefinition? = registry[view.id]
}
