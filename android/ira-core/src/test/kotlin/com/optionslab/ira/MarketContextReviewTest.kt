package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 13 Oct review of the market context (TODO I6, I10-I13): each confirmed finding and the edge cases it asked for. */
class MarketContextReviewTest {
    private fun session(d: LocalDate, open: Double, drift: Double, minutes: Int = 375, wiggle: Double = 20.0): List<Candle> = (0 until minutes).map { m ->
        val px = open + drift * m / 374.0 + (if (m % 7 < 3) wiggle else -wiggle) * ((m % 5) / 5.0)
        Candle(d.atTime(9, 15).plusMinutes(m.toLong()), px, px + 5, px - 5, px)
    }

    private fun weekdays(from: LocalDate, n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>(); var d = from
        while (out.size < n) { if (d.dayOfWeek.value <= 5) out += d; d = d.plusDays(1) }
        return out
    }

    private val fixed = setOf(LevelMap.Type.PDH, LevelMap.Type.PDL, LevelMap.Type.PDC, LevelMap.Type.P)

    // ---- level map: levels only from the previous trading day ------------------------------------------------------------

    @Test fun aMissingYesterdayGivesNoPreviousDayLevelsButKeepsTodaysOwnLines() {
        val days = weekdays(LocalDate.of(2026, 9, 1), 12)
        val today = days.last()
        // Yesterday (days[10]) is not on the phone: the newest held session is two trading days back.
        val bars = days.dropLast(1).filter { it != days[10] }.flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, if (i % 2 == 0) 150.0 else -120.0) }
        val map = LevelMap.map(bars, today, maxOiCall = 50_500.0, maxOiPut = 49_500.0, vwap = listOf("VWAP" to 50_100.0))
        assertTrue(map.none { it.type in fixed }, map.toString())
        assertEquals(setOf(LevelMap.Type.MAX_OI_CALL, LevelMap.Type.MAX_OI_PUT, LevelMap.Type.VWAP), map.map { it.type }.toSet())
        assertEquals(0, LevelMap.counted(bars, today))
        // ...but when that day was an exchange holiday, the session before it IS yesterday.
        val holiday = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != days[10] }
        val y = bars.filter { it.t.toLocalDate() == days[9] }
        assertEquals(y.maxOf { it.h }, LevelMap.map(bars, today, isTrading = holiday).first { it.type == LevelMap.Type.PDH }.price, 1e-9)
    }

    @Test fun aPartialYesterdayIsNotPassedOffAsYesterday() {
        val days = weekdays(LocalDate.of(2026, 9, 1), 12)
        val today = days.last()
        val bars = days.dropLast(1).flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, 100.0, minutes = if (x == days[10]) 120 else 375) }
        assertTrue(LevelMap.map(bars, today).none { it.type in fixed })
        assertEquals(0, LevelMap.counted(bars, today))
    }

    @Test fun aGapInTheHistorySkipsTheSessionAfterIt() {
        val days = weekdays(LocalDate.of(2026, 9, 1), 12)
        val today = days.last()
        val bars = days.dropLast(1).filter { it != days[7] }.flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, if (i % 2 == 0) 150.0 else -120.0) }
        // Ten whole sessions; the five after the first five are scored, less days[8] (its "previous" would be days[6]).
        assertEquals(4, LevelMap.counted(bars, today))
        val s = LevelMap.sessionsBefore(bars, today)
        assertEquals(listOf(5, 6, 8, 9), LevelMap.scored(s))
        // A holiday on days[7] makes days[8]'s previous session days[6]: all five count.
        assertEquals(5, LevelMap.counted(bars, today) { d -> d.dayOfWeek.value <= 5 && d != days[7] })
        assertTrue(LevelMap.map(bars, today).any { it.type == LevelMap.Type.PDH })
    }

    @Test fun todayWithNoBarsOpensAtYesterdaysClose() {
        val days = weekdays(LocalDate.of(2026, 9, 1), 8)
        val today = days.last()
        val bars = days.dropLast(1).flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, 80.0) }
        val map = LevelMap.map(bars, today)
        val y = bars.filter { it.t.toLocalDate() == days[6] }
        assertEquals(y.maxOf { it.h }, map.first { it.type == LevelMap.Type.PDH }.price, 1e-9)
        assertEquals(y.last().c, map.first { it.type == LevelMap.Type.PDC }.price, 1e-9)
    }

    @Test fun fewerThanSwingDaysSessionsHaveLevelsButNoCount() {
        val days = weekdays(LocalDate.of(2026, 9, 1), 4)
        val today = days.last()
        val bars = days.dropLast(1).flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, 80.0) }
        val map = LevelMap.map(bars, today)
        val pdh = map.first { it.type == LevelMap.Type.PDH }
        assertEquals(0, pdh.tested)
        assertTrue("2024-26: held" in pdh.label(), pdh.label())
        assertEquals(0, LevelMap.counted(bars, today))
        assertTrue(LevelMap.map(emptyList(), today).isEmpty())
    }

    // ---- level map: the 09:20 OI strikes -------------------------------------------------------------------------------

    private fun oiSeries(strike: Double, r: Right, minutes: IntArray, oi: LongArray) =
        Series(null, strike, r, 30, minutes, DoubleArray(minutes.size) { 100.0 }, null, null, null, null, oi)

    @Test fun theOiLinesAreThe0920Snapshot() {
        val mins = IntArray(20) { 555 + it }                       // 09:15 .. 09:34
        // 52,500 CE leads at 09:20; 52,000 CE overtakes it later in the morning.
        val a = oiSeries(52_000.0, Right.CE, mins, LongArray(20) { if (555 + it <= 560) 1_000L else 9_000L })
        val b = oiSeries(52_500.0, Right.CE, mins, LongArray(20) { 2_000L })
        val p = oiSeries(51_500.0, Right.PE, mins, LongArray(20) { 1_500L })
        assertEquals(52_500.0, LevelMap.maxOiStrikeAt0920(listOf(a, b, p), Right.CE, readMinute = 575, snapshot = false))
        assertEquals(51_500.0, LevelMap.maxOiStrikeAt0920(listOf(a, b, p), Right.PE, readMinute = 575, snapshot = false))
        assertNull(LevelMap.maxOiStrikeAt0920(listOf(a, b), Right.CE, readMinute = 560, snapshot = false), "09:20's bar not closed yet")
        // Minute candles that stop before 09:16: no 09:20 OI.
        val early = oiSeries(52_000.0, Right.CE, intArrayOf(555), longArrayOf(5_000))
        assertNull(LevelMap.maxOiStrikeAt0920(listOf(early), Right.CE, readMinute = 600, snapshot = false))
        // A quote snapshot: only one taken at 09:21-09:22 is 09:20's.
        val snap = { m: Int -> listOf(oiSeries(52_000.0, Right.CE, intArrayOf(m), longArrayOf(3_000)), oiSeries(52_500.0, Right.CE, intArrayOf(m), longArrayOf(2_000))) }
        assertEquals(52_000.0, LevelMap.maxOiStrikeAt0920(snap(561), Right.CE, readMinute = 561, snapshot = true))
        assertNull(LevelMap.maxOiStrikeAt0920(snap(600), Right.CE, readMinute = 600, snapshot = true))
        assertNull(LevelMap.maxOiStrikeAt0920(emptyList(), Right.CE, readMinute = 600, snapshot = false))
    }

    // ---- days like today -------------------------------------------------------------------------------------------------

    private val vix13 = { ds: List<LocalDate> -> ds.map { Candle(it.atTime(9, 20), 13.0, 13.0, 13.0, 13.0) } }

    @Test fun aPartialOrOlderPreviousDaySkipsTheRow() {
        val days = weekdays(LocalDate.of(2026, 10, 5), 4)            // Mon 5 .. Thu 8 Oct
        // days[0] stops at 12:35: days[1]'s gap and previous range would be wrong.
        val bars = session(days[0], 50_000.0, 100.0, minutes = 200) + session(days[1], 50_200.0, -50.0) +
            session(days[2], 50_100.0, 20.0) + session(days[3], 50_000.0, 0.0)
        val rows = DaysLikeToday.fromCandles(bars, vix13(days), emptySet(), emptySet(), days[3])
        assertEquals(listOf(days[2], days[3]), rows.map { it.day })
        // days[2] missing: days[3]'s "previous" would be days[1], two sessions back.
        val gap = bars.filter { it.t.toLocalDate() != days[2] }
        assertTrue(DaysLikeToday.fromCandles(gap, vix13(days), emptySet(), emptySet(), days[3]).none { it.day == days[3] })
        // ...unless days[2] was an exchange holiday.
        val hol = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != days[2] }
        assertEquals(listOf(days[3]), DaysLikeToday.fromCandles(gap, vix13(days), emptySet(), emptySet(), days[3], isTrading = hol).filter { it.day == days[3] }.map { it.day })
    }

    @Test fun missingVixUsesThePreviousSessionOrSkips() {
        val days = weekdays(LocalDate.of(2026, 10, 5), 2)
        val bars = session(days[0], 50_000.0, 100.0) + session(days[1], 50_200.0, -50.0)
        assertTrue(DaysLikeToday.fromCandles(bars, emptyList(), emptySet(), emptySet(), days[1]).isEmpty(), "no VIX at all: no row")
        val prevOnly = listOf(Candle(days[0].atTime(15, 29), 14.5, 14.5, 14.5, 14.5))
        assertEquals(14.5, DaysLikeToday.fromCandles(bars, prevOnly, emptySet(), emptySet(), days[1]).single().vix, 1e-9)
        // An older day's VIX is not today's.
        val older = listOf(Candle(days[0].minusDays(7).atTime(15, 29), 20.0, 20.0, 20.0, 20.0))
        assertTrue(DaysLikeToday.fromCandles(bars, older, emptySet(), emptySet(), days[1]).isEmpty())
    }

    private fun row(d: LocalDate, i: Int, range: Double? = 0.9) =
        DaysLikeToday.Row(d, (i % 10 - 5) / 10.0, 12.0 + i % 7, 0.8 + (i % 5) / 10.0, false, false, range, if (i % 2 == 0) 0.3 else -0.2)

    @Test fun aBriefWithEveryRangeMissingDoesNotThrow() {
        val days = weekdays(LocalDate.of(2025, 1, 1), 61)
        val rows = days.dropLast(1).mapIndexed { i, d -> row(d, i, range = null) }
        val b = DaysLikeToday.brief("NIFTY", row(days.last(), 3).copy(restRangePct = null, restRetPct = null), rows)!!
        val all = b.joinToString(" ")
        assertTrue("closed above their 09:20 price." in all, all)
        assertFalse("range after 09:20" in all, all)
        assertNull(DaysLikeToday.median(emptyList()))
        assertEquals(2.5, DaysLikeToday.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test fun csvBlankOrNanFieldsAreMissing() {
        val csv = """
            day,gap_pct,vix_0920,prev_range_pct,is_expiry,event,rest_range_pct,rest_ret_pct,net_ORB
            2025-01-01,0.1,13,0.8,0,0,0.9,0.2,100
            2025-01-02,,13,0.8,0,0,0.9,0.2,100
            2025-01-03,0.1,NaN,0.8,0,0,0.9,0.2,100
            2025-01-06,0.1,13,0.8,0,0,NaN,,NaN
            not-a-date,0.1,13,0.8,0,0,0.9,0.2,100
        """.trimIndent()
        val rows = DaysLikeToday.parseCsv(csv)
        assertEquals(listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 6)), rows.map { it.day })
        val r = rows.last()
        assertNull(r.restRangePct); assertNull(r.restRetPct)
        assertTrue(r.arms.isEmpty(), r.arms.toString())
        assertEquals(mapOf("ORB" to 100.0), rows.first().arms)
        assertTrue(DaysLikeToday.parseCsv("").isEmpty())
    }

    @Test fun thePhoneOverridesTheShippedFeaturesAndKeepsTheirArmsWhenItHasNone() {
        val d = LocalDate.of(2026, 10, 7)
        val shipped = listOf(DaysLikeToday.Row(d, 0.1, 12.0, 0.8, false, false, 0.9, 0.2, mapOf("ORB" to -500.0), DaysLikeToday.RESEARCH),
            DaysLikeToday.Row(d.minusDays(1), 0.0, 11.0, 0.7, false, false, 0.5, 0.1))
        val phone = DaysLikeToday.Row(d, 0.5, 14.0, 1.1, true, true, 1.2, -0.3)
        val m = DaysLikeToday.merge(shipped, listOf(phone))
        assertEquals(2, m.size)
        val x = m.last()
        assertEquals(0.5, x.gapPct); assertEquals(14.0, x.vix); assertTrue(x.expiry && x.event)
        assertEquals(mapOf("ORB" to -500.0), x.arms); assertEquals(DaysLikeToday.RESEARCH, x.armsFrom)
        val own = DaysLikeToday.merge(shipped, listOf(phone.copy(arms = mapOf("ORB" to 250.0), armsFrom = "your paper account"))).last()
        assertEquals(mapOf("ORB" to 250.0), own.arms); assertEquals("your paper account", own.armsFrom)
    }

    // ---- event shield --------------------------------------------------------------------------------------------------

    @Test fun electionResultsAreNotCompanyResults() {
        assertEquals(EventShield.Group.OTHER, EventShield.group("election results"))
        assertEquals(EventShield.Group.OTHER, EventShield.group("Lok Sabha Election Results"))
        assertEquals(EventShield.Group.RESULTS, EventShield.group("HDFC Bank results"))
        val w = EventShield.warning(listOf("election results"), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14))!!
        assertFalse("such days" in w, w)                             // no heavyweight-results record quoted
    }

    @Test fun lowercaseEventsAreGroupedAndCounted() {
        assertEquals(EventShield.Group.INDIA_POLICY, EventShield.group("rbi policy"))
        assertEquals(EventShield.Group.US_MACRO, EventShield.group("us fed decision overnight (fomc)"))
        assertTrue(EventShield.counts("rbi policy"))
        assertFalse(EventShield.counts("nifty expiry"))
        assertTrue("15 such days" in EventShield.warning(listOf("rbi policy"), LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 4))!!)
    }

    private val budgetOnly = { d: LocalDate -> if (d == LocalDate.of(2026, 2, 1)) listOf("Union Budget") else emptyList() }

    @Test fun aBudgetOnAClosedSundayIsTheNextSessionsEvent() {
        val sun = LocalDate.of(2026, 2, 1); val mon = LocalDate.of(2026, 2, 2)
        val mc = MarketContextCore
        assertTrue(mc.eventsOn(sun, budgetOnly, mc::weekday, cpi = emptyList()).isEmpty())
        val ev = mc.eventsOn(mon, budgetOnly, mc::weekday, cpi = emptyList())
        assertEquals(listOf("Union Budget (Sun 1 Feb, market closed)"), ev)
        assertEquals(EventShield.Group.INDIA_POLICY, EventShield.group(ev.single()))
        // Fri 30 Jan's evening warning names Monday.
        val w = mc.eventWarning(LocalDate.of(2026, 1, 30).atTime(15, 45), { d -> mc.eventsOn(d, budgetOnly, mc::weekday, emptyList()) })!!
        assertTrue(w.startsWith("Mon 2 Feb (next session) is an event day: Union Budget"), w)
        // A special Sunday session: the Budget is that day's own, and Monday's is clear.
        val special = { d: LocalDate -> d.dayOfWeek.value <= 5 || d == sun }
        assertEquals(listOf("Union Budget"), mc.eventsOn(sun, budgetOnly, special, emptyList()))
        assertTrue(mc.eventsOn(mon, budgetOnly, special, emptyList()).isEmpty())
        // An expiry on a closed day is never rolled into an event.
        assertTrue(mc.eventsOn(mon, { d -> if (d == sun) listOf("NIFTY expiry") else emptyList() }, mc::weekday, emptyList()).isEmpty())
    }

    @Test fun aFedNightOnAHolidayAndCpiRollToTheNextSession() {
        val fed = { d: LocalDate -> Events.builtIn(d, d).map { it.name } }
        val thu = LocalDate.of(2026, 10, 29); val fri = LocalDate.of(2026, 10, 30)
        val mc = MarketContextCore
        assertTrue(mc.eventsOn(thu, fed, mc::weekday, emptyList()).any { "FOMC" in it })
        val holiday = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != thu }
        assertTrue(mc.eventsOn(thu, fed, holiday, emptyList()).isEmpty())
        val f = mc.eventsOn(fri, fed, holiday, emptyList())
        assertEquals(listOf("US Fed decision overnight (FOMC) (Thu 29 Oct, market closed)"), f)
        assertEquals(EventShield.Group.US_MACRO, EventShield.group(f.single()))
        // US CPI on Wed 14 Oct (after the Indian close): Thursday reacts; with Thursday a holiday, Friday.
        val none = { _: LocalDate -> emptyList<String>() }
        assertTrue(mc.eventsOn(LocalDate.of(2026, 10, 14), none).isEmpty())
        assertEquals(listOf(EventShield.CPI_NAME), mc.eventsOn(LocalDate.of(2026, 10, 15), none))
        val h15 = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != LocalDate.of(2026, 10, 15) }
        assertEquals(listOf(EventShield.CPI_NAME), mc.eventsOn(LocalDate.of(2026, 10, 16), none, h15))
        assertTrue("listed until 14 Oct 2026" in EventShield.cpiNote(), EventShield.cpiNote())
        assertTrue("No US CPI dates" in EventShield.cpiNote(emptyList()))
    }

    // ---- the warning, the 09:00 lines and the day's rollover ---------------------------------------------------------------

    private val tue = LocalDate.of(2026, 10, 13)
    private val both = { d: LocalDate -> when (d) { tue -> listOf("RBI policy"); tue.plusDays(1) -> listOf("HDFC Bank results"); else -> emptyList() } }

    @Test fun theWarningSwitchesToTheNextSessionAt1530() {
        val mc = MarketContextCore
        assertTrue(mc.eventWarning(tue.atTime(15, 29), both)!!.startsWith("Today is an event day: RBI policy."))
        assertTrue(mc.eventWarning(tue.atTime(15, 30), both)!!.startsWith("Wed 14 Oct (next session) is an event day: HDFC Bank results."))
        // Nothing today: the next session's all day.
        val wedOnly = { d: LocalDate -> if (d == tue.plusDays(1)) listOf("RBI policy") else emptyList() }
        assertTrue(mc.eventWarning(tue.atTime(9, 0), wedOnly)!!.startsWith("Wed 14 Oct (next session)"))
        assertNull(mc.eventWarning(tue.atTime(9, 0), { emptyList() }))
        // The day's rollover: just after midnight it is that day's own warning.
        assertTrue(mc.eventWarning(tue.atTime(0, 5), both)!!.startsWith("Today is an event day: RBI policy."))
    }

    @Test fun weekendsAndHolidaysWarnForTheNextSession() {
        val mc = MarketContextCore
        val mon = LocalDate.of(2026, 10, 19)
        val monOnly = { d: LocalDate -> if (d == mon) listOf("RBI policy") else emptyList() }
        assertTrue(mc.eventWarning(LocalDate.of(2026, 10, 17).atTime(10, 0), monOnly)!!.startsWith("Mon 19 Oct (next session)"))
        // Fri 16 Oct a holiday: on Thursday evening and on Friday it is Monday's.
        val hol = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != LocalDate.of(2026, 10, 16) }
        assertTrue(mc.eventWarning(LocalDate.of(2026, 10, 15).atTime(16, 0), monOnly, hol)!!.startsWith("Mon 19 Oct (next session)"))
        assertTrue(mc.eventWarning(LocalDate.of(2026, 10, 16).atTime(10, 0), monOnly, hol)!!.startsWith("Mon 19 Oct (next session)"))
    }

    @Test fun theMorningLines() {
        val mc = MarketContextCore
        val exp = { u: String, t: LocalTime -> listOf("$u at ${t.hour}:${t.minute}") }
        val l = mc.morningLines(tue.atTime(9, 0), both, mc::weekday, listOf("NIFTY"), EventShield.Mode.SHADOW, exp)
        assertTrue(l[0].startsWith("Today is an event day: RBI policy."), l[0])
        assertEquals(ExpiryCard.title(listOf("NIFTY")) + ": NIFTY at 9:0", l[1])
        assertEquals("Your switch \"pause paper arms on event days\" is ${EventShield.Mode.SHADOW.words}.", l[2])
        assertEquals(mc.DAYS_LINE, l.last())
        // Switch off: not said. No event: only the expiry and the days line.
        assertFalse(mc.morningLines(tue.atTime(9, 0), both, mc::weekday, emptyList(), EventShield.Mode.OFF, exp).any { "Your switch" in it })
        assertEquals(listOf(mc.DAYS_LINE), mc.morningLines(tue.atTime(9, 0), { emptyList() }, mc::weekday, emptyList(), EventShield.Mode.ON, exp))
        // A Saturday: only the next session's warning.
        val sat = mc.morningLines(LocalDate.of(2026, 10, 17).atTime(9, 0), { d -> if (d.dayOfMonth == 19) listOf("RBI policy") else emptyList() },
            mc::weekday, listOf("NIFTY"), EventShield.Mode.SHADOW, exp)
        assertEquals(1, sat.size); assertTrue(sat[0].startsWith("Mon 19 Oct (next session)"))
    }

    // ---- shadow first --------------------------------------------------------------------------------------------------

    @Test fun aShadowDayCountsOnlyWhenShadowWatchedTheSession() {
        val ev = listOf("RBI policy")
        val mc = MarketContextCore
        val sh = EventShield.Mode.SHADOW
        fun c(since: LocalDateTime?, verdict: LocalDateTime?, now: LocalDateTime, mode: EventShield.Mode = sh, events: List<String> = ev, trading: Boolean = true) =
            mc.shadowDayCounts(tue, events, trading, mode, since, verdict, now)
        // Switched to Shadow at 10:00 and nothing logged: not watched.
        assertFalse(c(tue.atTime(10, 0), null, tue.atTime(16, 0)))
        // A verdict logged in market hours: counts (even before 15:30).
        assertTrue(c(tue.atTime(10, 0), tue.atTime(11, 5), tue.atTime(11, 6)))
        assertFalse(c(null, tue.atTime(9, 10), tue.atTime(16, 0)), "a verdict before the open is not a watched session")
        assertFalse(c(null, tue.atTime(15, 30), tue.atTime(16, 0)))
        assertFalse(c(null, tue.minusDays(1).atTime(11, 0), tue.atTime(16, 0)), "yesterday's verdict")
        // On from before 09:15 through 15:30.
        assertTrue(c(tue.atTime(8, 0), null, tue.atTime(15, 30)))
        assertTrue(c(tue.minusDays(2).atTime(20, 0), null, tue.atTime(15, 31)))
        assertFalse(c(tue.atTime(8, 0), null, tue.atTime(15, 29)), "not through the close yet")
        assertFalse(c(tue.atTime(9, 16), null, tue.atTime(16, 0)), "switched on after the open")
        assertFalse(c(tue.atTime(8, 0), null, tue.atTime(16, 0), mode = EventShield.Mode.OFF))
        assertFalse(c(tue.atTime(8, 0), null, tue.plusDays(1).atTime(16, 0)), "the next day is another day")
        // Not an event day, or a closed day: never.
        assertFalse(c(tue.atTime(8, 0), tue.atTime(11, 0), tue.atTime(16, 0), events = emptyList()))
        assertFalse(c(tue.atTime(8, 0), tue.atTime(11, 0), tue.atTime(16, 0), events = listOf("NIFTY expiry")))
        assertFalse(c(tue.atTime(8, 0), tue.atTime(11, 0), tue.atTime(16, 0), trading = false))
    }

    // ---- the brief's tries ------------------------------------------------------------------------------------------------

    @Test fun theBriefIsTriedFrom0921WithABackoffAndOnceBuiltNotAgain() {
        val mc = MarketContextCore
        var s = MarketContextCore.BriefTries()
        assertFalse(mc.briefDue(s, tue.atTime(9, 20), true))
        assertFalse(mc.briefDue(s, tue.atTime(10, 0), false), "a closed day")
        assertTrue(mc.briefDue(s, tue.atTime(9, 21), true))
        s = mc.briefAfter(s, tue.atTime(9, 21), ok = false)
        assertEquals(tue.atTime(9, 26), s.nextAt)
        assertFalse(mc.briefDue(s, tue.atTime(9, 22), true))
        assertTrue(mc.briefDue(s, tue.atTime(9, 26), true))
        var t = tue.atTime(9, 26)
        while (mc.briefDue(s, t, true)) { s = mc.briefAfter(s, t, ok = false); t = s.nextAt!! }
        assertEquals(mc.BRIEF_MAX_TRIES, s.tries)
        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 80L), (1..6).map { mc.briefBackoffMin(it) })
        assertFalse(mc.briefDue(s, tue.atTime(15, 0), true), "given up for the day")
        assertFalse(mc.briefShown(s, tue))
        // The next day starts afresh.
        val wed = tue.plusDays(1)
        assertTrue(mc.briefDue(s, wed.atTime(9, 21), true))
        s = mc.briefAfter(s, wed.atTime(9, 21), ok = true)
        assertEquals(1, s.tries); assertTrue(s.done); assertTrue(mc.briefShown(s, wed))
        assertFalse(mc.briefDue(s, wed.atTime(9, 22), true))
        assertFalse(mc.briefShown(s, wed.plusDays(1)), "rolled over: yesterday's brief is not shown")
    }

    // ---- explain a candle --------------------------------------------------------------------------------------------------

    private val t0 = LocalDateTime.of(2026, 10, 12, 10, 35)

    @Test fun aZeroOpenHasNoPercentage() {
        val l = CandleExplain.explain(CandleExplain.Inputs("BANKNIFTY", t0, 5, 0.0, 10.0, 0.0, 5.0, null))
        assertFalse(l[0].contains("Infinity") || l[0].contains("NaN"), l[0])
        assertTrue(l[0].contains("up 5.00, range 10.00."), l[0])
    }

    @Test fun dailyAndWeeklyCandlesHaveNoClockTimes() {
        val d = CandleExplain.explain(CandleExplain.Inputs("NIFTY", t0.withHour(9).withMinute(15), 1440, 100.0, 110.0, 95.0, 105.0, null))
        assertTrue(d[0].startsWith("NIFTY daily candle of 2026-10-12: opened"), d[0])
        assertTrue(d.any { it.startsWith("News: no headline on the phone from 12 Oct 09:00 to 13 Oct 09:15") }, d.toString())
        val w = CandleExplain.explain(CandleExplain.Inputs("NIFTY", t0.withHour(9).withMinute(15), 7 * 1440, 100.0, 110.0, 95.0, 105.0, null))
        assertTrue(w[0].startsWith("NIFTY weekly candle of the week of 2026-10-12: opened"), w[0])
        assertEquals("1-hour", CandleExplain.kind(60)); assertEquals("5-minute", CandleExplain.kind(5))
    }

    @Test fun anOptionCandleIsNotCalledAnIndex() {
        val sym = "BANKNIFTY 52100 CE"
        val none = CandleExplain.explain(CandleExplain.Inputs(sym, t0, 5, 210.0, 220.0, 205.0, 215.0, null))
        assertTrue(none[0].startsWith("$sym 5-minute candle"), none[0])
        assertTrue(none.any { it == "Volume: none on this chart for that candle." }, none.toString())
        assertFalse(none.any { "an index has no volume" in it })
        val chain = listOf(CandleExplain.Leg(52_100.0, "CE", 210.0, 1_200_000, null, 0.131), CandleExplain.Leg(52_100.0, "PE", 190.0, 900_000, null, 0.128))
        val rec = CandleExplain.explain(CandleExplain.Inputs(sym, t0, 5, 210.0, 220.0, 205.0, 215.0, 50_000.0,
            recStart = CandleExplain.Recorded(52_100.0, 52_190.0, 90.0, 13.2, chain)))
        assertTrue(rec.any { it.startsWith("Volume 50,000") }, rec.toString())
        assertTrue(rec.any { "52,100 CE OI 12.0 lakh" in it }, rec.toString())
        assertNotNull(rec.firstOrNull { "India VIX 13.20 (from the recording)" in it })
    }
}
