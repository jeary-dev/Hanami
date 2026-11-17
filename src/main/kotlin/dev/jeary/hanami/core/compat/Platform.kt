package dev.jeary.hanami.core.compat

import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** The server software Hanami is running on, detected once at startup. */
sealed class Platform(val displayName: String) {
    data object Spigot : Platform("Spigot")
    data object Paper : Platform("Paper")

    companion object {
        private val PAPER_MARKERS = listOf(
            "io.papermc.paper.ServerBuildInfo",
            "com.destroystokyo.paper.PaperConfig",
            "io.papermc.paper.configuration.Configuration",
        )

        fun detect(): Platform =
            if (PAPER_MARKERS.any(::classExists)) Paper else Spigot

        private fun classExists(name: String) = try {
            Class.forName(name, false, Platform::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

/**
 * The tiny surface of platform-specific behaviour Hanami needs. Hanami compiles against Spigot
 * only; Paper features are reached through [PaperAdapter] with method handles, so a missing
 * method simply turns a feature off instead of crashing.
 */
interface PlatformAdapter {
    val platform: Platform

    /** The entity's body (torso) yaw in degrees, or null when the platform cannot tell. */
    fun bodyYaw(entity: LivingEntity): Float?

    /** Players currently tracking [entity] (i.e. receiving its packets), or null if unknown. */
    fun trackedBy(entity: Entity): Set<Player>?

    /** Server's own average tick time in ms, or null if not exposed. */
    fun averageTickTimeMs(server: Server): Double?

    /** Names of the optional features that are active, for `/hanami debug`. */
    val features: List<String>

    companion object {
        fun create(platform: Platform = Platform.detect()): PlatformAdapter = when (platform) {
            Platform.Paper -> PaperAdapter()
            Platform.Spigot -> SpigotAdapter
        }
    }
}

object SpigotAdapter : PlatformAdapter {
    override val platform: Platform = Platform.Spigot
    override fun bodyYaw(entity: LivingEntity): Float? = null
    override fun trackedBy(entity: Entity): Set<Player>? = null
    override fun averageTickTimeMs(server: Server): Double? = null
    override val features: List<String> = emptyList()
}

class PaperAdapter : PlatformAdapter {
    override val platform: Platform = Platform.Paper

    private val lookup = MethodHandles.publicLookup()

    private val getBodyYaw: MethodHandle? =
        find(LivingEntity::class.java, "getBodyYaw", MethodType.methodType(Float::class.javaPrimitiveType))

    private val getTrackedBy: MethodHandle? =
        find(Entity::class.java, "getTrackedBy", MethodType.methodType(Set::class.java))

    private val getAverageTickTime: MethodHandle? =
        find(Server::class.java, "getAverageTickTime", MethodType.methodType(Double::class.javaPrimitiveType))

    override val features: List<String> = buildList {
        if (getBodyYaw != null) add("body-yaw")
        if (getTrackedBy != null) add("entity-tracker viewers")
        if (getAverageTickTime != null) add("mspt")
    }

    override fun bodyYaw(entity: LivingEntity): Float? =
        getBodyYaw?.let { runCatching { it.invoke(entity) as Float }.getOrNull() }

    override fun trackedBy(entity: Entity): Set<Player>? =
        getTrackedBy?.let { handle ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                handle.invoke(entity) as Set<Player>
            }.getOrNull()
        }

    override fun averageTickTimeMs(server: Server): Double? =
        getAverageTickTime?.let { runCatching { it.invoke(server) as Double }.getOrNull() }

    private fun find(owner: Class<*>, name: String, type: MethodType): MethodHandle? = try {
        lookup.findVirtual(owner, name, type)
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: IllegalAccessException) {
        null
    }
}
