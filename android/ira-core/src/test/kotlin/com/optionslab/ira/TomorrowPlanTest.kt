package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tomorrow's plan: the next session, Liquidity's day and levels, Solo and Hero, the events and cues - each only when known. */
class TomorrowPlanTest {
    private val tue: LocalDate = LocalDate.of(2026, 10, 6)
    private val wed: LocalDate = tue.plusDays(1)

    private fun lvl(kind: String, side: Int, edge: Double, swing: Boolean = false) = LiquidityMap.Level(kind, side, edge, swing)
    private fun side(side: Int, nearest: LiquidityMap.Level?) = LiquidityMap.Side(side, null, nearest, null, null, false)
    private fun read(u: String, minutes: Int, price: Double, above: LiquidityMap.Level?, below: LiquidityMap.Level?, at: LocalDateTime = tue.atTime(15, 29)) =
        LiquidityMap.Read(u, minutes, LiquidityMap.State.OK, price, at, at, side(1, above), side(-1, below), false, 60)

    private val reads = listOf(
        read("FINNIFTY", 30, 26_010.0, lvl("pool", 1, 26_050.0), lvl("swing", -1, 25_940.0)),
        read("BANKNIFTY", 15, 54_120.0, lvl("pool", 1, 54_180.0, swing = true), lvl("swing", -1, 53_900.0)),
        read("BANKNIFTY", 5, 54_120.0, lvl("pool", 1, 54_240.0), lvl("pool", -1, 54_000.0, swing = true)),
        read("FINNIFTY", 5, 26_010.0, null, lvl("pool", -1, 26_020.0)),   // already past (its bar not closed): not untaken
        LiquidityMap.Read("BANKNIFTY", 60, LiquidityMap.State.LOADING, 54_000.0, tue.atTime(15, 0)),
    )

    private val full = TomorrowPlan.Facts(
        today = tue, next = wed, expiries = listOf(Market.NIFTY), expiriesKnown = true,
        liquidity = TomorrowPlan.Day(2, 1, 1240.0), levels = TomorrowPlan.levels(reads), armed = true, lots = 2,
        soloOn = true, soloTrades = 12, heroArmed = false,
        events = listOf(Events.Event(wed, "NIFTY expiry"), Events.Event(wed, "RBI policy", owner = true), Events.Event(wed.plusDays(1), "Later")),
        fii = "FIIs are net short index futures, 8% long (NSE participant OI, 5 Oct)",
        flows = listOf(Flows.Flow("FII", "06-Oct-2026", 10_000.0, 12_000.0, -2_000.0), Flows.Flow("DII", "06-Oct-2026", 9_000.0, 6_500.0, 2_500.0)),
        bigMove = BigMoveRisk.Read(Market.NIFTY, BigMoveRisk.Level.NORMAL, 1.14, emptyList()),
        lesson = "Liquidity 15+5 in research terms: 2 trades (1 won, 1 lost), both normal; nothing unusual.",
    )

    @Test fun theLevelsAreTheNearestUntakenOfEachIndex() {
        val ls = TomorrowPlan.levels(reads)
        assertEquals(listOf("BANKNIFTY", "FINNIFTY"), ls.map { it.underlying })
        val bank = ls[0]
        assertEquals(54_120.0, bank.close)
        assertEquals(TomorrowPlan.Level(54_180.0, 60.0, "pool on a swing high", 15), bank.above)
        assertEquals(TomorrowPlan.Level(54_000.0, 120.0, "pool on a swing low", 5), bank.below)
        val fin = ls[1]
        assertEquals(40.0, fin.above!!.distance)
        // FinNifty's 5-min pool at 26,020 is above the 26,010 close on its "below" side: already past, so the 30-min swing low.
        assertEquals(TomorrowPlan.Level(25_940.0, 70.0, "swing low", 30), fin.below)
        assertEquals("BankNifty at the close 54,120 - above 54,180 (pool on a swing high, 15-min) 60 pts away; below 54,000 (pool on a swing low, 5-min) 120 pts away.",
            TomorrowPlan.levelLine(bank))
        assertEquals("FinNifty at the close 1,000 - nothing untaken above read; below 950 (swing low, 5-min) 50 pts away.",
            TomorrowPlan.levelLine(TomorrowPlan.Levels("FINNIFTY", 1_000.0, null, TomorrowPlan.Level(950.0, 50.0, "swing low", 5))))
        // Nothing read, or only books still loading: no line.
        assertTrue(TomorrowPlan.levels(emptyList()).isEmpty())
        assertTrue(TomorrowPlan.levels(listOf(LiquidityMap.Read("BANKNIFTY", 5, LiquidityMap.State.NO_DATA))).isEmpty())
        // The newest price of the books is the close.
        val two = TomorrowPlan.levels(listOf(read("BANKNIFTY", 15, 100.0, null, null, tue.atTime(15, 20)), read("BANKNIFTY", 5, 110.0, null, null)))
        assertEquals(110.0, two.single().close)
    }

