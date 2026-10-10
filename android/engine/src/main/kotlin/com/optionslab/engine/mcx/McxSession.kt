package com.optionslab.engine.mcx

import com.optionslab.engine.strategy.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * MCX's trading hours for energy and metals (IST), as a function of the date (research/MCX_GUIDE.md 1c):
 *
 *  - Monday to Friday 09:00 to 23:30 while the US is on summer time, 09:00 to 23:55 while it is on winter time. MCX moves
 *    its close on the first trading day after the US clocks change: US summer time ends on Sunday 1 Nov 2026 (23:55 from
 *    Monday 2 Nov) and starts again on the second Sunday of March (23:30 from 9 Mar 2026, 8 Mar 2027). [close] reads the
 *    US rule from the time-zone data, so every later year follows by itself;
 *  - MCX's own holidays differ from NSE's: some days are shut all day, many only in the morning (the evening session from
 *    17:00 still trades: Dussehra, Diwali Balipratipada, Guru Nanak Jayanti in 2026), and a Muhurat session can fall on a
 *    Sunday. [Calendar] holds them: the list fetched (Upstox's public market-holiday list carries MCX's open hours on each
 *    listed day, [parseUpstox]) where it covers the year, else [BUILT_IN];
 *  - MIS is squared off 10 minutes before the close ([misCut]: 23:20, or 23:45 in US winter).
 *
 * Pure: no clock, no network.
 */
object McxSession {
    val OPEN: LocalTime = LocalTime.of(9, 0)
    val SUMMER_CLOSE: LocalTime = LocalTime.of(23, 30)
    val WINTER_CLOSE: LocalTime = LocalTime.of(23, 55)
    /** Where a morning-closed holiday's evening session starts. */
    val EVENING: LocalTime = LocalTime.of(17, 0)
    /** MCX squares intraday (MIS) positions off this long before the close. */
    const val MIS_BEFORE_CLOSE_MIN = 10L

    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    /** One open window of a day (IST). */
    data class Window(val open: LocalTime, val close: LocalTime) {
        fun contains(t: LocalTime): Boolean = !t.isBefore(open) && t.isBefore(close)
    }

    /** True when the US is on winter (standard) time on [date]: MCX then closes at 23:55. */
    fun usWinter(date: LocalDate): Boolean =
        !NEW_YORK.rules.isDaylightSavings(date.atTime(12, 0).atZone(NEW_YORK).toInstant())

    /** The ordinary close on [date]: 23:30 in US summer time, 23:55 in US winter time. */
    fun close(date: LocalDate): LocalTime = if (usWinter(date)) WINTER_CLOSE else SUMMER_CLOSE

    /** The MIS square-off on [date]: 10 minutes before [close] (23:20 / 23:45). */
    fun misCut(date: LocalDate): LocalTime = close(date).minusMinutes(MIS_BEFORE_CLOSE_MIN)

    /**
     * MCX's 2026 calendar for the days that are not an ordinary weekday session (Upstox's market-holiday list, read 8 Oct
     * 2026; MCX circular via HDFC Securities in research/MCX_GUIDE.md): a window is that day's only session, null is shut
     * all day. Weekdays not listed trade 09:00 to [close]; weekends not listed are shut.
     */
    val BUILT_IN: Map<LocalDate, Window?> = linkedMapOf(
        LocalDate.of(2026, 1, 15) to Window(EVENING, WINTER_CLOSE),            // Municipal Corporation election: evening only
        LocalDate.of(2026, 1, 26) to null,                                     // Republic Day
        LocalDate.of(2026, 2, 1) to Window(OPEN, EVENING),                     // Sunday: Budget day session
        LocalDate.of(2026, 3, 3) to Window(EVENING, WINTER_CLOSE),             // Holi
        LocalDate.of(2026, 3, 26) to Window(EVENING, SUMMER_CLOSE),            // Ram Navami
        LocalDate.of(2026, 3, 31) to Window(EVENING, SUMMER_CLOSE),            // Mahavir Jayanti
        LocalDate.of(2026, 4, 3) to null,                                      // Good Friday
        LocalDate.of(2026, 4, 14) to Window(EVENING, SUMMER_CLOSE),            // Ambedkar Jayanti
        LocalDate.of(2026, 5, 1) to Window(EVENING, SUMMER_CLOSE),             // Maharashtra Day
        LocalDate.of(2026, 5, 28) to Window(EVENING, SUMMER_CLOSE),            // Bakri Id
        LocalDate.of(2026, 6, 26) to Window(EVENING, SUMMER_CLOSE),            // Muharram
        LocalDate.of(2026, 9, 14) to Window(EVENING, SUMMER_CLOSE),            // Ganesh Chaturthi
        LocalDate.of(2026, 10, 2) to null,                                     // Gandhi Jayanti
        LocalDate.of(2026, 10, 20) to Window(EVENING, SUMMER_CLOSE),           // Dussehra
        LocalDate.of(2026, 11, 8) to Window(LocalTime.of(18, 0), LocalTime.of(19, 0)),   // Sunday: Diwali Muhurat
        LocalDate.of(2026, 11, 10) to Window(EVENING, WINTER_CLOSE),           // Diwali Balipratipada
        LocalDate.of(2026, 11, 24) to Window(EVENING, WINTER_CLOSE),           // Guru Nanak Jayanti
        LocalDate.of(2026, 12, 25) to null,                                    // Christmas
    )

