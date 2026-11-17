package dev.jeary.hanami.core.profile

import dev.jeary.hanami.config.PlayerDefaults
import dev.jeary.hanami.storage.ProfileRepository
import dev.jeary.hanami.storage.StoredProfile
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Caches profiles of online players and does all storage I/O off the main thread.
 *
 * Every storage call goes through ONE single-threaded executor. That keeps SQLite happy and,
 * more importantly, orders operations per player: a quick rejoin's load always runs after the
 * quit's save, so data can never be read stale.
 *
 * Loading happens in [AsyncPlayerPreLoginEvent] (already off the main thread), so by the time
 * the player joins their cosmetics are ready and nothing pops in late.
 */
class ProfileService(
    private val plugin: Plugin,
    private val repository: ProfileRepository,
    private val defaults: () -> PlayerDefaults,
) : Listener {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Hanami-Storage").apply { isDaemon = true }
    }
    private val online = ConcurrentHashMap<UUID, PlayerProfile>()
    private val preloaded = ConcurrentHashMap<UUID, Pair<Long, PlayerProfile>>()

    val backendName: String get() = repository.name
    val cachedCount: Int get() = online.size

    operator fun get(player: Player): PlayerProfile? = online[player.uniqueId]
    operator fun get(uuid: UUID): PlayerProfile? = online[uuid]

    fun all(): Collection<PlayerProfile> = online.values

    /** Loads profiles for players already online (plugin enabled mid-game). */
    fun loadOnlinePlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            loadAsync(player.uniqueId).thenAcceptMainThread { online[player.uniqueId] = it }
        }
    }

    // ── lifecycle events ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val profile = try {
            loadAsync(event.uniqueId).get(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Timed out loading Hanami data for ${event.name}; it will load after join", e)
            return
        }
        preloaded[event.uniqueId] = System.currentTimeMillis() to profile
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val ready = preloaded.remove(uuid)?.second
        if (ready != null) {
            online[uuid] = ready
        } else {
            loadAsync(uuid).thenAcceptMainThread { profile ->
                if (event.player.isOnline) online[uuid] = profile
            }
        }
        // Players whose login was denied after pre-login leave stale entries; sweep them.
        val cutoff = System.currentTimeMillis() - PRELOAD_TTL_MS
        preloaded.entries.removeIf { it.value.first < cutoff }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        online.remove(event.player.uniqueId)?.let(::saveAsync)
    }

    // ── operations ───────────────────────────────────────────────────────

    /** Loads (or creates) a profile. Never completes exceptionally: failures yield read-only. */
    fun loadAsync(uuid: UUID): CompletableFuture<PlayerProfile> =
        CompletableFuture.supplyAsync({
            try {
                repository.load(uuid)?.let(PlayerProfile::fromStored) ?: PlayerProfile.fresh(uuid, defaults())
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Could not load Hanami profile $uuid; using a temporary read-only profile", e)
                PlayerProfile.fresh(uuid, defaults(), readOnly = true)
            }
        }, executor)

    fun saveAsync(profile: PlayerProfile): CompletableFuture<Unit> {
        if (profile.readOnly || !profile.dirty) return CompletableFuture.completedFuture(Unit)
        val snapshot = profile.toStored()
        profile.markClean()
        return submit { repository.save(snapshot) }.whenComplete { _, error ->
            if (error != null) {
                plugin.logger.log(Level.SEVERE, "Could not save Hanami profile ${profile.uuid}", error)
            }
        }
    }

    fun saveDirty() = all().filter { it.dirty }.forEach(::saveAsync)

    /** Runs blocking storage work on the storage thread. */
    fun <T> submit(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work, executor)

    /** Load-modify-save for a player who may be offline. */
    fun editOffline(uuid: UUID, edit: (StoredProfile?) -> Unit): CompletableFuture<Unit> =
        submit { edit(repository.load(uuid)) }

    fun repository(): ProfileRepository = repository

    /** Saves everything synchronously and stops the storage thread. Called from onDisable. */
    fun shutdown() {
        online.values.forEach(::saveAsync)
        online.clear()
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            plugin.logger.warning("Storage did not finish within ${SHUTDOWN_TIMEOUT_SECONDS}s; some changes may be lost")
            executor.shutdownNow()
        }
        repository.close()
    }

    private fun <T> CompletableFuture<T>.thenAcceptMainThread(action: (T) -> Unit) {
        thenAccept { value -> Bukkit.getScheduler().runTask(plugin, Runnable { action(value) }) }
    }

    private companion object {
        const val LOGIN_TIMEOUT_SECONDS = 5L
        const val SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val PRELOAD_TTL_MS = 60_000L
    }
}
