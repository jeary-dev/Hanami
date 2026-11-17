package dev.jeary.hanami.storage

import dev.jeary.hanami.api.player.DensityLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/** Exercises the real JDBC code against a throwaway SQLite file. */
class SqliteRepositoryTest {

    @TempDir
    lateinit var dir: File

    private lateinit var repo: ProfileRepository

    @BeforeEach
    fun open() {
        repo = StorageFactory.sqlite(File(dir, "test.db")).also { it.init() }
    }

    @AfterEach
    fun close() = repo.close()

    private val id: UUID = UUID.randomUUID()

    @Test
    fun `unknown players load as null`() {
        assertNull(repo.load(id))
    }

    @Test
    fun `profiles round-trip`() {
        val profile = StoredProfile(
            uuid = id,
            equipped = mapOf("trail" to "sakura_drift", "wings" to "tsuru"),
            unlocked = setOf("tsuru", "torii"),
            showOthers = false,
            density = DensityLevel.LOW,
        )
        repo.save(profile)
        assertEquals(profile, repo.load(id))
    }

    @Test
    fun `saving again replaces equipped cosmetics and removed unlocks`() {
        repo.save(StoredProfile(id, mapOf("trail" to "a", "aura" to "b"), setOf("x", "y"), true, DensityLevel.HIGH))
        repo.save(StoredProfile(id, mapOf("trail" to "c"), setOf("y"), true, DensityLevel.MEDIUM))
        val loaded = repo.load(id)
        assertEquals(mapOf("trail" to "c"), loaded?.equipped)
        assertEquals(setOf("y"), loaded?.unlocked)
        assertEquals(DensityLevel.MEDIUM, loaded?.density)
    }

    @Test
    fun `offline unlocks can be added and removed without a profile row`() {
        repo.addUnlock(id, "petal_storm")
        repo.addUnlock(id, "petal_storm") // idempotent
        assertEquals(setOf("petal_storm"), repo.load(id)?.unlocked)
        assertTrue(repo.removeUnlock(id, "petal_storm"))
        assertFalse(repo.removeUnlock(id, "petal_storm"))
    }

    @Test
    fun `migrations are idempotent across restarts`() {
        repo.save(StoredProfile(id, emptyMap(), setOf("kept"), true, DensityLevel.HIGH))
        repo.close()
        repo = StorageFactory.sqlite(File(dir, "test.db")).also { it.init() }
        assertEquals(setOf("kept"), repo.load(id)?.unlocked)
    }
}
