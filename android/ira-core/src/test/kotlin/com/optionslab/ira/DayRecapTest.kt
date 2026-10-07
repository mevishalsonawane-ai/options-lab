package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayRecapTest {
    private val TODAY: LocalDate = LocalDate.of(2026, 10, 7)        // a Wednesday
    private val MON = LocalDate.of(2026, 10, 5); private val TUE = LocalDate.of(2026, 10, 6)
    private val THU = LocalDate.of(2026, 10, 1); private val FRI = LocalDate.of(2026, 10, 2)   // 2 Oct: Gandhi Jayanti
    private val trading: (LocalDate) -> Boolean = { d -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d != FRI }
    private val holiday: (LocalDate) -> String? = { d -> if (d == FRI) "Gandhi Jayanti" else null }
    private val ACTS = Regex("(?i)\\b(you should|i recommend|i suggest|consider|buy|sell|square off|switched (it )?off|placed|armed it|disarm)\\b")

    private fun said(s: String) = DayRecap.asked(s)?.said

    // ---- the question ---------------------------------------------------------------------------------------------

    @Test fun theRecapQuestionsAreTakenWithTheDayTheyName() {
        val asked = mapOf(
            "what happened on 3 oct" to DayRecap.Said.Dated(10, 3),
            "what happened on 3rd october" to DayRecap.Said.Dated(10, 3),
            "what happened on oct 2nd" to DayRecap.Said.Dated(10, 2),
            "What happened on 3 Oct?" to DayRecap.Said.Dated(10, 3),
            "what happened on 3 oct 2025" to DayRecap.Said.Dated(10, 3, 2025),
            "what happened on the 3rd" to DayRecap.Said.OfMonth(3),
            "recap of yesterday" to DayRecap.Said.DaysAgo(1),
            "yesterday's recap" to DayRecap.Said.DaysAgo(1),
            "what's yesterday's recap" to DayRecap.Said.DaysAgo(1),
            "give me a recap of yesterday" to DayRecap.Said.DaysAgo(1),
            "summary of yesterday" to DayRecap.Said.DaysAgo(1),
            "what happened yesterday" to DayRecap.Said.DaysAgo(1),
            "what happened in the market yesterday" to DayRecap.Said.DaysAgo(1),
            "what happened day before yesterday" to DayRecap.Said.DaysAgo(2),
            "what happened 3 days ago" to DayRecap.Said.DaysAgo(3),
            "what happened two days ago" to DayRecap.Said.DaysAgo(2),
            "what happened a week ago" to DayRecap.Said.DaysAgo(7),
            "how was monday" to DayRecap.Said.Weekday(DayOfWeek.MONDAY, DayRecap.Mode.BARE),
            "how was last friday" to DayRecap.Said.Weekday(DayOfWeek.FRIDAY, DayRecap.Mode.LAST),
            "how did monday go" to DayRecap.Said.Weekday(DayOfWeek.MONDAY, DayRecap.Mode.BARE),
            "how was 3 oct" to DayRecap.Said.Dated(10, 3),
            "what happened last friday" to DayRecap.Said.Weekday(DayOfWeek.FRIDAY, DayRecap.Mode.LAST),
            "jarvis what happened last friday" to DayRecap.Said.Weekday(DayOfWeek.FRIDAY, DayRecap.Mode.LAST),
            "what happened this monday" to DayRecap.Said.Weekday(DayOfWeek.MONDAY, DayRecap.Mode.THIS),
            "monday's summary" to DayRecap.Said.Weekday(DayOfWeek.MONDAY, DayRecap.Mode.BARE),
            "recap last friday" to DayRecap.Said.Weekday(DayOfWeek.FRIDAY, DayRecap.Mode.LAST),
            "2 oct ka recap" to DayRecap.Said.Dated(10, 2),
            "kal ka recap" to DayRecap.Said.DaysAgo(1),
            "kal kya hua" to DayRecap.Said.DaysAgo(1),
            "parso kya hua" to DayRecap.Said.DaysAgo(2),
            "market mein kal kya hua" to DayRecap.Said.DaysAgo(1),
            "somvar ko kya hua" to DayRecap.Said.Weekday(DayOfWeek.MONDAY, DayRecap.Mode.BARE),
            "pichle shukravar ka recap" to DayRecap.Said.Weekday(DayOfWeek.FRIDAY, DayRecap.Mode.LAST),
            "kal ka din kaisa tha" to DayRecap.Said.DaysAgo(1),
            "3 oct ka market kaisa tha" to DayRecap.Said.Dated(10, 3),
        )
        for ((s, want) in asked) assertEquals(want, said(s), s)
    }

    @Test fun neverAnotherAnswersQuestion() {
        for (s in listOf("how was yesterday", "what happened today", "what happened in the market today", "today's recap", "aaj ka recap",
            "day recap", "recap the day", "weekly recap", "how was last week", "what happened last week", "liquidity on 3 oct",
            "how did liquidity do yesterday", "what did solo do yesterday", "why didn't hero trade yesterday", "my pnl on 2 oct", "pnl yesterday",
            "how did the market open yesterday", "how was yesterday for nifty", "how was the market yesterday", "what happened to nifty yesterday",
            "what happened on monday with nifty", "what happened to banknifty on 3 oct", "what news on 3 oct", "news yesterday",
            "what happened to my order yesterday", "what happened to my requests", "kal ka plan", "kal kya hoga", "what will happen tomorrow",
            "what happened next monday", "recap my trades yesterday", "liquidity recap of yesterday", "what happened yesterday then close all positions",
            "how does nifty usually do on fridays", "what will nifty do on monday", "what happened the last 3 expiries", "how was my day yesterday",
            "was yesterday a good day", "what happened on 31 feb kal"))
            assertNull(DayRecap.asked(s), s)
    }

    @Test fun theDaysWordsAreRead() {
        assertEquals(DayRecap.Said.Dated(10, 3, 2025), DayRecap.said("3rd of october 2025"))
        assertEquals(DayRecap.Said.Dated(12, 25), DayRecap.said("december 25th"))
        assertEquals(DayRecap.Said.OfMonth(9), DayRecap.said("the 9th"))
        assertEquals(DayRecap.Said.Weekday(DayOfWeek.THURSDAY, DayRecap.Mode.LAST), DayRecap.said("pichle guruvar"))
        assertEquals(DayRecap.Said.Weekday(DayOfWeek.TUESDAY, DayRecap.Mode.THIS), DayRecap.said("is mangalvar"))
        assertEquals(DayRecap.Said.DaysAgo(2), DayRecap.said("parso"))
        assertNull(DayRecap.said("the 40th"))
        assertNull(DayRecap.said("99 days ago"))
        assertNull(DayRecap.said(""))
    }

    // ---- which day -------------------------------------------------------------------------------------------------

    @Test fun theDayNamedIsFoundFromToday() {
        fun d(s: String) = DayRecap.dayOf(DayRecap.asked(s)!!.said, TODAY)
        assertEquals(TUE, d("recap of yesterday"))
        assertEquals(MON, d("parso kya hua"))
        assertEquals(MON, d("how was monday"))
        assertEquals(TODAY, d("how was wednesday"))                          // the latest Wednesday is today
        assertEquals(LocalDate.of(2026, 9, 30), d("what happened last wednesday"))
        assertEquals(LocalDate.of(2026, 10, 9), d("what happened this friday"))  // still to come
        assertEquals(LocalDate.of(2026, 10, 3), d("what happened on 3 oct"))
        assertEquals(LocalDate.of(2026, 10, 20), d("what happened on 20 oct"))  // within a month ahead: still to come
        assertEquals(LocalDate.of(2025, 12, 25), d("what happened on 25 dec"))  // further on: last year's
        assertEquals(LocalDate.of(2025, 10, 3), d("what happened on 3 oct 2025"))
        assertEquals(LocalDate.of(2026, 10, 3), d("what happened on the 3rd"))
        assertEquals(LocalDate.of(2026, 9, 9), d("what happened on the 9th"))    // this month's is to come: last month's
        assertNull(DayRecap.dayOf(DayRecap.Said.Dated(2, 30), TODAY))
        assertEquals(LocalDate.of(2026, 10, 31), DayRecap.dayOf(DayRecap.Said.OfMonth(31), LocalDate.of(2026, 11, 7)))   // no 31 Nov: October's
        assertNull(DayRecap.dayOf(DayRecap.Said.OfMonth(31), TODAY))                                                    // 31 Oct to come, no 31 Sep
    }

    @Test fun thePlanRefusesTheFutureSaysClosedDaysAndRollsYesterdayBack() {
        fun plan(s: String, today: LocalDate = TODAY) = DayRecap.plan(DayRecap.asked(s)!!, today, trading, holiday)
        assertEquals(DayRecap.Plan.Session(TUE), plan("recap of yesterday"))
        assertEquals(DayRecap.Plan.Future(LocalDate.of(2026, 10, 9)), plan("what happened this friday"))
        assertEquals(DayRecap.Plan.Today(TODAY), plan("how was wednesday"))
        assertEquals(DayRecap.Plan.Closed(LocalDate.of(2026, 10, 3), "a weekend", THU), plan("what happened on 3 oct"))
        assertEquals(DayRecap.Plan.Closed(FRI, "a market holiday (Gandhi Jayanti)", THU), plan("2 oct ka recap"))
        // "Yesterday" on a Monday (Sunday, after a Saturday and a holiday Friday): the session before it, Thursday.
        assertEquals(DayRecap.Plan.Session(THU, LocalDate.of(2026, 10, 4), "a weekend"), plan("kal kya hua", MON))
        assertEquals(DayRecap.Plan.Unknown, DayRecap.plan(DayRecap.Q(DayRecap.Said.Dated(2, 30, 2026)), TODAY, trading, holiday))
        // A calendar that cannot be read: the day is taken as a session (what is kept is said).
        assertEquals(DayRecap.Plan.Session(TUE), DayRecap.plan(DayRecap.Q(DayRecap.Said.DaysAgo(1)), TODAY, { error("no calendar") }, { null }))
        assertEquals("a market holiday", DayRecap.closedWhy(LocalDate.of(2026, 10, 20), { null }))
    }

    @Test fun whatIsSaidWithNoSessionToRead() {
        val future = DayRecap.say(DayRecap.Plan.Future(LocalDate.of(2026, 10, 9)), TODAY)!!
        assertTrue(future.startsWith("Fri 9 Oct is still to come, Boss"), future)
        val weekend = DayRecap.say(DayRecap.Plan.Closed(LocalDate.of(2026, 10, 3), "a weekend", THU), TODAY)!!
        assertEquals("Sat 3 Oct was a weekend, Boss - the market had no session, so there is nothing to recap. " +
            "The session before it was Thu 1 Oct - ask \"what happened on 1 Oct\".", weekend)
        val hol = DayRecap.say(DayRecap.Plan.Closed(FRI, "a market holiday (Gandhi Jayanti)", null), TODAY)!!
        assertTrue(hol.startsWith("Fri 2 Oct was a market holiday (Gandhi Jayanti), Boss") && !hol.contains("before it"), hol)
        assertEquals(DayRecap.UNKNOWN, DayRecap.say(DayRecap.Plan.Unknown, TODAY))
        assertNull(DayRecap.say(DayRecap.Plan.Session(TUE), TODAY))
        assertNull(DayRecap.say(DayRecap.Plan.Today(TODAY), TODAY))
        // Another year's day is named with its year.
        assertTrue(DayRecap.say(DayRecap.Plan.Closed(LocalDate.of(2025, 10, 4), "a weekend", null), TODAY)!!.startsWith("Sat 4 Oct 2025 was"))
    }

    // ---- the facts -------------------------------------------------------------------------------------------------

    private fun c(day: LocalDate, h: Int, m: Int, o: Double, hi: Double, lo: Double, cl: Double) = Candle(LocalDateTime.of(day, LocalTime.of(h, m)), o, hi, lo, cl)

    /** Monday closes at 25,000; Tuesday opens 25,100, high 25,300, low 24,950, closes 25,250. */
    private val nifty = listOf(
        c(MON, 15, 29, 25010.0, 25020.0, 24990.0, 25000.0),
        c(TUE, 9, 15, 25100.0, 25150.0, 25080.0, 25120.0),
        c(TUE, 11, 0, 25120.0, 25300.0, 24950.0, 25200.0),
        c(TUE, 15, 29, 25240.0, 25260.0, 25230.0, 25250.0),
        c(TODAY, 9, 15, 25260.0, 25270.0, 25250.0, 25255.0),
    )

    @Test fun anIndexIsReadFromItsCandlesOfThatDay() {
        val i = DayRecap.index(Market.NIFTY, nifty, TUE)!!
        assertEquals(DayRecap.Index(Market.NIFTY, 25100.0, 25300.0, 24950.0, 25250.0, 25000.0), i)
        assertEquals("Nifty: open 25,100.00 (gap up 100.00, +0.40%), high 25,300.00, low 24,950.00, close 25,250.00 (+1.00% on the day).",
            DayRecap.indexLine(i))
        // No candle of the day kept: none; no session before: the change from its open, no gap.
        assertNull(DayRecap.index(Market.NIFTY, nifty, LocalDate.of(2026, 9, 1)))
        val first = DayRecap.index(Market.NIFTY, nifty.drop(1), TUE)!!
        assertNull(first.prevClose)
        assertTrue(DayRecap.indexLine(first).contains("(+0.60% from its open)"), DayRecap.indexLine(first))
        // A flat open, a gap down, and the day still running.
        assertTrue(DayRecap.indexLine(i.copy(open = 25010.0)).contains("(a flat open)"))
        assertTrue(DayRecap.indexLine(i.copy(open = 24900.0)).contains("(gap down 100.00, -0.40%)"))
        assertTrue(DayRecap.indexLine(i, partial = true).contains("last 25,250.00 (+1.00% on the day so far)"))
    }

    @Test fun theRecordersReadingsStandInForMissingCandles() {
        val lines = listOf(
            MarketRecord.header(TUE),
            MarketRecord.spot(LocalTime.of(9, 5), "BANKNIFTY", 56000.0),         // before the open: not the session
            MarketRecord.spot(LocalTime.of(9, 15, 2), "BANKNIFTY", 56100.0),
            MarketRecord.spot(LocalTime.of(9, 15, 2), "NIFTY", 25100.0),
            MarketRecord.spot(LocalTime.of(12, 0, 1), "BANKNIFTY", 56400.0),
            MarketRecord.spot(LocalTime.of(13, 0, 1), "BANKNIFTY", 55900.0),
            MarketRecord.spot(LocalTime.of(15, 29, 1), "BANKNIFTY", 56200.0),
            MarketRecord.spot(LocalTime.of(15, 33, 0), "BANKNIFTY", 56300.0),    // after the close
            "S,broken",
        )
        val b = DayRecap.indexFromRecorder(Market.BANKNIFTY, lines, 56000.0)!!
        assertEquals(DayRecap.Index(Market.BANKNIFTY, 56100.0, 56400.0, 55900.0, 56200.0, 56000.0, recorder = true), b)
        assertTrue(DayRecap.indexLine(b).endsWith("- from the recorder's minute readings."), DayRecap.indexLine(b))
        assertNull(DayRecap.indexFromRecorder(Market.FINNIFTY, lines, null))
    }

    private val recorded = listOf(
        MarketRecord.header(TUE),
        MarketRecord.news(MarketRecord.News(LocalTime.of(9, 40, 0), "Reuters", null, "RBI keeps the repo rate unchanged", "https://x/1", -0.2, "negative", listOf("NIFTY"), true)),
        MarketRecord.news(MarketRecord.News(LocalTime.of(8, 50, 0), "ET", null, "Markets set for a flat start", "https://x/2", 0.0, "neutral", emptyList(), false)),
        MarketRecord.news(MarketRecord.News(LocalTime.of(13, 5, 0), "Mint", null, "Banks lead the afternoon rally", "https://x/3", 0.4, "positive", listOf("BANKNIFTY"), true)),
        MarketRecord.news(MarketRecord.News(LocalTime.of(14, 0, 0), "BS", null, "A".repeat(120), "https://x/4", 0.0, "neutral", emptyList(), false)),
        MarketRecord.event(MarketRecord.Event(TUE, LocalTime.of(10, 0), "RBI policy", false)),
        MarketRecord.event(MarketRecord.Event(TODAY, null, "Something tomorrow", false)),
        MarketRecord.flow("06-Oct-2026", "FII", 9000.0, 10234.5, -1234.5),
        MarketRecord.flow("06-Oct-2026", "DII", 8000.0, 6000.0, 2000.0),
        MarketRecord.flow("05-Oct-2026", "FII", 1.0, 2.0, -1.0),
    )

    @Test fun theRecordersEventsHeadlinesAndFlowsOfThatDay() {
        assertEquals(4, DayRecap.headlines(recorded).size)
        assertEquals(listOf(Events.Event(TUE, "RBI policy")), DayRecap.eventsOf(recorded, TUE))
        val flows = DayRecap.flowsFor(recorded, TUE)
        assertEquals(listOf("FII" to -1234.5, "DII" to 2000.0), flows.map { it.who to it.net })
        assertEquals(listOf(-1.0), DayRecap.flowsFor(recorded, MON).map { it.net })
        assertTrue(DayRecap.flowsFor(recorded, THU).isEmpty())
        // NSE's own date spelled another way still reads; a row of no day never does.
        assertEquals(1, DayRecap.flowsFor(listOf("X,2026-10-01,FII,1,2,-1", "X,,DII,1,2,3"), THU).size)
    }

    // ---- the words ---------------------------------------------------------------------------------------------------

    private fun t(source: String, h: Int, m: Int, entry: Double, exit: Double, why: String, day: LocalDate = TUE): BotTrades.Trade {
        val at = LocalDateTime.of(day, LocalTime.of(h, m))
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", if (source.endsWith("fin")) "PE" else "CE", 35, entry, at, at.minusMinutes(5),
            exit, at.plusMinutes(15), why, 60.0, false, lot = 35)
    }

    private val liq = LiquidityRecord.rows(listOf(
        t("liquidity15", 10, 42, 300.0, 330.0, "next_liquidity"),
        t("liquidity5_fin", 13, 5, 100.0, 90.0, "index_stop"),
        t("liquidity15", 14, 0, 300.0, 302.0, "time_stop"),
        t("liquidity15", 11, 0, 300.0, 310.0, "time_stop", day = MON),
    ))

    private fun full(locked: Boolean = false) = DayRecap.Facts(
        day = TUE, today = TODAY,
        indices = listOf(DayRecap.index(Market.NIFTY, nifty, TUE)!!, DayRecap.Index(Market.BANKNIFTY, 56100.0, 56400.0, 55900.0, 56200.0, 56000.0, recorder = true)),
        events = DayRecap.eventsOf(recorded, TUE) + Events.Event(TUE, "rbi policy") + Events.Event(TUE, "My own note", owner = true),
        news = DayRecap.headlines(recorded), flows = DayRecap.flowsFor(recorded, TUE),
        liquidity = liq.filter { it.day == TUE }, liquidityFrom = MON,
        solo = listOf(DayRecap.Paper("NIFTY26OCT25200CE", LocalTime.of(12, 1), 1200.0, "target")), soloFrom = MON,
        hero = emptyList(), heroFrom = null, locked = locked,
    )

    @Test fun theRecapOfADay() {
        val l = DayRecap.lines(full())
        assertEquals("Recap of Tue 6 Oct, Boss:", l[0])
        assertTrue(l[1].startsWith("Nifty: open 25,100.00 (gap up 100.00, +0.40%)"), l[1])
        assertTrue(l[2].startsWith("BankNifty: open 56,100.00") && l[2].endsWith("from the recorder's minute readings."), l[2])
        // Events once each (Boss's own named as his), the headlines that mattered first (in time order), the FII/DII figures.
        assertEquals("Events that day: RBI policy; My own note (added by you).", l[3])
        assertEquals("Headlines that day (4 recorded): 08:50 Markets set for a flat start; 09:40 RBI keeps the repo rate unchanged; " +
            "13:05 Banks lead the afternoon rally.", l[4])
        assertTrue(l[5].startsWith("Institutional flows (06-Oct-2026, NSE): FIIs net -Rs 1,235 crore, DIIs net +Rs 2,000 crore"), l[5])
        assertEquals("Not kept on the phone for that day: candles for FinNifty.", l[6])
        assertEquals("Liquidity 15+5 (paper): 3 trades, 2 won and 1 lost, net +Rs 590 a lot after charges (+Rs 590 in all); " +
            "best 10:42 BankNifty 15-min CE +Rs 990 a lot (next liquidity level), worst 13:05 FinNifty 5-min PE -Rs 410 a lot (index stop).", l[7])
        assertEquals("Solo (midday, paper): NIFTY26OCT25200CE bought at 12:01, net +Rs 1,200 after charges (target).", l[8])
        assertEquals("Hero (expiry, paper): no trade that day - its paper book holds no trades yet.", l[9])
        assertEquals(10, l.size)
        assertTrue(l.none { ACTS.containsMatchIn(it) }, l.joinToString("\n"))
    }

    @Test fun onALockedPhoneTheArmsAreLeftOut() {
        val l = DayRecap.lines(full(locked = true))
        assertEquals(DayRecap.LOCKED_ARMS, l.last())
        assertTrue(l.none { it.startsWith("Liquidity") || it.startsWith("Solo") || it.startsWith("Hero") || it.contains("Rs 990") }, l.joinToString("\n"))
        assertTrue(l.any { it.startsWith("Nifty:") })
        // Boss's own events are his: left out too.
        assertTrue(l.none { it.contains("My own note") })
    }

    @Test fun whatIsNotKeptIsSaidPlainly() {
        val bare = DayRecap.Facts(day = TUE, today = TODAY)
        val l = DayRecap.lines(bare)
        assertEquals("No candles kept for that day on the phone - nor the recorder's index readings.", l[1])
        assertEquals("Not kept on the phone for that day: headlines, FII/DII figures.", l[2])
        assertEquals("Liquidity 15+5 (paper): its book could not be read just now.", l[3])
        assertEquals("Solo (midday, paper): its record could not be read just now.", l[4])
        assertEquals("Hero (expiry, paper): its book could not be read just now.", l[5])
        // A day before an arm's record began, and a day it simply did not trade.
        val f = bare.copy(liquidity = emptyList(), liquidityFrom = TODAY, solo = emptyList(), soloFrom = MON, hero = emptyList(), heroFrom = THU)
        assertEquals("Liquidity 15+5 (paper): no trade that day - its paper book starts on Wed 7 Oct.", DayRecap.liquidityLine(f))
        assertEquals("Solo (midday, paper): no trade that day - why it passed is kept only on the day itself.", DayRecap.soloLine(f))
        assertEquals("Hero (expiry, paper): no trade that day.", DayRecap.heroLine(f))
        // One trade, an open one, Hero's exit in its words.
        val one = bare.copy(liquidity = liq.filter { it.day == MON })
        assertEquals("Liquidity 15+5 (paper): 1 trade - 11:00 BankNifty 15-min CE +Rs 290 a lot after charges (time stop).", DayRecap.liquidityLine(one))
        assertEquals("Solo (midday, paper): NIFTY26OCT25200PE bought at 12:02, still open.",
            DayRecap.soloLine(bare.copy(solo = listOf(DayRecap.Paper("NIFTY26OCT25200PE", LocalTime.of(12, 2), null)))))
        assertEquals("Hero (expiry, paper): NIFTY26OCT25300CE bought at 13:42, net -Rs 1,500 after charges (the -60% stop).",
            DayRecap.heroLine(bare.copy(hero = listOf(DayRecap.Paper("NIFTY26OCT25300CE", LocalTime.of(13, 42), -1500.0, "hero_stop")))))
    }

    @Test fun yesterdayRolledBackSaysWhy() {
        val l = DayRecap.lines(DayRecap.Facts(day = THU, today = MON, from = LocalDate.of(2026, 10, 4), fromWhy = "a weekend"))
        assertEquals("Sun 4 Oct was a weekend, so here is the session before it, Thu 1 Oct, Boss:", l[0])
    }

    @Test fun todayPointsToTheDaysOwnAnswers() {
        val f = DayRecap.Facts(day = TODAY, today = TODAY, partial = true, indices = listOf(DayRecap.index(Market.NIFTY, nifty, TODAY)!!),
            liquidity = liq, solo = emptyList(), hero = emptyList())
        val l = DayRecap.lines(f)
        assertEquals("That's today, Boss - here is the market so far (Wed 7 Oct):", l[0])
        assertTrue(l[1].contains("last 25,255.00") && l[1].contains("so far"), l[1])
        assertTrue(l.last().startsWith("For the rest of today ask \"what happened in the market today\""), l.last())
        assertTrue(l.none { it.startsWith("Liquidity") || it.startsWith("Not kept") }, l.joinToString("\n"))
        assertEquals("No candles of today on the phone yet.", DayRecap.lines(DayRecap.Facts(day = TODAY, today = TODAY, partial = true))[1])
        assertTrue(DayRecap.lines(f.copy(partial = false))[0].startsWith("That's today, Boss - here is how the market went"))
        // Each answer pointed to is the day's own.
        val audit = CoverageTest()
        assertEquals(listOf("MarketStory", "LiquidityWhyNot", "SoloDay", "HeroDay"), DayRecap.TODAY_ASKS.map { audit.feature(it) })
    }

    @Test fun headlinesAreCutAndCounted() {
        val many = (0 until 6).map { i -> NewsImpact.Seen(LocalTime.of(10, i), "X", null, "neutral", emptyList(), false, "Story $i " + "word ".repeat(30), "l$i") }
        val l = DayRecap.lines(DayRecap.Facts(day = TUE, today = TODAY, news = many)).first { it.startsWith("Headlines") }
        assertTrue(l.startsWith("Headlines that day (6 recorded): 10:00 Story 0"), l)
        assertTrue(l.contains("..."), l)
        assertEquals(DayRecap.HEADLINES, l.split("; ").size)
    }

    @Test fun neverSaysTheSameDayTwiceOrActs() {
        for (s in listOf("what happened on 3 oct", "recap of yesterday", "how was monday", "kal kya hua")) {
            val p = Ask.parse(s)
            assertNull(p.order, s); assertNull(p.command, s); assertFalse(Bundle.acts(s), s)
        }
    }
}
