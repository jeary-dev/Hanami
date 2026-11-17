package dev.jeary.hanami.core.tick

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.effect.EffectKind
import dev.jeary.hanami.core.effect.SpawnRequest
import dev.jeary.hanami.core.render.Emission
import dev.jeary.hanami.core.render.Frame
import dev.jeary.hanami.core.render.LayerRenderer
import dev.jeary.hanami.cosmetics.CosmeticModule
import dev.jeary.hanami.cosmetics.RenderContext
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator

/**
 * The pure maths stage: snapshot in, [Frame] out. Touches no Bukkit state, so it is safe to run
 * on the compute thread. Steps:
 *
 * 1. every module renders its anchors into emissions,
 * 2. viewers are resolved per emission (same world, in range, opted in, not private),
 * 3. per-player and global budgets thin the result.
 */
class FrameCompute(
    modules: Collection<CosmeticModule>,
    private val layers: LayerRenderer,
) {
    private val byType: Map<CosmeticType, CosmeticModule> = modules.associateBy { it.type }

    fun compute(snapshot: TickSnapshot): Frame {
        val started = System.nanoTime()
        val ctx = Context(snapshot.tick, snapshot.step, layers)

        for (player in snapshot.players) {
            for (cosmetic in player.cosmetics) {
                byType[cosmetic.definition.type]?.renderOnPlayer(player, cosmetic, ctx)
            }
        }
        for (projectile in snapshot.projectiles) {
            byType[CosmeticType.PROJECTILE]?.renderOnProjectile(projectile, ctx)
        }
        for (effect in snapshot.effects) {
            val module = when (effect.kind) {
                EffectKind.EMOTE -> byType[CosmeticType.EMOTE]
                EffectKind.LINGER -> byType[CosmeticType.TRAIL]
                EffectKind.ARC -> byType[CosmeticType.PROJECTILE]
            }
            module?.renderEffect(effect, ctx)
        }

        val emissions = ctx.emissions.filter { it.commands.isNotEmpty() }
        resolveViewers(emissions, snapshot)
        val outcome = ParticleBudget.apply(
            emissions,
            perPlayer = snapshot.settings.perPlayerBudget,
            global = snapshot.settings.globalBudget,
            tick = snapshot.tick,
        )

        return Frame(
            tick = snapshot.tick,
            emissions = emissions,
            spawnRequests = ctx.spawns,
            computeNanos = System.nanoTime() - started,
            pointsRequested = outcome.pointsBefore,
            pointsAfterBudget = outcome.pointsAfter,
            thinnedEmissions = outcome.thinnedEmissions,
        )
    }

    /**
     * Fills [Emission.viewers]. Players are bucketed by world first, so cost is
     * O(emissions × players-in-that-world) with a cheap squared-distance test.
     */
    private fun resolveViewers(emissions: List<Emission>, snapshot: TickSnapshot) {
        if (emissions.isEmpty()) return
        val byWorld = snapshot.players.groupBy { it.worldId }
        val range = snapshot.settings.viewDistance
        val rangeSq = range * range
        val buffer = IntArray(snapshot.players.size)

        for (e in emissions) {
            val candidates = byWorld[e.worldId] ?: continue
            var n = 0
            for (p in candidates) {
                val isOwner = p.uuid == e.ownerUuid
                if (e.privateTo != null && p.uuid != e.privateTo) continue
                if (!isOwner && !p.showOthers) continue
                val dx = p.x - e.originX
                val dy = p.y - e.originY
                val dz = p.z - e.originZ
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue
                buffer[n++] = p.index
            }
            e.viewers = buffer.copyOf(n)
        }
    }

    private class Context(
        override val tick: Long,
        override val step: Int,
        override val layers: LayerRenderer,
    ) : RenderContext {
        val emissions = ArrayList<Emission>()
        val spawns = ArrayList<SpawnRequest>()
        override val random: RandomGenerator = ThreadLocalRandom.current()

        override fun emission(
            ownerUuid: UUID,
            worldId: UUID,
            x: Double,
            y: Double,
            z: Double,
            privateTo: UUID?,
            attachedToOwner: Boolean,
        ): Emission = Emission(ownerUuid, worldId, x, y, z, privateTo, attachedToOwner).also(emissions::add)

        override fun spawn(request: SpawnRequest) {
            spawns += request
        }
    }
}
