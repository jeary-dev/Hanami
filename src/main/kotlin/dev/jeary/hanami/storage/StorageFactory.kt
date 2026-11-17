package dev.jeary.hanami.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.jeary.hanami.config.StorageSettings
import dev.jeary.hanami.storage.json.JsonProfileRepository
import dev.jeary.hanami.storage.sql.SqlDialect
import dev.jeary.hanami.storage.sql.SqlProfileRepository
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Builds the configured [ProfileRepository]: JSON files, or SQLite/MySQL behind a HikariCP pool. */
object StorageFactory {

    /**
     * Creates and initialises the repository. If the database can't be reached the plugin still
     * starts, on volatile in-memory storage, with a loud error — cosmetics shouldn't take a
     * server down.
     */
    fun create(settings: StorageSettings, dataFolder: File, logger: Logger): ProfileRepository = try {
        val repository = when (settings) {
            is StorageSettings.Json -> JsonProfileRepository(File(dataFolder, settings.folder))
            is StorageSettings.Sqlite -> sqlite(File(dataFolder, settings.file))
            is StorageSettings.MySql -> mysql(settings)
        }
        repository.init()
        repository
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "Could not start ${settings::class.simpleName} storage — using in-memory storage. Data will NOT be saved!", e)
        InMemoryProfileRepository()
    }

    fun sqlite(file: File): ProfileRepository {
        file.parentFile?.mkdirs()
        val config = HikariConfig().apply {
            poolName = "Hanami-SQLite"
            driverClassName = SqlDialect.Sqlite.driverClass
            jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            // SQLite allows one writer; a single connection avoids SQLITE_BUSY entirely.
            maximumPoolSize = 1
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "NORMAL")
            addDataSourceProperty("busy_timeout", "5000")
        }
        val pool = HikariDataSource(config)
        return SqlProfileRepository(pool, SqlDialect.Sqlite, pool::close)
    }

    fun mysql(settings: StorageSettings.MySql): ProfileRepository {
        val properties = settings.properties.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val config = HikariConfig().apply {
            poolName = "Hanami-MySQL"
            driverClassName = SqlDialect.MySql.driverClass
            jdbcUrl = "jdbc:mysql://${settings.host}:${settings.port}/${settings.database}" +
                if (properties.isNotEmpty()) "?$properties" else ""
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.poolSize
            minimumIdle = minOf(2, settings.poolSize)
            connectionTimeout = 10_000
            maxLifetime = 1_800_000
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "64")
            addDataSourceProperty("useServerPrepStmts", "true")
        }
        val pool = HikariDataSource(config)
        return SqlProfileRepository(pool, SqlDialect.MySql, pool::close)
    }
}
