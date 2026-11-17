package dev.jeary.hanami.core.profile

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.api.player.HanamiProfile
import dev.jeary.hanami.config.PlayerDefaults
import dev.jeary.hanami.storage.StoredProfile
import java.util.EnumMap
import java.util.UUID

/**
 * The live, mutable profile of an online player. Mutated only on the main thread; the storage
 * thread receives immutable [StoredProfile] copies.
 */
class PlayerProfile(
    override val uuid: UUID,
    equipped: Map<CosmeticType, CosmeticId>,
    unlocked: Set<CosmeticId>,
    showOthers: Boolean,
    density: DensityLevel,
    /** Set when loading failed: the player can still play, but we never overwrite their data. */
    val readOnly: Boolean = false,
) : HanamiProfile {

    private val equippedMap = EnumMap<CosmeticType, CosmeticId>(CosmeticType::class.java).apply { putAll(equipped) }
    private val unlockedSet = LinkedHashSet(unlocked)

    @Volatile
    var dirty = false
        private set

    override val equipped: Map<CosmeticType, CosmeticId> get() = equippedMap.toMap()
    override val unlocked: Set<CosmeticId> get() = unlockedSet.toSet()

    override var showOthers: Boolean = showOthers
        set(value) {
            if (field != value) dirty = true
            field = value
        }

    override var density: DensityLevel = density
        set(value) {
            if (field != value) dirty = true
            field = value
        }

    fun equippedId(type: CosmeticType): CosmeticId? = equippedMap[type]

    fun equip(type: CosmeticType, id: CosmeticId) {
        if (equippedMap.put(type, id) != id) dirty = true
    }

    fun unequip(type: CosmeticType): CosmeticId? = equippedMap.remove(type)?.also { dirty = true }

    fun unequipAll() {
        if (equippedMap.isNotEmpty()) dirty = true
        equippedMap.clear()
    }

    fun hasUnlocked(id: CosmeticId) = id in unlockedSet

    fun unlock(id: CosmeticId): Boolean = unlockedSet.add(id).also { if (it) dirty = true }

    fun revoke(id: CosmeticId): Boolean = unlockedSet.remove(id).also { if (it) dirty = true }

    fun markClean() {
        dirty = false
    }

    fun toStored() = StoredProfile(
        uuid = uuid,
        equipped = equippedMap.entries.associate { (type, id) -> type.id to id.value },
        unlocked = unlockedSet.mapTo(LinkedHashSet()) { it.value },
        showOthers = showOthers,
        density = density,
    )

    companion object {
        fun fresh(uuid: UUID, defaults: PlayerDefaults, readOnly: Boolean = false) =
            PlayerProfile(uuid, emptyMap(), emptySet(), defaults.showOthers, defaults.density, readOnly)

        /** Rebuilds a profile from storage, silently dropping ids that are no longer valid. */
        fun fromStored(stored: StoredProfile) = PlayerProfile(
            uuid = stored.uuid,
            equipped = stored.equipped.mapNotNull { (type, id) ->
                val t = CosmeticType.byId(type) ?: return@mapNotNull null
                val c = CosmeticId.parse(id) ?: return@mapNotNull null
                t to c
            }.toMap(),
            unlocked = stored.unlocked.mapNotNullTo(LinkedHashSet(), CosmeticId::parse),
            showOthers = stored.showOthers,
            density = stored.density,
        )
    }
}
