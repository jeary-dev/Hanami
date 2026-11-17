package dev.jeary.hanami.api.event

import dev.jeary.hanami.api.cosmetic.CosmeticView
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/** Base for cancellable Hanami player events. All are fired on the main thread. */
abstract class CancellableHanamiEvent(player: Player) : PlayerEvent(player), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }
}

/**
 * Fired before a player equips a cosmetic. Cancel to keep the current one.
 * [previous] is what was in the same slot before, if anything.
 */
class CosmeticEquipEvent(
    player: Player,
    val cosmetic: CosmeticView,
    val previous: CosmeticView?,
) : CancellableHanamiEvent(player) {
    override fun getHandlers() = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

/** Fired before a player's cosmetic is removed from its slot. */
class CosmeticUnequipEvent(
    player: Player,
    val cosmetic: CosmeticView,
) : CancellableHanamiEvent(player) {
    override fun getHandlers() = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

/** How a cosmetic was unlocked. */
enum class UnlockSource { PURCHASE, ADMIN, API }

/**
 * Fired before a cosmetic is permanently unlocked for a player (bought or granted).
 * The player may be offline when an admin grants it, hence [OfflinePlayer]; this is not a
 * [PlayerEvent].
 */
class CosmeticUnlockEvent(
    val player: OfflinePlayer,
    val cosmetic: CosmeticView,
    val source: UnlockSource,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers() = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

/** Why a one-shot effect is playing. */
enum class PlayCause { KILL, COMMAND, PREVIEW }

/** Fired before a one-shot (emote / kill) effect plays at [location]. */
class CosmeticPlayEvent(
    player: Player,
    val cosmetic: CosmeticView,
    val location: Location,
    val cause: PlayCause,
) : CancellableHanamiEvent(player) {
    override fun getHandlers() = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}
