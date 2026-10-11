package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The event-day calendar (TODO S11, 11 Oct): the Indian sessions whose results say more about the event than about a
 * strategy - RBI policy days, the Union Budget, election results, the big index rebalances, and an index expiry moved by a
 * holiday. It extends the calendar Jarvis already warns from ([Events]: the FOMC nights, the Budget, the expiries, Boss's own
 * "add event ..."); those warnings are unchanged.
 *
 * Bundled ([BUNDLED]) are the dates for 2026-2027 with their source; a date not yet published officially is marked
 * [Entry.tentative] (shown so in Bot settings - Boss corrects or removes it there, [Calendar.withOwner]). The rebalances and
 * the expiry shifts are worked out by rule ([rules]) from the holiday list the app keeps. Pure: the holiday check is given.
 */
object EventCalendar {
    enum class Kind(val words: String) {
        RBI("RBI policy"), BUDGET("Union Budget"), ELECTION("election results"), REBALANCE("index rebalance"),
        EXPIRY_SHIFT("expiry moved for a holiday"), OWNER("added by you"),
    }

    data class Entry(val day: LocalDate, val kind: Kind, val name: String, val source: String, val tentative: Boolean = false)

    private fun e(d: String, k: Kind, n: String, src: String, tentative: Boolean = false) = Entry(LocalDate.parse(d), k, n, src, tentative)

    private const val RBI_SRC = "RBI MPC schedule (rbi.org.in press release)"
    private const val RBI_TENT = "RBI MPC's usual bi-monthly schedule; confirm on rbi.org.in"

    /**
     * The bundled dates. RBI: the policy announcement (the MPC's last day, 10:00). Budget: 1 February each year (2026's was
     * presented on Sunday 1 Feb, a special trading session). Elections: none bundled - their dates are announced by the
     * Election Commission (eci.gov.in) only weeks ahead; Boss adds them in Bot settings.
     */
    val BUNDLED: List<Entry> = listOf(
        e("2026-02-06", Kind.RBI, "RBI policy", RBI_SRC),
        e("2026-04-08", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2026-06-05", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2026-08-06", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2026-10-01", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2026-12-04", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-02-05", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-04-07", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-06-04", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-08-06", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-10-01", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2027-12-03", Kind.RBI, "RBI policy", RBI_TENT, tentative = true),
        e("2026-02-01", Kind.BUDGET, "Union Budget", "Union Budget 2026-27, presented 1 Feb 2026 (indiabudget.gov.in)"),
        e("2027-02-01", Kind.BUDGET, "Union Budget", "1 February by convention since 2017 (indiabudget.gov.in)", tentative = true),
    )

