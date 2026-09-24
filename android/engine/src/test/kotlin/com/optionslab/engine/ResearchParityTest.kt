package com.optionslab.engine

import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Indicators, the signal backtest and the IC table, against the PC's own output. */
class ResearchParityTest {
    private val assets = System.getProperty("olx.assets") ?: "../app/src/main/assets"
    private val nifty: List<Session> by lazy { File(assets, "bars_nifty.olx").inputStream().use { Olx.read(it) } }

    private fun csv(name: String): List<Map<String, String>> {
        val lines = javaClass.getResource("/$name")!!.readText().trim().lines()
        val head = lines[0].split(",")
        return lines.drop(1).map { head.zip(it.split(",")).toMap() }
    }

    private fun near(a: Double, b: Double, tol: Double, what: String) =
        assertTrue(abs(a - b) <= tol || (a.isNaN() && b.isNaN()), "$what: kotlin $a vs python $b")

    @Test fun `ut bot and linreg reproduce the pine ports bar for bar`() {
        val rows = csv("reference_indicators.csv")
        val h = rows.map { it.getValue("high").toDouble() }.toDoubleArray()
        val l = rows.map { it.getValue("low").toDouble() }.toDoubleArray()
        val c = rows.map { it.getValue("close").toDouble() }.toDoubleArray()
        val stop = UtBot.trailingStop(h, l, c)
        val (buy, sell) = UtBot.signals(h, l, c)
        val trend = LinReg.rollingTrend(c, 100)
        val (lb, ls) = LinReg.flipSignals(trend)
        rows.forEachIndexed { i, r ->
            near(stop[i], r.getValue("stop").toDouble(), 1e-6, "stop[$i]")
            assertEquals(r.getValue("ut_buy") == "True", buy[i], "ut buy[$i]")
            assertEquals(r.getValue("ut_sell") == "True", sell[i], "ut sell[$i]")
            near(trend[i], r.getValue("lr_trend").ifEmpty { "NaN" }.toDouble(), 0.0, "trend[$i]")
            assertEquals(r.getValue("lr_buy") == "True", lb[i], "lr buy[$i]")
            assertEquals(r.getValue("lr_sell") == "True", ls[i], "lr sell[$i]")
        }
        val ch = LinReg.channel(c, h, l, 100)
        val ref = javaClass.getResource("/reference_channel.json")!!.readText()
        fun j(k: String) = Regex("\"$k\": ([-0-9.eE]+)").find(ref)!!.groupValues[1].toDouble()
        near(ch.slope, j("slope"), 1e-9, "slope"); near(ch.stdDev, j("std_dev"), 1e-6, "std")
        near(ch.pearsonR, j("pearson_r"), 1e-9, "r"); near(ch.upperEnd, j("upper_end"), 1e-6, "upper")
        near(ch.lowerEnd, j("lower_end"), 1e-6, "lower"); near(ch.trend, j("trend"), 0.0, "trend")
    }

