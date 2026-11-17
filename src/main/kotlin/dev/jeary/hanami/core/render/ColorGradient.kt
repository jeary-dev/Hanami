package dev.jeary.hanami.core.render

import dev.jeary.hanami.math.Easing
import org.bukkit.Color
import kotlin.math.floor
import kotlin.math.roundToInt

/** Linear RGB interpolation between two colours. */
fun Color.mix(to: Color, t: Double): Color {
    val k = t.coerceIn(0.0, 1.0)
    return Color.fromRGB(
        (red + (to.red - red) * k).roundToInt(),
        (green + (to.green - green) * k).roundToInt(),
        (blue + (to.blue - blue) * k).roundToInt(),
    )
}

/**
 * A looping colour cycle: the layer's colour drifts through [colors] once every [cycleTicks],
 * easing between neighbours with [easing]. Petals blush from pink to white and back.
 */
data class Palette(
    val colors: List<Color>,
    val cycleTicks: Int,
    val easing: Easing,
) {
    init {
        require(colors.isNotEmpty()) { "Palette needs at least one colour" }
        require(cycleTicks > 0) { "Palette cycle must be positive" }
    }

    fun colorAt(tick: Long): Color {
        if (colors.size == 1) return colors[0]
        val phase = (tick % cycleTicks).toDouble() / cycleTicks
        val position = phase * colors.size
        val index = floor(position).toInt() % colors.size
        val local = position - floor(position)
        return colors[index].mix(colors[(index + 1) % colors.size], easing.ease(local))
    }
}

/** Parses `#rrggbb` / `rrggbb` into a Bukkit colour. */
fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)?.let(Color::fromRGB)
}
