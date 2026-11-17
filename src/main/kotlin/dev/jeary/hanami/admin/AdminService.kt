package dev.jeary.hanami.admin

import dev.jeary.hanami.admin.creator.CosmeticDraft
import dev.jeary.hanami.admin.creator.CosmeticFileWriter
import dev.jeary.hanami.admin.creator.DraftCompiler
import dev.jeary.hanami.admin.menu.AdminMenu
import dev.jeary.hanami.admin.menu.CreatorMenu
import dev.jeary.hanami.admin.menu.PlayerCosmeticsMenu
import dev.jeary.hanami.admin.menu.PlayerListMenu
import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.Messenger.Companion.parsedTag
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.math.shape.ShapeCache
import dev.jeary.hanami.menu.MenuService
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

/**
 * The in-game admin tools behind `/hanami admin`: managing other players' cosmetics, and the
 * cosmetic creator. Holds each admin's unsaved draft, so closing the creator (to answer a chat
 * prompt, watch a preview, or just walk away) never loses work until the server restarts.
 */
class AdminService(val core: HanamiCore, val menus: MenuService) {

    val prompts = ChatPrompts(core.plugin, core.messenger)

    /** Validation uses its own cache so half-finished drafts never fill the live shape cache. */
    private val validationShapes = ShapeCache()
    private val compiler = DraftCompiler(validationShapes)
    private val previewCompiler = DraftCompiler(core.shapes)
    private val writer = CosmeticFileWriter(core.plugin.dataFolder)
    private val drafts = HashMap<UUID, CosmeticDraft>()

    fun enable() {
        core.plugin.server.pluginManager.registerEvents(prompts, core.plugin)
    }

    // ── navigation ───────────────────────────────────────────────────────

    fun openAdmin(viewer: Player) = AdminMenu(viewer, this).open()

    fun openPlayers(viewer: Player) = PlayerListMenu(viewer, this).open()

    fun openPlayer(viewer: Player, target: Player, filter: CosmeticType? = null) {
        if (!target.isOnline) {
            core.messenger.send(viewer, "admin.player-offline", tag("player", target.name))
            return openPlayers(viewer)
        }
        PlayerCosmeticsMenu(viewer, this, target, filter).open()
    }

    fun openCreator(viewer: Player) = CreatorMenu(viewer, this, draftFor(viewer)).open()

    // ── drafts ───────────────────────────────────────────────────────────

    fun hasDraft(viewer: Player) = viewer.uniqueId in drafts

    fun draftFor(viewer: Player): CosmeticDraft =
        drafts.getOrPut(viewer.uniqueId) { CosmeticDraft(CosmeticType.AURA, freshId()) }

    fun discard(viewer: Player) {
        drafts.remove(viewer.uniqueId)
        core.messenger.send(viewer, "admin.discarded")
    }

    /** `custom_1`, `custom_2`, … — the first id not used by a cosmetic or another draft. */
    fun freshId(): String = generateSequence(1) { it + 1 }
        .map { "custom_$it" }
        .first { !isTaken(it) && drafts.values.none { d -> d.id == it } }

    fun isTaken(id: String): Boolean = CosmeticId.parse(id)?.let { core.registry[it] != null } ?: false

    fun validate(draft: CosmeticDraft): ValidationResult<CosmeticDefinition> {
        validationShapes.clear() // only the current draft's shapes are ever worth keeping
        return compiler.compile(draft)
    }

    fun preview(viewer: Player, draft: CosmeticDraft) {
        val definition = when (val result = previewCompiler.compile(draft)) {
            is ValidationResult.Valid -> result.value
            is ValidationResult.Invalid -> return reportProblems(viewer, result)
        }
        viewer.closeInventory()
        val seconds = if (definition.behavior is Behavior.Emote) EMOTE_PREVIEW_SECONDS else PREVIEW_SECONDS
        core.cosmetics.preview(viewer, definition, seconds)
        core.messenger.send(viewer, "admin.preview", parsedTag("cosmetic", draft.displayName), tag("seconds", seconds))
        // Bring the creator back once the preview is over, unless they've moved on to something else.
        core.plugin.server.scheduler.runTaskLater(core.plugin, Runnable {
            if (viewer.isOnline && !prompts.isWaiting(viewer) && viewer.openInventory.topInventory.type == InventoryType.CRAFTING) {
                openCreator(viewer)
            }
        }, seconds * 20L + 10L)
    }

