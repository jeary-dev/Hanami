package dev.jeary.hanami.admin.menu

import dev.jeary.hanami.admin.AdminService
import dev.jeary.hanami.admin.creator.CosmeticDraft
import dev.jeary.hanami.admin.creator.CosmeticDraft.Companion.r
import dev.jeary.hanami.admin.creator.EmitStyle
import dev.jeary.hanami.admin.creator.EmoteTriggers
import dev.jeary.hanami.admin.creator.ParticleStyle
import dev.jeary.hanami.admin.creator.ShapeParam
import dev.jeary.hanami.admin.creator.ShapePreset
import dev.jeary.hanami.admin.creator.UnlockStyle
import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.config.CosmeticParser
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.core.Messenger.Companion.tag
import dev.jeary.hanami.core.render.parseHexColor
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.TrailMode
import dev.jeary.hanami.menu.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.util.Locale

/**
 * The cosmetic creator: every knob of a [CosmeticDraft] as a button. Rows are grouped —
 * identity, shape, particle, motion, type-specific behaviour — with save/preview at the bottom.
 *
 * Numbers: left/right-click to step up/down, shift for bigger steps. Choices: left/right-click to
 * cycle. Text (id, name, colours) is typed in chat. The status button re-validates the draft with
 * the real config parser on every change, so errors show up before saving.
 */
class CreatorMenu(viewer: Player, private val admin: AdminService, private val draft: CosmeticDraft) : Menu(viewer) {

    private val core get() = admin.core
    private val messenger get() = core.messenger

    override val size = 54

    override fun title() = admin.title("<#b8485f>花</#b8485f> <#3d3437>Creator <#8a7a7f>· ${draft.id}")

    override fun render() {
        val filler = admin.filler
        (0 until size).forEach { set(it, filler) }
        identityRow()
        shapeRow()
        particleRow()
        motionRow()
        behaviourRow()
        bottomRow()
    }

    // ── row 0: identity ──────────────────────────────────────────────────

    private fun identityRow() {
        choice(0, Material.COMPARATOR, "Type", admin.typeName(draft.type), CosmeticType.entries.map(admin::typeName), "Changing type resets the shape and motion") { dir ->
            draft.type = cycle(CosmeticType.entries, draft.type, dir)
        }
        set(1, admin.item(Material.NAME_TAG, "<#f3d1d9>Id", listOf(value(draft.id), "", hint("Click to type a new id")))) {
            askId()
        }
        set(
            2,
            admin.item(
                Material.OAK_SIGN, "<#f3d1d9>Display name",
                listOf("<reset>${draft.displayName}", if (draft.name == null) "<#6b5b60>(automatic, from the id and colours)" else "", "", hint("Left-click to type it (MiniMessage works)"), hint("Right-click to go back to automatic")),
            ),
        ) { click ->
            if (click.isRightClick) { draft.name = null; refresh() } else askName()
        }
        set(
            3,
            admin.item(
                Material.PAPER, "<#f3d1d9>Description",
                listOf(draft.lore?.let { "<reset>$it" } ?: "<#6b5b60>(none)", "", hint("Left-click to type one line"), hint("Right-click to clear")),
            ),
        ) { click ->
            if (click.isRightClick) { draft.lore = null; refresh() } else askLore()
        }
        set(
            4,
            admin.item(
                draft.iconMaterial, "<#f3d1d9>Menu icon",
                listOf(value(draft.iconMaterial.name.lowercase()), "", hint("Left-click to use the item in your hand"), hint("Right-click to use the shape's icon")),
            ),
        ) { click ->
            if (click.isRightClick) draft.icon = null
            else viewer.inventory.itemInMainHand.type.takeIf { !it.isAir && it.isItem }?.let { draft.icon = it }
            refresh()
        }
        val unlockLore = buildList {
            add(value(draft.unlock.label))
            when (draft.unlock) {
                UnlockStyle.FREE -> add("<#6b5b60>Everyone can wear it")
                UnlockStyle.PERMISSION -> add("<#6b5b60>Needs hanami.cosmetic.${draft.id}")
                UnlockStyle.PRICE -> {
                    add("<#9c8a8f>Price: <#ffd27f>${fmt(draft.price)}")
                    if (core.economy == null) add("<#d98c8c>No Vault economy installed!")
                }
            }
            add("")
            add(hint("Left/right-click to change"))
            if (draft.unlock == UnlockStyle.PRICE) add(hint("Shift-click to type the price"))
        }
        set(5, admin.item(Material.GOLD_INGOT, "<#f3d1d9>How players unlock it", unlockLore)) { click ->
            if (click.isShiftClick && draft.unlock == UnlockStyle.PRICE) return@set askPrice()
            draft.unlock = cycle(UnlockStyle.entries, draft.unlock, direction(click))
            refresh()
        }
        set(
            8,
            admin.item(
                Material.KNOWLEDGE_BOOK, "<#f3d1d9>How this works",
                listOf(
                    "<#9c8a8f>Numbers: <#f3d1d9>left<#9c8a8f>/<#f3d1d9>right-click<#9c8a8f> = up/down,",
                    "<#9c8a8f>hold <#f3d1d9>shift<#9c8a8f> for bigger steps.",
                    "<#9c8a8f>Choices: left/right-click to cycle.",
                    "",
                    "<#9c8a8f>Preview to try it, then save. It's",
                    "<#9c8a8f>added to the end of cosmetics.yml,",
                    "<#9c8a8f>where you can fine-tune it later.",
                    "",
                    "<#6b5b60>Your draft is kept if you close this.",
                ),
            ),
        )
    }

