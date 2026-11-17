package dev.jeary.hanami.menu

import dev.jeary.hanami.config.MenuAction
import dev.jeary.hanami.config.MenuItem
import dev.jeary.hanami.config.MenuLayout
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * Glue between a data-driven [MenuLayout] from `menus.yml` and the [Menu] framework: draws the
 * filler and the configured buttons, and routes each button's [MenuAction] to [onAction].
 * Concrete menus add their dynamic content (cosmetics, settings states) on top.
 */
interface LayoutDrawing {
    val layout: MenuLayout
    val context: MenuContext

    /** Placeholders available to every configured item in this menu. */
    fun tags(): Array<TagResolver> = emptyArray()

    /** Extra placeholders for one specific item (e.g. per-category counts). */
    fun tagsFor(item: MenuItem): Array<TagResolver> = emptyArray()

    /** Returns false for buttons that should not be shown right now (e.g. "next" on the last page). */
    fun isVisible(item: MenuItem): Boolean = true

    fun onAction(action: MenuAction, click: ClickType)
}

/** Draws the static part of a layout into [menu] via [place]. */
fun LayoutDrawing.drawLayout(viewer: Player, place: (Int, MenuButton) -> Unit) {
    val filler = context.icon(context.menus.filler)
    layout.rows.forEachIndexed { row, line ->
        line.forEachIndexed { col, char ->
            val slot = row * 9 + col
            when (char) {
                MenuLayout.EMPTY_CHAR, layout.contentChar -> Unit
                MenuLayout.FILLER_CHAR -> place(slot, MenuButton(filler))
                else -> {
                    val item = layout.items[char] ?: return@forEachIndexed
                    if (!isVisible(item)) {
                        place(slot, MenuButton(filler))
                        return@forEachIndexed
                    }
                    val icon = context.icon(item, *tags(), *tagsFor(item))
                    place(slot, MenuButton(icon) { click ->
                        if (item.action != MenuAction.None) context.clickSound(viewer)
                        onAction(item.action, click)
                    })
                }
            }
        }
    }
}
