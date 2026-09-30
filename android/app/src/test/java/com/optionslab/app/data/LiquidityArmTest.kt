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
    private var failDay = true

    /** The 5-minute bar k (09:15 + 5k) as (open, high, low, close). */
    private fun bar(k: Int): DoubleArray = when {
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k == 46 && failDay -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)
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
            Upstox.Contract("BANKNIFTY", expiry, 54_100.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54100CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_100.0, Right.PE, 30, peKey, "BANKNIFTY-LIQ-54100PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
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
        assertTrue("the books never show as rows of their own", arms.none { it == "liquidity15" || it == "liquidity5" })
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
        tick(LocalTime.of(14, 0)); tick(LocalTime.of(15, 0))
        assertTrue("no failed break, no liquidity reached: still open", row().today.single().open)
        tick(LocalTime.of(15, 10))
        val p = row().today.single()
        assertFalse(p.open)
        assertEquals("session_end", p.why)
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
        assertEquals("CE", pd.right); assertEquals(54_100, pd.strike); assertEquals(54_100.0, pd.level!!, 0.0)
        assertEquals(day.atTime(13, 10), pd.expires)                         // valid until the next 5-minute bar closes
        assertTrue(r.status, r.status.contains("Breakout: waiting for your approval."))
        val msg = runBlocking { OrbArms.approve("liquidity") }
        assertEquals("Entered (paper).", msg)
        val p = row().today.single()
        assertTrue(p.open); assertEquals("CE", p.right); assertEquals(54_100.0, p.level!!, 0.0)
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

    @Test fun disarmedItBuysNothing() {
        passes(LocalTime.of(12, 50), LocalTime.of(13, 15))
        assertTrue(row().today.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }
}
