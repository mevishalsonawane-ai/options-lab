package com.optionslab.engine

import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone must reproduce the PC. Every reference ledger here was written by
 * `tools/export_android_assets.py` straight from `expiry_put.run_backtest`,
 * and the engine replays the same bundled chains session by session.
 */
class ParityTest {
    companion object {
        val sessions: List<Session> by lazy {
            val dir = System.getProperty("olx.assets") ?: "../app/src/main/assets"
            File(dir, "expiry_nifty.olx").inputStream().use { Olx.read(it, float32Prices = true) }
        }

        fun reference(name: String): List<Map<String, String>> {
            val lines = ParityTest::class.java.getResource("/$name")!!.readText().trim().lines()
            val head = lines[0].split(",")
            return lines.drop(1).map { l -> head.zip(l.split(",")).toMap() }
        }
    }

    private fun close(a: Double, b: Double, tol: Double, what: String) =
        assertTrue(abs(a - b) <= tol, "$what: kotlin $a vs python $b")

    private fun checkLedger(file: String, p: ExpiryPut.Params) {
        val ref = reference(file).associateBy { LocalDate.parse(it.getValue("session")) }
        val (trades, skipped) = ExpiryPut.runBacktest(sessions, p)
        assertEquals(emptyList(), skipped)
        assertEquals(ref.size, trades.size)
        for (t in trades) {
            val r = ref.getValue(t.session!!)
            val d = t.session
            close(t.strike, r.getValue("strike").toDouble(), 1e-9, "$d strike")
            close(t.credit, r.getValue("credit").toDouble(), 1e-9, "$d credit")
            close(t.forward, r.getValue("forward").toDouble(), 1e-6, "$d forward")
            close(t.settlement, r.getValue("settlement").toDouble(), 1e-6, "$d settlement")
            close(t.cost, r.getValue("cost").toDouble(), 1e-6, "$d cost")
            close(t.netPnl, r.getValue("net_pnl").toDouble(), 1e-4, "$d net")
            assertEquals(r.getValue("lot_size").toInt(), t.lotSize, "$d lot")
            assertEquals(r.getValue("won") == "True", t.won, "$d won")
            r["wing_strike"]?.takeIf { it.isNotEmpty() }?.let { close(t.wingStrike!!, it.toDouble(), 1e-9, "$d wing") }
        }
    }

    @Test fun `naked put at lot 65 matches the PC ledger`() = checkLedger("reference_naked.csv", ExpiryPut.Params())

    @Test fun `hedged put at dated lots matches the PC ledger`() =
        checkLedger("reference_dated_hedged.csv", ExpiryPut.Params(lot = ExpiryPut.LotChoice.Dated, wingPct = 0.0075))

    @Test fun `one percent roll variant matches the PC ledger`() =
        checkLedger("reference_roll_100.csv", ExpiryPut.Params(otmPct = 0.01, regime = "roll"))

    @Test fun `the headline reproduces - 97_65 percent, +394, worst -2507`() {
        val r = BacktestReport.run(sessions, ExpiryPut.Params(), 30, 400_000.0, -0.06)
        val c = r.combined!!
        assertEquals(170, c.n)
        close(c.winRate, 166.0 / 170, 1e-12, "win rate")
        close(c.mean, 394.4, 0.05, "mean")
        close(c.worst, -2506.8, 0.05, "worst")
        close(c.total, 67042.0, 1.0, "total")
        assertEquals(140, r.trainSummary!!.n)
        close(r.drift!!, 165.6, 0.05, "holdout drift")
        assertEquals(listOf("2024-10-03", "2024-11-28", "2023-06-08", "2024-04-18"), r.losers.map { it.session.toString() })
        val plan = r.plan!!
        assertEquals(2, plan.lots)
        close(plan.capitalPerLot, 188_849.0, 1.0, "per lot")
        close(plan.expectedAnnualRs, 40_976.0, 0.5, "annual")
        close(plan.worstCaseRs, 163_170.0, 1.0, "worst case")
    }

    @Test fun `lot read off the chain agrees with the PC on every session`() {
        val ref = reference("reference_lots.csv").associate { LocalDate.parse(it.getValue("session")) to it.getValue("lot").toIntOrNull() }
        for (s in sessions) assertEquals(ref.getValue(s.day), Lots.lotFromChain(s.series), "${s.day}")
    }

    @Test fun `health check on the last 30 sessions matches the PC regime command`() {
        val (trades, _) = ExpiryPut.runBacktest(sessions, ExpiryPut.Params())
        val (recent, checks) = Monitor.healthOf(Monitor.rows(trades), 30, 0.0075, 65)
        assertEquals(30, recent.size)
        assertEquals(LocalDate.parse("2025-09-16"), recent.first().session)
        val byName = checks.associateBy { it.name }
        assertEquals(Monitor.Status.PASS, byName.getValue("credit level").status)
        assertEquals("median Rs 4.50/unit", byName.getValue("credit level").measured)
        assertEquals(Monitor.Status.FAIL, byName.getValue("margin of safety").status)
        assertEquals("min 5.2 pts, median 179.1", byName.getValue("margin of safety").measured)
        assertEquals(Monitor.Status.WARN, byName.getValue("regime").status)
        assertEquals("worst fall +0.813% = 99% of its own strike", byName.getValue("regime").measured)
        assertEquals("credit Rs 8.83 vs mean payout 0.00 pts", byName.getValue("variance premium").measured)
        assertEquals(Monitor.Status.FAIL, Monitor.verdict(checks))
    }

    @Test fun `bundled chains match their recorded provenance`() {
        val text = File(System.getProperty("olx.assets") ?: "../app/src/main/assets", "provenance.csv").readText()
        assertEquals(emptyList(), Provenance.verify(sessions, Provenance.parseCsv(text)))
    }

    @Test fun `olx round trips losslessly`() {
        val sample = sessions.take(3)
        val out = java.io.ByteArrayOutputStream()
        Olx.write(out, sample)
        val back = Olx.read(out.toByteArray().inputStream(), float32Prices = true)
        assertEquals(sample.size, back.size)
        for ((a, b) in sample.zip(back)) {
            assertEquals(a.day, b.day); assertEquals(a.lotHint, b.lotHint); assertEquals(a.series.size, b.series.size)
            for ((x, y) in a.series.zip(b.series)) {
                assertTrue(x.minutes.contentEquals(y.minutes)); assertTrue(x.close.contentEquals(y.close)); assertTrue(x.oi.contentEquals(y.oi))
            }
        }
    }
}