    /**
     * The days that are not ordinary sessions: [fetched] (a window, or null for shut all day) wherever it covers the
     * day's year, else [BUILT_IN]. [added]: days the owner marked shut by hand.
     */
    class Calendar(val fetched: Map<LocalDate, Window?> = emptyMap(), val added: Set<LocalDate> = emptySet()) {
        private val fetchedYears = fetched.keys.map { it.year }.toSet()

        /** The day's listed exception: (true, window or null) when listed, (false, null) when an ordinary day. */
        fun listed(d: LocalDate): Pair<Boolean, Window?> = when {
            d in added -> true to null
            d.year in fetchedYears -> if (d in fetched) true to fetched[d] else false to null
            d in BUILT_IN -> true to BUILT_IN[d]
            else -> false to null
        }

        /** The day's session, or null when MCX does not trade on [d]. */
        fun window(d: LocalDate): Window? {
            val (isListed, w) = listed(d)
            if (isListed) return w
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return null
            return Window(OPEN, close(d))
        }

        fun isTradingDay(d: LocalDate): Boolean = window(d) != null

        /** MCX is trading at [t] (IST). */
        fun isOpen(t: LocalDateTime): Boolean = window(t.toLocalDate())?.contains(t.toLocalTime()) == true

        /** The trading day [n] trading days before [d] (1 = the previous trading day); looks back at most 40 days. */
        fun tradingDaysBefore(d: LocalDate, n: Int): LocalDate {
            var x = d; var left = n
            var guard = 0
            while (left > 0 && guard < 40) { x = x.minusDays(1); guard++; if (isTradingDay(x)) left-- }
            return x
        }

        /** [d] itself when MCX trades on it, else the next trading day (at most 40 days on). */
        fun onOrAfter(d: LocalDate): LocalDate {
            var x = d; var guard = 0
            while (!isTradingDay(x) && guard < 40) { x = x.plusDays(1); guard++ }
            return x
        }

        /** The listed days from [from] on (for a screen): (day, its window or null = shut). */
        fun upcoming(from: LocalDate): List<Pair<LocalDate, Window?>> {
            val days = (fetched.keys + BUILT_IN.keys.filter { it.year !in fetchedYears } + added).filter { !it.isBefore(from) }.distinct().sorted()
            return days.map { it to listed(it).second }
        }
    }

    /** A calendar with nothing fetched: [BUILT_IN] alone. */
    val DEFAULT = Calendar()

    /**
     * MCX's days out of Upstox's public market-holiday list (api.upstox.com/v2/market/holidays): a day whose closed
     * exchanges include MCX is shut all day; a day that lists MCX among its open exchanges trades in that window (start
     * and end are epoch milliseconds). Days that name MCX in neither trade as usual and are left out. Unreadable rows are
     * skipped; nothing readable is an empty map.
     */
    fun parseUpstox(body: String): Map<LocalDate, Window?> {
        val root = runCatching { Json.parse(body) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val data = root["data"] as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, Window?>()
        val ist = ZoneOffset.ofHoursMinutes(5, 30)
        for (h in data) {
            val m = h as? Map<*, *> ?: continue
            val d = (m["date"] as? String)?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: continue
            val closed = (m["closed_exchanges"] as? List<*>)?.map { it.toString() } ?: emptyList()
            if ("MCX" in closed) { out[d] = null; continue }
            val open = (m["open_exchanges"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { it["exchange"] == "MCX" } ?: continue
            val s = (open["start_time"] as? Number)?.toLong() ?: continue
            val e = (open["end_time"] as? Number)?.toLong() ?: continue
            val w = Window(Instant.ofEpochMilli(s).atZone(ist).toLocalTime(), Instant.ofEpochMilli(e).atZone(ist).toLocalTime())
            // An ordinary full session listed only because other exchanges were shut is not an exception: left out.
            if (w.open == OPEN && w.close == close(d) && d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) continue
            out[d] = w
        }
        return out
    }

    /** "09:00-23:30" / "17:00-23:55" / "shut". */
    fun say(w: Window?): String = w?.let { "%02d:%02d-%02d:%02d".format(it.open.hour, it.open.minute, it.close.hour, it.close.minute) } ?: "shut"
}
