package dev.jeary.hanami.menu.impl

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.MenuAction
import dev.jeary.hanami.config.MenuLayout
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.unlock.PurchaseResult
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.menu.ItemBuilder
import dev.jeary.hanami.menu.LayoutDrawing
import dev.jeary.hanami.menu.Menu
import dev.jeary.hanami.menu.MenuContext
import dev.jeary.hanami.menu.drawLayout
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** "Buy X for Y?" — a purchase never happens on a single misclick. */
class ConfirmMenu(
    viewer: Player,
    override val context: MenuContext,
    private val cosmetic: CosmeticDefinition,
    private val returnTo: CosmeticType,
) : Menu(viewer), LayoutDrawing {

    private val core get() = context.core

    override val layout: MenuLayout get() = context.menus.confirm
    override val size: Int get() = layout.size

    override fun title(): String = context.legacyTitle(layout.title, *tags())

    override fun tags(): Array<TagResolver> {
        val economy = core.economy
        val price = cosmetic.unlock.price ?: 0.0
        return arrayOf(
            parsedTag("cosmetic", cosmetic.displayName),
            tag("price", economy?.format(price) ?: price.toString()),
            tag("balance", economy?.let { it.format(it.balance(viewer)) } ?: "-"),
        )
    }

    override fun render() {
        drawLayout(viewer, ::set)
        val messenger = core.messenger
        val icon = ItemBuilder(cosmetic.icon.material)
            .name(messenger.legacyItemText(cosmetic.displayName))
            .lore(cosmetic.lore.map { messenger.legacyItemText(it) })
            .customModelData(cosmetic.icon.customModelData)
            .glow(cosmetic.icon.glow)
            .build()
        layout.contentSlots.forEach { set(it, icon) }
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        when (action) {
            MenuAction.Confirm -> {
                buy()
                context.openCategory(viewer, returnTo)
            }
            MenuAction.Cancel -> context.openCategory(viewer, returnTo)
            else -> context.navigate(viewer, action)
        }
    }

    private fun buy() {
        val messenger = core.messenger
        val economy = core.economy
        val name = parsedTag("cosmetic", cosmetic.displayName)
        fun money(amount: Double) = economy?.format(amount) ?: amount.toString()
        when (val result = core.unlockOps.purchase(viewer, cosmetic)) {
            is PurchaseResult.Success -> {
                messenger.send(viewer, "purchase.success", name, tag("price", money(result.price)))
                core.cosmetics.equip(viewer, cosmetic)
            }
            is PurchaseResult.InsufficientFunds -> messenger.send(
                viewer, "purchase.insufficient-funds", tag("price", money(result.price)), tag("balance", money(result.balance)),
            )
            is PurchaseResult.Failed -> messenger.send(viewer, "purchase.failed", tag("reason", result.reason))
            PurchaseResult.AlreadyOwned -> messenger.send(viewer, "equip.already", name)
            PurchaseResult.Cancelled -> messenger.send(viewer, "unlock.cancelled")
            PurchaseResult.ProfileLoading -> messenger.send(viewer, "profile.loading")
            PurchaseResult.NoEconomy, PurchaseResult.NotForSale -> messenger.send(viewer, "purchase.unavailable")
        }
    }
}