    @Test fun theWholePlan() {
        assertEquals(listOf(
            "Tomorrow's plan, Boss - next session tomorrow, Wed 7 Oct.",
            "Expiry that day: Nifty.",
            "Liquidity 15+5 today on paper: 2 trades, 1 won, net Rs 1,240 after charges.",
            "Liquidity 15+5 in research terms: 2 trades (1 won, 1 lost), both normal; nothing unusual.",
            "BankNifty at the close 54,120 - above 54,180 (pool on a swing high, 15-min) 60 pts away; below 54,000 (pool on a swing low, 5-min) 120 pts away.",
            "FinNifty at the close 26,010 - above 26,050 (pool, 30-min) 40 pts away; below 25,940 (swing low, 30-min) 70 pts away.",
            "Liquidity 15+5's switch is on, 2 lots a trade: it watches its levels from 09:20 tomorrow.",
            "Solo (midday, paper): on - 12 of 60 forward-test trades.",
            "Hero (expiry, paper): the next session is a Nifty expiry - its kind of day, but it is switched off.",
            "Events: RBI policy (added by you).",
            "FIIs are net short index futures, 8% long (NSE participant OI, 5 Oct).",
            "Institutional flows (06-Oct-2026, NSE): FIIs net -Rs 2,000 crore, DIIs net +Rs 2,500 crore.",
            "Big-move risk at today's close (Nifty): normal, about 1.1x the usual - direction can't be told from this; tomorrow's 09:15 candle is the day's riskiest.",
            TomorrowPlan.GIFT,
            TomorrowPlan.NOTHING,
        ), TomorrowPlan.lines(full))
        assertEquals(TomorrowPlan.lines(full).joinToString("\n"), TomorrowPlan.say(full))
    }

    @Test fun briefKeepsTheDateTheArmAndTheEvents() {
        assertEquals(listOf(
            "Tomorrow's plan, Boss - next session tomorrow, Wed 7 Oct.",
            "Expiry that day: Nifty.",
            "Liquidity 15+5 today on paper: 2 trades, 1 won, net Rs 1,240 after charges.",
            "Liquidity 15+5's switch is on, 2 lots a trade: it watches its levels from 09:20 tomorrow.",
            "Events: RBI policy (added by you).",
            TomorrowPlan.GIFT, TomorrowPlan.NOTHING,
        ), TomorrowPlan.lines(full, brief = true))
    }

