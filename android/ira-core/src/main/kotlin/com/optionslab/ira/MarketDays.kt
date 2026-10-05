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
        /** "How many trading days are left this month?": the month's sessions on the exchange calendar (routing round 11). */
        object Month : Asked()
    }

    private val NEXT = Regex("(?i)\\b(next|upcoming|agla|agli)\\s+(market\\s+|trading\\s+|stock market\\s+)?(holiday|holidays|chutti|chhutti|chhuti|chuti)\\b|\\bwhen is the next (market )?holiday\\b|\\bholiday list\\b" +
        // "Holiday kab hai" (routing round 11: it got the whole account status): the next one.
        "|^\\W*(market |trading |stock market )?(ki |ka )?(holiday|holidays|chutti|chhutti|chhuti|chuti) kab (hai|h|he|hain|hogi|hoga|padegi|padega|aayegi|aaegi)( boss| jarvis)?\\W*$")
    /** "How many trading days left this month", "is mahine kitne trading din bache hain" (routing round 11: it got Boss's history). */
    private val MONTH = Regex("(?i)\\bhow many (trading |market )?(days|sessions)( are| do we have| are there)?( (left|remaining|to go))?( (in )?(this|the) month| this month)\\b(?! (did|were|was))" +
        "|\\b(trading|market) (days|sessions) (left|remaining) (in )?(this|the) month\\b" +
        "|\\b(is|iss) (mahine|mahina|month) (mein |me |main )?(kitne|kitna) (trading |market )?(din|days|sessions)\\b")
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
        if (MONTH.containsMatchIn(text) && !PAST.containsMatchIn(text) && !rx("(?i)\\b(i|my|me|we|our|traded|trade|trades|profit|loss|green|red)\\b").containsMatchIn(text)) return Asked.Month
        if (!DAY.containsMatchIn(text) || PAST.containsMatchIn(text)) return null
        val w = WHEN.find(text)?.value?.lowercase() ?: return null      // no day named: the usual status answer
        val nextWeek = rx("(?i)\\bnext\\s+$w\\b").containsMatchIn(text)
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
        rx("(?i)\\b(next|upcoming|this week'?s?|when is( the)?|is (today|tomorrow|monday|tuesday|wednesday|thursday|friday)( an?)?|today'?s?|kab hai|kab|aaj|kal)\\b.{0,20}\\bexpiry\\b|\\b(what|which) day is( the)?( next| this week'?s?)? expiry\\b|\\b(time|days?) (left|remaining) (for|to|till|until|in) (the )?(next )?expiry\\b|\\bhow (long|much time|many days) (to|till|until|for|is left for) (the )?(next )?expiry\\b|\\bexpiry\\b.{0,12}\\b(kab|when|today|tomorrow|aaj|kal|date|day|kis din|kis date|kaun se din|konse din)\\b|\\bexpiry (ka|ki) (din|date|tareekh|tarikh) (kya|kab|kaunsa|konsa|kaun sa|kon sa)\\b|^\\W*(nifty |banknifty |bank nifty |finnifty )?expiry\\W*$")
            .containsMatchIn(text) && !rx("(?i)\\b(my|positions?|square|close|buy|sell)\\b").containsMatchIn(text)

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
            Asked.Month -> {
                // A weekday without a holiday, or a session the exchange holds on a weekend.
                fun trading(d: LocalDate) = special(d) || d.dayOfWeek.value <= 5 && holiday(d) == null
                val first = today.withDayOfMonth(1)
                val all = (0 until today.lengthOfMonth()).map { first.plusDays(it.toLong()) }.filter { trading(it) }
                val left = all.count { !it.isBefore(today) }
                val name = today.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                val from = when {
                    left == 0 -> "none left from today"
                    trading(today) -> "$left of them from today on, today included"
                    else -> "$left of them still to come"
                }
                val shut = (0 until today.lengthOfMonth()).map { first.plusDays(it.toLong()) }
                    .filter { !it.isBefore(today) && it.dayOfWeek.value <= 5 && !special(it) }
                    .mapNotNull { d -> holiday(d)?.let { "${d.format(DATE)} ($it)" } }
                "Boss, $name has ${all.size} trading day${if (all.size == 1) "" else "s"} on the exchange calendar, $from." +
                    (if (shut.isEmpty()) "" else " Market holidays left this month: ${shut.joinToString(", ")}.")
            }
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
