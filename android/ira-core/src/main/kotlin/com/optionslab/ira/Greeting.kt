package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale

/**
 * A greeting answered like a person would (the owner's report, 2026-10-03: "good morning" got a canned line): the
 * greeting for the time of day, whether the market trades today, where Nifty and BankNifty stand, and an offer. Pure.
 */
object Greeting {
    fun timeWord(now: LocalDateTime): String = when (now.hour) {
        in 4..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    /**
     * [closedReason]: why there is no session today (a weekend, a holiday), or null on a trading day. [lead]: the index Boss
     * asks about by name, named first ([LeadIndex]; only the order changes), or null for Nifty first as always.
     */
    fun say(now: LocalDateTime, snaps: Map<Market, Snapshot>, closedReason: String?, lead: Market? = null): String {
        val m = now.hour * 60 + now.minute
        val open = closedReason == null && m in (9 * 60 + 15) until (15 * 60 + 30)
        val day = now.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val state = when {
            closedReason != null -> "It's $day and the market is closed today ($closedReason)."
            m < 9 * 60 + 15 -> "The market opens at 9:15."
            open -> "The market is open."
            else -> "The market has closed for the day."
        }
        fun line(mk: Market): String? = snaps[mk]?.let { s ->
            val ch = s.changePct?.let { " (%+.2f%%)".format(Locale.ENGLISH, it) } ?: ""
            if (open) "${mk.label} is at ${mk.price(s.price)}$ch" else "${mk.label} last closed at ${mk.price(s.price)}$ch"
        }
        val levels = LeadIndex.order(LeadIndex.INDICES, lead).mapNotNull { line(it) }
        val where = if (levels.isEmpty()) "" else " " + levels.joinToString("; ") + "."
        val offer = when {
            closedReason != null -> " Want last week's review, or to practise on a past day?"
            m < 9 * 60 + 15 -> " Want the morning brief?"
            open -> " Ask me how the market is, or what I'm watching."
            else -> " Want today's wrap-up?"
        }
        return "${timeWord(now)}, Boss. $state$where$offer"
    }
}
