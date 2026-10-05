package com.optionslab.app.ira

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything Jarvis does by itself, in one place (the owner's wish, 2026-10-03): each automatic behaviour with its
 * own switch and when it last acted, shown on the Jarvis health screen. Every watcher asks [on] first and calls
 * [acted] when it did something.
 */
internal object Automations {
    enum class Auto(val label: String, val what: String, val key: String, val byDefault: Boolean = true) {
        // Off until Boss switches it on: it moves the stop orders of his own positions, live ones too (review, 3 Oct).
        TRAIL("Trail my stops", "Your own bought options: stop to what you paid at +20%, then 15% under the best price.", "jarvis.autotrail", byDefault = false),
        RESCUE("Offer a stop", "A position of yours with no stop for 2 minutes: Jarvis offers one (asks first).", "jarvis.auto.rescue"),
        // Off until Boss switches it on with his fingerprint (4 Oct): it places a real stop order on Zerodha positions.
        GUARD("Guard my positions", "A bought option of yours with no stop for 2 minutes: Jarvis sets the stop itself, 15% under what you paid, and tells you - Zerodha ones too. It only ever adds a stop that closes; it never opens or adds.", "jarvis.auto.guard", byDefault = false),
        STALE("Trades going nowhere", "Open 45 minutes and within 5% of what you paid: Jarvis offers to close it (asks first).", "jarvis.auto.stale"),
        COOLOFF("Cool-off after losses", "Two of Jarvis's trades lose in a row: no suggestions for 30 minutes.", "jarvis.auto.cooloff"),
        OVERTRADE("Overtrading warning", "More than 3 of your buys in 30 minutes: a word to slow down.", "jarvis.auto.overtrade"),
        TARGET("Day target", "Your day's target reached: told to protect the gains.", "jarvis.auto.target"),
        POSNEWS("News on your positions", "A headline on an index you hold: good or bad for your side.", "jarvis.auto.posnews"),
        MIS("Intraday square-off heads-up", "15:10: open intraday (MIS) Zerodha positions named before Zerodha squares them off itself (words only).", "jarvis.auto.mis"),
        RELAY("Relay server watch", "08:30-15:30: the static-IP relay server not answering twice in a row is told at once, and when it is back.", "jarvis.auto.relay"),
        FEED("Live prices stopped", "No prices for 2 minutes in market hours: told at once.", "jarvis.auto.feed"),
        EXPIRY("Expiry heads-up", "14:55 on expiry day: what the 15:05 square-off will close.", "jarvis.auto.expiry"),
        GAP("Opening gap plan", "09:16: the gap and how the arms did on such days.", "jarvis.auto.gap"),
        PLAN("Plan my day (paper arms)", "09:00-10:00: a PAPER arm that lost on days like today is parked, and one Jarvis parked is armed again when the market suits it; told why. Never an arm on Zerodha, never one you left off.", "jarvis.auto.plan"),
        ORB("Opening range breaks", "Nifty or BankNifty leaving its first 15 minutes' range: told once per side a day.", "jarvis.auto.orb"),
        VIX("Fear spikes", "India VIX up 10% or more on the day: told once.", "jarvis.auto.vix"),
        MOMENTS("Market moments", "Nifty or BankNifty filling its opening gap, or going past the previous session's high or low: told once each a day.", "jarvis.auto.moments"),
        OI("Open interest walls", "The biggest call / put open interest moving to a new strike.", "jarvis.auto.oi"),
        MORNING_VOICE("Morning check aloud", "09:00: the morning check is spoken even with listening off (never when muted or in quiet hours).", "jarvis.auto.morningvoice"),
        USUAL("Your usual, unasked", "A market question you ask at the same hour most days (4+ times): said at that hour's start, once a day.", "jarvis.auto.usual"),
        SUMMARY("15:35 wrap-up", "The day's P&L, scorecard and tomorrow's events, spoken.", "jarvis.auto.summary"),
        BACKUP("Backup reminder", "No backup in 7 days: a reminder in the morning check.", "jarvis.auto.backup"),
        SELFHEAL("Self-healing voice", "No listening for 3 minutes: the microphone is restarted.", "jarvis.auto.selfheal"),
        ACT_PAPER("Act on my own ideas (paper)", "A news or pattern idea with 3/5 confidence or more: Jarvis takes it on the PAPER account by itself and tells you (never real money).", "jarvis.auto.actpaper"),
        SOLO_IDEAS("Solo's setups as ideas", "Solo switched off: when its setup appears, offered to you as a trade idea (you approve; on paper until proven).", "jarvis.auto.soloideas"),
        QUIET("Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "jarvis.quiet"),
    }

    /**
     * The switches Boss sees (4 Oct: "combine the AI settings that go together; drop those that stay on anyway"): each
     * behaviour belongs to one group with one switch; the safety helpers have none and are always on.
     */
    enum class Group(val label: String, val what: String, val key: String, val members: List<Auto>, val byDefault: Boolean = true,
                     /** Switched on only with the fingerprint (it touches real stop orders). */ val fingerprint: Boolean = false) {
        GUARD("Guard my positions", "Your own bought options: a stop set by itself when one has none for 2 minutes (15% under what you paid), then trailed up - to what you paid at +20%, then 15% under the best price. Zerodha positions too; it only adds or raises stops that close, never opens or adds.",
            "jarvis.group.guard", listOf(Auto.GUARD, Auto.TRAIL), byDefault = false, fingerprint = true),
        HELP("Offer help on my positions", "A position with no stop, or one going nowhere for 45 minutes: Jarvis offers a stop or a close (asks first).",
            "jarvis.group.help", listOf(Auto.RESCUE, Auto.STALE)),
        OWN("Act on his own, on paper", "Takes his own ideas of 3/5 or more on PAPER (raising the bar where he loses), plans the paper arms each morning, and offers Solo's setups when Solo is off.",
            "jarvis.group.own", listOf(Auto.ACT_PAPER, Auto.PLAN, Auto.SOLO_IDEAS)),
        MARKET("Market alerts", "Opening gap plan, opening range breaks, gaps filling, the previous day's high or low passed, fear (VIX) spikes, open interest walls moving, and news on indices you hold.",
            "jarvis.group.market", listOf(Auto.GAP, Auto.ORB, Auto.MOMENTS, Auto.VIX, Auto.OI, Auto.POSNEWS)),
        COACH("Coach me", "A word when you overtrade, your day's target reached, your usual question answered at its hour, the 09:00 check and the 15:35 wrap-up spoken.",
            "jarvis.group.coach", listOf(Auto.OVERTRADE, Auto.TARGET, Auto.USUAL, Auto.MORNING_VOICE, Auto.SUMMARY)),
        QUIET("Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "jarvis.group.quiet", listOf(Auto.QUIET)),
    }

    /** Always on, no switch: they only warn, cool off or heal (live prices stopped, expiry heads-up, cool-off, backup, voice). */
    val ALWAYS = setOf(Auto.MIS, Auto.RELAY, Auto.FEED, Auto.EXPIRY, Auto.COOLOFF, Auto.BACKUP, Auto.SELFHEAL)

    fun groupOf(a: Auto): Group? = Group.entries.firstOrNull { a in it.members }

    fun on(a: Auto): Boolean {
        if (a in ALWAYS) return true
        val g = groupOf(a) ?: return a.byDefault
        return on(g)
    }

    fun on(g: Group): Boolean = runCatching {
        val p = com.optionslab.app.security.SecurePrefs
        // Before the groups each behaviour had its own switch: a group starts as Boss left its members (the guard only
        // from its own fingerprint switch, never from the old trail switch, which did not ask for it).
        val was = if (g == Group.GUARD) p.getBoolean(Auto.GUARD.key, false)
            else g.members.any { p.getBoolean(it.key, it.byDefault) }
        p.getBoolean(g.key, was)
    }.getOrDefault(false)

    fun set(g: Group, v: Boolean) { runCatching { com.optionslab.app.security.SecurePrefs.put(g.key, v) } }

    /** A behaviour's switch is its group's (an always-on one has none). */
    fun set(a: Auto, v: Boolean) { groupOf(a)?.let { set(it, v) } }

    /** When the group last acted and what it did (the latest of its members). */
    fun last(g: Group): Pair<LocalDateTime, String>? = g.members.mapNotNull { last(it) }.maxByOrNull { it.first }

    /** [a] just did something: when, and what (one line). */
    fun acted(a: Auto, what: String) {
        runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.auto.last.${a.name}",
            LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0).toString() + "|" + com.optionslab.ira.Secrets.redact(what).take(160)) }
    }

    /** When [a] last acted and what it did, or null. */
    fun last(a: Auto): Pair<LocalDateTime, String>? = runCatching {
        val v = com.optionslab.app.security.SecurePrefs.getString("jarvis.auto.last.${a.name}") ?: return@runCatching null
        LocalDateTime.parse(v.substringBefore('|')) to v.substringAfter('|')
    }.getOrNull()
}
