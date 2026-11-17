package dev.jeary.hanami.config

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.render.ParticleSpec
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.EmitMode
import dev.jeary.hanami.cosmetics.TrailMode
import dev.jeary.hanami.math.shape.Circle
import dev.jeary.hanami.math.shape.ShapeCache
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** Parsing runs against MockBukkit so Material/Particle/BlockData lookups behave like a real server. */
class CosmeticParserTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun startServer() {
            MockBukkit.mock()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            MockBukkit.unmock()
        }
    }

    private fun parse(yaml: String): CosmeticParser.Result {
        val config = YamlConfiguration().apply { loadFromString(yaml) }
        val rootErrors = mutableListOf<ConfigError>()
        val result = CosmeticParser(ShapeCache()).parseAll(ConfigNode("cosmetics.yml", config, "", rootErrors))
        return result.copy(problems = rootErrors + result.problems)
    }

    private val CosmeticParser.Result.errors get() = problems.filter { it.isError }

    @Test
    fun `every bundled cosmetic loads without errors`() {
        val bundled = requireNotNull(javaClass.getResource("/cosmetics.yml")).readText()
        val result = parse(bundled)
        assertEquals(emptyList<ConfigError>(), result.errors)
        assertTrue(result.cosmetics.size >= 12, "ship at least 12 cosmetics, found ${result.cosmetics.size}")
        CosmeticType.entries.forEach { type ->
            assertTrue(result.cosmetics.any { it.type == type }, "no default cosmetic of type $type")
        }
    }

    @Test
    fun `the custom cosmetic example in the README is valid`() {
        val readme = java.io.File("README.md").readText()
        val example = readme.substringAfter("## 🎨 Making your own cosmetic").substringAfter("```yaml").substringBefore("```")
        val result = parse(example)
        assertEquals(emptyList<ConfigError>(), result.errors)
        val lotus = result.cosmetics.single()
        assertEquals(2, lotus.layers.size)
        // 8 petals × 2 bezier segments × 18 points
        assertEquals(8 * 18, ShapeCache()[lotus.layers.first().shape].size)
    }

    @Test
    fun `a minimal trail parses with sensible defaults`() {
        val result = parse(
            """
            cosmetics:
              simple:
                type: trail
                particle: { kind: dust, color: '#ffb7c5' }
                shape: { kind: circle, radius: 0.5, points: 8 }
            """.trimIndent(),
        )
        assertEquals(emptyList<ConfigError>(), result.errors)
        val trail = result.cosmetics.single()
        assertEquals(CosmeticId.of("simple"), trail.id)
        assertEquals("hanami.cosmetic.simple", trail.permission)
        val behavior = assertInstanceOf(Behavior.Trail::class.java, trail.behavior)
        assertEquals(TrailMode.BEHIND, behavior.mode)
        val layer = trail.layers.single()
        assertInstanceOf(ParticleSpec.Dust::class.java, layer.particle)
        assertEquals(Circle(0.5, 8), layer.shape)
        assertEquals(EmitMode.All, layer.emit.mode)
    }

    @Test
    fun `errors carry the file, the full path and what was expected`() {
        val result = parse(
            """
            cosmetics:
              broken:
                type: aura
                particle: { kind: dust, color: 'pink' }
                shape: { kind: circle, radius: big }
            """.trimIndent(),
        )
        val messages = result.errors.map { it.toString() }
        assertTrue(messages.any { it.startsWith("cosmetics.yml → cosmetics.broken.shape.radius: expected a number between") }, "$messages")
        assertTrue(messages.any { it.startsWith("cosmetics.yml → cosmetics.broken.particle.color: expected a hex colour") }, "$messages")
        assertTrue(result.cosmetics.isEmpty())
    }

    @Test
    fun `one broken cosmetic does not stop the others from loading`() {
        val result = parse(
            """
            cosmetics:
              good:
                type: aura
                particle: { particle: END_ROD }
                shape: { kind: point }
              bad:
                type: dance
                particle: { particle: END_ROD }
                shape: { kind: point }
            """.trimIndent(),
        )
        assertEquals(listOf("good"), result.cosmetics.map { it.id.value })
        assertTrue(result.errors.single().toString().contains("bad.type: unknown type \"dance\""))
    }

    @Test
    fun `unknown shapes and particles list the valid options`() {
        val result = parse(
            """
            cosmetics:
              odd:
                type: aura
                particle: { particle: NOT_A_PARTICLE }
                shape: { kind: hexagon }
            """.trimIndent(),
        )
        val text = result.errors.joinToString("\n")
        assertTrue(text.contains("odd.shape.kind: unknown shape \"hexagon\" (expected one of: point, line"), text)
        assertTrue(text.contains("odd.particle.particle: unknown particle \"NOT_A_PARTICLE\""), text)
    }

    @Test
    fun `a particle missing on this version falls back`() {
        val result = parse(
            """
            cosmetics:
              future:
                type: aura
                particle:
                  particle: SOME_FUTURE_PARTICLE
                  fallback: { kind: dust, color: '#ffffff', size: 0.5 }
                shape: { kind: point }
            """.trimIndent(),
        )
        assertEquals(emptyList<ConfigError>(), result.errors)
        assertInstanceOf(ParticleSpec.Dust::class.java, result.cosmetics.single().layers.single().particle)
    }

    @Test
    fun `particles that need data are rejected as simple`() {
        val result = parse(
            """
            cosmetics:
              needs_data:
                type: aura
                particle: { kind: simple, particle: DUST }
                shape: { kind: point }
            """.trimIndent(),
        )
        assertTrue(result.errors.single().message.contains("needs extra data"), result.errors.toString())
    }

    @Test
    fun `layers inherit top-level keys and override their own`() {
        val result = parse(
            """
            cosmetics:
              layered:
                type: aura
                shape: { kind: circle, radius: 1, points: 12 }
                layers:
                  - particle: { particle: END_ROD }
                  - particle: { particle: END_ROD }
                    shape: { kind: point }
            """.trimIndent(),
        )
        assertEquals(emptyList<ConfigError>(), result.errors)
        val layers = result.cosmetics.single().layers
        assertEquals(Circle(1.0, 12), layers[0].shape)
        assertEquals(dev.jeary.hanami.math.shape.PointShape, layers[1].shape)
        assertEquals(Particle.END_ROD, layers[0].particle.particle)
    }

    @Test
    fun `wings get a default flap and keyframes are sorted`() {
        val result = parse(
            """
            cosmetics:
              w:
                type: wings
                particle: { particle: END_ROD }
                shape: { kind: line, from: [0, 0, 0], to: [1, 1, 0], points: 4 }
              e:
                type: emote
                particle: { particle: END_ROD }
                shape: { kind: point }
                animation:
                  keyframes:
                    - { at: 1.0, scale: 2 }
                    - { at: 0.0, scale: 0.5 }
            """.trimIndent(),
        )
        assertEquals(emptyList<ConfigError>(), result.errors)
        val wings = result.cosmetics.first { it.type == CosmeticType.WINGS }
        assertNotNull(wings.layers.single().animation.flap)
        val emote = result.cosmetics.first { it.type == CosmeticType.EMOTE }
        val animation = emote.layers.single().animation
        assertEquals(0.5, animation.scaleAt(0.0), 1e-9)
        assertEquals(1.25, animation.scaleAt(0.5), 1e-9)
        assertEquals(EmitMode.Progressive, emote.layers.single().emit.mode)
    }

    @Test
    fun `invalid ids are rejected`() {
        val result = parse(
            """
            cosmetics:
              'Has Spaces':
                type: aura
                particle: { particle: END_ROD }
                shape: { kind: point }
            """.trimIndent(),
        )
        assertTrue(result.errors.single().message.startsWith("invalid id"))
    }
}
