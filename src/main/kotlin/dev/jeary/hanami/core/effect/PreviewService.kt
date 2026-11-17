package dev.jeary.hanami.core.effect

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import java.util.UUID

/**
 * Temporary, private try-ons. While a preview runs it replaces the player's equipped cosmetic of
 * the same type and is visible only to them, so locked cosmetics can be shown without being
 * given away. Main-thread owned.
 */
class PreviewService {

    class Preview(val definition: CosmeticDefinition, val expiresAtTick: Long)

    private val previews = HashMap<UUID, Preview>()

    fun start(uuid: UUID, definition: CosmeticDefinition, untilTick: Long) {
        previews[uuid] = Preview(definition, untilTick)
    }

    /** The running preview of [type], if any; expired previews are dropped. */
    fun active(uuid: UUID, type: CosmeticType, tick: Long): CosmeticDefinition? {
        val preview = previews[uuid] ?: return null
        if (tick > preview.expiresAtTick) {
            previews.remove(uuid)
            return null
        }
        return preview.definition.takeIf { it.type == type }
    }

    fun stop(uuid: UUID) {
        previews.remove(uuid)
    }

    fun clear() = previews.clear()
}
