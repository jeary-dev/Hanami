package dev.jeary.hanami.admin.creator

import dev.jeary.hanami.config.ConfigNode
import dev.jeary.hanami.config.ConfigService
import dev.jeary.hanami.config.CosmeticParser
import dev.jeary.hanami.config.ValidationResult
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.math.shape.ShapeCache
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Turns a [CosmeticDraft] into a real [CosmeticDefinition] by running its YAML through the same
 * [CosmeticParser] that loads `cosmetics.yml`. The creator therefore can't produce anything the
 * file couldn't, and every error reads exactly like a reload error.
 */
class DraftCompiler(private val shapes: ShapeCache) {

    fun compile(draft: CosmeticDraft): ValidationResult<CosmeticDefinition> {
        val yaml = YamlConfiguration()
        yaml.createSection("cosmetics").createSection(draft.id, draft.toYaml())
        val root = ConfigNode(ConfigService.COSMETICS, yaml, "", mutableListOf())
        val section = root.node("cosmetics") ?: return ValidationResult.Invalid(emptyList())
        return CosmeticParser(shapes).parse(ConfigService.COSMETICS, draft.id, section)
    }
}

/**
 * Appends finished drafts to `cosmetics.yml` as plain text, below everything already there.
 * Rewriting the file through Bukkit's YAML API would reflow the hand-written flow-style entries
 * (`{ material: X }`) into block style and could drop formatting; appending keeps the admin's file
 * exactly as they left it.
 */
class CosmeticFileWriter(private val dataFolder: File) {

    sealed interface Result {
        data object Saved : Result
        data class Failed(val reason: String) : Result
    }

    fun append(draft: CosmeticDraft, author: String): Result {
        val file = File(dataFolder, ConfigService.COSMETICS)
        val existing = try {
            YamlConfiguration().apply { load(file) }
        } catch (e: Exception) {
            return Result.Failed("cosmetics.yml can't be read (${e.message?.lineSequence()?.firstOrNull()}); fix it first")
        }
        val topLevel = existing.getKeys(false)
        if (topLevel.any { it != "cosmetics" }) {
            return Result.Failed("cosmetics.yml has top-level keys other than `cosmetics:`; add the entry by hand")
        }
        if (existing.contains("cosmetics.${draft.id}")) return Result.Failed("a cosmetic called ${draft.id} already exists")

        val body = YamlConfiguration().apply { createSection(draft.id, draft.toYaml()) }.saveToString()
        return try {
            val text = if (file.exists()) file.readText(StandardCharsets.UTF_8) else ""
            val block = buildString {
                if (text.isNotEmpty() && !text.endsWith("\n")) append('\n')
                append('\n')
                // `cosmetics:` with nothing under it isn't reported as a key, so check the text.
                if (!ROOT_KEY.containsMatchIn(text)) append("cosmetics:\n")
                append("  # Created in-game by ").append(author).append(" on ").append(LocalDate.now()).append('\n')
                body.lineSequence().filter { it.isNotEmpty() }.forEach { append("  ").append(it).append('\n') }
            }
            file.appendText(block, StandardCharsets.UTF_8)
            Result.Saved
        } catch (e: IOException) {
            Result.Failed("could not write cosmetics.yml: ${e.message}")
        }
    }

    private companion object {
        val ROOT_KEY = Regex("(?m)^cosmetics:")
    }
}
