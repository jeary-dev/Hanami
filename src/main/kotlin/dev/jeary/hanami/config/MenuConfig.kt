package dev.jeary.hanami.config

import dev.jeary.hanami.api.cosmetic.CosmeticType
import org.bukkit.Material

/** What a menu button does when clicked. Parsed from strings like `open-category:trail`. */
sealed interface MenuAction {
    data class OpenCategory(val type: CosmeticType) : MenuAction
    data object OpenSettings : MenuAction
    data object OpenMain : MenuAction
    data object Close : MenuAction
    data object PreviousPage : MenuAction
    data object NextPage : MenuAction
    data object UnequipCategory : MenuAction
    data object ToggleShowOthers : MenuAction
    data object CycleDensity : MenuAction
    data object Confirm : MenuAction
    data object Cancel : MenuAction
    data object None : MenuAction

    companion object {
        val NAMES = listOf(
            "open-category:<type>", "open-settings", "open-main", "close", "previous-page", "next-page",
            "unequip-category", "toggle-show-others", "cycle-density", "confirm", "cancel", "none",
        )

        fun parse(raw: String): MenuAction? {
            val value = raw.trim().lowercase()
            if (value.startsWith("open-category:")) {
                return CosmeticType.byId(value.substringAfter(':'))?.let(::OpenCategory)
            }
            return when (value) {
                "open-settings" -> OpenSettings
                "open-main", "back" -> OpenMain
                "close" -> Close
                "previous-page" -> PreviousPage
                "next-page" -> NextPage
                "unequip-category" -> UnequipCategory
                "toggle-show-others" -> ToggleShowOthers
                "cycle-density" -> CycleDensity
                "confirm" -> Confirm
                "cancel" -> Cancel
                "none" -> None
                else -> null
            }
        }
    }
}

/** A configurable item: name and lore are MiniMessage. */
data class MenuItem(
    val material: Material,
    val name: String,
    val lore: List<String>,
    val customModelData: Int?,
    val glow: Boolean,
    val action: MenuAction,
)

/**
 * A menu drawn as a character grid, one string per row of 9:
 * ```
 * "#########"
 * "#ccccccc#"
 * "#p##x##n#"
 * ```
 * Each character maps to an item in [items]; [contentChar] marks the slots paginated content
 * fills; [FILLER_CHAR] uses the shared filler; a space leaves the slot empty.
 */
data class MenuLayout(
    val title: String,
    val rows: List<String>,
    val items: Map<Char, MenuItem>,
    val contentChar: Char?,
) {
    val size: Int get() = rows.size * 9

    /** Slot indices of every occurrence of [char], in reading order. */
    fun slotsOf(char: Char): List<Int> = rows.flatMapIndexed { row, line ->
        line.mapIndexedNotNull { col, c -> if (c == char) row * 9 + col else null }
    }

    val contentSlots: List<Int> get() = contentChar?.let(::slotsOf) ?: emptyList()

    companion object {
        const val FILLER_CHAR = '#'
        const val EMPTY_CHAR = ' '
    }
}

/** Lore appended to each cosmetic in a category menu, per state. */
data class CosmeticLore(
    val equipped: List<String>,
    val unlocked: List<String>,
    val locked: List<String>,
    val purchasable: List<String>,
)

data class MenuConfig(
    val filler: MenuItem,
    val main: MenuLayout,
    val category: MenuLayout,
    val settings: MenuLayout,
    val confirm: MenuLayout,
    val cosmeticLore: CosmeticLore,
    val categoryNames: Map<CosmeticType, String>,
    val clickSound: String?,
) {
    companion object {
        fun parse(root: ConfigNode): MenuConfig {
            val filler = root.node("filler", required = true)?.let { parseItem(it, requireAction = false) }
                ?: MenuItem(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList(), null, false, MenuAction.None)

            val loreNode = root.node("category")?.node("cosmetic-lore")
            val lore = CosmeticLore(
                equipped = loreNode?.stringList("equipped").orEmpty(),
                unlocked = loreNode?.stringList("unlocked").orEmpty(),
                locked = loreNode?.stringList("locked").orEmpty(),
                purchasable = loreNode?.stringList("purchasable").orEmpty(),
            )
            val names = root.node("category-names")
            return MenuConfig(
                filler = filler,
                main = parseLayout(root, "main", contentRequired = false),
                category = parseLayout(root, "category", contentRequired = true),
                settings = parseLayout(root, "settings", contentRequired = false),
                confirm = parseLayout(root, "confirm", contentRequired = false),
                cosmeticLore = lore,
                categoryNames = CosmeticType.entries.associateWith { type ->
                    names?.string(type.id) ?: type.id.replaceFirstChar(Char::uppercase)
                },
                clickSound = root.string("click-sound")?.takeIf { it.isNotBlank() },
            )
        }

        private fun parseLayout(root: ConfigNode, key: String, contentRequired: Boolean): MenuLayout {
            val node = root.node(key, required = true)
                ?: return MenuLayout(key, listOf("         "), emptyMap(), null)
            val rows = node.stringList("layout")
            if (rows.isEmpty() || rows.size > 6) node.error("layout", "expected between 1 and 6 rows, got ${rows.size}")
            rows.forEachIndexed { i, row ->
                if (row.length != 9) node.error("layout[$i]", "each row must be exactly 9 characters, \"$row\" has ${row.length}")
            }
            val contentChar = node.string("content-char")?.singleOrNull()
            if (contentRequired && contentChar == null) node.error("content-char", "expected a single character marking content slots")

            val itemsNode = node.node("items")
            val items = itemsNode?.keys().orEmpty().mapNotNull { k ->
                val char = k.singleOrNull()
                if (char == null) {
                    itemsNode?.error(k, "item keys must be a single character")
                    null
                } else itemsNode?.node(k)?.let { char to parseItem(it, requireAction = true) }
            }.toMap()

            val used = rows.joinToString("").toSet()
            val known = items.keys + MenuLayout.FILLER_CHAR + MenuLayout.EMPTY_CHAR + listOfNotNull(contentChar)
            (used - known).forEach { c -> node.error("layout", "character '$c' is used but has no entry under items") }

            val safeRows = rows.take(6).map { it.padEnd(9).take(9) }.ifEmpty { listOf("         ") }
            return MenuLayout(node.string("title", key), safeRows, items, contentChar)
        }

        private fun parseItem(node: ConfigNode, requireAction: Boolean): MenuItem {
            val material = node.string("material")?.let { raw ->
                Material.matchMaterial(raw)?.takeIf { it.isItem } ?: null.also { node.error("material", "unknown item \"$raw\"") }
            } ?: Material.PAPER.also { if (!node.has("material")) node.error("material", "missing required item") }
            val action = node.string("action")?.let { raw ->
                MenuAction.parse(raw) ?: MenuAction.None.also {
                    node.error("action", "unknown action \"$raw\" (expected one of: ${MenuAction.NAMES.joinToString()})")
                }
            } ?: MenuAction.None
            if (requireAction && !node.has("action") && !node.has("decoration")) {
                node.warn("action", "no action set; the item will do nothing (set `action: none` to silence this)")
            }
            return MenuItem(
                material = material,
                name = node.string("name", " "),
                lore = node.stringList("lore"),
                customModelData = if (node.has("custom-model-data")) node.int("custom-model-data", 0, 0..Int.MAX_VALUE) else null,
                glow = node.bool("glow", false),
                action = action,
            )
        }
    }
}
