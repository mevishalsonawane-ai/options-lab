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

    private val NEXT = Regex("(?i)\\b(next|upcoming|agla|agli)\\s+(market\\s+|trading\\s+|stock market\\s+)?(holiday|holidays|chutti)\\b|\\bwhen is the next (market )?holiday\\b|\\bholiday list\\b")
    private val DAY = Regex("(?i)\\b(holiday|chutti|trading day|market (open|closed|shut|band|khulega|khula)|open for trading|is (the )?market (open|closed)|exchange (open|closed))\\b")
    /** Asked about the past ("was it open on Friday", "kal band tha"): not for this answer. */
    private val PAST = Regex("(?i)\\b(was|were|did|yesterday|tha|thi|last)\\b")
    private val WHEN = Regex("(?i)\\b(today|aaj|tomorrow|tmrw|kal|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

    fun asked(text: String, today: LocalDate): Asked? {
        if (NEXT.containsMatchIn(text)) return Asked.Next
        if (!DAY.containsMatchIn(text) || PAST.containsMatchIn(text)) return null
        val w = WHEN.find(text)?.value?.lowercase() ?: return null      // no day named: the usual status answer
        val nextWeek = Regex("(?i)\\bnext\\s+$w\\b").containsMatchIn(text)
        val d = when (w) {
            "today", "aaj" -> today
            "tomorrow", "tmrw", "kal" -> today.plusDays(1)
            else -> {
                val dow = DayOfWeek.valueOf(w.uppercase()); var x = today; while (x.dayOfWeek != dow) x = x.plusDays(1)
                // "Next Friday" asked on a Friday, or "next Monday" any day: the one after the coming one only when the
                // coming one is today.
                if (nextWeek && x == today) x.plusDays(7) else x
            }
        }
        return Asked.Day(d)
    }

    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

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
