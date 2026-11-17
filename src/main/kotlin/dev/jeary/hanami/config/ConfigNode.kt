package dev.jeary.hanami.config

import dev.jeary.hanami.core.render.parseHexColor
import dev.jeary.hanami.math.Easing
import dev.jeary.hanami.math.Vec3
import org.bukkit.Color
import org.bukkit.configuration.ConfigurationSection

/**
 * A typed, path-aware view over a YAML section that *collects* problems instead of throwing.
 *
 * Every accessor validates type and range and, on failure, records a [ConfigError] carrying the
 * full path (`koi_ripple.shape.radius`) and returns `null` or the supplied default. Parsers can
 * then read a whole cosmetic and report every problem in one go.
 */
class ConfigNode(
    val file: String,
    private val section: ConfigurationSection,
    val path: String,
    private val sink: MutableList<ConfigError>,
) {

    fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    fun has(key: String): Boolean = section.contains(key, true)

    fun keys(): Set<String> = section.getKeys(false)

    fun error(key: String?, message: String) {
        sink += ConfigError(file, key?.let(::pathOf) ?: path, message)
    }

    fun warn(key: String?, message: String) {
        sink += ConfigError(file, key?.let(::pathOf) ?: path, message, ConfigError.Severity.WARNING)
    }

    /** A child node, or null (with an error if [required]). */
    fun node(key: String, required: Boolean = false): ConfigNode? {
        val child = section.getConfigurationSection(key)
        if (child == null) {
            if (required) error(key, if (has(key)) "expected a section" else "missing required section")
            return null
        }
        return ConfigNode(file, child, pathOf(key), sink)
    }

    /** A child node that reports into its own [sink] — lets one cosmetic fail without the others. */
    fun isolated(key: String, sink: MutableList<ConfigError>): ConfigNode? =
        section.getConfigurationSection(key)?.let { ConfigNode(file, it, pathOf(key), sink) }

    /** Child sections of a list, e.g. `layers:` or `shapes:`. Non-map entries are reported. */
    fun nodeList(key: String): List<ConfigNode> {
        val raw = section.getList(key) ?: return emptyList()
        return raw.mapIndexedNotNull { index, entry ->
            val map = entry as? Map<*, *>
            if (map == null) {
                error("$key[$index]", "expected a section (key: value pairs)")
                return@mapIndexedNotNull null
            }
            // createSection converts nested maps into sections recursively
            val child = org.bukkit.configuration.MemoryConfiguration().createSection("entry", map)
            ConfigNode(file, child, pathOf("$key[$index]"), sink)
        }
    }

    fun string(key: String): String? {
        val value = section.get(key) ?: return null
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null.also { error(key, "expected text, got ${describe(value)}") }
        }
    }

    fun requireString(key: String): String? =
        string(key) ?: null.also { if (!has(key)) error(key, "missing required value") }

    fun string(key: String, default: String): String = string(key) ?: default

    fun stringList(key: String): List<String> {
        val value = section.get(key) ?: return emptyList()
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> listOf(value)
            else -> emptyList<String>().also { error(key, "expected a list of text") }
        }
    }

    fun bool(key: String, default: Boolean): Boolean {
        val value = section.get(key) ?: return default
        return when (value) {
            is Boolean -> value
            is String -> when (value.lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> default.also { error(key, "expected true or false, got \"$value\"") }
            }
            else -> default.also { error(key, "expected true or false, got ${describe(value)}") }
        }
    }

    fun int(key: String, default: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        val value = section.get(key) ?: return default
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (number == null || number % 1.0 != 0.0) {
            error(key, "expected a whole number${rangeHint(range)}, got ${describe(value)}")
            return default
        }
        val int = number.toInt()
        if (int !in range) {
            error(key, "expected a whole number${rangeHint(range)}, got $int")
            return int.coerceIn(range)
        }
        return int
    }

    fun double(
        key: String,
        default: Double,
        range: ClosedFloatingPointRange<Double> = -Double.MAX_VALUE..Double.MAX_VALUE,
    ): Double {
        val value = section.get(key) ?: return default
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (number == null || number.isNaN() || number.isInfinite()) {
            error(key, "expected a number${rangeHint(range)}, got ${describe(value)}")
            return default
        }
        if (number !in range) {
            error(key, "expected a number${rangeHint(range)}, got $number")
            return number.coerceIn(range)
        }
        return number
    }

    fun optionalDouble(key: String, range: ClosedFloatingPointRange<Double>): Double? =
        if (has(key)) double(key, range.start, range) else null

    /** A vector written as `[x, y, z]` (or `[x, y]` when [allow2d], z = 0). */
    fun vec3(key: String, default: Vec3, allow2d: Boolean = false): Vec3 {
        val raw = section.get(key) ?: return default
        return parseVec(raw, allow2d) ?: default.also {
            error(key, "expected [x, y, z]${if (allow2d) " or [x, y]" else ""}, got ${describe(raw)}")
        }
    }

    fun color(key: String): Color? {
        val raw = string(key) ?: return null
        return parseHexColor(raw) ?: null.also { error(key, "expected a hex colour like \"#ffb7c5\", got \"$raw\"") }
    }

    fun colorList(key: String): List<Color> = stringList(key).mapIndexedNotNull { i, raw ->
        parseHexColor(raw) ?: null.also { error("$key[$i]", "expected a hex colour like \"#ffb7c5\", got \"$raw\"") }
    }

    fun easing(key: String, default: Easing): Easing {
        val raw = string(key) ?: return default
        return Easing.byId(raw) ?: default.also {
            error(key, "unknown curve \"$raw\" (expected one of: ${Easing.ALL.joinToString { it.id }})")
        }
    }

    /** Looks up [key] with [lookup]; unknown values are reported with the [options] list. */
    fun <T : Any> choice(key: String, default: T, options: Collection<String>, lookup: (String) -> T?): T {
        val raw = string(key) ?: return default
        return lookup(raw) ?: default.also {
            error(key, "unknown value \"$raw\" (expected one of: ${options.joinToString()})")
        }
    }

    /** Raw access for parsers that need to inspect a list of lists (bezier segments). */
    fun rawList(key: String): List<*>? = section.getList(key)

    private fun rangeHint(range: IntRange) = when {
        range.first == Int.MIN_VALUE && range.last == Int.MAX_VALUE -> ""
        range.last == Int.MAX_VALUE -> " ≥ ${range.first}"
        else -> " between ${range.first} and ${range.last}"
    }

    private fun rangeHint(range: ClosedFloatingPointRange<Double>) = when {
        range.start == -Double.MAX_VALUE && range.endInclusive == Double.MAX_VALUE -> ""
        range.endInclusive == Double.MAX_VALUE -> " ≥ ${fmt(range.start)}"
        else -> " between ${fmt(range.start)} and ${fmt(range.endInclusive)}"
    }

    companion object {
        fun parseVec(raw: Any?, allow2d: Boolean): Vec3? {
            val list = raw as? List<*> ?: return null
            val nums = list.map { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() ?: return null }
            return when {
                nums.size == 3 -> Vec3(nums[0], nums[1], nums[2])
                nums.size == 2 && allow2d -> Vec3(nums[0], nums[1], 0.0)
                else -> null
            }
        }

        private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

        fun describe(value: Any?): String = when (value) {
            null -> "nothing"
            is String -> "\"$value\""
            is ConfigurationSection -> "a section"
            is List<*> -> "a list"
            is Map<*, *> -> "a section"
            else -> value.toString()
        }
    }
}
