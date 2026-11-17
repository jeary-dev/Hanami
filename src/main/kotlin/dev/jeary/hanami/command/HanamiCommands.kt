package dev.jeary.hanami.command

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.event.PlayCause
import dev.jeary.hanami.command.parser.CosmeticArgumentParser.Companion.cosmeticParser
import dev.jeary.hanami.command.parser.CosmeticTypeParser.Companion.typeParser
import dev.jeary.hanami.command.parser.UnknownCosmeticException
import dev.jeary.hanami.command.parser.UnknownTypeException
import dev.jeary.hanami.command.parser.WrongCosmeticTypeException
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.EquipResult
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.PlayResult
import dev.jeary.hanami.core.UnequipResult
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.EmoteTrigger
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.Command
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.bukkit.parser.PlayerParser.playerParser
import org.incendo.cloud.exception.ArgumentParseException
import org.incendo.cloud.exception.CommandExecutionException
import org.incendo.cloud.exception.InvalidCommandSenderException
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import java.util.Locale
import java.util.logging.Level

/**
 * `/hanami` and all its subcommands, built with Incendo Cloud. Every command declares its
 * permission so Cloud hides it from tab completion for players who can't use it.
 *
 * Feature-specific subcommands (menu, give/revoke) are contributed by their packages through
 * [register], which keeps this class from depending on every feature.
 */
class HanamiCommands(private val core: HanamiCore) {

    val manager: LegacyPaperCommandManager<CommandSender> =
        LegacyPaperCommandManager.createNative(core.plugin, ExecutionCoordinator.simpleCoordinator())

    private val messenger get() = core.messenger

    /** The `/hanami` root (aliases: /sakura, /cosmetics) that subcommands extend. */
    val root: Command.Builder<CommandSender> = manager.commandBuilder("hanami", "sakura", "cosmetics")

