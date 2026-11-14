package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.FixedRupee
import com.optionslab.engine.orb.LiquidityRules
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
 * 14 Nov 2026 (Boss: "Make it 2k profit and 1k loss fix"; CLAUDE.md's fifth exception): Liquidity 15+5 with the fixed ₹2,000 target /
 * ₹1,000 stop ([FixedRupee], [FixedRupeeExits]) on PAPER - its resting stop at the ₹1k level where tighter (1 and 2 lots), sold by it
 * and labelled; the ₹2k target sold at once with the stop taken out first; its own -15% where that is tighter; switch off = as before;
 * the flag kept across a restart. The day is [LiquidityArmTest]'s (the 5-minute book buys BANKNIFTY's 54,000 CE, lot 30, at 13:05;
 * the break holds). At about 300 a lot of 30: the ₹1k stop is 33.33 under the fill (the -15% is 45 under), the ₹2k target 66.67 over.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FixedRupeeLiquidityTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|FXLIQCE"

    private fun bar(k: Int): DoubleArray = when {
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k >= 46 -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

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
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-FXLIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, "NSE_FO|FXLIQPE", "BANKNIFTY-FXLIQ-54200PE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, "NSE_FO|FXLIQPE0", "BANKNIFTY-FXLIQ-54000PE")))
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        FixedRupeeExits.resetForTest(on = false)
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
    private fun passes(from: LocalTime, until: LocalTime) { var t = from; while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) } }
    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    /** The 13:05 entry with the switch [on]. */
    private fun entered(on: Boolean = true): OrbArms.Position {
        FixedRupeeExits.resetForTest(on = on)
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }.startsWith("Liquidity 15+5 armed on paper"))
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        return row().today.single().also { assertTrue(it.open); assertFalse(it.live) }
    }

    @Test fun itsStopRestsAtTheOneThousandLevelAndSellsThere() {
        val p = entered()
        assertTrue(p.fixed)
        val fs = FixedRupee.stopLevel(p.entry, p.qty)!!
        assertTrue("tighter than the -15%", fs > LiquidityRules.stopTrigger(p.entry)!!)
        assertEquals(fs, p.stopTrigger!!, 1e-9)
        val stop = Paper.state.orders.single { it.action == "SELL" }
        assertEquals(p.stopOrderId, stop.orderId)
        assertEquals(fs, stop.triggerPrice!!.toDouble(), 1e-9)
        // The position popup carries the label and the target.
        val exits = com.optionslab.ira.ArmExits.line(false, p.symbol)!!
        assertTrue(exits, exits.contains("failed gates, Boss's exception"))
        // The option falls under it: the book fills the resting stop; booked as the ₹1k stop.
        upstox.price(ceKey, fs - 0.5)
        at(LocalTime.of(13, 15)); runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 15))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(FixedRupee.WHY_STOP, closed.why)
        assertTrue("about -₹1,000 before charges: ${closed.grossPnl}", closed.grossPnl!! in -1_100.0..-900.0)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun theTwoThousandTargetSellsOnceTheStopTakenOutFirst() {
        val p = entered()
        val tg = FixedRupee.targetLevel(p.entry, p.qty)!!
        upstox.price(ceKey, tg + 1.0)
        at(LocalTime.of(13, 15)); runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 15))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(FixedRupee.WHY_TARGET, closed.why)
        assertTrue("at least +₹2,000 before charges: ${closed.grossPnl}", closed.grossPnl!! >= FixedRupee.TARGET_RS - 1e-6)
        assertEquals("the resting stop was taken out", "cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        assertEquals("one sale", 1, Paper.state.orders.count { it.action == "SELL" && it.status == "complete" })
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun twoLotsTakeTheLevelsForTheWholePosition() {
        assertTrue(runBlocking { OrbArms.setLiquidityLots(2, "test") }.startsWith("Liquidity 15+5 now buys 2 lots"))
        val p = entered()
        assertEquals(60, p.qty)
        assertEquals(FixedRupee.stopLevel(p.entry, 60)!!, p.stopTrigger!!, 1e-9)
        assertTrue("half the distance of one lot's", p.entry - p.stopTrigger!! < 17.0)
        assertEquals(60, Paper.state.orders.single { it.action == "SELL" }.quantity)
    }

    @Test fun itsOwnStopWinsWhereItIsTighter() {
        upstox.price(ceKey, 150.0)                       // -15% = 22.5 x 30 = Rs 675: tighter than the ₹1k level (33.33 under)
        val p = entered()
        assertTrue(p.fixed)
        assertEquals(LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
    }

    @Test fun withTheSwitchOffItTradesAsBefore() {
        val p = entered(on = false)
        assertFalse(p.fixed)
        assertEquals(LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
        upstox.price(ceKey, FixedRupee.targetLevel(p.entry, p.qty)!! + 1.0)
        at(LocalTime.of(13, 15)); runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 15))
        assertTrue("no ₹2k target", row().today.single().open)
    }

    @Test fun theFlagSurvivesARestartAndASwitchOff() {
        entered()
        assertTrue(FixedRupeeExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        AutomationSupport.reloadFromDisk(OrbArms)
        val p = row().today.single()
        assertTrue("an open trade keeps the exits it entered with", p.fixed)
        upstox.price(ceKey, FixedRupee.targetLevel(p.entry, p.qty)!! + 1.0)
        at(LocalTime.of(13, 15)); runBlocking { Paper.tick() }
        tick(LocalTime.of(13, 15))
        assertEquals(FixedRupee.WHY_TARGET, row().today.single().why)
    }
}

/**
 * 14 Nov 2026: Liquidity 15+5 LIVE with the fixed ₹2,000 target / ₹1,000 stop against the fake Kite ([LiquidityArmLiveTest]'s set-up: an
 * approved entry at 200, lot 30): two lots rest the SL at the ₹1k level (200 - 16.67 = 183.35 on the tick, limit 5% under), one lot
 * keeps its own -15% (170 is tighter); the ₹2k target sells with exactly one MARKET order, the SL cancelled first; hands-off waits.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FixedRupeeLiquidityLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|FXLIVECE", "BANKNIFTY-FXLIVE-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|FXLIVEPE", "BANKNIFTY-FXLIVE-52000PE")))
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        FixedRupeeExits.resetForTest(on = false)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Both books armed in Live, the 5-minute book's signal waiting for approval, at [lots] a trade (as [LiquidityArmLiveTest]). */
    private fun state(lots: Int) {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val bar = maxOf(now.minusMinutes(1), now.toLocalDate().atStartOfDay())
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true))
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE))
            .put("positions", JSONArray())
            .put("pending", JSONObject().put("liquidity5", JSONObject().put("right", "CE").put("bar", bar.toString())
                .put("expires", now.plusMinutes(9).toString()).put("strike", 52_000).put("level", 52_050.0)))
            .put("liqLots", lots).put("liqLotsChosen", true))
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private val marketSells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    private fun approved(lots: Int, on: Boolean = true): OrbArms.Position {
        FixedRupeeExits.resetForTest(on = on)
        state(lots)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        return row().open!!
    }

    @Test fun twoLotsRestTheStopAtTheOneThousandLevelAndTheTargetSendsOneExit() {
        val p = approved(2)
        assertTrue(p.live); assertTrue(p.fixed); assertEquals(60, p.qty)
        val (_, stop) = kite.placed
        val keys = setOf("transaction_type", "order_type", "quantity", "price", "trigger_price")
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "60", "price" to "174.15", "trigger_price" to "183.35"),
            stop.form.filterKeys { it in keys })
        assertEquals(183.35, p.stopTrigger!!, 1e-9)
        // Zerodha's price reaches 200 + 16.67 = 216.70: one MARKET sell for all 60, the SL cancelled before it.
        kite.quote("NFO:$sym", 217.0, 216.95, 217.05)
        kite.requests.clear()
        runBlocking { OrbArms.priceCheckOnly() }
        val cancel = kite.requests.indexOfFirst { it.method == "DELETE" && it.path == "/orders/regular/${p.stopOrderId}" }
        val sell = kite.requests.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["order_type"] == "MARKET" }
        assertTrue("the stop was cancelled first", cancel in 0 until sell)
        assertEquals(1, marketSells.size); assertEquals("60", marketSells.single().form["quantity"])
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        val done = row().today.single()
        assertEquals(FixedRupee.WHY_TARGET, done.why)
        // Nothing more on the next pass.
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
    }

    @Test fun oneLotKeepsItsOwnTighterStopButTheTargetStill() {
        val p = approved(1)
        assertTrue(p.fixed)
        assertEquals("the -15% (Rs 900) is tighter than the ₹1k level (166.70)", 170.0, p.stopTrigger!!, 1e-9)
        kite.quote("NFO:$sym", 267.0, 266.95, 267.05)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
        assertEquals(FixedRupee.WHY_TARGET, row().today.single().why)
    }

    @Test fun theTargetWaitsWhileBossIsExitingByHand() {
        approved(2)
        kite.quote("NFO:$sym", 217.0, 216.95, 217.05)
        ArmLive.handsOff("NFO", sym)
        try {
            runBlocking { OrbArms.priceCheckOnly() }
            assertTrue("hands off: no sale", marketSells.isEmpty())
            assertTrue(row().open != null)
        } finally { ArmLive.handsBack("NFO", sym) }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
    }

    @Test fun withTheSwitchOffTwoLotsKeepTheFifteenPercentStopAndNoTarget() {
        val p = approved(2, on = false)
        assertFalse(p.fixed)
        assertEquals(170.0, p.stopTrigger!!, 1e-9)
        kite.quote("NFO:$sym", 217.0, 216.95, 217.05)
        runBlocking { OrbArms.priceCheckOnly() }
        assertTrue(marketSells.isEmpty())
    }
}
