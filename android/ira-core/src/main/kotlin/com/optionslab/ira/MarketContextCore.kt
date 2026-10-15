package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The pure parts of the app's market context (TODO I6, I11; review fixes of 13 Oct 2026): which session an event belongs
 * to, the event warning (today's, or the next session's from 15:30 and on closed days), the 09:00 lines, when an event day
 * counts as lived through in Shadow, and when the "days like today" brief is tried again. The app passes its exchange
 * calendar ([isTrading]) and its clock; nothing here reads a file, the network or the clock. Advisory only.
 */
object MarketContextCore {
    val OPEN: LocalTime = LocalTime.of(9, 15)
    /** Index / cash close: from here the warning is the next session's. */
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** The calendar when the app has none: Monday to Friday. */
    fun weekday(d: LocalDate): Boolean = d.dayOfWeek.value <= 5

    /** The first trading day after [after] (at most 15 days on: a long run of closed days is not expected). */
    fun nextSession(after: LocalDate, isTrading: (LocalDate) -> Boolean = ::weekday): LocalDate {
        var d = after.plusDays(1)
        var n = 0
        while (!isTrading(d) && n++ < 15) d = d.plusDays(1)
        return d
    }

    /** The last trading day before [before]. */
    fun previousSession(before: LocalDate, isTrading: (LocalDate) -> Boolean = ::weekday): LocalDate {
        var d = before.minusDays(1)
        var n = 0
        while (!isTrading(d) && n++ < 15) d = d.minusDays(1)
        return d
    }

    private fun short(d: LocalDate) = "${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} " +
        d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    /**
     * The counting events of session [day] ([EventShield.counts]): its own ([raw]: the names the calendar holds for a day),
     * those of the closed days since the session before (a Budget on a Sunday, a Fed night that falls on an Indian holiday:
     * the next session is the one that reacts), and a US CPI release ([cpi]: US dates, 08:30 New York, after the Indian
     * close) on the session before or on a closed day since. None on a closed day itself.
     */
    fun eventsOn(day: LocalDate, raw: (LocalDate) -> List<String>, isTrading: (LocalDate) -> Boolean = ::weekday,
                 cpi: List<LocalDate> = EventShield.US_CPI): List<String> {
        if (!isTrading(day)) return emptyList()
        val prev = previousSession(day, isTrading)
        val out = ArrayList(raw(day))
        var d = prev.plusDays(1)
        while (d.isBefore(day)) {
            for (n in raw(d).filter(EventShield::counts)) out +="$n (${short(d)}, market closed)"
            d = d.plusDays(1)
        }
        if (cpi.any { !it.isBefore(prev) && it.isBefore(day) }) out += EventShield.CPI_NAME
        return out.filter(EventShield::counts).distinct()
    }

    /**
     * The event warning said at [now]: today's on a trading day before 15:30 (that morning and through the day), else - and
     * when today has none - the next session's (the evening before, a weekend, a holiday). Null when nothing counts.
     */
    fun eventWarning(now: LocalDateTime, events: (LocalDate) -> List<String>, isTrading: (LocalDate) -> Boolean = ::weekday): String? {
        val d = now.toLocalDate()
        if (isTrading(d) && now.toLocalTime().isBefore(CLOSE)) EventShield.warning(events(d), d, d)?.let { return it }
        val next = nextSession(d, isTrading)
        return EventShield.warning(events(next), next, d)
    }

    const val DAYS_LINE = "Days like today: on Home and from Jarvis after 09:21, once the open is known (context, not a forecast)."

