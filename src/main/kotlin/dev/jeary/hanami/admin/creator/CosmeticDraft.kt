package dev.jeary.hanami.admin.creator

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.CosmeticParser
import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** A shape the creator offers, with the parameters it uses. Each one maps onto a YAML shape. */
enum class ShapePreset(
    val label: String,
    val icon: Material,
    val description: String,
    val params: Set<ShapeParam>,
    val wingsOnly: Boolean = false,
) {
    RING("Ring", Material.HEART_OF_THE_SEA, "A flat circle around you", setOf(ShapeParam.SIZE, ShapeParam.POINTS)),
    STAR("Star", Material.NETHER_STAR, "A flat five-pointed star", setOf(ShapeParam.SIZE, ShapeParam.POINTS)),
    SPIRAL("Spiral", Material.NAUTILUS_SHELL, "Winds outward as it rises", setOf(ShapeParam.SIZE, ShapeParam.POINTS, ShapeParam.HEIGHT, ShapeParam.TURNS)),
    HELIX("Helix", Material.END_ROD, "Strands twisting around an axis", setOf(ShapeParam.SIZE, ShapeParam.POINTS, ShapeParam.HEIGHT, ShapeParam.TURNS, ShapeParam.STRANDS)),
    SPHERE("Sphere", Material.SNOWBALL, "An even shell of points", setOf(ShapeParam.SIZE, ShapeParam.POINTS)),
    ORBIT("Orbit", Material.ENDER_EYE, "A looping figure-of-eight orbit", setOf(ShapeParam.SIZE, ShapeParam.POINTS, ShapeParam.HEIGHT)),
    TEXT("Text", Material.WRITABLE_BOOK, "Letters or kanji drawn in particles", setOf(ShapeParam.SIZE, ShapeParam.POINTS, ShapeParam.TEXT)),
    PETAL_WING("Petal wing", Material.CHERRY_LEAVES, "A rounded petal with a notched tip", setOf(ShapeParam.POINTS), wingsOnly = true),
    BLADE_WING("Blade wing", Material.FEATHER, "A long, swept-back wing", setOf(ShapeParam.POINTS), wingsOnly = true),
    ;

    fun availableFor(type: CosmeticType) = wingsOnly == (type == CosmeticType.WINGS)

    companion object {
        fun forType(type: CosmeticType) = entries.filter { it.availableFor(type) }
    }
}

enum class ShapeParam { SIZE, POINTS, HEIGHT, TURNS, STRANDS, TEXT }

/** How the particle is described: a solid colour, a two-colour fade, or a stock particle. */
enum class ParticleStyle(val label: String, val icon: Material) {
    DUST("Coloured dust", Material.REDSTONE),
    GRADIENT("Two-colour fade", Material.GLOWSTONE_DUST),
    PARTICLE("Stock particle", Material.BLAZE_POWDER),
}

enum class EmitStyle(val id: String, val label: String, val description: String) {
    ALL("all", "Whole shape", "Every point, every frame"),
    SWEEP("sweep", "Sweep", "Bright markers running around the shape"),
    RANDOM("random", "Sparkle", "A few random points each frame"),
    PROGRESSIVE("progressive", "Draw in", "Drawn stroke by stroke (emotes)"),
}

enum class UnlockStyle(val label: String) { FREE("Free for everyone"), PERMISSION("Permission only"), PRICE("Buy with money") }

enum class EmoteTriggers(val label: String, val ids: List<String>) {
    BOTH("Kill and /hanami emote", listOf("kill", "command")),
    KILL("Kill only", listOf("kill")),
    COMMAND("/hanami emote only", listOf("command")),
}

/**
 * Everything an admin has set in the cosmetic creator. It is deliberately a *single layer*
 * cosmetic described by friendly knobs; [toYaml] turns it into exactly what an admin would write
 * by hand in `cosmetics.yml`, so the normal parser validates it and the saved entry stays
 * readable and editable.
 */
class CosmeticDraft(type: CosmeticType, var id: String) {

    var type: CosmeticType = type
        set(value) {
            if (field == value) return
            field = value
            applyTypeDefaults()
        }

    var name: String? = null
    var lore: String? = null
    var icon: Material? = null

    var unlock = UnlockStyle.PERMISSION
    var price = 5000.0

    lateinit var shape: ShapePreset
    var size = 1.0
    var points = 32
    var height = 1.5
    var turns = 2.0
    var strands = 2
    var text = "花"

    var particleStyle = ParticleStyle.GRADIENT
    var color1 = "#ffb7c5"
    var color2 = "#ffffff"
    var particleSize = 0.8
    var particle = "CHERRY_LEAVES"

