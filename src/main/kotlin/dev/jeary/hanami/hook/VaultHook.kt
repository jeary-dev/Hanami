package dev.jeary.hanami.hook

import dev.jeary.hanami.core.HanamiCore
import dev.jeary.hanami.core.unlock.EconomyProvider
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent

/**
 * Lets players buy cosmetics with any Vault-compatible economy. Economy plugins sometimes
 * register with Vault after Hanami has enabled, so registrations are also watched live.
 */
class VaultHook : Hook, Listener {
    override val plugins = listOf("Vault")

    private lateinit var core: HanamiCore

    override fun enable(core: HanamiCore): String {
        this.core = core
        core.plugin.server.pluginManager.registerEvents(this, core.plugin)
        refresh()
        return "Vault (${core.economy?.name ?: "waiting for an economy plugin"})"
    }

    override fun disable() {
        HandlerList.unregisterAll(this)
        core.economy = null
    }

    @EventHandler
    fun onRegister(event: ServiceRegisterEvent) {
        if (event.provider.service == Economy::class.java) refresh()
    }

    @EventHandler
    fun onUnregister(event: ServiceUnregisterEvent) {
        if (event.provider.service == Economy::class.java) refresh()
    }

    private fun refresh() {
        val economy = core.plugin.server.servicesManager.getRegistration(Economy::class.java)?.provider
        core.economy = economy?.let(::VaultEconomy)
    }

    private class VaultEconomy(private val economy: Economy) : EconomyProvider {
        override val name: String get() = economy.name

        override fun balance(player: OfflinePlayer): Double = economy.getBalance(player)

        override fun withdraw(player: OfflinePlayer, amount: Double): String? {
            val response = economy.withdrawPlayer(player, amount)
            return if (response.transactionSuccess()) null else response.errorMessage ?: "unknown error"
        }

        override fun format(amount: Double): String = economy.format(amount)
    }
}
