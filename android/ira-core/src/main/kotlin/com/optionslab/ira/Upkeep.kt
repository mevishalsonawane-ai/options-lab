package com.optionslab.ira

import java.time.LocalDate

/**
 * Keeping Jarvis's stores small and the backup clean (reliability round 7). Jarvis keeps many small records in the
 * settings vault, and every write re-encrypts the whole vault; the long-running app also keeps "told once today" sets in
 * memory. Here: which settings may leave the phone in a backup ([carried]), the day a per-day key or a day-keyed entry
 * belongs to ([dayOf]) so old ones can be dropped, a cap that drops the oldest of an ordered set ([trimOldest]), and how
 * often a record whose only change is a count is written ([dueToSave]). Pure.
 */
object Upkeep {

    // ---- the backup -------------------------------------------------------------------------------------------------

    /**
     * Settings (by prefix) that stay on this phone only: never written to a backup, never taken from one.
     *
     * RULE: safety settings never come from a file. Trading mode and order limits (k.), the risk guard - kill switch,
     * loss / drawdown / exposure / lot / value limits, cut-off, expiry square-off (g.) - the security switches (sec.),
     * the idle lock (lock.), the PIN (pin.) and what the widget may show (ui.widgetPnl, and its cached figures w.) are
     * this phone's alone: a restored file must never be able to switch on live trading, clear the kill switch, loosen a
     * limit or weaken a lock. A new setting of that kind needs its prefix added here.
     */
    val PRIVATE: List<String> = listOf("jarvis.memory", "kite.", "draft.kite", "pin.", "tls.", "ol.vault", "hb.", "sq.", "report.", "k.", "g.",
        "sec.", "lock.", "ui.widgetPnl", "w.", "intent.", "relay.", "breaker.",
        // Jarvis: the owner's voice print never leaves the phone; its trades' safety (paper first - the "AI trades go live"
        // switch -, loss limit, risk) and the autopilot are this phone's alone, like the other limits. Its learning (the
        // study, records) is carried.
        "jarvis.voiceprint", "jarvis.trades.", "jarvis.autopilot",
        // The record that earns live trading and lot sizing, and the model's verified mark, are never taken from a file.
        "jarvis.newstrades", "ira.model.verified",
        // Solo: its switch, its pause, and its paper record - which earns its trades real orders, like Jarvis's own
        // record above - and its learned brain (the minds it trades by): this phone's alone (round 7).
        "jarvis.solo", "solo.",
        // What Jarvis does by himself (the guard places real stop orders; it is switched on with the fingerprint only).
        "jarvis.group.", "jarvis.auto.", "jarvis.autotrail",
        // Commands Boss set for a time ("start all the arms at 9") run by themselves at their time: confirmed on this
        // phone only, never run from a file (round 7). His reminders - words only - are a separate key and are carried.
        "jarvis.later",
        // Whether Jarvis's ears may use Google's speech service (speech may leave the phone): Boss's choice on this phone.
        // So is "Don't listen" (jarvis.voice.nolisten, 6 Oct): a restore never switches the microphone back on behind him.
        "jarvis.voice.",
        // The history of this phone's limits: what "Jarvis, undo" puts back, so a file's history could set a limit (round 7).
        "settings.")

    /** May the setting [key] go into a backup, or be taken from one? */
    fun carried(key: String): Boolean = PRIVATE.none { key.startsWith(it) }

    // ---- per-day keys and day-keyed entries -----------------------------------------------------------------------

    /**
     * The day [key] belongs to: the first of its "|"- or "."-separated parts that starts with an ISO date
     * ("2026-10-05|NIFTY", "NIFTY|2026-10-05", "jarvis.usage.2026-10-05", "2026-10-05T10:15"); null when none does.
     */
    fun dayOf(key: String): LocalDate? {
        var i = 0
        while (i <= key.length - 10) {
            val starts = i == 0 || key[i - 1] == '|' || key[i - 1] == '.'
            if (starts && key[i + 4] == '-' && key[i + 7] == '-' && key[i].isDigit()) {
                val d = runCatching { LocalDate.parse(key.substring(i, i + 10)) }.getOrNull()
                if (d != null) return d
            }
            i++
        }
        return null
    }

    /**
     * Of [keys], those starting with [prefix] whose day ([dayOf]) is more than [keepDays] days before [today] - to drop.
     * A key with no day in it is never dropped.
     */
    fun staleDayKeys(keys: Iterable<String>, prefix: String, today: LocalDate, keepDays: Long): List<String> {
        val from = today.minusDays(keepDays.coerceAtLeast(0))
        return keys.filter { k -> k.startsWith(prefix) && dayOf(k.removePrefix(prefix))?.isBefore(from) == true }
    }

    /** [keys] (a "told once a day" set, or a day-keyed map's keys) without those of days more than [keepDays] before [today]. */
    fun dropOldDays(keys: MutableCollection<String>, today: LocalDate, keepDays: Long = 0) {
        val from = today.minusDays(keepDays.coerceAtLeast(0))
        keys.removeAll { k -> dayOf(k)?.isBefore(from) == true }
    }

    /** [set] (insertion-ordered) cut to its newest [max] entries. */
    fun <E> trimOldest(set: MutableSet<E>, max: Int) {
        val over = set.size - max.coerceAtLeast(0)
        if (over <= 0) return
        val iter = set.iterator()
        repeat(over) { iter.next(); iter.remove() }
    }

    // ---- counts-only writes -------------------------------------------------------------------------------------------

    /** A record whose only change is a count is written at most this often (the newest is in memory meanwhile). */
    const val COUNTS_EVERY_MS = 10 * 60_000L

    /**
     * Write now? Always when the change is [material] (anything but a count) or nothing was written yet this run; a
     * counts-only change when [COUNTS_EVERY_MS] passed since the last write (or the clock went back).
     */
    fun dueToSave(lastSavedMs: Long?, nowMs: Long, material: Boolean, everyMs: Long = COUNTS_EVERY_MS): Boolean =
        material || lastSavedMs == null || nowMs - lastSavedMs !in 0 until everyMs
}
