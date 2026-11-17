package dev.jeary.hanami.storage

import dev.jeary.hanami.api.player.DensityLevel
import java.util.UUID

/** The persisted form of a player's data. Ids are raw strings: storage doesn't validate config. */
data class StoredProfile(
    val uuid: UUID,
    val equipped: Map<String, String>,
    val unlocked: Set<String>,
    val showOthers: Boolean,
    val density: DensityLevel,
)

/**
 * Persistence for player profiles.
 *
 * Implementations are **blocking** and are only ever called from Hanami's storage thread; the
 * [dev.jeary.hanami.core.profile.ProfileService] owns the async orchestration and caching. Keeping
 * the repository synchronous makes implementations trivial to write and to test.
 */
interface ProfileRepository : AutoCloseable {
    /** Human-readable backend name for logs and `/hanami debug`. */
    val name: String

    /** Creates tables / runs migrations. Called once before any other method. */
    fun init()

    /** @return the stored profile, or null for a player Hanami has never seen. */
    fun load(uuid: UUID): StoredProfile?

    /** Inserts or fully replaces the profile. */
    fun save(profile: StoredProfile)

    /** Adds a single unlock without loading the profile (offline grants). */
    fun addUnlock(uuid: UUID, cosmeticId: String)

    /** @return true when the unlock existed. */
    fun removeUnlock(uuid: UUID, cosmeticId: String): Boolean
}

/** Volatile storage: used in tests, and as the fallback when the real backend cannot start. */
class InMemoryProfileRepository : ProfileRepository {
    private val profiles = java.util.concurrent.ConcurrentHashMap<UUID, StoredProfile>()

    override val name = "memory"

    override fun init() = Unit

    override fun load(uuid: UUID): StoredProfile? = profiles[uuid]

    override fun save(profile: StoredProfile) {
        profiles[profile.uuid] = profile
    }

    override fun addUnlock(uuid: UUID, cosmeticId: String) {
        profiles.compute(uuid) { _, existing ->
            val base = existing ?: StoredProfile(uuid, emptyMap(), emptySet(), true, DensityLevel.HIGH)
            base.copy(unlocked = base.unlocked + cosmeticId)
        }
    }

    override fun removeUnlock(uuid: UUID, cosmeticId: String): Boolean {
        var removed = false
        profiles.computeIfPresent(uuid) { _, existing ->
            removed = cosmeticId in existing.unlocked
            existing.copy(unlocked = existing.unlocked - cosmeticId)
        }
        return removed
    }

    override fun close() = Unit
}