    // ── row 1: shape ─────────────────────────────────────────────────────

    private fun shapeRow() {
        val presets = ShapePreset.forType(draft.type)
        set(9, admin.item(draft.shape.icon, "<#f3d1d9>Shape", listOf(value(draft.shape.label), "<#6b5b60>${draft.shape.description}", "") + options(presets.map { it.label }, draft.shape.label) + hint("Left/right-click to change"))) { click ->
            draft.shape = cycle(presets, draft.shape, direction(click))
            refresh()
        }
        val params = draft.shape.params
        if (ShapeParam.SIZE in params) number(10, Material.SLIME_BALL, "Size", "${fmt(draft.size)} blocks", "radius / letter height") { d, b ->
            draft.size = step(draft.size, d, b, 0.1, 0.1..8.0)
        }
        if (ShapeParam.POINTS in params) number(11, Material.GLOWSTONE_DUST, "Points", "${draft.points}", "more points = smoother, but costlier") { d, b ->
            draft.points = step(draft.points.toDouble(), d, b, 2.0, 4.0..240.0, 10.0).toInt()
        }
        if (ShapeParam.HEIGHT in params) number(12, Material.SCAFFOLDING, "Height", "${fmt(draft.height)} blocks") { d, b ->
            draft.height = step(draft.height, d, b, 0.25, 0.0..6.0)
        }
        if (ShapeParam.TURNS in params) number(13, Material.TWISTING_VINES, "Turns", fmt(draft.turns)) { d, b ->
            draft.turns = step(draft.turns, d, b, 0.5, 0.5..10.0)
        }
        if (ShapeParam.STRANDS in params) number(14, Material.STRING, "Strands", "${draft.strands}") { d, b ->
            draft.strands = step(draft.strands.toDouble(), d, b, 1.0, 1.0..8.0, 1.0).toInt()
        }
        if (ShapeParam.TEXT in params) {
            set(
                15,
                admin.item(
                    Material.WRITABLE_BOOK, "<#f3d1d9>Text",
                    listOf(value(draft.text), "<#6b5b60>Kanji need a CJK font on the server", "", hint("Click to type the text")),
                ),
            ) { askText() }
        }
        label(17, Material.PINK_STAINED_GLASS_PANE, "Shape", "Where the particles are")
    }

    // ── row 2: particle ──────────────────────────────────────────────────

    private fun particleRow() {
        choice(18, draft.particleStyle.icon, "Particle style", draft.particleStyle.label, ParticleStyle.entries.map { it.label }) { dir ->
            draft.particleStyle = cycle(ParticleStyle.entries, draft.particleStyle, dir)
        }
        when (draft.particleStyle) {
            ParticleStyle.DUST -> {
                colour(19, "Colour", draft.color1) { draft.color1 = it }
                number(21, Material.SUGAR, "Particle size", fmt(draft.particleSize)) { d, b -> draft.particleSize = step(draft.particleSize, d, b, 0.1, 0.2..3.0) }
            }
            ParticleStyle.GRADIENT -> {
                colour(19, "From colour", draft.color1) { draft.color1 = it }
                colour(20, "To colour", draft.color2) { draft.color2 = it }
                number(21, Material.SUGAR, "Particle size", fmt(draft.particleSize)) { d, b -> draft.particleSize = step(draft.particleSize, d, b, 0.1, 0.2..3.0) }
            }
            ParticleStyle.PARTICLE -> {
                val available = CosmeticDraft.PARTICLES.filter { CosmeticParser.particleByName(it) != null }
                val lore = buildList {
                    add(value(draft.particle.lowercase()))
                    if (!draft.particleExists()) add("<#d98c8c>Not available on this server version")
                    add("")
                    add(hint("Left/right-click to cycle"))
                    add(hint("Shift-click to type any particle name"))
                }
                set(19, admin.item(Material.BLAZE_POWDER, "<#f3d1d9>Particle", lore)) { click ->
                    if (click.isShiftClick) return@set askParticle()
                    val current = available.indexOf(draft.particle.uppercase())
                    val next = if (current < 0) 0 else Math.floorMod(current + direction(click), available.size)
                    draft.particle = available[next]
                    refresh()
                }
            }
        }
        label(26, Material.MAGENTA_STAINED_GLASS_PANE, "Particle", "What each point looks like")
    }

