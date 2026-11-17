package dev.jeary.hanami.storage.sql

import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.storage.ProfileRepository
import dev.jeary.hanami.storage.StoredProfile
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation shared by SQLite and MySQL; only [SqlDialect] differs. Blocking by design —
 * [dev.jeary.hanami.core.profile.ProfileService] always calls it from the storage thread.
 *
 * Every save is one transaction, so a crash mid-write can never leave a profile half-saved.
 */
class SqlProfileRepository(
    private val dataSource: DataSource,
    private val dialect: SqlDialect,
    private val onClose: () -> Unit = {},
) : ProfileRepository {

    override val name: String get() = dialect.name

    override fun init() {
        dataSource.connection.use { Migrations.migrate(it, dialect) }
    }

    override fun load(uuid: UUID): StoredProfile? = dataSource.connection.use { c ->
        val id = uuid.toString()
        val settings = c.prepareStatement("SELECT show_others, density FROM hanami_players WHERE uuid = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) to rs.getString(2) else null }
        }
        val equipped = c.prepareStatement("SELECT slot, cosmetic FROM hanami_equipped WHERE uuid = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
        }
        val unlocked = c.prepareStatement("SELECT cosmetic FROM hanami_unlocks WHERE uuid = ? ORDER BY unlocked_at").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        if (settings == null && equipped.isEmpty() && unlocked.isEmpty()) return@use null
        StoredProfile(
            uuid = uuid,
            equipped = equipped,
            unlocked = unlocked,
            showOthers = settings?.first ?: true,
            density = settings?.second?.let(DensityLevel::byId) ?: DensityLevel.HIGH,
        )
    }

    override fun save(profile: StoredProfile): Unit = transaction { c ->
        val id = profile.uuid.toString()
        c.prepareStatement(
            dialect.upsert(
                "hanami_players",
                columns = listOf("uuid", "show_others", "density", "updated_at"),
                keyColumns = listOf("uuid"),
                updateColumns = listOf("show_others", "density", "updated_at"),
            ),
        ).use { st ->
            st.setString(1, id)
            st.setBoolean(2, profile.showOthers)
            st.setString(3, profile.density.id)
            st.setLong(4, System.currentTimeMillis())
            st.executeUpdate()
        }

        c.prepareStatement("DELETE FROM hanami_equipped WHERE uuid = ?").use { st ->
            st.setString(1, id)
            st.executeUpdate()
        }
        if (profile.equipped.isNotEmpty()) {
            c.prepareStatement("INSERT INTO hanami_equipped (uuid, slot, cosmetic) VALUES (?, ?, ?)").use { st ->
                for ((slot, cosmetic) in profile.equipped) {
                    st.setString(1, id)
                    st.setString(2, slot)
                    st.setString(3, cosmetic)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }

        // Keep unlock timestamps: insert new unlocks, delete only the ones that were removed.
        insertUnlocks(c, id, profile.unlocked)
        val keep = profile.unlocked.toList()
        val sql = if (keep.isEmpty()) "DELETE FROM hanami_unlocks WHERE uuid = ?"
        else "DELETE FROM hanami_unlocks WHERE uuid = ? AND cosmetic NOT IN (${keep.joinToString { "?" }})"
        c.prepareStatement(sql).use { st ->
            st.setString(1, id)
            keep.forEachIndexed { i, cosmetic -> st.setString(i + 2, cosmetic) }
            st.executeUpdate()
        }
    }

    override fun addUnlock(uuid: UUID, cosmeticId: String): Unit = transaction { c ->
        insertUnlocks(c, uuid.toString(), setOf(cosmeticId))
    }

    override fun removeUnlock(uuid: UUID, cosmeticId: String): Boolean = dataSource.connection.use { c ->
        c.prepareStatement("DELETE FROM hanami_unlocks WHERE uuid = ? AND cosmetic = ?").use { st ->
            st.setString(1, uuid.toString())
            st.setString(2, cosmeticId)
            st.executeUpdate() > 0
        }
    }

    override fun close() = onClose()

    private fun insertUnlocks(c: Connection, id: String, cosmetics: Set<String>) {
        if (cosmetics.isEmpty()) return
        c.prepareStatement(dialect.insertIgnore("hanami_unlocks", listOf("uuid", "cosmetic", "unlocked_at"))).use { st ->
            val now = System.currentTimeMillis()
            for (cosmetic in cosmetics) {
                st.setString(1, id)
                st.setString(2, cosmetic)
                st.setLong(3, now)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private inline fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { c ->
        val previous = c.autoCommit
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = previous
        }
    }
}
