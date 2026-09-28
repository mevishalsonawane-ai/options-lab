package com.optionslab.engine.pine

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optimiser's grids, splits and limits. */
class PineCoverageOptimiseTest {
    @Test fun rangeValues() {
        assertEquals(listOf(1.0, 1.5, 2.0), PineOptimise.Range("k", 1.0, 2.0, 0.5).values())
        assertEquals(listOf(0.1, 0.2, 0.3), PineOptimise.Range("k", 0.1, 0.3, 0.1).values())       // rounded, and the end reached
        assertEquals(listOf(5.0), PineOptimise.Range("k", 5.0, 9.0, 0.0).values())
        assertEquals(listOf(5.0), PineOptimise.Range("k", 5.0, 9.0, -1.0).values())
        assertEquals(listOf(5.0), PineOptimise.Range("k", 5.0, 9.0, Double.NaN).values())
        assertEquals(listOf(5.0), PineOptimise.Range("k", 5.0, 1.0, 1.0).values())
        assertEquals(1, PineOptimise.Range("k", Double.NaN, 1.0, 1.0).values().size)
        assertEquals(listOf(5.0), PineOptimise.Range("k", 5.0, Double.POSITIVE_INFINITY, 1.0).values())
        assertEquals(200, PineOptimise.Range("k", 0.0, 1e6, 1.0).values().size)
        assertEquals(1, PineOptimise.combos(emptyList()))
        assertEquals(Int.MAX_VALUE, PineOptimise.combos(List(5) { PineOptimise.Range("k$it", 0.0, 1e6, 1.0) }))
    }

    private val strat = """
        strategy("o")
        n = input.int(2, "N")
        if bar_index % n == 0
            strategy.entry("L", strategy.long)
        if bar_index % n == 1
            strategy.close("L")
    """

