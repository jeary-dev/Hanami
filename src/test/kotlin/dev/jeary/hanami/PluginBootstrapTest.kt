package dev.jeary.hanami

import dev.jeary.hanami.api.HanamiApi
import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.event.CosmeticEquipEvent
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.menu.Menu
import dev.jeary.hanami.menu.MenuService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

/**
 * The real plugin booted in MockBukkit: config loading, JSON storage (the default), profiles, the API,
 * events and menus. Only command registration is skipped — Incendo Cloud needs real CraftBukkit
 * internals that MockBukkit doesn't provide.
 */
open class TestHanamiPlugin : HanamiPlugin() {
    override fun registerCommands(core: HanamiCore, menus: MenuService) = Unit
}

class PluginBootstrapTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: TestHanamiPlugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestHanamiPlugin::class.java)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun api() = requireNotNull(HanamiApi.get()) { "API not registered" }

    private fun joinedPlayer(): PlayerMock {
        val player = server.addPlayer()
        server.scheduler.performTicks(5) // profile load completes on the storage thread, then main
        repeat(50) {
            if (api().profile(player) != null) return player
            Thread.sleep(20)
            server.scheduler.performOneTick()
        }
        error("profile never loaded")
    }

    @Test
    fun `the plugin enables with every bundled cosmetic and registers its API`() {
        assertTrue(plugin.isEnabled)
        assertEquals(15, api().cosmetics().size)
        CosmeticType.entries.forEach { assertTrue(api().cosmetics(it).isNotEmpty(), "no $it") }
    }

    @Test
    fun `default cosmetics can be equipped, locked ones cannot`() {
        val player = joinedPlayer()
        val free = requireNotNull(api().cosmetic(CosmeticId.of("sakura_drift")))
        val paid = requireNotNull(api().cosmetic(CosmeticId.of("tsuru")))

        assertTrue(api().equip(player, free))
        assertEquals(free.id, api().profile(player)?.equipped?.get(CosmeticType.TRAIL))
        assertFalse(api().equip(player, paid))
        assertTrue(api().equip(player, paid, bypassLock = true))
    }

    @Test
    fun `cancelling the equip event keeps the old cosmetic`() {
        val player = joinedPlayer()
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onEquip(event: CosmeticEquipEvent) {
                event.isCancelled = true
            }
        }, plugin)
        assertFalse(api().equip(player, requireNotNull(api().cosmetic(CosmeticId.of("sakura_drift")))))
        assertNull(api().profile(player)?.equipped?.get(CosmeticType.TRAIL))
    }

    @Test
    fun `settings and equips survive a quit and rejoin through JSON storage`() {
        val player = joinedPlayer()
        api().equip(player, requireNotNull(api().cosmetic(CosmeticId.of("hanafubuki"))))
        player.disconnect()
        server.scheduler.performTicks(2)

        assertNull(api().profile(player), "profile is evicted from the cache on quit")

        player.reconnect()
        for (attempt in 0 until 50) {
            if (api().profile(player) != null) break
            Thread.sleep(20)
            server.scheduler.performOneTick()
        }
        assertEquals("hanafubuki", api().profile(player)?.equipped?.get(CosmeticType.AURA)?.value)
    }

    @Test
    fun `the menu opens and clicking a category navigates`() {
        val player = joinedPlayer()
        requireNotNull(plugin.menus).openMain(player)
        val main = player.openInventory.topInventory
        assertInstanceOf(Menu::class.java, main.holder)

        // the trails button is the 't' in row 2 of the default layout
        val trailsSlot = 9 + 2
        val click = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, trailsSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        server.pluginManager.callEvent(click)
        assertTrue(click.isCancelled, "menu clicks must never move items")
        val category = player.openInventory.topInventory
        assertNotNull(category.getItem(10), "first cosmetic slot should hold a trail")
    }

    @Test
    fun `reload swaps in the new config atomically`() {
        val core = (api() as HanamiCore)
        val before = core.config
        val result = core.reload()
        assertInstanceOf(dev.jeary.hanami.config.ValidationResult.Valid::class.java, result)
        assertTrue(before !== core.config)
        assertEquals(15, api().cosmetics().size)
    }

    @Test
    fun `a broken config file is refused and the running config is kept`() {
        val core = (api() as HanamiCore)
        val before = core.config
        java.io.File(plugin.dataFolder, "config.yml").writeText("rendering:\n  update-interval: [oops\n")
        val result = core.reload()
        val invalid = assertInstanceOf(dev.jeary.hanami.config.ValidationResult.Invalid::class.java, result)
        assertTrue(invalid.errors.single().toString().startsWith("config.yml: YAML syntax error"), invalid.errors.toString())
        assertTrue(before === core.config)
    }
}
