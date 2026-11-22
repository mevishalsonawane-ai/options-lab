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
        // The removed Signal tester's Telegram login (15 Oct): Boss's api_id / api_hash and the key of TDLib's encrypted database.
        // 25 Oct (review): kept private after Telegram's removal - the clean-up that deletes them may not have run yet, and until
        // then they must never reach a backup.
        "telegram.",
        // ntfy alerts (25 Oct): the topic (anyone who knows it can post), the access token and the server are this phone's alone -
        // never in a backup, an upload or a log.
        "ntfy.",
        // Jarvis: the owner's voice print never leaves the phone; its trades' safety (paper first - the "AI trades go live"
        // switch -, loss limit, risk) and the autopilot are this phone's alone, like the other limits. Its learning (the
        // study, records) is carried.
        "jarvis.voiceprint", "jarvis.trades.", "jarvis.autopilot",
        // Autopilot learning's level (11 Oct): what the agents may change by themselves - a safety setting, never from a file.
        "jarvis.autolearn",
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
        "settings.",
        // The market recorder (06 Oct): its days are this phone's files (never in a backup), so its switch and gap counts stay too.
        "recorder.",
        // The forward-test watch's last told verdicts (jarvis.forward.told.): read from Solo's record, which never leaves
        // the phone - restored over a fresh record they would silence its milestones (07 Oct).
        ForwardWatch.KEY_PREFIX,
        // The weekly "what's working" note's day and newest counted exit (jarvis.liqinsight.): read from the arms' book,
        // which never leaves the phone - restored over a fresh book they would hold back or skip its notes (07 Oct).
        LiquidityInsight.KEY_PREFIX,
        // When the Ira page was last seen and the last "catch me up" (jarvis.catchup.): this phone's own moments - restored
        // from a file they would hide today's notes from the catch-up (07 Oct).
        CatchUp.KEY_PREFIX,
        // The trade manager's policy (and per-arm policies): ACT may close or trail live positions - a file must never
        // switch it on (review 2, 11 Oct).
        "trademanager.",
        // The regime book (its live approval) and the learning-freeze line: safety settings, never from a file (review 2).
        "regime.", "learn.freeze",
        // GitHub sync (13 Oct, TODO G1/G2): the token (PIN-sealed, and the device copy the evening upload uses), the backup
        // passphrase's keys and the upload record are this phone's alone - a backup never carries a token or a key.
        "github.",
        // 20 Nov 2026 (Boss's exception, the cheaper strike): its switch adds live trades (on takes the PIN, so a file must never switch
        // it on), its record is this phone's, and its one-time timeline mark must never come back from a backup.
        "cheaperStrike.",
        // 22 Nov 2026 (B6 review): the arms' live copies' second copy of each arm's Trade live / Shadow switch (ArmLive; a file must never
        // switch an arm's live copy on or take it out of shadow), and the order flow's modes (FlowGate: its CONFIRM skips live entries,
        // and a file must never put one back to SHADOW).
        "armlive.", "flow.modes",
        // 22 Nov 2026 (B6 review): the day the self-review last ran on this phone (SmartWorkers): a backup made after today's review would
        // skip this phone's own review today, and with it a park to paper.
        "brain.reviewedOn",
        // 22 Nov 2026 (B6 review): the change timeline's one-time marks ("switched on by the update", logged once per phone): a backup's
        // would hold back this phone's own first entry, and the timeline itself never leaves the phone.
        TIMELINE_ONCE)

    /** 22 Nov 2026 (B6 review): the prefix of the change timeline's one-time marks (one key per "switched on by the update" entry). */
    const val TIMELINE_ONCE = "timeline.once."

    /** May the setting [key] go into a backup, or be taken from one? */
    fun carried(key: String): Boolean = PRIVATE.none { key.startsWith(it) }

    // ---- what a restore merges with this phone's own (22 Nov 2026, B6 review) ----------------------------------------------------

    /**
     * The guard's live drawdown peak after a restore: the higher of this phone's ([here]) and the backup's ([theirs]) - a restore
     * never lowers it (a lower peak measures a smaller drawdown, so the kill switch would come later). Null: neither is a figure.
     */
    fun higherPeak(here: Double?, theirs: Double?): Double? =
        listOfNotNull(here?.takeIf { it.isFinite() && it > 0 }, theirs?.takeIf { it.isFinite() && it > 0 }).maxOrNull()

    /** A comma-separated set (the self-review's parks or pauses) after a restore: this phone's and the backup's together, never fewer. */
    fun union(here: String?, theirs: String?): String =
        listOf(here, theirs).flatMap { it.orEmpty().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().joinToString(",")

    /** The brain's entry pause ([MarketBrain.Policy]) after a restore: for each cause the stricter of the two (OFF < SHADOW < ON). */
    fun stricterPause(here: String?, theirs: String?): String {
        val a = MarketBrain.decode(here); val b = MarketBrain.decode(theirs)
        return MarketBrain.encode(MarketBrain.Policy(MarketBrain.Cause.entries.associateWith { c -> maxOf(a.mode(c), b.mode(c)) }))
    }

    /**
     * The findings' reactions ([Findings.Policy]) after a restore: every mode the stricter of the two (OFF < SHADOW < ACT), globally and
     * for each strategy either names, so no strategy's reaction is milder than it was on this phone or in the backup.
     */
    fun stricterReactions(here: String?, theirs: String?): String {
        val a = Findings.decode(here); val b = Findings.decode(theirs)
        val global = Findings.Reaction.entries.filter { it in a.global || it in b.global }
            .associateWith { r -> maxOf(a.global[r] ?: r.default, b.global[r] ?: r.default) }
        val per = (a.perStrategy.keys + b.perStrategy.keys).associateWith { k ->
            Findings.Reaction.entries.filter { r -> a.perStrategy[k]?.containsKey(r) == true || b.perStrategy[k]?.containsKey(r) == true }
                .associateWith { r -> maxOf(a.mode(k, r), b.mode(k, r)) }
        }
        return Findings.encode(Findings.Policy(global, per))
    }

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