    var spin = 0.0
    var bob = 0.0
    var flapAngle = 18.0
    var flapPeriod = 48
    lateinit var emit: EmitStyle
    var interval = 2
    var offsetY = 0.0

    var trailMode = "behind"
    var idleOnly = false
    var wingScale = 1.0
    var emoteDuration = 60
    var emoteCooldown = 15
    var emoteTriggers = EmoteTriggers.BOTH

    init {
        applyTypeDefaults()
    }

    /** Sensible starting values each time the type changes. */
    private fun applyTypeDefaults() {
        shape = ShapePreset.forType(type).first()
        emit = if (type == CosmeticType.EMOTE) EmitStyle.PROGRESSIVE else EmitStyle.ALL
        interval = if (type == CosmeticType.TRAIL || type == CosmeticType.PROJECTILE) 1 else 2
        when (type) {
            CosmeticType.TRAIL -> { size = 0.4; points = 12; offsetY = 0.1; spin = 0.0 }
            CosmeticType.AURA -> { size = 1.0; points = 32; offsetY = 0.1; spin = 20.0 }
            CosmeticType.WINGS -> { points = 60; offsetY = 0.0; spin = 0.0 }
            CosmeticType.PROJECTILE -> { size = 0.25; points = 8; offsetY = 0.0; spin = 0.0 }
            CosmeticType.EMOTE -> { size = 1.5; points = 48; offsetY = 0.1; spin = 0.0 }
        }
    }

    val displayName: String get() = name ?: "<gradient:$color1:$color2>${id.split('_', '-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }}</gradient>"

    val iconMaterial: Material get() = icon ?: shape.icon

    // ── YAML ─────────────────────────────────────────────────────────────

    fun toYaml(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("type", type.id)
        put("name", displayName)
        lore?.let { put("lore", listOf(it)) }
        put("icon", mapOf("material" to iconMaterial.name))
        put(
            "unlock",
            when (unlock) {
                UnlockStyle.FREE -> mapOf("default" to true)
                UnlockStyle.PERMISSION -> mapOf("permission" to "hanami.cosmetic.$id")
                UnlockStyle.PRICE -> mapOf("price" to price)
            },
        )
        put(type.id, behaviourYaml())
        put("particle", particleYaml())
        put("shape", shapeYaml())
        if (offsetY != 0.0) put("offset", listOf(0.0, r(offsetY), 0.0))
        animationYaml().takeIf { it.isNotEmpty() }?.let { put("animation", it) }
        put("emit", emitYaml())
    }

    private fun behaviourYaml(): Map<String, Any> = when (type) {
        CosmeticType.TRAIL -> mapOf("mode" to trailMode)
        CosmeticType.AURA -> mapOf("idle-only" to idleOnly)
        CosmeticType.WINGS -> mapOf("scale" to r(wingScale))
        CosmeticType.PROJECTILE -> mapOf("applies-to" to listOf("ALL"))
        CosmeticType.EMOTE -> mapOf(
            "duration-ticks" to emoteDuration,
            "trigger" to emoteTriggers.ids,
            "cooldown-seconds" to emoteCooldown,
        )
    }

    private fun particleYaml(): Map<String, Any> = when (particleStyle) {
        ParticleStyle.DUST -> mapOf("kind" to "dust", "color" to color1, "size" to r(particleSize))
        ParticleStyle.GRADIENT -> mapOf("kind" to "dust_transition", "colors" to listOf(color1, color2), "size" to r(particleSize))
        ParticleStyle.PARTICLE -> mapOf("kind" to "simple", "particle" to particle)
    }

    fun shapeYaml(): Map<String, Any> = when (shape) {
        ShapePreset.RING -> mapOf("kind" to "circle", "radius" to r(size), "points" to points, "plane" to "xz")
        ShapePreset.STAR -> mapOf("kind" to "polyline", "vertices" to starVertices(size), "points" to points)
        ShapePreset.SPIRAL -> mapOf(
            "kind" to "spiral", "turns" to r(turns), "radius-start" to 0.1, "radius-end" to r(size),
            "height" to r(height), "points" to points,
        )
        ShapePreset.HELIX -> mapOf(
            "kind" to "helix", "radius" to r(size), "height" to r(height), "turns" to r(turns),
            "strands" to strands, "points" to points,
        )
        ShapePreset.SPHERE -> mapOf("kind" to "sphere", "radius" to r(size), "points" to points)
        ShapePreset.ORBIT -> mapOf(
            "kind" to "lissajous", "a" to 1, "b" to 2, "c" to 3, "delta" to 90,
            "amplitude" to listOf(r(size), r(height / 4), r(size)), "points" to points,
        )
        ShapePreset.TEXT -> mapOf(
            "kind" to "glyph", "text" to text, "size" to r(size), "points" to points, "plane" to "xy",
            "fallback" to mapOf("kind" to "circle", "radius" to r(size / 2), "points" to points / 2 + 1),
        )
        ShapePreset.PETAL_WING -> mapOf("kind" to "bezier", "points" to points, "segments" to PETAL_SEGMENTS)
        ShapePreset.BLADE_WING -> mapOf("kind" to "bezier", "points" to points, "segments" to BLADE_SEGMENTS)
    }

