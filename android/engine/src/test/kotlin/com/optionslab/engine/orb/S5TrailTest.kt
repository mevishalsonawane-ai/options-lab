package com.optionslab.engine.orb

import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "S5 Trail" ([S5Trail]): the network the app ships (assets s5/, research/hunt/r128_missing/export_s5.py) reproduces R123's own
 * holdout outputs on 8 golden rows; the window and the 42 inputs match R123's Python on synthetic sessions (android/tools/
 * s5_parity_fixture.py); the pick, the stop premium, the trailing lock, the calendar helpers and the asset checks.
 */
class S5TrailTest {
    private val dir = File(System.getProperty("olx.assets"))
    private val model: S5Trail.Model by lazy {
        S5Trail.parseModel(File(dir, S5Trail.ASSET_META).readText(), File(dir, S5Trail.ASSET_BIN).readBytes())
    }

    @Suppress("UNCHECKED_CAST")
    private fun res(path: String): Any? = MiniJson.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())

    private fun arr(x: Any?): DoubleArray = (x as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()

    // ---- the network --------------------------------------------------------------------------------------------------

    @Test fun theShippedNetworkIsR123sHoldoutFit() {
        assertEquals(3, model.seeds.size)
        assertEquals(64, model.seeds[0].tabB.size)
        assertEquals(42 * 64, model.seeds[0].tabW.size)
        assertEquals(64, model.sha256.length)
        assertEquals(S5Trail.UNDERLYINGS.toSet(), model.neither.keys)
    }

    @Test fun everyGoldenRowMatchesR123() {
        @Suppress("UNCHECKED_CAST") val rows = res("/s5/s5golden.json") as List<Map<String, Any?>>
        assertEquals(8, rows.size)
        var worstP = 0.0; var worstR = 0.0; var worstEv = 0.0
        for (r in rows) {
            val seq = (r["seq_raw"] as List<*>).map { arr(it) }.toTypedArray()
            val o = model.predict(seq, arr(r["tab_raw"]))
            val p = (r["probs"] as List<*>).map { arr(it) }
            for (j in 0 until S5Trail.NJ) for (k in 0 until 3) worstP = maxOf(worstP, abs(o.probs[j][k] - p[j][k]))
            val rg = arr(r["range"])
            for (k in 0 until 2) worstR = maxOf(worstR, abs(o.range[k] - rg[k]))
            val ev = model.ev(r["und"] as String, o)
            val evG = arr(r["ev"])
            for (j in 0 until S5Trail.NJ) worstEv = maxOf(worstEv, abs(ev[j] - evG[j]))
            assertTrue(o.up.all { it in 0.0..1.0 })
        }
        assertTrue(worstP <= 1e-4, "probs off by $worstP")
        assertTrue(worstR <= 1e-4, "range off by $worstR")
        assertTrue(worstEv <= 0.5, "EV off by Rs $worstEv")
    }

    @Test fun scalingImputesClipsAndMasks() {
        val raw = DoubleArray(42) { Double.NaN }
        val x = model.scaleTab(raw)
        for (i in x.indices) assertEquals(((model.tabMed[i] - model.tabMu[i]) / model.tabSd[i]).coerceIn(-S5Trail.CLIP, S5Trail.CLIP), x[i], 1e-9)
        raw[0] = 1e9
        assertEquals(S5Trail.CLIP, model.scaleTab(raw)[0])
        raw[0] = -1e9
        assertEquals(-S5Trail.CLIP, model.scaleTab(raw)[0])
        val seq = Array(S5Trail.SEQ_N) { t -> doubleArrayOf(1e9, 1.0, 2.0, 3.0, 4.0, if (t == 0) 1.0 else 0.0, if (t % 2 == 0) 1.0 else 0.0) }
        val s = model.scaleSeq(seq)
        assertEquals(S5Trail.CLIP, s[0][0])
        assertEquals(0.0, s[1][0])                       // masked minute: zero
        assertEquals(1.0, s[0][5]); assertEquals(0.0, s[1][6])
        assertFailsWith<IllegalArgumentException> { model.scaleTab(DoubleArray(3)) }
        assertFailsWith<IllegalArgumentException> { model.scaleSeq(Array(3) { DoubleArray(7) }) }
        assertFailsWith<IllegalArgumentException> { model.scaleSeq(Array(S5Trail.SEQ_N) { DoubleArray(6) }) }
    }

    @Test fun theAssetsAreChecked() {
        val meta = File(dir, S5Trail.ASSET_META).readText()
        val bin = File(dir, S5Trail.ASSET_BIN).readBytes()
        val bad = bin.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta, bad) }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta, bin.copyOf(bin.size - 4)) }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta.replace("\"gap\"", "\"gapx\""), bin) }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta.replace("\"seq_n\": 120", "\"seq_n\": 60"), bin) }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta.replace("\"head_c.bias\"", "\"head_x.bias\""), bin) }
        assertFailsWith<IllegalArgumentException> { S5Trail.parseModel(meta.replace("\"range.sd\"", "\"range.xx\""), bin) }
        assertFailsWith<MiniJson.Error> { S5Trail.parseModel("[1]", bin) }
    }

    @Test fun theModelRefusesBadShapes() {
        val m = model
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(emptyList(), m.tabMed, m.tabMu, m.tabSd, m.seqMu, m.seqSd, m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, DoubleArray(3), m.tabMu, m.tabSd, m.seqMu, m.seqSd, m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, m.tabMed, DoubleArray(3), m.tabSd, m.seqMu, m.seqSd, m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, m.tabMed, m.tabMu, DoubleArray(3), m.seqMu, m.seqSd, m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, m.tabMed, m.tabMu, m.tabSd, DoubleArray(3), m.seqSd, m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, m.tabMed, m.tabMu, m.tabSd, m.seqMu, DoubleArray(3), m.rangeMu, m.rangeSd, m.neither, "x") }
        assertFailsWith<IllegalArgumentException> { S5Trail.Model(m.seeds, m.tabMed, m.tabMu, m.tabSd, m.seqMu, m.seqSd, m.rangeMu, m.rangeSd, m.neither - "SENSEX", "x") }
        val s = m.seeds[0]
        val broken = S5Trail.Seed(s.inpW, s.inpB, s.convW, s.convB, FloatArray(5), s.tabB, s.t0W, s.t0B, s.t3W, s.t3B, s.aW, s.aB, s.bW, s.bB, s.cW, s.cB)
        assertFailsWith<IllegalArgumentException> { S5Trail.forward(broken, Array(S5Trail.SEQ_N) { DoubleArray(7) }, DoubleArray(42)) }
    }

    // ---- the inputs (parity with R123's Python) -----------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun day(date: LocalDate, a: List<*>) = PassedArms.DayBars(date, arr(a[0]), arr(a[1]), arr(a[2]), arr(a[3]))

    @Test fun theWindowAndInputsMatchR123() {
        @Suppress("UNCHECKED_CAST") val fx = res("/s5/inputs_parity.json") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val bars = fx["bars"] as Map<String, Map<String, List<*>>>
        @Suppress("UNCHECKED_CAST") val pastFx = fx["past"] as Map<String, List<List<Any?>>>
        @Suppress("UNCHECKED_CAST") val vixFx = fx["vix"] as Map<String, List<*>>
        val byDay = bars.mapValues { (_, m) -> m.map { (d, a) -> LocalDate.parse(d).let { it to day(it, a) } }.toMap() }
        val vix = vixFx.map { (d, a) -> LocalDate.parse(d).let { it to day(it, a) } }.toMap()
        @Suppress("UNCHECKED_CAST") val cases = fx["cases"] as List<Map<String, Any?>>
        assertEquals(8, cases.size)
        var worstSeq = 0.0; var worstTab = 0.0; var nanMatch = 0
        for (c in cases) {
            val u = c["und"] as String
            val t = (c["t"] as Double).toInt()
            val own = byDay.getValue(u).toSortedMap().values.toList()
            val sessions = own.dropLast(1) + own.last().through(t)
            val past = pastFx.getValue(u).map { S5Trail.Past(LocalDate.parse(it[0] as String), it[1] as Double, it[2] as Double) }
            val m = S5Trail.Market(u, sessions, byDay, vix, past)
            val w = m.window(t)
            @Suppress("UNCHECKED_CAST") val ws = (c["seq"] as List<*>).map { arr(it) }
            for (k in 0 until S5Trail.SEQ_N) for (ch in 0 until 7) worstSeq = maxOf(worstSeq, abs(w[k][ch] - ws[k][ch]))
            @Suppress("UNCHECKED_CAST") val ctx = c["ctx"] as Map<String, Any?>
            val cal = S5Trail.Calendar((ctx["to_expiry"] as Double).toInt(), ctx["is_expiry"] as Boolean, ctx["is_budget"] as Boolean)
            @Suppress("UNCHECKED_CAST") val chFx = c["chain"] as Map<String, List<*>>
            fun grid(k: String): Array<Array<DoubleArray>> {
                val a = arr(chFx[k]); return Array(t + 1) { r -> Array(21) { o -> DoubleArray(2) { s -> a[(r * 21 + o) * 2 + s] } } }
            }
            val chain = S5Trail.Chain(grid("close"), grid("oi"), grid("volume"), grid("iv"))
            val x = S5Trail.inputs(m, t, cal, chain)
            val want = arr(c["tab"])
            for (k in x.indices) {
                if (want[k].isNaN()) { assertTrue(x[k].isNaN(), "$u t=$t ${S5Trail.TAB_COLS[k]}: want NaN, got ${x[k]}"); nanMatch++ }
                else worstTab = maxOf(worstTab, abs(x[k] - want[k]) / maxOf(1.0, abs(want[k])))
            }
        }
        assertTrue(worstSeq <= 1e-4, "window off by $worstSeq (R123 keeps it in float32)")
        assertTrue(worstTab <= 1e-6, "inputs off by $worstTab")
        assertTrue(nanMatch > 20)
    }

    private fun flatDay(date: LocalDate, px: Double, gapAt: Int = -1): PassedArms.DayBars {
        val c = DoubleArray(PassedArms.Session.N) { if (it == gapAt) Double.NaN else px + it * 0.5 }
        val o = DoubleArray(c.size) { if (c[it].isNaN()) Double.NaN else c[it] - 0.25 }
        return PassedArms.DayBars(date, o, DoubleArray(c.size) { c[it] + 1 }, DoubleArray(c.size) { c[it] - 1 }, c)
    }

    @Test fun aFirstSessionPadsTheWindowWithZerosAndMissingDataIsMissing() {
        val d = LocalDate.of(2026, 10, 20)
        val m = S5Trail.Market("NIFTY", listOf(flatDay(d, 25000.0).through(9)), emptyMap(), emptyMap())
        val w = m.window(9)
        assertTrue(w[0].all { it == 0.0 })
        assertEquals(1.0, w[S5Trail.SEQ_N - 10][5])          // the session's first minute: new_session
        assertEquals(0.0, w[S5Trail.SEQ_N - 10][6])          // ...with no previous close: masked
        assertEquals(1.0, w[S5Trail.SEQ_N - 1][6])
        assertEquals(0.0, w[S5Trail.SEQ_N - 1][4])           // no other index: breadth 0
        val x = S5Trail.inputs(m, 9, S5Trail.Calendar(null, false, false), null)
        val f = S5Trail.TAB_COLS.zip(x.toList()).toMap()
        assertTrue(f.getValue("to_expiry").isNaN()); assertTrue(f.getValue("gap").isNaN()); assertTrue(f.getValue("prev_range").isNaN())
        assertTrue(f.getValue("avg_range20").isNaN()); assertTrue(f.getValue("vix").isNaN()); assertTrue(f.getValue("atm_iv").isNaN())
        assertTrue(f.getValue("rv30").isFinite())
        assertEquals(1.0, f.getValue("u_NIFTY")); assertEquals(0.0, f.getValue("u_SENSEX"))
        assertTrue(f.getValue("br_up").isNaN())             // no previous close anywhere
        assertFailsWith<IllegalArgumentException> { S5Trail.Market("NIFTY", emptyList(), emptyMap(), emptyMap()) }
        // A day with too few minutes for rv30; a missing day close and missing VIX today
        val early = S5Trail.Market("NIFTY", listOf(flatDay(d.minusDays(1), 24990.0), flatDay(d, 25000.0, gapAt = 2).through(4)), emptyMap(), emptyMap())
        val e = S5Trail.TAB_COLS.zip(S5Trail.inputs(early, 4, S5Trail.Calendar(40, true, true), null).toList()).toMap()
        assertEquals(25.0, e.getValue("to_expiry")); assertEquals(1.0, e.getValue("is_expiry")); assertEquals(1.0, e.getValue("is_budget"))
        assertTrue(e.getValue("prev_range").isFinite()); assertTrue(e.getValue("prev_ret").isNaN())
        assertTrue(e.getValue("ret60").isFinite())
    }

    @Test fun theChainIsReadOnEachMinutesOwnAtm() {
        val step = 50
        val spot = DoubleArray(20) { if (it == 3) Double.NaN else 25010.0 + it * 3 }
        val bars = HashMap<Pair<Int, Boolean>, Array<DoubleArray>>()
        for (k in (25000 - 12 * step)..(25000 + 12 * step) step step) for (call in listOf(true, false)) {
            bars[k to call] = Array(20) { r -> if (r == 5 && k == 25000) doubleArrayOf(Double.NaN, Double.NaN, Double.NaN) else doubleArrayOf(100.0 + r, 1000.0 + r, if (call) 10.0 else 20.0) }
        }
        var ivCalls = 0
        val ch = S5Trail.Chain.of("NIFTY", 19, spot, bars) { p, s, k, r, call -> ivCalls++; assertTrue(p > 0 && s > 0 && k > 0 && r >= 0); if (call) 12.0 else 14.0 }
        assertTrue(ivCalls > 0)
        assertTrue(ch.close[3][10][0].isNaN())                // no spot: the minute is empty
        val f = ch.features(19, 65, 25060.0)
        assertEquals(13.0, f.getValue("atm_iv"), 1e-12)
        assertEquals(14.0 - 12.0, f.getValue("skew"), 1e-12)
        assertEquals(1.0, f.getValue("pcr"), 1e-12)
        assertTrue(f.getValue("cp_vol15") < -0.6 && f.getValue("cp_vol15") > -0.7)   // calls trade half the puts' volume
        assertTrue(f.getValue("pcr_chg15").isFinite())
        assertEquals(ln(119.0 * 65), f.getValue("log_c_rs"), 1e-12)
        val none = ch.features(19, 65, Double.NaN)
        assertTrue(none.values.all { it.isNaN() })
        // no OI fifteen minutes back: the changes stay missing
        val thin = S5Trail.Chain(Array(16) { Array(21) { DoubleArray(2) { Double.NaN } } }.also { a -> a[15][10][0] = 5.0; a[15][10][1] = 6.0 },
            Array(16) { r -> Array(21) { DoubleArray(2) { if (r == 15) 100.0 else Double.NaN } } },
            Array(16) { Array(21) { DoubleArray(2) { -3.0 } } }, Array(16) { Array(21) { DoubleArray(2) { Double.NaN } } })
        val g = thin.features(15, 65, 25000.0)
        assertTrue(g.getValue("pcr_chg15").isNaN()); assertTrue(g.getValue("atm_iv").isNaN())
        assertEquals(0.0, g.getValue("cp_vol15"), 1e-12)       // negative prints count as none
        assertEquals(ln((5.0 + 6.0) * 65), g.getValue("log_strad_rs"), 1e-12)
        val zero = S5Trail.Chain(Array(1) { Array(21) { DoubleArray(2) { 0.0 } } }, Array(1) { Array(21) { DoubleArray(2) { 0.0 } } },
            Array(1) { Array(21) { DoubleArray(2) { Double.NaN } } }, Array(1) { Array(21) { DoubleArray(2) { Double.NaN } } })
        val z = zero.features(0, 65, 25000.0)
        assertTrue(z.getValue("atm_c_pct").isNaN()); assertTrue(z.getValue("pcr").isNaN())
    }

    // ---- the pick and the exits ---------------------------------------------------------------------------------------

    @Test fun thePickIsTheBestFillableEvOfAtLeast300AtTheMorningRows() {
        val ev = DoubleArray(16) { 100.0 }.also { it[3] = 450.0; it[6] = 500.0; it[9] = Double.NaN }
        val p = assertNotNull(S5Trail.pick("NIFTY", 9, ev) { true })
        assertEquals(6, p.j); assertTrue(p.call); assertEquals(2000.0, p.stopRs); assertEquals(1000.0, p.targetRs); assertEquals(60, p.minutes)
        val q = assertNotNull(S5Trail.pick("NIFTY", 4, ev) { it != 6 })
        assertEquals(3, q.j); assertFalse(q.call); assertEquals(1000.0, q.stopRs); assertEquals(1000.0, q.targetRs); assertEquals(60, q.minutes)
        assertNull(S5Trail.pick("NIFTY", 19, ev) { true })                     // not a decision row
        assertNull(S5Trail.pick("NIFTY", 14, ev) { false })                    // nothing fillable
        val edge = DoubleArray(16) { 299.99 }
        assertNull(S5Trail.pick("NIFTY", 14, edge) { true })
        edge[15] = 300.0
        assertEquals(15, S5Trail.pick("NIFTY", 14, edge) { true }?.j)
        assertNull(S5Trail.pick("NIFTY", 14, DoubleArray(16) { Double.NaN }) { true })
    }

    @Test fun windowsAndSquareOff() {
        assertEquals(PassedArms.Session.row(15, 19), S5Trail.lastRow(false))
        assertEquals(PassedArms.Session.row(15, 4), S5Trail.lastRow(true))
        assertTrue(S5Trail.fitsWindow(1, 5, S5Trail.lastRow(true)))
        assertFalse(S5Trail.fitsWindow(1, S5Trail.lastRow(true) - 10, S5Trail.lastRow(true)))
        assertTrue(S5Trail.fitsWindow(0, S5Trail.lastRow(false) - 29, S5Trail.lastRow(false)))
        assertFalse(S5Trail.fitsWindow(0, S5Trail.lastRow(false) - 28, S5Trail.lastRow(false)))
    }

    private val charge: (Boolean, Double) -> Double = { buy, px -> if (buy) 20.0 + px * 0.0003 * 65 else 20.0 + px * 0.0013 * 65 }

    @Test fun theStopPremiumNetsTheCellsLossAfterCharges() {
        val sp = assertNotNull(S5Trail.stopPremium(150.0, 65, 2000.0, charge))
        // The fill: 2 ticks and 10 bps under the trigger (research's stop fill) nets about -Rs 2,000 (within a tick).
        val fill = sp * (1 - S5Trail.STOP_BPS / 1e4) - 2 * 0.05
        val net = S5Trail.netAt(150.0, fill, 65, charge)
        assertTrue(net >= -2000.0 - 1e-6 && net <= -2000.0 + 0.05 * 65 + 1e-6, "net at the stop's fill $net")
        val thin = assertNotNull(S5Trail.stopPremium(150.0, 65, 2000.0, charge, ticks = 2))
        assertTrue(thin > sp)
        assertEquals(0.0, (sp * 100).let { it - Math.round(it) }, 1e-6)
        assertTrue(abs(sp / 0.05 - Math.round(sp / 0.05)) < 1e-6)
        assertNull(S5Trail.stopPremium(20.0, 65, 2000.0, charge))           // the loss is more than the premium
        assertEquals(10.0 * 65 - charge(true, 150.0) - charge(false, 160.0), S5Trail.netAt(150.0, 160.0, 65, charge), 1e-9)
    }

    @Test fun theLockTrailsAt60PercentOfTheBestOnceUp1000() {
        var tr = S5Trail.Trail()
        assertNull(tr.floor)
        for (n in listOf(200.0, 900.0, 500.0)) { val (t2, out) = tr.onClose(n); assertFalse(out); tr = t2 }
        assertNull(tr.floor)
        val (t3, o3) = tr.onClose(1500.0); assertFalse(o3); tr = t3
        assertEquals(900.0, tr.floor!!, 1e-9)
        val (t4, o4) = tr.onClose(Double.NaN); assertFalse(o4); assertEquals(tr, t4)
        val (t5, o5) = tr.onClose(901.0); assertFalse(o5); tr = t5
        val (_, o6) = tr.onClose(900.0); assertTrue(o6)
        val (_, o7) = S5Trail.Trail().onClose(1000.0); assertFalse(o7)
        val (_, o8) = S5Trail.Trail(1000.0).onClose(600.0); assertTrue(o8)
    }

    // ---- the calendar helpers -----------------------------------------------------------------------------------------

    @Test fun calendarHelpers() {
        val d = LocalDate.of(2026, 10, 26)
        val days = (0..10).map { d.plusDays(it.toLong()) }.filter { it.dayOfWeek.value < 6 }
        assertEquals(2, S5Trail.sessionsToExpiry(d, d.plusDays(2), days))
        assertEquals(0, S5Trail.sessionsToExpiry(d, d, days))
        assertNull(S5Trail.sessionsToExpiry(d, null, days))
        assertEquals((1 * 86400 + 6 * 3600 + 10 * 60).toDouble() / (365 * 86400), S5Trail.yearsToExpiry(d, 4, d.plusDays(1)), 1e-12)
        assertEquals(0.0, S5Trail.yearsToExpiry(d, 4, d.minusDays(1)))
        assertEquals(25050, S5Trail.atm(25025.0, 50))
        assertEquals(25000, S5Trail.atm(25024.99, 50))
        assertEquals(100, S5Trail.step("SENSEX")); assertEquals(25, S5Trail.step("MIDCPNIFTY"))
        val past = S5Trail.Past.of(d, 110.0, 90.0, 100.0)
        assertEquals(20.0, past.range, 1e-12); assertEquals(100.0, past.close)
        val pd = S5Trail.Past.of(flatDay(d, 100.0))
        assertEquals(pd.close, flatDay(d, 100.0).lastClose)
        assertTrue(S5Trail.NOT_PROVEN.contains("Failed research gates") && S5Trail.NOT_PROVEN.contains("never live"))
        assertTrue(S5Trail.ARM.paperOnly)
    }
}
