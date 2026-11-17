package dev.jeary.hanami.api.player

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import java.util.UUID

/** A read-only view of an online player's Hanami data. */
interface HanamiProfile {
    val uuid: UUID

    /** Equipped cosmetic id per type. Ids may refer to cosmetics removed from the config. */
    val equipped: Map<CosmeticType, CosmeticId>

    /** Cosmetics granted by an admin or bought (permission/default unlocks are not listed). */
    val unlocked: Set<CosmeticId>

    /** Whether this player sees other players' cosmetics. */
    val showOthers: Boolean

    val density: DensityLevel
}
