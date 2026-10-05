package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CausesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    /** Monday 5 October 2026. */
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(12, 30)

    /** [days] earlier sessions (weekdays before today) of 90 flat candles, each closing [step]% away from the one before, alternately. */
    private fun history(base: Double, step: Double, days: Int = 15): List<Candle> {
        val out = ArrayList<Candle>()
        var d = today.minusDays(1)
        val dates = ArrayList<LocalDate>()
        while (dates.size < days) { if (d.dayOfWeek.value <= 5) dates += d; d = d.minusDays(1) }
        dates.reversed().forEachIndexed { k, day ->
            val c = base * (1 + if ((days - 1 - k) % 2 == 1) step / 100 else 0.0)
            for (i in 0 until 90) out += Candle(day.atTime(9, 15).plusMinutes(i.toLong()), c, c, c, c)
        }
        return out
    }

    /** Today from 09:15 for [total] minutes: opens at [open], flat until [from] minutes in, then [per] points a minute for [len] minutes. */
    private fun todayBars(open: Double, from: Int, per: Double, len: Int, total: Int = 180) = (0 until total).map { i ->
        val c = open + per * (i - from).coerceIn(0, len)
        Candle(today.atTime(9, 15).plusMinutes(i.toLong()), open, maxOf(open, c), minOf(open, c), c)
    }

    private fun headline(title: String, at: LocalDateTime, source: String = "ET", tone: Double = 0.0) =
        Headline(title, "https://x/$source/${title.hashCode()}", source, at.atZone(zone).toInstant(), tone, emptyList())

    /** Nifty's last close before today in [history] (base 25,000; with 15 days the last is the unmoved one). */
    private val prev = 25_000.0

    // ---- what was asked ----

    @Test fun whyQuestionsAreAsked() {
        assertEquals(Causes.Ask(Market.NIFTY, true), Causes.asked("why did Nifty fall?"))
        assertEquals(Causes.Ask(Market.NIFTY, true), Causes.asked("why is nifty falling today"))
        assertEquals(Causes.Ask(null, true), Causes.asked("why is the market down today"))
        assertEquals(Causes.Ask(Market.BANKNIFTY, false), Causes.asked("why did banknifty jump"))
        assertEquals(Causes.Ask(Market.SENSEX, false), Causes.asked("why is sensex up"))
        assertEquals(Causes.Ask(null, true), Causes.asked("what caused the fall today"))
        assertEquals(Causes.Ask(Market.BANKNIFTY, false), Causes.asked("what's behind the rally in bank nifty"))
        assertEquals(Causes.Ask(null, true), Causes.asked("reason for today's fall"))
        assertEquals(Causes.Ask(Market.NIFTY, true), Causes.asked("nifty kyun gira"))
        assertEquals(Causes.Ask(null, true), Causes.asked("market kyun gira aaj"))
        assertEquals(Causes.Ask(Market.NIFTY, null), Causes.asked("why is nifty moving so much"))
        assertNotNull(Causes.asked("Jarvis, why has Nifty fallen so much today?"))
    }

    @Test fun othersKeepTheirOwnQuestions() {
        for (s in listOf("why did nifty suddenly fall", "news behind today's fall", "why did my last trade lose", "why didn't you take that trade",
            "why did you stop orb", "will nifty fall tomorrow", "why is vix up", "why is gold falling", "how much did nifty fall today",
            "should i sell because nifty is falling", "why so quiet", "nifty kal kyun girega", "what is nifty at", "why is crude falling"))
            assertNull(Causes.asked(s), s)
    }

    @Test fun theIndexWeighed() {
        assertEquals(Market.NIFTY, Causes.market(Causes.Ask(null, true), emptyList()))
        assertEquals(Market.BANKNIFTY, Causes.market(Causes.Ask(null, true), listOf(Market.BANKNIFTY)))
        assertNull(Causes.market(Causes.Ask(null, true), listOf(Market.GOLD)))
    }

    // ---- the move ----

    @Test fun theMoveAndItsBiggestStretch() {
        // Opens 100 below, flat, then 5 points a minute down for 30 minutes from 10:15: -250 on the day.
        val bars = history(prev, 0.4) + todayBars(24_900.0, 60, -5.0, 30)
        val mv = Causes.move(Market.NIFTY, bars)!!
        assertEquals(-250.0, mv.net, 1e-9)
        assertEquals(-100.0, mv.gap, 1e-9)
        assertTrue(mv.down)
        assertEquals(today.atTime(10, 15), mv.legFrom)
        assertEquals(today.atTime(10, 45), mv.legTo)
        assertEquals(-150.0, mv.legPoints, 1e-9)
    }

    @Test fun barelyMovedHasNothingToExplain() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.4) + todayBars(25_000.0, 60, -0.5, 30))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(s.contains("barely moved"), s)
        assertTrue(s.contains(Causes.HYGIENE), s)
    }

    @Test fun tooFewCandlesIsSaid() {
        val s = Causes.answer(Causes.Ask(null, true), Market.NIFTY, mapOf(Market.NIFTY to todayBars(25_000.0, 0, -5.0, 5, total = 5)), emptyList(), zone, now)
        assertTrue(s.contains("don't have enough"), s)
        assertEquals(Causes.NOT_HERE, Causes.answer(Causes.Ask(null, true), Market.GOLD, emptyMap(), emptyList(), zone, now))
    }

    @Test fun theWrongDirectionIsCorrected() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.4) + todayBars(25_000.0, 60, 5.0, 30))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(s.startsWith("Nifty isn't down today, Boss - it's up. Weighing the rise instead."), s)
    }

    @Test fun anOrdinarySizedMoveIsRankedFirstAsNothingUnusual() {
        // Earlier days move 0.8% close to close every other day (middle 0.8%); today -0.3%.
        val bars = mapOf(Market.NIFTY to history(prev, 0.8) + todayBars(25_000.0, 60, -2.5, 30))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(s.contains("1. Nothing unusual to explain"), s)
    }

    // ---- the candidates ----

    @Test fun aHeadlineBeforeTheStretchFitsOneAfterIsCoincidenceAndAReportIsNeverAReason() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        val news = listOf(
            headline("RBI governor flags sticky inflation risks", today.atTime(10, 5), "Reuters", tone = -0.4),
            headline("RBI governor flags sticky inflation risks, says vigilant", today.atTime(10, 7), "Mint", tone = -0.4),
            headline("Auto sales data for September released by SIAM", today.atTime(10, 30), "ET"),
            headline("Nifty slips below 24,900 as selling deepens", today.atTime(10, 40), "Moneycontrol"),
        )
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, news, zone, now)
        assertTrue(s.contains("1. \"RBI governor flags sticky inflation risks\", out 10 minutes before the biggest stretch began (10:15); 2 sources; its words read negative, the move's way."), s)
        assertTrue(s.contains("Only coincidence in time:"), s)
        assertTrue(s.contains("\"Auto sales data for September released by SIAM\" came out at 10:30, after the fall had begun (10:15) - it can't have started it"), s)
        assertTrue(s.contains("\"Nifty slips below 24,900 as selling deepens\" at 10:40 reports the move itself"), s)
        assertTrue(s.indexOf("RBI governor") < s.indexOf("Only coincidence"), s)
        assertTrue(s.endsWith(Causes.HYGIENE), s)
        assertTrue(s.contains(Causes.NOT_ON_PHONE), s)
    }

    @Test fun aHeadlineWhoseWordsReadTheOtherWayRanksBelowOneThatFits() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        val news = listOf(
            headline("Infosys wins large deal, IT stocks cheer", today.atTime(10, 0), "ET", tone = 0.6),
            headline("Fed minutes show hawkish tilt on rates", today.atTime(10, 10), "Reuters", tone = -0.5),
        )
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, news, zone, now)
        assertTrue(s.indexOf("1. \"Fed minutes") >= 0 && s.indexOf("2. \"Infosys") > s.indexOf("1. \"Fed minutes"), s)
        assertTrue(s.contains("though its words read positive, the other way"), s)
    }

    @Test fun aGapCarriesTheDayAndPreOpenNewsCanBearOnIt() {
        // Opens 200 below and drifts 50 lower: the gap is 80% of the day.
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(24_800.0, 60, -1.0, 50))
        val news = listOf(headline("Fed signals more rate hikes as US inflation stays hot", today.atTime(7, 40), "Reuters", tone = -0.5))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, news, zone, now)
        assertTrue(s.contains("It opened 200 points below the previous close (80% of the day's move)."), s)
        assertTrue(s.contains("1. Most of it came before the session: the gap at the open was 80% of the day's move"), s)
        assertTrue(s.contains("\"Fed signals more rate hikes as US inflation stays hot\", out before the open, so it could bear on the gap"), s)
    }

    @Test fun nothingLinedUpIsSaidHonestly() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(s.contains("Nothing on the phone lines up ahead of it"), s)
        assertFalse(s.contains("Weighed by the evidence"), s)
    }

    @Test fun breadthAndVixDescribeButDoNotExplain() {
        val bars = mapOf(
            Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30),
            Market.BANKNIFTY to history(52_000.0, 0.2) + todayBars(52_000.0, 60, -25.0, 30),
            Market.FINNIFTY to history(24_000.0, 0.2) + todayBars(24_000.0, 60, -6.0, 30),
            Market.SENSEX to history(82_000.0, 0.2) + todayBars(82_000.0, 60, -16.0, 30),
            Market.VIX to history(12.0, 0.0) + todayBars(12.0, 60, 0.03, 30),
        )
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(s.contains("How it moved (that describes it, it doesn't explain it): Broad:"), s)
        assertTrue(s.contains("heavier in banks (BankNifty -1.44% against Nifty's -0.60%)"), s)
        assertTrue(s.contains("India VIX +7.50% on the day"), s)
        assertTrue(s.contains("not a reason for the move"), s)
    }

    @Test fun anIndexMovingAloneIsSaid() {
        val bars = mapOf(
            Market.BANKNIFTY to history(52_000.0, 0.2) + todayBars(52_000.0, 60, -25.0, 30),
            Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, 0.5, 30),
        )
        val s = Causes.answer(Causes.Ask(Market.BANKNIFTY, true), Market.BANKNIFTY, bars, emptyList(), zone, now)
        assertTrue(s.contains("BankNifty alone: Nifty +0.06%"), s)
    }

    @Test fun fiiFiguresAreADayTotalAndYesterdaysCannotTellToday() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        val old = listOf(Flows.Flow("FII", "02-Oct-2026", 0.0, 0.0, -2100.0), Flows.Flow("DII", "02-Oct-2026", 0.0, 0.0, 1500.0))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now, flows = old)
        assertTrue(s.contains("The latest FII figures I have are for 2 Oct: net sellers of Rs 2,100 crore - from before this session"), s)
        val same = listOf(Flows.Flow("FII", "05-Oct-2026", 0.0, 0.0, -3000.0))
        val t = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now, flows = same)
        assertTrue(t.contains("FIIs were net sellers of Rs 3,000 crore on 5 Oct (NSE's day total) - the same way as the move, but a day's total can't say when"), t)
        val none = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now)
        assertTrue(none.contains("No FII figures were read either."), none)
    }

    @Test fun eventsAndExpiryAreCalendarFactsOnly() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, emptyList(), zone, now, events = listOf("RBI policy"), expiry = true)
        assertTrue(s.contains("Scheduled today: RBI policy - the calendar holds the day, not the time"), s)
        assertTrue(s.contains("It is Nifty's expiry day - a fact of the calendar"), s)
    }

    @Test fun aThemeWithAPoorRecordHereIsMarkedDown() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(25_000.0, 60, -5.0, 30))
        // Ten earlier RBI notes, none followed by a 0.3% move within the hour.
        val log = (1..10).map { NewsMoves.Note(NewsDesk.Tag.RBI, Market.NIFTY, today.minusDays(it.toLong() * 3).atTime(11, 0), 25_000.0, mapOf(30 to 0.05, 60 to 0.1)) }
        val news = listOf(headline("RBI governor flags sticky inflation risks", today.atTime(10, 5), "Reuters"))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, news, zone, now, log = log)
        assertTrue(s.contains("but RBI headlines here were followed by such a move no more often than any hour (0 of 10"), s)
    }

    @Test fun neverAdviceOrAForecast() {
        val bars = mapOf(Market.NIFTY to history(prev, 0.2) + todayBars(24_900.0, 60, -5.0, 30))
        val news = listOf(headline("RBI governor flags sticky inflation risks", today.atTime(10, 5), "Reuters", tone = -0.4))
        val s = Causes.answer(Causes.Ask(Market.NIFTY, true), Market.NIFTY, bars, news, zone, now).lowercase()
        for (w in listOf(" buy ", " sell ", " should ", " will fall", " will rise", "because of", "caused by", "due to")) assertFalse(s.contains(w), "$w in $s")
    }
}