    @Test fun noSplitAndTinyData() {
        val s = PC.ok(strat)
        val b = PC.bars(*DoubleArray(20) { 100.0 + it })
        for (pct in listOf(0, 100)) {
            val r = PineOptimise.run(s, b, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 3.0, 1.0)), inSamplePct = pct))
            assertNull(r.splitTime); assertTrue(r.rows.all { it.outSample == null })
            assertEquals(2, r.tried); assertTrue(!r.stoppedEarly)
        }
        // One candle: no split either.
        val one = PineOptimise.run(s, b.take(1), emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 2.0, 1.0))))
        assertNull(one.splitTime); assertEquals(0, one.rows.single().inSample.trades)
        // A split at 50%: the tenth candle's time.
        val half = PineOptimise.run(s, b, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 2.0, 1.0)), inSamplePct = 50))
        assertEquals(b[10].time, half.splitTime)
        val row = half.rows.single()
        assertEquals(row.inSample.trades + row.outSample!!.trades, Pine.run(s, b).report!!.trades.count { !it.open })
    }

    @Test fun limitsAndBudget() {
        val s = PC.ok(strat)
        val b = PC.bars(*DoubleArray(20) { 100.0 + it })
        val plan = PineOptimise.Plan(listOf(PineOptimise.Range("N", 1.0, 10.0, 1.0)), maxCombos = 3)
        val r = PineOptimise.run(s, b, emptyMap(), plan)
        assertEquals(3, r.tried); assertEquals(10, r.total); assertTrue(r.stoppedEarly)
        // No time at all: nothing tried.
        val none = PineOptimise.run(s, b, emptyMap(), plan.copy(budgetMs = -1))
        assertEquals(0, none.tried); assertTrue(none.stoppedEarly); assertTrue(none.rows.isEmpty())
        // No ranges: the script once, with the base inputs.
        val base = PineOptimise.run(s, b, mapOf("N" to 4.0), PineOptimise.Plan(emptyList()))
        assertEquals(1, base.tried); assertEquals(emptyMap(), base.rows.single().values)
        assertEquals(Pine.run(s, b, mapOf("N" to 4.0)).report!!.trades.count { !it.open }, base.rows.single().inSample.trades + base.rows.single().outSample!!.trades)
    }

    @Test fun errorsAndSignalsPlans() {
        // A run that stops on an error is listed last, with its message.
        val s = PC.ok("strategy(\"e\")\nn = input.int(1, \"N\")\nif n == 2 and bar_index == 3\n    runtime.error(\"boom\")\nif bar_index % 2 == 0\n    strategy.entry(\"L\", strategy.long)\nif bar_index % 2 == 1\n    strategy.close(\"L\")")
        val b = PC.bars(*DoubleArray(10) { 100.0 + it * (if (it % 2 == 0) 1 else -1) })
        val r = PineOptimise.run(s, b, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 1.0, 3.0, 1.0))))
        assertEquals(mapOf("N" to 2.0), r.rows.last().values)
        assertTrue(r.rows.last().error!!.contains("boom"))
        assertTrue(r.rows.dropLast(1).all { it.error == null })
        // An indicator traded on its signals.
        val ind = PC.ok("indicator(\"i\")\nn = input.int(3, \"N\")\nplotshape(bar_index % n == 0, \"Buy\")\nplotshape(bar_index % n == 1, \"Sell\")")
        val b2 = PC.bars(*DoubleArray(12) { 100.0 + it })
        val sig = PineOptimise.Signals(buy = 0, sell = 1, reverse = true, qty = 2.0, capital = 50_000.0)
        val res = PineOptimise.run(ind, b2, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 3.0, 1.0)), signals = sig, inSamplePct = 99))
        assertEquals(2, res.rows.size); assertTrue(res.rows.all { it.error == null && it.inSample.trades > 0 })
        val direct = Pine.run(ind, b2, mapOf("N" to 2.0))
        val want = Pine.signalBacktest(b2, direct.signals[0], direct.signals[1], true, 2.0, 50_000.0).trades.filter { !it.open }
        val row = res.rows.first { it.values["N"] == 2.0 }
        assertEquals(want.sumOf { it.pnl }, row.inSample.net + (row.outSample?.net ?: 0.0), 1e-9)
        // A signal that does not exist: an error row rather than a crash.
        val bad = PineOptimise.run(ind, b2, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 2.0, 1.0)), signals = sig.copy(sell = 7)))
        assertNotNull(bad.rows.single().error); assertNull(bad.rows.single().outSample)
        // An indicator without a signals plan has no report: empty summaries.
        val plain = PineOptimise.run(ind, b2, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 2.0, 1.0))))
        assertEquals(0, plain.rows.single().inSample.trades)
        var calls = 0
        PineOptimise.run(ind, b2, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("N", 2.0, 4.0, 1.0)))) { done, of -> calls++; assertEquals(3, of); assertEquals(calls, done) }
        assertEquals(3, calls)
    }

    @Test fun summaries() {
        fun t(pnl: Double, exitBar: Int) = Pine.Trade("e", "x", true, 1.0, 0, 0, 1.0, exitBar, 0, 1.0, pnl, 0.0, 0.0, false)
        assertEquals(PineOptimise.Summary(0.0, 0, 0.0, null, 0.0), PineOptimise.summary(emptyList()))
        val s = PineOptimise.summary(listOf(t(10.0, 3), t(-4.0, 1), t(-2.0, 2), t(5.0, 4)))
        // In exit order: -4, -2, +10, +5 -> deepest fall 6 from the start.
        assertEquals(PineOptimise.Summary(9.0, 4, 50.0, 15.0 / 6, 6.0), s)
        assertEquals(null, PineOptimise.summary(listOf(t(1.0, 1))).profitFactor)
    }
}

/** Re-pricing trades on options: every reason a trade is skipped and each way a fill is priced. */
class PineCoveragePremiumTest {
    private val dayA = LocalDate.of(2026, 9, 22)                     // a Tuesday
    private val expiry = LocalDate.of(2026, 9, 24)
    private val t0 = PC.T0                                             // 09:15 IST on dayA
    private val mins = IntArray(60) { 555 + it }                       // 09:15 .. 10:14

