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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 21 Oct, enhancement round 4 (idea 4): a REAL two-book twin day. Round 1's no-twin rule was only tested with the other book's
 * holding faked ([OrbArms.testTwinHolders]): the synthetic day made only the 5-minute book trade. Here BOTH Liquidity BANKNIFTY
 * books (5-minute and 15-minute) read their own charts from one 1-minute series and signal the very same option on the same
 * minute, through the arms' own pass.
 *
 * The series (two days of history, then today):
 *  - yesterday 13:00-13:14 trades higher (54,040-54,070, bodies 54,050-54,060) with one wick to 54,100 at 13:02: a swing high on
 *    both charts, and an upper-wick pool [54,060-54,100] on both;
 *  - yesterday 14:32 a wick to 54,070 closing back at 54,000 touches that pool again on both charts (5 and 15-minute bars apart);
 *  - everything else flat (54,000, high 54,010, low 53,990) - until today 13:14, whose minute closes at 54,140: the 13:10 5-minute
 *    bar and the 13:00 15-minute bar both close at 13:15 through 54,100, taking the pool on the swing zone. Both books buy the
 *    1-ITM call, the 54,000 CE, at 13:15; the index then holds at 54,150 (no upper wick: no level ahead).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityTwinDayTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|TWINCE"

    private val flat = doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)

    /** The 1-minute bar starting at [t] as (open, high, low, close). */
    private fun minute(t: LocalDateTime): DoubleArray {
        val m = t.hour * 60 + t.minute
        return when (t.toLocalDate()) {
            day.minusDays(1) -> when {
                m == 13 * 60 + 2 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
                m in 13 * 60 until 13 * 60 + 15 -> doubleArrayOf(54_050.0, 54_070.0, 54_040.0, 54_060.0)
                m == 14 * 60 + 32 -> doubleArrayOf(54_000.0, 54_070.0, 53_990.0, 54_000.0)
                else -> flat
            }
            day -> when {
                m == 13 * 60 + 14 -> doubleArrayOf(54_000.0, 54_150.0, 53_990.0, 54_140.0)
                m >= 13 * 60 + 15 -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)
                else -> flat
            }
            else -> flat
        }
    }

    private fun session(d: LocalDate, until: LocalDateTime? = null): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { d.atTime(it / 60, it % 60) }
        .filter { until == null || !it.plusMinutes(1).isAfter(until) }
        .map { start -> minute(start).let { b -> Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0) } }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-TWIN-54000CE")))
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> session(day, t) }
        OrbArms.testHistoryBars = { d -> session(d.minusDays(2)) + session(d.minusDays(1)) }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
        LiquidityTest.setNoTwin(true, log = false)
    }

    @After fun down() {
        LiquidityTest.setNoTwin(true, log = false)
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

    /** The paper account's MIS quantity on the call after every pass: never short. */
    private fun held(): Int = Paper.state.positions.filter { it.product == "MIS" && it.symbol == theCall() }.sumOf { it.quantity }

    private fun tick(t: LocalTime) {
        at(t)
        runBlocking { OrbArms.tick() }
        assertTrue("never short at $t: ${held()}", held() >= 0)
    }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun theCall(): String = Paper.contractFor("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE)!!.symbol
    private fun positions() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }.today

    private fun arm() {
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper"))
    }

    private fun buys() = Paper.state.orders.filter { it.action == "BUY" && it.symbol == theCall() }
    private fun sells() = Paper.state.orders.filter { it.action == "SELL" && it.symbol == theCall() && it.status == "complete" }

    /** The series itself: on the books' own charts, both see the break of 54,100 on their bars closing at 13:15, and nothing before. */
    @Test fun bothChartsSignalTheSameBreakOnTheSameMinute() {
        val ones = (session(day.minusDays(2)) + session(day.minusDays(1)) + session(day, day.atTime(13, 15))).map { b ->
            com.optionslab.engine.orb.Bar(java.time.Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDateTime(), b.open, b.high, b.low, b.close)
        }
        for (m in listOf(5, 15)) {
            val bars = com.optionslab.engine.orb.LiquidityRules.fold(ones, m)
            val s = com.optionslab.engine.orb.LiquidityRules.signal(bars, com.optionslab.engine.orb.LiquidityRules.zones(bars))
            assertEquals("the $m-minute chart's break", 1, s?.side)
            assertEquals(54_100.0, s!!.level, 0.0)
            assertEquals(54_000, com.optionslab.engine.orb.LiquidityRules.entryStrike(s.side, bars.last().close, "BANKNIFTY"))
            val before = bars.dropLast(1)
            assertEquals("no $m-minute signal before", null,
                com.optionslab.engine.orb.LiquidityRules.signal(before, com.optionslab.engine.orb.LiquidityRules.zones(before)))
        }
    }

    /** The rule on (paper's default): the first book buys; the second book's same signal is a would-be trade, never bought. */
    @Test fun withTheRuleOnOnlyOneBookBuysTheOptionAndTheOtherIsAWouldBeTrade() {
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 14))
        assertTrue("nothing before the bars close at 13:15", buys().isEmpty())
        tick(LocalTime.of(13, 15))
        val open = positions().filter { it.open }
        assertEquals("one position: ${positions()}", 1, open.size)
        val holder = open.single()
        assertTrue(holder.arm in setOf("liquidity5", "liquidity15"))
        assertEquals(theCall(), holder.symbol)
        assertEquals(1, buys().size)
        assertEquals(30, held())
        val other = if (holder.arm == "liquidity5") "liquidity15" else "liquidity5"
        val rec = LiquidityTest.twins().single()
        assertEquals(other, rec.book); assertEquals(holder.arm, rec.by); assertEquals(theCall(), rec.symbol)
        assertTrue(rec.applied); assertFalse(rec.live)
        assertEquals(day.atTime(if (other == "liquidity5") 13 else 13, if (other == "liquidity5") 10 else 0), rec.signalBar)
        // The bar is decided once: no second record and no second buy on the next passes; the holder's own exit sells only its lot.
        passes(LocalTime.of(13, 16), LocalTime.of(13, 40))
        assertEquals(1, LiquidityTest.twins().size)
        assertEquals(1, buys().size)
        val after = positions().single()
        assertFalse("sold by the 20-minute time stop (the call did not gain 5%)", after.open)
        assertEquals(30, sells().sumOf { it.filledQuantity })
        assertEquals(0, held())
        assertTrue("no stop left resting", Paper.state.orders.none { it.symbol == theCall() && it.status == "trigger pending" })
    }

    /** The rule off (Boss's switch): both books buy the same option - each with its own stop - and each sells only its own lot. */
    @Test fun withTheRuleOffBothBooksBuyAndEachSellsOnlyItsOwnLot() {
        LiquidityTest.setNoTwin(false, log = false)
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 15))
        val open = positions().filter { it.open }
        assertEquals("both books hold the call: ${positions()}", setOf("liquidity5", "liquidity15"), open.map { it.arm }.toSet())
        assertTrue(open.all { it.symbol == theCall() && it.qty == 30 })
        assertEquals(2, buys().size)
        assertEquals(60, held())
        val stops = Paper.state.orders.filter { it.symbol == theCall() && it.action == "SELL" && it.status == "trigger pending" }
        assertEquals("one resting stop each, for its own lot", 2, stops.size)
        assertEquals(open.map { it.stopOrderId }.toSet(), stops.map { it.orderId }.toSet())
        assertTrue(stops.all { it.quantity == 30 })
        assertFalse("off: only noted, never applied", LiquidityTest.twins().single().applied)
        // To the time stop and the session's end: each book sells its own 30; never more than held, never short (checked each pass).
        passes(LocalTime.of(13, 16), LocalTime.of(15, 15))
        assertTrue(positions().none { it.open })
        assertEquals(60, sells().sumOf { it.filledQuantity })
        assertEquals(0, held())
        assertTrue("no stop left resting", Paper.state.orders.none { it.symbol == theCall() && it.status == "trigger pending" })
    }

    /** The rule off, the call falls through both stops at once: each book's own stop fills and each book books only its own. */
    @Test fun withTheRuleOffBothStopsFillingTogetherLeaveNothingShortAndNothingSoldTwice() {
        LiquidityTest.setNoTwin(false, log = false)
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 15))
        assertEquals(60, held())
        upstox.price(ceKey, 200.0)                                     // under both 15% stops (255)
        at(LocalTime.of(13, 16))
        runBlocking { Paper.tick() }                                   // the market watch matches both resting stops
        assertEquals("both stops filled, nothing more", 0, held())
        passes(LocalTime.of(13, 16), LocalTime.of(13, 20))
        assertTrue(positions().toString(), positions().none { it.open })
        assertEquals("the two stops' 30 each, nothing more", 60, sells().sumOf { it.filledQuantity })
        assertEquals(0, held())
        assertTrue(positions().toString(), positions().all { it.why != null })
    }
}