    /**
     * Validates, appends to `cosmetics.yml`, then reloads so the cosmetic is live immediately.
     * Runs a tick later: reloading closes every open menu, which must not happen mid-click.
     */
    fun save(viewer: Player, draft: CosmeticDraft) {
        viewer.closeInventory()
        core.plugin.server.scheduler.runTask(core.plugin, Runnable { saveNow(viewer, draft) })
    }

    private fun saveNow(viewer: Player, draft: CosmeticDraft) {
        val messenger = core.messenger
        val name = parsedTag("cosmetic", draft.displayName)
        if (isTaken(draft.id)) {
            messenger.send(viewer, "admin.id-taken", tag("input", draft.id))
            return openCreator(viewer)
        }
        val invalid = validate(draft) as? ValidationResult.Invalid
        if (invalid != null) {
            reportProblems(viewer, invalid)
            return openCreator(viewer)
        }
        when (val written = writer.append(draft, viewer.name)) {
            is CosmeticFileWriter.Result.Failed -> {
                messenger.send(viewer, "admin.save-failed", tag("reason", written.reason))
                return openCreator(viewer)
            }
            CosmeticFileWriter.Result.Saved -> Unit
        }
        drafts.remove(viewer.uniqueId)
        when (val reload = core.reload()) {
            is ValidationResult.Valid -> {
                messenger.send(viewer, "admin.saved", name, tag("id", draft.id))
                openAdmin(viewer)
            }
            is ValidationResult.Invalid -> {
                messenger.send(viewer, "admin.saved-reload-failed", name)
                reload.errors.take(MAX_PROBLEMS).forEach { messenger.send(viewer, "reload.problem", tag("problem", it)) }
                core.logProblems(reload.errors)
            }
        }
    }

    fun reportProblems(viewer: Player, result: ValidationResult.Invalid) {
        val errors = result.errors.filter { it.isError }
        core.messenger.send(viewer, "admin.draft-invalid", tag("count", errors.size))
        errors.take(MAX_PROBLEMS).forEach {
            core.messenger.send(viewer, "reload.problem", tag("problem", "${it.path.substringAfter('.')}: ${it.message}"))
        }
    }

    // ── items ────────────────────────────────────────────────────────────

    /** A menu icon from MiniMessage text. [tint] colours leather items; [head] sets a skull's owner. */
    fun item(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
        glow: Boolean = false,
        tint: Color? = null,
        head: OfflinePlayer? = null,
    ): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta ?: return stack
        val messenger = core.messenger
        meta.setDisplayName(messenger.legacyItemText(name))
        if (lore.isNotEmpty()) meta.lore = lore.map { messenger.legacyItemText(it) }
        if (glow) meta.setEnchantmentGlintOverride(true)
        if (tint != null && meta is LeatherArmorMeta) meta.setColor(tint)
        if (head != null && meta is SkullMeta) meta.owningPlayer = head
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_DYE)
        stack.itemMeta = meta
        return stack
    }

    val filler: ItemStack get() = item(Material.BLACK_STAINED_GLASS_PANE, " ")

    fun title(raw: String): String = core.messenger.legacy(core.messenger.parse(raw))

    fun typeName(type: CosmeticType): String = core.config.menus.categoryNames[type] ?: type.id

    companion object {
        const val PREVIEW_SECONDS = 8
        const val EMOTE_PREVIEW_SECONDS = 5
        private const val MAX_PROBLEMS = 6
    }
}