    private fun series(strike: Double, right: Right, open: Boolean = true, lot: Int = 75, exp: LocalDate? = expiry, price: (Int) -> Double = { 100.0 + it }) =
        Series(exp, strike, right, lot, mins, DoubleArray(60) { price(it) + 0.5 }, if (open) DoubleArray(60) { price(it) } else null, null, null, null, LongArray(60))
    private fun session(vararg s: Series, day: LocalDate = dayA, lotHint: Int? = 75) = Session(day, lotHint, s.toList())
    private val bars = List(60) { Pine.Bar(t0 + it * 60L, 25000.0, 25001.0, 24999.0, 25000.0, 0.0) }
    private fun trade(entryMin: Int, exitMin: Int, long: Boolean = true, price: Double = 25000.0, qty: Double = 1.0, open: Boolean = false,
                      entryDay: Long = 0, exitDay: Long = 0, id: String = "L") =
        Pine.Trade(id, "X", long, qty, entryMin, t0 + entryDay * 86400 + entryMin * 60L, price, exitMin, t0 + exitDay * 86400 + exitMin * 60L, price,
            0.0, 0.0, 0.0, open)
    private fun run(trades: List<Pine.Trade>, sessions: (LocalDate) -> Session?, lots: Int = 1, shortsBuyPuts: Boolean = true, slippage: Double = 0.0) =
        PinePremium.run(trades, bars, sessions, strikeStep = 50, lots = lots, capital = 100_000.0, shortsBuyPuts = shortsBuyPuts, slippage = slippage)

    @Test fun everySkipReason() {
        val ce = series(25000.0, Right.CE); val pe = series(25000.0, Right.PE)
        val s = session(ce, pe)
        fun reason(t: Pine.Trade, sessions: (LocalDate) -> Session? = { if (it == dayA) s else null }, shortsBuyPuts: Boolean = true): Map<String, Int> {
            val r = run(listOf(t), sessions, shortsBuyPuts = shortsBuyPuts)
            assertEquals(0, r.priced); assertNull(r.report); assertEquals(1, r.skipped)
            return r.reasons
        }
        assertEquals(mapOf("shorts only exit" to 1), reason(trade(1, 5, long = false), shortsBuyPuts = false))
        assertEquals(mapOf("no option data for the entry day" to 1), reason(trade(1, 5), { null }))
        assertEquals(mapOf("no option data for the entry day" to 1), reason(trade(1, 5), { throw IllegalStateException("disk") }))
        assertEquals(mapOf("no listed expiry" to 1), reason(trade(1, 5), { session(series(25000.0, Right.CE, exp = null)) }))
        assertEquals(mapOf("no listed expiry" to 1), reason(trade(1, 5), { session(series(25000.0, Right.CE, exp = dayA.minusDays(1))) }))
        assertEquals(mapOf("held past the option's expiry" to 1), reason(trade(1, 5, exitDay = 3)))
        assertEquals(mapOf("the ATM strike is not in the data" to 1), reason(trade(1, 5, price = 26000.0)))
        assertEquals(mapOf("no option data for the exit day" to 1), reason(trade(1, 5, exitDay = 1)))
        assertEquals(mapOf("no option data for the exit day" to 1), reason(trade(1, 5, exitDay = 1),
            { if (it == dayA) s else session(series(25050.0, Right.CE), day = it) }))              // that day lacks the strike
        val late = series(25000.0, Right.CE, open = false).let { Series(it.expiry, it.strike, it.right, it.lot, IntArray(60) { 600 + it }, it.close, null, null, null, null, it.oi) }
        assertEquals(mapOf("no option price at entry" to 1), reason(trade(1, 5), { session(late) }))
        assertEquals(mapOf("no option price at exit" to 1), reason(trade(1, 5), { session(series(25000.0, Right.CE) { m -> if (m >= 4) -0.5 else 100.0 }) }))
        // Open trades are left out without a reason; a result with nothing priced counts the closed ones.
        val r = run(listOf(trade(1, 5, open = true), trade(1, 5, long = false)), { null })
        assertEquals(1, r.skipped); assertEquals(mapOf("no option data for the entry day" to 1), r.reasons)
    }