    @Test fun eachLineOnlyWhenItsDataIsThere() {
        val bare = TomorrowPlan.lines(TomorrowPlan.Facts(tue, wed))
        assertEquals(listOf("Tomorrow's plan, Boss - next session tomorrow, Wed 7 Oct.", TomorrowPlan.GIFT, TomorrowPlan.NOTHING), bare)
        // No contract list: neither the expiries nor Hero's day.
        val noList = TomorrowPlan.lines(full.copy(expiriesKnown = false)).joinToString("\n")
        assertFalse(noList.contains("Expiry") || noList.contains("Hero"), noList)
        // Known and none: said.
        val none = TomorrowPlan.lines(full.copy(expiries = emptyList()))
        assertTrue("No index expiry that day." in none)
        assertTrue("Hero (expiry, paper): not a Nifty expiry, so it sits the next session out." in none)
        assertTrue(TomorrowPlan.lines(full.copy(heroArmed = true)).contains("Hero (expiry, paper): the next session is a Nifty expiry - its kind of day and it is armed."))
        assertTrue(TomorrowPlan.lines(full.copy(heroArmed = null)).contains("Hero (expiry, paper): the next session is a Nifty expiry - its kind of day."))
        // No paper trade today; a loss; the arm off; size unknown; lots only.
        assertTrue("Liquidity 15+5 today: no paper trade." in TomorrowPlan.lines(full.copy(liquidity = TomorrowPlan.Day(0, 0, 0.0))))
        assertTrue("Liquidity 15+5 today on paper: 1 trade, 0 won, net -Rs 820 after charges." in TomorrowPlan.lines(full.copy(liquidity = TomorrowPlan.Day(1, 0, -820.4))))
        assertTrue("Liquidity 15+5's switch is off (1 lot a trade when on): it won't trade tomorrow unless you arm it." in TomorrowPlan.lines(full.copy(armed = false, lots = 1)))
        assertTrue("Liquidity 15+5's switch is on: it watches its levels from 09:20 tomorrow." in TomorrowPlan.lines(full.copy(lots = null)))
        assertTrue("Liquidity 15+5 trades 3 lots a trade." in TomorrowPlan.lines(full.copy(armed = null, lots = 3)))
        assertFalse(TomorrowPlan.lines(full.copy(armed = null, lots = null)).any { it.contains("15+5's switch") || it.contains("trades 3") })
        // Solo off, past its 60; unknown.
        assertTrue("Solo (midday, paper): off - 60 of 60 forward-test trades (64 in all)." in TomorrowPlan.lines(full.copy(soloOn = false, soloTrades = 64)))
        assertTrue("Solo (midday, paper): on." in TomorrowPlan.lines(full.copy(soloTrades = null)))
        assertFalse(TomorrowPlan.lines(full.copy(soloOn = null)).any { it.startsWith("Solo") })
        // A big-move read that could not be made, no flows (or only another kind), no events after expiries: left out.
        val quiet = TomorrowPlan.lines(full.copy(bigMove = BigMoveRisk.Read(Market.NIFTY, null, null, emptyList(), unknown = "no"),
            flows = listOf(Flows.Flow("PRO", "x", 0.0, 0.0, 1.0)), events = listOf(Events.Event(wed, "BANKNIFTY expiry")), fii = null, lesson = null))
        assertFalse(quiet.any { it.startsWith("Big-move") || it.startsWith("Institutional") || it.startsWith("Events") || it.startsWith("FIIs") || it.contains("research terms") }, quiet.toString())
        // A line already ending in a full stop keeps one.
        assertTrue("FII line." in TomorrowPlan.lines(full.copy(fii = "FII line.")))
        // No next session known.
        assertEquals("I couldn't tell the next trading day just now, Boss, so there's no plan for it yet.", TomorrowPlan.say(TomorrowPlan.Facts(tue, null)))
    }

    @Test fun theNextSessionSkipsWeekendsAndNamedHolidays() {
        val fri = LocalDate.of(2026, 10, 9)
        assertEquals("tomorrow, Wed 7 Oct", TomorrowPlan.nextWords(tue, wed, emptyList()))
        assertEquals("Mon 12 Oct (after the weekend)", TomorrowPlan.nextWords(fri, fri.plusDays(3), emptyList()))
        assertEquals("Fri 9 Oct (Thu 8 Oct is a holiday: Dussehra)", TomorrowPlan.nextWords(wed, fri, listOf(LocalDate.of(2026, 10, 8) to "Dussehra", fri.plusDays(10) to "Later")))
        assertEquals("Tue 13 Oct (after the weekend; Mon 12 Oct is a holiday: A)",
            TomorrowPlan.nextWords(fri, fri.plusDays(4), listOf(fri.plusDays(3) to "A")))
        assertEquals("Thu 8 Oct", TomorrowPlan.nextWords(tue, wed.plusDays(1), emptyList()))
        // An event on a skipped day is said with its date; one after the next session is not.
        val f = TomorrowPlan.Facts(fri, fri.plusDays(3), events = listOf(Events.Event(fri.plusDays(1), "US data"), Events.Event(fri.plusDays(3), "CPI"),
            Events.Event(fri.plusDays(4), "Not yet"), Events.Event(fri, "Today's")))
        assertTrue("Events: Sat 10 Oct: US data; CPI." in TomorrowPlan.lines(f), TomorrowPlan.lines(f).toString())
    }

    @Test fun todaysPaperResultFromTheArmsBook() {
        fun t(source: String, entry: Double, exit: Double?, at: LocalDateTime, live: Boolean = false, charges: Double = 40.0) =
            BotTrades.Trade(source, "BANKNIFTY26OCT54100CE", "CE", 30, entry, at.minusMinutes(10), at.minusMinutes(11), exit, exit?.let { at }, "target", charges, live)
        val rows = LiquidityRecord.rows(listOf(
            t("liquidity15", 100.0, 120.0, tue.atTime(10, 30)),          // +600 - 40
            t("liquidity5_fin", 100.0, 90.0, tue.atTime(11, 30)),         // -300 - 40
            t("liquidity5", 100.0, 150.0, tue.minusDays(1).atTime(11, 0)),  // yesterday's
            t("liquidity15", 100.0, 150.0, tue.atTime(12, 0), live = true),  // Zerodha, not paper
            t("liquidity15", 100.0, null, tue.atTime(13, 0)),                // still open
            t("orb", 100.0, 150.0, tue.atTime(12, 0)),                     // another arm
        ))
        assertEquals(TomorrowPlan.Day(2, 1, 220.0), TomorrowPlan.dayOf(rows, tue))
        assertEquals(TomorrowPlan.Day(0, 0, 0.0), TomorrowPlan.dayOf(rows, wed))
    }

