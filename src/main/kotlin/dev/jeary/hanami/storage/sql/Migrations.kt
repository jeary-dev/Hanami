package dev.jeary.hanami.storage.sql

import java.sql.Connection

/**
 * Versioned, forward-only schema migrations. Each migration runs once, inside a transaction,
 * and bumps `hanami_schema.version`. New versions are appended — never edited.
 */
object Migrations {

    class Migration(val version: Int, val description: String, val statements: (SqlDialect) -> List<String>)

    val ALL: List<Migration> = listOf(
        Migration(1, "initial schema") {
            listOf(
                """
                CREATE TABLE IF NOT EXISTS hanami_players (
                    uuid        CHAR(36)    NOT NULL PRIMARY KEY,
                    show_others BOOLEAN     NOT NULL,
                    density     VARCHAR(16) NOT NULL,
                    updated_at  BIGINT      NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS hanami_equipped (
                    uuid     CHAR(36)    NOT NULL,
                    slot     VARCHAR(16) NOT NULL,
                    cosmetic VARCHAR(64) NOT NULL,
                    PRIMARY KEY (uuid, slot)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS hanami_unlocks (
                    uuid        CHAR(36)    NOT NULL,
                    cosmetic    VARCHAR(64) NOT NULL,
                    unlocked_at BIGINT      NOT NULL,
                    PRIMARY KEY (uuid, cosmetic)
                )
                """,
            )
        },
    )

    val LATEST: Int get() = ALL.maxOf { it.version }

    /** @return the migrations that were applied. */
    fun migrate(connection: Connection, dialect: SqlDialect): List<Migration> {
        connection.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS hanami_schema (version INT NOT NULL)") }
        val current = connection.createStatement().use { st ->
            st.executeQuery("SELECT MAX(version) FROM hanami_schema").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val pending = ALL.filter { it.version > current }.sortedBy { it.version }
        for (migration in pending) {
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    migration.statements(dialect).forEach { st.executeUpdate(it.trimIndent()) }
                }
                connection.prepareStatement("INSERT INTO hanami_schema (version) VALUES (?)").use {
                    it.setInt(1, migration.version)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw IllegalStateException("Migration ${migration.version} (${migration.description}) failed", e)
            } finally {
                connection.autoCommit = autoCommit
            }
        }
        return pending
    }
}