    @Test fun pricingDetails() {
        val ce = series(25000.0, Right.CE, lot = 0); val pe = series(25000.0, Right.PE)
        val s = session(ce, pe, lotHint = null)
        val r = run(listOf(trade(2, 6)), { if (it == dayA) s else null }, lots = 500, slippage = 1.0)
        val t = r.report!!.trades.single()
        // Lot 0 and no hint: one unit a lot; lots capped at 100.
        assertEquals(100.0, t.qty)
        assertEquals(102.0 + 1.0, t.entryPrice)                     // minute 2's open plus slippage
        assertEquals(106.0 - 1.0, t.exitPrice)
        val c = SandboxCosts.charge("BUY", BigDecimal(103.0), 100).toDouble() + SandboxCosts.charge("SELL", BigDecimal(105.0), 100).toDouble()
        assertEquals(c, t.commission, 1e-9); assertEquals((105.0 - 103.0) * 100 - c, t.pnl, 1e-6)
        assertEquals("25000 CE", t.entryId); assertEquals(1, r.priced); assertEquals(0, r.skipped)
        // A sell price never goes below 0.05.
        val cheap = run(listOf(trade(2, 6)), { session(series(25000.0, Right.CE) { 0.5 }) }, slippage = 10.0).report!!.trades.single()
        assertEquals(0.05, cheap.exitPrice)
        // A lot size from the session's hint when the series has none.
        assertEquals(75.0 * 2, run(listOf(trade(2, 6)), { session(series(25000.0, Right.CE, lot = 0), lotHint = 75) }, lots = 2).report!!.trades.single().qty)
        // An expiry on the entry day itself is used when there is no later one.
        val sameDay = run(listOf(trade(2, 6)), { session(series(25000.0, Right.CE, exp = dayA)) })
        assertEquals(1, sameDay.priced)
        // A nearby strike within one step is used as the ATM.
        assertEquals("25050 CE", run(listOf(trade(2, 6, price = 25040.0)), { session(series(25050.0, Right.CE)) }).report!!.trades.single().entryId)
        // Exiting on a later day before the expiry prices the exit on that day's data.
        val next = run(listOf(trade(2, 6, exitDay = 1)), { d -> session(series(25000.0, Right.CE) { if (d == dayA) 100.0 + it else 200.0 + it }, day = d) })
        assertEquals(206.0, next.report!!.trades.single().exitPrice)
    }

    @Test fun pricesAtOpensClosesAndTheFirstMinute() {
        // No opens recorded: a fill at a minute's first second takes the close before it; at the day's first minute, its own close.
        val s = session(series(25000.0, Right.CE, open = false))
        val t = run(listOf(trade(3, 7)), { s }).report!!.trades.single()
        assertEquals(102.5, t.entryPrice); assertEquals(106.5, t.exitPrice)
        assertEquals(100.5, run(listOf(trade(0, 7)), { s }).report!!.trades.single().entryPrice)
        // An open of 0 is not a price either.
        val zeroOpen = Series(expiry, 25000.0, Right.CE, 75, mins, DoubleArray(60) { 100.5 + it }, DoubleArray(60) { 0.0 }, null, null, null, LongArray(60))
        assertEquals(102.5, run(listOf(trade(3, 7)), { session(zeroOpen) }).report!!.trades.single().entryPrice)
        // A fill later in a minute takes that minute's close.
        val mid = trade(3, 7).copy(entryFillTime = t0 + 3 * 60 + 30)
        assertEquals(103.5, run(listOf(mid), { s }).report!!.trades.single().entryPrice)
        // Before the series starts: no price.
        val early = trade(3, 7).copy(entryFillTime = t0 - 3600 + 30)
        assertEquals(mapOf("no option price at entry" to 1), run(listOf(early), { s }).reasons)
    }