    /**
     * The rule-made days of [from]..[to]: the index rebalances (NSE Indices' semi-annual review takes effect after the last
     * trading day of March and September; MSCI's quarterly reviews after the last trading day of February, May, August and
     * November - the index funds trade at those closes), and an index's weekly expiry moved to the trading day before when its
     * usual day ([expiryDays]: index -> weekday) is a holiday ([holiday]; weekends are never trading days).
     */
    fun rules(from: LocalDate, to: LocalDate, holiday: (LocalDate) -> Boolean,
              expiryDays: Map<String, DayOfWeek> = EXPIRY_DAYS): List<Entry> {
        fun trading(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !holiday(d)
        fun lastTrading(ym: YearMonth): LocalDate {
            var d = ym.atEndOfMonth()
            var n = 0
            while (!trading(d) && n++ < 31) d = d.minusDays(1)
            return d
        }
        val out = ArrayList<Entry>()
        var ym = YearMonth.from(from)
        while (!ym.atDay(1).isAfter(to)) {
            val m = ym.monthValue
            if (m == 3 || m == 9) out += Entry(lastTrading(ym), Kind.REBALANCE, "NSE index rebalance", "NSE Indices semi-annual review (rule: last trading day of Mar and Sep)")
            if (m == 2 || m == 5 || m == 8 || m == 11) out += Entry(lastTrading(ym), Kind.REBALANCE, "MSCI index rebalance", "MSCI quarterly review (rule: last trading day of Feb, May, Aug and Nov)")
            ym = ym.plusMonths(1)
        }
        var d = from
        while (!d.isAfter(to)) {
            for ((idx, dow) in expiryDays) if (d.dayOfWeek == dow && holiday(d)) {
                var s = d.minusDays(1)
                var n = 0
                while (!trading(s) && n++ < 31) s = s.minusDays(1)
                out += Entry(s, Kind.EXPIRY_SHIFT, "$idx expiry moved to this day (${dow.name.lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} is a holiday)",
                    "rule: the exchange moves a holiday's expiry to the trading day before")
            }
            d = d.plusDays(1)
        }
        return out.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
    }

    /** The weekly expiries the app trades (NSE's Tuesday, BSE's Thursday, from 1 Sep 2025). */
    val EXPIRY_DAYS: Map<String, DayOfWeek> = linkedMapOf("NIFTY" to DayOfWeek.TUESDAY, "SENSEX" to DayOfWeek.THURSDAY)

    /**
     * A calendar to ask: the bundled dates ([removed] taken out by Boss), the rule-made ones over [from]..[to], and Boss's own
     * ([owner]). [isEventDay] is what learning asks ([LearningFilter]).
     */
    class Calendar(val entries: List<Entry>) {
        private val byDay: Map<LocalDate, List<Entry>> = entries.groupBy { it.day }
        fun isEventDay(day: LocalDate): Boolean = byDay.containsKey(day)
        fun on(day: LocalDate): List<Entry> = byDay[day].orEmpty()
        fun between(from: LocalDate, to: LocalDate): List<Entry> =
            entries.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }.sortedWith(compareBy({ it.day }, { it.name }))
        /** "RBI policy, NSE index rebalance". */
        fun words(day: LocalDate): String? = on(day).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name.substringBefore(" (") }
    }

    /** The calendar from [from] to [to]; [owner]: Boss's own entries; [removed]: the bundled or rule days Boss took out. */
    fun calendar(from: LocalDate, to: LocalDate, holiday: (LocalDate) -> Boolean, owner: List<Entry> = emptyList(),
                 removed: Set<LocalDate> = emptySet()): Calendar {
        val all = (BUNDLED.filter { !it.day.isBefore(from) && !it.day.isAfter(to) } + rules(from, to, holiday)).filter { it.day !in removed } +
            owner.map { it.copy(kind = Kind.OWNER) }
        return Calendar(all.distinctBy { Triple(it.day, it.kind, it.name) })
    }

    /** Boss's events as calendar entries ([Events.Event] as Jarvis keeps them, "add event RBI policy on 5 Dec"). */
    fun fromEvents(events: List<Events.Event>): List<Entry> =
        events.filter { it.owner }.map { Entry(it.day, Kind.OWNER, it.name, "added by you") }

    /** The Home / Jarvis note on an event day. */
    const val NOTE = "Event day: learning ignores today's results"

    fun note(cal: Calendar, today: LocalDate): String? = cal.words(today)?.let { "$NOTE ($it)." }

    /** One line for Bot settings: "Thu 1 Oct 2026 · RBI policy (tentative)". */
    fun line(e: Entry): String =
        "${e.day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${e.day.dayOfMonth} " +
            "${e.day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${e.day.year} · ${e.name}" +
            (if (e.tentative) " (tentative)" else "")
}

/**
 * Whether a day's results count as evidence for learning (TODO S11): AgentLessons, Autopilot and the strategy lab call
 * [include] for each day; an event day ([EventCalendar]) is kept out of the evidence by default and kept in its own
 * "event-day" bucket ([split]) instead, so nothing is thrown away. The calendar is set by the app ([use]) from the bundled
 * dates, the rules and Boss's edits; until then the bundled dates and the rules alone (no holidays known). Thread-safe.
 */
object LearningFilter {
    enum class Bucket(val words: String) { EVIDENCE("evidence"), EVENT_DAY("event-day") }

    data class Split<T>(val evidence: List<T>, val eventDays: List<T>)

    @Volatile private var cal: EventCalendar.Calendar = default()
    /** False: event days count as evidence too (a switch for the lab's comparisons; default true). */
    @Volatile var excludeEventDays: Boolean = true

    private fun default(): EventCalendar.Calendar =
        EventCalendar.calendar(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), { false })

    /** The calendar learning reads from now on. */
    fun use(c: EventCalendar.Calendar) { cal = c }
    val calendar: EventCalendar.Calendar get() = cal

    /** Whether [day]'s results are evidence (false on an event day while [excludeEventDays]). */
    fun include(day: LocalDate): Boolean = !excludeEventDays || !cal.isEventDay(day)

    fun bucket(day: LocalDate): Bucket = if (cal.isEventDay(day)) Bucket.EVENT_DAY else Bucket.EVIDENCE

    /** [items] split by their day ([dayOf]): the evidence, and the event days kept apart. */
    fun <T> split(items: List<T>, dayOf: (T) -> LocalDate): Split<T> {
        val ev = ArrayList<T>(items.size); val evt = ArrayList<T>()
        for (x in items) if (include(dayOf(x))) ev += x else evt += x
        return Split(ev, evt)
    }

    /** TEST ONLY: back to the bundled calendar. */
    fun resetForTest() { cal = default(); excludeEventDays = true }
}
