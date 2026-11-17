package dev.jeary.hanami.config

import dev.jeary.hanami.cosmetics.CosmeticRegistry
import dev.jeary.hanami.math.shape.ShapeCache
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Everything loaded from the four config files, as one immutable unit. */
data class LoadedConfig(
    val settings: HanamiSettings,
    val cosmetics: CosmeticRegistry,
    val messages: Messages,
    val menus: MenuConfig,
    /** Non-fatal problems (skipped cosmetics, warnings) worth showing to the admin. */
    val problems: List<ConfigError>,
)

/**
 * Loads and validates all configuration. Loading is side-effect free: it builds a complete
 * [LoadedConfig] or reports why it can't, and the caller decides whether to swap it in.
 * That is what makes `/hanami reload` safe — a typo can never leave the plugin half-reloaded.
 *
 * Fatal (reload refused): YAML syntax errors, invalid values in config.yml / menus.yml /
 * messages.yml. Non-fatal: a broken cosmetic, which is reported and skipped.
 */
class ConfigService(
    private val plugin: Plugin,
    private val shapeCache: ShapeCache,
) {
    private val dataFolder: File get() = plugin.dataFolder

    fun load(): ValidationResult<LoadedConfig> = load(fromJar = false)

    /** The bundled defaults, used when the user's files are broken at startup. */
    fun loadBundledDefaults(): ValidationResult<LoadedConfig> = load(fromJar = true)

    private fun load(fromJar: Boolean): ValidationResult<LoadedConfig> {
        val fatal = mutableListOf<ConfigError>()
        val problems = mutableListOf<ConfigError>()

        fun file(name: String): YamlConfiguration? =
            if (fromJar) bundled(name) else read(name, fatal)

        val configYaml = file(CONFIG)
        val cosmeticsYaml = file(COSMETICS)
        val messagesYaml = file(MESSAGES)
        val menusYaml = file(MENUS)
        if (configYaml == null || cosmeticsYaml == null || messagesYaml == null || menusYaml == null) {
            return ValidationResult.Invalid(fatal)
        }

        // config.yml: missing keys fall back to the bundled defaults
        bundled(CONFIG)?.let(configYaml::setDefaults)
        val settingsSink = mutableListOf<ConfigError>()
        val settings = HanamiSettings.parse(ConfigNode(CONFIG, configYaml, "", settingsSink))
        fatal += settingsSink.filter { it.isError }
        problems += settingsSink.filterNot { it.isError }

        // messages.yml
        val messagesSink = mutableListOf<ConfigError>()
        val defaults = bundled(MESSAGES)?.let(Messages::flatten) ?: emptyMap()
        val messages = Messages.parse(ConfigNode(MESSAGES, messagesYaml, "", messagesSink), messagesYaml, defaults)
        fatal += messagesSink.filter { it.isError }
        problems += messagesSink.filterNot { it.isError }

        // menus.yml
        val menusSink = mutableListOf<ConfigError>()
        val menus = MenuConfig.parse(ConfigNode(MENUS, menusYaml, "", menusSink))
        fatal += menusSink.filter { it.isError }
        problems += menusSink.filterNot { it.isError }

        // cosmetics.yml — per-cosmetic errors are non-fatal
        val cosmeticsSink = mutableListOf<ConfigError>()
        val parsed = CosmeticParser(shapeCache).parseAll(ConfigNode(COSMETICS, cosmeticsYaml, "", cosmeticsSink))
        problems += cosmeticsSink + parsed.problems

        if (fatal.isNotEmpty()) return ValidationResult.Invalid(fatal + problems.filter { it.isError })
        return ValidationResult.Valid(
            LoadedConfig(settings, CosmeticRegistry(parsed.cosmetics), messages, menus, problems),
            problems,
        )
    }

    /** Copies any missing default file into the data folder. */
    fun saveDefaults() {
        for (name in ALL_FILES) {
            if (!File(dataFolder, name).exists()) plugin.saveResource(name, false)
        }
    }

    private fun read(name: String, errors: MutableList<ConfigError>): YamlConfiguration? {
        val file = File(dataFolder, name)
        if (!file.exists()) plugin.saveResource(name, false)
        return try {
            YamlConfiguration().apply { load(file) }
        } catch (e: InvalidConfigurationException) {
            // SnakeYAML messages carry line/column; keep the first meaningful lines.
            val detail = e.message?.lineSequence()?.filter { it.isNotBlank() }?.take(3)?.joinToString(" ")?.trim()
            errors += ConfigError(name, "", "YAML syntax error — ${detail ?: "unknown"}")
            null
        } catch (e: java.io.IOException) {
            errors += ConfigError(name, "", "could not read file: ${e.message}")
            null
        }
    }

    private fun bundled(name: String): YamlConfiguration? =
        plugin.getResource(name)?.use { stream ->
            YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
        }

    companion object {
        const val CONFIG = "config.yml"
        const val COSMETICS = "cosmetics.yml"
        const val MESSAGES = "messages.yml"
        const val MENUS = "menus.yml"
        val ALL_FILES = listOf(CONFIG, COSMETICS, MESSAGES, MENUS)
    }
}