    // ── row 3: motion ────────────────────────────────────────────────────

    private fun motionRow() {
        number(27, Material.COMPASS, "Spin", "${fmt(draft.spin)}°/s", "negative = the other way") { d, b ->
            draft.spin = step(draft.spin, d, b, 10.0, -360.0..360.0, 45.0)
        }
        number(28, Material.FEATHER, "Bob", "${fmt(draft.bob)} blocks", "gentle up-and-down float") { d, b ->
            draft.bob = step(draft.bob, d, b, 0.05, 0.0..1.0, 0.25)
        }
        number(29, Material.LADDER, "Height offset", "${fmt(draft.offsetY)} blocks", "raise or lower the whole shape") { d, b ->
            draft.offsetY = step(draft.offsetY, d, b, 0.1, -1.0..3.0, 0.5)
        }
        set(30, admin.item(Material.FIREWORK_ROCKET, "<#f3d1d9>Draw style", listOf(value(draft.emit.label), "<#6b5b60>${draft.emit.description}", "") + options(EmitStyle.entries.map { it.label }, draft.emit.label) + hint("Left/right-click to change"))) { click ->
            draft.emit = cycle(EmitStyle.entries, draft.emit, direction(click))
            refresh()
        }
        number(31, Material.REPEATER, "Draw every", "${draft.interval} tick(s)", "higher = lighter on the server") { d, b ->
            draft.interval = step(draft.interval.toDouble(), d, b, 1.0, 1.0..20.0, 5.0).toInt()
        }
        if (draft.type == CosmeticType.WINGS) {
            number(32, Material.PHANTOM_MEMBRANE, "Flap angle", "${fmt(draft.flapAngle)}°") { d, b ->
                draft.flapAngle = step(draft.flapAngle, d, b, 2.0, 0.0..60.0, 10.0)
            }
            number(33, Material.CLOCK, "Flap time", "${draft.flapPeriod} ticks", "time for one full beat") { d, b ->
                draft.flapPeriod = step(draft.flapPeriod.toDouble(), d, b, 4.0, 8.0..120.0, 20.0).toInt()
            }
        }
        label(35, Material.PURPLE_STAINED_GLASS_PANE, "Motion", "How it moves and how often it draws")
    }

    // ── row 4: type behaviour ────────────────────────────────────────────

    private fun behaviourRow() {
        when (draft.type) {
            CosmeticType.TRAIL -> {
                val modes = TrailMode.entries.map { it.id }
                choice(36, Material.LEATHER_BOOTS, "Trail position", draft.trailMode, modes, "water-surface only shows while swimming") { dir ->
                    draft.trailMode = cycle(modes, draft.trailMode, dir)
                }
            }
            CosmeticType.AURA -> set(
                36,
                admin.item(Material.LEVER, "<#f3d1d9>Only while standing still", listOf(value(if (draft.idleOnly) "Yes" else "No"), "", hint("Click to toggle"))),
            ) { draft.idleOnly = !draft.idleOnly; refresh() }
            CosmeticType.WINGS -> number(36, Material.ELYTRA, "Wing size", "×${fmt(draft.wingScale)}") { d, b ->
                draft.wingScale = step(draft.wingScale, d, b, 0.1, 0.3..3.0)
            }
            CosmeticType.PROJECTILE -> set(
                36,
                admin.item(Material.BOW, "<#f3d1d9>Projectile trail", listOf("<#9c8a8f>Follows arrows and tridents", "<#9c8a8f>the wearer shoots.")),
            )
            CosmeticType.EMOTE -> {
                number(36, Material.CLOCK, "Duration", "${draft.emoteDuration} ticks (${fmt(draft.emoteDuration / 20.0)}s)") { d, b ->
                    draft.emoteDuration = step(draft.emoteDuration.toDouble(), d, b, 10.0, 20.0..300.0, 50.0).toInt()
                }
                number(37, Material.HOPPER, "Cooldown", "${draft.emoteCooldown}s") { d, b ->
                    draft.emoteCooldown = step(draft.emoteCooldown.toDouble(), d, b, 5.0, 0.0..600.0, 30.0).toInt()
                }
                choice(38, Material.IRON_SWORD, "Plays on", draft.emoteTriggers.label, EmoteTriggers.entries.map { it.label }) { dir ->
                    draft.emoteTriggers = cycle(EmoteTriggers.entries, draft.emoteTriggers, dir)
                }
            }
        }
        label(44, Material.BLUE_STAINED_GLASS_PANE, "Behaviour", "Settings for ${admin.typeName(draft.type)}")
    }

