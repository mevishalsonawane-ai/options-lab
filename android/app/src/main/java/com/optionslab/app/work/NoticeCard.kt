package com.optionslab.app.work

/**
 * What a tapped notification opens over the app (Boss, 5 Oct: "I should go to the application with that banner, with
 * the details"): its title, its whole text, when it came, and - for one that asks something - what it asks, by id only.
 *
 *  - [action]: a request of Jarvis's waiting for Boss's Confirm ([com.optionslab.app.ira.IraHub] pending id; a news
 *    trade, a stop, a close, the kill switch). The banner shows the chat's own Confirm / Cancel for it.
 *  - [proposal]: a strategy Jarvis backtested, waiting for Approve / Dismiss (the chat's own buttons).
 *  - [close]: "Paper|SYMBOL" or "Live|SYMBOL": a position's card; the banner opens the app's own close popup for it.
 *  - [approve]: an arm's entry or a strategy's start waiting on Home; the banner shows Home's own rows for it (their
 *    Approve / Skip, the PIN or fingerprint in Live).
 *  - [setting]: a key of [NoticeCards.SETTINGS]; the app opens that Settings page with the row highlighted.
 *
 * Nothing secret is ever in it: no token, key or Zerodha order id - only the words the notification itself shows, and
 * the app's own ids, which mean nothing outside it. Pure Kotlin, so the encoding and the routing are tested on the JVM.
 */
data class NoticeCard(
    /** The notification's own id (the same id posted again replaces the card, as the notification is replaced). */
    val id: Int,
    /** The channel it was posted on, or "jarvis" / "approval" for Jarvis's own pop-ups. */
    val kind: String,
    val title: String,
    val text: String,
    /** When it was posted (epoch ms). */
    val at: Long,
    /** The page the notification opened before the banner existed ([com.optionslab.app.MainActivity.EXTRA_TAB]). */
    val tab: String? = null,
    val setting: String? = null,
    val action: Long? = null,
    val proposal: Long? = null,
    val close: String? = null,
    /** An entry waiting for approval on Home: "orb" (an ORB / liquidity arm's entry) or "strategy" (a scheduled start). */
    val approve: String? = null,
) {
    /** "Paper" or "Live" for a position's card, else null. */
    val closeVenue: String? get() = close?.substringBefore('|', "")?.takeIf { it == "Paper" || it == "Live" }
    val closeSymbol: String? get() = if (closeVenue == null) null else close?.substringAfter('|', "")?.takeIf { it.isNotBlank() }
}

object NoticeCards {
    private const val P = "notice."
    /** A notification's text is short; anything longer is cut (an intent's extras must stay small). */
    const val MAX_TEXT = 4_000

    /**
     * The Settings rows a notification can point at, and the Settings page holding each
     * ([com.optionslab.app.ui.screens.CabinetScreen]'s page keys). Each row carries its key
     * ([com.optionslab.app.ui.screens.SettingSpot]); an unknown key opens no page.
     */
    val SETTINGS: Map<String, String> = mapOf(
        "broker.login" to "broker",
        "broker.staticip" to "broker",
        "security.backup" to "security",
        "security.unlock" to "security",
        "schedule.notifications" to "schedule",
        "schedule.permissions" to "schedule",
        "schedule.holidays" to "schedule",
        "risk.guards" to "risk",
        "jarvis.voice" to "jarvis",
        "jarvis.model" to "jarvis",
        "jarvis.automations" to "jarvis",
    )

    fun settingsPage(key: String?): String? = key?.let { SETTINGS[it] }

    /** Where a card takes the app: a Settings row, or the page the notification always opened ([NavState.request]). */
    sealed interface Route {
        data class Page(val dest: String?) : Route
        data class Setting(val page: String, val key: String) : Route
    }

    fun route(c: NoticeCard): Route {
        val page = settingsPage(c.setting)
        return if (page != null && c.setting != null) Route.Setting(page, c.setting) else Route.Page(c.tab)
    }

    /** Does the card ask Boss something (it then shows that question's own buttons, or why it no longer can)? */
    fun asks(c: NoticeCard): Boolean = c.action != null || c.proposal != null || c.closeSymbol != null || c.approve != null

