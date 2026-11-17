package dev.jeary.hanami.core.unlock

import dev.jeary.hanami.core.profile.PlayerProfile
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import org.bukkit.entity.Player

/** How a player owns (or could own) a cosmetic — drives the menu's lore and click behaviour. */
sealed interface Ownership {
    data object Owned : Ownership
    data class Purchasable(val price: Double) : Ownership
    data object Locked : Ownership
}

/**
 * A cosmetic is unlocked when it is free for everyone, the player has its permission (or the
 * `hanami.cosmetic.*` wildcard), or it was granted/bought and stored in their profile.
 */
class UnlockService(private val economyAvailable: () -> Boolean) {

    fun isUnlocked(player: Player, cosmetic: CosmeticDefinition, profile: PlayerProfile?): Boolean =
        cosmetic.unlock.default ||
            (profile?.hasUnlocked(cosmetic.id) == true) ||
            player.hasPermission(cosmetic.permission) ||
            player.hasPermission(WILDCARD)

    fun ownership(player: Player, cosmetic: CosmeticDefinition, profile: PlayerProfile?): Ownership {
        if (isUnlocked(player, cosmetic, profile)) return Ownership.Owned
        val price = cosmetic.unlock.price
        return if (price != null && economyAvailable()) Ownership.Purchasable(price) else Ownership.Locked
    }

    companion object {
        const val WILDCARD = "hanami.cosmetic.*"
    }
}