    // ── row 5: actions ───────────────────────────────────────────────────

    private fun bottomRow() {
        set(45, admin.item(Material.ARROW, "<#f3d1d9>Back", listOf("<#6b5b60>Your draft is kept"))) { admin.openAdmin(viewer) }

        val result = admin.validate(draft)
        set(
            47,
            admin.item(Material.ENDER_EYE, "<#f3d1d9>Preview", listOf("<#9c8a8f>Shows it on you (only you see it)", "<#9c8a8f>— press F5 to look. The creator", "<#9c8a8f>reopens when the preview ends.")),
        ) { admin.preview(viewer, draft) }
        set(49, status(result))
        set(
            51,
            admin.item(
                Material.LIME_DYE, "<#a8e6a1>Save cosmetic",
                listOf("<#9c8a8f>Adds <#f3d1d9>${draft.id}<#9c8a8f> to cosmetics.yml", "<#9c8a8f>and reloads, so it's live at once."),
                glow = result is ValidationResult.Valid,
            ),
        ) { admin.save(viewer, draft) }
        set(53, admin.item(Material.BARRIER, "<#d98c8c>Discard draft", listOf("<#9c8a8f>Shift-click to throw it away"))) { click ->
            if (!click.isShiftClick) return@set
            admin.discard(viewer)
            admin.openAdmin(viewer)
        }
    }

    private fun status(result: ValidationResult<CosmeticDefinition>) = when (result) {
        is ValidationResult.Valid -> admin.item(
            Material.EMERALD, "<#a8e6a1>✔ Ready to save",
            listOf("<#9c8a8f>${result.value.layers.size} layer · ${draft.shape.label.lowercase()} · ${draft.particleStyle.label.lowercase()}") +
                result.warnings.take(3).map { "<#ffd27f>⚠ ${it.message}" },
        )
        is ValidationResult.Invalid -> admin.item(
            Material.REDSTONE_BLOCK, "<#d98c8c>✘ ${result.errors.count { it.isError }} problem(s)",
            result.errors.filter { it.isError }.take(5).map { "<#d98c8c>${it.path.substringAfter('.')}: <#9c8a8f>${it.message}" },
        )
    }

    // ── chat prompts ─────────────────────────────────────────────────────

    private fun reopen() = admin.openCreator(viewer)

    private fun askId() = admin.prompts.ask(viewer, "admin.ask-id", onCancel = ::reopen) { answer ->
        val id = answer.lowercase(Locale.ROOT)
        when {
            CosmeticId.parse(id) == null -> messenger.send(viewer, "admin.invalid-id", tag("input", answer))
            admin.isTaken(id) -> messenger.send(viewer, "admin.id-taken", tag("input", id))
            else -> draft.id = id
        }
        reopen()
    }

    private fun askName() = admin.prompts.ask(viewer, "admin.ask-name", onCancel = ::reopen) { answer ->
        draft.name = answer
        reopen()
    }

    private fun askLore() = admin.prompts.ask(viewer, "admin.ask-lore", onCancel = ::reopen) { answer ->
        draft.lore = if (answer.startsWith("<")) answer else "<#9c8a8f>$answer"
        reopen()
    }

    private fun askText() = admin.prompts.ask(viewer, "admin.ask-text", onCancel = ::reopen) { answer ->
        draft.text = answer.take(MAX_TEXT)
        reopen()
    }