    /** The kinds of entry approval a card may name (anything else is dropped). */
    val APPROVALS = setOf("orb", "strategy")

    /**
     * A request code of its own for each notification, so two notifications' PendingIntents never collapse into one
     * (with FLAG_UPDATE_CURRENT the later one's card would replace the earlier one's). The same notification posted
     * again (the same id) reuses its code, as it replaces the notification.
     */
    fun requestCode(c: NoticeCard): Int = 0x4E000000 xor (c.kind.hashCode() * 31 + c.id)

    /** The card as string extras (only what the notification shows, and the app's own ids). */
    fun toExtras(c: NoticeCard): Map<String, String> = buildMap {
        put(P + "id", c.id.toString())
        put(P + "kind", c.kind)
        put(P + "title", c.title.take(300))
        put(P + "text", c.text.take(MAX_TEXT))
        put(P + "at", c.at.toString())
        c.tab?.let { put(P + "tab", it) }
        c.setting?.let { put(P + "setting", it) }
        c.action?.let { put(P + "action", it.toString()) }
        c.proposal?.let { put(P + "proposal", it.toString()) }
        c.close?.let { put(P + "close", it) }
        c.approve?.let { put(P + "approve", it) }
    }

    /** The card back from [get] (an intent's string extras), or null when there is none or it is malformed. */
    fun fromExtras(get: (String) -> String?): NoticeCard? {
        val id = get(P + "id")?.toIntOrNull() ?: return null
        val title = get(P + "title") ?: return null
        val text = get(P + "text") ?: return null
        val at = get(P + "at")?.toLongOrNull() ?: return null
        return NoticeCard(
            id = id, kind = get(P + "kind") ?: "", title = title, text = text.take(MAX_TEXT), at = at,
            tab = get(P + "tab"), setting = get(P + "setting")?.takeIf { it in SETTINGS },
            action = get(P + "action")?.toLongOrNull(), proposal = get(P + "proposal")?.toLongOrNull(),
            close = get(P + "close")?.takeIf { it.startsWith("Paper|") || it.startsWith("Live|") },
            approve = get(P + "approve")?.takeIf { it in APPROVALS },
        )
    }

    /**
     * The whole card, kept in memory only (never on disk) for a notification whose shown text is shorter than the card:
     * a pop-up posted while the phone was locked shows only that it is in the chat ([com.optionslab.ira.Overheard]);
     * opened in the unlocked app, the banner shows it in full. Lost with the process - the shown text is then used.
     */
    private val kept = object : LinkedHashMap<String, NoticeCard>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NoticeCard>?): Boolean = size > 60
    }

    private fun keyOf(c: NoticeCard) = "${c.kind}|${c.id}|${c.at}"

    fun keep(c: NoticeCard) { synchronized(kept) { kept[keyOf(c)] = c } }

    /** The kept card for [shown] (the same notification), or [shown] itself. */
    fun resolve(shown: NoticeCard): NoticeCard = synchronized(kept) { kept[keyOf(shown)] } ?: shown

    fun forgetAll() { synchronized(kept) { kept.clear() } }

    /**
     * The Settings row a morning check points at, if any ([com.optionslab.app.work.DailyReports]): the Zerodha login
     * first (nothing trades without it), then the battery (a battery-optimized app's order watch can be stopped by
     * Android: Settings → Schedule → Permissions has its Battery row), the microphone, the AI model, then the backup reminder.
     */
    fun morningSetting(lines: List<String>, needsLogin: Boolean): String? = when {
        needsLogin -> "broker.login"
        lines.any { com.optionslab.ira.WatchHealth.isBatteryFix(it) } -> "schedule.permissions"
        lines.any { (it.contains("Microphone permission") || it.contains("Voice:")) && it.contains("not working") } -> "jarvis.voice"
        lines.any { it.contains("AI model") && it.contains("not working") } -> "jarvis.model"
        lines.any { it.contains("back up", ignoreCase = true) || it.contains("backup", ignoreCase = true) } -> "security.backup"
        else -> null
    }
}
