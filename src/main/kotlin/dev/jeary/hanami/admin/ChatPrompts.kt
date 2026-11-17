package dev.jeary.hanami.admin

import dev.jeary.hanami.core.Messenger
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks a player to type one line in chat (a name, an id, a colour) and hands the answer back on
 * the main thread. Inventories can't take text input on every version, so the menu closes,
 * the player answers in chat, and the caller reopens the menu.
 *
 * The answer never reaches public chat. Typing `cancel` (or waiting [TIMEOUT_SECONDS]) gives up.
 */
class ChatPrompts(private val plugin: Plugin, private val messenger: Messenger) : Listener {

    private class Pending(val onAnswer: (String) -> Unit, val onCancel: () -> Unit)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun ask(
        player: Player,
        messageKey: String,
        vararg tags: TagResolver,
        onCancel: () -> Unit = {},
        onAnswer: (String) -> Unit,
    ) {
        val prompt = Pending(onAnswer, onCancel)
        pending[player.uniqueId] = prompt // a newer question replaces an unanswered one
        player.closeInventory()
        messenger.send(player, messageKey, *tags)
        messenger.send(player, "admin.prompt-hint", Messenger.tag("seconds", TIMEOUT_SECONDS))
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (pending.remove(player.uniqueId, prompt)) {
                if (player.isOnline) messenger.send(player, "admin.prompt-timeout")
                prompt.onCancel()
            }
        }, TIMEOUT_SECONDS * 20L)
    }

    fun isWaiting(player: Player) = pending.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val prompt = pending.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val answer = event.message.trim()
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (answer.equals("cancel", ignoreCase = true)) {
                messenger.send(event.player, "admin.prompt-cancelled")
                prompt.onCancel()
            } else {
                prompt.onAnswer(answer)
            }
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