    private fun askPrice() = admin.prompts.ask(viewer, "admin.ask-price", onCancel = ::reopen) { answer ->
        val price = answer.replace(",", "").toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
        if (price == null) messenger.send(viewer, "admin.invalid-number", tag("input", answer)) else draft.price = price
        reopen()
    }

    private fun askParticle() = admin.prompts.ask(viewer, "admin.ask-particle", onCancel = ::reopen) { answer ->
        val name = answer.trim().uppercase(Locale.ROOT).removePrefix("MINECRAFT:")
        val particle = CosmeticParser.particleByName(name)
        when {
            particle == null -> messenger.send(viewer, "admin.invalid-particle", tag("input", answer))
            particle.dataType != Void::class.java -> messenger.send(viewer, "admin.particle-needs-data", tag("input", particle.name))
            else -> draft.particle = particle.name
        }
        reopen()
    }

    private fun askColour(apply: (String) -> Unit) = admin.prompts.ask(viewer, "admin.ask-color", onCancel = ::reopen) { answer ->
        val hex = (if (answer.startsWith("#")) answer else "#$answer").lowercase(Locale.ROOT)
        if (parseHexColor(hex) == null) messenger.send(viewer, "admin.invalid-color", tag("input", answer)) else apply(hex)
        reopen()
    }

    // ── widgets ──────────────────────────────────────────────────────────

    /** A number: left/right-click steps it up/down, shift-click takes bigger steps. */
    private fun number(
        slot: Int,
        material: Material,
        label: String,
        shown: String,
        note: String? = null,
        onStep: (dir: Int, big: Boolean) -> Unit,
    ) {
        val lore = buildList {
            add(value(shown))
            note?.let { add("<#6b5b60>$it") }
            add("")
            add(hint("Left / right-click: up / down"))
            add(hint("Shift-click: bigger steps"))
        }
        set(slot, admin.item(material, "<#f3d1d9>$label", lore)) { click ->
            onStep(direction(click), click.isShiftClick)
            refresh()
        }
    }

    private fun choice(
        slot: Int,
        material: Material,
        label: String,
        current: String,
        all: List<String>,
        note: String? = null,
        onCycle: (Int) -> Unit,
    ) {
        val lore = buildList {
            add(value(current))
            note?.let { add("<#6b5b60>$it") }
            add("")
            addAll(options(all, current))
            add(hint("Left/right-click to change"))
        }
        set(slot, admin.item(material, "<#f3d1d9>$label", lore)) { click ->
            onCycle(direction(click))
            refresh()
        }
    }

    private fun colour(slot: Int, label: String, current: String, apply: (String) -> Unit) {
        val lore = listOf(
            value(current), "<$current>■■■■■■", "",
            hint("Left/right-click: preset colours"), hint("Shift-click: type a hex colour"),
        )
        set(slot, admin.item(Material.LEATHER_HORSE_ARMOR, "<#f3d1d9>$label", lore, tint = parseHexColor(current))) { click ->
            if (click.isShiftClick) return@set askColour(apply)
            apply(cycle(CosmeticDraft.COLORS, current.lowercase(Locale.ROOT), direction(click)))
            refresh()
        }
    }

    private fun label(slot: Int, material: Material, title: String, subtitle: String) =
        set(slot, admin.item(material, "<#ffd1dc>$title", listOf("<#6b5b60>$subtitle")))

    private fun options(all: List<String>, current: String): List<String> =
        all.map { if (it == current) "<#ffd1dc>› $it" else "<#6b5b60>  $it" } + ""

    private fun value(text: String) = "<#9c8a8f>Now: <#ffd1dc>${text.replace("<", "\\<")}"

    private fun hint(text: String) = "<#6b5b60>$text"

    private companion object {
        const val MAX_TEXT = 12

        /** +1 for left-clicks, −1 for right-clicks. */
        fun direction(click: ClickType) = if (click.isRightClick) -1 else 1

        /** The entry [dir] steps away from [current] (wrapping); unknown values restart at the first. */
        fun <T> cycle(all: List<T>, current: T, dir: Int): T {
            val index = all.indexOf(current)
            return if (index < 0) all.first() else all[Math.floorMod(index + dir, all.size)]
        }

        /** [value] moved one step (or one [bigUnit] when [big]) in [dir], clamped and rounded. */
        fun step(
            value: Double,
            dir: Int,
            big: Boolean,
            unit: Double,
            range: ClosedFloatingPointRange<Double>,
            bigUnit: Double = unit * 5,
        ): Double = r((value + dir * (if (big) bigUnit else unit)).coerceIn(range))

        fun fmt(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
