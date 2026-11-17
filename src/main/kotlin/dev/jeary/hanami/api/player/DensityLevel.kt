package dev.jeary.hanami.api.player

/**
 * How many of each cosmetic's particles a player wants to *see*. This is a per-viewer setting:
 * a player on a weak PC can pick LOW without changing what anyone else sees.
 */
enum class DensityLevel(val id: String, val fraction: Double) {
    LOW("low", 0.35),
    MEDIUM("medium", 0.65),
    HIGH("high", 1.0);

    fun next(): DensityLevel = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byId(id: String): DensityLevel? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}
