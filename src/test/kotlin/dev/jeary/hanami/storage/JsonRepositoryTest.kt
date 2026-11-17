package dev.jeary.hanami.storage

import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.storage.json.JsonProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.UUID

/** The default backend: one JSON file per player. */
class JsonRepositoryTest {

    @TempDir
    lateinit var dir: File

    private lateinit var folder: File
    private lateinit var repo: ProfileRepository

    private val id: UUID = UUID.randomUUID()

    @BeforeEach
    fun open() {
        folder = File(dir, "players")
        repo = JsonProfileRepository(folder).also { it.init() }
    }

    @Test
    fun `unknown players load as null`() {
        assertNull(repo.load(id))
    }

    @Test
    fun `profiles round-trip, keeping unlock order`() {
        val profile = StoredProfile(
            uuid = id,
            equipped = mapOf("trail" to "sakura_drift", "wings" to "tsuru"),
            unlocked = linkedSetOf("tsuru", "torii", "fude"),
            showOthers = false,
            density = DensityLevel.LOW,
        )
        repo.save(profile)
        val loaded = repo.load(id)
        assertEquals(profile, loaded)
        assertEquals(listOf("tsuru", "torii", "fude"), loaded?.unlocked?.toList())
    }

    @Test
    fun `saving again replaces the file and leaves no temp file behind`() {
        repo.save(StoredProfile(id, mapOf("trail" to "a", "aura" to "b"), setOf("x", "y"), true, DensityLevel.HIGH))
        repo.save(StoredProfile(id, mapOf("trail" to "c"), setOf("y"), true, DensityLevel.MEDIUM))
        val loaded = repo.load(id)
        assertEquals(mapOf("trail" to "c"), loaded?.equipped)
        assertEquals(setOf("y"), loaded?.unlocked)
        assertEquals(DensityLevel.MEDIUM, loaded?.density)
        assertEquals(listOf("$id.json"), folder.list()?.toList())
    }

    @Test
    fun `offline unlocks can be added and removed without an existing file`() {
        repo.addUnlock(id, "petal_storm")
        repo.addUnlock(id, "petal_storm") // idempotent
        assertEquals(setOf("petal_storm"), repo.load(id)?.unlocked)
        assertTrue(repo.removeUnlock(id, "petal_storm"))
        assertFalse(repo.removeUnlock(id, "petal_storm"))
        assertFalse(repo.removeUnlock(UUID.randomUUID(), "petal_storm"))
    }

    @Test
    fun `data survives a restart`() {
        repo.save(StoredProfile(id, emptyMap(), setOf("kept"), true, DensityLevel.HIGH))
        repo.close()
        repo = JsonProfileRepository(folder).also { it.init() }
        assertEquals(setOf("kept"), repo.load(id)?.unlocked)
    }

    @Test
    fun `hand-edited files with missing keys fall back to defaults`() {
        File(folder, "$id.json").writeText("""{ "unlocked": ["torii"] }""")
        val loaded = repo.load(id)
        assertEquals(setOf("torii"), loaded?.unlocked)
        assertEquals(emptyMap<String, String>(), loaded?.equipped)
        assertTrue(loaded?.showOthers == true)
        assertEquals(DensityLevel.HIGH, loaded?.density)
    }

    @Test
    fun `a corrupt file is reported instead of being treated as a new player`() {
        File(folder, "$id.json").writeText("{ not json")
        // ProfileService turns this into a read-only profile, so the file is never overwritten.
        assertThrows(IOException::class.java) { repo.load(id) }
    }
}
