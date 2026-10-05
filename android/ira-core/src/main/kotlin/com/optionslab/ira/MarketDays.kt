package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Is tomorrow a holiday?", "is the market open on Friday?", "next holiday", "kal market khulega?": which day is asked,
 * answered from the exchange calendar in one line (not the whole account status). Pure.
 */
object MarketDays {
    sealed class Asked {
        data class Day(val date: LocalDate) : Asked()
        object Next : Asked()
    }

    private val NEXT = Regex("(?i)\\b(next|upcoming|agla|agli)\\s+(market\\s+|trading\\s+|stock market\\s+)?(holiday|holidays|chutti|chhutti|chhuti|chuti)\\b|\\bwhen is the next (market )?holiday\\b|\\bholiday list\\b")
    private val DAY = Regex("(?i)\\b(holiday|chutti|chhutti|chhuti|chuti|trading day|market (open|closed|shut|band|khulega|khula)|market( \\w+)? (kab )?(khulega|khulta)|open for trading|is (the )?market (open|closed)|exchange (open|closed))\\b")
    /** Asked about the past ("was it open on Friday", "kal band tha"): not for this answer. */
    private val PAST = Regex("(?i)\\b(was|were|did|yesterday|tha|thi|last)\\b")
    private val WHEN = Regex("(?i)\\b(today|aaj|tomorrow|tmrw|kal|parso|monday|tuesday|wednesday|thursday|friday|saturday|sunday|somvar|mangalvar|budhvar|guruvar|shukravar|shanivar|ravivar)\\b")

    private val HINDI = mapOf("somvar" to DayOfWeek.MONDAY, "mangalvar" to DayOfWeek.TUESDAY, "budhvar" to DayOfWeek.WEDNESDAY,
        "guruvar" to DayOfWeek.THURSDAY, "shukravar" to DayOfWeek.FRIDAY, "shanivar" to DayOfWeek.SATURDAY, "ravivar" to DayOfWeek.SUNDAY)

    /** Does [text] ask about a day other than today ("when does the market open tomorrow")? The calendar answers it. */
    fun namesAnotherDay(text: String): Boolean {
        if (NEXT.containsMatchIn(text)) return true
        if (!DAY.containsMatchIn(text) || PAST.containsMatchIn(text)) return false
        val w = WHEN.find(text)?.value?.lowercase() ?: return false
        return w != "today" && w != "aaj"
    }

    fun asked(text: String, today: LocalDate): Asked? {
        if (NEXT.containsMatchIn(text)) return Asked.Next
        if (!DAY.containsMatchIn(text) || PAST.containsMatchIn(text)) return null
        val w = WHEN.find(text)?.value?.lowercase() ?: return null      // no day named: the usual status answer
        val nextWeek = Regex("(?i)\\bnext\\s+$w\\b").containsMatchIn(text)
        val d = when (w) {
            "today", "aaj" -> today
            "tomorrow", "tmrw", "kal" -> today.plusDays(1)
            "parso" -> today.plusDays(2)
            else -> {
                val dow = HINDI[w] ?: DayOfWeek.valueOf(w.uppercase()); var x = today; while (x.dayOfWeek != dow) x = x.plusDays(1)
                // "Next Friday" asked on a Friday, or "next Monday" any day: the one after the coming one only when the
                // coming one is today.
                if (nextWeek && x == today) x.plusDays(7) else x
            }
        }
        return Asked.Day(d)
    }

    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "When is the next expiry", "is today expiry", "expiry kab hai", "BankNifty expiry": which index (null: all three). */
    fun expiryAsked(text: String): Boolean =
        Regex("(?i)\\b(next|upcoming|this week'?s?|when is( the)?|is (today|tomorrow|monday|tuesday|wednesday|thursday|friday)( an?)?|today'?s?|kab hai|kab|aaj|kal)\\b.{0,20}\\bexpiry\\b|\\b(what|which) day is( the)?( next| this week'?s?)? expiry\\b|\\b(time|days?) (left|remaining) (for|to|till|until|in) (the )?(next )?expiry\\b|\\bhow (long|much time|many days) (to|till|until|for|is left for) (the )?(next )?expiry\\b|\\bexpiry\\b.{0,12}\\b(kab|when|today|tomorrow|aaj|kal|date|day)\\b|^\\W*(nifty |banknifty |bank nifty |finnifty )?expiry\\W*$")
            .containsMatchIn(text) && !Regex("(?i)\\b(my|positions?|square|close|buy|sell)\\b").containsMatchIn(text)

    /** One line per index: its next expiry, "today" or "tomorrow" said plainly. */
    fun expirySay(today: LocalDate, next: List<Pair<Market, LocalDate?>>): String {
        val parts = next.mapNotNull { (m, d) -> d?.let { m.label + ": " + when (it) { today -> "today (${it.format(DATE)})"; today.plusDays(1) -> "tomorrow (${it.format(DATE)})"; else -> it.format(DATE) } } }
        return if (parts.isEmpty()) "I have no expiry dates loaded yet, Boss (the contracts load in the morning)." else "Next expiry, Boss - " + parts.joinToString("; ") + "."
    }

    /** [holiday]: the exchange holiday's name on that day, or null; [next]: the next listed holiday after [today]. */
    fun say(a: Asked, today: LocalDate, holiday: (LocalDate) -> String?, next: Pair<LocalDate, String>?,
            /** A session the exchange holds on a weekend (budget day), from the app's own calendar. */
            special: (LocalDate) -> Boolean = { false }): String {
        val nextLine = next?.let { (d, n) -> "Next market holiday: ${d.format(DATE)} ($n)." } ?: "I have no market holiday listed ahead."
        return when (a) {
            Asked.Next -> "Boss, $nextLine"
            is Asked.Day -> {
                val day = when (a.date) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> a.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } } +
                    " (" + a.date.format(DATE) + ")"
                val what = when {
                    special(a.date) -> "has a special trading session (a weekend session on the exchange calendar)"
                    a.date.dayOfWeek == DayOfWeek.SATURDAY || a.date.dayOfWeek == DayOfWeek.SUNDAY -> "is a weekend: the market is closed"
                    else -> holiday(a.date)?.let { "is a market holiday ($it): the market is closed" } ?: "is a trading day: the market opens at 9:15"
                }
                "Boss, $day $what. $nextLine"
            }
        }
    }
}
