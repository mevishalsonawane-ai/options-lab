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

    /** A failed-break day that then breaks above the range for good from this minute of day (null: never). */
    private var breakAt: Int? = null

    /** The index at the minute of day [m] (minutes since midnight). */
    private fun price(m: Int): Double = when {
        m < 10 * 60 + 5 -> swing[(m - (9 * 60 + 15)) % swing.size]
        m < 10 * 60 + 30 -> 53_950.0
        sweepDay && breakAt.let { it == null || m < it } -> 53_950.0
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
        val msg = runBlocking { OrbArms.armForTest("orb", true, automatic = true) }
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

    // ---- expiry day -----------------------------------------------------------------------------------

    @Test fun onAnExpiryDayTheOrbBuysTheOptionExpiringToday() {
        // Today's contracts and next month's are both listed: the ORB family takes today's (the owner's choice).
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", day, strike.toDouble(), Right.CE, 30, "NSE_FO|ORBTODAYCE", "BANKNIFTY-ORB-TODAY-${strike}CE"),
            Upstox.Contract("BANKNIFTY", day, strike.toDouble(), Right.PE, 30, "NSE_FO|ORBTODAYPE", "BANKNIFTY-ORB-TODAY-${strike}PE"),
            Upstox.Contract("BANKNIFTY", day.plusDays(28), strike.toDouble(), Right.CE, 30, ceKey, "BANKNIFTY-ORB-NEXT-${strike}CE"),
            Upstox.Contract("BANKNIFTY", day.plusDays(28), strike.toDouble(), Right.PE, 30, peKey, "BANKNIFTY-ORB-NEXT-${strike}PE")))
        upstox.price("NSE_FO|ORBTODAYCE", 120.0)
        upstox.price("NSE_FO|ORBTODAYPE", 110.0)
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val legs = runBlocking { OrbArms.view() }.legs!!
        assertEquals("today's expiry", day, legs.expiry)
        val p = arm().open!!
        assertEquals(legs.ce.symbol, p.symbol)
        assertEquals(120.0, p.entry, 0.5)
    }

    // ---- the profit lock (25 / 50 / 75 % of the target) ------------------------------------------------

    @Test fun aTradeThatGotAQuarterOfTheWayIsSoldAtThePricePaidNotTheFullStop() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val p = arm().open!!
        assertTrue("entered under the ladder", p.ladder)
        upstox.price(ceKey, p.entry + 12)                           // +12: past 25% of the +40 target
        passes(LocalTime.of(10, 36), LocalTime.of(10, 37))
        assertTrue("still held while it runs", arm().open != null)
        assertEquals(p.entry + 12, arm().open!!.peak!!, 0.5)
        upstox.price(ceKey, p.entry - 1)                            // back under the price paid
        tick(LocalTime.of(10, 38))
        val closed = arm().today.single()
        assertEquals("profit_lock", closed.why)
        assertEquals(p.entry - 1, closed.exit!!, 0.5)
        assertTrue("the resting -40 stop came out of the book",
            Paper.state.orders.none { it.orderId == p.stopOrderId && it.status == "trigger pending" })
    }

    @Test fun theBreakevenRungCoversTheChargesSoAPointAboveThePricePaidIsSold() {
        // Boss's 06 Oct: a lock exit at +0.5 was a loss after ~Rs 70 of charges. The rung now sits at entry + charges.
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val p = arm().open!!
        val cost = com.optionslab.engine.orb.ProfitLock.roundTripPerUnit(p.entry, p.qty)
        assertTrue("about 2 points a unit for one lot: $cost", cost > 1.0 && cost < 4.0)
        upstox.price(ceKey, p.entry + 12)                           // past 25% of the +40 target
        tick(LocalTime.of(10, 36))
        upstox.price(ceKey, p.entry + cost + 1)                     // above the after-charges breakeven: held
        tick(LocalTime.of(10, 37))
        assertTrue("held above entry + charges", arm().open != null)
        upstox.price(ceKey, p.entry + 0.5)                          // the old rung (the price paid) would have held this
        tick(LocalTime.of(10, 38))
        val closed = arm().today.single()
        assertEquals("profit_lock", closed.why)
        assertTrue("the resting -40 stop is never lowered or moved", closed.stopTrigger == p.stopTrigger)
    }

    @Test fun threeQuartersOfTheWayLocksHalfTheTarget() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val p = arm().open!!
        upstox.price(ceKey, p.entry + 31)                           // +31: past 75%, so +20 is locked
        tick(LocalTime.of(10, 36))
        upstox.price(ceKey, p.entry + 22)                           // above the lock: held
        tick(LocalTime.of(10, 37))
        assertTrue(arm().open != null)
        upstox.price(ceKey, p.entry + 19)                           // under +20: sold
        tick(LocalTime.of(10, 38))
        val closed = arm().today.single()
        assertEquals("profit_lock", closed.why)
        assertTrue("sold with a profit, not at the -40 stop", closed.exit!! > p.entry + 15)
    }

    @Test fun belowTheFirstRungTheTradeKeepsItsUsualStop() {
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val p = arm().open!!
        upstox.price(ceKey, p.entry + 8)                            // only +8 (20%): nothing locked
        tick(LocalTime.of(10, 36))
        upstox.price(ceKey, p.entry - 10)
        passes(LocalTime.of(10, 37), LocalTime.of(10, 40))
        assertTrue("held: -10 is above the -40 stop", arm().open != null)
    }

    // ---- ORB Sweep (paper only) ----------------------------------------------------------------------

    @Test fun orbSweepFadesAFailedBreakOfTheRangeOnPaper() {
        sweepDay = true
        at(LocalTime.of(9, 50))
        val msg = runBlocking { OrbArms.armForTest("orb_sweep", true, automatic = true) }
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

    // ---- one index, one side (Boss's 06 Oct rule) -----------------------------------------------------

    @Test fun theOrbNeverBuysACallWhileOrbSweepHoldsAPutOnTheSameIndex() {
        // 06 Oct: ORB Sweep bought a BankNifty put, then ORB a call five minutes later. Now the call is refused.
        sweepDay = true; breakAt = 10 * 60 + 45                    // the 10:30 failed break, then a real break from 10:45
        at(LocalTime.of(9, 50))
        runBlocking { OrbArms.armForTest("orb_sweep", true, automatic = true) }
        armOrb(LocalTime.of(9, 50))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 40))
        val put = arm("orb_sweep").open!!
        assertEquals("PE", put.right)
        passes(LocalTime.of(10, 41), LocalTime.of(10, 55))         // the 10:45 bar closed above the range: ORB wants the call
        assertTrue("ORB bought nothing", arm("orb").today.isEmpty())
        assertTrue(arm("orb").status, arm("orb").status.startsWith("opposite_position_open: ORB Sweep holds ${put.symbol}"))
        assertTrue("no call was ordered", Paper.state.orders.none { it.symbol.endsWith("CE") })
        assertTrue(OrbArms.describe(arm("orb").status), OrbArms.describe(arm("orb").status).contains("the other way on this index"))
    }

    @Test fun twoArmsNeverHoldTheSameSideOfOneIndexAtOnce() {
        // The 10:30 failed break is a signal for ORB Sweep and for Range Fade alike: the first buys the put, the second is refused.
        sweepDay = true
        at(LocalTime.of(9, 50))
        runBlocking { OrbArms.armForTest("orb_sweep", true, automatic = true); OrbArms.armForTest("range_fade", true, automatic = true) }
        passes(LocalTime.of(9, 50), LocalTime.of(10, 39))                  // (the 10:35 bar, decided at 10:40, is not a signal)
        val put = arm("orb_sweep").today.single()
        assertEquals("PE", put.right)
        assertTrue("Range Fade bought nothing", arm("range_fade").today.isEmpty())
        assertTrue(arm("range_fade").status, arm("range_fade").status.startsWith("same_side_already_held: ORB Sweep holds ${put.symbol}"))
        assertEquals("one paper buy", 1, Paper.state.orders.count { it.action == "BUY" })
    }

    // ---- the one-time switch-off (Boss's choice, 06 Oct) ------------------------------------------------

    @Test fun onThisUpdateTheLosingArmsAreSwitchedOffOnceAndTheirPositionsStayManaged() {
        val books = listOf("orb_sweep", "range_fade", "liquidity15", "liquidity5", "orb")
        fun flags(v: Boolean) = org.json.JSONObject().apply { books.forEach { put(it, v) } }
        val t = day.atTime(9, 50)
        // Saved before both 06 Oct updates: no one-time change marked; Range Fade holds a put.
        AutomationSupport.orbState(context, org.json.JSONObject().put("migrated", org.json.JSONArray())
            .put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
            .put("positions", org.json.JSONArray().put(org.json.JSONObject().put("arm", "range_fade")
                .put("symbol", "BANKNIFTY-ORB-${strike}PE").put("right", "PE").put("qty", 30).put("entry", 280.0)
                .put("entryTime", t.minusMinutes(5).toString()).put("signalBar", t.minusMinutes(10).toString()))))
        at(LocalTime.of(9, 50))
        val v = runBlocking { OrbArms.view() }
        // The first switch-off (paper record negative) took ORB Sweep and Range Fade; the retirement then took ORB.
        for (src in listOf("orb_sweep", "range_fade")) {
            val a = v.arms.single { it.arm.source == src }
            assertTrue("$src switched off", !a.armed)
            assertEquals(OrbArms.SWITCHED_OFF, a.status)
        }
        val orb = v.arms.single { it.arm.source == "orb" }
        assertTrue("ORB is retired", !orb.armed)
        assertEquals(com.optionslab.engine.orb.RetiredArms.SWITCHED_OFF, orb.status)
        // ... and the retirement switched Liquidity 15+5 back on, on paper only.
        val liq = v.arms.single { it.arm.source == "liquidity" }
        assertTrue("both liquidity books back on", liq.armed)
        assertTrue("paper only: never cleared for Zerodha", !liq.liveOk)
        assertTrue(liq.status, liq.status.contains("switched back on, paper only (Boss's choice 06 Oct)"))
        assertNotNull("Range Fade's open put is kept, managed to its exit", v.arms.single { it.arm.source == "range_fade" }.open)
        // Armed again (by the tests' own hook: the app refuses it): neither change runs a second time, even after a restart.
        runBlocking { OrbArms.armForTest("orb_sweep", true, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue("never switched off twice", arm("orb_sweep").armed)
    }

    // ---- the retirement (Boss's choice after six years of real data, 06 Oct) -----------------------------

    /** The book as the first 06 Oct update left it: its switch-off done, ORB and ORB Fresh armed, ORB holding a call. */
    private fun savedAfterTheFirstUpdate(extra: (org.json.JSONObject) -> Unit = {}) {
        val t = day.atTime(9, 50)
        fun flags(on: Boolean, vararg src: String) = org.json.JSONObject().apply { src.forEach { put(it, on) } }
        val o = org.json.JSONObject()
            .put("armed", flags(true, "orb", "orb_fresh").put("liquidity15", false).put("liquidity5", false).put("liquidity30_fin", false).put("liquidity5_fin", false))
            .put("auto", flags(true, "orb", "orb_fresh")).put("liveOk", flags(true, "orb"))
            .put("status", org.json.JSONObject().put("liquidity5", OrbArms.SWITCHED_OFF))
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS))
            .put("positions", org.json.JSONArray().put(org.json.JSONObject().put("arm", "orb")
                .put("symbol", "BANKNIFTY-ORB-${strike}CE").put("right", "CE").put("qty", 30).put("entry", 300.0)
                .put("entryTime", t.minusMinutes(5).toString()).put("signalBar", t.minusMinutes(10).toString())))
        extra(o)
        AutomationSupport.orbState(context, o)
    }

    @Test fun theRetirementSwitchesOrbOffAndLiquidityBackOnOnPaperOnlyOnce() {
        savedAfterTheFirstUpdate()
        at(LocalTime.of(9, 50))
        val v = runBlocking { OrbArms.view() }
        for (src in listOf("orb", "orb_fresh")) {
            val a = v.arms.single { it.arm.source == src }
            assertTrue("$src switched off", !a.armed)
            assertTrue("$src no longer cleared for Zerodha", !a.liveOk)
            assertEquals(com.optionslab.engine.orb.RetiredArms.SWITCHED_OFF, a.status)
            assertEquals(src, a.retired!!.arm.source)
        }
        assertNotNull("ORB's open call is kept, managed to its exit", v.arms.single { it.arm.source == "orb" }.open)
        val liq = v.arms.single { it.arm.source == "liquidity" }
        assertTrue(liq.armed); assertTrue("automatic", liq.automatic); assertTrue("paper only", !liq.liveOk)
        assertNull("Liquidity is not retired", liq.retired)
        assertTrue(liq.status, liq.status.contains("BANKNIFTY 5-min: switched back on, paper only (Boss's choice 06 Oct)"))
        val log = Diag.lines()
        assertTrue(log.toString(), log.any { it.contains("ORB: switched off: lost on 6 years of real data (Boss's choice 06 Oct); its open position is still managed to its exit") })
        assertTrue(log.toString(), log.any { it.contains("ORB Fresh: switched off: lost on 6 years of real data (Boss's choice 06 Oct)") })
        assertTrue(log.toString(), log.any { it.contains("Liquidity 5m: switched back on, paper only (Boss's choice 06 Oct)") })
        // Boss switches Liquidity off: it stays off, even after a restart (the change ran once).
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue("never switched on twice", !arm("liquidity").armed)
        assertTrue("ORB stays off", !arm("orb").armed)
    }

    @Test fun aRestoreNotYetDisarmedSwitchesNothingOn() {
        savedAfterTheFirstUpdate()
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, true)
        try {
            at(LocalTime.of(9, 50))
            val v = runBlocking { OrbArms.view() }
            assertTrue("nothing restored is armed", v.arms.none { it.armed })
        } finally {
            com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, null)
        }
        // The change is done: the next start arms nothing either.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(!arm("liquidity").armed)
        assertTrue(!arm("orb").armed)
    }

    @Test fun aNewBookArmsNothingByItself() {
        at(LocalTime.of(9, 50))
        assertTrue(runBlocking { OrbArms.view() }.arms.none { it.armed })
    }

    @Test fun aRetiredArmIsNeverArmedAgainBySwitchOrByJarvis() {
        at(LocalTime.of(9, 50))
        for (src in listOf("orb", "orb_fresh", "orb_sweep", "range_fade")) {
            val said = runBlocking { OrbArms.setArmed(src, true, automatic = true) }
            val r = com.optionslab.engine.orb.RetiredArms.of(src)!!
            assertEquals(com.optionslab.engine.orb.RetiredArms.refusal(r), said)
            assertTrue("$src stays off", !arm(src).armed)
        }
        assertTrue(runBlocking { OrbArms.setArmed("orb", true, automatic = true) }.startsWith("ORB is retired: it lost ₹9.77 lakh over 2021–2026 on real data"))
        // "Start Range Fade": Jarvis says why, with nothing to confirm.
        val (said, act) = runBlocking { com.optionslab.app.ira.IraActions.prepare(com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.START_ONE, target = "range fade")) }
        assertNull("nothing to confirm", act)
        assertTrue(said, said.startsWith("Range Fade is retired, Boss: it lost ₹2.74 lakh over 2021–2026"))
        // A plan's or a voice start's arm call is refused the same way; the Hero arm and Liquidity still arm on paper.
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }.startsWith("Liquidity 15+5 armed on paper"))
        assertTrue(runBlocking { OrbArms.setArmed("hero", true, automatic = true) }.startsWith("Hero (expiry) armed on paper"))
    }

    @Test fun liquidityIsAskedAboutOnlyFromFortyPaperTradesSinceTheSixth() {
        val t = day.atTime(9, 50)
        fun trades(n: Int, from: LocalDateTime) = org.json.JSONArray().apply {
            repeat(n) { i ->
                val at = from.plusMinutes(i.toLong())
                put(org.json.JSONObject().put("arm", "liquidity5").put("symbol", "BANKNIFTY-LIQ-${strike}CE").put("right", "CE").put("qty", 30)
                    .put("entry", 300.0).put("exit", 290.0).put("entryTime", at.toString()).put("signalBar", at.minusMinutes(5).toString())
                    .put("exitTime", at.plusMinutes(5).toString()).put("why", "stop").put("charges", 60.0).put("near", i % 2 == 0))
            }
        }
        fun state(positions: org.json.JSONArray) = AutomationSupport.orbState(context, org.json.JSONObject()
            .put("armed", org.json.JSONObject().put("liquidity5", true)).put("auto", org.json.JSONObject().put("liquidity5", true))
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)).put("positions", positions))
        at(LocalTime.of(9, 50))
        // 39 losing trades since 06 Oct (and older ones before it, which do not count): not yet asked.
        val before = com.optionslab.engine.orb.LiquidityShadow.SINCE.minusDays(3).atTime(10, 0)
        state(trades(20, before).also { a -> val more = trades(39, t.minusHours(1)); for (i in 0 until more.length()) a.put(more.get(i)) })
        val (liq, _) = runBlocking { com.optionslab.app.ira.IraBots.cutoffArms() }.first { it.first.name == "Liquidity 15+5" }
        assertEquals(39, liq.paper.size)
        assertEquals(40, liq.minTrades)
        assertTrue("39 is too few for Liquidity", !com.optionslab.ira.ArmCutoff.due(liq))
        // The row shows the shadow: 39 trades, half of them flagged by candidate (a).
        val s = arm("liquidity").shadow!!
        assertEquals(39, s.all.trades)
        assertEquals(19, s.withoutNear.trades)
        state(trades(40, t.minusHours(1)))
        val (liq40, _) = runBlocking { com.optionslab.app.ira.IraBots.cutoffArms() }.first { it.first.name == "Liquidity 15+5" }
        assertTrue("40 losing trades: asked", com.optionslab.ira.ArmCutoff.due(liq40))
    }

    @Test fun anArmWithFifteenLosingPaperTradesIsPutToBossAndHisYesOnlySwitchesItOff() {
        // Boss's 06 Oct rule: 15+ closed paper trades and a net below zero after charges - asked; never armed again.
        val t = day.atTime(9, 50)
        val closed = org.json.JSONArray().apply {
            repeat(15) { i ->
                val at = t.minusDays(20L - i)
                put(org.json.JSONObject().put("arm", "orb").put("symbol", "BANKNIFTY-ORB-${strike}CE").put("right", "CE").put("qty", 30)
                    .put("entry", 300.0).put("exit", 301.0).put("entryTime", at.toString()).put("signalBar", at.minusMinutes(5).toString())
                    .put("exitTime", at.plusMinutes(5).toString()).put("why", "profit_lock").put("charges", 62.0))   // +30 gross, -32 after charges
            }
        }
        AutomationSupport.orbState(context, org.json.JSONObject()
            .put("armed", org.json.JSONObject().put("orb", true)).put("auto", org.json.JSONObject().put("orb", true))
            // Saved after both 06 Oct changes (ORB armed again through the tests' own hook: the app refuses it).
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)).put("positions", closed))
        at(LocalTime.of(9, 50))
        val arms = runBlocking { com.optionslab.app.ira.IraBots.cutoffArms() }
        val (orb, act) = arms.first { it.first.name == "ORB" }
        assertEquals(15, orb.paper.size)
        assertEquals(-32.0 * 15, orb.paper.sum(), 0.01)
        assertTrue("due: armed, 15 trades, negative after charges", com.optionslab.ira.ArmCutoff.due(orb))
        assertTrue("no other arm is due", arms.filter { it.first.name != "ORB" }.none { com.optionslab.ira.ArmCutoff.due(it.first) })
        val said = runBlocking { act() }
        assertTrue(said, said.startsWith("ORB disarmed"))
        assertTrue("switched off, never on", !arm("orb").armed)
    }

    // ---- Range Fade (paper only) ---------------------------------------------------------------------

    @Test fun rangeFadeBuysThePutWhenABarAtTheTopEdgeClosesBackInside() {
        sweepDay = true                                     // 10:05-10:25 sit mid-range; the 10:30 bar touches 54,060
        at(LocalTime.of(9, 50))
        val msg = runBlocking { OrbArms.armForTest("range_fade", true, automatic = true) }
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
