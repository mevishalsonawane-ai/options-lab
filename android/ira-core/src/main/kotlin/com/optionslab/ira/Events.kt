package com.optionslab.ira

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Market events Jarvis warns about: the US Fed's 2026 decisions (announced after Indian hours, so the Indian session
 * that reacts is the next day), India's Union Budget (1 February), the index expiries the app knows, and anything
 * the owner adds ("add event RBI policy on 5 Dec"). Only dates known for certain are built in. Pure.
 */
object Events {
    data class Event(val day: LocalDate, val name: String, val owner: Boolean = false)

    /** FOMC 2026 decision days (US); the Indian session reacting is the day after. */
    private val FOMC_2026 = listOf("2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09")
        .map { LocalDate.parse(it) }

    fun builtIn(from: LocalDate, to: LocalDate): List<Event> {
        val out = ArrayList<Event>()
        FOMC_2026.forEach { d -> out += Event(d.plusDays(1), "US Fed decision overnight (FOMC)") }
        for (y in from.year..to.year) out += Event(LocalDate.of(y, 2, 1), "Union Budget")
        return out.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
    }

    /** Events from [from] to [to]: built in, the expiries ([expiries]: index -> dates) and the owner's. */
    fun between(from: LocalDate, to: LocalDate, expiries: Map<String, List<LocalDate>>, owner: List<Event>): List<Event> =
        (builtIn(from, to) + expiries.flatMap { (u, ds) -> ds.filter { !it.isBefore(from) && !it.isAfter(to) }.map { Event(it, "$u expiry") } } +
            owner.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }).sortedWith(compareBy({ it.day }, { it.name }))

    fun line(e: Event, today: LocalDate): String {
        val whenStr = when (e.day) { today -> "today"; today.plusDays(1) -> "tomorrow"
            else -> "${e.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${e.day.dayOfMonth} ${e.day.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" }
        return "Event $whenStr: ${e.name}${if (e.owner) " (added by you)" else ""}."
    }

    /** "5 dec", "december 5", "5/12", "2026-12-05", "tomorrow" -> the date (the next one from [today]), or null. */
    fun date(text: String, today: LocalDate): LocalDate? {
        val t = text.lowercase()
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(t)?.let { return runCatching { LocalDate.parse(it.value) }.getOrNull() }
        if (Regex("\\btomorrow\\b").containsMatchIn(t)) return today.plusDays(1)
        if (Regex("\\btoday\\b").containsMatchIn(t)) return today
        val months = Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().take(3) }
        val m1 = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b").find(t)
        val m2 = Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b").find(t)
        val m3 = Regex("\\b(\\d{1,2})/(\\d{1,2})\\b").find(t)
        val (d, mon) = when {
            m1 != null -> m1.groupValues[1].toInt() to months.getValue(m1.groupValues[2]).value
            m2 != null -> m2.groupValues[2].toInt() to months.getValue(m2.groupValues[1]).value
            m3 != null -> m3.groupValues[1].toInt() to m3.groupValues[2].toInt()
            else -> return null
        }
        val thisYear = runCatching { LocalDate.of(today.year, mon, d) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
    }
}