    /**
     * The 09:00 morning check's lines: the event warning, the expiry card's first line ([expiring]: the indices expiring
     * today, [expiryLines] the card's lines for the first), Boss's switch on an event day, and when "days like today" comes.
     * On a closed day only the warning (for the next session).
     */
    fun morningLines(now: LocalDateTime, events: (LocalDate) -> List<String>, isTrading: (LocalDate) -> Boolean, expiring: List<String>,
                     mode: EventShield.Mode, expiryLines: (String, LocalTime) -> List<String> = ExpiryCard::lines): List<String> {
        val out = ArrayList<String>()
        eventWarning(now, events, isTrading)?.let { out += it }
        val d = now.toLocalDate()
        if (!isTrading(d)) return out
        if (expiring.isNotEmpty()) out += ExpiryCard.title(expiring) + ": " + expiryLines(expiring.first(), now.toLocalTime()).first()
        if (mode != EventShield.Mode.OFF && events(d).isNotEmpty()) out += "Your switch \"pause paper arms on event days\" is ${mode.words}."
        out += DAYS_LINE
        return out
    }

    // ---- shadow first --------------------------------------------------------------------------------------------------

    /** Inside 09:15-15:30 of [day]. */
    fun inSession(t: LocalDateTime, day: LocalDate): Boolean =
        t.toLocalDate() == day && !t.toLocalTime().isBefore(OPEN) && t.toLocalTime().isBefore(CLOSE)

    /**
     * Does [day] count as an event day run in SHADOW ("shadow first" unlocks ON)? Only when it is a trading day with a counting
     * event ([events]) and the switch actually watched a session: a real shadow verdict was logged during market hours
     * ([verdictAt]), or Shadow was on without a break from before 09:15 ([shadowSince]: since when this process has had it on)
     * and it is now ([now]) 15:30 or later that same day with Shadow still on ([mode]).
     */
    fun shadowDayCounts(day: LocalDate, events: List<String>, isTrading: Boolean, mode: EventShield.Mode,
                        shadowSince: LocalDateTime?, verdictAt: LocalDateTime?, now: LocalDateTime): Boolean {
        if (!isTrading || events.none(EventShield::counts)) return false
        if (verdictAt != null && inSession(verdictAt, day)) return true
        return mode == EventShield.Mode.SHADOW && shadowSince != null && !shadowSince.isAfter(day.atTime(OPEN)) &&
            now.toLocalDate() == day && !now.toLocalTime().isBefore(CLOSE)
    }

    // ---- the days-like-today brief: tried from 09:21, backing off when it cannot be built -----------------------------------

    /** [day]: the day these tries are for; [done]: built today; [tries] so far; [nextAt]: not before then. */
    data class BriefTries(val day: LocalDate? = null, val done: Boolean = false, val tries: Int = 0, val nextAt: LocalDateTime? = null)

    const val BRIEF_MAX_TRIES = 6
    /** Minutes to wait after the n-th failed try: 5, 10, 20, 40, 80. */
    fun briefBackoffMin(failed: Int): Long = 5L shl (failed - 1).coerceIn(0, 4)

    /** Is a try of the brief due at [now]? Once built, never again that day; after [BRIEF_MAX_TRIES] failures, not that day. */
    fun briefDue(s: BriefTries, now: LocalDateTime, isTrading: Boolean): Boolean {
        if (!isTrading || now.toLocalTime().isBefore(DaysLikeToday.READY_AT)) return false
        if (s.day != now.toLocalDate()) return true                    // a new day: the first try
        return !s.done && s.tries < BRIEF_MAX_TRIES && (s.nextAt == null || !now.isBefore(s.nextAt))
    }

    /** The tries after one at [now] that built the brief ([ok]) or not; a new day starts afresh. */
    fun briefAfter(s: BriefTries, now: LocalDateTime, ok: Boolean): BriefTries {
        val base = if (s.day == now.toLocalDate()) s else BriefTries(now.toLocalDate())
        val n = base.tries + 1
        return if (ok) base.copy(done = true, tries = n, nextAt = null)
        else base.copy(tries = n, nextAt = now.plusMinutes(briefBackoffMin(n)))
    }

    /** Is a brief kept for [s] still today's (the card drops yesterday's at the day's rollover)? */
    fun briefShown(s: BriefTries, today: LocalDate): Boolean = s.day == today && s.done
}
