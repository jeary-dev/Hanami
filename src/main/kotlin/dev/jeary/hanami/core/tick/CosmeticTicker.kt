package dev.jeary.hanami.core.tick

import dev.jeary.hanami.config.RenderSettings
import dev.jeary.hanami.core.effect.EffectManager
import dev.jeary.hanami.core.render.Frame
import dev.jeary.hanami.core.render.ParticleEmitter
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * The single scheduler task that drives every cosmetic on the server — there is no task per
 * player or per cosmetic. Each run is a three-stage pipeline:
 *
 * ```
 *  tick N (main)                 compute thread               tick N+1 (main)
 *  ─────────────                 ──────────────               ───────────────
 *  snapshot players ──────────▶  shapes × matrices,     ───▶  send particles to
 *  (cheap copies)                viewers, budgets             resolved viewers
 * ```
 *
 * The maths for tick N overlaps with the server's own work and its particles go out on tick N+1
 * (50 ms later — imperceptible). If a compute pass ever takes longer than a tick, that frame is
 * skipped instead of queueing work, so Hanami can never snowball into lag.
 */
class CosmeticTicker(
    private val plugin: Plugin,
    private val snapshotter: Snapshotter,
    private val compute: FrameCompute,
    private val emitter: ParticleEmitter,
    private val effects: EffectManager,
    private val settings: () -> RenderSettings,
    val metrics: TickMetrics = TickMetrics(),
) : Runnable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Hanami-Compute").apply { isDaemon = true }
    }

    private var task: BukkitTask? = null
    private var interval = 1
    private var pending: CompletableFuture<Frame>? = null
    private var pendingPlayers: List<PlayerSnapshot> = emptyList()
    private var failureLogged = false

    /** Hanami's own tick counter (advances by the update interval). */
    var tick: Long = 0
        private set

    fun start() {
        stop()
        interval = settings().updateInterval
        task = plugin.server.scheduler.runTaskTimer(plugin, this, 1L, interval.toLong())
    }

    /** Re-schedules when a reload changed the update interval. */
    fun applySettings() {
        if (settings().updateInterval != interval) start()
    }

    fun stop() {
        task?.cancel()
        task = null
        pending?.cancel(false)
        pending = null
    }

    fun shutdown() {
        stop()
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)
    }

    override fun run() {
        val started = System.nanoTime()
        tick += interval
        val render = settings()

        // 1) emit the frame computed during the previous tick
        var sent = 0
        var computeNs = 0L
        var pointsAfter = 0
        var pointsBefore = 0
        pending?.let { previous ->
            if (!previous.isDone) {
                metrics.recordLateFrame()
                metrics.record(System.nanoTime() - started, 0, 0, 0, 0)
                return
            }
            pending = null
            consume(previous)?.let { frame ->
                sent = emitter.emit(frame, pendingPlayers)
                frame.spawnRequests.forEach { effects.apply(it, tick) }
                computeNs = frame.computeNanos
                pointsAfter = frame.pointsAfterBudget
                pointsBefore = frame.pointsRequested
            }
        }

        // 2) snapshot the world
        val snapshot = snapshotter.capture(tick)
        val sources = snapshot.players.count { it.cosmetics.isNotEmpty() }
        metrics.recordPopulation(sources, snapshot.projectiles.size, snapshot.effects.size)

        // 3) compute — skipped entirely when nothing is being worn
        if (sources > 0 || snapshot.projectiles.isNotEmpty() || snapshot.effects.isNotEmpty()) {
            if (render.asyncCompute) {
                pending = CompletableFuture.supplyAsync({ compute.compute(snapshot) }, executor)
                pendingPlayers = snapshot.players
            } else {
                val frame = compute.compute(snapshot)
                sent += emitter.emit(frame, snapshot.players)
                frame.spawnRequests.forEach { effects.apply(it, tick) }
                computeNs = frame.computeNanos
                pointsAfter = frame.pointsAfterBudget
                pointsBefore = frame.pointsRequested
            }
        }

        metrics.record(System.nanoTime() - started, computeNs, sent, pointsAfter, pointsBefore)
    }

    private fun consume(future: CompletableFuture<Frame>): Frame? = try {
        future.join()
    } catch (e: Exception) {
        if (!failureLogged) {
            failureLogged = true
            plugin.logger.log(Level.SEVERE, "A cosmetic frame failed to compute (further errors are suppressed)", e.cause ?: e)
        }
        null
    }
}