    @Test fun `a nan in the price series fails loudly`() {
        assertFailsWith<UtBot.DirtyInput> {
            UtBot.trailingStop(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, Double.NaN), doubleArrayOf(1.0, 2.0))
        }
    }

    @Test fun `ut bot signal backtest reproduces the PC trades on 2026-09-09`() {
        val day = nifty.first { it.day == LocalDate.of(2026, 9, 9) }
        val ix = day.index!!
        val bars = SignalBacktest.Bars(ix.minutes, ix.open!!, ix.high!!, ix.low!!, ix.close)
        val nearest = day.options.mapNotNull { it.expiry }.min()
        val chain = day.options.filter { it.expiry == nearest }
        val (b, s) = UtBot.signals(bars.high, bars.low, bars.close)
        val trades = SignalBacktest.run(bars, chain, b, s, lotSize = 65)
        val ref = csv("reference_signal.csv")
        assertEquals(ref.size, trades.size)
        trades.zip(ref).forEach { (t, r) ->
            assertEquals(r.getValue("contract_id"), t.contractId)
            assertEquals(hhmm(r.getValue("entry_ts").substring(11, 16)), t.entryMinute)
            assertEquals(r.getValue("exit_reason"), t.exitReason)
            near(t.netPnl, r.getValue("net_pnl").toDouble(), 1e-6, "net ${t.entryMinute}")
        }
    }

    @Test fun `ic table matches the PC on every row the rng does not touch`() {
        val meta = javaClass.getResource("/reference_ic_meta.json")!!.readText()
        val res = Ic.measure("NIFTY", nifty, "quoted")
        near(res.medianPremium, Regex("\"median_premium\": ([0-9.]+)").find(meta)!!.groupValues[1].toDouble(), 1e-9, "premium")
        near(res.breakeven, Regex("\"breakeven\": ([0-9.]+)").find(meta)!!.groupValues[1].toDouble(), 1e-9, "breakeven")
        val ref = csv("reference_ic.csv")
        assertEquals(ref.size, res.rows.size)
        res.rows.zip(ref).forEach { (k, r) ->
            val tag = "${k.feature} h=${k.horizon}"
            assertEquals(r.getValue("n_obs").toInt(), k.nObs, tag)
            assertEquals(r.getValue("measured").toInt(), k.nSessionsMeasured, tag)
            near(k.ic, r.getValue("ic").toDouble(), 1e-9, "$tag ic")
            near(k.dailyIcMean, r.getValue("daily").toDouble(), 1e-9, "$tag daily")
            near(k.partialIc, r.getValue("partial").toDouble(), 1e-6, "$tag partial")
            near(k.decileSpreadPts, r.getValue("spread").toDouble(), 1e-9, "$tag spread")
            near(k.edgeOverCost, r.getValue("edge").toDouble(), 1e-9, "$tag edge")
        }
        assertFailsWith<Ic.UncontrolledIC> { res.rows[0].copy(controlled = false).format() }
    }

    @Test fun `costs keep the tick floor and refuse a zero-cost regime`() {
        // The law's intercept is what a flat percentage misses on a Rs 5 option.
        assertEquals(3 * (0.162 + 0.00292 * 4.85), Costs.spreadPerUnit(4.85, "quoted"), 1e-12)
        assertTrue(Costs.spreadPerUnit(4.85, "quoted") > 0.009 * 4.85)
        assertFailsWith<Costs.UnknownRegime> { Costs.sellToSettle(5.0, 65, 1, "zero") }
        val c = Costs.sellToSettle(4.85, 65, 1, "quoted")
        assertEquals(0.0, c.stamp)
        assertTrue(Costs.buyToSettle(4.85, 65, 1, "quoted", intrinsic = 10.0).stt > 0)
    }

    @Test fun `sizing refuses a positive survive move and too little capital`() {
        assertFailsWith<IllegalArgumentException> { Sizing.planPosition(400_000.0, 0.06) }
        assertFailsWith<Sizing.InsufficientCapital> { Sizing.planPosition(50_000.0) }
        // Payoff arithmetic, as sizing.loss_at_move computes it (not the
        // observed Rs 2,507 worst trade, which had its own forward and credit).
        assertEquals(5301.0, Sizing.lossAtMove(-0.0111), 0.01)
        assertEquals(81_585.0, Sizing.lossAtMove(-0.06), 0.01)   // x2 lots = the CLI's Rs 163,170
    }

    @Test fun `hedged paper ticket settles with the wing netted once`() {
        val tk = Live.Ticket(LocalDate.of(2026, 9, 15), "NIFTY", LocalDate.of(2026, 9, 15), "SELL", "PE",
            23800.0, 65, 1, 65, credit = 3.0, forward = 24000.0, breakeven = 23797.0, margin = 0.0,
            maxLoss = 0.0, wingStrike = 23650.0, wingDebit = 1.5)
        val settled = Live.settle(Live.LedgerRow(tk, "open"), settlement = 24000.0)
        assertEquals(3.0, settled.trade!!.credit, 1e-12)
        assertEquals(3.0 * 65, settled.trade!!.grossPnl, 1e-9)
    }

    @Test fun `units firewall refuses a value that is not whole lots`() {
        assertFailsWith<Units.UnitsMismatch> { Units.toContracts(longArrayOf(130, 131), 65) }
        assertTrue(Units.toContracts(longArrayOf(130, 65), 65).contentEquals(longArrayOf(2, 1)))
    }

    @Test fun `manifest refuses a same-day claim on a backfilled session`() {
        assertFailsWith<Manifest.ScopeMismatch> {
            Manifest.Entry(LocalDate.of(2026, 9, 1), 1, 10, Manifest.SAME_DAY, LocalDate.of(2026, 9, 2))
        }
        assertEquals(Manifest.BACKFILL, Manifest.scopeFor(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2), 0, 0))
    }

    @Test fun `upstox urls match the python client`() {
        assertEquals("https://api.upstox.com/v3/historical-candle/NSE_INDEX%7CNifty%2050/minutes/1/2026-09-10/2026-09-01",
            Upstox.candleUrl("NSE_INDEX|Nifty 50", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1)))
        assertFailsWith<IllegalArgumentException> { Upstox.monthChunks(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1)) }
    }
}
