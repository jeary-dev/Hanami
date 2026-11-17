package dev.jeary.hanami.admin

import dev.jeary.hanami.admin.creator.CosmeticDraft
import dev.jeary.hanami.admin.creator.CosmeticFileWriter
import dev.jeary.hanami.admin.creator.DraftCompiler
import dev.jeary.hanami.admin.creator.EmitStyle
import dev.jeary.hanami.admin.creator.ParticleStyle
import dev.jeary.hanami.admin.creator.ShapePreset
import dev.jeary.hanami.admin.creator.UnlockStyle
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.ConfigError
import dev.jeary.hanami.config.ConfigNode
import dev.jeary.hanami.config.CosmeticParser
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.config.problems
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.math.shape.ShapeCache
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File

/** The creator must only ever produce YAML that the normal config parser accepts. */
class CosmeticCreatorTest {

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

    @TempDir
    lateinit var dir: File

    private val compiler = DraftCompiler(ShapeCache())

    private fun assertValid(draft: CosmeticDraft, context: String) {
        val result = compiler.compile(draft)
        assertInstanceOf(ValidationResult.Valid::class.java, result, "$context: ${result.problems}")
    }

    @Test
    fun `every type, shape, particle style and draw style compiles`() {
        for (type in CosmeticType.entries) {
            for (shape in ShapePreset.forType(type)) {
                for (style in ParticleStyle.entries) {
                    for (emit in EmitStyle.entries) {
                        val draft = CosmeticDraft(type, "test_draft").apply {
                            this.shape = shape
                            particleStyle = style
                            this.emit = emit
                            spin = 30.0
                            bob = 0.1
                        }
                        assertValid(draft, "$type / $shape / $style / $emit")
                    }
                }
            }
        }
    }

    @Test
    fun `knobs at their extremes still compile`() {
        val low = CosmeticDraft(CosmeticType.AURA, "low").apply {
            shape = ShapePreset.HELIX
            size = 0.1; points = 4; height = 0.0; turns = 0.5; strands = 1; particleSize = 0.2; offsetY = -1.0; interval = 1
        }
        val high = CosmeticDraft(CosmeticType.AURA, "high").apply {
            shape = ShapePreset.HELIX
            size = 8.0; points = 240; height = 6.0; turns = 10.0; strands = 8; particleSize = 3.0; offsetY = 3.0; interval = 20
            spin = -360.0; bob = 1.0
        }
        assertValid(low, "minimums")
        assertValid(high, "maximums")
    }

    @Test
    fun `behaviour settings reach the parsed cosmetic`() {
        val draft = CosmeticDraft(CosmeticType.EMOTE, "big_bang").apply {
            emoteDuration = 120
            emoteCooldown = 45
            unlock = UnlockStyle.PRICE
            price = 2500.0
        }
        val cosmetic = (compiler.compile(draft) as ValidationResult.Valid).value
        val emote = assertInstanceOf(Behavior.Emote::class.java, cosmetic.behavior)
        assertEquals(120, emote.durationTicks)
        assertEquals(45, emote.cooldownSeconds)
        assertEquals(2500.0, cosmetic.unlock.price)
    }

    @Test
    fun `an unknown particle is reported, not saved`() {
        val draft = CosmeticDraft(CosmeticType.TRAIL, "broken").apply {
            particleStyle = ParticleStyle.PARTICLE
            particle = "NOT_A_PARTICLE"
        }
        assertInstanceOf(ValidationResult.Invalid::class.java, compiler.compile(draft))
    }

    @Test
    fun `saved cosmetics are appended and load with the rest of the file`() {
        val file = File(dir, "cosmetics.yml")
        val bundled = requireNotNull(javaClass.getResource("/cosmetics.yml")).readText()
        file.writeText(bundled)
        val writer = CosmeticFileWriter(dir)

        val wings = CosmeticDraft(CosmeticType.WINGS, "moon_wings").apply { lore = "<#9c8a8f>Pale and quiet." }
        val ring = CosmeticDraft(CosmeticType.AURA, "moon_ring").apply { name = "<gradient:#c6b3ff:#ffffff>Moon Ring</gradient>" }
        assertEquals(CosmeticFileWriter.Result.Saved, writer.append(wings, "Admin"))
        assertEquals(CosmeticFileWriter.Result.Saved, writer.append(ring, "Admin"))
        assertInstanceOf(CosmeticFileWriter.Result.Failed::class.java, writer.append(ring, "Admin"), "duplicate ids are refused")

        val text = file.readText()
        assertTrue(text.startsWith(bundled), "the existing file is left exactly as it was")
        assertTrue("# Created in-game by Admin" in text)

        val yaml = YamlConfiguration().apply { loadFromString(text) }
        val errors = mutableListOf<ConfigError>()
        val result = CosmeticParser(ShapeCache()).parseAll(ConfigNode("cosmetics.yml", yaml, "", errors))
        assertEquals(emptyList<ConfigError>(), (errors + result.problems).filter { it.isError })
        val ids = result.cosmetics.map { it.id.value }
        assertTrue("moon_wings" in ids && "moon_ring" in ids, "both new cosmetics load: $ids")
        assertEquals("<gradient:#c6b3ff:#ffffff>Moon Ring</gradient>", result.cosmetics.first { it.id.value == "moon_ring" }.displayName)
    }

    @Test
    fun `writing into an empty file adds the cosmetics root`() {
        File(dir, "cosmetics.yml").writeText("")
        assertEquals(CosmeticFileWriter.Result.Saved, CosmeticFileWriter(dir).append(CosmeticDraft(CosmeticType.TRAIL, "solo"), "Admin"))
        val yaml = YamlConfiguration().apply { load(File(dir, "cosmetics.yml")) }
        assertTrue(yaml.isConfigurationSection("cosmetics.solo"))
    }
}
