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
 * 15 Nov 2026, third version (Boss: "Switch to + picker + breakeven at +10% (V4)"; CLAUDE.md's fifth exception): Liquidity 15+5 with the
 * breakeven at +10% ([StepTrail], [StepTrailExits]) on PAPER - its own -15% stop rests at the entry (the rule sets none), moved up to
 * breakeven (the fill plus the charges) once the option is 10% up (1 and 2 lots), never down, sold there and labelled; off, nothing
 * changes; a restart keeps it. (The 5-minute book's signal: CE, lot 30, at 13:05; the break holds; the option at about 600.)
 * 16 Nov 2026, fourth version (Boss: "... for liquidity use Breakeven at +₹1,000"): a new trade also moves to the same breakeven once its
 * best open P&L is +₹1,000 a lot net (about +6% at 600, before +10%), whichever first; nothing more; labelled with R186's record.
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
        // 16 Nov (main CI): the option-highs read finished while the fake feed still answers, then forgotten (no leaked real read).
        kotlinx.coroutines.runBlocking { RuleHighs.settleForTest() }; RuleHighs.clear()
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
    /** 16 Nov: the premium at which [p]'s open P&L nets +₹1,000 a lot (its breakeven from there, the fourth version). */
    private fun plus1000(p: OrbArms.Position): Double = StepTrail.lockTrigger(p.entry, lotOf(p), p.qty, 0)!!
    /**
     * 16 Nov: the rule's stop for [p] whose best is [best]: the higher of V4's breakeven (from +10%) and R186's breakeven at +₹1,000 a lot
     * (the stop whose sale nets ₹0 on the whole quantity after charges and the stop's slippage), each once earned.
     */
    private fun stopAt(p: OrbArms.Position, best: Double): Double =
        StepTrail.levelOf(p.entry, lotOf(p), best, ladder = StepTrail.Ladder(p.entry, lotOf(p), p.qty, StepTrail.Lock.BE1000))!!

    /** The option at [px]: the paper book's own pass, then the arms' at [t]. */
    private fun priced(px: Double, t: LocalTime) {
        upstox.price(ceKey, px)
        at(t); runBlocking { Paper.tick() }
        tick(t)
    }

    private fun restingStop(p: OrbArms.Position) = Paper.state.orders.single { it.orderId == p.stopOrderId }

    @Test fun itKeepsItsOwnStopAtTheEntryAndThePopupNamesTheRule() {
        val p = entered()
        assertTrue(p.fixed)
        assertTrue("a new trade takes the fourth version", p.lock)
        assertEquals(30, p.lot)
        assertEquals("no rule stop at the entry: its own -15%", LiquidityRules.stopTrigger(p.entry)!!, p.stopTrigger!!, 1e-9)
        val stop = Paper.state.orders.single { it.action == "SELL" }
        assertEquals(p.stopOrderId, stop.orderId)
        assertEquals(LiquidityRules.stopTrigger(p.entry)!!, stop.triggerPrice!!.toDouble(), 1e-9)
        // The position popup carries the label and where the breakeven starts.
        val exits = com.optionslab.ira.ArmExits.line(false, p.symbol)!!
        assertTrue(exits, exits.contains("failed gates, Boss's exception") && exits.contains("breakeven from"))
        // 16 Nov: it starts at the sooner of +₹1,000 a lot and +10%: here +₹1,000 a lot.
        assertTrue(exits, exits.contains(StepTrail.LABEL_BE1000) && exits.contains("breakeven from " + String.format(java.util.Locale.ENGLISH, "%.2f", plus1000(p))))
        // The option falls under the -15%: the book fills it; booked as its own stop.
        priced(LiquidityRules.stopTrigger(p.entry)!! - 0.5, LocalTime.of(13, 15))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("stop", closed.why)
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun theStopMovesToBreakevenAtPlusOneThousandALotNeverDownAndSellsAsTheBreakevenLock() {
        val p = entered()
        val own = LiquidityRules.stopTrigger(p.entry)!!
        val first = plus1000(p)
        assertTrue("+₹1,000 a lot comes before +10% at 600: $first", first < p.entry * 1.10 && first > p.entry * 1.05)
        // +5% and one tick under +₹1,000 a lot: not yet - the stop stays at its own -15%.
        priced(p.entry * 1.05, LocalTime.of(13, 6))
        assertEquals(own, row().today.single().stopTrigger!!, 1e-9)
        priced(first - 0.05, LocalTime.of(13, 7))
        assertEquals(own, row().today.single().stopTrigger!!, 1e-9)
        // +₹1,000 a lot net (under +10%): the stop moves up to breakeven (the resting paper stop modified, not a new order).
        priced(first, LocalTime.of(13, 8))
        val b = stopAt(p, first)
        assertEquals("R186's ₹0 stop", StepTrail.lockKeep(p.entry, 30, 30, 0, StepTrail.Lock.BE1000)!!, b, 1e-9)
        assertTrue("over the fill: $b", b > p.entry && b < p.entry * 1.01)
        val after1 = row().today.single()
        assertEquals(b, after1.stopTrigger!!, 1e-9)
        assertEquals(b, restingStop(after1).triggerPrice!!.toDouble(), 1e-9)
        assertEquals("one resting stop", 1, Paper.state.orders.count { it.action == "SELL" })
        // A dip that stays above it, and a higher best: the stop never comes down and has no further step.
        priced(p.entry * 1.03, LocalTime.of(13, 9))
        assertEquals(b, row().today.single().stopTrigger!!, 1e-9)
        priced(p.entry * 1.14, LocalTime.of(13, 10))
        // Nothing more: no step lock on Liquidity - at most the higher of the two breakevens (V4's earned at +10%).
        val b2 = stopAt(p, p.entry * 1.14)
        assertEquals(maxOf(b, be(p)), b2, 1e-9)
        assertEquals("nothing more: no step lock on Liquidity", b2, row().today.single().stopTrigger!!, 1e-9)
        // Back under it: the resting stop fills, booked as the breakeven lock, about ₹0 before charges.
        priced(b2 - 0.5, LocalTime.of(13, 11))
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals(StepTrail.WHY_TRAIL, closed.why)
        assertTrue("about the charges before charges: ${closed.grossPnl}", closed.grossPnl!! in -40.0..((b2 - p.entry) * 30 + 1.0))
        assertEquals(0, Paper.state.positions.filter { it.product == "MIS" }.sumOf { it.quantity })
    }

    @Test fun twoLotsTakeTheSamePremiumDistances() {
        assertTrue(runBlocking { OrbArms.setLiquidityLots(2, "test") }.startsWith("Liquidity 15+5 now buys 2 lots"))
        val p = entered()
        assertEquals(60, p.qty)
        assertEquals("its own -15%", LiquidityRules.stopTrigger(p.entry)!!, p.stopTrigger!!, 1e-9)
        assertEquals(60, Paper.state.orders.single { it.action == "SELL" }.quantity)
        priced(p.entry * 1.11, LocalTime.of(13, 7))
        val b = stopAt(p, p.entry * 1.11)
        assertEquals("per lot, on the two lots", b, row().today.single().stopTrigger!!, 1e-9)
        priced(b - 0.5, LocalTime.of(13, 8))
        val closed = row().today.single()
        assertEquals(StepTrail.WHY_TRAIL, closed.why)
        assertTrue("about the charges x 2 before charges: ${closed.grossPnl}", closed.grossPnl!! in -80.0..((b - p.entry) * 60 + 2.0))
    }

    /** 16 Nov (review): a part sold (a partial live exit splits the trade) never moves the levels: they stay on the entry quantity. */
    @Test fun aPartSoldKeepsTheLevelsOnTheEntryQuantity() {
        assertTrue(runBlocking { OrbArms.setLiquidityLots(2, "test") }.startsWith("Liquidity 15+5 now buys 2 lots"))
        val p = entered()
        assertEquals(60, p.qty); assertEquals("the entry quantity kept", 60, p.entryQty)
        val first = plus1000(p)
        val held = p.copy(peak = first)
        val lv = OrbArms.fixedStopOf(held)!!
        assertEquals(stopAt(p, first), lv, 1e-9)
        // One lot sold at Zerodha: the rest (30) keeps the two lots' levels - the same stop, the same name, the same app sale.
        val rest = held.copy(qty = 30)
        assertEquals(lv, OrbArms.fixedStopOf(rest)!!, 1e-9)
        assertEquals(StepTrail.WHY_TRAIL, OrbArms.ruleWhy(rest))
        assertEquals(StepTrail.WHY_TRAIL, OrbArms.fixedWhy(rest.copy(stopTrigger = lv)))
        assertTrue(OrbArms.fixedBelow(rest, lv - 0.05)); assertFalse(OrbArms.fixedBelow(rest, lv + 0.5))
        // Worked out on what is left (the old split), one lot's trigger is higher: the same best would not have earned it.
        assertTrue(StepTrail.lockTrigger(p.entry, 30, 30, 0)!! > first)
        assertEquals(null, OrbArms.fixedStopOf(rest.copy(entryQty = null)))
        // The entry quantity survives a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(60, row().today.single().entryQty)
    }

    /** 16 Nov (review): the "Kept apart" note names each version's record, and both with their counts when V4 and V5 trades mix. */
    @Test fun theKeptApartNoteSaysEachVersionsRecord() {
        val p = entered()
        val v5 = p.copy(lock = true); val v4 = p.copy(lock = false)
        assertEquals("your 16 Nov choice; ${StepTrail.LOCK_RECORD}", OrbArms.fixedNote(listOf(v5, v5)))
        assertEquals("your 15 Nov choice; ${StepTrail.RECORD}", OrbArms.fixedNote(listOf(v4)))
        val mix = OrbArms.fixedNote(listOf(v4, v5, v5))
        assertTrue(mix, mix.contains("your 15 Nov choice (1): ${StepTrail.RECORD}") && mix.contains("your 16 Nov choice (2): ${StepTrail.LOCK_RECORD}"))
        // Their labels stay apart too.
        assertEquals(StepTrail.LABEL_BE1000, v5.copy(trail = null, be = null).ownExitsLabel)
        assertEquals(StepTrail.LABEL, v4.copy(trail = null, be = null).ownExitsLabel)
    }

    @Test fun withTheSwitchOffItTradesAsBefore() {
        val p = entered(on = false)
        assertFalse(p.fixed)
        assertEquals(LiquidityRules.stopTrigger(p.entry), p.stopTrigger)
        priced(p.entry * 1.12, LocalTime.of(13, 7))
        assertEquals("no breakeven", LiquidityRules.stopTrigger(p.entry)!!, row().today.single().stopTrigger!!, 1e-9)
        priced(p.entry * 1.01, LocalTime.of(13, 8))
        assertTrue("held", row().today.single().open)
    }

    @Test fun theFlagAndTheBreakevenSurviveARestartAndASwitchOff() {
        val p = entered()
        priced(p.entry * 1.11, LocalTime.of(13, 7))
        val b = stopAt(p, p.entry * 1.11)
        assertTrue(StepTrailExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        AutomationSupport.reloadFromDisk(OrbArms)
        val back = row().today.single()
        assertTrue("an open trade keeps the rule it entered with", back.fixed)
        assertTrue("and its fourth version", back.lock)
        assertEquals("never back down after a restart", b, back.stopTrigger!!, 1e-9)
        priced(p.entry * 1.03, LocalTime.of(13, 8))
        assertEquals(b, row().today.single().stopTrigger!!, 1e-9)
        priced(b - 0.5, LocalTime.of(13, 9))
        assertEquals(StepTrail.WHY_TRAIL, row().today.single().why)
    }

    /** A rule trade is kept out of the plain-rules records and named as the rule on the 26 Oct decision card, never "plain rules". */
    @Test fun aRuleTradeIsKeptOutOfThePlainRulesRecordsAndLabelled() {
        val p = entered()
        priced(p.entry * 1.11, LocalTime.of(13, 7))
        priced(stopAt(p, p.entry * 1.11) - 0.5, LocalTime.of(13, 15))
        val closed = row().today.single()
        assertEquals(StepTrail.WHY_TRAIL, closed.why)
        assertTrue(closed.ownExits); assertEquals(StepTrail.LABEL_BE1000, closed.ownExitsLabel)
        val book = runBlocking { OrbArms.closedPaper() }
        assertTrue("not in the forward check", ForwardRecords.liquidity(book).isEmpty())
        assertTrue("not in the record chart", ForwardRecords.liquidityEquityTrades(book).isEmpty())
        val view = row().test
        if (!com.optionslab.app.BuildConfig.GOLD) {
            val t = view!!
            assertTrue(t.paperTest, t.paperTest.contains("0 trades since 13 Oct"))
            assertTrue(t.paperTest, t.paperTest.contains("Kept apart, ${StepTrail.LABEL_BE1000} (your 16 Nov choice; R186"))
            assertFalse(t.paperTest, t.paperTest.contains("plain rules ("))
        }
        // Explain-my-bots and the weekly / cut-off readers see it as the rule's own.
        val bt = com.optionslab.app.ira.IraBots.tradeOf(closed)
        assertTrue(bt.trail); assertTrue(bt.fixed); assertTrue(bt.lock); assertEquals(StepTrail.LABEL_BE1000, bt.rules)
    }

    /** With Boss's own paper breakeven at +10% too, the two breakevens sit about together; the higher stop wins and both are named. */
    @Test fun withBosssBreakevenTooTheHigherStopWinsAndBothAreNamed() {
        val before = LiquidityTest.state.value
        LiquidityTest.setStateForTest(before.copy(breakeven = true))
        try {
            val p = entered()
            assertTrue(p.fixed); assertTrue(p.be != null)
            assertEquals("its own -15% first", LiquidityRules.stopTrigger(p.entry)!!, p.stopTrigger!!, 1e-9)
            priced(p.entry * 1.11, LocalTime.of(13, 6))           // +11%: both breakevens are earned
            val raised = row().today.single()
            assertTrue(raised.open)
            assertTrue("raised to a breakeven: ${raised.stopTrigger}", raised.stopTrigger!! > p.entry)
            priced(p.entry * 1.13, LocalTime.of(13, 7))           // a higher best: never down
            assertTrue(row().today.single().stopTrigger!! >= raised.stopTrigger!! - 1e-9)
            priced(p.entry - 1.0, LocalTime.of(13, 8))
            val closed = row().today.single()
            assertTrue(closed.why, closed.why == StepTrail.WHY_TRAIL || closed.why == com.optionslab.engine.orb.LiquidityBreakeven.WHY)
            val label = closed.ownExitsLabel!!
            assertTrue(label, label.contains(com.optionslab.engine.orb.LiquidityBreakeven.LABEL.removePrefix("with ")) && label.contains(StepTrail.LABEL_BE1000))
            if (!com.optionslab.app.BuildConfig.GOLD) {
                val t = row().test!!
                assertTrue(t.paperTest, t.paperTest.contains("Kept apart, $label (your own choices, kept apart from the plain rules): 1 trade"))
            }
        } finally { LiquidityTest.setStateForTest(before) }
    }
}

/**
 * 15 Nov 2026, third version: Liquidity 15+5 LIVE with the breakeven at +10% against the fake Kite ([LiquidityArmLiveTest]'s set-up, an
 * approved entry - here at 600, lot 30): one or two lots rest the SL at their own -15% (510; the rule sets no entry stop); at +10% the
 * breakeven MODIFIES that same order up (the fill plus the charges), never down, and Zerodha's fill there is the breakeven lock's exit; a
 * missing stop is rested again at the breakeven; under it the app sells at the level with exactly one exit; hands-off waits.
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

    /** A cancel in the exchange's / Zerodha's risk system's words ([com.optionslab.ira.ManualCoverRule.endedByExchange]). */
    private val EXCHANGE_CANCEL = "Cancelled by the exchange (RMS)"

    /** The rule's breakeven for a fill at [entry] (lot 30), and as Kite's forms carry it. */
    private fun be(entry: Double): Double = StepTrail.breakevenLevel(entry, 30)!!
    /** 16 Nov: the rule's stop for the live trade [p] whose best is [best] (the higher of V4's and R186's breakeven at +₹1,000, each once earned). */
    private fun lvl(p: OrbArms.Position, best: Double): Double =
        StepTrail.levelOf(p.entry, 30, best, ladder = StepTrail.Ladder(p.entry, 30, p.qty, StepTrail.Lock.BE1000))!!
    private fun two(x: Double): String = String.format(java.util.Locale.ENGLISH, "%.2f", x)

    /** An approved trade of [lots] lots whose option has traded 10.5% up: its stop moved to the breakeven. */
    private fun earned(lots: Int): OrbArms.Position {
        val p = approved(lots)
        quoted(p.entry * 1.105)
        assertEquals(lvl(p, p.entry * 1.105), row().open!!.stopTrigger!!, 1e-9)
        return p
    }

    @Test fun oneLotRestsItsOwnStopAndZerodhasFillThereIsItsOwnStop() {
        val p = approved(1)
        assertTrue(p.live); assertTrue(p.fixed); assertEquals(30, p.qty)
        val stop = kite.placed.last { it.form["order_type"] == "SL" }
        val keys = setOf("transaction_type", "order_type", "quantity", "trigger_price")
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30", "trigger_price" to "510.00"),
            stop.form.filterKeys { it in keys })
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
        kite.trigger(p.stopOrderId!!, 507.0)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("stop", row().today.single().why)
        assertTrue(marketSells.isEmpty())
    }

    @Test fun twoLotsRestTheirOwnStopToo() {
        val p = approved(2)
        assertTrue(p.fixed); assertEquals(60, p.qty)
        val stop = kite.placed.last { it.form["order_type"] == "SL" }
        assertEquals("60", stop.form["quantity"]); assertEquals("510.00", stop.form["trigger_price"])
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
    }

    @Test fun theBreakevenModifiesTheRestingStopUpNeverDownAndZerodhasFillThereIsTheBreakevenLock() {
        val p = approved(2)
        val id = p.stopOrderId!!
        // 16 Nov: the fourth version - the breakeven from +₹1,000 a lot net on the two lots (+₹2,000), here before +10%.
        val first = StepTrail.lockTrigger(p.entry, 30, p.qty, 0)!!
        val b = lvl(p, first)
        assertTrue("$first", first < p.entry * 1.10 && first > p.entry * 1.055)
        assertTrue(p.lock)
        quoted(p.entry * 1.055)                                    // +5.5%: not yet (the second version's trigger)
        quoted(first - 0.05)                                       // a tick under +₹1,000 a lot: not yet
        assertTrue(modifies.isEmpty())
        quoted(first)                                              // +₹1,000 a lot: up to breakeven
        assertEquals(1, modifies.size)
        assertEquals("/orders/regular/$id", modifies.single().path)
        assertEquals(b, kite.order(id).trigger, 1e-9)
        assertEquals(b, row().open!!.stopTrigger!!, 1e-9)
        quoted(p.entry * 1.03)                                     // a dip above it: never down
        assertEquals(1, modifies.size)
        quoted(p.entry * 1.15)                                     // a higher best: at most V4's breakeven (+10%) if higher; no step
        val b2 = lvl(p, p.entry * 1.15)
        assertEquals(maxOf(b, be(p.entry)), b2, 1e-9)
        assertEquals(if (b2 > b + 1e-9) 2 else 1, modifies.size)
        assertEquals(b2, kite.order(id).trigger, 1e-9)
        assertEquals("the same order, moved (never a second stop)", 1, kite.placed.count { it.form["order_type"] == "SL" })
        // Zerodha's stop fills there: that is the breakeven lock's exit, no MARKET sell.
        kite.trigger(id, b2 - 0.5)
        runBlocking { OrbArms.priceCheckOnly() }
        val done = row().today.single()
        assertEquals(StepTrail.WHY_TRAIL, done.why)
        assertTrue(marketSells.isEmpty())
    }

    @Test fun theBreakevenSurvivesARestart() {
        val p = earned(2)
        val b = lvl(p, p.entry * 1.105)
        AutomationSupport.reloadFromDisk(OrbArms)
        val back = row().open!!
        assertTrue(back.fixed); assertTrue(back.lock); assertEquals(b, back.stopTrigger!!, 1e-9)
        quoted(p.entry * 1.03)
        assertEquals("never moved down", 1, modifies.size)
        assertEquals(b, kite.order(p.stopOrderId!!).trigger, 1e-9)
    }

    @Test fun theAppSaleWaitsWhileBossIsExitingByHand() {
        val p = earned(2)
        kite.reject(p.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val under = lvl(p, p.entry * 1.105) - 1.0
        kite.quote("NFO:$sym", under, under - 0.05, under + 0.05)
        ArmLive.handsOff("NFO", sym)
        try {
            runBlocking { OrbArms.priceCheckOnly() }
            assertTrue("hands off: no sale", marketSells.isEmpty())
            assertTrue(row().open != null)
        } finally { ArmLive.handsBack("NFO", sym) }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_TRAIL, row().today.single().why)
    }

    @Test fun withTheSwitchOffTwoLotsKeepTheFifteenPercentStopAndNoBreakeven() {
        val p = approved(2, on = false)
        assertFalse(p.fixed)
        assertEquals(510.0, p.stopTrigger!!, 1e-9)
        quoted(670.0)                                              // +11.7%: no breakeven with the switch off
        assertTrue(modifies.isEmpty())
        assertTrue(marketSells.isEmpty())
    }

    /** "Keep a stop at the exchange" rests a missing stop at the breakeven once earned ([lockOrStop] includes it). */
    @Test fun aMissingStopIsRestedAgainAtTheBreakeven() {
        val p = earned(2)
        AppSettings.save(AppSettings.load().copy(exchangeStops = true))
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(two(lvl(p, p.entry * 1.105)), kite.placed.last().form["trigger_price"])
        assertEquals(lvl(p, p.entry * 1.105), row().open!!.stopTrigger!!, 1e-9)
    }

    /**
     * 16 Nov (Boss: "All the stops and profit locks should be on zerodha"): a Liquidity trade's missing stop is put back at Zerodha at the
     * breakeven it earned even with "Keep a stop at the exchange" off - never held in the app alone while it can rest.
     */
    @Test fun aMissingStopIsRestedAgainAtZerodhaWithoutTheSetting() {
        val p = earned(2)
        AppSettings.save(AppSettings.load().copy(exchangeStops = false))
        val sls = kite.placed.count { it.form["order_type"] == "SL" }
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("rested again at Zerodha", sls + 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(two(lvl(p, p.entry * 1.105)), kite.placed.last().form["trigger_price"])
        assertEquals(lvl(p, p.entry * 1.105), row().open!!.stopTrigger!!, 1e-9)
        assertTrue("no app sale while it can rest", marketSells.isEmpty())
    }

    /**
     * 16 Nov (review): a stop Boss cancelled themselves (in Kite: no exchange words) is respected - never put back at Zerodha by itself, said
     * so truthfully - and the app watches the level itself: it sells there when the price comes back to it.
     */
    @Test fun aStopBossCancelledIsNotPutBackAndTheAppWatchesIt() {
        val p = earned(2)
        val b = lvl(p, p.entry * 1.105)
        val sls = kite.placed.count { it.form["order_type"] == "SL" }
        kite.reject(p.stopOrderId!!, message = "", status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("never put back", sls, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue(row().open!!.stopByYou)
        assertTrue(AutomationSupport.alerts().joinToString("\n"), AutomationSupport.alerts().any { it.contains("It looks cancelled by you (in Kite)") })
        // Kept across a restart; and the app sells at the level itself.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(row().open!!.stopByYou)
        kite.quote("NFO:$sym", b - 0.5, b - 0.55, b - 0.45)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(1, marketSells.size)
        assertEquals(sls, kite.placed.count { it.form["order_type"] == "SL" })
    }

    /** 16 Nov (review): ended by the exchange (its words), the alert says it goes back at Zerodha - and it does. */
    @Test fun aStopTheExchangeCancelledIsSaidToGoBack() {
        val p = earned(2)
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertTrue(AutomationSupport.alerts().joinToString("\n"), AutomationSupport.alerts().any { it.contains("again at Zerodha") })
        assertFalse(row().open!!.stopByYou)
    }

    private fun slCount() = kite.placed.count { it.form["order_type"] == "SL" }

    /** A working order of someone else's on the same contract (another book's stop, Boss's own): it never holds this stop back. */
    @Test fun anotherBooksStopOnTheContractNeverHoldsTheReRestBack() {
        val p = earned(2)
        kite.orders["OTHER1"] = FakeKite.Order("OTHER1", sym, "NFO", "SELL", 30, "MIS", "SL", 500.0, 505.0, "iraorb", "TRIGGER PENDING")
        runBlocking { Strategies.tagOwner("kite:OTHER1", "Another book · stop") }
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("rested again beside another book's stop", sls + 1, slCount())
    }

    /** The 15:05 expiry square-off's order working on the contract (read between its cancel and its sale): no new stop beside it. */
    @Test fun theExpirySquareOffsOrderHoldsTheReRestBack() {
        val p = earned(2)
        kite.orders["SQ1"] = FakeKite.Order("SQ1", sym, "NFO", "SELL", 60, "MIS", "MARKET", 0.0, 0.0, "iraalgoexpiry", "OPEN")
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("never two exits", sls, slCount())
    }

    /**
     * This position's own arm's exit sale still working (an earlier pass's sale, its reply lost - no label kept): no new stop beside it;
     * another book's working sale (its own label) never holds it back.
     */
    @Test fun itsOwnExitSaleWorkingHoldsTheReRestBackAnotherBooksDoesNot() {
        val p = earned(2)
        kite.orders["MINE1"] = FakeKite.Order("MINE1", sym, "NFO", "SELL", 60, "MIS", "MARKET", 0.0, 0.0, "iraorb", "OPEN")
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("never beside its own working sale", sls, slCount())
        // That sale gone; another book's sale, labelled as that book's, working instead: the stop goes back.
        kite.orders.remove("MINE1")
        kite.orders["THEIRS1"] = FakeKite.Order("THEIRS1", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "iraorb", "OPEN")
        runBlocking { Strategies.tagOwner("kite:THEIRS1", "Another book · exit") }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(sls + 1, slCount())
    }

    /** A resting stop of the arms' own with no other book's label (this position's own, its reply lost): no second stop beside it. */
    @Test fun itsOwnOrphanStopHoldsTheReRestBack() {
        val p = earned(2)
        kite.orders["ORPHAN1"] = FakeKite.Order("ORPHAN1", sym, "NFO", "SELL", 60, "MIS", "SL", 600.0, 603.0, "iraorb", "TRIGGER PENDING")
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("never a second stop beside its own", sls, slCount())
    }

    /** With Stop for today on, no new stop: the operator's stop sells it at market on the same look. */
    @Test fun stopForTodaySellsInsteadOfReResting() {
        val p = earned(2)
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(sls, slCount())
        assertEquals(1, marketSells.size)
    }

    /** At or after 15:10 no new stop: the session's square-off sells it at market on the same look. */
    @Test fun atTheSquareOffItSellsInsteadOfReResting() {
        val p = earned(2)
        val sls = slCount()
        kite.reject(p.stopOrderId!!, message = EXCHANGE_CANCEL, status = "CANCELLED")
        OrbArms.testNow = now.toLocalDate().atTime(15, 11).atZone(IST)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(sls, slCount())
        assertEquals(1, marketSells.size)
        assertEquals("session_end", row().today.single().why)
    }

    /** At or under the breakeven a missing stop is not re-rested (no -15% put back): the same look sells at the level. */
    @Test fun underTheBreakevenAMissingStopIsNotRestedAndTheSameLookSells() {
        val p = earned(2)
        AppSettings.save(AppSettings.load().copy(exchangeStops = true))
        kite.reject(p.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val under = lvl(p, p.entry * 1.105) - 1.0
        kite.quote("NFO:$sym", under, under - 0.05, under + 0.05)
        val sls = kite.placed.count { it.form["order_type"] == "SL" }
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals("no stop re-rested", sls, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_TRAIL, row().today.single().why)
    }
}
