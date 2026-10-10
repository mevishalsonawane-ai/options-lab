package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.SoloDay.Bought
import com.optionslab.ira.SoloDay.Facts
import com.optionslab.ira.SoloDay.Prefetch
import com.optionslab.ira.SoloDay.Q
import com.optionslab.ira.SoloDay.Record
import com.optionslab.ira.SoloDay.Trade
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloDayTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val noon = at(12, 0)

    // BANKNIFTY up 400 on an ATR of 500 (0.80), NIFTY up 120 on 200 (0.60), FINNIFTY only 0.30.
    private val bn = SoloMidday.Signal("BANKNIFTY", 1, 54_000.0, 54_400.0, 54_420.0, 53_950.0, 500.0, 54_410.0, noon)
    private val nf = SoloMidday.Signal("NIFTY", 1, 25_000.0, 25_120.0, 25_130.0, 24_980.0, 200.0, 25_125.0, noon)
    private val fnDown = SoloMidday.Signal("FINNIFTY", -1, 26_000.0, 25_880.0, 26_010.0, 25_870.0, 220.0, 25_878.0, noon)
    private fun sig(s: SoloMidday.Signal) = SoloMidday.Decision(s.underlying, s, "signal", s.strength, s.position)
    private val fnSmall = SoloMidday.Decision("FINNIFTY", null, "move_too_small", 0.3)
    private val nfMid = SoloMidday.Decision("NIFTY", null, "mid_range", 0.7, 0.4)
    private val nfNoAtr = SoloMidday.Decision("NIFTY", null, "no_atr")

    private val bnTrade = Trade(day, "BANKNIFTY", "BANKNIFTY26OCT54000CE", true, 30, 612.5, 166, 54_410.0, bn.stop, bn.target, 500.0,
        "BANKNIFTY is up 400 points from the open.")

    private fun check(vararg nets: Double, base: SoloMidday.Baseline = SoloMidday.Baseline()) = SoloMidday.judge(nets.toList(), base)

    private fun facts(now: LocalDateTime = at(13, 0), on: Boolean? = true, record: Record? = null, trades: List<Trade> = emptyList(),
                      prefetch: Prefetch? = null, since: LocalDateTime? = at(9, 0), tradingDay: Boolean = true, paused: String? = null,
                      earlier: Trade? = null, check: SoloMidday.Check? = check(1_000.0, -400.0), thin: Pair<Int, Int>? = 0 to 0) =
        Facts(now, tradingDay, on, paused, record, since, prefetch, trades, earlier, check, thin)

    private fun decidedRecord(vararg ds: SoloMidday.Decision, at: LocalDateTime = at(12, 1)): Record = SoloDay.decided(null, at, ds.toList())

    // ---- the question --------------------------------------------------------------------------------------------

    @Test fun theDaysQuestionIsAskedInEnglishAndHinglish() {
        for (s in listOf("what did solo do today", "What did Solo do today?", "why didn't solo trade", "why didn't solo trade today",
            "why hasn't solo traded today", "why no solo trade today", "why didn't solo buy anything today", "solo ne aaj kya kiya",
            "solo ne kya kiya", "solo ne trade kyu nahi liya", "solo ne aaj trade kyun nahi liya", "solo ne kaise decide kiya",
            "how did solo decide", "how did solo decide today", "how did solo choose", "explain solo's decision", "solo's decision today",
            "which index did solo pick", "why did solo pick banknifty", "why did solo skip nifty", "what did solo see at 12",
            "what is solo waiting for", "what will solo look at", "when will solo decide", "solo kya karega aaj", "did solo trade today",
            "what did solo buy today", "what has solo done today"))
            assertNotNull(SoloDay.asked(s), s)
    }

    @Test fun whyNotAndTheIndexNamedAreRead() {
        assertTrue(SoloDay.asked("why didn't solo trade")!!.why)
        assertTrue(SoloDay.asked("solo ne trade kyu nahi liya")!!.why)
        assertFalse(SoloDay.asked("what did solo do today")!!.why)
        assertEquals("BANKNIFTY", SoloDay.asked("why did solo pick banknifty")!!.market)
        assertEquals("NIFTY", SoloDay.asked("why did solo skip nifty")!!.market)
        assertNull(SoloDay.asked("how did solo decide")!!.market)
        // SENSEX is not one Solo reads: never the index focused on.
        assertNull(SoloDay.asked("why did solo skip sensex")?.market)
    }

    @Test fun neverTheQuestionsThatHaveTheirOwnAnswers() {
        for (s in listOf("how is solo doing", "solo on", "solo off", "is solo on", "switch on solo", "turn off solo", "should i switch off solo",
            "solo record", "solo forward test", "what is solo", "how did solo do today", "solo trades today", "why did solo exit",
            "why did solo lose today", "why didn't solo take that trade", "solo ne wo trade kyu nahi liya", "why didn't solo trade yesterday",
            "what did solo do last week", "why didn't liquidity trade", "why didn't orb trade", "what did you do today", "why no trade today",
            "solo ko band karo", "solo chalu karo", "how many lots does solo trade", "solo kal kya karega", "solo backtest", ""))
            assertNull(SoloDay.asked(s), s)
    }

    // ---- the record's steps ----------------------------------------------------------------------------------------

    @Test fun theRecordKeepsOneDayAndOneReasonAnIndex() {
        var r = SoloDay.held(null, at(12, 0), SoloDay.KILL_SWITCH)
        assertEquals(day, r.day)
        r = SoloDay.waited(r, at(12, 0), listOf("NIFTY"))
        r = SoloDay.decided(r, at(12, 1), listOf(sig(bn), sig(nf), fnSmall))
        r = SoloDay.passed(r, at(12, 1), "BANKNIFTY", "expiry_today")
        r = SoloDay.passed(r, at(12, 1), "BANKNIFTY", "no price for X")
        assertEquals(listOf("no price for X"), r.passes.map { it.why })
        assertEquals(SoloDay.KILL_SWITCH, r.held?.why)
        assertEquals(listOf("NIFTY"), r.waited?.second)
        assertEquals(3, r.decisions.size)
        repeat(20) { r = SoloDay.thin(r, at(12, 1), "NIFTY", "S$it", "wide spread") }
        assertEquals(SoloDay.MAX_THIN, r.thin.size)
        r = SoloDay.late(r, at(12, 4))
        assertEquals(at(12, 4), r.late)
        r = SoloDay.bought(r, Bought(at(12, 1), "NIFTY", "NIFTY26OCT24900CE", 24_900, 4, 210.0))
        assertEquals("NIFTY", r.bought?.underlying)
        // The next day's first note starts afresh.
        val next = SoloDay.passed(r, day.plusDays(1).atTime(12, 1), "NIFTY", "expiry_today")
        assertEquals(day.plusDays(1), next.day)
        assertNull(next.bought); assertTrue(next.decisions.isEmpty()); assertEquals(1, next.passes.size)
        assertEquals(r, SoloDay.of(r, day))
    }

    // ---- the answer ---------------------------------------------------------------------------------------------------

    @Test fun noSessionToday() {
        val a = SoloDay.answer(Q(), facts(tradingDay = false))
        assertTrue(a.startsWith("No session today"), a)
        assertTrue("Forward test:" in a && "178 trades" in a, a)
    }

    @Test fun beforeNoonItSaysWhatItWillReadAndItsEarlyReads() {
        val a = SoloDay.answer(Q(), facts(now = at(10, 30), prefetch = Prefetch(true, mapOf("NIFTY" to 210.0, "BANKNIFTY" to 480.0, "FINNIFTY" to 230.0), true)))
        assertTrue("is on today" in a, a)
        assertTrue("has not decided yet" in a && "between 12:00 and 12:03" in a, a)
        assertTrue("NIFTY, BANKNIFTY and FINNIFTY" in a && "SENSEX is not traded" in a, a)
        assertTrue("NIFTY 210 (it needs 105 points from the open)" in a, a)
        assertTrue("BANKNIFTY 480 (it needs 240 points" in a, a)
        assertTrue("the contracts are read" in a, a)
        assertFalse("read again at 12:00" in a, a)
        assertTrue("Forward test: 2 of 60 closed paper trades, net ₹600 (₹300 a trade)" in a, a)
        assertTrue("worst drawdown −₹400 from its start against the bar of −₹25,000" in a, a)
        assertTrue("Research, unseen period (Jul 2024 - Oct 2026) on NIFTY, BANKNIFTY and FINNIFTY: 178 trades, net ₹4,375, profit factor 1.02 - about break-even, not proven." in a, a)
    }

    @Test fun earlyReadsMissingOrNotStarted() {
        val part = SoloDay.answer(Q(), facts(now = at(11, 0), prefetch = Prefetch(true, mapOf("NIFTY" to 210.0), false)))
        assertTrue("BANKNIFTY and FINNIFTY not read yet" in part, part)
        assertTrue("the contracts are not read yet" in part && "Anything missing is read again at 12:00." in part, part)
        val none = SoloDay.answer(Q(), facts(now = at(11, 0), prefetch = Prefetch(false)))
        assertTrue("have not run yet" in none, none)
        val early = SoloDay.answer(Q(), facts(now = at(9, 5), prefetch = Prefetch(false)))
        assertTrue("start from 09:15" in early, early)
        assertNull(SoloDay.prefetchWords(null, at(10, 0)))
        assertTrue("no daily ATR read yet" in SoloDay.prefetchWords(Prefetch(true), at(10, 0))!!)
    }

    @Test fun offAndSwitchedItselfOff() {
        val off = SoloDay.answer(Q(), facts(now = at(10, 0), on = false))
        assertTrue("is off, Boss" in off && "will not look at anything at 12:00 today" in off, off)
        val paused = SoloDay.answer(Q(), facts(now = at(13, 0), on = false, paused = "Boss, Solo (midday) is −₹26,000 below its best..."))
        assertTrue("switched itself off on its forward test's bar" in paused && "your choice" in paused, paused)
        assertTrue("No 12:00 decision is on record today." in paused, paused)
        val unread = SoloDay.answer(Q(), facts(on = null))
        assertTrue("could not read Solo's switch" in unread, unread)
    }

    @Test fun theDecisionEachIndexAgainstTheOthersAndThePick() {
        val r = SoloDay.bought(decidedRecord(sig(bn), sig(nf), fnSmall), Bought(at(12, 1), "BANKNIFTY", bnTrade.symbol, 54_000, 4, 612.5))
        val a = SoloDay.answer(Q(), facts(now = at(12, 30), record = r, trades = listOf(bnTrade)))
        assertTrue("At 12:01 it read all three:" in a, a)
        assertTrue("BANKNIFTY up 400 points from the open, 0.80 x its daily ATR (500), closing in the top 4% of the morning's range - a signal, the strongest of the 2 that signalled" in a, a)
        assertTrue("NIFTY up 120 points from the open, 0.60 x its daily ATR (200)" in a && "number 2 of the 2 that signalled" in a, a)
        assertTrue("FINNIFTY moved only 0.30 ATR from the open (it needs 0.5)" in a, a)
        // The strongest first, the one with no signal last.
        assertTrue(a.indexOf("BANKNIFTY up") < a.indexOf("NIFTY up 120") && a.indexOf("NIFTY up 120") < a.indexOf("FINNIFTY moved"), a)
        assertTrue("It bought BANKNIFTY26OCT54000CE (4 in the money) on paper at ₹612.50 at 12:01." in a, a)
        assertTrue("Not taken as weaker (one Solo position at a time): NIFTY (0.60 ATR)." in a, a)
        assertTrue("Entry: BANKNIFTY at 54,410 (the 12:00 price). Stop: out on a 1-minute close at or below 54,260 (0.3 ATR)" in a, a)
        assertTrue("the stop moves to breakeven (54,410) once BANKNIFTY reaches 54,635" in a, a)
        assertTrue("the 2R mark is 54,710 (no fixed target); out at 14:30 at the latest." in a, a)
        assertTrue("It is still open" in a, a)
        assertFalse("It did trade today." in a, a)
        val why = SoloDay.answer(Q(why = true), facts(now = at(12, 30), record = r, trades = listOf(bnTrade)))
        assertTrue(why.startsWith("Solo (midday) is on today, Boss - paper only, not proven. It did trade today."), why)
    }

    @Test fun aPutsPlanReadsTheOtherWay() {
        val t = Trade(day, "FINNIFTY", "FINNIFTY26OCT26050PE", false, 65, 300.0, 165, 25_878.0, fnDown.stop, fnDown.target, 220.0, "down")
        val p = SoloDay.planWords(t)
        assertTrue("at or above 25,944" in p && "once FINNIFTY reaches 25,779" in p && "the 2R mark is 25,746" in p, p)
        assertTrue("closing in the bottom" in SoloDay.indexWords(sig(fnDown)))
        assertTrue("the only one that signalled" in SoloDay.indexWords(sig(fnDown), 1, 1))
        // With no ATR kept, no lock level is said.
        assertFalse("breakeven" in SoloDay.planWords(t.copy(atr = null)))
    }

    @Test fun aClosedTradeSaysItsExitAndNet() {
        val closed = bnTrade.copy(closed = true, exitPrice = 701.25, net = 2_412.0, exit = "Out by 14:30. At best BANKNIFTY went 180 points its way.")
        val r = SoloDay.bought(decidedRecord(sig(bn), nfMid, fnSmall), Bought(at(12, 1), "BANKNIFTY", bnTrade.symbol, 54_000, 4, 612.5))
        val a = SoloDay.answer(Q(), facts(now = at(15, 0), record = r, trades = listOf(closed)))
        assertTrue("Closed at ₹701.25: Out by 14:30. At best BANKNIFTY went 180 points its way. Net ₹2,412 after charges." in a, a)
        assertTrue("the only one that signalled" in a, a)
        assertTrue("NIFTY's close was mid-range (40% from its edge; it needs the outer 25%)" in a, a)
        assertFalse("Not taken as weaker" in a, a)
        val unread = SoloDay.tradeState(bnTrade.copy(closed = true, exit = null))
        assertTrue("exit not recorded" in unread && "could not be read" in unread, unread)
    }

    @Test fun noIndexSignalled() {
        val a = SoloDay.answer(Q(why = true), facts(record = decidedRecord(SoloMidday.Decision("BANKNIFTY", null, "move_too_small", 0.2), nfMid, fnSmall)))
        assertTrue("None met both rules (0.5 ATR from the open and a close in the outer quarter), so no trade today." in a, a)
        assertFalse("It did trade today." in a, a)
        // The strongest read first among those with no signal.
        assertTrue(a.indexOf("NIFTY's close") < a.indexOf("FINNIFTY moved") && a.indexOf("FINNIFTY moved") < a.indexOf("BANKNIFTY moved"), a)
    }

    @Test fun theStrongestSetAsideAndTheNextBought() {
        var r = decidedRecord(sig(bn), sig(nf), fnSmall)
        r = SoloDay.passed(r, at(12, 1), "BANKNIFTY", "expiry_today")
        r = SoloDay.bought(r, Bought(at(12, 2), "NIFTY", "NIFTY26OCT24900CE", 24_900, 4, 210.0))
        val nTrade = Trade(day, "NIFTY", "NIFTY26OCT24900CE", true, 75, 210.0, 166, 25_125.0, nf.stop, nf.target, 200.0, "NIFTY up")
        val a = SoloDay.answer(Q(), facts(record = r, trades = listOf(nTrade)))
        assertTrue("Set aside: BANKNIFTY expires today (Solo never trades an index on its expiry day)." in a, a)
        assertTrue("- BANKNIFTY was stronger but was set aside." in a, a)
        assertFalse("Not taken as weaker" in a, a)
    }

    @Test fun everyIndexSetAsideOrLate() {
        var r = decidedRecord(sig(bn), sig(nf), fnSmall)
        r = SoloDay.passed(r, at(12, 1), "BANKNIFTY", "expiry_unknown")
        r = SoloDay.passed(r, at(12, 1), "NIFTY", "the other automatic positions could not be read")
        val a = SoloDay.answer(Q(why = true), facts(record = r))
        assertTrue("BANKNIFTY's expiry could not be read from the contracts" in a, a)
        assertTrue("NIFTY: the other automatic positions could not be read" in a, a)
        assertTrue("Every index that signalled was set aside, so no trade today." in a, a)
        val late = SoloDay.answer(Q(why = true), facts(record = SoloDay.late(decidedRecord(sig(bn), nfMid, fnSmall), at(12, 4))))
        assertTrue("The 12:00-12:03 window closed (12:04) before the order could go in - no order after 12:03, so no trade today." in late, late)
        val lateOnly = SoloDay.answer(Q(why = true), facts(record = SoloDay.late(null, at(12, 4))))
        assertTrue("no order after 12:03" in lateOnly, lateOnly)
    }

    @Test fun thinStrikesMovedOrPassedOver() {
        var r = decidedRecord(sig(bn), sig(nf), fnSmall)
        r = SoloDay.thin(r, at(12, 1), "BANKNIFTY", "BANKNIFTY26OCT54000CE", "the spread is 9% of the price")
        r = SoloDay.bought(r, Bought(at(12, 1), "BANKNIFTY", "BANKNIFTY26OCT54100CE", 54_100, 3, 560.0, moved = true))
        val moved = SoloDay.answer(Q(), facts(record = r, trades = listOf(bnTrade.copy(symbol = "BANKNIFTY26OCT54100CE"))))
        assertTrue("The strike 4 in the money was thin (BANKNIFTY26OCT54000CE: the spread is 9% of the price): it bought the next one, as the research's fallback." in moved, moved)
        assertTrue("(3 in the money)" in moved, moved)

        var all = decidedRecord(sig(bn), sig(nf), fnSmall)
        for (k in listOf("54000", "54100", "53900")) all = SoloDay.thin(all, at(12, 1), "BANKNIFTY", "BANKNIFTY26OCT${k}CE", "hardly traded today")
        all = SoloDay.passed(all, at(12, 1), "BANKNIFTY", "hardly traded today")
        all = SoloDay.bought(all, Bought(at(12, 2), "NIFTY", "NIFTY26OCT24900CE", 24_900, 4, 210.0))
        val passed = SoloDay.answer(Q(), facts(record = all, thin = 1 to 2))
        assertTrue("BANKNIFTY's strikes near the one it wanted were all thin (BANKNIFTY26OCT54000CE: hardly traded today) - passed over" in passed, passed)
        assertTrue("Thin strikes so far: the strike moved 1 time, an index passed over 2 times." in passed, passed)
        // Its trade record unreadable: said so.
        assertTrue("Its trade record could not be read just now." in passed, passed)
    }

    @Test fun eachReasonAnIndexWasSetAside() {
        fun w(why: String) = SoloDay.passWords(SoloDay.Pass(at(12, 1), "NIFTY", why))
        assertEquals("NIFTY: no expiry is listed for it (no contract to buy)", w("no NIFTY expiry is listed"))
        assertEquals("NIFTY: no NIFTY strike near 24900 is listed (no contract)", w("no NIFTY strike near 24900 is listed"))
        assertEquals("NIFTY: no price for NIFTY26OCT24900CE (data missing)", w("no price for NIFTY26OCT24900CE"))
        assertEquals("NIFTY: the paper order for X did not fill", w("the paper order for X did not fill"))
        assertEquals("NIFTY: you hold X yourself", w("you hold X yourself"))
        assertTrue("expires today" in w("expiry_today"))
        assertTrue("could not be read" in w("expiry_unknown"))
    }

    @Test fun guardsAndWaiting() {
        for ((code, words) in listOf(SoloDay.KILL_SWITCH to "the kill switch is on", SoloDay.LOSS_BREAKER to "the day's loss breaker has tripped",
            SoloDay.CHECK_STOP to "the trade check says STOP", SoloDay.CHECK_UNREAD to "the trade check could not be read")) {
            val a = SoloDay.answer(Q(why = true), facts(now = at(12, 5), record = SoloDay.held(null, at(12, 0), code)))
            assertTrue("At 12:00 its decision was held back: $words" in a, a)
            assertTrue("The window closed with no trade today (no order after 12:03)." in a, a)
        }
        assertEquals("other", SoloDay.heldWords("other"))
        val waiting = SoloDay.answer(Q(), facts(now = at(12, 0), record = SoloDay.waited(null, at(12, 0), listOf("NIFTY", "FINNIFTY"))))
        assertTrue("At 12:00 it was waiting for the 11:59 minute or the daily ATR of NIFTY and FINNIFTY" in waiting, waiting)
        assertTrue("It is still within its 12:00-12:03 window." in waiting, waiting)
        // A guard that held it at 12:00 and a decision a minute later: the decision is told, not the guard.
        val r = SoloDay.decided(SoloDay.held(null, at(12, 0), SoloDay.CHECK_UNREAD), at(12, 1), listOf(nfNoAtr, sig(bn), fnSmall))
        val a = SoloDay.answer(Q(), facts(record = r))
        assertFalse("held back" in a, a)
        assertTrue("NIFTY's daily ATR could not be read" in a, a)
        // Held before noon (a guard read before 12:00 is not possible, but the rule is said).
        val pre = SoloDay.answer(Q(), facts(now = at(11, 50), record = SoloDay.held(null, at(11, 50), SoloDay.KILL_SWITCH)))
        assertTrue(SoloDay.RULE in pre, pre)
    }

    @Test fun inTheWindowWithNothingYet() {
        val a = SoloDay.answer(Q(), facts(now = at(12, 2), prefetch = Prefetch(true, mapOf("NIFTY" to 200.0, "BANKNIFTY" to 500.0, "FINNIFTY" to 220.0), true)))
        assertTrue("It is deciding now (12:00-12:03)." in a, a)
    }

    @Test fun nothingOnRecordAfterTheWindow() {
        val restarted = SoloDay.answer(Q(why = true), facts(now = at(13, 0), since = at(12, 30)))
        assertTrue("the app started at 12:30, after the 12:00-12:03 window" in restarted, restarted)
        val during = SoloDay.answer(Q(why = true), facts(now = at(13, 0), since = at(12, 2)))
        assertTrue("restarted at 12:02, during the window" in during, during)
        val plain = SoloDay.answer(Q(why = true), facts(now = at(13, 0), since = at(9, 0)))
        assertTrue("No 12:00 decision is on record today and no Solo trade" in plain, plain)
        val yesterday = SoloDay.answer(Q(why = true), facts(now = at(13, 0), since = day.minusDays(1).atTime(9, 0)))
        assertTrue("No 12:00 decision is on record today and no Solo trade" in yesterday, yesterday)
    }

    @Test fun aTradeWithItsDecisionNotInMemory() {
        val a = SoloDay.answer(Q(), facts(now = at(13, 0), trades = listOf(bnTrade), since = at(12, 40)))
        assertTrue("It bought BANKNIFTY26OCT54000CE at 12:01: BANKNIFTY is up 400 points from the open." in a, a)
        assertTrue("not in memory: the app restarted since" in a, a)
        assertTrue("Entry: BANKNIFTY at 54,410" in a, a)
        // An older day's trade is not today's.
        val old = SoloDay.answer(Q(), facts(now = at(13, 0), trades = listOf(bnTrade.copy(day = day.minusDays(1)))))
        assertFalse("It bought" in old, old)
        // A record kept on another day is not today's either.
        val stale = SoloDay.answer(Q(), facts(now = at(13, 0), record = decidedRecord(sig(bn), nfMid, fnSmall, at = day.minusDays(1).atTime(12, 1))))
        assertFalse("it read all three" in stale, stale)
    }

    @Test fun aTradeFromAnEarlierDayStillOpen() {
        val a = SoloDay.answer(Q(), facts(now = at(12, 30), earlier = bnTrade.copy(day = day.minusDays(1))))
        assertTrue("still seeing through its BANKNIFTY26OCT54000CE trade from 2026-10-05" in a, a)
    }

    @Test fun theForwardTestLine() {
        val base = SoloMidday.Baseline(2, 600.0)
        val since = SoloDay.forwardLine(check(1_000.0, -400.0, -2_000.0, base = base))
        assertTrue("3 of 60 closed paper trades, net −₹1,400" in since && "worst drawdown −₹2,000 since you switched it back on" in since, since)
        val failed = SoloDay.forwardLine(check(-26_000.0))
        assertTrue("that bar has failed" in failed, failed)
        val failedNet = SoloDay.forwardLine(check(*DoubleArray(60) { if (it % 2 == 0) 100.0 else -150.0 }))
        assertTrue("the net bar failed" in failedNet, failedNet)
        val passed = SoloDay.forwardLine(check(*DoubleArray(61) { 100.0 }))
        assertTrue("60 of 60 closed paper trades (61 in all)" in passed && "passed the bar" in passed, passed)
        assertEquals("Its forward test could not be read just now.", SoloDay.forwardLine(null))
        val empty = SoloDay.forwardLine(check())
        assertTrue("0 of 60 closed paper trades, net ₹0;" in empty, empty)
    }

    @Test fun theIndexNamedIsToldOnItsOwn() {
        var r = decidedRecord(sig(bn), sig(nf), fnSmall)
        r = SoloDay.bought(r, Bought(at(12, 1), "BANKNIFTY", bnTrade.symbol, 54_000, 4, 612.5))
        assertTrue(SoloDay.focus("BANKNIFTY", r)!!.endsWith("- it was the one bought."))
        assertTrue(SoloDay.focus("NIFTY", r)!!.endsWith("- not taken - BANKNIFTY was bought first."))
        assertTrue("FINNIFTY moved only 0.30 ATR" in SoloDay.focus("FINNIFTY", r)!! && SoloDay.focus("FINNIFTY", r)!!.endsWith("- no signal."))
        val aside = SoloDay.passed(decidedRecord(sig(bn), sig(nf), fnSmall), at(12, 1), "BANKNIFTY", "expiry_today")
        assertTrue("set aside - BANKNIFTY expires today" in SoloDay.focus("BANKNIFTY", aside)!!)
        assertTrue(SoloDay.focus("NIFTY", SoloDay.late(decidedRecord(sig(nf), fnSmall), at(12, 4)))!!.endsWith("the window closed before the order."))
        assertEquals("On NIFTY: it was not read at 12:00.", SoloDay.focus("NIFTY", decidedRecord(fnSmall)))
        assertTrue(SoloDay.focus("NIFTY", decidedRecord(sig(nf)))!!.endsWith("- not taken."))
        assertNull(SoloDay.focus(null, r)); assertNull(SoloDay.focus("NIFTY", null)); assertNull(SoloDay.focus("NIFTY", Record(day)))
        val a = SoloDay.answer(Q(market = "NIFTY"), facts(record = r, trades = listOf(bnTrade)))
        assertTrue("On NIFTY: NIFTY up 120" in a, a)
        val fewer = SoloDay.answer(Q(), facts(record = decidedRecord(sig(nf), fnSmall)))
        assertTrue("it read 2 indices:" in fewer, fewer)
    }

    @Test fun neverAWordOfActing() {
        val r = SoloDay.bought(decidedRecord(sig(bn), sig(nf), fnSmall), Bought(at(12, 1), "BANKNIFTY", bnTrade.symbol, 54_000, 4, 612.5))
        val answers = listOf(
            SoloDay.answer(Q(), facts(now = at(10, 0), prefetch = Prefetch(true, mapOf("NIFTY" to 200.0), true))),
            SoloDay.answer(Q(why = true), facts(record = r, trades = listOf(bnTrade))),
            SoloDay.answer(Q(why = true), facts(record = decidedRecord(nfMid, fnSmall))),
            SoloDay.answer(Q(), facts(on = false)),
        )
        val acting = Regex("(?i)\\b(i will|i'll|i have (placed|closed|switched)|i (placed|closed|switched on|switched off|armed)|placing|shall i|should i|want me to|do you want)\\b")
        for (a in answers) {
            assertFalse(acting.containsMatchIn(a), a)
            assertTrue("Research, unseen period" in a, a)
        }
        // Thin counts of zero are not said; none read is not said either.
        assertFalse("Thin strikes" in SoloDay.answer(Q(), facts(thin = null)))
    }
}
