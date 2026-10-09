package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayGlanceTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)   // a Tuesday
    private fun at(h: Int, m: Int = 0): LocalDateTime = day.atTime(h, m)

    // ---- the market line -------------------------------------------------------------------------------------------

    @Test fun phases() {
        assertEquals(TodayGlance.Phase.PRE_OPEN, TodayGlance.phase(at(8, 50), true))
        assertEquals(TodayGlance.Phase.OPEN, TodayGlance.phase(at(9, 15), true))
        assertEquals(TodayGlance.Phase.OPEN, TodayGlance.phase(at(15, 29), true))
        // F&O trades to 15:40 since 3 Aug 2026 (15:30 before).
        assertEquals(TodayGlance.Phase.OPEN, TodayGlance.phase(at(15, 39), true))
        assertEquals(TodayGlance.Phase.CLOSED, TodayGlance.phase(at(15, 40), true))
        assertEquals(TodayGlance.Phase.CLOSED, TodayGlance.phase(LocalDate.of(2026, 7, 31).atTime(15, 30), true))
        assertEquals(TodayGlance.Phase.OPEN, TodayGlance.phase(LocalDate.of(2026, 7, 31).atTime(15, 29), true))
        assertEquals(TodayGlance.Phase.CLOSED, TodayGlance.phase(at(10, 0), false))
    }

    @Test fun marketLines() {
        assertEquals("Pre-open · opens at 09:15 (in 25m)", TodayGlance.marketLine(at(8, 50), true, null))
        assertEquals("Market open · closes at 15:40 (5h 30m left)", TodayGlance.marketLine(at(10, 10), true, null))
        assertEquals("Market open · closes at 15:40 (1h 10m left)", TodayGlance.marketLine(at(14, 30), true, null))
        assertEquals("Market open · closes at 15:40 (5m left) · index closed 15:30", TodayGlance.marketLine(at(15, 35), true, null))
        assertEquals("Market open · closes at 15:30 (1h left)", TodayGlance.marketLine(LocalDate.of(2026, 7, 31).atTime(14, 30), true, null))
        assertEquals("Market closed · next session tomorrow, 09:15", TodayGlance.marketLine(at(16, 0), true, day.plusDays(1)))
        assertEquals("Market closed · next session Mon 12 Oct, 09:15", TodayGlance.marketLine(at(16, 0), true, LocalDate.of(2026, 10, 12)))
        assertEquals("Market closed", TodayGlance.marketLine(at(11, 0), false, null))
    }

    @Test fun expiryAndHolidayNotices() {
        assertEquals("Expiry today: NIFTY, SENSEX", TodayGlance.expiryLine(listOf("NIFTY", "SENSEX", "NIFTY"), true))
        assertNull(TodayGlance.expiryLine(emptyList(), true))
        assertNull(TodayGlance.expiryLine(listOf("NIFTY"), false))
        val hols = listOf(LocalDate.of(2026, 10, 20) to "Dussehra", LocalDate.of(2026, 10, 9) to "Test holiday")
        assertEquals("Holiday Fri 9 Oct: Test holiday - market shut", TodayGlance.holidayLine(day, hols))
        assertEquals("Holiday today: Dussehra", TodayGlance.holidayLine(LocalDate.of(2026, 10, 20), hols))
        assertNull(TodayGlance.holidayLine(day, listOf(LocalDate.of(2026, 10, 20) to "Dussehra")))
    }

    // ---- the cues ----------------------------------------------------------------------------------------------------

    @Test fun giftLineWithAndWithoutAReading() {
        assertTrue(TodayGlance.giftLine(null, null, null, at(8, 0)).startsWith("GIFT Nifty: no reading"))
        val g = RecorderFeeds.Gift("GIFTNIFTY", null, 25_100.0, null, null, null, at(7, 45))
        val prev = MorningCues.Close(day.minusDays(1), 25_000.0)
        assertEquals("GIFT Nifty points to a gap-up of ~100 pts (+0.40%), read at 07:45 today.", TodayGlance.giftLine(g, at(7, 46), prev, at(8, 0)))
    }

    @Test fun fiiLineFreshOldOrNone() {
        val fresh = MorningCues.Fii(day.minusDays(1), 30_000, 70_000, 0, 0, 0, 0)
        assertTrue(TodayGlance.fiiLine(fresh, null, day).startsWith("FIIs are net short index futures, 30% long"))
        val old = fresh.copy(date = day.minusDays(20))
        assertEquals("FII positioning: the newest NSE participant file on the phone is from 16 Sep - too old to read as today's.", TodayGlance.fiiLine(old, null, day))
        assertTrue(TodayGlance.fiiLine(null, null, day).startsWith("FII positioning: no NSE participant file"))
    }

    // ---- the risk badges ---------------------------------------------------------------------------------------------

    @Test fun badges() {
        val r = BigMoveRisk.Read(Market.NIFTY, BigMoveRisk.Level.HIGH, 2.54, listOf("x"), asOf = at(10, 9))
        assertEquals(TodayGlance.Badge("Nifty", "high · 2.5x", BigMoveRisk.Level.HIGH), TodayGlance.badge(r, at(10, 10)))
        assertEquals("high · 2.5x (as of 09:50)", TodayGlance.badge(r.copy(asOf = at(9, 50)), at(10, 10)).word)
        val unknown = BigMoveRisk.Read(Market.BANKNIFTY, null, null, emptyList(), unknown = "too few")
        assertEquals(TodayGlance.Badge("BankNifty", "not known yet", null), TodayGlance.badge(unknown, at(10, 10)))
        assertTrue(TodayGlance.RISK_NOTE.contains(BigMoveRisk.CAVEAT))
    }

    // ---- the strategies ----------------------------------------------------------------------------------------------

    @Test fun liquidityRow() {
        val off = TodayGlance.liquidity(TodayGlance.Liquidity(false, 2, 0, null, null))
        assertEquals("off", off.state); assertEquals(listOf("Today: no trades"), off.lines); assertEquals(TodayGlance.TO_STRATEGIES, off.to)
        val on = TodayGlance.liquidity(TodayGlance.Liquidity(true, 2, 2, 1_240.4, TodayGlance.Open("BANKNIFTY26OCT52000PE", 210.5, 180.0)))
        assertEquals("armed · 2 lots", on.state)
        assertEquals(listOf("Today: 2 trades, +₹1,240", "Open BANKNIFTY26OCT52000PE · bought 210.50 · stop 180.00"), on.lines)
        val one = TodayGlance.liquidity(TodayGlance.Liquidity(true, 1, 1, -300.0, TodayGlance.Open("X", 100.0, null)))
        assertEquals("armed · 1 lot", one.state)
        assertEquals(listOf("Today: 1 trade, −₹300", "Open X · bought 100.00 · no stop yet"), one.lines)
    }

    @Test fun soloRow() {
        val s = TodayGlance.Solo(true, null, 12)
        assertEquals("on · 12 of 60", TodayGlance.solo(s, at(10, 0), true).state)
        assertEquals(listOf("Decides at 12:00"), TodayGlance.solo(s, at(10, 0), true).lines)
        assertEquals(listOf("No trade today"), TodayGlance.solo(s, at(12, 30), true).lines)
        assertEquals(listOf("No session today"), TodayGlance.solo(s, at(10, 0), false).lines)
        assertEquals(listOf("Today: NIFTY26OCT25000CE · open"), TodayGlance.solo(s.copy(today = TodayGlance.SoloTrade("NIFTY26OCT25000CE", true, null)), at(13, 0), true).lines)
        assertEquals(listOf("Today: N · +₹450"), TodayGlance.solo(s.copy(today = TodayGlance.SoloTrade("N", false, 450.0)), at(15, 0), true).lines)
        val off = TodayGlance.solo(TodayGlance.Solo(false, null, 0), at(10, 0), true)
        assertEquals("off · 0 of 60", off.state); assertEquals(emptyList(), off.lines); assertEquals(TodayGlance.TO_SOLO, off.to)
        // With its net so far; switched off by itself (the forward test's bar): switching back on is Boss's.
        assertEquals("on · 12 of 60 · net +₹3,200", TodayGlance.solo(s.copy(net = 3_200.0), at(10, 0), true).state)
        assertEquals("off · 0 of 60", TodayGlance.solo(TodayGlance.Solo(false, null, 0, net = 0.0), at(10, 0), true).state)
        val selfOff = TodayGlance.solo(TodayGlance.Solo(false, null, 9, net = -26_000.0, switchedOff = true), at(10, 0), true)
        assertEquals("switched itself off · 9 of 60 · net −₹26,000", selfOff.state)
        assertEquals(listOf("Switching it back on is Boss's switch"), selfOff.lines)
    }

    @Test fun heroAndJarvisRows() {
        assertEquals(listOf("NIFTY expiry today · may trade 13:30-14:45"), TodayGlance.hero(TodayGlance.Hero(true, true), true).lines)
        assertEquals(listOf("Not a NIFTY expiry day · stays out"), TodayGlance.hero(TodayGlance.Hero(true, false), true).lines)
        assertEquals(listOf("Expiry not known (no contract list on the phone)"), TodayGlance.hero(TodayGlance.Hero(false, null), true).lines)
        assertEquals("off", TodayGlance.hero(TodayGlance.Hero(false, false), false).state)
        assertEquals(emptyList(), TodayGlance.hero(TodayGlance.Hero(false, false), false).lines)
        assertEquals("none open", TodayGlance.jarvis(0).state)
        assertEquals("2 open", TodayGlance.jarvis(2).state)
        assertEquals(TodayGlance.TO_JARVIS, TodayGlance.jarvis(2).to)
    }

    // ---- verdicts and events -----------------------------------------------------------------------------------------

    @Test fun verdicts() {
        val none = ForwardCheck.check(ForwardCheck.LIQUIDITY, emptyList())
        assertEquals("Liquidity 15+5: too few trades (<20) · 0 so far", TodayGlance.verdict(none))
        val many = ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 25).map { ForwardCheck.Trade(day.plusDays(it.toLong()), 228.0) })
        assertEquals("Liquidity 15+5: in line · 25 trades", TodayGlance.verdict(many))
        val bad = ForwardCheck.check(ForwardCheck.SOLO, (0 until 25).map { ForwardCheck.Trade(day.plusDays(it.toLong()), -3_000.0) })
        assertTrue(TodayGlance.verdict(bad).startsWith("Solo: below expectation (25 trades)"), TodayGlance.verdict(bad))
    }

    @Test fun eventsTodayAndTomorrowOnlyThreeAtMost() {
        val ev = listOf(
            Events.Event(day.plusDays(2), "Later"),
            Events.Event(day.plusDays(1), "US Fed decision overnight (FOMC)"),
            Events.Event(day, "NIFTY expiry"),
            Events.Event(day, "BANKNIFTY expiry"),
            Events.Event(day.plusDays(1), "RBI policy", owner = true),
        )
        assertEquals(listOf("Today: BANKNIFTY expiry", "Today: NIFTY expiry", "Tomorrow: RBI policy (added by you)"), TodayGlance.events(ev, day))
        assertEquals(emptyList(), TodayGlance.events(listOf(Events.Event(day.plusDays(3), "x")), day))
    }

    // ---- the whole card ----------------------------------------------------------------------------------------------

    private val facts = TodayGlance.Facts(
        now = at(8, 40), tradingToday = true, expiriesToday = listOf("NIFTY"),
        gift = "GIFT line.", fii = "FII line.",
        risks = listOf(BigMoveRisk.Read(Market.NIFTY, BigMoveRisk.Level.LOW, 0.4, emptyList())),
        liquidity = TodayGlance.Liquidity(true, 1, 0, null, null), solo = TodayGlance.Solo(true, null, 3),
        hero = TodayGlance.Hero(false, true), jarvisOpen = 1,
        forward = listOf(ForwardCheck.check(ForwardCheck.LIQUIDITY, emptyList())),
        events = listOf(Events.Event(day, "NIFTY expiry")),
    )

    @Test fun preOpenCardHasCuesNotRisks() {
        val c = TodayGlance.card(facts)
        assertEquals(TodayGlance.Phase.PRE_OPEN, c.phase)
        assertEquals(listOf("GIFT line.", "FII line."), c.cues)
        assertEquals(emptyList(), c.risks)
        assertEquals(listOf("Expiry today: NIFTY"), c.notices)
        assertEquals(listOf("Liquidity 15+5", "Solo (midday)", "Hero (expiry)", "Jarvis's own trades"), c.rows.map { it.title })
        assertEquals(listOf("Today: NIFTY expiry"), c.events)
        assertEquals("Updated 08:40", c.asOf)
    }

    @Test fun inSessionCardHasRisksNotCues() {
        val c = TodayGlance.card(facts.copy(now = at(11, 5)))
        assertEquals(TodayGlance.Phase.OPEN, c.phase)
        assertEquals(emptyList(), c.cues)
        assertEquals(listOf("low · 0.4x"), c.risks.map { it.word })
    }

    @Test fun closedDayCardHasNeither() {
        val c = TodayGlance.card(facts.copy(now = LocalDateTime.of(2026, 10, 10, 11, 0), tradingToday = false, nextSession = LocalDate.of(2026, 10, 12)))
        assertEquals(TodayGlance.Phase.CLOSED, c.phase)
        assertEquals("Market closed · next session Mon 12 Oct, 09:15", c.market)
        assertEquals(emptyList(), c.cues); assertEquals(emptyList(), c.risks)
        assertEquals(emptyList(), c.notices)   // no expiry on a day without a session
    }

    @Test fun wordsHelpers() {
        assertEquals("0m", TodayGlance.span(-3)); assertEquals("3h", TodayGlance.span(180)); assertEquals("1h 5m", TodayGlance.span(65))
        assertEquals("₹0", TodayGlance.signed(0.2)); assertEquals("+₹100,000", TodayGlance.signed(100_000.0))
    }
}
