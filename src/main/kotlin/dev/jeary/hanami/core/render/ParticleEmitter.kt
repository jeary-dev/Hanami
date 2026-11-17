package dev.jeary.hanami.core.render

import dev.jeary.hanami.core.compat.PlatformAdapter
import dev.jeary.hanami.core.tick.PlayerSnapshot
import org.bukkit.entity.Player

/**
 * The only place Hanami sends particles. Runs on the main thread (packet sending through the
 * Bukkit API is not thread-safe on Spigot).
 *
 * Per viewer it applies the last checks that need live state — still online, can actually see
 * the owner (vanish plugins hide players with `hidePlayer`) — then thins the points by the
 * viewer's personal density setting.
 */
class ParticleEmitter(private val platform: PlatformAdapter) {

    /** @return number of particle sends (one per point per viewer). */
    fun emit(frame: Frame, players: List<PlayerSnapshot>): Int {
        if (frame.emissions.isEmpty()) return 0
        val owners = HashMap<java.util.UUID, Player>(players.size)
        for (p in players) owners[p.uuid] = p.player

        var sent = 0
        for (emission in frame.emissions) {
            val owner = owners[emission.ownerUuid]
            // On Paper the entity tracker already knows exactly who can see the owner.
            val tracked = if (owner != null && emission.attachedToOwner) platform.trackedBy(owner) else null

            for (viewerIndex in emission.viewers) {
                val snapshot = players.getOrNull(viewerIndex) ?: continue
                val viewer = snapshot.player
                if (!viewer.isOnline) continue
                if (owner != null && viewer !== owner) {
                    val visible = tracked?.contains(viewer) ?: viewer.canSee(owner)
                    if (!visible) continue
                }
                val fraction = snapshot.density.fraction
                for (cmd in emission.commands) sent += send(viewer, cmd, fraction)
            }
        }
        return sent
    }

    /** Sends [cmd] to [viewer], keeping an evenly spread `fraction` of its points. */
    private fun send(viewer: Player, cmd: DrawCommand, fraction: Double): Int {
        val coords = cmd.coords
        val n = cmd.points
        var sent = 0
        var accumulator = 1.0 - 1e-9 // the first point is always drawn
        for (i in 0 until n) {
            accumulator += fraction
            if (accumulator < 1.0) continue
            accumulator -= 1.0
            val o = i * 3
            viewer.spawnParticle(
                cmd.particle,
                coords[o], coords[o + 1], coords[o + 2],
                cmd.count,
                cmd.offsetX, cmd.offsetY, cmd.offsetZ,
                cmd.extra,
                cmd.data,
            )
            sent++
        }
        return sent
    }
}
