package dev.jeary.hanami.storage.json

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.jeary.hanami.api.player.DensityLevel
import dev.jeary.hanami.storage.ProfileRepository
import dev.jeary.hanami.storage.StoredProfile
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * One human-readable JSON file per player (`players/<uuid>.json`). No database, no drivers:
 * the zero-setup default, and easy to inspect or hand-edit.
 *
 * ```json
 * {
 *   "version": 1,
 *   "show-others": true,
 *   "density": "high",
 *   "equipped": { "trail": "sakura_drift" },
 *   "unlocked": [ "torii" ],
 *   "updated-at": 1763337600000
 * }
 * ```
 *
 * Writes go to a temporary file first and are then moved over the real one, so a crash
 * mid-write can never leave a half-written profile. Like every repository this is blocking and
 * called from the storage thread; the methods are synchronized anyway so offline grants can
 * never interleave with a save of the same file.
 */
class JsonProfileRepository(private val folder: File) : ProfileRepository {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    override val name = "JSON"

    override fun init() {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create ${folder.absolutePath}")
    }

    @Synchronized
    override fun load(uuid: UUID): StoredProfile? {
        val file = fileOf(uuid)
        if (!file.isFile) return null
        val root = try {
            JsonParser.parseString(file.readText(StandardCharsets.UTF_8)).asJsonObject
        } catch (e: JsonParseException) {
            throw IOException("Corrupt profile file ${file.name}: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw IOException("Corrupt profile file ${file.name}: expected a JSON object", e)
        }
        return read(uuid, root)
    }

    @Synchronized
    override fun save(profile: StoredProfile) = write(profile)

    @Synchronized
    override fun addUnlock(uuid: UUID, cosmeticId: String) {
        val existing = load(uuid) ?: StoredProfile(uuid, emptyMap(), emptySet(), true, DensityLevel.HIGH)
        if (cosmeticId !in existing.unlocked) write(existing.copy(unlocked = existing.unlocked + cosmeticId))
    }

    @Synchronized
    override fun removeUnlock(uuid: UUID, cosmeticId: String): Boolean {
        val existing = load(uuid) ?: return false
        if (cosmeticId !in existing.unlocked) return false
        write(existing.copy(unlocked = existing.unlocked - cosmeticId))
        return true
    }

    override fun close() = Unit

    private fun fileOf(uuid: UUID) = File(folder, "$uuid.json")

    private fun read(uuid: UUID, root: JsonObject): StoredProfile {
        val equipped = root.getAsJsonObject("equipped")?.entrySet()
            ?.filter { (_, v) -> v.isJsonPrimitive }
            ?.associate { (k, v) -> k to v.asString }
            ?: emptyMap()
        val unlocked = root.getAsJsonArray("unlocked")
            ?.filter { it.isJsonPrimitive }
            ?.mapTo(LinkedHashSet()) { it.asString }
            ?: emptySet()
        val showOthers = root.get("show-others")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val density = root.get("density")?.takeIf { it.isJsonPrimitive }?.asString?.let(DensityLevel::byId) ?: DensityLevel.HIGH
        return StoredProfile(uuid, equipped, unlocked, showOthers, density)
    }

    private fun write(profile: StoredProfile) {
        val root = JsonObject().apply {
            addProperty("version", FORMAT_VERSION)
            addProperty("show-others", profile.showOthers)
            addProperty("density", profile.density.id)
            add("equipped", JsonObject().apply { profile.equipped.toSortedMap().forEach { (k, v) -> addProperty(k, v) } })
            add("unlocked", JsonArray().apply { profile.unlocked.forEach(::add) })
            addProperty("updated-at", System.currentTimeMillis())
        }
        val target = fileOf(profile.uuid)
        val temp = File(folder, "${profile.uuid}.json.tmp")
        temp.writeText(gson.toJson(root) + "\n", StandardCharsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
