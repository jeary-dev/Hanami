package dev.jeary.hanami

import dev.jeary.hanami.admin.AdminService
import dev.jeary.hanami.api.HanamiApi
import dev.jeary.hanami.command.HanamiCommands
import dev.jeary.hanami.command.Permissions
import dev.jeary.hanami.command.UnlockCommands
import dev.jeary.hanami.hook.HookManager
import dev.jeary.hanami.hook.MetricsHook
import dev.jeary.hanami.menu.MenuService
import org.bstats.bukkit.Metrics
import org.bukkit.entity.Player
import dev.jeary.hanami.config.ConfigService
import dev.jeary.hanami.config.LoadedConfig
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.compat.PlatformAdapter
import dev.jeary.hanami.cosmetics.aura.AuraModule
import dev.jeary.hanami.cosmetics.emote.EmoteListener
import dev.jeary.hanami.cosmetics.emote.EmoteModule
import dev.jeary.hanami.cosmetics.projectile.ProjectileModule
import dev.jeary.hanami.cosmetics.trail.TrailModule
import dev.jeary.hanami.cosmetics.wings.WingsModule
import dev.jeary.hanami.math.shape.ShapeCache
import dev.jeary.hanami.storage.StorageFactory
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Bootstrap only: detect the platform, load config, build [HanamiCore], register the API.
 * All behaviour lives in the services the core wires together.
 *
 * `open` so the MockBukkit bootstrap test can substitute command registration.
 */
open class HanamiPlugin : JavaPlugin() {

    private var core: HanamiCore? = null
    internal var menus: MenuService? = null
        private set
    internal var admin: AdminService? = null
        private set
    private var hooks: HookManager? = null
    private var metrics: Metrics? = null

    override fun onEnable() {
        val platform = PlatformAdapter.create()
        val shapes = ShapeCache()
        val configService = ConfigService(this, shapes)
        configService.saveDefaults()
        val config = loadConfig(configService)

        val core = HanamiCore(
            plugin = this,
            platform = platform,
            configService = configService,
            shapes = shapes,
            initial = config,
            repository = StorageFactory.create(config.settings.storage, dataFolder, logger),
            modules = listOf(TrailModule(), AuraModule(), WingsModule(), ProjectileModule(), EmoteModule()),
        )
        this.core = core
        core.enable()
        server.pluginManager.registerEvents(EmoteListener(core), this)

        hooks = HookManager(core).also { it.enableAll() }
        metrics = MetricsHook.start(core)

        val menus = MenuService(core).also { it.enable() }
        this.menus = menus
        admin = AdminService(core, menus).also { it.enable() }
        registerCommands(core, menus)

        server.servicesManager.register(HanamiApi::class.java, core, this, ServicePriority.Normal)
        logger.info("Hanami enabled on ${platform.platform.displayName} with ${config.cosmetics.size} cosmetics.")
    }

    override fun onDisable() {
        server.servicesManager.unregisterAll(this)
        metrics?.shutdown()
        hooks?.disableAll()
        menus?.disable()
        core?.disable()
        core = null
    }

    /**
     * Registers `/hanami`. Separate (and open) because Incendo Cloud needs a real CraftBukkit
     * server — the MockBukkit bootstrap test overrides this.
     */
    protected open fun registerCommands(core: HanamiCore, menus: MenuService) {
        val commands = HanamiCommands(core)
        commands.enable()
        UnlockCommands(core, commands).register()
        admin?.let { admin ->
            commands.register(
                commands.root.literal("admin")
                    .permission(Permissions.ADMIN)
                    .senderType(Player::class.java)
                    .handler { ctx -> admin.openAdmin(ctx.sender()) },
            )
        }
        // bare /hanami opens the menu for players and prints help for the console
        commands.register(
            commands.root.permission(Permissions.MENU).handler { ctx ->
                when (val sender = ctx.sender()) {
                    is Player -> menus.openMain(sender)
                    else -> core.messenger.send(sender, "help")
                }
            },
        )
    }

    /** Loads the user's config; if it is broken, explains why and starts with the bundled defaults. */
    private fun loadConfig(service: ConfigService): LoadedConfig {
        return when (val result = service.load()) {
            is ValidationResult.Valid -> {
                result.warnings.forEach { logger.warning(it.toString()) }
                result.value
            }
            is ValidationResult.Invalid -> {
                logger.severe("Your Hanami configuration has ${result.errors.size} problem(s); starting with the bundled defaults:")
                result.errors.forEach { logger.severe("  $it") }
                when (val fallback = service.loadBundledDefaults()) {
                    is ValidationResult.Valid -> fallback.value
                    is ValidationResult.Invalid -> error("Bundled configuration is invalid: ${fallback.errors}")
                }
            }
        }
    }
}
