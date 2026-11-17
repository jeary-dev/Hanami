package dev.jeary.hanami.api

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.api.cosmetic.CosmeticView
import dev.jeary.hanami.api.player.HanamiProfile
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * The public Hanami API.
 *
 * ```kotlin
 * val hanami = HanamiApi.get() ?: return
 * hanami.cosmetic(CosmeticId.of("sakura_drift"))?.let { hanami.equip(player, it) }
 * ```
 * Hanami also fires cancellable events (see `dev.jeary.hanami.api.event`).
 */
interface HanamiApi {

    /** All loaded cosmetics, in menu order. */
    fun cosmetics(): List<CosmeticView>

    fun cosmetics(type: CosmeticType): List<CosmeticView>

    fun cosmetic(id: CosmeticId): CosmeticView?

    /** The player's profile, or `null` while it is still loading (or they are offline). */
    fun profile(player: Player): HanamiProfile?

    fun isUnlocked(player: Player, cosmetic: CosmeticView): Boolean

    /**
     * Equips a cosmetic, firing [dev.jeary.hanami.api.event.CosmeticEquipEvent].
     * @param bypassLock equip even when the player has not unlocked it.
     * @return true when the cosmetic was equipped.
     */
    fun equip(player: Player, cosmetic: CosmeticView, bypassLock: Boolean = false): Boolean

    fun unequip(player: Player, type: CosmeticType): Boolean

    /** Plays [cosmetic] for [player] only, for [seconds], without equipping it. */
    fun preview(player: Player, cosmetic: CosmeticView, seconds: Int = 5)

    companion object {
        /** The running API instance, or `null` when Hanami is not enabled. */
        @JvmStatic
        fun get(): HanamiApi? = Bukkit.getServicesManager().load(HanamiApi::class.java)
    }
}
