package dev.jeary.hanami.menu

import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.MenuConfig
import dev.jeary.hanami.config.MenuItem
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.unlock.UnlockOperations
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.menu.impl.CategoryMenu
import dev.jeary.hanami.menu.impl.ConfirmMenu
import dev.jeary.hanami.menu.impl.MainMenu
import dev.jeary.hanami.menu.impl.SettingsMenu
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Shared services every menu needs — config, text rendering, navigation. */
interface MenuContext {
    val core: HanamiCore
    val unlockOps: UnlockOperations
    val menus: MenuConfig

    fun icon(item: MenuItem, vararg tags: TagResolver): ItemStack
    fun clickSound(player: Player)

    fun openMain(player: Player)
    fun openCategory(player: Player, type: CosmeticType)
    fun openSettings(player: Player)
    fun openConfirm(player: Player, cosmetic: CosmeticDefinition, returnTo: CosmeticType)
}

class MenuService(override val core: HanamiCore) : MenuContext {

    override val unlockOps: UnlockOperations get() = core.unlockOps

    val listener = MenuListener()

    override val menus: MenuConfig get() = core.config.menus

    fun enable() {
        core.plugin.server.pluginManager.registerEvents(listener, core.plugin)
        // Configured items may have changed: close every open menu before the swap is visible.
        core.onReload { listener.closeAll() }
    }

    fun disable() = listener.closeAll()

    override fun icon(item: MenuItem, vararg tags: TagResolver): ItemStack {
        val messenger = core.messenger
        return ItemBuilder(item.material)
            .name(messenger.legacyItemText(item.name, *tags))
            .lore(item.lore.map { messenger.legacyItemText(it, *tags) })
            .customModelData(item.customModelData)
            .glow(item.glow)
            .build()
    }

    override fun clickSound(player: Player) {
        val sound = menus.clickSound ?: return
        player.playSound(player.location, sound, 0.6f, 1.6f)
    }

    override fun openMain(player: Player) = MainMenu(player, this).open()

    override fun openCategory(player: Player, type: CosmeticType) = CategoryMenu(player, this, type).open()

    override fun openSettings(player: Player) = SettingsMenu(player, this).open()

    override fun openConfirm(player: Player, cosmetic: CosmeticDefinition, returnTo: CosmeticType) =
        ConfirmMenu(player, this, cosmetic, returnTo).open()
}
