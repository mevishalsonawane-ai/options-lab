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
        STALE("Trades going nowhere", "Open 45 minutes and within 5% of what you paid: Jarvis offers to close it (asks first).", "jarvis.auto.stale"),
        COOLOFF("Cool-off after losses", "Two of Jarvis's trades lose in a row: no suggestions for 30 minutes.", "jarvis.auto.cooloff"),
        OVERTRADE("Overtrading warning", "More than 3 of your buys in 30 minutes: a word to slow down.", "jarvis.auto.overtrade"),
        TARGET("Day target", "Your day's target reached: told to protect the gains.", "jarvis.auto.target"),
        POSNEWS("News on your positions", "A headline on an index you hold: good or bad for your side.", "jarvis.auto.posnews"),
        FEED("Live prices stopped", "No prices for 2 minutes in market hours: told at once.", "jarvis.auto.feed"),
        EXPIRY("Expiry heads-up", "14:55 on expiry day: what the 15:05 square-off will close.", "jarvis.auto.expiry"),
        GAP("Opening gap plan", "09:16: the gap and how the arms did on such days.", "jarvis.auto.gap"),
        ORB("Opening range breaks", "Nifty or BankNifty leaving its first 15 minutes' range: told once per side a day.", "jarvis.auto.orb"),
        VIX("Fear spikes", "India VIX up 10% or more on the day: told once.", "jarvis.auto.vix"),
        OI("Open interest walls", "The biggest call / put open interest moving to a new strike.", "jarvis.auto.oi"),
        SUMMARY("15:35 wrap-up", "The day's P&L, scorecard and tomorrow's events, spoken.", "jarvis.auto.summary"),
        BACKUP("Backup reminder", "No backup in 7 days: a reminder in the morning check.", "jarvis.auto.backup"),
        SELFHEAL("Self-healing voice", "No listening for 3 minutes: the microphone is restarted.", "jarvis.auto.selfheal"),
        SOLO_IDEAS("Solo's setups as ideas", "Solo switched off: when its setup appears, offered to you as a trade idea (you approve; on paper until proven).", "jarvis.auto.soloideas"),
        QUIET("Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "jarvis.quiet"),
    }

    fun on(a: Auto): Boolean = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(a.key, a.byDefault) }.getOrDefault(false)

    fun set(a: Auto, v: Boolean) { runCatching { com.optionslab.app.security.SecurePrefs.put(a.key, v) } }

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
