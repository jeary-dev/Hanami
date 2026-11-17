package dev.jeary.hanami.api.cosmetic

/**
 * A validated cosmetic identifier: lowercase letters, digits, `_` and `-`.
 *
 * Deliberately a regular class rather than a Kotlin value class so API methods taking it keep
 * their plain JVM names and stay callable from Java plugins.
 */
class CosmeticId private constructor(val value: String) : Comparable<CosmeticId> {

    override fun equals(other: Any?) = other is CosmeticId && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = value
    override fun compareTo(other: CosmeticId) = value.compareTo(other.value)

    companion object {
        private val PATTERN = Regex("[a-z0-9_-]{1,48}")

        @JvmStatic
        fun parse(raw: String): CosmeticId? =
            raw.trim().lowercase().takeIf(PATTERN::matches)?.let(::CosmeticId)

        @JvmStatic
        fun of(raw: String): CosmeticId =
            requireNotNull(parse(raw)) { "Invalid cosmetic id '$raw' (allowed: a-z, 0-9, _ and -, max 48 chars)" }
    }
}
