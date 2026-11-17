package dev.jeary.hanami.menu.impl

import dev.jeary.hanami.config.MenuAction
import dev.jeary.hanami.config.MenuLayout
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.menu.LayoutDrawing
import dev.jeary.hanami.menu.Menu
import dev.jeary.hanami.menu.MenuContext
import dev.jeary.hanami.menu.drawLayout
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** Per-player viewing preferences: see others' cosmetics, and particle density. */
class SettingsMenu(viewer: Player, override val context: MenuContext) : Menu(viewer), LayoutDrawing {

    override val layout: MenuLayout get() = context.menus.settings
    override val size: Int get() = layout.size

    override fun title(): String = context.legacyTitle(layout.title)

    override fun render() = drawLayout(viewer, ::set)

    override fun tags(): Array<TagResolver> {
        val profile = context.core.profiles[viewer]
        val showOthers = profile?.showOthers ?: true
        val level = profile?.density?.id?.replaceFirstChar(Char::uppercase) ?: "High"
        return arrayOf(tag("state", if (showOthers) "On" else "Off"), tag("level", level))
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        val core = context.core
        val profile = core.profiles[viewer] ?: return core.messenger.send(viewer, "profile.loading")
        when (action) {
            MenuAction.ToggleShowOthers -> {
                profile.showOthers = !profile.showOthers
                core.messenger.send(viewer, if (profile.showOthers) "settings.show-others-on" else "settings.show-others-off")
                refresh()
            }
            MenuAction.CycleDensity -> {
                profile.density = profile.density.next()
                core.messenger.send(viewer, "settings.density", tag("level", profile.density.id.replaceFirstChar(Char::uppercase)))
                refresh()
            }
            else -> context.navigate(viewer, action)
        }
    }
}
