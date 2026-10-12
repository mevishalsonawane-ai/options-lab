package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VettingTest {
    private val oct20 = LocalDate.of(2026, 10, 20)
    /** Far enough after 13 Oct for 40 trading days (no real date anywhere). */
    private val late = LocalDate.of(2027, 1, 29)
    private fun trades(n: Int, day: LocalDate = oct20, f: (Int) -> Double) = List(n) { Vetting.Trade(day, f(it)) }
    private val BN = "Liquidity 15+5 BANKNIFTY"

    @Test fun theRuleItself() {
        assertEquals(60, Vetting.MIN_TRADES); assertEquals(40, Vetting.MIN_DAYS)
        assertEquals(LocalDate.of(2026, 10, 13), Vetting.START)
        // 5-95% band for n trades: n·mean ± 1.645·sd·√n.
        val b = Vetting.Backtest(100.0, 1_000.0, "t").band(25)!!
        assertEquals(2_500.0, b.mid, 1e-9); assertEquals(2_500.0 - 1.645 * 5_000, b.low, 1e-6); assertEquals(2_500.0 + 1.645 * 5_000, b.high, 1e-6)
        assertNull(Vetting.Backtest(100.0, 1_000.0, "t").band(0))
        assertEquals(5, Vetting.tradingDays(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 19)))
        assertEquals(0, Vetting.tradingDays(oct20, LocalDate.of(2026, 10, 13)))
    }

    @Test fun tooEarlyUntilSixtyTradesAndFortyDays() {
        val v = Vetting.judge(BN, trades(46) { if (it % 2 == 0) 600.0 else -300.0 }, oct20)
        assertEquals(Vetting.State.TOO_EARLY, v.state)
        assertTrue(v.text().contains("too early (46/60, 6/40)"), v.text())
        assertTrue(v.text().contains("within the backtest's normal range so far"), v.text())
        assertFalse(v.text().contains("of 15"), v.text())
        // 60 trades in 6 days: still too early on days.
        assertEquals(Vetting.State.TOO_EARLY, Vetting.judge(BN, trades(70) { 300.0 }, oct20).state)
    }

    @Test fun tradesBeforeTheStartNeverCount() {
        val old = trades(80, LocalDate.of(2026, 10, 9)) { 900.0 }
        val v = Vetting.judge(BN, old, late)
        assertEquals(0, v.trades); assertEquals(Vetting.State.TOO_EARLY, v.state)
    }

    @Test fun belowAndAboveTheBandAreFlaggedFromTenTrades() {
        // ORB (backtest -101 a trade): 30 trades +5,068 (X1's lucky record) is above its band - check the fills, never "held up".
        val orb = Vetting.judge("ORB", trades(30) { if (it < 18) 600.0 else -477.33 }, oct20)
        assertEquals(Vetting.State.ABOVE, orb.state, orb.text())
        assertTrue(orb.text().contains("above the backtest's range - check the fills"), orb.text())
        // Liquidity BANKNIFTY 20 trades -6,186 (X1: ordinary bad luck) is inside its band.
        assertEquals(Vetting.State.TOO_EARLY, Vetting.judge(BN, trades(20) { -309.3 }, oct20).state)
        // Far below: investigate.
        val bad = Vetting.judge(BN, trades(20) { -1_200.0 }, oct20)
        assertEquals(Vetting.State.BELOW, bad.state)
        assertTrue(bad.text().contains("below the backtest's range - investigate"), bad.text())
        // Under 10 trades: never flagged.
        assertEquals(Vetting.State.TOO_EARLY, Vetting.judge(BN, trades(5) { -5_000.0 }, oct20).state)
    }

    @Test fun passedOnlyWithAPositiveBacktestAndNeverForALoser() {
        val ok = Vetting.judge(BN, trades(60) { if (it % 2 == 0) 1_000.0 else -500.0 }, late)
        assertEquals(Vetting.State.PASSED, ok.state)
        assertTrue(ok.text().contains("Passing only informs you: going live stays your step, with your PIN."), ok.text())
        // A losing backtest at the minimums inside its band: within, never passed (even when up).
        val orb = Vetting.judge("ORB", trades(60) { if (it % 2 == 0) 300.0 else -250.0 }, late)
        assertEquals(Vetting.State.WITHIN, orb.state, orb.text())
        assertTrue(orb.text().contains("cannot pass"), orb.text())
        // Down at the minimums but inside the band: within, not passed.
        assertEquals(Vetting.State.WITHIN, Vetting.judge(BN, trades(60) { if (it % 2 == 0) 300.0 else -400.0 }, late).state)
        // No backtest on the phone: never passes.
        val pine = Vetting.judge("Pine #3", trades(70) { 500.0 }, late)
        assertEquals(Vetting.State.NO_BACKTEST, pine.state)
        assertTrue(pine.text().contains("cannot pass"), pine.text())
    }

    @Test fun daysFromTheTradesWhenTodayIsUnknown() {
        val v = Vetting.judge(BN, listOf(Vetting.Trade(oct20, 10.0), Vetting.Trade(oct20.plusDays(1), 10.0)), null)
        assertEquals(2, v.days)
    }

    @Test fun saidPlainly() {
        val s = Vetting.say(listOf(Vetting.judge(BN, trades(60) { if (it % 2 == 0) 1_000.0 else -500.0 }, late),
            Vetting.judge("Pine B", trades(3) { 100.0 }, late)))
        assertTrue(s.startsWith("Passed on paper, Boss: $BN passed its paper test"), s)
        assertTrue(s.endsWith("Passing switches nothing: going live is your step, in Live with your PIN."), s)
        assertTrue(Vetting.say(emptyList()).contains("at least 60 trades and 40 trading days from 13 Oct"))
        val none = Vetting.say(listOf(Vetting.judge("ORB", trades(3) { 100.0 }, oct20)))
        assertTrue(none.startsWith("Nothing has passed its paper test yet, Boss"), none)
        // A "%" in a name never breaks the sentence.
        assertTrue(Vetting.judge("Pine 50% fade", trades(16) { 300.0 }, oct20).text().startsWith("Pine 50% fade's paper test"))
        assertTrue(Vetting.asked("Jarvis, what held up?")); assertTrue(Vetting.asked("what should I trade live"))
    }
}
