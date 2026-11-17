package dev.jeary.hanami.menu.impl

import dev.jeary.hanami.config.MenuAction
import dev.jeary.hanami.config.MenuItem
import dev.jeary.hanami.config.MenuLayout
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.menu.LayoutDrawing
import dev.jeary.hanami.menu.Menu
import dev.jeary.hanami.menu.MenuContext
import dev.jeary.hanami.menu.drawLayout
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** The landing page: one button per category, plus settings. */
class MainMenu(viewer: Player, override val context: MenuContext) : Menu(viewer), LayoutDrawing {

    override val layout: MenuLayout get() = context.menus.main
    override val size: Int get() = layout.size

    override fun title(): String = context.legacyTitle(layout.title)

    override fun render() = drawLayout(viewer, ::set)

    /** Category buttons show what's worn and how many are unlocked. */
    override fun tagsFor(item: MenuItem): Array<TagResolver> {
        val action = item.action as? MenuAction.OpenCategory ?: return emptyArray()
        val core = context.core
        val profile = core.profiles[viewer]
        val all = core.registry.ofType(action.type)
        val equipped = profile?.equippedId(action.type)?.let(core.registry::get)
        return arrayOf(
            parsedTag("equipped", equipped?.displayName ?: "None"),
            tag("unlocked", all.count { core.unlocks.isUnlocked(viewer, it, profile) }),
            tag("total", all.size),
        )
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        context.navigate(viewer, action)
    }
}

/** Renders a MiniMessage title to the legacy string Bukkit inventories need. */
fun MenuContext.legacyTitle(raw: String, vararg tags: TagResolver): String =
    core.messenger.legacy(core.messenger.parse(raw, *tags))

/** Shared handling of navigation actions; returns true when [action] was one of them. */
fun MenuContext.navigate(viewer: Player, action: MenuAction): Boolean {
    when (action) {
        is MenuAction.OpenCategory -> openCategory(viewer, action.type)
        MenuAction.OpenMain -> openMain(viewer)
        MenuAction.OpenSettings -> openSettings(viewer)
        MenuAction.Close -> viewer.closeInventory()
        else -> return false
    }
    return true
}
