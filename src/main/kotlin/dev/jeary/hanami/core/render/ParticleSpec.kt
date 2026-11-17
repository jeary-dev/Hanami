package dev.jeary.hanami.core.render

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

/**
 * What a layer draws with. Each variant knows the Bukkit particle and how to build its data
 * object; [data] receives the palette colour for the current frame (if the layer has a palette).
 *
 * The parser checks each variant against [Particle.getDataType] on the running server, so a
 * mismatch is reported at load time instead of throwing while particles are being sent.
 */
sealed interface ParticleSpec {
    val particle: Particle

    /** The particle data for this frame. [tint] overrides the base colour for tintable specs. */
    fun data(tint: Color?): Any?

    /** Plain particles with no data (CHERRY_LEAVES, END_ROD, SOUL_FIRE_FLAME, ...). */
    data class Simple(override val particle: Particle) : ParticleSpec {
        override fun data(tint: Color?): Any? = null
    }

    /** Coloured redstone-style dust. */
    data class Dust(val color: Color, val size: Float) : ParticleSpec {
        override val particle: Particle get() = Particle.DUST
        private val static = Particle.DustOptions(color, size)
        override fun data(tint: Color?): Any = if (tint == null) static else Particle.DustOptions(tint, size)
    }

    /** Dust that fades from one colour to another over its short life. */
    data class DustTransition(val from: Color, val to: Color, val size: Float) : ParticleSpec {
        override val particle: Particle get() = Particle.DUST_COLOR_TRANSITION
        private val static = Particle.DustTransition(from, to, size)
        override fun data(tint: Color?): Any = if (tint == null) static else Particle.DustTransition(tint, to, size)
    }

    /** Particles whose data is a bare colour (e.g. ENTITY_EFFECT, TINTED_LEAVES on 1.21.5+). */
    data class Colored(override val particle: Particle, val color: Color) : ParticleSpec {
        override fun data(tint: Color?): Any = tint ?: color
    }

    /** Block-textured particles (FALLING_DUST, BLOCK, BLOCK_MARKER, ...). */
    data class Block(override val particle: Particle, val block: BlockData) : ParticleSpec {
        override fun data(tint: Color?): Any = block
    }

    /** Item-textured particles (ITEM). */
    data class Item(override val particle: Particle, val item: ItemStack) : ParticleSpec {
        override fun data(tint: Color?): Any = item
    }
}
