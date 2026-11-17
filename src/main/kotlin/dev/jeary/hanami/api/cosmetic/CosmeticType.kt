package dev.jeary.hanami.api.cosmetic

/** The slot a cosmetic occupies. A player can equip one cosmetic per type. */
enum class CosmeticType(val id: String) {
    TRAIL("trail"),
    AURA("aura"),
    WINGS("wings"),
    PROJECTILE("projectile"),
    EMOTE("emote");

    companion object {
        fun byId(id: String): CosmeticType? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}
