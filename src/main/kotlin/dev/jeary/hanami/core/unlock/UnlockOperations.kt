package dev.jeary.hanami.core.unlock

import dev.jeary.hanami.api.event.CosmeticUnlockEvent
import dev.jeary.hanami.api.event.UnlockSource
import dev.jeary.hanami.core.profile.ProfileService
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/** A money backend. Implemented by the Vault hook; absent when no economy is installed. */
interface EconomyProvider {
    val name: String
    fun balance(player: OfflinePlayer): Double

    /** @return null on success, otherwise the economy's error message. */
    fun withdraw(player: OfflinePlayer, amount: Double): String?

    fun format(amount: Double): String
}

sealed interface PurchaseResult {
    data class Success(val price: Double) : PurchaseResult
    data object AlreadyOwned : PurchaseResult
    data object NotForSale : PurchaseResult
    data object NoEconomy : PurchaseResult
    data class InsufficientFunds(val price: Double, val balance: Double) : PurchaseResult
    data class Failed(val reason: String) : PurchaseResult
    data object Cancelled : PurchaseResult
    data object ProfileLoading : PurchaseResult
}

sealed interface GrantResult {
    data object Granted : GrantResult
    data object AlreadyOwned : GrantResult
    data object Cancelled : GrantResult
    data class Failed(val error: Throwable) : GrantResult
}

sealed interface RevokeResult {
    data object Revoked : RevokeResult
    data object NotGranted : RevokeResult
    data class Failed(val error: Throwable) : RevokeResult
}

/**
 * Permanent unlocks: buying with money and admin grants/revokes. Online players are updated in
 * their cached profile; offline players are edited directly in storage on the storage thread.
 * Results always complete on the main thread so callers can message players directly.
 */
class UnlockOperations(
    private val plugin: Plugin,
    private val profiles: ProfileService,
    private val unlocks: UnlockService,
    private val economy: () -> EconomyProvider?,
) {

    fun purchase(player: Player, cosmetic: CosmeticDefinition): PurchaseResult {
        val profile = profiles[player] ?: return PurchaseResult.ProfileLoading
        if (unlocks.isUnlocked(player, cosmetic, profile)) return PurchaseResult.AlreadyOwned
        val price = cosmetic.unlock.price ?: return PurchaseResult.NotForSale
        val eco = economy() ?: return PurchaseResult.NoEconomy

        val balance = eco.balance(player)
        if (balance < price) return PurchaseResult.InsufficientFunds(price, balance)

        val event = CosmeticUnlockEvent(player, cosmetic, UnlockSource.PURCHASE)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) return PurchaseResult.Cancelled

        eco.withdraw(player, price)?.let { return PurchaseResult.Failed(it) }
        profile.unlock(cosmetic.id)
        profiles.saveAsync(profile)
        return PurchaseResult.Success(price)
    }

    fun grant(target: OfflinePlayer, cosmetic: CosmeticDefinition): CompletableFuture<GrantResult> {
        val event = CosmeticUnlockEvent(target, cosmetic, UnlockSource.ADMIN)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) return CompletableFuture.completedFuture(GrantResult.Cancelled)

        profiles[target.uniqueId]?.let { profile ->
            val added = profile.unlock(cosmetic.id)
            profiles.saveAsync(profile)
            return CompletableFuture.completedFuture(if (added) GrantResult.Granted else GrantResult.AlreadyOwned)
        }
        val id = cosmetic.id.value
        return profiles.submit {
            val existing = profiles.repository().load(target.uniqueId)
            if (existing != null && id in existing.unlocked) GrantResult.AlreadyOwned
            else {
                profiles.repository().addUnlock(target.uniqueId, id)
                GrantResult.Granted
            }
        }.exceptionally { GrantResult.Failed(it) }.onMainThread()
    }

    fun revoke(target: OfflinePlayer, cosmetic: CosmeticDefinition): CompletableFuture<RevokeResult> {
        profiles[target.uniqueId]?.let { profile ->
            val removed = profile.revoke(cosmetic.id)
            if (profile.equippedId(cosmetic.type) == cosmetic.id && target is Player &&
                !unlocks.isUnlocked(target, cosmetic, profile)
            ) {
                profile.unequip(cosmetic.type)
            }
            profiles.saveAsync(profile)
            return CompletableFuture.completedFuture(if (removed) RevokeResult.Revoked else RevokeResult.NotGranted)
        }
        return profiles.submit<RevokeResult> {
            if (profiles.repository().removeUnlock(target.uniqueId, cosmetic.id.value)) RevokeResult.Revoked
            else RevokeResult.NotGranted
        }.exceptionally { RevokeResult.Failed(it) }.onMainThread()
    }

    private fun <T> CompletableFuture<T>.onMainThread(): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        whenComplete { value, error ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (error != null) result.completeExceptionally(error) else result.complete(value)
            })
        }
        return result
    }
}
