package dev.jeary.hanami.menu.impl

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.command.HanamiCommands
import dev.jeary.hanami.config.MenuAction
import dev.jeary.hanami.config.MenuItem
import dev.jeary.hanami.config.MenuLayout
import dev.jeary.hanami.core.EquipResult
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.UnequipResult
import dev.jeary.hanami.core.unlock.Ownership
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.menu.ItemBuilder
import dev.jeary.hanami.menu.LayoutDrawing
import dev.jeary.hanami.menu.MenuButton
import dev.jeary.hanami.menu.MenuContext
import dev.jeary.hanami.menu.PaginatedMenu
import dev.jeary.hanami.menu.drawLayout
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * All cosmetics of one type, paginated. Each icon shows its state (wearing / unlocked /
 * purchasable / locked); left-click wears, takes off or buys, right-click previews.
 */
class CategoryMenu(
    viewer: Player,
    override val context: MenuContext,
    private val type: CosmeticType,
) : PaginatedMenu<CosmeticDefinition>(viewer), LayoutDrawing {

    private val core get() = context.core

    override val layout: MenuLayout get() = context.menus.category
    override val size: Int get() = layout.size
    override val contentSlots: List<Int> get() = layout.contentSlots

    private val categoryName get() = context.menus.categoryNames[type] ?: type.id

    override fun entries(): List<CosmeticDefinition> = core.registry.ofType(type)

    override fun title(): String = context.legacyTitle(layout.title, *tags())

    override fun tags(): Array<TagResolver> = arrayOf(
        tag("category", categoryName),
        tag("page", page + 1),
        tag("pages", pageCount),
    )

    override fun isVisible(item: MenuItem): Boolean = when (item.action) {
        MenuAction.PreviousPage -> hasPrevious
        MenuAction.NextPage -> hasNext
        MenuAction.UnequipCategory -> core.profiles[viewer]?.equippedId(type) != null
        else -> true
    }

    override fun render() {
        drawLayout(viewer, ::set)
        renderPage()
    }

    override fun button(entry: CosmeticDefinition): MenuButton {
        val profile = core.profiles[viewer]
        val equipped = profile?.equippedId(type) == entry.id
        val ownership = core.unlocks.ownership(viewer, entry, profile)
        val lore = context.menus.cosmeticLore
        val messenger = core.messenger
        val priceTag = tag("price", (ownership as? Ownership.Purchasable)?.let { formatPrice(it.price) } ?: "")
        val stateLines = when {
            equipped -> lore.equipped
            ownership is Ownership.Owned -> lore.unlocked
            ownership is Ownership.Purchasable -> lore.purchasable
            else -> lore.locked
        }

        val icon = ItemBuilder(entry.icon.material)
            .name(messenger.legacyItemText(entry.displayName))
            .lore((entry.lore + stateLines).map { messenger.legacyItemText(it, priceTag) })
            .customModelData(entry.icon.customModelData)
            .glow(equipped || entry.icon.glow)
            .build()

        return MenuButton(icon) { click -> onCosmeticClick(entry, click, equipped, ownership) }
    }

    private fun onCosmeticClick(entry: CosmeticDefinition, click: ClickType, equipped: Boolean, ownership: Ownership) {
        context.clickSound(viewer)
        val name = parsedTag("cosmetic", entry.displayName)
        if (click.isRightClick) {
            viewer.closeInventory()
            core.cosmetics.preview(viewer, entry, HanamiCommands.PREVIEW_SECONDS)
            val key = if (type == CosmeticType.EMOTE) "preview.start" else if (type == CosmeticType.PROJECTILE) "preview.start-projectile" else "preview.start-third-person"
            core.messenger.send(viewer, key, name, tag("seconds", HanamiCommands.PREVIEW_SECONDS))
            return
        }
        when {
            equipped -> when (core.cosmetics.unequip(viewer, type)) {
                is UnequipResult.Unequipped -> core.messenger.send(viewer, "unequip.success", name)
                UnequipResult.Cancelled -> core.messenger.send(viewer, "unequip.cancelled", tag("type", categoryName))
                else -> Unit
            }
            ownership is Ownership.Owned -> when (core.cosmetics.equip(viewer, entry)) {
                is EquipResult.Equipped -> core.messenger.send(viewer, "equip.success", name)
                EquipResult.Cancelled -> core.messenger.send(viewer, "equip.cancelled", name)
                EquipResult.ProfileLoading -> core.messenger.send(viewer, "profile.loading")
                else -> Unit
            }
            ownership is Ownership.Purchasable -> {
                context.openConfirm(viewer, entry, type)
                return
            }
            else -> core.messenger.send(viewer, "equip.locked", name)
        }
        refresh()
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        when (action) {
            MenuAction.PreviousPage -> goTo(page - 1)
            MenuAction.NextPage -> goTo(page + 1)
            MenuAction.UnequipCategory -> {
                if (core.cosmetics.unequip(viewer, type) is UnequipResult.Unequipped) {
                    core.messenger.send(viewer, "unequip.success", parsedTag("cosmetic", categoryName))
                }
                refresh()
            }
            else -> context.navigate(viewer, action)
        }
    }

    private fun formatPrice(price: Double): String = core.economy?.format(price) ?: price.toString()
}
