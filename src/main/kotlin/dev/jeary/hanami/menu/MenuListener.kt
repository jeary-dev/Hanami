package dev.jeary.hanami.menu

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * The one listener behind every Hanami menu. It cancels all item movement in our inventories
 * (including shift-clicks and drags from the player's own inventory) and forwards top-inventory
 * clicks to the owning [Menu]. It also tracks open menus so a reload can close them safely.
 */
class MenuListener : Listener {

    private val open = HashMap<Player, Menu>()

    /** Every menu currently open, for closing them all on reload/disable. */
    fun openMenus(): Collection<Menu> = open.values.toList()

    fun closeAll() {
        openMenus().forEach { it.viewer.closeInventory() }
        open.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        event.isCancelled = true
        val topSize = event.inventory.size
        if (event.rawSlot in 0 until topSize) menu.click(event.rawSlot, event.click)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Menu) event.isCancelled = true
    }

    @EventHandler
    fun onOpen(event: org.bukkit.event.inventory.InventoryOpenEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        open[menu.viewer] = menu
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        if (open[menu.viewer] === menu) open.remove(menu.viewer)
        menu.onClose()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        open.remove(event.player)
    }
}
