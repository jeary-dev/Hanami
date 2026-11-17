package dev.jeary.hanami.core

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.config.ConfigError
import dev.jeary.hanami.config.ConfigNode
import dev.jeary.hanami.config.CosmeticParser
import dev.jeary.hanami.config.RenderSettings
import dev.jeary.hanami.core.render.LayerRenderer
import dev.jeary.hanami.core.tick.ActiveCosmetic
import dev.jeary.hanami.core.tick.FrameCompute
import dev.jeary.hanami.core.tick.PlayerSnapshot
import dev.jeary.hanami.core.tick.TickSnapshot
import dev.jeary.hanami.cosmetics.CosmeticRegistry
import dev.jeary.hanami.cosmetics.aura.AuraModule
import dev.jeary.hanami.cosmetics.emote.EmoteModule
import dev.jeary.hanami.cosmetics.projectile.ProjectileModule
import dev.jeary.hanami.cosmetics.trail.TrailModule
import dev.jeary.hanami.cosmetics.wings.WingsModule
import dev.jeary.hanami.math.shape.ShapeCache
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID

/**
 * Runs the compute stage end to end on synthetic snapshots: real cosmetics from the bundled
 * cosmetics.yml, real modules, real budgets — everything except sending packets.
 */
class PipelineTest {

    companion object {
        private lateinit var server: ServerMock
        private lateinit var registry: CosmeticRegistry
        private val shapes = ShapeCache()

        @JvmStatic
        @BeforeAll
        fun setUp() {
            server = MockBukkit.mock()
            val yaml = YamlConfiguration().apply {
                loadFromString(requireNotNull(PipelineTest::class.java.getResource("/cosmetics.yml")).readText())
            }
            val errors = mutableListOf<ConfigError>()
            registry = CosmeticRegistry(CosmeticParser(shapes).parseAll(ConfigNode("cosmetics.yml", yaml, "", errors)).cosmetics)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            MockBukkit.unmock()
        }
    }

    private val world = UUID.randomUUID()
    private val settings = RenderSettings(updateInterval = 1, viewDistance = 32.0, perPlayerBudget = 200, globalBudget = 6000, asyncCompute = true)
    private val compute = FrameCompute(
        listOf(TrailModule(), AuraModule(), WingsModule(), ProjectileModule(), EmoteModule()),
        LayerRenderer(shapes),
    )

    private fun cosmetic(id: String, private: Boolean = false) =
        ActiveCosmetic(requireNotNull(registry[CosmeticId.of(id)]) { "missing $id" }, private)

    private fun snapshot(
        index: Int,
        player: Player,
        x: Double,
        cosmetics: List<ActiveCosmetic> = emptyList(),
        moving: Boolean = false,
        showOthers: Boolean = true,
    ) = PlayerSnapshot(
        index = index, player = player, uuid = player.uniqueId, worldId = world,
        x = x, y = 64.0, z = 0.0, yaw = 0f, bodyYaw = 0f, pitch = 0f,
        dx = if (moving) 0.2 else 0.0, dy = 0.0, dz = 0.0,
        sneaking = false, gliding = false, waterSurfaceY = Double.NaN,
        showOthers = showOthers, density = DensityLevel.HIGH, cosmetics = cosmetics,
    )

    @Test
    fun `viewers are limited by range and by the show-others setting`() {
        val wearer = server.addPlayer()
        val near = server.addPlayer()
        val far = server.addPlayer()
        val optedOut = server.addPlayer()
        val players = listOf(
            snapshot(0, wearer, 0.0, listOf(cosmetic("sakura_wings"))),
            snapshot(1, near, 5.0),
            snapshot(2, far, 500.0),
            snapshot(3, optedOut, 3.0, showOthers = false),
        )
        val frame = (0L..3L).map { compute.compute(TickSnapshot(it, settings, players, emptyList(), emptyList())) }
            .first { it.emissions.isNotEmpty() }

        val viewers = frame.emissions.single().viewers.toSet()
        assertEquals(setOf(0, 1), viewers, "the wearer and the nearby player see the wings")
    }

    @Test
    fun `previews are only visible to their owner`() {
        val wearer = server.addPlayer()
        val other = server.addPlayer()
        val players = listOf(
            snapshot(0, wearer, 0.0, listOf(cosmetic("hanafubuki", private = true))),
            snapshot(1, other, 2.0),
        )
        val frames = (0L..8L).map { compute.compute(TickSnapshot(it, settings, players, emptyList(), emptyList())) }
        val emissions = frames.flatMap { it.emissions }
        assertTrue(emissions.isNotEmpty())
        emissions.forEach { assertEquals(listOf(0), it.viewers.toList()) }
    }

    @Test
    fun `trails only draw while moving`() {
        val walker = server.addPlayer()
        val still = listOf(snapshot(0, walker, 0.0, listOf(cosmetic("sakura_drift")), moving = false))
        val moving = listOf(snapshot(0, walker, 0.0, listOf(cosmetic("sakura_drift")), moving = true))
        assertTrue(compute.compute(TickSnapshot(6, settings, still, emptyList(), emptyList())).emissions.isEmpty())
        assertTrue(compute.compute(TickSnapshot(6, settings, moving, emptyList(), emptyList())).emissions.isNotEmpty())
    }

    @Test
    fun `budgets cap output for a crowd wearing everything`() {
        val tight = settings.copy(perPlayerBudget = 40, globalBudget = 2_000)
        val crowd = (0 until 60).map { i ->
            snapshot(
                i, server.addPlayer(), (i % 8).toDouble(),
                listOf(cosmetic("kanji_ring"), cosmetic("sakura_wings"), cosmetic("sakura_drift")),
                moving = false,
            )
        }
        repeat(8) { t ->
            val frame = compute.compute(TickSnapshot(t.toLong(), tight, crowd, emptyList(), emptyList()))
            val perOwner = frame.emissions.groupBy { it.ownerUuid }.mapValues { (_, e) -> e.sumOf { it.points } }
            perOwner.values.forEach { assertTrue(it <= tight.perPlayerBudget + 2, "per-player cap exceeded: $it") }
            val sends = frame.emissions.sumOf { it.points.toLong() * it.viewers.size }
            // error-diffused rounding overshoots by at most half of one command's viewer count
            assertTrue(sends <= tight.globalBudget + crowd.size / 2, "global cap exceeded: $sends")
        }
    }
}
