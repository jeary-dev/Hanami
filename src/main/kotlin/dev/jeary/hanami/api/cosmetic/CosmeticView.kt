package dev.jeary.hanami.api.cosmetic

/**
 * A read-only view of a loaded cosmetic.
 *
 * Text is exposed as raw MiniMessage / plain strings rather than Adventure components because
 * Hanami relocates its Adventure copy; strings are safe to pass across plugin boundaries.
 */
interface CosmeticView {
    val id: CosmeticId
    val type: CosmeticType

    /** Display name in MiniMessage format, e.g. `<gradient:#ffb7c5:#ff8fab>Sakura Drift`. */
    val displayName: String

    /** Display name with all formatting stripped. */
    val plainName: String

    /** Permission that unlocks this cosmetic. */
    val permission: String

    /** Price for purchasing with Vault, or `null` when it cannot be bought. */
    val price: Double?

    /** True when every player owns this cosmetic without any permission or purchase. */
    val unlockedByDefault: Boolean
}
