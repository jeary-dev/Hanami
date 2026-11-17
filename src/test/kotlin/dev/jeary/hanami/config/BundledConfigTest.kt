package dev.jeary.hanami.config

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.player.DensityLevel
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** The files shipped in the jar must always parse cleanly — they're the fallback for broken configs. */
class BundledConfigTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun startServer() {
            MockBukkit.mock()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            MockBukkit.unmock()
        }
    }

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text) }
    private fun bundled(name: String) = yaml(requireNotNull(javaClass.getResource("/$name")).readText())

    @Test
    fun `config yml parses with the documented defaults`() {
        val errors = mutableListOf<ConfigError>()
        val settings = HanamiSettings.parse(ConfigNode("config.yml", bundled("config.yml"), "", errors))
        assertEquals(emptyList<ConfigError>(), errors)
        assertInstanceOf(StorageSettings.Json::class.java, settings.storage)
        assertEquals(1, settings.render.updateInterval)
        assertEquals(DensityLevel.HIGH, settings.defaults.density)
        assertTrue(settings.render.asyncCompute)
    }

    @Test
    fun `out of range settings are reported with their path`() {
        val errors = mutableListOf<ConfigError>()
        HanamiSettings.parse(
            ConfigNode(
                "config.yml",
                yaml(
                    """
                    rendering:
                      update-interval: 0
                      view-distance: lots
                    player-defaults:
                      density: ultra
                    """.trimIndent(),
                ),
                "",
                errors,
            ),
        )
        val text = errors.joinToString("\n")
        assertTrue(text.contains("config.yml → rendering.update-interval: expected a whole number between 1 and 20, got 0"), text)
        assertTrue(text.contains("config.yml → rendering.view-distance: expected a number between 4 and 256, got \"lots\""), text)
        assertTrue(text.contains("config.yml → player-defaults.density: unknown value \"ultra\" (expected one of: low, medium, high)"), text)
    }

    @Test
    fun `mysql settings are read, and the password never appears in toString`() {
        val settings = StorageSettings.parse(
            ConfigNode(
                "config.yml",
                yaml(
                    """
                    type: mysql
                    mysql:
                      host: db.example
                      port: 3307
                      password: hunter2
                    """.trimIndent(),
                ),
                "storage",
                mutableListOf(),
            ),
        )
        val mysql = assertInstanceOf(StorageSettings.MySql::class.java, settings)
        assertEquals("db.example", mysql.host)
        assertEquals(3307, mysql.port)
        assertTrue("hunter2" !in mysql.toString())
    }

    @Test
    fun `menus yml parses and every layout is a valid grid`() {
        val errors = mutableListOf<ConfigError>()
        val menus = MenuConfig.parse(ConfigNode("menus.yml", bundled("menus.yml"), "", errors))
        assertEquals(emptyList<ConfigError>(), errors.filter { it.isError })
        for (layout in listOf(menus.main, menus.category, menus.settings, menus.confirm)) {
            assertTrue(layout.rows.all { it.length == 9 })
        }
        assertEquals(21, menus.category.contentSlots.size)
        assertEquals(CosmeticType.entries.toSet(), menus.categoryNames.keys)
    }

    @Test
    fun `menu layout errors point at the offending row`() {
        val errors = mutableListOf<ConfigError>()
        MenuConfig.parse(
            ConfigNode(
                "menus.yml",
                yaml(
                    """
                    filler: { material: BLACK_STAINED_GLASS_PANE }
                    main:
                      layout: ['#####', '#########']
                    """.trimIndent(),
                ),
                "",
                errors,
            ),
        )
        assertTrue(errors.any { it.toString() == "menus.yml → main.layout[0]: each row must be exactly 9 characters, \"#####\" has 5" }, errors.toString())
    }

    @Test
    fun `every bundled message is valid MiniMessage`() {
        val messages = Messages.flatten(bundled("messages.yml"))
        val mini = MiniMessage.builder().strict(false).build()
        assertTrue(messages.size > 30)
        messages.forEach { (key, raw) ->
            runCatching { mini.deserialize(raw) }.onFailure { throw AssertionError("messages.yml → $key: ${it.message}") }
        }
    }
}
