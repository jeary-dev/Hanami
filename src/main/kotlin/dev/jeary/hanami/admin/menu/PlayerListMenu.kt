package dev.jeary.hanami.admin.menu

import dev.jeary.hanami.admin.AdminService
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.menu.MenuButton
import dev.jeary.hanami.menu.PaginatedMenu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/** Every online player as a head; click one to manage their cosmetics. */
class PlayerListMenu(viewer: Player, private val admin: AdminService) : PaginatedMenu<Player>(viewer) {

    override val size = 54
    override val contentSlots = (0 until 45).toList()

    override fun title() = admin.title("<#b8485f>花</#b8485f> <#3d3437>Players <#8a7a7f>(${page + 1}/$pageCount)")

    override fun entries(): List<Player> = Bukkit.getOnlinePlayers().sortedBy { it.name.lowercase() }

    override fun button(entry: Player): MenuButton {
        val core = admin.core
        val profile = core.profiles[entry]
        val worn = CosmeticType.entries.mapNotNull { type ->
            profile?.equippedId(type)?.let(core.registry::get)?.let { "<#9c8a8f>${admin.typeName(type)}: <reset>${it.displayName}" }
        }
        val unlocked = core.registry.all.count { core.unlocks.isUnlocked(entry, it, profile) }
        val lore = buildList {
            add("<#9c8a8f>Unlocked: <#ffd1dc>$unlocked<#9c8a8f>/${core.registry.size}")
            if (worn.isEmpty()) add("<#6b5b60>Wearing nothing") else addAll(worn)
            add("")
            add("<#6b5b60>Click to manage")
        }
        return MenuButton(admin.item(Material.PLAYER_HEAD, "<#f3d1d9>${entry.name}", lore, head = entry)) {
            admin.openPlayer(viewer, entry)
        }
    }

    override fun render() {
        val filler = admin.filler
        (45 until 54).forEach { set(it, filler) }
        renderPage()
        set(45, admin.item(Material.ARROW, "<#f3d1d9>Back")) { admin.openAdmin(viewer) }
        if (hasPrevious) set(48, admin.item(Material.SPECTRAL_ARROW, "<#f3d1d9>Previous page")) { goTo(page - 1) }
        set(
            49,
            admin.item(
                Material.BOOK, "<#f3d1d9>Offline players",
                listOf("<#9c8a8f>Use <#ffd1dc>/hanami give \\<player> \\<id>","<#9c8a8f>— it works while they're offline."),
            ),
        )
        if (hasNext) set(50, admin.item(Material.SPECTRAL_ARROW, "<#f3d1d9>Next page")) { goTo(page + 1) }
    }
}
