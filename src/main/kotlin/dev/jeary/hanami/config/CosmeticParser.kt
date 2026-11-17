package dev.jeary.hanami.config

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.core.render.Palette
import dev.jeary.hanami.core.render.ParticleSpec
import dev.jeary.hanami.cosmetics.Animation
import dev.jeary.hanami.cosmetics.Behavior
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.Emit
import dev.jeary.hanami.cosmetics.EmitMode
import dev.jeary.hanami.cosmetics.EmoteTrigger
import dev.jeary.hanami.cosmetics.IconSpec
import dev.jeary.hanami.cosmetics.Keyframe
import dev.jeary.hanami.cosmetics.Layer
import dev.jeary.hanami.cosmetics.Oscillation
import dev.jeary.hanami.cosmetics.TrailMode
import dev.jeary.hanami.cosmetics.UnlockSpec
import dev.jeary.hanami.math.Easing
import dev.jeary.hanami.math.Vec3
import dev.jeary.hanami.math.shape.GlyphUnavailableException
import dev.jeary.hanami.math.shape.ShapeCache
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.entity.EntityType
import org.bukkit.inventory.ItemStack

/**
 * Parses `cosmetics.yml`. Each cosmetic is parsed independently: a broken entry is reported and
 * skipped while the rest load. Shapes are sampled here (through the [ShapeCache]) so problems
 * such as a missing CJK font surface at load time, not on the first tick.
 */
