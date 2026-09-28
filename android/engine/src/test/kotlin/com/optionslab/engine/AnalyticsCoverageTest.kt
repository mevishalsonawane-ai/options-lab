package com.optionslab.engine

import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsCoverageTest {
    private val day = LocalDate.of(2026, 1, 6)
    private fun near(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a vs $b")
    private fun ser(k: Double, r: Right, minutes: IntArray, close: DoubleArray, vol: LongArray? = null, exp: LocalDate? = day) =
        Series(exp, k, r, 65, minutes, close, null, null, null, vol, LongArray(minutes.size))

    // ------------------------------------------------------------ signal backtest

    private val mins = intArrayOf(600, 601, 602, 603, 604, 605)
    private fun bars(open: Double = 100.0) = SignalBacktest.Bars(mins, DoubleArray(6) { open }, DoubleArray(6) { open + 1 }, DoubleArray(6) { open - 1 },
        doubleArrayOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0))
    private val chain = listOf(
        ser(100.0, Right.CE, mins, doubleArrayOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0)),
        ser(100.0, Right.PE, mins, doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.5, 1.0)),
        ser(0.0, Right.IX, mins, DoubleArray(6) { 100.0 }, exp = null),
    )
    private fun flags(vararg on: Int) = BooleanArray(6) { it in on }

    @Test fun `signals enter next bar at the ATM option and exit on the opposite signal or the close`() {
        val trades = SignalBacktest.run(bars(), chain, flags(0), flags(2), lotSize = 65, regime = "roll")
        assertEquals(2, trades.size)
        val call = trades[0]
        assertEquals("long_call", call.direction); assertEquals(Right.CE, call.right); assertEquals(601, call.entryMinute)
        assertEquals(603, call.exitMinute); assertEquals("opposite_signal", call.exitReason); assertEquals(2, call.barsHeld)
        near(call.entryPx, 6.0); near(call.exitPx, 8.0); near(call.grossPnl, 2.0 * 65)
        near(call.cost, Costs.roundTrip(6.0, 65, 1, "roll").total); near(call.netPnl, call.grossPnl - call.cost)
        assertEquals("NIFTY|2026-01-06|100|CE", call.contractId)
        near(call.underlyingEntry, 100.0); near(call.underlyingExit, 103.0)
        val put = trades[1]
        assertEquals("long_put", put.direction); assertEquals(603, put.entryMinute); assertEquals("session_close", put.exitReason)
        assertEquals(605, put.exitMinute); near(put.exitPx, 1.0)
    }

    @Test fun `signals that cannot trade are skipped, never invented`() {
        assertFailsWith<IllegalArgumentException> { SignalBacktest.run(bars(), chain, BooleanArray(5), BooleanArray(6), 65) }
        assertFailsWith<IllegalArgumentException> { SignalBacktest.run(bars(), chain, BooleanArray(6), BooleanArray(5), 65) }
        val twoExpiries = chain + ser(100.0, Right.CE, mins, DoubleArray(6) { 1.0 }, exp = day.plusDays(7))
        val e = assertFailsWith<SignalBacktest.AmbiguousChain> { SignalBacktest.run(bars(), twoExpiries, flags(0), flags(), 65) }
        assertTrue(e.message!!.contains("2 expiries"))
        assertTrue(SignalBacktest.run(bars(), emptyList(), flags(0), flags(), 65).isEmpty(), "no strikes")
        assertTrue(SignalBacktest.run(bars(open = 110.0), chain, flags(0), flags(), 65).isEmpty(), "ATM 10% away")
        assertTrue(SignalBacktest.run(bars(), chain.filter { it.right != Right.PE }, flags(), flags(0), 65).isEmpty(), "no put listed")
        val late = listOf(ser(100.0, Right.CE, intArrayOf(604, 605), doubleArrayOf(1.0, 2.0)))
        assertEquals(604, SignalBacktest.run(bars(), late, flags(0, 1, 2, 3), flags(), 65).single().entryMinute, "no quote before 604")
        val zero = listOf(ser(100.0, Right.CE, mins, DoubleArray(6) { 0.0 }))
        assertTrue(SignalBacktest.run(bars(), zero, flags(0), flags(), 65).isEmpty())
        // A signal on the last bar has no next bar.
        assertTrue(SignalBacktest.run(bars(), chain, flags(5), flags(), 65).isEmpty())
        // Buy and sell on the same bar: the call wins; the sell does not close it on the next bar...
        val both = SignalBacktest.run(bars(), chain, flags(0), flags(0), 65, lots = 2)
        assertEquals("long_call", both.first().direction); assertEquals(130.0 * (10.0 - 6.0), both.first().grossPnl, 1e-9)
    }

    @Test fun `signed option flow`() {
        val m = intArrayOf(1, 2, 3)
        val ch = listOf(
            ser(100.0, Right.CE, m, doubleArrayOf(1.0, 2.0, 2.0), longArrayOf(10, 20, 30)),
            ser(100.0, Right.PE, m, doubleArrayOf(3.0, 2.0, 4.0), longArrayOf(5, 5, 0)),
            ser(0.0, Right.IX, m, doubleArrayOf(1.0, 1.0, 1.0), longArrayOf(9, 9, 9)),
            ser(90.0, Right.CE, m, doubleArrayOf(1.0, 9.0, 1.0), null),
        )
        assertEquals(mapOf(1 to 0.0, 2 to 25.0, 3 to 0.0), Flow.signedFlow(ch))
        assertEquals(mapOf(1 to 0.0, 2 to 1.0, 3 to 0.0), Flow.signedFlow(ch, normalise = true))
        val silent = listOf(ser(100.0, Right.CE, m, doubleArrayOf(1.0, 2.0, 3.0), longArrayOf(0, 0, 0)))
        assertEquals(mapOf(1 to 0.0, 2 to 0.0, 3 to 0.0), Flow.signedFlow(silent, normalise = true))
    }

    @Test fun `regime gates`() {
        assertEquals(7, Gates.dte(day, day.plusDays(7))); assertEquals(0, Gates.dte(day, day))
        assertFailsWith<Gates.Expired> { Gates.dte(day, day.minusDays(1)) }
        assertEquals(listOf("0", "1", "2-3", "2-3", "4-7", "8-21", "22+"), listOf(0, 1, 2, 3, 7, 21, 22).map(Gates::dteBucket))
        assertFailsWith<Gates.Expired> { Gates.dteBucket(-1) }
        near(Gates.atmThetaSharePerDay(365), 0.5 / 365); assertFailsWith<IllegalArgumentException> { Gates.atmThetaSharePerDay(0) }
        near(Gates.spreadInThetaMinutes(1.0, 100.0, 365), 1.0 / (0.5 / 365 * 100 / 375))
        assertFailsWith<IllegalArgumentException> { Gates.spreadInThetaMinutes(1.0, 0.0, 5) }
        assertEquals(listOf("open", "midday", "midday", "close"), listOf(599, 600, 869, 870).map(Gates::timeOfDayBucket))
        assertTrue(Gates.liquid(0.8)); assertFalse(Gates.liquid(0.79)); assertTrue(Gates.liquid(0.5, threshold = 0.5))
        assertFailsWith<IllegalArgumentException> { Gates.liquid(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Gates.liquid(1.01) }; assertFailsWith<IllegalArgumentException> { Gates.liquid(-0.01) }
    }

    // ------------------------------------------------------------ IC

    private fun row(ic: Double = 0.1, spread: Double = 2.0, edge: Double = 3.0, daily: Double = 0.1, nullHi: Double = 0.05, controlled: Boolean = true) =
        Ic.IcRow("flow", 5, 100, 10, ic, daily, 0.0, 0.2, 0.0, 0.2, nullHi, spread, 1.0, edge, 0.05, controlled, spread / 2, 9)

    @Test fun `an IC row is only reported with its partial and its tails`() {
        assertTrue(row().monotone); assertTrue(row().clearsCost); assertTrue(row().beatsNull)
        assertFalse(row(spread = -2.0).monotone); assertFalse(row(ic = Double.NaN).monotone); assertFalse(row(spread = Double.NaN).monotone)
        assertFalse(row(edge = 1.9).clearsCost); assertFalse(row(spread = -2.0).clearsCost)
        assertFalse(row(daily = 0.01).beatsNull); assertTrue(row(daily = -0.1).beatsNull)
        assertTrue(row().format().startsWith("flow h=5 n=100 sessions=9/10 ic=+0.1000 partial_ic=+0.0500"))
        assertFalse(row().format().contains("NON-MONOTONE"))
        assertTrue(row(spread = -2.0).format().endsWith("[NON-MONOTONE: tails disagree with IC]"))
        assertFailsWith<Ic.UncontrolledIC> { row(controlled = false).format() }
        val res = Ic.IcResult("NIFTY", emptyList(), 1.0, 65, "quoted", 1.0, listOf(row(), row(edge = 0.5)))
        assertEquals(1, res.cleared.size)
    }

    @Test fun `residualise removes the controls and flags a degenerate fit`() {
        val c = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = doubleArrayOf(2.0, 4.1, 5.9, 8.2, 9.8)
        val r = Ic.residualise(y, listOf(c))
        near(r.sum(), 0.0, 1e-9)
        assertTrue(Ic.residualise(DoubleArray(5) { 3.0 * c[it] + 1 }, listOf(c)).all { it.isNaN() }, "perfectly explained")
        // A duplicated control is singular: its column gets no coefficient, and the fit still works.
        near(Ic.residualise(y, listOf(c, c.copyOf())).zip(r).maxOf { (a, b) -> abs(a - b) }, 0.0, 1e-9)
        // A constant y has zero variance: returned as is.
        assertContentEquals(DoubleArray(5), Ic.residualise(DoubleArray(5) { 7.0 }, listOf(c)).map { if (abs(it) < 1e-12) 0.0 else it }.toDoubleArray())
        assertContentEquals(DoubleArray(0), Ic.residualise(DoubleArray(0), emptyList()))
    }

    @Test fun `panel IC with and without controls, sessions and null draws`() {
        val n = 40
        val rnd = java.util.Random(7)
        val f = DoubleArray(n) { rnd.nextGaussian() }
        val fw = DoubleArray(n) { f[it] * 0.5 + rnd.nextGaussian() * 0.1 }
        val sess = IntArray(n) { it / 10 }
        assertFailsWith<IllegalArgumentException> { Ic.panelIc(f, fw.copyOf(39), sess, null, "x", 1, 1.0) }
        assertFailsWith<IllegalArgumentException> { Ic.panelIc(f, fw, sess.copyOf(39), null, "x", 1, 1.0) }
        val r = Ic.panelIc(f, fw, sess, listOf(DoubleArray(n) { rnd.nextGaussian() }), "x", 1, 1.0, nullDraws = 5, seed = 3)
        assertTrue(r.ic > 0.8 && r.partialIc > 0.8 && r.controlled && r.monotone)
        assertEquals(4, r.nSessions); assertEquals(4, r.nSessionsMeasured)
        assertTrue(r.dailyIcLo < r.dailyIcMean && r.dailyIcMean < r.dailyIcHi); assertFalse(r.nullIcHi.isNaN())
        val bare = Ic.panelIc(f, fw, IntArray(n), null, "x", 1, 0.0, nullDraws = 0)
        assertTrue(bare.partialIc.isNaN()); assertFalse(bare.controlled); assertTrue(bare.nullIcHi.isNaN())
        assertTrue(bare.dailyIcLo.isNaN(), "one session: no daily band"); assertTrue(bare.edgeOverCost.isNaN())
        val tiny = Ic.panelIc(f.copyOf(5), fw.copyOf(5), IntArray(5) { it }, emptyList(), "x", 1, 1.0, nullDraws = 2)
        assertTrue(tiny.dailyIcMean.isNaN()); assertTrue(tiny.decileSpreadPts.isNaN()); assertTrue(tiny.singleLegEdgePts.isNaN())
        assertFalse(tiny.controlled)
    }

    @Test fun `decile spread needs ten values and priced tails`() {
        assertTrue(Ic.decileSpread(DoubleArray(9), DoubleArray(9)).isNaN())
        val f = DoubleArray(20) { it.toDouble() }
        near(Ic.decileSpread(f, DoubleArray(20) { it.toDouble() }), 18.5 - 0.5)
        assertTrue(Ic.decileSpread(f, DoubleArray(20) { if (it < 2) Double.NaN else 1.0 }).isNaN())
    }

    private fun session(nIndex: Int, withVolume: Boolean = true, index: Boolean = true): Session {
        val m = IntArray(nIndex) { 555 + it }
        val ix = ser(0.0, Right.IX, m, DoubleArray(nIndex) { 24000.0 + (it * 7 % 5) - it * 0.3 }, exp = null)
        val rnd = java.util.Random(nIndex.toLong())
        val ce = ser(24000.0, Right.CE, m, DoubleArray(nIndex) { 100.0 + rnd.nextInt(5) }, if (withVolume) LongArray(nIndex) { 10L + rnd.nextInt(50) } else null)
        val pe = ser(24000.0, Right.PE, m, DoubleArray(nIndex) { 90.0 + rnd.nextInt(5) }, if (withVolume) LongArray(nIndex) { 10L + rnd.nextInt(50) } else null)
        return Session(day.plusDays(nIndex.toLong()), 65, if (index) listOf(ix, ce, pe) else listOf(ce, pe))
    }

    @Test fun `session panels and premiums`() {
        assertNull(Ic.sessionPanel(session(40, index = false), 1))
        assertNull(Ic.sessionPanel(session(5), 1), "shorter than horizon + 5")
        assertNull(Ic.sessionPanel(Session(day, 65, listOf(session(40).index!!)), 1), "no options")
        assertNull(Ic.sessionPanel(session(10), 5), "no row survives both ends")
        val p = Ic.sessionPanel(session(40), 5)!!
        assertEquals(30, p.size)
        assertNull(Ic.atmPremium(session(40, withVolume = false)))
        assertNull(Ic.atmPremium(Session(day, 65, listOf(ser(1.0, Right.CE, intArrayOf(1), doubleArrayOf(1.0), longArrayOf(0))))))
        assertTrue(Ic.atmPremium(session(40))!! in 90.0..105.0)
    }

    @Test fun `the ic command measures every horizon and refuses empty input`() {
        val res = Ic.measure("NIFTY", sequenceOf(session(100), session(101), session(102)), "quoted")
        assertEquals(65, res.lot); assertEquals(3, res.sessions.size)
        assertEquals(Ic.HORIZONS.size * 2, res.rows.size)
        assertTrue(res.breakeven > 0)
        var seen = 0
        Ic.measure("BANKNIFTY", sequenceOf(session(12)), "roll") { seen = it }.let { assertEquals(30, it.lot); assertEquals(2, it.rows.size, "only h=1 leaves more rows than its horizon") }
        assertEquals(1, seen)
        assertFailsWith<IllegalArgumentException> { Ic.measure("NIFTY", emptySequence(), "quoted") }
        assertFailsWith<IllegalArgumentException> { Ic.measure("NIFTY", sequenceOf(session(40, withVolume = false)), "quoted") }
        assertFailsWith<NoSuchElementException> { Ic.measure("FINNIFTY", sequenceOf(session(40)), "quoted") }
    }

    // ------------------------------------------------------------ indicators

    @Test fun `UT Bot refuses gaps, smooths ATR and signals crossings`() {
        val c = doubleArrayOf(10.0, 11.0, 12.0, 9.0, 8.0, 12.0)
        val h = c.map { it + 0.5 }.toDoubleArray(); val l = c.map { it - 0.5 }.toDoubleArray()
        val e = assertFailsWith<UtBot.DirtyInput> { UtBot.trailingStop(h, l, doubleArrayOf(1.0, Double.NaN, 3.0, 4.0, 5.0, 6.0)) }
        assertTrue(e.message!!.startsWith("close has 1 non-finite value(s), first at index 1"))
        assertFailsWith<UtBot.DirtyInput> { UtBot.trailingStop(doubleArrayOf(Double.POSITIVE_INFINITY), l, c) }
        val tr = UtBot.trueRange(h, l, c)
        val atr3 = UtBot.atr(h, l, c, 3)
        near(atr3[0], tr[0]); near(atr3[1], tr[1] / 3 + tr[0] * 2 / 3)
        assertContentEquals(DoubleArray(0), UtBot.atr(DoubleArray(0), DoubleArray(0), DoubleArray(0), 3))
        val (buy, sell) = UtBot.signals(h, l, c, keyValue = 1.0, atrPeriod = 2)
        assertEquals(c.size, buy.size); assertFalse(buy[0] || sell[0])
        assertTrue(buy.any { it } || sell.any { it })
    }

    @Test fun `linear regression channel edges`() {
        assertFailsWith<IllegalArgumentException> { LinReg.channel(DoubleArray(3), DoubleArray(3), DoubleArray(3), length = 0) }
        assertFailsWith<IllegalArgumentException> { LinReg.channel(DoubleArray(3), DoubleArray(3), DoubleArray(3), length = 4) }
        val one = LinReg.channel(doubleArrayOf(5.0), doubleArrayOf(6.0), doubleArrayOf(4.0), length = 1)
        assertEquals(0.0, one.slope); assertEquals(0.0, one.stdDev); assertEquals(0.0, one.pearsonR); assertFalse(one.isUptrend)
        val up = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val ch = LinReg.channel(up, up.map { it + 1 }.toDoubleArray(), up.map { it - 1 }.toDoubleArray(), length = 5, useUpper = false, useLower = false)
        assertTrue(ch.isUptrend, "X runs backwards: a rising series has a negative slope")
        near(ch.upperEnd - ch.endPrice, ch.upDev); near(ch.endPrice - ch.lowerEnd, ch.dnDev)
        near(abs(ch.pearsonR), 1.0, 1e-12)
        val withBands = LinReg.channel(up, up, up, length = 5, upperMult = 1.0, lowerMult = 3.0)
        near(withBands.upperStart - withBands.startPrice, withBands.stdDev); near(withBands.startPrice - withBands.lowerStart, 3 * withBands.stdDev)
        assertTrue(LinReg.rollingTrend(up, 0).all { it.isNaN() }); assertTrue(LinReg.rollingTrend(up, 6).all { it.isNaN() })
        assertTrue(LinReg.rollingTrend(up, 1).all { it.isNaN() }, "one point has no slope")
        val tr = LinReg.rollingTrend(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0, 2.0), 3)
        assertTrue(tr[0].isNaN() && tr[1].isNaN())
        assertEquals(listOf(-1.0, 0.0, 1.0, 0.0), tr.drop(2))
        val (b, s) = LinReg.flipSignals(doubleArrayOf(Double.NaN, 1.0, -1.0, 0.0, 1.0, Double.NaN))
        assertContentEquals(booleanArrayOf(false, false, true, false, false, false), b)
        assertContentEquals(booleanArrayOf(false, false, false, false, true, false), s)
    }

    // ------------------------------------------------------------ stats and reports

    @Test fun `stats ignore NaN and refuse too little data`() {
        assertTrue(Stats.median(listOf(Double.NaN)).isNaN()); assertEquals(2.5, Stats.median(listOf(4.0, 1.0, Double.NaN, 3.0, 2.0)))
        assertTrue(Stats.mean(emptyList()).isNaN()); assertEquals(2.0, Stats.mean(listOf(1.0, 3.0, Double.NaN)))
        assertTrue(Stats.std(listOf(1.0, Double.NaN)).isNaN()); near(Stats.std(listOf(1.0, 3.0)), kotlin.math.sqrt(2.0))
        assertTrue(Stats.percentile(emptyList(), 50.0).isNaN()); assertEquals(4.25, Stats.percentile(listOf(1.0, 2.0, 4.0, 5.0), 75.0))
        assertContentEquals(doubleArrayOf(1.0, 2.5, 2.5, 4.0), Stats.rank(doubleArrayOf(1.0, 5.0, 5.0, 9.0)))
        assertTrue(Stats.pearson(doubleArrayOf(1.0), doubleArrayOf(1.0)).isNaN())
        assertTrue(Stats.pearson(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 2.0)).isNaN())
        assertTrue(Stats.pearson(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 3.0)).isNaN())
        assertTrue(Stats.spearman(doubleArrayOf(1.0, 2.0, Double.NaN), doubleArrayOf(1.0, 2.0, 3.0)).isNaN())
        assertTrue(Stats.spearman(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 2.0, 3.0)).isNaN())
        assertTrue(Stats.spearman(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(1.0, 1.0, 1.0)).isNaN())
        near(Stats.spearman(doubleArrayOf(1.0, 2.0, 3.0, 4.0), doubleArrayOf(10.0, 20.0, 30.0, 1.0)), -0.2)
    }

    private fun trade(d: LocalDate, net: Double) = ExpiryPut.settleTrade(23800.0, 5.0, 24000.0, 65).copy(session = d, netPnl = net)

    @Test fun `summaries and the backtest report split, drift and plan`() {
        assertNull(Summary.of("x", emptyList(), 0))
        val d = (0 until 4).map { day.plusDays(it * 7L) }
        val trades = listOf(trade(d[0], 100.0), trade(d[1], -50.0), trade(d[2], 300.0))
        val r = BacktestReport.assemble(d, trades.reversed(), listOf(ExpiryPut.Skip(d[3], "why")), ExpiryPut.Params(), holdout = 2, capital = 1e8, survive = -0.06)
        assertEquals(d.take(2), r.trainDays); assertEquals(d.drop(2), r.holdoutDays)
        assertEquals(2, r.train.size); assertEquals(1, r.holdout.size)
        near(r.drift!!, 300.0 - 25.0)
        assertEquals(1, r.holdoutSummary!!.skipped); assertEquals(0, r.trainSummary!!.skipped); assertEquals(1, r.combined!!.skipped)
        assertEquals(listOf(-50.0), r.losers.map { it.netPnl })
        assertNotNull(r.plan); assertNull(r.planError)
        val none = BacktestReport.assemble(d, trades, emptyList(), ExpiryPut.Params(), holdout = 0, capital = 10.0, survive = -0.06)
        assertTrue(none.holdout.isEmpty()); assertNull(none.drift); assertNull(none.holdoutSummary); assertNull(none.plan)
        assertTrue(none.planError!!.contains("not enough to hold one lot"))
        val bad = BacktestReport.assemble(d, trades, emptyList(), ExpiryPut.Params(), holdout = 1, capital = 1e8, survive = 0.05)
        assertTrue(bad.planError!!.startsWith("survive_move_pct must be negative"))
        val s = Summary.of("train", trades, 2)!!
        near(s.winRate, 2.0 / 3); near(s.total, 350.0); near(s.worst, -50.0); near(s.best, 300.0)
        near(s.costShare, s.medianCost / s.creditPerLot)
        val ran = BacktestReport.run(listOf(Session(day, null, Chains.chain(Chains.DAY_MINUTES))), ExpiryPut.Params(), 0, 1e8, -0.06)
        assertEquals(1, ran.all.size)
    }

    // ------------------------------------------------------------ Kite ticks

    private fun pkt(size: Int, token: Long, fill: (ByteBuffer) -> Unit = {}): ByteArray {
        val b = ByteBuffer.allocate(size); b.putInt(0, token.toInt()); fill(b); return b.array()
    }

    @Test fun `every Kite packet shape decodes, and the rest is ignored`() {
        assertEquals(10_000_000.0, KiteTicks.divisor(0x103)); assertEquals(10_000.0, KiteTicks.divisor(0x206)); assertEquals(100.0, KiteTicks.divisor(0x101))
        assertNull(KiteTicks.packet(ByteArray(7)))
        val ltp = KiteTicks.packet(pkt(8, 0x101) { it.putInt(4, 12345) })!!
        assertEquals(123.45, ltp.last); assertTrue(ltp.tradable)
        val ixLtp = KiteTicks.packet(pkt(8, 0x109) { it.putInt(4, 2400000) })!!
        assertFalse(ixLtp.tradable)
        val ixQuote = KiteTicks.packet(pkt(28, 0x109) { it.putInt(4, 2400000); it.putInt(20, 2390000) })!!
        assertNull(ixQuote.exchangeTime); assertEquals(23900.0, ixQuote.close); near(ixQuote.changePct, 100.0 / 23900)
        val ixFull = KiteTicks.packet(pkt(32, 0x109) { it.putInt(28, 1_700_000_000) })!!
        assertEquals(1_700_000_000L, ixFull.exchangeTime); assertEquals(0.0, ixFull.changePct)
        assertNull(KiteTicks.packet(pkt(28, 0x101)), "a 28-byte packet is only an index quote")
        val quote = KiteTicks.packet(pkt(44, 0x103) { it.putInt(4, 835_000_000); it.putInt(16, 7) })!!
        assertEquals(83.5, quote.last); assertEquals(7L, quote.volume); assertNull(quote.bid); assertEquals(0L, quote.oi)
        val full = KiteTicks.packet(pkt(184, 0x206) { it.putInt(4, 8350); it.putInt(48, 900); it.putInt(60, 42); it.putInt(68, 8340); it.putInt(128, 8360) })!!
        assertEquals(0.835, full.last); assertEquals(900L, full.oi); assertEquals(42L, full.exchangeTime)
        assertEquals(0.834, full.bid); assertEquals(0.836, full.ask)
        val emptyBook = KiteTicks.packet(pkt(184, 0x101))!!
        assertNull(emptyBook.bid); assertNull(emptyBook.ask)
        assertNull(KiteTicks.packet(pkt(50, 0x101)))
        // Negative prices are signed; volume is unsigned.
        val neg = KiteTicks.packet(pkt(44, 0x101) { it.putInt(4, -100); it.putInt(16, -1) })!!
        assertEquals(-1.0, neg.last); assertEquals(0xffffffffL, neg.volume)
    }

    @Test fun `messages carry several packets and stop at truncation`() {
        fun msg(vararg ps: ByteArray, count: Int = ps.size, cut: Int = 0): ByteArray {
            val b = ByteBuffer.allocate(2 + ps.sumOf { 2 + it.size })
            b.putShort(count.toShort()); for (p in ps) { b.putShort(p.size.toShort()); b.put(p) }
            return b.array().copyOf(b.capacity() - cut)
        }
        val a = pkt(8, 0x101) { it.putInt(4, 100) }
        assertEquals(emptyList(), KiteTicks.parse(ByteArray(1)), "heartbeat")
        assertEquals(2, KiteTicks.parse(msg(a, a)).size)
        assertEquals(1, KiteTicks.parse(msg(a, a, cut = 3)).size, "second packet truncated")
        assertEquals(1, KiteTicks.parse(msg(a, count = 3)).size, "count larger than the packets present")
        assertEquals(1, KiteTicks.parse(msg(a, ByteArray(5))).size, "a malformed packet is skipped")
        assertEquals("""{"a":"unsubscribe","v":[1,2]}""", KiteTicks.unsubscribe(listOf(1, 2)))
        assertEquals("""{"a":"subscribe","v":[]}""", KiteTicks.subscribe(emptyList()))
        assertEquals("""{"a":"mode","v":["full",[7]]}""", KiteTicks.mode("full", listOf(7)))
    }

    // ------------------------------------------------------------ round trips and replay

    private val t0 = LocalDateTime.of(2026, 1, 6, 9, 15)
    private fun fill(id: String, side: Int, qty: Int, px: Double, min: Long, sym: String = "A", charges: Double = 0.0, order: String = "o$id") =
        RoundTrips.Fill(id, order, sym, side, qty, px, t0.plusMinutes(min), charges)

    @Test fun `round trips pair FIFO, split charges and open the remainder the other way`() {
        val trips = RoundTrips.of(listOf(
            fill("1", 1, 10, 100.0, 0, charges = 10.0), fill("2", 1, 10, 110.0, 1, charges = 10.0, order = "o1"),
            fill("0", 1, 0, 1.0, 2), fill("3", -1, 15, 120.0, 3, charges = 6.0),
            fill("4", -1, 10, 130.0, 4, charges = 4.0), fill("5", 1, 5, 125.0, 5),
            fill("6", -1, 5, 50.0, 6, sym = "B"),
        ))
        assertEquals(3, trips.size)
        val t1 = trips[0]
        assertEquals(1, t1.direction); assertEquals(15, t1.qty); near(t1.entry, (1000.0 + 550.0) / 15); near(t1.exit, 120.0)
        near(t1.charges, 10.0 + 5.0 + 6.0); assertEquals(listOf("1", "2"), t1.openFillIds); assertEquals(listOf("o1"), t1.openOrderIds)
        assertEquals(listOf("1", "2", "3"), t1.fillIds); assertEquals(listOf("o1", "o3"), t1.orderIds); assertEquals(t0.toLocalDate(), t1.day)
        near(t1.gross, (120.0 - 1550.0 / 15) * 15); near(t1.net, t1.gross - 21.0)
        // Fill 4 closes the last 5 long and opens 5 short with its remaining charges.
        val t2 = trips[1]
        assertEquals(5, t2.qty); near(t2.charges, 5.0 + 2.0)
        val t3 = trips[2]
        assertEquals(-1, t3.direction); near(t3.entry, 130.0); near(t3.exit, 125.0); near(t3.gross, 25.0); near(t3.charges, 2.0)
        val st = RoundTrips.stats(trips)
        assertEquals(3, st.trips); assertEquals(3, st.wins); assertNull(st.profitFactor); near(st.winRate, 1.0); near(st.average, st.net / 3)
        val empty = RoundTrips.stats(emptyList())
        assertEquals(0.0, empty.winRate); assertEquals(0.0, empty.average); assertEquals(0.0, empty.best); assertEquals(0.0, empty.worst)
        val lossy = RoundTrips.stats(RoundTrips.of(listOf(fill("a", 1, 1, 10.0, 0), fill("b", -1, 1, 5.0, 1), fill("c", 1, 1, 10.0, 2), fill("d", -1, 1, 12.0, 3))))
        near(lossy.profitFactor!!, 2.0 / 5.0); near(lossy.maxDrawdown, -5.0)
    }

    @Test fun `replay account books on the average and flips through zero`() {
        val a = ReplayAccount()
        assertFailsWith<IllegalArgumentException> { a.trade(1, 0, 1, 1.0) }
        assertFailsWith<IllegalArgumentException> { a.trade(1, 1, 0, 1.0) }
        assertFailsWith<IllegalArgumentException> { a.trade(1, 1, 1, 0.0) }
        assertFailsWith<IllegalArgumentException> { a.trade(1, 1, 1, Double.NaN) }
        assertEquals(0.0, a.unrealised(100.0))
        a.trade(1, 1, 10, 100.0, cost = 1.0); a.trade(2, 1, 10, 110.0)
        near(a.average, 105.0); assertEquals(20, a.position)
        a.trade(3, -1, 30, 120.0, cost = 2.0)
        assertEquals(-10, a.position); near(a.average, 120.0); near(a.realised, 300.0); near(a.charges, 3.0)
        near(a.unrealised(110.0), 100.0); near(a.net(110.0), 300.0 + 100.0 - 3.0)
        a.trade(4, 1, 10, 130.0)
        assertEquals(0, a.position); assertEquals(0.0, a.average); near(a.realised, 200.0)
        assertEquals(4, a.fills.size); near(a.fills[2].realised, 300.0)
        a.reset()
        assertEquals(0, a.position); assertTrue(a.fills.isEmpty()); assertEquals(0.0, a.charges)
    }
}
