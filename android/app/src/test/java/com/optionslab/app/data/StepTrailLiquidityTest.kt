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
import com.optionslab.engine.orb.StepTrail
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
 * 15 Nov 2026, second version (Boss: "Make it 1.5k stoploss", "Instead 10% breakeven, make it 5% breakeven"; per lot; CLAUDE.md's fifth
 * exception): Liquidity 15+5 with the ₹1,500 stop a lot + breakeven at +5% ([StepTrail], [StepTrailExits]) on PAPER - its resting stop
 * where it loses ₹1,500 a lot after charges where tighter (1 and 2 lots), moved up to breakeven (the fill plus the charges) once the
 * option is 5% up, never down, sold there and labelled; its own -15% where that is tighter; off, nothing changes; a restart keeps it.
 * (The 5-minute book's signal: CE, lot 30, at 13:05; the break holds). At about 600 a lot of 30: ₹1,500 a lot is 50 under the fill less
 * the charges (the -15% is 90 under); at about 300 the -15% (45 under) is the tighter one.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class StepTrailLiquidityTest : RobolectricTest() {
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
        upstox.price(ceKey, 600.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        StepTrailExits.resetForTest(on = false)
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
        StepTrailExits.resetForTest(on = on)
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }.startsWith("Liquidity 15+5 armed on paper"))
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        return row().today.single().also { assertTrue(it.open); assertFalse(it.live) }
    }

    private fun lotOf(p: OrbArms.Position): Int = p.lot ?: 30
    private fun be(p: OrbArms.Position): Double = StepTrail.breakevenLevel(p.entry, lotOf(p))!!

    /** The option at [px]: the paper book's own pass, then the arms' at [t]. */
    private fun priced(px: Double, t: LocalTime) {
        upstox.price(ceKey, px)
        at(t); runBlocking { Paper.tick() }
        tick(t)
    }

    private fun restingStop(p: OrbArms.Position) = Paper.state.orders.single { it.orderId == p.stopOrderId }

    @Test fun itsStopRestsAtFifteenHundredALotAfterChargesAndSellsThere() {
        val p = entered()
        assertTrue(p.fixed)
        assertEquals(30, p.lot)
        val fs = StepTrail.stopLevel(p.entry, 30)!!
        assertEquals("50 under the fill less the charges, on the tick (rounded up)", p.entry - 50.0 + StepTrail.chargesPerUnit(p.entry, 30), fs, 0.051)
        assertTrue("tighter than the -15%", fs > LiquidityRules.stopTrigger(p.entry)!!)
        assertEquals(fs, p.stopTrigger!!, 1e-9)
        val stop = Paper.state.orders.single { it.action == "SELL" }
        assertEquals(p.stopOrderId, stop.orderId)
        assertEquals(fs, stop.triggerPrice!!.toDouble(), 1e-9)
        // The position popup carries the label and the stop.
        val exits = com.optionslab.ira.ArmExits.line(false, p.symbol)!!
        assertTrue(exits, exits.contains("failed gates, Boss's exception"))
        // The option falls under it: the book fills the resting stop; booked as the ₹1,500 stop.
        priced(fs - 0.5, LocalTime.of(13, 15))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(StepTrail.WHY_STOP, closed.why)
        assertTrue("about -₹1,500 a lot with the charges: ${closed.grossPnl}", closed.grossPnl!! in -1_600.0..-1_380.0)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun theStopMovesToBreakevenAtFivePercentNeverDownAndSellsAsTheBreakevenLock() {
        val p = entered()
        // +4% : not yet - the stop stays at ₹1,500 a lot.
        priced(p.entry * 1.04, LocalTime.of(13, 6))
        assertEquals(StepTrail.stopLevel(p.entry, 30)!!, row().today.single().stopTrigger!!, 1e-9)
        // +6%: the stop moves up to breakeven (the resting paper stop modified, not a new order).
        priced(p.entry * 1.06, LocalTime.of(13, 7))
        val b = be(p)
        assertEquals("the fill plus the charges", p.entry + StepTrail.chargesPerUnit(p.entry, 30), b, 0.05 + 1e-9)
        assertTrue("over the fill: $b", b > p.entry)
        val after1 = row().today.single()
        assertEquals(b, after1.stopTrigger!!, 1e-9)
        assertEquals(b, restingStop(after1).triggerPrice!!.toDouble(), 1e-9)
        assertEquals("one resting stop", 1, Paper.state.orders.count { it.action == "SELL" })
        // A dip that stays above it, and a higher best: the stop never comes down and has no further step.
        priced(p.entry * 1.02, LocalTime.of(13, 8))
        assertEquals(b, row().today.single().stopTrigger!!, 1e-9)
        priced(p.entry * 1.09, LocalTime.of(13, 9))
        assertEquals(b, row().today.single().stopTrigger!!, 1e-9)
        // Back under it: the resting stop fills, booked as the breakeven lock, about ₹0 before charges.
        priced(b - 0.5, LocalTime.of(13, 10))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(StepTrail.WHY_TRAIL, closed.why)
        assertTrue("about the charges before charges: ${closed.grossPnl}", closed.grossPnl!! in -40.0..((b - p.entry) * 30 + 1.0))
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun twoLotsTakeTheSamePremiumDistances() {
        assertTrue(runBlocking { OrbArms.setLiquidityLots(2, "test") }.startsWith("Liquidity 15+5 now buys 2 lots"))
        val p = entered()
        assertEquals(60, p.qty)
        assertEquals("per lot: the same distance as one lot's", StepTrail.stopLevel(p.entry, 30)!!, p.stopTrigger!!, 1e-9)
        assertEquals(60, Paper.state.orders.single { it.action == "SELL" }.quantity)
        priced(p.entry * 1.06, LocalTime.of(13, 7))
        assertEquals(be(p), row().today.single().stopTrigger!!, 1e-9)
        priced(be(p) - 0.5, LocalTime.of(13, 8))
        val closed = row().today.single()
        assertEquals(StepTrail.WHY_TRAIL, closed.why)
        assertTrue("about the charges x 2 before charges: ${closed.grossPnl}", closed.grossPnl!! in -80.0..((be(p) - p.entry) * 60 + 2.0))
    }

    @Test fun itsOwnStopWinsWhereItIsTighter() {
        upstox.price(ceKey, 300.0)                       // -15% = 45 x 30 = Rs 1,350 a lot: tighter than ₹1,500 a lot (about 49 under)
        val p = entered()
        assertTrue(p.fixed)
        assertEquals(LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
        priced(LiquidityRules.stopTrigger(p.entry)!! - 0.5, LocalTime.of(13, 15))
        assertEquals("its own stop", "stop", row().today.single().why)
    }

    @Test fun withTheSwitchOffItTradesAsBefore() {
        val p = entered(on = false)
        assertFalse(p.fixed)
        assertEquals(LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
        priced(p.entry * 1.08, LocalTime.of(13, 7))
        assertEquals("no breakeven", LiquidityRules.stopTrigger(p.entry)!!, row().today.single().stopTrigger!!, 1e-9)
        priced(p.entry * 1.01, LocalTime.of(13, 8))
        assertTrue("held", row().today.single().open)
    }

    @Test fun theFlagAndTheBreakevenSurviveARestartAndASwitchOff() {
        val p = entered()
        priced(p.entry * 1.06, LocalTime.of(13, 7))
        val b = be(p)
        assertTrue(StepTrailExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        AutomationSupport.reloadFromDisk(OrbArms)
        val back = row().today.single()
        assertTrue("an open trade keeps the rule it entered with", back.fixed)
        assertEquals("never back down after a restart", b, back.stopTrigger!!, 1e-9)
        priced(p.entry * 1.02, LocalTime.of(13, 8))
        assertEquals(b, row().today.single().stopTrigger!!, 1e-9)
        priced(b - 0.5, LocalTime.of(13, 9))
        assertEquals(StepTrail.WHY_TRAIL, row().today.single().why)
    }

    /** A rule trade is kept out of the plain-rules records and named as the rule on the 26 Oct decision card, never "plain rules". */
    @Test fun aRuleTradeIsKeptOutOfThePlainRulesRecordsAndLabelled() {
        val p = entered()
        priced(StepTrail.stopLevel(p.entry, 30)!! - 0.5, LocalTime.of(13, 15))
        val closed = row().today.single()
        assertEquals(StepTrail.WHY_STOP, closed.why)
        assertTrue(closed.ownExits); assertEquals(StepTrail.LABEL, closed.ownExitsLabel)
        val book = runBlocking { OrbArms.closedPaper() }
        assertTrue("not in the forward check", ForwardRecords.liquidity(book).isEmpty())
        assertTrue("not in the record chart", ForwardRecords.liquidityEquityTrades(book).isEmpty())
        val view = row().test
        if (!com.optionslab.app.BuildConfig.GOLD) {
            val t = view!!
            assertTrue(t.paperTest, t.paperTest.contains("0 trades since 13 Oct"))
            assertTrue(t.paperTest, t.paperTest.contains("Kept apart, ${StepTrail.LABEL} (your 15 Nov choice; R179"))
            assertFalse(t.paperTest, t.paperTest.contains("plain rules ("))
        }
        // Explain-my-bots and the weekly / cut-off readers see it as the rule's own.
        val bt = com.optionslab.app.ira.IraBots.tradeOf(closed)
        assertTrue(bt.trail); assertTrue(bt.fixed); assertEquals(StepTrail.LABEL, bt.rules)
    }

    /** With Boss's own paper breakeven at +10% too, the higher stop wins (the two breakevens sit about together); both are named. */
    @Test fun withBosssBreakevenTooTheHigherStopWinsAndBothAreNamed() {
        val before = LiquidityTest.state.value
        LiquidityTest.setStateForTest(before.copy(breakeven = true))
        try {
            val p = entered()
            assertTrue(p.fixed); assertTrue(p.be != null)
            assertEquals("₹1,500 a lot first", StepTrail.stopLevel(p.entry, 30)!!, p.stopTrigger!!, 1e-9)
            priced(p.entry * 1.06, LocalTime.of(13, 6))           // +6%: the rule's breakeven is earned (Boss's +10% not yet)
            val raised = row().today.single()
            assertTrue(raised.open)
            assertTrue("raised to the breakeven: ${raised.stopTrigger}", raised.stopTrigger!! > p.entry)
            priced(p.entry * 1.105, LocalTime.of(13, 7))         // +10.5%: Boss's breakeven too; the higher of the two stays
            assertTrue(row().today.single().stopTrigger!! >= raised.stopTrigger!! - 1e-9)
            priced(p.entry - 1.0, LocalTime.of(13, 8))
            val closed = row().today.single()
            assertTrue(closed.why, closed.why == StepTrail.WHY_TRAIL || closed.why == com.optionslab.engine.orb.LiquidityBreakeven.WHY)
            val label = closed.ownExitsLabel!!
            assertTrue(label, label.contains("breakeven at +10%") && label.contains(StepTrail.LABEL))
            if (!com.optionslab.app.BuildConfig.GOLD) {
                val t = row().test!!
                assertTrue(t.paperTest, t.paperTest.contains("Kept apart, $label (your own choices, kept apart from the plain rules): 1 trade"))
            }
        } finally { LiquidityTest.setStateForTest(before) }
    }
}

/**
 * 15 Nov 2026, second version: Liquidity 15+5 LIVE with the ₹1,500 stop a lot + breakeven at +5% against the fake Kite
 * ([LiquidityArmLiveTest]'s set-up, an approved entry - here at 600, lot 30): one or two lots rest the SL where a lot loses ₹1,500 after
 * charges (about 553, limit 5% under), tighter than the -15% (510); at +5% the breakeven MODIFIES that same order up (the fill plus the
 * charges), never down, and Zerodha's fill there is the breakeven lock's exit; a stop that could not rest is sold by the app at the level
 * with exactly one exit; hands-off waits.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class StepTrailLiquidityLiveTest : RobolectricTest() {
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
        kite.quote("NFO:$sym", 600.0, 599.95, 600.05)
        runBlocking { Broker.instruments() }
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|FXLIVECE", "BANKNIFTY-FXLIVE-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|FXLIVEPE", "BANKNIFTY-FXLIVE-52000PE")))
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
        // The price checks run Liquidity's index exits too: no index minutes here (nothing reaches Upstox), so only the price exits act.
        OrbArms.testIndexBars = { emptyList() }
        OrbArms.testHistoryBars = { emptyList() }
    }

    @After fun down() {
        StepTrailExits.resetForTest(on = false)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /**
     * Both books armed in Live, the 5-minute book's signal waiting for approval, at [lots] a trade (as [LiquidityArmLiveTest]); every
     * one-time change marked done ([AutomationSupport.orbState]'s default), so no park notice of another test's is left in the log.
     */
    private fun state(lots: Int) {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val bar = maxOf(now.minusMinutes(1), now.toLocalDate().atStartOfDay())
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true))
            .put("positions", JSONArray())
            .put("pending", JSONObject().put("liquidity5", JSONObject().put("right", "CE").put("bar", bar.toString())
                .put("expires", now.plusMinutes(9).toString()).put("strike", 52_000).put("level", 52_050.0)))
            .put("liqLots", lots).put("liqLotsChosen", true))
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private val marketSells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
    private val modifies: List<FakeKite.Req> get() = kite.requests.filter { it.method == "PUT" && it.path.startsWith("/orders/regular/") }

    private fun approved(lots: Int, on: Boolean = true): OrbArms.Position {
        StepTrailExits.resetForTest(on = on)
        state(lots)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        return row().open!!
    }

    private fun quoted(px: Double) { kite.quote("NFO:$sym", px, px - 0.05, px + 0.05); runBlocking { OrbArms.priceCheckOnly() } }

    /** The rule's levels for a fill at [entry] (lot 30), and as Kite's forms carry them. */
    private fun fs(entry: Double): Double = StepTrail.stopLevel(entry, 30)!!
    private fun be(entry: Double): Double = StepTrail.breakevenLevel(entry, 30)!!
    private fun two(x: Double): String = String.format(java.util.Locale.ENGLISH, "%.2f", x)

    @Test fun oneLotRestsTheStopAtFifteenHundredALotAndZerodhasFillIsTheFifteenHundredStop() {
        val p = approved(1)
        assertTrue(p.live); assertTrue(p.fixed); assertEquals(30, p.qty)
        val f = fs(p.entry)
        assertTrue("tighter than the -15% (510): $f", f > 510.0)
        assertEquals("₹1,500 a lot after the charges", p.entry - 50.0 + StepTrail.chargesPerUnit(p.entry, 30), f, 0.05 + 1e-9)
        val stop = kite.placed.last { it.form["order_type"] == "SL" }
        val keys = setOf("transaction_type", "order_type", "quantity", "trigger_price")
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30", "trigger_price" to two(f)),
            stop.form.filterKeys { it in keys })
        assertTrue("its limit under the trigger", stop.form["price"]!!.toDouble() < f)
        assertEquals(f, p.stopTrigger!!, 1e-9)
        kite.trigger(p.stopOrderId!!, f - 3.0)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(StepTrail.WHY_STOP, row().today.single().why)
        assertTrue(marketSells.isEmpty())
    }

    @Test fun twoLotsRestTheStopAtTheSamePremium() {
        val p = approved(2)
        assertTrue(p.fixed); assertEquals(60, p.qty)
        val stop = kite.placed.last { it.form["order_type"] == "SL" }
        assertEquals("60", stop.form["quantity"]); assertEquals(two(fs(p.entry)), stop.form["trigger_price"])
        assertEquals(fs(p.entry), p.stopTrigger!!, 1e-9)
    }

    @Test fun theBreakevenModifiesTheRestingStopUpNeverDownAndZerodhasFillThereIsTheBreakevenLock() {
        val p = approved(2)
        val id = p.stopOrderId!!
        val b = be(p.entry)
        quoted(p.entry * 1.045)                                    // +4.5%: not yet
        assertTrue(modifies.isEmpty())
        quoted(p.entry * 1.055)                                    // +5.5%: up to breakeven
        assertEquals(1, modifies.size)
        assertEquals("/orders/regular/$id", modifies.single().path)
        assertEquals(b, kite.order(id).trigger, 1e-9)
        assertEquals(b, row().open!!.stopTrigger!!, 1e-9)
        quoted(p.entry * 1.02)                                     // a dip above it: never down
        quoted(p.entry * 1.12)                                     // a higher best: no further step
        assertEquals(1, modifies.size)
        assertEquals(b, kite.order(id).trigger, 1e-9)
        assertEquals("the same order, moved (never a second stop)", 1, kite.placed.count { it.form["order_type"] == "SL" })
        // Zerodha's stop fills there: that is the breakeven lock's exit, no MARKET sell.
        kite.trigger(id, b - 0.5)
        runBlocking { OrbArms.priceCheckOnly() }
        val done = row().today.single()
        assertEquals(StepTrail.WHY_TRAIL, done.why)
        assertTrue(marketSells.isEmpty())
    }

    @Test fun theBreakevenSurvivesARestart() {
        val p = approved(2)
        quoted(p.entry * 1.055)
        val b = be(p.entry)
        assertEquals(b, row().open!!.stopTrigger!!, 1e-9)
        AutomationSupport.reloadFromDisk(OrbArms)
        val back = row().open!!
        assertTrue(back.fixed); assertEquals(b, back.stopTrigger!!, 1e-9)
        quoted(p.entry * 1.02)
        assertEquals("never moved down", 1, modifies.size)
        assertEquals(b, kite.order(p.stopOrderId!!).trigger, 1e-9)
    }

    @Test fun theAppSaleWaitsWhileBossIsExitingByHand() {
        val p = approved(2)
        kite.reject(p.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val under = fs(p.entry) - 1.0
        kite.quote("NFO:$sym", under, under - 0.05, under + 0.05)
        ArmLive.handsOff("NFO", sym)
        try {
            runBlocking { OrbArms.priceCheckOnly() }
            assertTrue("hands off: no sale", marketSells.isEmpty())
            assertTrue(row().open != null)
        } finally { ArmLive.handsBack("NFO", sym) }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_STOP, row().today.single().why)
    }

    @Test fun withTheSwitchOffTwoLotsKeepTheFifteenPercentStopAndNoBreakeven() {
        val p = approved(2, on = false)
        assertFalse(p.fixed)
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
        quoted(640.0)
        assertTrue(modifies.isEmpty())
        assertTrue(marketSells.isEmpty())
    }

    /**
     * The price fell under the ₹1,500 level before the stop went (Zerodha refuses an SL sell triggered above the price) - the 15% rests at
     * once and the app sells at the level on its next look.
     */
    @Test fun aPriceUnderTheLevelBeforeTheStopRestsTheFifteenPercentAndTheAppSellsAtTheLevel() {
        StepTrailExits.resetForTest(on = true)
        state(2)
        val under = fs(600.0) - 1.0
        kite.afterNext("/orders/regular") { kite.quote("NFO:$sym", under, under - 0.05, under + 0.05) }
        kite.nextPlace()                                           // the buy
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Trigger price for stoploss sell orders should be lower than the last traded price.")
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val p = row().open!!
        assertTrue(p.fixed)
        assertEquals(listOf(two(fs(p.entry)), "510.00"), kite.placed.filter { it.form["order_type"] == "SL" }.map { it.form["trigger_price"] })
        assertEquals("a stop rests at Zerodha", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(StepTrail.WHY_STOP, row().today.single().why)
    }

    /** Zerodha refuses the stop at the ₹1,500 level - the 15% rests straight away (never no stop), then is moved up once it can rest. */
    @Test fun aRefusedStopAtTheLevelRestsTheFifteenPercentAtOnceThenMovesUp() {
        StepTrailExits.resetForTest(on = true)
        state(2)
        kite.nextPlace()                                           // the buy
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Trigger price for stoploss sell orders should be lower than the last traded price.")
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val p = row().open!!
        val sls = kite.placed.filter { it.form["order_type"] == "SL" }
        assertEquals(listOf(two(fs(p.entry)), "510.00"), sls.map { it.form["trigger_price"] })
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
        quoted(600.0)
        assertEquals(fs(p.entry), kite.order(p.stopOrderId!!).trigger, 1e-9)
        assertEquals(fs(p.entry), row().open!!.stopTrigger!!, 1e-9)
    }

    /** "Keep a stop at the exchange" rests a missing stop at the rule's level ([lockOrStop] includes it). */
    @Test fun aMissingStopIsRestedAgainAtTheLevel() {
        val p = approved(2)
        AppSettings.save(AppSettings.load().copy(exchangeStops = true))
        kite.reject(p.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(two(fs(p.entry)), kite.placed.last().form["trigger_price"])
        assertEquals(fs(p.entry), row().open!!.stopTrigger!!, 1e-9)
    }

    /** At or under the level a missing stop is not re-rested (no -15% put back): the same look sells at the level. */
    @Test fun underTheLevelAMissingStopIsNotRestedAndTheSameLookSells() {
        val p = approved(2)
        AppSettings.save(AppSettings.load().copy(exchangeStops = true))
        kite.reject(p.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val under = fs(p.entry) - 1.0
        kite.quote("NFO:$sym", under, under - 0.05, under + 0.05)
        val sls = kite.placed.count { it.form["order_type"] == "SL" }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("no stop re-rested", sls, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_STOP, row().today.single().why)
    }
}
