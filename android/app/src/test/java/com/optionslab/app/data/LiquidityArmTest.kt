package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
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

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

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
}
