package dev.jeary.hanami.admin.menu

import dev.jeary.hanami.admin.AdminService
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.command.Permissions
import dev.jeary.hanami.core.EquipResult
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.UnequipResult
import dev.jeary.hanami.core.unlock.GrantResult
import dev.jeary.hanami.core.unlock.RevokeResult
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.menu.MenuButton
import dev.jeary.hanami.menu.PaginatedMenu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.util.logging.Level

/**
 * One player's cosmetics, as an admin sees them: what they own and why, and what they wear.
 *
 * - **Left-click** gives the cosmetic permanently, **right-click** revokes a granted one.
 * - **Shift-left** puts it on them (even if locked), **shift-right** takes it off.
 */
class PlayerCosmeticsMenu(
    viewer: Player,
    private val admin: AdminService,
    private val target: Player,
    private val filter: CosmeticType?,
) : PaginatedMenu<CosmeticDefinition>(viewer) {

    private val core get() = admin.core

    override val size = 54
    override val contentSlots = (0 until 45).toList()

    override fun title() = admin.title(
        "<#b8485f>花</#b8485f> <#3d3437>${target.name} <#8a7a7f>· ${filter?.let(admin::typeName) ?: "All"} (${page + 1}/$pageCount)",
    )

    override fun entries(): List<CosmeticDefinition> = filter?.let(core.registry::ofType) ?: core.registry.all

    override fun button(entry: CosmeticDefinition): MenuButton {
        val profile = core.profiles[target]
        val equipped = profile?.equippedId(entry.type) == entry.id
        val granted = profile?.hasUnlocked(entry.id) == true
        val owned = core.unlocks.isUnlocked(target, entry, profile)
        val why = when {
            granted -> "<#a8e6a1>✔ Owned <#6b5b60>(granted or bought)"
            entry.unlock.default -> "<#a8e6a1>✔ Owned <#6b5b60>(free for everyone)"
            owned -> "<#a8e6a1>✔ Owned <#6b5b60>(by permission)"
            else -> "<#d98c8c>✘ Not owned"
        }
        val lore = buildList {
            add("<#6b5b60>${admin.typeName(entry.type)} · ${entry.id.value}")
            add(why)
            if (equipped) add("<#ffd1dc>★ Currently wearing")
            add("")
            add("<#9c8a8f>Left-click <#6b5b60>› <#f3d1d9>give")
            if (granted) add("<#9c8a8f>Right-click <#6b5b60>› <#f3d1d9>revoke")
            add(if (equipped) "<#9c8a8f>Shift-right <#6b5b60>› <#f3d1d9>take it off" else "<#9c8a8f>Shift-left <#6b5b60>› <#f3d1d9>put it on")
        }
        val icon = admin.item(entry.icon.material, entry.displayName, lore, glow = equipped)
        return MenuButton(icon) { click -> onClick(entry, click, equipped) }
    }

    private fun onClick(entry: CosmeticDefinition, click: ClickType, equipped: Boolean) {
        if (!target.isOnline) return admin.openPlayers(viewer)
        val messenger = core.messenger
        val tags = arrayOf(parsedTag("cosmetic", entry.displayName), tag("player", target.name))
        when (click) {
            ClickType.LEFT -> {
                if (!allowed(Permissions.GIVE)) return
                core.unlockOps.grant(target, entry).thenAccept { result ->
                    when (result) {
                        GrantResult.Granted -> {
                            messenger.send(viewer, "unlock.give-success", *tags)
                            messenger.send(target, "unlock.received", *tags)
                        }
                        GrantResult.AlreadyOwned -> messenger.send(viewer, "unlock.give-already", *tags)
                        GrantResult.Cancelled -> messenger.send(viewer, "unlock.cancelled")
                        is GrantResult.Failed -> failed(result.error)
                    }
                    refresh()
                }
            }
            ClickType.RIGHT -> {
                if (!allowed(Permissions.REVOKE)) return
                core.unlockOps.revoke(target, entry).thenAccept { result ->
                    when (result) {
                        RevokeResult.Revoked -> messenger.send(viewer, "unlock.revoke-success", *tags)
                        RevokeResult.NotGranted -> messenger.send(viewer, "unlock.revoke-missing", *tags)
                        is RevokeResult.Failed -> failed(result.error)
                    }
                    refresh()
                }
            }
            ClickType.SHIFT_LEFT -> {
                if (equipped || !allowed(Permissions.EQUIP_OTHERS)) return
                when (core.cosmetics.equip(target, entry, bypassLock = true)) {
                    is EquipResult.Equipped -> {
                        messenger.send(target, "equip.success", *tags)
                        messenger.send(viewer, "equip.other", *tags)
                    }
                    EquipResult.Cancelled -> messenger.send(viewer, "equip.cancelled", *tags)
                    EquipResult.ProfileLoading -> messenger.send(viewer, "profile.loading")
                    else -> Unit
                }
                refresh()
            }
            ClickType.SHIFT_RIGHT -> {
                if (!equipped || !allowed(Permissions.EQUIP_OTHERS)) return
                if (core.cosmetics.unequip(target, entry.type) is UnequipResult.Unequipped) {
                    messenger.send(viewer, "admin.unequipped-other", *tags)
                }
                refresh()
            }
            else -> Unit
        }
    }

    private fun allowed(permission: String): Boolean =
        viewer.hasPermission(permission).also { if (!it) core.messenger.send(viewer, "command.no-permission") }

    private fun failed(error: Throwable) {
        core.plugin.logger.log(Level.SEVERE, "Storage error while changing an unlock", error)
        core.messenger.send(viewer, "command.error")
    }

    override fun render() {
        val filler = admin.filler
        (45 until 54).forEach { set(it, filler) }
        renderPage()
        set(45, admin.item(Material.ARROW, "<#f3d1d9>Back to players")) { admin.openPlayers(viewer) }
        tab(46, null, Material.NETHER_STAR)
        tab(47, CosmeticType.TRAIL, Material.PINK_PETALS)
        tab(48, CosmeticType.AURA, Material.SPORE_BLOSSOM)
        tab(49, CosmeticType.WINGS, Material.FEATHER)
        tab(50, CosmeticType.PROJECTILE, Material.ARROW)
        tab(51, CosmeticType.EMOTE, Material.FIREWORK_STAR)
        if (hasPrevious) set(52, admin.item(Material.SPECTRAL_ARROW, "<#f3d1d9>Previous page")) { goTo(page - 1) }
        if (hasNext) set(53, admin.item(Material.SPECTRAL_ARROW, "<#f3d1d9>Next page")) { goTo(page + 1) }
    }

    private fun tab(slot: Int, type: CosmeticType?, material: Material) {
        val selected = type == filter
        val name = type?.let(admin::typeName) ?: "All cosmetics"
        set(slot, admin.item(material, if (selected) "<#ffd1dc>› $name" else "<#9c8a8f>$name", glow = selected)) {
            if (!selected) admin.openPlayer(viewer, target, type)
        }
    }
}
