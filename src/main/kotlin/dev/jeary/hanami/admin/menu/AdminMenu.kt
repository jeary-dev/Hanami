package dev.jeary.hanami.admin.menu

import dev.jeary.hanami.admin.AdminService
import dev.jeary.hanami.command.Permissions
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.menu.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/** `/hanami admin`: the hub for player management, the cosmetic creator and reloads. */
class AdminMenu(viewer: Player, private val admin: AdminService) : Menu(viewer) {

    override val size = 27

    override fun title() = admin.title("<#b8485f>花</#b8485f> <#3d3437>Admin")

    override fun render() {
        val filler = admin.filler
        (0 until size).forEach { set(it, filler) }
        val core = admin.core

        set(
            11,
            admin.item(
                Material.PLAYER_HEAD, "<#f3d1d9>Manage players",
                listOf(
                    "<#9c8a8f>Give, revoke, equip and remove",
                    "<#9c8a8f>cosmetics for online players.",
                    "",
                    "<#6b5b60>${Bukkit.getOnlinePlayers().size} online",
                ),
            ),
        ) { admin.openPlayers(viewer) }

        val draft = if (admin.hasDraft(viewer)) admin.draftFor(viewer) else null
        val creatorLore = buildList {
            add("<#9c8a8f>Design a new cosmetic with")
            add("<#9c8a8f>buttons instead of YAML, preview")
            add("<#9c8a8f>it, and save it straight to")
            add("<#9c8a8f>cosmetics.yml.")
            add("")
            if (draft != null) add("<#ffd1dc>Draft in progress: <#f3d1d9>${draft.id}") else add("<#6b5b60>Click to start")
            if (!viewer.hasPermission(Permissions.CREATE)) add("<#d98c8c>Needs ${Permissions.CREATE}")
        }
        set(13, admin.item(Material.BRUSH, "<#f3d1d9>Cosmetic creator", creatorLore, glow = draft != null)) {
            if (viewer.hasPermission(Permissions.CREATE)) admin.openCreator(viewer)
            else core.messenger.send(viewer, "command.no-permission")
        }

        set(
            15,
            admin.item(
                Material.CLOCK, "<#f3d1d9>Reload config",
                listOf(
                    "<#9c8a8f>Re-reads all four config files.",
                    "<#9c8a8f>Nothing changes if any are broken.",
                    "",
                    "<#6b5b60>${core.registry.size} cosmetics loaded · storage: ${core.profiles.backendName}",
                ),
            ),
        ) {
            if (!viewer.hasPermission(Permissions.RELOAD)) return@set core.messenger.send(viewer, "command.no-permission")
            viewer.closeInventory()
            core.plugin.server.scheduler.runTask(core.plugin, Runnable { reload() })
        }

        set(22, admin.item(Material.BARRIER, "<#d98c8c>Close")) { viewer.closeInventory() }
    }

    private fun reload() {
        val core = admin.core
        when (val result = core.reload()) {
            is ValidationResult.Valid -> core.messenger.send(
                viewer, "reload.success", tag("count", result.value.cosmetics.size), tag("ms", "–"),
            )
            is ValidationResult.Invalid -> {
                core.messenger.send(viewer, "reload.failed", tag("count", result.errors.size))
                result.errors.take(8).forEach { core.messenger.send(viewer, "reload.problem", tag("problem", it)) }
            }
        }
    }
}
