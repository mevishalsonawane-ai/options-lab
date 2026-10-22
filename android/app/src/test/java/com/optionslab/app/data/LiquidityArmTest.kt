package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ManagerShadow
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Liquidity 15+5 on the PAPER account over a synthetic BANKNIFTY day, minute by minute.
 *
 * The 5-minute chart: flat bars at 54,000 (high 54,010, low 53,990) except a swing high at the 10:55 bar (high 54,100,
 * body 54,050-54,060), a second rejection of its wick at 11:25 (high 54,070, close 54,030), and at 13:00 a bar that
 * closes at 54,140 - taking the pool that sits on the swing zone. So the 5-minute book buys the 54,100 CE at 13:05.
 * [failDay]: the 13:05 bar closes back at 54,090 (the break failed, sold at 13:10); otherwise the index holds at
 * 54,150 with no upper wicks (no new liquidity above) and the position runs to 15:10. The 15-minute chart has too few bars that day for its levels: it stays out.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LIQCE"
    private val peKey = "NSE_FO|LIQPE"
    /** The put at the call's strike: candidate (f)'s opposite right. */
    private val pe0Key = "NSE_FO|LIQPE0"
    private var failDay = true
    /** The 13:05 bar dips to 54,060 (40 under the broken 54,100) and closes back above it: the index stop, not a failed break. */
    private var dipDay = false
    /** BANKNIFTY flat all day (the FINNIFTY test: only FINNIFTY breaks). */
    private var bankFlat = false

    /** The 5-minute bar k (09:15 + 5k) as (open, high, low, close). */
    private fun bar(k: Int): DoubleArray = when {
        bankFlat -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k == 46 && failDay -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)
        k == 46 && dipDay -> doubleArrayOf(54_150.0, 54_150.0, 54_060.0, 54_150.0)
        k >= 46 && !failDay -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)   // no upper wick: no new liquidity above
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    /** The day's 1-minute bars the feed has at [t]: every minute that has finished, each carrying its 5-minute bar's prices. */
    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, peKey, "BANKNIFTY-LIQ-54200PE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, pe0Key, "BANKNIFTY-LIQ-54000PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        // The day's figures below are one lot's (the default since 08 Oct); the size tests set their own.
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    /** [looks]: the trade manager takes a look after each pass (the SHADOW re-runs, [ManagerShadow.rerun]). */
    private var looks = false

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() }; if (looks) ManagerShadow.look() }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    private fun armLiquidity(automatic: Boolean = true) {
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = automatic) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper, " + if (automatic) "fully automatic" else "you approve each entry"))
    }

    @Test fun oneRowOneSwitchForBothBooks() {
        val arms = runBlocking { OrbArms.view() }.arms.map { it.arm.source }
        assertEquals(1, arms.count { it == "liquidity" })
        assertTrue("the books never show as rows of their own", arms.none { it.startsWith("liquidity") && it != "liquidity" })
        assertTrue("the other arms are all still there", arms.containsAll(listOf("orb", "orb_fresh", "orb_sweep", "range_fade")))
        assertFalse(row().armed)
        armLiquidity()
        assertTrue(row().armed)
        val off = runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        assertTrue(off, off.startsWith("Liquidity 15+5 disarmed."))
        assertFalse(row().armed)
    }

    @Test fun buysTheCallWhenAPoolOnASwingHighIsTakenAndSellsWhenTheBreakFails() {
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        assertTrue("nothing before the 13:00 bar closes", Paper.state.orders.isEmpty())
        tick(LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open)
        assertEquals("liquidity5", p.arm)
        assertEquals("CE", p.right); assertEquals(30, p.qty); assertFalse("paper, never live", p.live)
        assertEquals(54_100.0, p.level!!, 0.0)
        assertNull("no liquidity above yet: no target", p.target)
        // Candidate (a)'s shadow (pre-registered 06 Oct) is recorded with every signal: no level ahead, so it would not skip it.
        assertEquals(false, p.near)
        // Candidate (c), the volatility filter (06 Oct), is recorded too: with no earlier sessions loaded it cannot tell, so
        // it is recorded as not skipped - and the trade went ahead exactly as before (it never changes what the arm trades).
        assertEquals(false, p.volSkip)
        // Candidate (f) (a forward test): no option candles in this feed, so its momentum cannot be read - not recorded.
        assertNull(p.strong)
        assertEquals(day.atTime(13, 0), p.signalBar)
        // The owner's 15% stop rests in the book at 85% of the fill.
        assertEquals(com.optionslab.engine.orb.LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
        assertEquals(p.entry * 0.85, p.stopTrigger!!, 0.06)
        // 16 Oct: the position popup's Exits line: its 15% stop, its exit time and its 20-minute check (no level ahead: no target).
        val exits = com.optionslab.ira.ArmExits.line(false, p.symbol)!!
        assertTrue(exits, exits.startsWith("stop %.2f · time ".format(java.util.Locale.ENGLISH, p.stopTrigger!!)) && exits.endsWith(" · 20-minute check at 13:25"))
        val stop = Paper.state.orders.single { it.action == "SELL" }
        assertEquals(p.stopOrderId, stop.orderId); assertEquals("trigger pending", stop.status)
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals(1, buys.size); assertTrue(buys.single().symbol.endsWith("CE")); assertEquals("complete", buys.single().status)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 9))
        assertTrue("still holding inside the bar", row().today.single().open)
        tick(LocalTime.of(13, 10))                                          // the 13:05 bar closed back under 54,100
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("failed_break", closed.why)
        assertNull("closed: no exits line", com.optionslab.ira.ArmExits.line(false, p.symbol))
        // The row counts it from 06 Oct, with and without each candidate (neither would have dropped it).
        val s = row().shadow!!
        assertEquals(1, s.all.trades); assertEquals(1, s.withoutNear.trades); assertEquals(1, s.withoutFin30.trades)
        assertEquals(1, s.volAll.trades); assertEquals(1, s.withoutVol.trades)
        assertEquals((closed.grossPnl ?: 0.0) - closed.charges, s.all.net, 0.01)
        // Saved and read back: the flag survives a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(false, row().today.single().near)
        assertEquals(false, row().today.single().volSkip)
        assertEquals(1, Paper.state.orders.count { it.action == "SELL" && it.status == "complete" })
        assertEquals("the resting stop was taken out first", "cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
        // One trade only: no second entry on the same break.
        passes(LocalTime.of(13, 11), LocalTime.of(13, 30))
        assertEquals(1, row().today.size)
    }

    // ---- 20 Oct, enhancement round 1 (idea 3): the no-twin rule ----------------------------------------------------------

    /** The 13:05 entry's own contract (the 1-ITM call) as the paper account names it. */
    private fun theCall(): String = Paper.contractFor("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE)!!.symbol

    /** 19 Oct: both books held one put and were stopped together. Another book holding THIS option: a would-be trade, not bought. */
    @Test fun anEntryOnAnOptionTheOtherBookHoldsIsAWouldBeTradeOnPaper() {
        val call = theCall()
        OrbArms.testTwinHolders = { listOf(com.optionslab.ira.LiquidityTwin.Holding("liquidity15", call, live = false)) }
        assertTrue("on by this update", LiquidityTest.noTwinOn())
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue("nothing bought", Paper.state.orders.isEmpty())
        assertTrue(row().today.isEmpty())
        assertTrue(row().status, row().status.contains("Not bought (a would-be trade, counted apart): Liquidity 15m already holds $call"))
        val rec = LiquidityTest.twins().single()
        assertEquals("liquidity5", rec.book); assertEquals(call, rec.symbol); assertTrue(rec.applied); assertFalse(rec.live)
        assertEquals("liquidity15", rec.by)
        assertTrue(LiquidityTest.twinLine(), LiquidityTest.twinLine().contains("today 1 would-be trade on paper"))
        // The bar is decided once: no second would-be record on the next passes.
        passes(LocalTime.of(13, 6), LocalTime.of(13, 12))
        assertEquals(1, LiquidityTest.twins().size)
    }

    /** Boss's switch off (or its Undo): both books may buy the same option again, as before. */
    @Test fun withTheRuleOffTheSecondBookStillBuys() {
        val call = theCall()
        OrbArms.testTwinHolders = { listOf(com.optionslab.ira.LiquidityTwin.Holding("liquidity15", call, live = false)) }
        LiquidityTest.setNoTwin(false, log = false)
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue(row().today.single().open)
        assertFalse("off: only noted, never applied", LiquidityTest.twins().single().applied)
        LiquidityTest.setNoTwin(true, log = false)
    }

    /** Another strike (or the other right) is no twin: bought as before. */
    @Test fun anotherOptionIsNoTwin() {
        val put = Paper.contractFor("BANKNIFTY", day.plusDays(7), 54_000.0, Right.PE)!!.symbol
        OrbArms.testTwinHolders = { listOf(com.optionslab.ira.LiquidityTwin.Holding("liquidity15", put, live = false)) }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue(row().today.single().open)
        assertTrue(LiquidityTest.twins().isEmpty())
    }

    /** A live holding beside a paper entry is not a twin (another account): the paper entry goes on. */
    @Test fun aLiveHoldingIsNoTwinForAPaperEntry() {
        val call = theCall()
        OrbArms.testTwinHolders = { listOf(com.optionslab.ira.LiquidityTwin.Holding("liquidity15", call, live = true)) }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue(row().today.single().open)
    }

    @Test fun aBreakThatHoldsRunsToTheSquareOff() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        assertTrue(row().today.single().open)
        upstox.price(ceKey, row().today.single().entry * 1.10)              // +10%: past the 20-minute time stop's +5%
        tick(LocalTime.of(14, 0)); tick(LocalTime.of(15, 0))
        assertTrue("no failed break, no liquidity reached: still open", row().today.single().open)
        tick(LocalTime.of(15, 10))
        val p = row().today.single()
        assertFalse(p.open)
        assertEquals("session_end", p.why)
    }

    @Test fun notFivePercentUpAfterTwentyMinutesIsSoldByTheTimeStop() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        val p = row().today.single()
        upstox.price(ceKey, p.entry * 1.03)                                 // +3% only
        passes(LocalTime.of(13, 6), LocalTime.of(13, 24))
        assertTrue("held for the first 20 minutes", row().today.single().open)
        tick(LocalTime.of(13, 25))
        val closed = row().today.single()
        assertEquals("time_stop", closed.why)
        assertFalse(closed.open)
    }

    @Test fun theTimeStopWaitsForAFreshPriceRatherThanTheFillOrAnOldOne() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        val p = row().today.single()
        upstox.price(ceKey, p.entry * 1.03)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 20))
        upstox.prices.remove(ceKey)                                         // the option's feed goes quiet
        passes(LocalTime.of(13, 21), LocalTime.of(13, 27))
        val held = row().today.single()
        assertTrue("no fresh price at 20 minutes: nothing decided", held.open)
        assertFalse(held.timed)
        upstox.price(ceKey, p.entry * 1.02)                                 // back, and still under +5%
        tick(LocalTime.of(13, 28))
        assertEquals("time_stop", row().today.single().why)
    }

    @Test fun fivePercentUpAfterTwentyMinutesKeepsTheTrade() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        upstox.price(ceKey, row().today.single().entry * 1.06)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 40))
        val p = row().today.single()
        assertTrue("+6% at 20 minutes: held", p.open)
        assertTrue("decided once", p.timed)
    }

    @Test fun theIndexTradingThirtyPointsBackThroughTheLevelSellsAtOnce() {
        failDay = false; dipDay = true
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        assertTrue(row().today.single().open)
        tick(LocalTime.of(13, 6))     // the 13:05 minute traded down to 54,060: 40 points under 54,100, though it closed above
        val closed = row().today.single()
        assertEquals("index_stop", closed.why)
    }

    @Test fun theOptionFalling15PercentIsSoldByItsStop() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open)
        upstox.price(ceKey, p.entry * 0.80)                                 // the call drops 20%: through its 15% stop
        at(LocalTime.of(13, 20))
        runBlocking { Paper.tick() }                                        // the market watch matches the resting stop
        tick(LocalTime.of(13, 20))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("stop", closed.why)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun armedToAskItWaitsForTheApprovalThenBuys() {
        armLiquidity(automatic = false)
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val r = row()
        assertTrue("nothing bought before the approval", Paper.state.orders.isEmpty())
        val pd = r.pending!!
        assertEquals("CE", pd.right); assertEquals(54_000, pd.strike); assertEquals(54_100.0, pd.level!!, 0.0)   // strike: one in the money (liq2)
        assertEquals(day.atTime(13, 10), pd.expires)                         // valid until the next 5-minute bar closes
        assertTrue(r.status, r.status.contains("Breakout: waiting for your approval."))
        assertEquals("candidate (c)'s flag waits with the signal", false, pd.volSkip)
        val msg = runBlocking { OrbArms.approve("liquidity") }
        assertEquals("Entered (paper).", msg)
        val p = row().today.single()
        assertTrue(p.open); assertEquals("CE", p.right); assertEquals(54_100.0, p.level!!, 0.0)
        assertEquals(false, p.volSkip)
        assertNull("the approval is used up", row().pending)
        assertEquals(1, Paper.state.orders.count { it.action == "BUY" })
    }

    @Test fun aSkippedSignalBuysNothing() {
        armLiquidity(automatic = false)
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals("Skipped.", runBlocking { OrbArms.skip("liquidity") })
        assertNull(row().pending)
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun itsTradesAreNotInTheOrbForwardTest() {
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 10))                   // bought at 13:05, sold at 13:10 (failed break)
        assertFalse(row().today.single().open)
        assertEquals(0, runBlocking { OrbArms.view() }.forward.trades)
    }

    /** FINNIFTY's day: the BANKNIFTY shape at 24,000 (swing high 24,050 at 10:55, second rejection 11:25, break at 13:00). */
    private fun finBar(k: Int): DoubleArray = when (k) {
        20 -> doubleArrayOf(24_025.0, 24_050.0, 24_020.0, 24_030.0)
        26 -> doubleArrayOf(24_020.0, 24_035.0, 24_010.0, 24_015.0)
        45 -> doubleArrayOf(24_010.0, 24_075.0, 24_008.0, 24_070.0)
        else -> if (k > 45) doubleArrayOf(24_070.0, 24_070.0, 24_065.0, 24_070.0) else doubleArrayOf(24_000.0, 24_005.0, 23_995.0, 24_000.0)
    }

    @Test fun finniftyIsTradedTooOnItsOwnChartAndOptions() {
        finniftyDay()
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("liquidity5_fin", p.arm)
        assertTrue(p.symbol, p.symbol.startsWith("FINNIFTY") && p.symbol.endsWith("24000CE"))                // one strike in the money (liq2)
        assertEquals(65, p.qty)                                              // FINNIFTY's own lot
        assertEquals(24_050.0, p.level!!, 0.0)
        assertEquals(p.entry * 0.85, p.stopTrigger!!, 0.06)                  // the same 15% stop
        assertTrue("nothing bought on BANKNIFTY", Paper.state.orders.none { it.symbol.startsWith("BANKNIFTY") })
    }

    /** Only FINNIFTY breaks today (at 13:00, its own lot 65). */
    private fun finniftyDay() {
        bankFlat = true
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, peKey, "BANKNIFTY-LIQ-54200PE"),
            Upstox.Contract("FINNIFTY", expiry, 24_000.0, Right.CE, 65, "NSE_FO|LIQFINCE", "FINNIFTY-LIQ-24000CE"),
            Upstox.Contract("FINNIFTY", expiry, 24_100.0, Right.PE, 65, "NSE_FO|LIQFINPE", "FINNIFTY-LIQ-24100PE")))
        upstox.price("NSE_FO|LIQFINCE", 120.0)
        upstox.price("NSE_FO|LIQFINPE", 110.0)
        OrbArms.testOtherIndexBars = { u, t ->
            if (u != "FINNIFTY") emptyList() else (9 * 60 + 15 until 15 * 60 + 30).map { day.atTime(it / 60, it % 60) }
                .filter { !it.plusMinutes(1).isAfter(t) }
                .map { start -> val b = finBar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
                    Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0) }
        }
    }

    /** MIDCPNIFTY's day (research h4): the FINNIFTY shape at 12,800 - swing high 12,850 at 10:55, second rejection 11:25, break at 13:00. */
    private fun midBar(k: Int): DoubleArray = finBar(k).map { it - 24_000.0 + 12_800.0 }.toDoubleArray()

    /** Only MIDCPNIFTY breaks today (at 13:00, its own lot 140, monthly expiry, 25-point strikes). */
    private fun midcpniftyDay() {
        bankFlat = true
        val expiry = day.plusDays(20)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, peKey, "BANKNIFTY-LIQ-54200PE"),
            Upstox.Contract("MIDCPNIFTY", expiry, 12_850.0, Right.CE, 140, "NSE_FO|LIQMIDCE", "MIDCPNIFTY-LIQ-12850CE"),
            Upstox.Contract("MIDCPNIFTY", expiry, 12_875.0, Right.CE, 140, "NSE_FO|LIQMIDCE2", "MIDCPNIFTY-LIQ-12875CE")))
        upstox.price("NSE_FO|LIQMIDCE", 120.0)
        upstox.price("NSE_FO|LIQMIDCE2", 100.0)
        OrbArms.testOtherIndexBars = { u, t ->
            if (u != "MIDCPNIFTY") emptyList() else (9 * 60 + 15 until 15 * 60 + 30).map { day.atTime(it / 60, it % 60) }
                .filter { !it.plusMinutes(1).isAfter(t) }
                .map { start -> val b = midBar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
                    Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0) }
        }
    }

    @Test fun midcpniftyIsTradedOnItsOwnChartAndOptionsOnPaper() {
        midcpniftyDay()
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("liquidity5_mid", p.arm)
        // One strike in the money on its 25-point grid: ATM 12,875 for the 12,870 close, the call one step below.
        assertTrue(p.symbol, p.symbol.startsWith("MIDCPNIFTY") && p.symbol.endsWith("12850CE"))
        assertEquals("MIDCPNIFTY's own lot from the instrument master", 140, p.qty)
        assertEquals(12_850.0, p.level!!, 0.0)
        assertEquals(p.entry * 0.85, p.stopTrigger!!, 0.06)                  // the same 15% stop
        assertFalse("paper only", p.live)
        assertTrue("nothing bought on BANKNIFTY", Paper.state.orders.none { it.symbol.startsWith("BANKNIFTY") })
        assertTrue(row().status, row().status.contains("MIDCPNIFTY 15-min:") && row().status.contains("MIDCPNIFTY 5-min:"))
    }

    // ---- 13 Oct audit fix: a failed index read stops only the books on that index --------------------------------

    /** The index's feed down: every read of it throws. */
    private fun down(index: String): Nothing = throw java.io.IOException("$index feed down")

    @Test fun aBankniftyFeedFailureLeavesTheMidcpniftyBooksTrading() {
        midcpniftyDay()
        OrbArms.testIndexBars = { down("BANKNIFTY") }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("liquidity5_mid", p.arm)
        assertTrue(p.symbol, p.symbol.startsWith("MIDCPNIFTY") && p.symbol.endsWith("12850CE"))
        val status = row().status
        assertTrue(status, status.contains("BANKNIFTY 5-min: No BANKNIFTY bars yet."))
        assertTrue(status, status.contains("BANKNIFTY 15-min: No BANKNIFTY bars yet."))
        assertFalse(status, status.contains("No MIDCPNIFTY bars") || status.contains("No FINNIFTY bars"))
    }

    @Test fun aBankniftyFeedFailureLeavesTheFinniftyBooksTrading() {
        finniftyDay()
        OrbArms.testIndexBars = { down("BANKNIFTY") }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals("liquidity5_fin", row().today.single().arm)
        assertTrue(row().status, row().status.contains("BANKNIFTY 5-min: No BANKNIFTY bars yet."))
    }

    @Test fun aMidcpniftyFeedFailureStopsOnlyTheMidcpniftyBooks() {
        finniftyDay()
        val fin = OrbArms.testOtherIndexBars!!
        OrbArms.testOtherIndexBars = { u, t -> if (u == "MIDCPNIFTY") down(u) else fin(u, t) }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals("FINNIFTY still trades on its own chart", "liquidity5_fin", row().today.single().arm)
        val status = row().status
        assertTrue(status, status.contains("MIDCPNIFTY 15-min: No MIDCPNIFTY bars yet.") && status.contains("MIDCPNIFTY 5-min: No MIDCPNIFTY bars yet."))
        assertFalse(status, status.contains("No BANKNIFTY bars") || status.contains("No FINNIFTY bars"))
    }

    @Test fun everyIndexFailingStopsEveryBookEachNamingItsIndex() {
        finniftyDay()
        OrbArms.testIndexBars = { down("BANKNIFTY") }
        OrbArms.testOtherIndexBars = { u, _ -> down(u) }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue(row().today.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        val status = row().status
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) assertTrue(status, status.contains("$u 5-min: No $u bars yet."))
    }

    // ---- 22 Oct (R100 audit A): a failed index read mid-trade never skips the exits that do not need it ---------------------

    /** Its own index's feed goes down mid-trade: the time stop (the option's price only) still sells at 20 minutes. */
    @Test fun theIndexFeedFailingMidTradeStillLetsTheTimeStopSell() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open)
        upstox.price(ceKey, p.entry * 1.03)                                 // +3% only: short of the time stop's +5%
        passes(LocalTime.of(13, 6), LocalTime.of(13, 20))
        assertTrue(row().today.single().open)
        OrbArms.testIndexBars = { down("BANKNIFTY") }                       // BANKNIFTY's candles cannot be read from here on
        passes(LocalTime.of(13, 21), LocalTime.of(13, 25))
        val closed = row().today.single()
        assertFalse("the time stop sold it although the index could not be read", closed.open)
        assertEquals("time_stop", closed.why)
    }

    /** BANKNIFTY's feed fails while BANKNIFTY and FINNIFTY each hold a call: FINNIFTY's exits still run (they used to be skipped). */
    @Test fun oneIndexFeedFailingLeavesTheOtherIndexsPositionsExits() {
        finniftyDay()
        bankFlat = false; failDay = false                                   // BANKNIFTY breaks at 13:00 too
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals("both indices hold a call", setOf("liquidity5", "liquidity5_fin"), row().today.filter { it.open }.map { it.arm }.toSet())
        val fin = row().today.single { it.arm == "liquidity5_fin" }
        val bank = row().today.single { it.arm == "liquidity5" }
        upstox.price("NSE_FO|LIQFINCE", fin.entry * 1.03)                   // both short of +5% at 20 minutes
        upstox.price(ceKey, bank.entry * 1.03)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 20))
        OrbArms.testIndexBars = { down("BANKNIFTY") }                       // the first position's index fails
        passes(LocalTime.of(13, 21), LocalTime.of(13, 25))
        val after = row().today
        assertEquals("FINNIFTY's time stop ran although BANKNIFTY's read failed first", "time_stop", after.single { it.arm == "liquidity5_fin" }.why)
        assertEquals("and BANKNIFTY's own time stop too", "time_stop", after.single { it.arm == "liquidity5" }.why)
    }

    /** Research h4 (07 Oct): a book saved with the switch on takes the MIDCPNIFTY books on paper, once; never cleared for Zerodha. */
    @Test fun theMidcpniftyBooksJoinAnArmedSwitchOnPaperOnce() {
        val books = listOf("liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin")
        fun flags(v: Boolean) = org.json.JSONObject().apply { books.forEach { put(it, v) } }
        AutomationSupport.orbState(context, org.json.JSONObject()
            .put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(true))
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)
                .put(com.optionslab.engine.orb.RetiredArms.UNRETIRE))
            .put("positions", org.json.JSONArray()))
        val r = row()
        assertTrue(r.armed); assertTrue(r.automatic)
        assertFalse("the MIDCPNIFTY books are never cleared for Zerodha by this change", r.liveOk)
        val joined = com.optionslab.engine.orb.LiquidityRules.MIDCP_JOINED
        assertTrue(r.status, r.status.contains("MIDCPNIFTY 15-min: $joined") && r.status.contains("MIDCPNIFTY 5-min: $joined"))
        val states = runBlocking { OrbArms.liquidityDay(day) }.first
        assertTrue(states.toString(), states.filter { it.book.endsWith("_mid") }.let { m -> m.size == 2 && m.all { it.armed } })
        assertEquals(2, Diag.lines().count { it.contains(joined) })
        // Once: switched off afterwards, a restart leaves them off and says nothing again.
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertFalse(row().armed)
        assertEquals(2, Diag.lines().count { it.contains(joined) })
    }

    @Test fun aSwitchedOffBookLeavesTheMidcpniftyBooksOffUntilTheSwitchComesOn() {
        // (9 Oct's parking marked done: this test is about the MIDCPNIFTY books alone.)
        AutomationSupport.orbState(context, org.json.JSONObject()
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)
                .put(com.optionslab.engine.orb.RetiredArms.UNRETIRE).put(com.optionslab.engine.orb.ParkedArms.MIGRATION))
            .put("positions", org.json.JSONArray()))
        assertFalse(row().armed)
        assertTrue(runBlocking { OrbArms.liquidityDay(day) }.first.none { it.armed })
        armLiquidity()
        assertTrue(runBlocking { OrbArms.liquidityDay(day) }.first.all { it.armed })
    }

    // ---- Liquidity's size (Boss's 06 Oct choice: 2-3 lots) ----------------------------------------------------------------

    @Test fun twoLotsBuyTwiceTheContractsLotAndTheStopAndExitCoverIt() {
        assertTrue(runBlocking { OrbArms.setLiquidityLots(2, "Boss on the row") }.startsWith("Liquidity 15+5 now buys 2 lots"))
        assertEquals(2, row().lots)
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("2 x BANKNIFTY's lot of 30", 60, p.qty)
        assertEquals(30, p.lot); assertEquals(2.0, p.lots, 0.0)
        // The 15% resting stop covers the full quantity.
        val stop = Paper.state.orders.single { it.action == "SELL" }
        assertEquals(60, stop.quantity); assertEquals(p.stopOrderId, stop.orderId)
        assertEquals(60, Paper.state.orders.single { it.action == "BUY" }.quantity)
        tick(LocalTime.of(13, 10))                                          // the failed break sells it all
        val closed = row().today.single()
        assertEquals("failed_break", closed.why)
        assertEquals(60, Paper.state.orders.single { it.action == "SELL" && it.status == "complete" }.quantity)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
        // The row's P&L is the trade's rupees at its real quantity; the shadow counts it per lot.
        val net = (closed.grossPnl ?: 0.0) - closed.charges
        assertEquals((closed.exit!! - closed.entry) * 60, closed.grossPnl!!, 0.001)
        assertEquals(net / 2, row().shadow!!.all.net, 0.01)
        // Saved and read back: the lot and the size survive a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(30, row().today.single().lot); assertEquals(2, row().lots)
    }

    @Test fun aNewSizeLeavesTheOpenPositionAsItWasAndAppliesToTheNextEntry() {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        assertEquals(30, row().today.single().qty)
        upstox.price(ceKey, row().today.single().entry * 1.10)              // +10%: past the 20-minute time stop's +5%
        val msg =runBlocking { OrbArms.setLiquidityLots(3, "Boss on the row") }
        assertTrue(msg, msg.contains("the open position keeps its own quantity"))
        assertEquals(3, row().lots)
        val held = row().today.single()
        assertTrue(held.open); assertEquals("bought 1 lot: still 1 lot", 30, held.qty)
        tick(LocalTime.of(15, 10))
        val closed = row().today.single()
        assertEquals("session_end", closed.why)
        assertEquals("its exit sells what it holds, no more", 30, Paper.state.orders.filter { it.action == "SELL" && it.status == "complete" }.sumOf { it.quantity })
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun finniftyTakesItsOwnLotTimesTheSize() {
        finniftyDay()
        runBlocking { OrbArms.setLiquidityLots(3, "Boss on the row") }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("liquidity5_fin", p.arm)
        assertEquals("3 x FINNIFTY's lot of 65", 195, p.qty)
        assertEquals(195, Paper.state.orders.single { it.action == "SELL" }.quantity)
    }

    @Test fun aBookSavedBeforeTheSizeTakesOneLotOnceAndTheArmLogSaysSo() {
        AutomationSupport.orbState(context, org.json.JSONObject().put("positions", org.json.JSONArray()))
        assertEquals("1 lot by default since 08 Oct (research X1)", 1, row().lots)
        assertTrue(Diag.lines().toString(), Diag.lines().any { it.contains(com.optionslab.engine.orb.LiquidityLots.MIGRATED) })
        // Boss's own choice afterwards stays: the change ran once.
        runBlocking { OrbArms.setLiquidityLots(2, "Boss on the row") }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(2, row().lots)
        assertTrue(Diag.lines().any { it.contains("Liquidity 15+5: size 1 lot -> 2 lots (Boss on the row)") })
    }

    /** "Liquidity ko 3 lot karo": said back and asked; nothing changes until Boss's yes, and a raise counts as more risk. */
    @Test fun jarvisAsksBeforeRaisingTheLotsAndChangesNothingUntilTheYes() {
        val raise = com.optionslab.ira.Ask.parse("liquidity ko 3 lot karo").command!!
        assertEquals(com.optionslab.ira.Command.Kind.SET_LIMIT, raise.kind)
        assertTrue("a raise needs Boss's own voice", com.optionslab.app.ira.IraActions.loosens(raise))
        val (said, act) = runBlocking { com.optionslab.app.ira.IraActions.prepare(raise) }
        assertTrue(said, said.startsWith("change Liquidity 15+5's lots a trade from 1 to 3 (this allows more risk"))
        assertEquals("nothing changed before the yes", 1, row().lots)
        val done = runBlocking { act!!() }
        assertTrue(done, done.startsWith("Liquidity 15+5 now buys 3 lots"))
        assertEquals(3, row().lots)
        assertTrue(Diag.lines().any { it.contains("Liquidity 15+5: size 1 lot -> 3 lots (Jarvis, on Boss's yes)") })
        // Fewer lots: still said back and confirmed, but no more risk.
        val cut = com.optionslab.ira.Ask.parse("set liquidity to 2 lots").command!!
        assertFalse(com.optionslab.app.ira.IraActions.loosens(cut))
        val (cutSaid, cutAct) = runBlocking { com.optionslab.app.ira.IraActions.prepare(cut) }
        assertEquals("change Liquidity 15+5's lots a trade from 3 to 2", cutSaid)
        // Changed on the row meanwhile: the yes applies nothing.
        runBlocking { OrbArms.setLiquidityLots(1, "Boss on the row") }
        assertTrue(runBlocking { cutAct!!() }.contains("changed since you asked"))
        assertEquals(1, row().lots)
        // Only 1, 2 or 3; the same size is said, not asked.
        assertEquals("Liquidity 15+5 trades 1, 2 or 3 lots, Boss - not 5." to null,
            runBlocking { com.optionslab.app.ira.IraActions.prepare(com.optionslab.ira.Ask.parse("set liquidity to 5 lots").command!!) })
        assertEquals("Liquidity 15+5 already trades 1 lot." to null,
            runBlocking { com.optionslab.app.ira.IraActions.prepare(com.optionslab.ira.Ask.parse("set liquidity to 1 lot").command!!) })
    }

    @Test fun onlyOneTwoOrThreeLots() {
        assertEquals("Liquidity 15+5 trades 1, 2 or 3 lots, not 4.", runBlocking { OrbArms.setLiquidityLots(4, "test") })
        assertEquals(1, row().lots)
        assertEquals("Liquidity 15+5 already trades 1 lot.", runBlocking { OrbArms.setLiquidityLots(1, "test") })
    }

    /** A build that ran FINNIFTY on 15 + 5 minutes saved its 15-minute book as "liquidity15_fin": it carries on as the 30-minute book. */
    @Test fun finniftysOld15MinuteBookCarriesOnAsItsThirtyMinuteBook() {
        val books = listOf("liquidity15", "liquidity5", "liquidity15_fin", "liquidity5_fin")
        fun flags(v: Boolean) = org.json.JSONObject().apply { books.forEach { put(it, v) } }
        val t = day.atTime(12, 50)
        AutomationSupport.orbState(context, org.json.JSONObject()
            .put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
            // Saved after the 06 Oct update (its one-time switch-off already done): armed again by Boss.
            .put("migrated", org.json.JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE))
            .put("positions", org.json.JSONArray().put(org.json.JSONObject().put("arm", "liquidity15_fin")
                .put("symbol", "FINNIFTY-LIQ-24050CE").put("right", "CE").put("qty", 65).put("entry", 120.0)
                .put("entryTime", t.minusMinutes(20).toString()).put("signalBar", t.minusMinutes(50).toString())
                .put("level", 24_050.0))))
        val r = row()
        assertTrue(r.armed); assertTrue(r.automatic)
        assertEquals("liquidity30_fin", r.open!!.arm)
        assertTrue(r.status, r.status.contains("FINNIFTY 30-min:") && r.status.contains("FINNIFTY 5-min:") && !r.status.contains("FINNIFTY 15-min"))
        assertTrue(r.status, r.status.contains("BANKNIFTY 15-min:") && r.status.contains("BANKNIFTY 5-min:"))
    }

    /**
     * Boss's 07 Oct decision (research/HUNT_H21.md): Liquidity 15+5 has priority over the ORB arms. ORB holds a BANKNIFTY call
     * on paper since 12:30; the 13:00 break is still bought at 13:05, beside it, and the arms' log says why.
     */
    @Test fun aLiquidityEntryWhileOrbHoldsBankNiftyIsTakenAndTheLogSaysLiquidityHasPriority() {
        failDay = false
        val t = day.atTime(12, 30)
        AutomationSupport.orbState(context, org.json.JSONObject().put("liqLots", 1)
            .put("positions", org.json.JSONArray().put(org.json.JSONObject().put("arm", "orb")
                .put("symbol", "BANKNIFTY-ORB-54000CE").put("right", "CE").put("qty", 30).put("entry", 300.0)
                .put("entryTime", t.toString()).put("signalBar", t.minusMinutes(5).toString()))))
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue("taken beside ORB's call", p.open)
        assertEquals("CE", p.right); assertEquals("liquidity5", p.arm)
        assertEquals("one paper buy: Liquidity's", 1, Paper.state.orders.count { it.action == "BUY" })
        val orb = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }
        assertTrue("ORB's call is untouched", orb.open != null)
        val log = Diag.lines()
        assertTrue(log.toString(), log.any { it.contains("Liquidity 5m is not held back by ORB's BANKNIFTY-ORB-54000CE: Liquidity has priority over ORB arms.") })
    }

    @Test fun disarmedItBuysNothing() {
        passes(LocalTime.of(12, 50), LocalTime.of(13, 15))
        assertTrue(row().today.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }

    /** An option's 1-minute candles at [t] from 12:30: [from] moving [step] a minute. */
    private fun option(t: LocalDateTime, from: Double, step: Double): List<Upstox.Bar> = (12 * 60 + 30 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }.filter { !it.plusMinutes(1).isAfter(t) }
        .mapIndexed { i, start -> val c = from + step * i; Upstox.Bar(start.atZone(IST).toEpochSecond(), c, c, c, c, 1000, 0) }

    @Test fun candidateFIsRecordedWithTheSignalAndNeverChangesTheTrade() {
        // The call's premium rising and the put's at the same strike falling: momentum. But the 13:00 bar closed 40 points
        // (7.4 bp) beyond the swept 54,100, not more than 10.4 bp: (f) would not have taken it.
        OrbArms.testOptionBars = { key, t -> when (key) { ceKey -> option(t, 200.0, 1.0); pe0Key -> option(t, 300.0, -1.0); else -> emptyList() } }
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue("the arm traded exactly as before", p.open)
        assertEquals(false, p.strong)
        assertTrue(com.optionslab.engine.orb.LiquidityShadow.premiumMomentum(
            option(day.atTime(13, 5), 200.0, 1.0).map { com.optionslab.engine.orb.Bar(java.time.Instant.ofEpochSecond(it.epochSecond).atZone(IST).toLocalDateTime(), it.open, it.high, it.low, it.close) },
            option(day.atTime(13, 5), 300.0, -1.0).map { com.optionslab.engine.orb.Bar(java.time.Instant.ofEpochSecond(it.epochSecond).atZone(IST).toLocalDateTime(), it.open, it.high, it.low, it.close) },
            day.atTime(13, 5)) == true)
        tick(LocalTime.of(13, 10))
        assertFalse(row().today.single().open)
        val s = row().shadow!!
        assertEquals("counted for (f): its flag was recorded", 1, s.strongAll.trades)
        assertEquals("and it would have skipped it", 0, s.withStrong.trades)
        // Saved and read back: the flag survives a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(false, row().today.single().strong)
    }

    // ---- the order flow beside the entry (9 Oct): SHADOW logs, CONFIRM only ever skips ----

    /** The flow's read for every index: [buyers] the buyers' share (30: sellers at 70, against a call). */
    private fun flow(buyers: Int) {
        com.optionslab.app.data.FlowGate.testNowSec = 1_000
        OrderFlowLive.testRead = { u -> com.optionslab.app.testing.FlowFixtures.read(u, buyers, 1_000) }
    }

    @Test fun orderFlowShadowLogsTheSignalAndChangesNothing() {
        flow(30)
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue("the arm traded exactly as before, against the flow", p.open)
        assertEquals("CE", p.right)
        val s = FlowGate.signalsForTest().single { it.key == "liquidity:BANKNIFTY" }
        assertEquals(com.optionslab.ira.OrderFlow.Mode.SHADOW, s.mode)
        assertEquals(com.optionslab.ira.OrderFlow.Agreement.DISAGREES, s.agreement)
        assertFalse(s.skipped); assertFalse(s.live); assertEquals(1, s.side); assertEquals(30, s.buyers)
        assertTrue("its paper order is kept for the result", s.taken && s.orderId == p.entryOrderId)
    }

    @Test fun orderFlowConfirmSkipsAPaperEntryTheFlowDisagreesWithAndLogsIt() {
        val said = FlowGate.set("liquidity:BANKNIFTY", com.optionslab.ira.OrderFlow.Mode.CONFIRM)
        assertTrue(said, said.startsWith("Liquidity 15+5 BankNifty: an entry is skipped unless the order flow agrees"))
        flow(30)
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue("skipped: nothing bought", row().today.isEmpty())
        assertTrue("no paper order at all", Paper.state.orders.isEmpty())
        val s = FlowGate.signalsForTest().single { it.key == "liquidity:BANKNIFTY" }
        assertTrue(s.skipped); assertEquals(FlowGate.SKIPPED, s.verdict); assertFalse(s.taken)
        assertTrue(Diag.lines().any { "skipped by flow" in it })
    }

    @Test fun orderFlowConfirmTakesTheEntryWhenTheFlowAgrees() {
        FlowGate.set("liquidity:BANKNIFTY", com.optionslab.ira.OrderFlow.Mode.CONFIRM)
        flow(72)
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open); assertEquals(30, p.qty)
        val s = FlowGate.signalsForTest().single { it.key == "liquidity:BANKNIFTY" }
        assertEquals(com.optionslab.ira.OrderFlow.Agreement.AGREES, s.agreement); assertFalse(s.skipped); assertTrue(s.taken)
    }

    @Test fun orderFlowConfirmWithNoFlowHasNoOpinionAndTheEntryGoesAhead() {
        FlowGate.set("liquidity:BANKNIFTY", com.optionslab.ira.OrderFlow.Mode.CONFIRM)
        com.optionslab.app.data.FlowGate.testNowSec = 1_000
        OrderFlowLive.testRead = { null }                                   // no Zerodha stream: no flow at all
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue("the strategy's own entry, as without the flow", row().today.single().open)
        val s = FlowGate.signalsForTest().single { it.key == "liquidity:BANKNIFTY" }
        assertEquals(com.optionslab.ira.OrderFlow.Agreement.UNKNOWN, s.agreement); assertFalse(s.skipped); assertTrue(s.taken)
    }

    // ---- the trade manager in SHADOW (10 Oct; hooked, SHADOW by default): the same trades and P&L, its would-have exits recorded only ----

    @Test fun inShadowLiquidityTradesExactlyAsBefore() = ManagerShadow.rerun("liquidity", { looks = it }) { buysTheCallWhenAPoolOnASwingHighIsTakenAndSellsWhenTheBreakFails() }

    @Test fun inShadowLiquidityStopTradesExactlyAsBefore() = ManagerShadow.rerun("liquidity", { looks = it }) { theOptionFalling15PercentIsSoldByItsStop() }

    // ---- Boss's own Rs 500 give-back trail on paper (R19b, 13 Oct: failed research, their opt-in switch, off by default; from +Rs 1,000 a lot since R19c) ----

    /** [body] with Boss's paper trail switch on; the state put back after. */
    private fun <T> withTrail(body: () -> T): T {
        val before = LiquidityTest.state.value
        LiquidityTest.setStateForTest(before.copy(trail = true))
        try { return body() } finally { LiquidityTest.setStateForTest(before) }
    }

    @Test fun offByDefaultTheTradeRunsOnPlainRules() {
        assertFalse("off unless Boss switches it on", LiquidityTest.trailOn())
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertNull("plain rules", p.trail)
        upstox.price(ceKey, p.entry * 1.10)
        tick(LocalTime.of(13, 6))
        assertEquals("no trail: the 15% stop stays where it was", com.optionslab.engine.orb.LiquidityRules.stopTrigger(p.entry), row().today.single().stopTrigger)
        assertEquals(com.optionslab.engine.orb.LiquidityTrail.PLAIN, com.optionslab.engine.orb.LiquidityTrail.label(row().today.single().trail))
    }

    @Test fun withTheTrailTheRestingStopRisesAndFillsAsTheTrail() = withTrail {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("entered under Boss's Rs 500 a lot", com.optionslab.engine.orb.LiquidityTrail.GIVE_BACK_RS, p.trail!!, 0.0)
        assertEquals("from +Rs 1,000 a lot (R19c, Boss's rule)", com.optionslab.engine.orb.LiquidityTrail.ACTIVATE_RS, p.trailFrom!!, 0.0)
        assertEquals(30, p.lot)
        assertFalse(p.live)
        // +Rs 900 a lot (30 points): not yet +Rs 1,000, the stop stays at -15%.
        upstox.price(ceKey, p.entry + 30.0)
        tick(LocalTime.of(13, 6))
        assertEquals(com.optionslab.engine.orb.LiquidityRules.stopTrigger(p.entry), row().today.single().stopTrigger)
        // +Rs 1,200 a lot (40 points): the resting stop moves up to the best less Rs 500 a lot (16.67 points), on the tick.
        upstox.price(ceKey, p.entry + 40.0)
        tick(LocalTime.of(13, 7))
        val up = row().today.single()
        val want = com.optionslab.engine.orb.LiquidityTrail.level(p.entry, p.entry + 40.0, 30, 500.0, activateRs = 1000.0)!!
        assertTrue("never under +Rs 500 a lot", (want - p.entry) * 30 >= 500.0 - 1e-6)
        assertEquals(want, up.stopTrigger!!, 1e-9)
        assertEquals("the paper book's stop order moved too", want, Paper.state.orders.single { it.orderId == up.stopOrderId }.triggerPrice!!.toDouble(), 1e-9)
        // A smaller price never lowers it.
        upstox.price(ceKey, p.entry + 30.0)
        tick(LocalTime.of(13, 8))
        assertEquals(want, row().today.single().stopTrigger!!, 1e-9)
        assertTrue(row().today.single().open)
        // Saved and read back: the trade keeps its rule over a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(500.0, row().today.single().trail!!, 0.0)
        assertEquals(1000.0, row().today.single().trailFrom!!, 0.0)
        // The price gives back through it: the paper book fills the resting stop, booked as the trail.
        upstox.price(ceKey, p.entry + 5.0)
        at(LocalTime.of(13, 9))
        runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 9))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(com.optionslab.engine.orb.LiquidityTrail.WHY, closed.why)
        assertTrue("sold above the price paid", closed.exit!! > p.entry)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun withTheTrailAndNoRestingStopTheAppSellsItItself() = withTrail {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        runBlocking { Paper.cancel(checkNotNull(p.stopOrderId), "test") }   // its resting stop is gone
        upstox.price(ceKey, p.entry + 40.0)                                // +Rs 1,200 a lot: Boss's trail is on
        tick(LocalTime.of(13, 6))
        assertTrue(row().today.single().open)
        upstox.price(ceKey, p.entry + 20.0)                                // Rs 600 a lot under its best, far above the 15% stop
        tick(LocalTime.of(13, 7))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(com.optionslab.engine.orb.LiquidityTrail.WHY, closed.why)
    }

    /** 13 Oct review: the trail starts at exactly +Rs 1,000 a lot (R19c, Boss's rule) - a hair under it, the stop stays put. */
    @Test fun theTrailStartsAtExactlyPlusRs1000ALot() = withTrail {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals(30, p.lot)
        val edge = p.entry + com.optionslab.engine.orb.LiquidityTrail.ACTIVATE_RS / 30     // +Rs 1,000 a lot, to the paisa
        val own = com.optionslab.engine.orb.LiquidityRules.stopTrigger(p.entry)
        // One tick under the edge (+Rs 998.50 a lot): not yet, the stop stays at -15%.
        upstox.price(ceKey, edge - 0.05)
        tick(LocalTime.of(13, 6))
        assertEquals(own, row().today.single().stopTrigger)
        // At the edge itself: the stop moves up to the best less Rs 500 a lot, never under +Rs 500 a lot.
        upstox.price(ceKey, edge)
        tick(LocalTime.of(13, 7))
        val want = com.optionslab.engine.orb.LiquidityTrail.level(p.entry, edge, 30, 500.0, activateRs = 1000.0)
        assertTrue("the edge starts the trail", want != null)
        assertEquals(want!!, row().today.single().stopTrigger!!, 1e-9)
        assertTrue((want - p.entry) * 30 >= 500.0 - 1e-6)
        assertTrue(row().today.single().open)
    }

    /**
     * 13 Oct review: a trailed trade is kept out of the plain-rules records the 26 Oct decision reads - the paper test's judge
     * (said apart, labelled) and the paper stop rule.
     */
    @Test fun aTrailedTradeIsKeptOutOfThePaperTestAndThePaperStopRule() = withTrail {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        upstox.price(ceKey, p.entry + 40.0)
        tick(LocalTime.of(13, 6))
        upstox.price(ceKey, p.entry + 5.0)
        at(LocalTime.of(13, 7))
        runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 7))
        val closed = row().today.single()
        assertFalse(closed.open); assertEquals(500.0, closed.trail!!, 0.0)
        assertEquals("the paper stop rule counts plain-rules trades only", 0, OrbArms.liquidityStopPaperHint.trades)
        // The paper test's view is in every build but IraGoldAlgo (where it is never read).
        val view = row().test
        if (com.optionslab.app.BuildConfig.GOLD) assertNull(view) else {
            org.junit.Assert.assertNotNull("the paper test's view is read", view)
            val t = view!!
            assertTrue(t.paperTest, t.paperTest.contains("0 trades since 13 Oct"))
            assertTrue(t.paperTest, t.paperTest.contains("Kept apart, with ₹500 trail (failed research, your choice): 1 trade,"))
        }
        assertTrue("not in the forward check either", ForwardRecords.liquidity(runBlocking { OrbArms.closedPaper() }).isEmpty())
        // Jarvis's Liquidity record: the plain rules' (none yet), the trailed trade on a labelled line of its own.
        assertTrue(com.optionslab.app.ira.IraBots.tradeOf(closed).trail)
        val said = runBlocking { com.optionslab.app.ira.IraBots.liquidityRecord(com.optionslab.ira.LiquidityRecord.Q()) }
        assertTrue(said, said.startsWith("Liquidity 15+5 has no closed paper trade on plain rules in its book yet"))
        assertTrue(said, said.contains("With your ₹500 trail (failed research, your choice), kept apart from the plain-rules record above: 1 trade, "))
    }

    /**
     * 13 Oct review: with the trail on under a lab lock variant, a stop that rests at the LOCK's level (the trail not started yet)
     * fills as the profit lock, not as the trail.
     */
    @Test fun underALockVariantAStopAtTheLocksLevelIsTheProfitLockNotTheTrail() = withLockVariant(0.3) { withTrail {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.ladder); assertEquals(500.0, p.trail!!, 0.0)
        // +25 points (+Rs 750 a lot): the lock's first rung (a quarter of its 30% reference) is earned, the trail (+Rs 1,000) is not.
        upstox.price(ceKey, p.entry + 25.0)
        tick(LocalTime.of(13, 6))
        val held = row().today.single()
        assertTrue(held.open)
        assertTrue("the lock moved the stop to the price paid or higher", held.stopTrigger!! >= p.entry)
        assertNull("the trail has not started", com.optionslab.engine.orb.LiquidityTrail.level(p.entry, held.peak, 30, 500.0, activateRs = 1000.0))
        // Back under it: the paper book fills the resting stop - the lock's, so booked as the lock.
        upstox.price(ceKey, p.entry - 5.0)
        at(LocalTime.of(13, 7))
        runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 7))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("profit_lock", closed.why)
    } }

    // ---- review G2-4 / G2-8 (11 Oct): Liquidity under a paper profit-lock variant ----

    /** A paper profit-lock variant for Liquidity in force today ([lock] of the premium paid), as the lab promotes one (S9: exits only). */
    private fun <T> withLockVariant(lock: Double, body: () -> T): T {
        org.junit.Assume.assumeTrue("the strategy lab is in IraAlgo with Jarvis only", VariantRunner.enabled())
        VariantRunner.setForTest(com.optionslab.ira.VariantLab.Book(tunes = listOf(com.optionslab.ira.VariantLab.Tune("liquidity", false,
            day.minusDays(1), mapOf(com.optionslab.ira.ArmParams.LOCK to lock), "test"))), com.optionslab.ira.StrategyForge.Book())
        try { return body() } finally { VariantRunner.resetForTest() }
    }

    /** G2-4: its resting stop gone, an earned lock is sold by the app (as the ORB arms'), not held down to the 15% stop. */
    @Test fun anEarnedLockIsSoldWhenItsStopIsGone() = withLockVariant(0.3) {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open); assertTrue("laddered under the lock variant", p.ladder)
        // Its resting stop is gone (rejected or cancelled): the app watches the position itself.
        runBlocking { Paper.cancel(checkNotNull(p.stopOrderId), "test") }
        upstox.price(ceKey, p.entry * 1.6)                                   // +60%: the lock is earned (reference 30% of the price paid)
        tick(LocalTime.of(13, 6))
        assertTrue(row().today.single().open)
        upstox.price(ceKey, p.entry * 1.02)                                  // back under the lock, far above the 15% stop
        tick(LocalTime.of(13, 7))
        val closed = row().today.single()
        assertFalse("sold at the lock, not held down to the stop", closed.open)
        assertEquals("profit_lock", closed.why)
    }

    /** G2-8: under a lock variant the worst premium seen is still kept for Jarvis's trade lesson. */
    @Test fun underALockVariantItsWorstPremiumIsStillKept() = withLockVariant(0.3) {
        failDay = false
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.ladder)
        upstox.price(ceKey, p.entry * 0.95)                                  // 5% under the price paid, above the 15% stop
        tick(LocalTime.of(13, 6))
        val held = row().today.single()
        assertTrue(held.open)
        assertEquals(p.entry * 0.95, checkNotNull(held.low), 0.06)
    }
}