    @Test fun dueOnceAfterTheCloseOnATradingDay() {
        assertFalse(TomorrowPlan.due(tue.atTime(15, 44), true, null))
        assertTrue(TomorrowPlan.due(tue.atTime(15, 45), true, null))
        assertTrue(TomorrowPlan.due(tue.atTime(21, 59), true, tue.minusDays(1)))
        assertFalse(TomorrowPlan.due(tue.atTime(22, 0), true, null))
        assertFalse(TomorrowPlan.due(tue.atTime(16, 0), false, null))
        assertFalse(TomorrowPlan.due(tue.atTime(16, 0), true, tue))
    }

    @Test fun theRecordersFlowRows() {
        val lines = listOf("H,v1,2026-10-06", "X,05-Oct-2026,FII,1,2,-1", "X,05-Oct-2026,DII,3,1,2", "S,10:00:00,NIFTY,25000",
            "X,06-Oct-2026,DII,9000,6500,2500", "X,06-Oct-2026,FII,10000,12000,-2000", "X,06-Oct-2026,FII,bad", "X,\"06-Oct-2026\",PRO,1,1,x")
        val f = TomorrowPlan.flowsOf(lines)
        assertEquals(listOf("FII", "DII"), f.map { it.who })
        assertEquals(-2000.0, f[0].net); assertEquals(2500.0, f[1].net); assertEquals("06-Oct-2026", f[0].date)
        assertTrue(TomorrowPlan.flowsOf(listOf("N,x", "X,1,2")).isEmpty())
        assertTrue(TomorrowPlan.flowsOf(emptyList()).isEmpty())
        // As the recorder writes them.
        assertEquals(listOf(Flows.Flow("FII", "06-Oct-2026", 10.5, 2.0, 8.5)), TomorrowPlan.flowsOf(listOf(MarketRecord.flow("06-Oct-2026", "FII", 10.5, 2.0, 8.5))))
    }

    // ---- asked ----

    private val ASKED = listOf("what's the plan for tomorrow", "what is the plan for tomorrow", "whats the plan for tomorrow", "plan for tomorrow",
        "the plan for tomorrow", "tomorrow's plan", "what's tomorrow's plan", "tomorrow plan", "tomorrows game plan", "what's the game plan for tomorrow",
        "give me the plan for tomorrow", "jarvis what's the plan for tomorrow", "what's the plan for tomorrow boss", "my plan for tomorrow",
        "plan for the next session", "what's the plan for the next trading day", "tomorrow ka plan", "tomorrow ka plan kya hai", "kal ka plan",
        "kal ka plan kya hai", "kal ka plan batao", "kal ki taiyari", "kal ke liye plan", "kal ke liye kya plan", "mera kal ka plan", "plan kal ka",
        "prepare me for tomorrow", "prep me for tomorrow", "get me ready for tomorrow", "brief me for tomorrow", "can you prepare me for tomorrow",
        "prepare me for the next session", "how should i prepare for tomorrow", "help me prepare for tomorrow", "how do i get ready for tomorrow",
        "Jarvis, what's the plan for tomorrow?", "What’s the plan for tomorrow?", "tomorrow's prep", "briefing for tomorrow")
    private val NOT_ASKED = listOf("what's the plan for today", "plan for the day", "morning plan", "today's plan", "aaj ka plan", "what's your plan for tomorrow",
        "what's the plan for nifty tomorrow", "banknifty plan for tomorrow", "outlook for tomorrow", "how does tomorrow look", "what do i need to do before tomorrow",
        "checklist for tomorrow", "remind me of the plan tomorrow", "plan to buy nifty tomorrow", "plan for the day after tomorrow", "weekly review",
        "how did liquidity do", "start all strategies tomorrow", "what's the trade plan for tomorrow", "is tomorrow a holiday", "what expires tomorrow",
        "plan my trip for tomorrow", "what should i trade tomorrow", "")

    @Test fun theQuestion() {
        for (s in ASKED) assertTrue(TomorrowPlan.asked(s), s)
        for (s in NOT_ASKED) assertFalse(TomorrowPlan.asked(s), s)
    }

    @Test fun theQuestionNeverActs() {
        for (s in ASKED) {
            val p = Ask.parse(s)
            assertNull(p.order, s); assertNull(p.command, s)
        }
    }
}