    fun enable() {
        when {
            manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER) -> runCatching { manager.registerBrigadier() }
            manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION) -> manager.registerAsynchronousCompletions()
        }
        registerExceptionHandlers()

        register(root.literal("help").handler { ctx -> messenger.send(ctx.sender(), "help") })
        register(
            root.literal("equip")
                .permission(Permissions.EQUIP)
                .required("type", typeParser())
                .required("cosmetic", cosmeticParser({ core.registry }, typeArgument = "type"))
                .optional("player", playerParser())
                .handler { ctx -> equip(ctx.sender(), ctx.get("cosmetic"), ctx.optional<Player>("player").orElse(null)) },
        )
        register(
            root.literal("unequip")
                .permission(Permissions.EQUIP)
                .senderType(Player::class.java)
                .required("type", typeParser())
                .optional("cosmetic", cosmeticParser({ core.registry }, typeArgument = "type"))
                .handler { ctx -> unequip(ctx.sender(), ctx.get("type"), ctx.optional<CosmeticDefinition>("cosmetic").orElse(null)) },
        )
        register(
            root.literal("preview")
                .permission(Permissions.PREVIEW)
                .senderType(Player::class.java)
                .required("cosmetic", cosmeticParser({ core.registry }))
                .handler { ctx -> preview(ctx.sender(), ctx.get("cosmetic")) },
        )
        register(
            root.literal("emote")
                .permission(Permissions.EMOTE)
                .senderType(Player::class.java)
                .handler { ctx -> emote(ctx.sender()) },
        )
        register(
            root.literal("reload")
                .permission(Permissions.RELOAD)
                .handler { ctx -> reload(ctx.sender()) },
        )
        register(
            root.literal("debug")
                .permission(Permissions.DEBUG)
                .handler { ctx -> DebugReport(core).send(ctx.sender()) },
        )
    }

    fun register(builder: Command.Builder<out CommandSender>) {
        manager.command(builder)
    }

    // ── handlers ─────────────────────────────────────────────────────────

    private fun equip(sender: CommandSender, cosmetic: CosmeticDefinition, target: Player?) {
        val player = target ?: sender as? Player ?: run {
            messenger.send(sender, "command.players-only")
            return
        }
        val forOther = player != sender
        if (forOther && !sender.hasPermission(Permissions.EQUIP_OTHERS)) {
            messenger.send(sender, "command.no-permission")
            return
        }
        val name = parsedTag("cosmetic", cosmetic.displayName)
        val type = tag("type", typeName(cosmetic.type))
        when (core.cosmetics.equip(player, cosmetic, bypassLock = forOther)) {
            is EquipResult.Equipped -> {
                messenger.send(player, "equip.success", name, type)
                if (forOther) messenger.send(sender, "equip.other", name, tag("player", player.name))
            }
            EquipResult.AlreadyEquipped -> messenger.send(sender, "equip.already", name)
            EquipResult.Locked -> messenger.send(sender, "equip.locked", name)
            EquipResult.Cancelled -> messenger.send(sender, "equip.cancelled", name)
            EquipResult.ProfileLoading -> messenger.send(sender, "profile.loading")
        }
    }

    private fun unequip(player: Player, type: CosmeticType, cosmetic: CosmeticDefinition?) {
        val profile = core.profiles[player]
        if (cosmetic != null && profile?.equippedId(type) != cosmetic.id) {
            messenger.send(player, "unequip.not-equipped", parsedTag("cosmetic", cosmetic.displayName))
            return
        }
        when (val result = core.cosmetics.unequip(player, type)) {
            is UnequipResult.Unequipped -> messenger.send(
                player, "unequip.success",
                parsedTag("cosmetic", result.cosmetic?.displayName ?: typeName(type)),
                tag("type", typeName(type)),
            )
            UnequipResult.NothingEquipped -> messenger.send(player, "unequip.nothing", tag("type", typeName(type)))
            UnequipResult.Cancelled -> messenger.send(player, "unequip.cancelled", tag("type", typeName(type)))
            UnequipResult.ProfileLoading -> messenger.send(player, "profile.loading")
        }
    }

    private fun preview(player: Player, cosmetic: CosmeticDefinition) {
        core.cosmetics.preview(player, cosmetic, PREVIEW_SECONDS)
        val key = when (cosmetic.type) {
            CosmeticType.WINGS, CosmeticType.AURA, CosmeticType.TRAIL -> "preview.start-third-person"
            CosmeticType.PROJECTILE -> "preview.start-projectile"
            CosmeticType.EMOTE -> "preview.start"
        }
        messenger.send(player, key, parsedTag("cosmetic", cosmetic.displayName), tag("seconds", PREVIEW_SECONDS))
    }

    private fun emote(player: Player) {
        val profile = core.profiles[player] ?: return messenger.send(player, "profile.loading")
        val emote = profile.equippedId(CosmeticType.EMOTE)?.let(core.registry::get)
        val behavior = emote?.behavior as? Behavior.Emote
        if (emote == null || behavior == null) return messenger.send(player, "emote.none")
        if (EmoteTrigger.COMMAND !in behavior.triggers) {
            return messenger.send(player, "emote.not-playable", parsedTag("cosmetic", emote.displayName))
        }
        when (val result = core.cosmetics.play(player, emote, player.location, PlayCause.COMMAND)) {
            PlayResult.Played -> Unit
            is PlayResult.OnCooldown -> messenger.send(player, "emote.cooldown", tag("seconds", result.secondsLeft))
            PlayResult.Cancelled -> messenger.send(player, "emote.cancelled")
        }
    }

    private fun reload(sender: CommandSender) {
        val started = System.nanoTime()
        when (val result = core.reload()) {
            is ValidationResult.Valid -> {
                val ms = (System.nanoTime() - started) / 1_000_000
                messenger.send(
                    sender, "reload.success",
                    tag("count", result.value.cosmetics.size),
                    tag("ms", ms),
                )
                result.warnings.take(MAX_PROBLEMS_SHOWN).forEach { messenger.send(sender, "reload.problem", tag("problem", it)) }
                core.logProblems(result.warnings)
            }
            is ValidationResult.Invalid -> {
                messenger.send(sender, "reload.failed", tag("count", result.errors.size))
                result.errors.take(MAX_PROBLEMS_SHOWN).forEach { messenger.send(sender, "reload.problem", tag("problem", it)) }
                core.logProblems(result.errors)
            }
        }
    }

    fun typeName(type: CosmeticType): String = core.config.menus.categoryNames[type] ?: type.id

    // ── errors → messages.yml ────────────────────────────────────────────

    private fun registerExceptionHandlers() {
        val controller = manager.exceptionController()
        controller.registerHandler(InvalidSyntaxException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.invalid-syntax", tag("syntax", "/" + ctx.exception().correctSyntax()))
        }
        controller.registerHandler(NoPermissionException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.no-permission")
        }
        controller.registerHandler(InvalidCommandSenderException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.players-only")
        }
        controller.registerHandler(ArgumentParseException::class.java) { ctx ->
            val sender = ctx.context().sender()
            when (val cause = ctx.exception().cause) {
                is UnknownCosmeticException -> messenger.send(sender, "command.unknown-cosmetic", tag("input", cause.input))
                is UnknownTypeException -> messenger.send(
                    sender, "command.unknown-type",
                    tag("input", cause.input), tag("types", CosmeticType.entries.joinToString { it.id }),
                )
                is WrongCosmeticTypeException -> messenger.send(
                    sender, "command.wrong-type",
                    parsedTag("cosmetic", cause.cosmetic.displayName), tag("type", typeName(cause.expected)),
                )
                else -> messenger.send(sender, "command.invalid-argument", tag("reason", cause.message ?: "?"))
            }
        }
        controller.registerHandler(CommandExecutionException::class.java) { ctx ->
            core.plugin.logger.log(Level.SEVERE, "Error while running a Hanami command", ctx.exception().cause ?: ctx.exception())
            messenger.send(ctx.context().sender(), "command.error")
        }
    }

    companion object {
        const val PREVIEW_SECONDS = 5
        private const val MAX_PROBLEMS_SHOWN = 8

        fun formatMs(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
