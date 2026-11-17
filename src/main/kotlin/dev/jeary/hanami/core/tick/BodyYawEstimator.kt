package dev.jeary.hanami.core.tick

import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Spigot exposes only the *head* yaw. Wings pinned to the head would whip around every time a
 * player glances sideways, so on Spigot we reproduce the vanilla body-rotation rules instead:
 * the torso turns towards the direction of travel and is dragged along once the head turns more
 * than ~50° away from it. (On Paper the real value comes from `LivingEntity#getBodyYaw`.)
 */
class BodyYawEstimator {
    private val bodies = HashMap<UUID, Float>()

    fun update(uuid: UUID, headYaw: Float, dx: Double, dz: Double): Float {
        var body = bodies[uuid] ?: headYaw

        if (dx * dx + dz * dz > MOVING_SQ) {
            var moveYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            // Walking backwards: vanilla keeps the torso facing forward.
            if (abs(wrap(moveYaw - headYaw)) > 95f) moveYaw += 180f
            body += wrap(moveYaw - body) * TURN_RATE
        }

        val diff = wrap(headYaw - body)
        if (abs(diff) > MAX_HEAD_OFFSET) body = headYaw - sign(diff) * MAX_HEAD_OFFSET

        body = wrap(body)
        bodies[uuid] = body
        return body
    }

    fun forget(uuid: UUID) {
        bodies.remove(uuid)
    }

    companion object {
        private const val MOVING_SQ = 0.0025 // 0.05 blocks/tick
        private const val TURN_RATE = 0.3f
        private const val MAX_HEAD_OFFSET = 50f

        /** Wraps an angle into [-180, 180). */
        fun wrap(degrees: Float): Float {
            var d = degrees % 360f
            if (d >= 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
