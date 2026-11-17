package dev.jeary.hanami.command

import dev.jeary.hanami.command.parser.CosmeticArgumentParser.Companion.cosmeticParser
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.unlock.GrantResult
import dev.jeary.hanami.core.unlock.RevokeResult
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import java.util.UUID
import java.util.logging.Level

/**
 * `/hanami give|revoke <player> <cosmetic>` — permanent unlocks that work for offline players
 * too (edited straight in storage, off the main thread).
 */
class UnlockCommands(private val core: HanamiCore, private val commands: HanamiCommands) {

    private val messenger get() = core.messenger

    fun register() {
        val players = SuggestionProvider.blockingStrings<CommandSender> { _, _ -> Bukkit.getOnlinePlayers().map { it.name } }
        commands.register(
            commands.root.literal("give")
                .permission(Permissions.GIVE)
                .required("player", stringParser(), players)
                .required("cosmetic", cosmeticParser({ core.registry }))
                .handler { ctx -> give(ctx.sender(), ctx.get("player"), ctx.get("cosmetic")) },
        )
        commands.register(
            commands.root.literal("revoke")
                .permission(Permissions.REVOKE)
                .required("player", stringParser(), players)
                .required("cosmetic", cosmeticParser({ core.registry }))
                .handler { ctx -> revoke(ctx.sender(), ctx.get("player"), ctx.get("cosmetic")) },
        )
    }

    private fun give(sender: CommandSender, name: String, cosmetic: CosmeticDefinition) {
        val target = resolve(name) ?: return messenger.send(sender, "command.player-not-found", tag("player", name))
        val tags = arrayOf(parsedTag("cosmetic", cosmetic.displayName), tag("player", target.name ?: name))
        core.unlockOps.grant(target, cosmetic).thenAccept { result ->
            when (result) {
                GrantResult.Granted -> {
                    messenger.send(sender, "unlock.give-success", *tags)
                    target.player?.let { messenger.send(it, "unlock.received", *tags) }
                }
                GrantResult.AlreadyOwned -> messenger.send(sender, "unlock.give-already", *tags)
                GrantResult.Cancelled -> messenger.send(sender, "unlock.cancelled")
                is GrantResult.Failed -> failed(sender, result.error)
            }
        }
    }

    private fun revoke(sender: CommandSender, name: String, cosmetic: CosmeticDefinition) {
        val target = resolve(name) ?: return messenger.send(sender, "command.player-not-found", tag("player", name))
        val tags = arrayOf(parsedTag("cosmetic", cosmetic.displayName), tag("player", target.name ?: name))
        core.unlockOps.revoke(target, cosmetic).thenAccept { result ->
            when (result) {
                RevokeResult.Revoked -> messenger.send(sender, "unlock.revoke-success", *tags)
                RevokeResult.NotGranted -> messenger.send(sender, "unlock.revoke-missing", *tags)
                is RevokeResult.Failed -> failed(sender, result.error)
            }
        }
    }

    private fun failed(sender: CommandSender, error: Throwable) {
        core.plugin.logger.log(Level.SEVERE, "Storage error while changing an unlock", error)
        messenger.send(sender, "command.error")
    }

    /**
     * Finds a player by name or UUID without any blocking web lookup (which
     * `Bukkit.getOfflinePlayer(String)` may do on the main thread).
     */
    private fun resolve(input: String): OfflinePlayer? {
        Bukkit.getPlayerExact(input)?.let { return it }
        runCatching { UUID.fromString(input) }.getOrNull()?.let { uuid ->
            return Bukkit.getOfflinePlayer(uuid).takeIf { it.isOnline || it.hasPlayedBefore() }
        }
        return Bukkit.getOfflinePlayers().firstOrNull { it.name.equals(input, ignoreCase = true) }
    }
}
