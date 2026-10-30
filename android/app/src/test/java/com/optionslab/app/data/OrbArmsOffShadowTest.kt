package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiquidityRules
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
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
 * an ORB-family arm or a Liquidity book that is OFF still decides on every bar and hands each would-be entry to [OffShadow] with
 * its exits - and never places a paper order; an arm that is ON trades paper exactly as before and keeps no would-be trade.
 *
 * The ORB day is [OrbArmsDayTest]'s (the 09:15-10:04 range 53,800-54,000, the break on the 10:30 bar, decided at 10:35); the
 * Liquidity day is [LiquidityArmTest]'s failed-break day (the 13:00 bar takes the pool on the 54,100 swing; the 13:05 bar closes
 * back under it).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        OffShadow.init(context)
        OffShadow.wipeForErase()
        OffShadow.testSession = true
        // The shadow's own price read: the option at 300 (both days' options are priced at 300 by the feed too).
        OffShadow.testPrice = { _ -> Triple(300.0, null, null) }
        OrbArms.testOffShadow = true
    }

    @After fun down() {
        OrbArms.testOffShadow = false
        OffShadow.wipeForErase()
        com.optionslab.ira.ArmExits.clear()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        OffShadow.testNow = z.toLocalDateTime()
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun arm(source: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }
    private fun shadow() = OffShadow.view.value

    private fun assertNoPaperOrder() {
        assertTrue("no paper order: ${Paper.state.orders}", Paper.state.orders.isEmpty())
        assertTrue("no paper position", Paper.state.positions.none { it.quantity != 0 })
    }

    // ---- the ORB day -------------------------------------------------------------------------------------------------------

    private val ceKey = "NSE_FO|ORBOFFCE"
    private val peKey = "NSE_FO|ORBOFFPE"
    private val swing = listOf(53_900.0, 53_950.0, 54_000.0, 53_950.0, 53_900.0, 53_850.0, 53_800.0, 53_850.0, 53_900.0, 53_900.0)

    private fun orbPrice(m: Int): Double = when {
        m < 10 * 60 + 5 -> swing[(m - (9 * 60 + 15)) % swing.size]
        m < 10 * 60 + 30 -> 53_950.0
        else -> 54_150.0
    }

    private fun orbFeed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start -> val p = orbPrice(start.hour * 60 + start.minute); Upstox.Bar(start.atZone(IST).toEpochSecond(), p, p, p, p, 1000, 0) }

    private var strike = 0

    private fun orbDay() {
        day = AutomationSupport.tradingDayAt(9, 50).toLocalDate()
        at(LocalTime.of(9, 50))
        strike = OrbRules.atmStrike(orbPrice(9 * 60 + 24))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.CE, 30, ceKey, "BANKNIFTY-ORBOFF-${strike}CE"),
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.PE, 30, peKey, "BANKNIFTY-ORBOFF-${strike}PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> orbFeed(t) }
    }

    private fun theOrbCall(): Paper.Contract = Paper.contractFor("BANKNIFTY", day.plusDays(7), strike.toDouble(), Right.CE)!!

    @Test fun anOrbThatIsOffBuysNothingAndKeepsTheBreakAsAWouldBeTradeWithItsExits() {
        orbDay()
        assertTrue("ORB starts off", !arm("orb").armed)
        passes(LocalTime.of(9, 50), LocalTime.of(10, 34))
        assertNoPaperOrder()
        assertTrue("nothing before the break", shadow().openOf("orb").isEmpty())
        tick(LocalTime.of(10, 35))                                   // the 10:30 bar closed above 54,000
        assertNoPaperOrder()
        assertTrue("the arm's own book took nothing", arm("orb").today.isEmpty())
        val w = shadow().openOf("orb").single()
        assertEquals(theOrbCall().symbol, w.symbol)
        assertEquals("bullish on the index: the call", 1, w.view)
        assertEquals("a buy", 1, w.side)
        assertEquals("1 lot", 30, w.qty)
        assertEquals(day.atTime(10, 35), w.entryTime)
        assertTrue(w.ref, w.ref.endsWith("|orb|10:30"))
        // Its exits: the -40 premium stop and the +40 target on the price at the signal (300 + 5 bps), the ladder's nearest lock
        // (half the target's rupees, keep half), and 15:10.
        assertEquals(OrbRules.stopTrigger(300.0 * 1.0005, 40.0)!!, w.exits.stop!!, 1e-9)
        assertEquals(300.0 * 1.0005 + 40.0, w.exits.target!!, 1e-9)
        assertEquals(0.5 * 40.0 * 30, w.exits.lockAfterRs!!, 1e-9)
        assertEquals(0.5, w.exits.lockKeep, 1e-9)
        assertEquals(LocalTime.of(15, 10), w.exits.exitAt)
        assertNull("not on the index", w.exits.indexStop)
        // The row says it runs in shadow; the arm's own status words are its own (never the shadow's).
        val row = arm("orb")
        assertNotNull(row.offShadow)
        assertTrue(row.offShadow!!, row.offShadow!!.startsWith(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // Later bars: one would-be trade at a time, as one position at a time when it is on; still nothing bought.
        passes(LocalTime.of(10, 36), LocalTime.of(10, 50))
        assertEquals(1, shadow().openOf("orb").size)
        assertNoPaperOrder()
    }

    @Test fun anOrbThatIsOnTradesPaperExactlyAsBeforeAndKeepsNoWouldBeTrade() {
        orbDay()
        val msg = runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertTrue(msg, msg.startsWith("ORB armed on paper, fully automatic"))
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val a = arm("orb")
        val p = a.today.single()
        assertTrue(p.open); assertEquals("CE", p.right); assertEquals(theOrbCall().symbol, p.symbol); assertEquals(30, p.qty)
        assertEquals(day.atTime(10, 30), p.signalBar); assertEquals(day.atTime(10, 35), p.entryTime)
        assertEquals(OrbRules.stopTrigger(p.entry), p.stopTrigger)
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals("one paper buy, never two", 1, buys.size)
        assertEquals("complete", buys.single().status); assertEquals(p.entryOrderId, buys.single().orderId)
        assertEquals("trigger pending", Paper.state.orders.single { it.action == "SELL" }.status)
        assertTrue(a.status, a.status == "entered" || a.status == "holding")
        assertTrue("on: no would-be trade of its own", shadow().openOf("orb").isEmpty() && shadow().closedOf("orb").isEmpty())
        assertNull("on: no shadow line on its row", a.offShadow)
    }

    @Test fun switchedOffWhileHoldingItKeepsItsPositionsExitsAndTakesNoWouldBeTrade() {
        orbDay()
        runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        val held = arm("orb").today.single()
        assertTrue(held.open)
        runBlocking { OrbArms.setArmed("orb", false, automatic = true) }
        passes(LocalTime.of(10, 36), LocalTime.of(10, 50))
        val still = arm("orb").today.single()
        assertTrue("its paper position runs to its own exits", still.open)
        assertEquals(held.stopTrigger, still.stopTrigger)
        assertEquals("its stop still rests in the paper book", "trigger pending", Paper.state.orders.single { it.action == "SELL" }.status)
        assertEquals("never a second buy", 1, Paper.state.orders.count { it.action == "BUY" })
        assertTrue("holding: no would-be trade meanwhile", shadow().openOf("orb").isEmpty())
    }

    @Test fun withTheTestFeedsSetTheShadowRunsOnlyWhenItsSeamIsOn() {
        OrbArms.testOffShadow = false
        orbDay()
        passes(LocalTime.of(9, 50), LocalTime.of(10, 35))
        assertNoPaperOrder()
        assertTrue("the arm tests written before 30 Oct see no shadow", shadow().open.isEmpty())
    }

    // ---- the Liquidity day (LiquidityArmTest's failed break) ---------------------------------------------------------------

    private val liqCeKey = "NSE_FO|LIQOFFCE"
    private val liqPeKey = "NSE_FO|LIQOFFPE"

    private fun liqBar(k: Int): DoubleArray = when (k) {
        20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        46 -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun liqFeed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = liqBar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    private fun liquidityDay() {
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, liqCeKey, "BANKNIFTY-LIQOFF-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, liqPeKey, "BANKNIFTY-LIQOFF-54000PE")))
        upstox.price(liqCeKey, 300.0)
        upstox.price(liqPeKey, 280.0)
        OrbArms.testIndexBars = { t -> liqFeed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @Test fun liquidityOffKeepsTheBreakAsAWouldBeTradeAndClosesItOnTheFailedBreak() {
        liquidityDay()
        assertTrue("Liquidity starts off", !arm("liquidity").armed)
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        assertTrue(shadow().openOf(LiquidityRules.ARM5.source).isEmpty())
        tick(LocalTime.of(13, 5))
        assertNoPaperOrder()
        assertTrue("the arm's own book took nothing", arm("liquidity").today.isEmpty())
        val w = shadow().openOf(LiquidityRules.ARM5.source).single()
        assertEquals(Paper.contractFor("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE)!!.symbol, w.symbol)
        assertEquals(1, w.view); assertEquals(30, w.qty)
        assertTrue(w.ref, w.ref.endsWith("|${LiquidityRules.ARM5.source}|13:00"))
        // Its exits: the 15% premium stop, the index stop 30 points back under the broken 54,100, no level ahead (no index
        // target), and 15:10.
        assertEquals(LiquidityRules.stopTrigger(300.0 * 1.0005)!!, w.exits.stop!!, 1e-9)
        assertEquals(54_100.0 - 30.0, w.exits.indexStop!!, 1e-9)
        assertNull(w.exits.indexTarget)
        assertEquals(LocalTime.of(15, 10), w.exits.exitAt)
        assertNotNull(arm("liquidity").offShadow)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 9))
        assertEquals("still held inside the bar", 1, shadow().openOf(LiquidityRules.ARM5.source).size)
        tick(LocalTime.of(13, 10))                                   // the 13:05 bar closed back under 54,100
        assertTrue(shadow().openOf(LiquidityRules.ARM5.source).isEmpty())
        val closed = shadow().closedOf(LiquidityRules.ARM5.source).single()
        assertEquals("the arm's own turn exit", "failed_break", closed.why)
        assertNoPaperOrder()
    }
}