    private fun animationYaml(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        if (spin != 0.0) put("rotate", mapOf("yaw" to r(spin)))
        if (bob != 0.0) put("bob", mapOf("amplitude" to r(bob), "period-ticks" to 60, "curve" to "sine"))
        if (type == CosmeticType.WINGS) {
            put("flap", mapOf("angle" to r(flapAngle), "period-ticks" to flapPeriod, "curve" to "sine"))
        }
    }

    private fun emitYaml(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("mode", emit.id)
        when (emit) {
            EmitStyle.SWEEP -> put("sweep", mapOf("heads" to 3, "cycles-per-second" to 0.5, "tail" to 2))
            EmitStyle.RANDOM -> put("random", mapOf("count" to maxOf(1, points / 6)))
            else -> Unit
        }
        put("interval-ticks", interval)
    }

    /** Lets the menu warn about a stock particle that doesn't exist on this server version. */
    fun particleExists(): Boolean = CosmeticParser.particleByName(particle) != null

    companion object {
        /** Stock particles that need no extra data and look good as cosmetics (1.20.6+ names). */
        val PARTICLES = listOf(
            "CHERRY_LEAVES", "END_ROD", "FLAME", "SOUL_FIRE_FLAME", "SNOWFLAKE", "HEART", "NOTE",
            "ENCHANT", "GLOW", "ELECTRIC_SPARK", "WAX_ON", "WAX_OFF", "FIREWORK", "TOTEM_OF_UNDYING",
            "CLOUD", "SMOKE", "SOUL", "SCULK_SOUL", "DRIPPING_WATER", "DRIPPING_HONEY", "SPORE_BLOSSOM_AIR",
            "CRIMSON_SPORE", "WARPED_SPORE", "WHITE_ASH", "HAPPY_VILLAGER", "ANGRY_VILLAGER", "BUBBLE_POP",
            "SCRAPE", "DRAGON_BREATH", "PORTAL", "REVERSE_PORTAL", "COMPOSTER", "NAUTILUS", "FALLING_NECTAR",
        )

        /** Pastel and ink colours that suit Hanami's look. */
        val COLORS = listOf(
            "#ffb7c5", "#ff9fb4", "#ffffff", "#fbe4ea", "#e0452b", "#b52a1a", "#ffd27f", "#f3c969",
            "#9fd8ff", "#7fb3e6", "#c6b3ff", "#8a6fd1", "#a8e6a1", "#5fa65a", "#1a1a1a", "#6b6b6b",
        )

        // The bundled Sakura Wings outline, and a simpler swept blade (right wing, XY plane).
        private val PETAL_SEGMENTS = listOf(
            listOf(listOf(0.05, 0.15), listOf(0.20, 0.62), listOf(0.55, 0.92), listOf(0.90, 0.82)),
            listOf(listOf(0.90, 0.82), listOf(0.92, 0.74), listOf(0.87, 0.67), listOf(0.84, 0.62)),
            listOf(listOf(0.84, 0.62), listOf(0.92, 0.62), listOf(0.99, 0.56), listOf(1.00, 0.48)),
            listOf(listOf(1.00, 0.48), listOf(0.98, 0.15), listOf(0.70, -0.20), listOf(0.40, -0.30)),
            listOf(listOf(0.40, -0.30), listOf(0.25, -0.33), listOf(0.12, -0.20), listOf(0.05, -0.05)),
        )
        private val BLADE_SEGMENTS = listOf(
            listOf(listOf(0.05, 0.10), listOf(0.40, 0.55), listOf(0.90, 0.80), listOf(1.30, 0.70)),
            listOf(listOf(1.30, 0.70), listOf(0.95, 0.40), listOf(0.60, 0.05), listOf(0.05, -0.10)),
        )

        private fun starVertices(radius: Double): List<List<Double>> = (0..10).map { i ->
            val angle = PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) radius else radius * 0.45
            listOf(r(cos(angle) * rr), 0.0, r(sin(angle) * rr))
        }

        /** Rounds to 2 decimals so the saved YAML stays tidy. */
        fun r(value: Double): Double = round(value * 100) / 100
    }
}