class CosmeticParser(
    private val shapeCache: ShapeCache,
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
) {

    data class Result(val cosmetics: List<CosmeticDefinition>, val problems: List<ConfigError>)

    fun parseAll(root: ConfigNode): Result {
        val section = root.node("cosmetics")
        if (section == null) {
            root.warn("cosmetics", "no cosmetics defined")
            return Result(emptyList(), emptyList())
        }
        val cosmetics = mutableListOf<CosmeticDefinition>()
        val problems = mutableListOf<ConfigError>()
        for (key in section.keys()) {
            when (val result = parse(section.file, key, section)) {
                is ValidationResult.Valid -> {
                    cosmetics += result.value
                    problems += result.warnings
                }
                is ValidationResult.Invalid -> problems += result.errors
            }
        }
        return Result(cosmetics, problems)
    }

    /** Parses the cosmetic at `parent.<key>` with its own problem list. */
    fun parse(file: String, key: String, parent: ConfigNode): ValidationResult<CosmeticDefinition> {
        val sink = mutableListOf<ConfigError>()
        val id = CosmeticId.parse(key)
        if (id == null) {
            sink += ConfigError(file, parent.pathOf(key), "invalid id \"$key\" — use lowercase letters, digits, _ and -")
            return ValidationResult.Invalid(sink)
        }
        val scoped = parent.isolated(key, sink)
        if (scoped == null) {
            sink += ConfigError(file, parent.pathOf(key), "expected a section describing the cosmetic")
            return ValidationResult.Invalid(sink)
        }
        val node = scoped

        val type = node.requireString("type")?.let { raw ->
            CosmeticType.byId(raw) ?: null.also {
                node.error("type", "unknown type \"$raw\" (expected one of: ${CosmeticType.entries.joinToString { it.id }})")
            }
        }

        val name = node.string("name", id.value)
        val plainName = runCatching { miniMessage.stripTags(name) }.getOrElse {
            node.error("name", "invalid MiniMessage: ${it.message}")
            id.value
        }
        val layers = parseLayers(node, type)
        val behavior = type?.let { parseBehavior(it, node) }
        val icon = parseIcon(node.node("icon"), type, node)
        val unlock = parseUnlock(node.node("unlock"), id)

        return ValidationResult.of(sink) {
            CosmeticDefinition(
                id = id,
                displayName = name,
                plainName = plainName,
                lore = node.stringList("lore"),
                icon = icon,
                order = node.int("category-order", 100),
                unlock = unlock,
                layers = layers,
                behavior = requireNotNull(behavior) { "validated above" },
            )
        }
    }

    // ── layers ───────────────────────────────────────────────────────────

    /**
     * Either a `layers:` list, or a single layer described at the top level. Each list entry may
     * omit keys to inherit them from the top level, so shared settings are written once.
     */
    private fun parseLayers(node: ConfigNode, type: CosmeticType?): List<Layer> {
        val entries = node.nodeList("layers")
        val layers = if (entries.isEmpty()) listOfNotNull(parseLayer(node, null, type))
        else entries.mapNotNull { parseLayer(it, node, type) }
        if (layers.isEmpty() && node.has("layers")) node.error("layers", "no valid layers")
        return layers
    }

    private fun parseLayer(own: ConfigNode, inherited: ConfigNode?, type: CosmeticType?): Layer? {
        fun pick(key: String, required: Boolean = false): ConfigNode? =
            own.node(key) ?: inherited?.node(key) ?: null.also { if (required) own.error(key, "missing required section") }

        val particle = pick("particle", required = true)?.let(::parseParticle)
        val shapeNode = pick("shape", required = true)
        val shape = shapeNode?.let { ShapeParser.parse(it) }
        if (shape != null) {
            try {
                if (shapeCache[shape].isEmpty()) shapeNode.error(null, "the shape produced no points")
            } catch (e: GlyphUnavailableException) {
                shapeNode.error(null, "${e.message} Add a `fallback:` shape to use when the font is missing.")
            } catch (e: RuntimeException) {
                shapeNode.error(null, "could not build shape: ${e.message}")
            }
        }
        val palette = pick("palette")?.let(::parsePalette)
        val animation = parseAnimation(pick("animation"), type)
        val emit = parseEmit(pick("emit"), type)
        val offsetSource = if (own.has("offset") || inherited == null) own else inherited
        val offset = offsetSource.vec3("offset", Vec3.ZERO)

        if (particle == null || shape == null) return null
        return Layer(particle, palette, shape, offset, animation, emit)
    }

    // ── particles ────────────────────────────────────────────────────────

    private val particleKinds = listOf("simple", "dust", "dust_transition", "color", "block", "item")

    fun parseParticle(node: ConfigNode): ParticleSpec? {
        val defaultKind = when {
            node.has("colors") -> "dust_transition"
            node.has("color") && !node.has("particle") -> "dust"
            else -> "simple"
        }
        val kind = node.string("kind", defaultKind).lowercase().replace('-', '_')

        // A particle that does not exist on this server version (e.g. FIREFLY before 1.21.5)
        // can name a fallback instead of failing the whole cosmetic.
        val particleName = node.string("particle")
        if (particleName != null && particleByName(particleName) == null) {
            val fallback = node.node("fallback")
            if (fallback != null) return parseParticle(fallback)
            node.error("particle", "unknown particle \"$particleName\" on this server version (add a `fallback:` particle for older versions)")
            return null
        }

        return when (kind) {
            "simple" -> particle(node, null)?.let { p ->
                if (p.dataType != Void::class.java) {
                    node.error("particle", "${p.name} needs extra data (${p.dataType.simpleName}); use a matching `kind`")
                    null
                } else ParticleSpec.Simple(p)
            }
            "dust" -> {
                val color = node.color("color") ?: run { if (!node.has("color")) node.error("color", "missing required colour"); null }
                val size = node.double("size", 1.0, 0.01..4.0).toFloat()
                color?.let { ParticleSpec.Dust(it, size) }
            }
            "dust_transition" -> {
                val colors = node.colorList("colors")
                if (colors.size != 2) {
                    node.error("colors", "expected exactly two colours: [from, to]")
                    null
                } else ParticleSpec.DustTransition(colors[0], colors[1], node.double("size", 1.0, 0.01..4.0).toFloat())
            }
            "color" -> {
                val p = particle(node, null) ?: return null
                val color = node.color("color") ?: Color.WHITE
                if (!expect(node, p, Color::class.java)) null else ParticleSpec.Colored(p, color)
            }
            "block" -> {
                val p = particle(node, "FALLING_DUST") ?: return null
                val block = material(node, "block", requireBlock = true)?.let { createBlockData(node, it) }
                if (block == null || !expect(node, p, BlockData::class.java)) null else ParticleSpec.Block(p, block)
            }
            "item" -> {
                val p = particle(node, "ITEM") ?: return null
                val item = material(node, "item", requireBlock = false)?.let(::ItemStack)
                if (item == null || !expect(node, p, ItemStack::class.java)) null else ParticleSpec.Item(p, item)
            }
            else -> null.also { node.error("kind", "unknown particle kind \"$kind\" (expected one of: ${particleKinds.joinToString()})") }
        }
    }

    private fun particle(node: ConfigNode, default: String?): Particle? {
        val name = node.string("particle") ?: default
        if (name == null) {
            node.error("particle", "missing required particle name")
            return null
        }
        return particleByName(name) ?: null.also { node.error("particle", "unknown particle \"$name\"") }
    }

    private fun expect(node: ConfigNode, particle: Particle, type: Class<*>): Boolean {
        if (type.isAssignableFrom(particle.dataType)) return true
        node.error("particle", "${particle.name} does not take ${type.simpleName} data (it takes ${particle.dataType.simpleName})")
        return false
    }

    private fun material(node: ConfigNode, key: String, requireBlock: Boolean): Material? {
        val raw = node.requireString(key) ?: return null
        val material = Material.matchMaterial(raw)
        return when {
            material == null -> null.also { node.error(key, "unknown material \"$raw\"") }
            requireBlock && !material.isBlock -> null.also { node.error(key, "${material.name} is not a block") }
            !requireBlock && !material.isItem -> null.also { node.error(key, "${material.name} is not an item") }
            else -> material
        }
    }

    private fun createBlockData(node: ConfigNode, material: Material): BlockData? =
        runCatching { material.createBlockData() }.getOrElse {
            node.error("block", "cannot create block data for ${material.name}: ${it.message}")
            null
        }

    private fun parsePalette(node: ConfigNode): Palette? {
        val colors = node.colorList("colors")
        if (colors.isEmpty()) {
            node.error("colors", "a palette needs at least one colour")
            return null
        }
        return Palette(colors, node.int("cycle-ticks", 80, 1..72_000), node.easing("curve", Easing.Sine))
    }

    // ── animation & emission ─────────────────────────────────────────────

    private fun parseAnimation(node: ConfigNode?, type: CosmeticType?): Animation {
        if (node == null) {
            return if (type == CosmeticType.WINGS) Animation.STILL.copy(followBodyYaw = true, flap = DEFAULT_FLAP)
            else Animation.STILL.copy(followBodyYaw = type == CosmeticType.TRAIL)
        }
        val rotate = node.node("rotate")?.let {
            Vec3(it.double("pitch", 0.0), it.double("yaw", 0.0), it.double("roll", 0.0))
        } ?: Vec3.ZERO
        val keyframes = node.nodeList("keyframes").map {
            Keyframe(it.double("at", 0.0, 0.0..1.0), it.double("scale", 1.0, 0.0..32.0), it.easing("curve", Easing.Linear))
        }.sortedBy { it.at }

        return Animation(
            rotate = rotate,
            followBodyYaw = node.bool("follow-body-yaw", type == CosmeticType.WINGS || type == CosmeticType.TRAIL),
            bob = node.node("bob")?.let(::parseOscillation),
            flap = node.node("flap")?.let {
                Oscillation(it.double("angle", 20.0, 0.0..90.0), it.int("period-ticks", 40, 2..1200), it.easing("curve", Easing.Sine))
            } ?: DEFAULT_FLAP.takeIf { type == CosmeticType.WINGS },
            scale = node.double("scale", 1.0, 0.01..16.0),
            keyframes = keyframes,
        )
    }

    private fun parseOscillation(node: ConfigNode) = Oscillation(
        amplitude = node.double("amplitude", 0.1, -16.0..16.0),
        periodTicks = node.int("period-ticks", 40, 2..72_000),
        easing = node.easing("curve", Easing.Sine),
    )

    private fun parseEmit(node: ConfigNode?, type: CosmeticType?): Emit {
        val defaultInterval = when (type) {
            CosmeticType.TRAIL, CosmeticType.PROJECTILE -> 1
            else -> 2
        }
        if (node == null) {
            return Emit(defaultInterval, 1, Vec3.ZERO, 0.0, 64, if (type == CosmeticType.EMOTE) EmitMode.Progressive else EmitMode.All, null)
        }
        val modeName = node.string("mode", if (type == CosmeticType.EMOTE) "progressive" else "all").lowercase()
        val mode = when (modeName) {
            "all" -> EmitMode.All
            "progressive" -> EmitMode.Progressive
            "sweep" -> node.node("sweep").let {
                EmitMode.Sweep(
                    heads = it?.int("heads", 3, 1..64) ?: 3,
                    cyclesPerSecond = it?.double("cycles-per-second", 0.25, -20.0..20.0) ?: 0.25,
                    tail = it?.int("tail", 2, 0..64) ?: 2,
                )
            }
            "random" -> EmitMode.Random(node.node("random")?.int("count", 6, 1..512) ?: 6)
            else -> EmitMode.All.also { node.error("mode", "unknown emit mode \"$modeName\" (expected one of: all, sweep, random, progressive)") }
        }
        return Emit(
            intervalTicks = node.int("interval-ticks", defaultInterval, 1..200),
            count = node.int("count", 1, 0..50),
            spread = node.vec3("spread", Vec3.ZERO),
            speed = node.double("speed", 0.0, 0.0..10.0),
            maxPoints = node.int("max-points-per-frame", 64, 1..2_000),
            mode = mode,
            direction = if (node.has("direction")) node.vec3("direction", Vec3.UP) else null,
        )
    }

    // ── behaviour per type ───────────────────────────────────────────────

    private fun parseBehavior(type: CosmeticType, root: ConfigNode): Behavior {
        val node = root.node(type.id)
        return when (type) {
            CosmeticType.TRAIL -> Behavior.Trail(
                minSpeed = node?.double("min-speed", 0.08, 0.0..10.0) ?: 0.08,
                mode = node?.choice("mode", TrailMode.BEHIND, TrailMode.entries.map { it.id }, TrailMode::byId) ?: TrailMode.BEHIND,
                lingerTicks = node?.int("linger-ticks", 0, 0..200) ?: 0,
                dropIntervalTicks = node?.int("drop-interval-ticks", 6, 1..200) ?: 6,
            )
            CosmeticType.AURA -> Behavior.Aura(idleOnly = node?.bool("idle-only", false) ?: false)
            CosmeticType.WINGS -> Behavior.Wings(
                scale = node?.double("scale", 1.0, 0.1..4.0) ?: 1.0,
                tilt = node?.double("tilt", 15.0, -60.0..60.0) ?: 15.0,
                height = node?.double("height", 1.35, 0.0..3.0) ?: 1.35,
                back = node?.double("back", 0.28, -1.0..2.0) ?: 0.28,
                sneakTuck = node?.bool("sneak-tuck", true) ?: true,
            )
            CosmeticType.PROJECTILE -> {
                val raw = node?.stringList("applies-to") ?: emptyList()
                val types = if (raw.isEmpty() || raw.any { it.equals("ALL", true) }) emptySet()
                else raw.mapIndexedNotNull { i, name ->
                    EntityType.entries.firstOrNull { it.name.equals(name.trim(), true) }
                        ?: null.also { node?.error("applies-to[$i]", "unknown entity type \"$name\"") }
                }.toSet()
                Behavior.Projectile(types, node?.int("max-lifetime-ticks", 100, 1..1200) ?: 100)
            }
            CosmeticType.EMOTE -> {
                val triggers = node?.stringList("trigger")?.mapIndexedNotNull { i, raw ->
                    EmoteTrigger.byId(raw) ?: null.also { node.error("trigger[$i]", "unknown trigger \"$raw\" (expected kill or command)") }
                }?.toSet()?.ifEmpty { null } ?: EmoteTrigger.entries.toSet()
                Behavior.Emote(
                    durationTicks = node?.int("duration-ticks", 40, 1..600) ?: 40,
                    triggers = triggers,
                    cooldownSeconds = node?.int("cooldown-seconds", 10, 0..86_400) ?: 10,
                )
            }
        }
    }

    // ── presentation ─────────────────────────────────────────────────────

    private fun parseIcon(node: ConfigNode?, type: CosmeticType?, root: ConfigNode): IconSpec {
        val fallback = when (type) {
            CosmeticType.TRAIL -> Material.PINK_PETALS
            CosmeticType.AURA -> Material.SPORE_BLOSSOM
            CosmeticType.WINGS -> Material.FEATHER
            CosmeticType.PROJECTILE -> Material.ARROW
            CosmeticType.EMOTE -> Material.FIREWORK_STAR
            null -> Material.PAPER
        }
        if (node == null) {
            root.string("icon")?.let { raw ->
                val material = Material.matchMaterial(raw)?.takeIf { it.isItem }
                if (material == null) root.error("icon", "unknown item \"$raw\"")
                return IconSpec(material ?: fallback, null, false)
            }
            return IconSpec(fallback, null, false)
        }
        val material = node.string("material")?.let { raw ->
            Material.matchMaterial(raw)?.takeIf { it.isItem } ?: null.also { node.error("material", "unknown item \"$raw\"") }
        } ?: fallback
        val model = if (node.has("custom-model-data")) node.int("custom-model-data", 0, 0..Int.MAX_VALUE) else null
        return IconSpec(material, model?.takeIf { it > 0 }, node.bool("glow", false))
    }

    private fun parseUnlock(node: ConfigNode?, id: CosmeticId) = UnlockSpec(
        permission = node?.string("permission", "hanami.cosmetic.${id.value}") ?: "hanami.cosmetic.${id.value}",
        price = node?.optionalDouble("price", 0.0..1.0e12),
        default = node?.bool("default", false) ?: false,
    )

    companion object {
        val DEFAULT_FLAP = Oscillation(20.0, 40, Easing.Sine)

        fun particleByName(name: String): Particle? {
            val key = name.trim().uppercase().removePrefix("MINECRAFT:")
            return Particle.entries.firstOrNull { it.name == key }
        }
    }
}
