package dev.jeary.hanami.core

import dev.jeary.hanami.config.Messages
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

/**
 * All player-facing text goes through here: MiniMessage templates from `messages.yml`, rendered
 * with Adventure and delivered by adventure-platform so it works on Spigot as well as Paper.
 */
class Messenger(plugin: Plugin, messages: Messages) {

    val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val audiences: BukkitAudiences = BukkitAudiences.create(plugin)

    @Volatile
    var messages: Messages = messages

    /** Section-sign serializer with hex support, for Bukkit APIs that only accept strings. */
    private val legacy = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    fun component(key: String, vararg tags: TagResolver): Component = parse(messages.raw(key), *tags)

    fun parse(raw: String, vararg tags: TagResolver): Component =
        miniMessage.deserialize(raw, TagResolver.resolver(Placeholder.parsed("prefix", messages.prefix), *tags))

    fun send(sender: CommandSender, key: String, vararg tags: TagResolver) {
        val raw = messages.raw(key)
        if (raw.isBlank()) return // an admin blanked the message to disable it
        audiences.sender(sender).sendMessage(parse(raw, *tags))
    }

    fun sendComponent(sender: CommandSender, component: Component) {
        audiences.sender(sender).sendMessage(component)
    }

    fun actionBar(sender: CommandSender, key: String, vararg tags: TagResolver) {
        val raw = messages.raw(key)
        if (raw.isBlank()) return
        audiences.sender(sender).sendActionBar(parse(raw, *tags))
    }

    /** MiniMessage → legacy string for item names and lore (italics explicitly turned off). */
    fun legacyItemText(raw: String, vararg tags: TagResolver): String =
        legacy.serialize(parse(raw, *tags).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))

    fun legacy(component: Component): String = legacy.serialize(component)

    fun plain(raw: String): String = miniMessage.stripTags(raw)

    fun close() = audiences.close()

    companion object {
        fun tag(name: String, value: Any): TagResolver = Placeholder.unparsed(name, value.toString())
        fun parsedTag(name: String, miniMessage: String): TagResolver = Placeholder.parsed(name, miniMessage)
        fun componentTag(name: String, component: Component): TagResolver = Placeholder.component(name, component)
    }
}
