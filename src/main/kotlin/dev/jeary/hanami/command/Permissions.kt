package dev.jeary.hanami.command

/** Every permission node Hanami checks (also declared in plugin.yml). */
object Permissions {
    const val MENU = "hanami.command.menu"
    const val EQUIP = "hanami.command.equip"
    const val EQUIP_OTHERS = "hanami.command.equip.others"
    const val PREVIEW = "hanami.command.preview"
    const val EMOTE = "hanami.command.emote"
    const val GIVE = "hanami.command.give"
    const val REVOKE = "hanami.command.revoke"
    const val RELOAD = "hanami.command.reload"
    const val DEBUG = "hanami.command.debug"
    const val ADMIN = "hanami.command.admin"
    const val CREATE = "hanami.command.create"
    const val ALL_COSMETICS = "hanami.cosmetic.*"
}
