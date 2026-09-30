package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.OrbRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The ORB arm's whole minute cycle on the PAPER account: [OrbArms.tick] over a synthetic BANKNIFTY day
 * ([OrbArms.testIndexBars]), on a clock the test steps minute by minute ([OrbArms.testNow] and
 * [Market.testClock]), with option prices from the fake Upstox feed so the paper account really fills.
 *
 * The day: 1-minute bars from 09:15; the opening range 09:15-10:04 is 53,800-54,000; inside it until 10:29;
 * from 10:30 the index trades at 54,150, above the range, for the rest of the day. So the 10:30 bar is the
 * break, decided at 10:35. Normal minute passes, slow passes and the app being away all take the break;
 * arming while it is already under way waits one bar; stopped for today enters nothing.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsDayTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private var strike = 0
    private val ceKey = "NSE_FO|ORBDAYCE"
    private val peKey = "NSE_FO|ORBDAYPE"

    /** The opening range's swing, one value a minute from 09:15 (high 54,000, low 53,800; 53,900 at 09:24). */
    private val swing = listOf(53_900.0, 53_950.0, 54_000.0, 53_950.0, 53_900.0, 53_850.0, 53_800.0, 53_850.0, 53_900.0, 53_900.0)

    /** A failed-break day for ORB Sweep: no break, but at 10:31 a wick to 54,060 that closes back at 53,950. */
    private var sweepDay = false

    /** The index at the minute of day [m] (minutes since midnight). */
    private fun price(m: Int): Double = when {
        m < 10 * 60 + 5 -> swing[(m - (9 * 60 + 15)) % swing.size]
        m < 10 * 60 + 30 || sweepDay -> 53_950.0
        else -> 54_150.0
    }

    /** The minute's high: the price, except the sweep day's 10:31 wick above the range high. */
    private fun high(m: Int): Double = if (sweepDay && m == 10 * 60 + 31) 54_060.0 else price(m)

    /** The day's 1-minute bars the feed has at [t]: every minute that has finished by then. */
    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start -> val p = price(start.hour * 60 + start.minute)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), p, high(start.hour * 60 + start.minute), p, p, 1000, 0) }

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(9, 50).toLocalDate()
        at(LocalTime.of(9, 50))
        strike = OrbRules.atmStrike(price(9 * 60 + 24))     // the 09:20 bar's close
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.CE, 30, ceKey, "BANKNIFTY-ORB-${strike}CE"),
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.PE, 30, peKey, "BANKNIFTY-ORB-${strike}PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
    }

    @After fun down() {
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the clock and the passes -------------------------------------------------------------------

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    /** One pass of the market watch at [t]. */
    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    /** A pass every [every] minutes from [from] to [until], both included when on the step. */
    private fun passes(from: LocalTime, until: LocalTime, every: Long = 1) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(every) }
    }

    private fun armOrb(t: LocalTime) {
        at(t)
        val msg = runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertTrue(msg, msg.startsWith("ORB armed on paper, fully automatic"))
    }

    private fun arm(source: String = "orb") = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }
    private fun ceSymbol() = runBlocking { OrbArms.view() }.legs!!.ce.symbol

    /** Exactly one paper BUY of the day's CE, filled, with its resting stop 40 below the fill; the arm holds it. */
    private fun assertOneCeBuy(signalBar: LocalTime, entryTime: LocalTime) {
        val sym = ceSymbol()
        val view = runBlocking { OrbArms.view() }
        assertEquals(strike, view.legs!!.strike)
        assertEquals(54_000.0 to 53_800.0, view.range)
        val a = arm()
        val p = a.today.single()
        assertTrue(p.open)
        assertEquals("CE", p.right); assertEquals(sym, p.symbol); assertEquals(30, p.qty)
        assertTrue("premium ${p.entry} above 40", p.entry > 40.0)
        assertEquals(300.0, p.entry, 0.5)
        assertEquals(day.atTime(signalBar), p.signalBar)
        assertEquals(day.atTime(entryTime), p.entryTime)
        assertEquals(OrbRules.stopTrigger(p.entry), p.stopTrigger)
        // "entered" on the pass that bought, "holding" from the next pass on.
        assertTrue(a.status, a.status == "entered" || a.status == "holding")

        val orders = Paper.state.orders
        val buys = orders.filter { it.action == "BUY" }
        assertEquals("one paper buy, never two", 1, buys.size)
        assertEquals(sym, buys.single().symbol); assertEquals("complete", buys.single().status); assertEquals(p.entryOrderId, buys.single().orderId)
        val stop = orders.single { it.action == "SELL" }
        assertEquals(p.stopOrderId, stop.orderId); assertEquals("trigger pending", stop.status)
        assertEquals(30, Paper.state.positions.filter { it.symbol == sym && it.product == "MIS" }.sumOf { it.quantity })
        assertTrue("nothing bought on the PE", orders.none { it.symbol.endsWith("PE") })
    }

    private fun assertNothingBought() {
        assertTrue(arm().today.isEmpty())
        assertTrue("no paper order", Paper.state.orders.isEmpty())
    }

    // ---- the day ------------------------------------------------------------------------------------

    @Test fun armedBeforeTenAndTickedEveryMinuteBuysTheCallOnTheBreak() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 34))
        assertNothingBought()
        assertEquals("inside_range", arm().status)
        tick(LocalTime.of(10, 35))                                  // the 10:30 bar closed above 54,000
        assertOneCeBuy(signalBar = LocalTime.of(10, 30), entryTime = LocalTime.of(10, 35))
        passes(LocalTime.of(10, 36), LocalTime.of(11, 30))
        assertOneCeBuy(signalBar = LocalTime.of(10, 30), entryTime = LocalTime.of(10, 35))
    }

    @Test fun slowPassesEvery12MinutesStillTakeTheBreak() {
        armOrb(LocalTime.of(9, 53))
        // 09:53, 10:05, 10:17, 10:29, 10:41: the first pass after the break sees the 10:35 bar, the 10:30 break already under way.
        passes(LocalTime.of(9, 53), LocalTime.of(10, 29), every = 12)
        assertNothingBought()
        tick(LocalTime.of(10, 41))
        assertOneCeBuy(signalBar = LocalTime.of(10, 35), entryTime = LocalTime.of(10, 41))
        passes(LocalTime.of(10, 53), LocalTime.of(11, 53), every = 12)
        assertOneCeBuy(signalBar = LocalTime.of(10, 35), entryTime = LocalTime.of(10, 41))
    }

    @Test fun theAppAwayForHalfAnHourAcrossTheBreakCatchesUp() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 25))
        // No pass from 10:26 to 10:55 (the phone killed the app); the break came at 10:30 and holds.
        tick(LocalTime.of(10, 56))
        assertOneCeBuy(signalBar = LocalTime.of(10, 50), entryTime = LocalTime.of(10, 56))
    }

    @Test fun armedWhileTheBreakIsUnderWayWaitsOneBarThenEnters() {
        armOrb(LocalTime.of(10, 40))
        tick(LocalTime.of(10, 40))                                  // the 10:35 bar continues the 10:30 break: not chased
        assertEquals("waiting_for_fresh_break_after_pause", arm().status)
        assertNothingBought()
        passes(LocalTime.of(10, 41), LocalTime.of(10, 44))
        assertNothingBought()
        tick(LocalTime.of(10, 45))                                  // the next bar out of the range is taken
        assertOneCeBuy(signalBar = LocalTime.of(10, 40), entryTime = LocalTime.of(10, 45))
    }

    @Test fun stoppedForTodayDuringTheBreakEntersNothing() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 20))
        at(LocalTime.of(10, 21))
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        passes(LocalTime.of(10, 21), LocalTime.of(10, 50))
        assertEquals("stopped_for_today", arm().status)
        assertNothingBought()
        assertNull(arm().open)
        assertNotNull("the day's range was still read", runBlocking { OrbArms.view() }.range)
        // Started again after the stop: that was a real pause, so the bar under way is not chased; the next one is taken.
        runBlocking { Strategies.startAgain() }
        passes(LocalTime.of(10, 51), LocalTime.of(10, 54))
        assertEquals("waiting_for_fresh_break_after_pause", arm().status)
        assertNothingBought()
        tick(LocalTime.of(10, 55))
        assertOneCeBuy(signalBar = LocalTime.of(10, 50), entryTime = LocalTime.of(10, 55))
    }

    // ---- ORB Sweep (paper only) ----------------------------------------------------------------------

    @Test fun orbSweepFadesAFailedBreakOfTheRangeOnPaper() {
        sweepDay = true
        at(LocalTime.of(9, 50))
        val msg = runBlocking { OrbArms.setArmed("orb_sweep", true, automatic = true) }
        assertTrue(msg, msg.startsWith("ORB Sweep armed on paper (it never trades on Zerodha)"))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 34))
        assertTrue("no sweep before the 10:30 bar closes", arm("orb_sweep").today.isEmpty())
        passes(LocalTime.of(10, 35), LocalTime.of(10, 40))
        // The 10:30 bar went above 54,000 and closed back at 53,950: the break failed, so the PUT is bought.
        val p = arm("orb_sweep").today.single()
        assertTrue(p.open)
        assertEquals("PE", p.right); assertEquals(30, p.qty); assertTrue("paper, never live", !p.live)
        assertEquals(day.atTime(10, 30), p.signalBar)
        assertTrue("the ORB itself stays off", arm("orb").today.isEmpty())
    }

    // ---- Range Fade (paper only) ---------------------------------------------------------------------

    @Test fun rangeFadeBuysThePutWhenABarAtTheTopEdgeClosesBackInside() {
        sweepDay = true                                     // 10:05-10:25 sit mid-range; the 10:30 bar touches 54,060
        at(LocalTime.of(9, 50))
        val msg = runBlocking { OrbArms.setArmed("range_fade", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Range Fade armed on paper (it never trades on Zerodha)"))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 34))
        assertTrue("nothing while the bars sit mid-range", arm("range_fade").today.isEmpty())
        passes(LocalTime.of(10, 35), LocalTime.of(10, 40))
        val p = arm("range_fade").today.single()
        assertTrue(p.open)
        assertEquals("PE", p.right); assertTrue("paper, never live", !p.live)
        assertEquals(day.atTime(10, 30), p.signalBar)
        assertTrue("the ORB itself stays off", arm("orb").today.isEmpty())
    }
}
