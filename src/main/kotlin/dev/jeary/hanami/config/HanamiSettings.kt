package dev.jeary.hanami.config

import dev.jeary.hanami.api.player.DensityLevel

/** `config.yml`, parsed and validated. */
data class HanamiSettings(
    val storage: StorageSettings,
    val render: RenderSettings,
    val visibility: VisibilitySettings,
    val defaults: PlayerDefaults,
    val emotes: EmoteSettings,
    val metrics: Boolean,
) {
    companion object {
        fun parse(root: ConfigNode): HanamiSettings {
            val storage = root.node("storage")
            val render = root.node("rendering")
            val visibility = root.node("visibility")
            val defaults = root.node("player-defaults")
            val emotes = root.node("emotes")
            return HanamiSettings(
                storage = StorageSettings.parse(storage),
                render = RenderSettings(
                    updateInterval = render?.int("update-interval", 1, 1..20) ?: 1,
                    viewDistance = render?.double("view-distance", 48.0, 4.0..256.0) ?: 48.0,
                    perPlayerBudget = render?.int("particle-budget.per-player", 200, 1..10_000) ?: 200,
                    globalBudget = render?.int("particle-budget.global", 6_000, 1..1_000_000) ?: 6_000,
                    asyncCompute = render?.bool("async-compute", true) ?: true,
                ),
                visibility = VisibilitySettings(
                    hideInCombat = visibility?.bool("hide-in-combat", true) ?: true,
                    combatSeconds = visibility?.int("combat-seconds", 10, 1..600) ?: 10,
                    hideInvisible = visibility?.bool("hide-invisible", true) ?: true,
                    hideSpectators = visibility?.bool("hide-spectators", true) ?: true,
                    hideVanished = visibility?.bool("hide-vanished", true) ?: true,
                    worldBlacklist = visibility?.stringList("world-blacklist")?.map { it.lowercase() }?.toSet() ?: emptySet(),
                ),
                defaults = PlayerDefaults(
                    showOthers = defaults?.bool("show-others", true) ?: true,
                    density = defaults?.choice("density", DensityLevel.HIGH, DensityLevel.entries.map { it.id }, DensityLevel::byId)
                        ?: DensityLevel.HIGH,
                ),
                emotes = EmoteSettings(
                    onPlayerKill = emotes?.bool("on-player-kill", true) ?: true,
                    onMobKill = emotes?.bool("on-mob-kill", false) ?: false,
                ),
                metrics = root.bool("metrics", true),
            )
        }
    }
}

sealed interface StorageSettings {
    val autosaveMinutes: Int

    data class Json(val folder: String, override val autosaveMinutes: Int) : StorageSettings

    data class Sqlite(val file: String, override val autosaveMinutes: Int) : StorageSettings

    data class MySql(
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String,
        val poolSize: Int,
        val properties: Map<String, String>,
        override val autosaveMinutes: Int,
    ) : StorageSettings {
        override fun toString() = "MySql(host=$host, port=$port, database=$database, username=$username, password=****)"
    }

    companion object {
        fun parse(node: ConfigNode?): StorageSettings {
            val autosave = node?.int("autosave-minutes", 5, 1..1440) ?: 5
            val types = listOf("json", "sqlite", "mysql")
            val type = node?.choice("type", "json", types) { raw -> raw.trim().lowercase().takeIf { it in types } } ?: "json"
            if (type == "json" || node == null) {
                return Json(node?.string("json.folder", "players") ?: "players", autosave)
            }
            if (type == "sqlite") {
                return Sqlite(node.string("sqlite.file", "data.db"), autosave)
            }
            val mysql = node.node("mysql", required = true)
            return MySql(
                host = mysql?.string("host", "localhost") ?: "localhost",
                port = mysql?.int("port", 3306, 1..65535) ?: 3306,
                database = mysql?.string("database", "hanami") ?: "hanami",
                username = mysql?.string("username", "root") ?: "root",
                password = mysql?.string("password", "") ?: "",
                poolSize = mysql?.int("pool-size", 6, 1..64) ?: 6,
                properties = mysql?.node("properties")?.let { p -> p.keys().associateWith { p.string(it, "") } } ?: emptyMap(),
                autosaveMinutes = autosave,
            )
        }
    }
}

data class RenderSettings(
    val updateInterval: Int,
    val viewDistance: Double,
    val perPlayerBudget: Int,
    val globalBudget: Int,
    val asyncCompute: Boolean,
)

data class VisibilitySettings(
    val hideInCombat: Boolean,
    val combatSeconds: Int,
    val hideInvisible: Boolean,
    val hideSpectators: Boolean,
    val hideVanished: Boolean,
    val worldBlacklist: Set<String>,
)

data class PlayerDefaults(val showOthers: Boolean, val density: DensityLevel)

data class EmoteSettings(val onPlayerKill: Boolean, val onMobKill: Boolean)
