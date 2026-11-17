package dev.jeary.hanami.cosmetics

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.cosmetic.CosmeticView
import dev.jeary.hanami.core.render.Palette
import dev.jeary.hanami.core.render.ParticleSpec
import dev.jeary.hanami.math.Easing
import dev.jeary.hanami.math.Vec3
import dev.jeary.hanami.math.shape.Shape
import org.bukkit.Material
import org.bukkit.entity.EntityType

/** A fully parsed, immutable cosmetic loaded from `cosmetics.yml`. */
data class CosmeticDefinition(
    override val id: CosmeticId,
    override val displayName: String,
    override val plainName: String,
    val lore: List<String>,
    val icon: IconSpec,
    val order: Int,
    val unlock: UnlockSpec,
    val layers: List<Layer>,
    val behavior: Behavior,
) : CosmeticView {
    override val type: CosmeticType get() = behavior.type
    override val permission: String get() = unlock.permission
    override val price: Double? get() = unlock.price
    override val unlockedByDefault: Boolean get() = unlock.default
}

data class IconSpec(val material: Material, val customModelData: Int?, val glow: Boolean)

data class UnlockSpec(val permission: String, val price: Double?, val default: Boolean)

/**
 * One visual layer of a cosmetic. Simple cosmetics have one; richer ones stack several
 * (e.g. a wing outline in white dust plus soft pink feather lines).
 */
data class Layer(
    val particle: ParticleSpec,
    val palette: Palette?,
    val shape: Shape,
    val offset: Vec3,
    val animation: Animation,
    val emit: Emit,
)

/** A periodic motion: `amplitude * curve.wave(tick / period)`. */
data class Oscillation(val amplitude: Double, val periodTicks: Int, val easing: Easing) {
    fun at(tick: Long, phaseOffset: Double = 0.0): Double =
        amplitude * easing.wave(tick.toDouble() / periodTicks + phaseOffset)
}

/** Scale over an effect's lifetime, eased between keyframes. */
data class Keyframe(val at: Double, val scale: Double, val easing: Easing)

data class Animation(
    /** Continuous rotation in degrees per second around (x = pitch, y = yaw, z = roll). */
    val rotate: Vec3,
    val followBodyYaw: Boolean,
    val bob: Oscillation?,
    val flap: Oscillation?,
    val scale: Double,
    val keyframes: List<Keyframe>,
) {
    /** Keyframed scale for an effect at [progress] ∈ [0, 1]; 1.0 without keyframes. */
    fun scaleAt(progress: Double): Double {
        if (keyframes.isEmpty()) return 1.0
        if (progress <= keyframes.first().at) return keyframes.first().scale
        if (progress >= keyframes.last().at) return keyframes.last().scale
        val next = keyframes.indexOfFirst { it.at >= progress }
        val a = keyframes[next - 1]
        val b = keyframes[next]
        val span = b.at - a.at
        val t = if (span <= 0.0) 1.0 else (progress - a.at) / span
        return a.scale + (b.scale - a.scale) * b.easing.ease(t)
    }

    companion object {
        val STILL = Animation(Vec3.ZERO, followBodyYaw = false, bob = null, flap = null, scale = 1.0, keyframes = emptyList())
    }
}

/** Which of a shape's points are drawn on a given frame. */
sealed interface EmitMode {
    /** Every point, every frame. */
    data object All : EmitMode

    /** [heads] bright markers travelling along the shape, each followed by a [tail] of points. */
    data class Sweep(val heads: Int, val cyclesPerSecond: Double, val tail: Int) : EmitMode

    /** [count] random points per frame — shimmer and firefly clouds. */
    data class Random(val count: Int) : EmitMode

    /** Points are revealed in order over the effect's lifetime, as if being drawn. */
    data object Progressive : EmitMode
}

data class Emit(
    val intervalTicks: Int,
    val count: Int,
    val spread: Vec3,
    val speed: Double,
    val maxPoints: Int,
    val mode: EmitMode,
    /** When set, particles are launched in this (local) direction instead of being scattered. */
    val direction: Vec3?,
)

/** Type-specific behaviour. The sealed hierarchy *is* the cosmetic type. */
sealed interface Behavior {
    val type: CosmeticType

    /**
     * @param lingerTicks > 0 turns each emission into an imprint that animates in place.
     * @param dropIntervalTicks how often an imprint is dropped while moving.
     */
    data class Trail(
        val minSpeed: Double,
        val mode: TrailMode,
        val lingerTicks: Int,
        val dropIntervalTicks: Int,
    ) : Behavior {
        override val type get() = CosmeticType.TRAIL
    }

    data class Aura(val idleOnly: Boolean) : Behavior {
        override val type get() = CosmeticType.AURA
    }

    data class Wings(
        val scale: Double,
        val tilt: Double,
        val height: Double,
        val back: Double,
        val sneakTuck: Boolean,
    ) : Behavior {
        override val type get() = CosmeticType.WINGS
    }

    /** [appliesTo] empty = every projectile type. */
    data class Projectile(val appliesTo: Set<EntityType>, val maxLifetimeTicks: Int) : Behavior {
        override val type get() = CosmeticType.PROJECTILE
    }

    data class Emote(
        val durationTicks: Int,
        val triggers: Set<EmoteTrigger>,
        val cooldownSeconds: Int,
    ) : Behavior {
        override val type get() = CosmeticType.EMOTE
    }
}

enum class TrailMode(val id: String) {
    /** A little behind the player, opposite to the direction of travel. */
    BEHIND("behind"),

    /** Directly at the player's feet. */
    FEET("feet"),

    /** Only while swimming, drawn flat on the water surface. */
    WATER_SURFACE("water-surface");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id.equals(id.trim().replace('_', '-'), ignoreCase = true) }
    }
}

enum class EmoteTrigger(val id: String) {
    KILL("kill"),
    COMMAND("command");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}