    @Test fun intrabarFillsAreTimedFromTheIndexMinutes() {
        val ce = series(25000.0, Right.CE)
        // Index minutes: high/low only from minute 3, and a close path before that.
        val ixClose = DoubleArray(60) { if (it == 2) 25020.0 else 25000.0 }
        val ix = Series(null, 0.0, Right.IX, 0, mins, ixClose, null, null, null, null, LongArray(60))
        val s = session(ce, ix)
        // Entry a stop at 25020 inside the 5-minute bar from minute 0: first touched in minute 2 (by its close, second 59).
        val t = trade(0, 10, price = 25020.0).copy(entryTime = t0, entryFillTime = t0 + 299, entryIntrabar = true)
        val p = run(listOf(t), { s }).report!!.trades.single()
        assertEquals(t0 + 2 * 60 + 59, p.entryFillTime); assertEquals(102.5, p.entryPrice)
        // Never touched in the bar: the fill time stays the bar's close.
        val never = t.copy(entryPrice = 25045.0)                   // still the 25000 strike, a level the index never reaches
        assertEquals(t0 + 299, run(listOf(never), { s }).report!!.trades.single().entryFillTime)
        // With highs and lows the range decides.
        val hl = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(60) { 25000.0 }, DoubleArray(60) { 25000.0 },
            DoubleArray(60) { if (it >= 4) 25030.0 else 25005.0 }, DoubleArray(60) { 24995.0 }, null, LongArray(60))
        val h = run(listOf(t.copy(entryPrice = 25025.0, entryFillTime = t0 + 599)), { session(ce, hl) }).report!!.trades.single()
        assertEquals(t0 + 4 * 60 + 59, h.entryFillTime)
        // Inverted high/low data falls back to the open-close range.
        val bad = Series(null, 0.0, Right.IX, 0, mins, ixClose, DoubleArray(60) { 25000.0 }, DoubleArray(60) { 1.0 }, DoubleArray(60) { 2.0 }, null, LongArray(60))
        assertEquals(t0 + 2 * 60 + 59, run(listOf(t), { session(ce, bad) }).report!!.trades.single().entryFillTime)
        // A fill that crosses midnight is not searched; nor an exit on a day without index data.
        val overnight = t.copy(entryFillTime = t0 + 86400)
        assertEquals(t0 + 86400, run(listOf(overnight.copy(exitTime = t0 + 86400 + 600)), { d -> session(ce, ix, day = d) }).report!!.trades.single().entryFillTime)
        val exitIntra = trade(0, 10).copy(exitTime = t0 + 600, exitFillTime = t0 + 899, exitIntrabar = true, exitPrice = 25020.0)
        assertEquals(t0 + 899, run(listOf(exitIntra), { session(ce) }).report!!.trades.single().exitFillTime)
        assertEquals(t0 + 899, run(listOf(exitIntra), { s }).report!!.trades.single().exitFillTime)     // minute 2 is before the bar
    }

    @Test fun lotSharesAndReport() {
        fun t(q: Double, id: String = "L", bar: Int = 1) = trade(bar, 5, qty = q, id = id)
        assertEquals(listOf(3, 3), PinePremium.lotShares(listOf(t(0.0), t(0.0)), 3).toList())            // no quantity: each gets all
        assertEquals(listOf(2, 1), PinePremium.lotShares(listOf(t(2.0), t(1.0)), 3).toList())
        assertEquals(listOf(1, 1, 1), PinePremium.lotShares(listOf(t(1.0), t(1.0), t(1.0)), 3).toList())
        assertEquals(listOf(4, 4), PinePremium.lotShares(listOf(t(1.0, "A"), t(1.0, "B")), 4).toList())      // separate entries
        // Report from priced trades: equity steps at each exit.
        val b = PC.bars(1.0, 2.0, 3.0)
        val tr = listOf(Pine.Trade("a", "x", true, 1.0, 0, b[0].time, 1.0, 1, b[1].time, 2.0, 10.0, 10.0, 1.0, false),
            Pine.Trade("b", "x", true, 1.0, 1, b[1].time, 1.0, 2, b[2].time, 2.0, -4.0, -4.0, 1.0, false))
        val r = PinePremium.reportOf(tr, b, 1000.0, 2.0)
        assertEquals(listOf(1000.0, 1010.0, 1006.0), r.equity.toList())
        assertEquals(6.0, r.netProfit); assertEquals(2.5, r.profitFactor); assertEquals(4.0, r.maxDrawdown); assertEquals(4.0 / 1010 * 100, r.maxDrawdownPct, 1e-12)
        assertEquals(200.0, r.buyHoldPct); assertEquals(2.0, r.commission); assertEquals(1.0, r.avgBarsInTrade)
        assertNotNull(r.extra)
        val noBars = PinePremium.reportOf(tr, emptyList(), 1000.0, 0.0)
        assertEquals(0.0, noBars.buyHoldPct); assertEquals(0, noBars.equity.size)
        val winsOnly = PinePremium.reportOf(tr.take(1), b, 1000.0, 0.0)
        assertNull(winsOnly.profitFactor); assertEquals(0.0, winsOnly.avgLoss); assertEquals(0.0, winsOnly.largestLoss)
    }
}
