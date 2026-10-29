package com.optionslab.engine.orb

import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CISD + network ([CisdNet], R133 S3-V2-E3) against the research code itself on RECORDED lake days (fixture: android/tools/
 * cisd_parity_fixture.py, research/hunt R131 / R132 / R134 code unchanged):
 *  - the network: the app's N1 (assets s5/, the same weights and scalers R134's nnlive rebuilds) gives R123's saved outputs to 1e-5;
 *  - the inputs: the 120 x 7 window and the 42 inputs the app builds from the same minutes and per-strike option bars equal R123's;
 *  - the setups: every CISD setup R131 found, its filters' verdicts, its target, the network filter's verdict, and R132's E3 exit
 *    (minute and reason) for each trade.
 */
class CisdNetParityTest {
    private val dir = File(System.getProperty("olx.assets"))
    private val model: S5Trail.Model by lazy {
        S5Trail.parseModel(File(dir, S5Trail.ASSET_META).readText(), File(dir, S5Trail.ASSET_BIN).readBytes())
    }

    @Suppress("UNCHECKED_CAST")
    private val fx: Map<String, Any?> by lazy {
        val text = GZIPInputStream(javaClass.getResourceAsStream("/cisd/parity.json.gz")!!).bufferedReader().readText()
        MiniJson.parse(text) as Map<String, Any?>
    }

    private fun arr(x: Any?): DoubleArray = (x as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()
    @Suppress("UNCHECKED_CAST") private fun obj(x: Any?) = x as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun list(x: Any?) = x as List<Any?>

    /** Undoes the fixture's delta coding (whole numbers; null stays a gap). */
    private fun undelta(x: Any?): DoubleArray {
        var last = 0.0
        return list(x).map { v -> if (v == null) Double.NaN else { last += v as Double; last } }.toDoubleArray()
    }

    // ---- the network --------------------------------------------------------------------------------------------------

    @Test fun theNetworkGivesR123sOutputsTo1e5() {
        val rows = list(fx["net"]).map { obj(it) }
        assertEquals(24, rows.size)
        var worstP = 0.0; var worstEv = 0.0
        for (r in rows) {
            val o = model.predict(list(r["seq"]).map { arr(it) }.toTypedArray(), arr(r["tab"]))
            val pw = arr(r["pw"]); val pl = arr(r["pl"]); val pn = arr(r["pn"])
            for (j in 0 until S5Trail.NJ) {
                worstP = max(worstP, abs(o.probs[j][0] - pw[j])); worstP = max(worstP, abs(o.probs[j][1] - pl[j]))
                worstP = max(worstP, abs(o.probs[j][2] - pn[j]))
            }
            val ev = model.ev(r["und"] as String, o)
            val evR = arr(r["ev"])
            for (j in 0 until S5Trail.NJ) worstEv = max(worstEv, abs(ev[j] - evR[j]))
        }
        println("CISD parity: network max |P - R123| = $worstP, max |EV - R123| = Rs $worstEv over ${rows.size} rows")
        assertTrue(worstP <= 1e-5, "probabilities off by $worstP")
        assertTrue(worstEv <= 0.03, "EV off by Rs $worstEv")
    }

    // ---- the recorded days ------------------------------------------------------------------------------------------------

    private class Day(val date: LocalDate, val index: Map<String, List<PassedArms.DayBars>>, val vix: List<PassedArms.DayBars>, val raw: Map<String, Any?>)

    private fun sessions(m: Map<String, Any?>): List<PassedArms.DayBars> = m.entries.map { (d, v) ->
        val a = list(v).map { arr(it) }
        PassedArms.DayBars(LocalDate.parse(d), a[0], a[1], a[2], a[3])
    }.sortedBy { it.date }

    private val days: List<Day> by lazy {
        list(fx["days"]).map { obj(it) }.map { d ->
            Day(LocalDate.parse(d["day"] as String), obj(d["index"]).mapValues { sessions(obj(it.value)) }, sessions(obj(d["vix"])), d)
        }
    }

    @Test fun everySetupFilterTargetAndExitMatchesR131AndR132() {
        var setups = 0; var trades = 0; var kept = 0
        for (day in days) {
            for ((u, ss) in day.index) {
                val want = list(obj(day.raw["setups"])[u]).map { obj(it) }
                val sides = CisdNet.Sides(ss.takeLast(CisdNet.SESSIONS_READ + 1))
                val today = sides.sessions.size - 1
                assertEquals(day.date, sides.sessions[today].date)
                val all = CisdNet.candidates(sides)
                val mine = all.filter { it.d == today }
                assertEquals(want.map { (it["side"] as Double).toInt() to (it["r0"] as Double).toInt() },
                    mine.map { it.side to it.r0 }, "$u ${day.date}: the setups")
                val expiries = list(obj(day.raw["expiries"])[u]).map { LocalDate.parse(it as String) }
                val blocked = CisdNet.expiryFor(day.date, expiries) == null
                var busy = -1
                for ((c, w) in mine.zip(want)) {
                    setups++
                    assertEquals(w["stop"] as Double, c.stop, 1e-6, "$u ${c.r0}: the stop")
                    val ref = ss.last().o.getOrElse(c.r0) { Double.NaN }
                    val why = when {
                        all.any { it.d == c.d && it.r0 == c.r0 && it.side != c.side } -> "both"
                        c.r0 <= busy -> "busy"
                        c.r0 > CisdNet.LAST_ENTRY -> "late"
                        blocked -> "blocked"
                        else -> CisdNet.check(sides, c, all, ref)?.let { r ->
                            when { "past the stop" in r -> "no_room"; "target" in r -> "no_target"; "no index price" in r -> "no_price"; else -> r } }
                    }
                    val target = if (ref.isFinite() && c.side * ref > c.stopSide) CisdNet.structTarget(sides, c.side, c.d, c.r0, c.side * ref) else null
                    if (w.containsKey("target")) assertEquals(w["target"] as Double?, target?.let { it * c.side }, "$u ${c.r0}: the target")
                    if (w["skip"] == "no_option") continue                     // the option leg: below, from the bought ones
                    assertEquals(w["skip"] as String?, why, "$u ${day.date} ${c.side} ${c.r0}: R131's verdict")
                    if (why != null) continue
                    // R132 E3's exit on the option bought (research's own buy fill), and the busy window it sets.
                    trades++
                    val opt = list(w["opt"]).map { arr(it) }.toTypedArray()
                    val lot = (w["lot"] as Double).toInt()
                    assertEquals(w["strike"] as Double, CisdNet.strike(u, ss.last().closeAtOrBefore(c.r0 - 1)).toDouble(), "$u ${c.r0}: ATM")
                    val ex = assertNotNull(CisdNet.simExit(opt, ss.last().h, ss.last().l, c.call, c.r0, w["buy"] as Double, lot, c.stop))
                    assertEquals((w["exit"] as Double).toInt(), ex.row, "$u ${c.r0}: the exit minute")
                    assertEquals(w["why"] as String, ex.why, "$u ${c.r0}: the exit")
                    busy = ex.row
                    // The network filter: the latest view, its side EV, kept or not.
                    val t = CisdNet.viewRow(c.r0)
                    assertEquals((w["view"] as Double?)?.toInt(), t)
                    if (w.containsKey("kept")) {
                        val row = list(day.raw["rows"]).map { obj(it) }.single { it["und"] == u && (it["row"] as Double).toInt() == t }
                        val ev = arr(row["ev"])
                        assertEquals(w["ev_side"] as Double, CisdNet.evSide(ev, c.call), 1e-5)
                        assertEquals(w["kept"] as Boolean, CisdNet.keep(ev, c.call), "$u ${c.r0}: the filter")
                        if (w["kept"] == true) kept++
                    }
                }
            }
        }
        println("CISD parity: $setups setups, $trades traded before the filter, $kept kept (R134's replay: 4 on these days)")
        assertEquals(4, kept)
        assertTrue(setups >= 15 && trades >= 4)
    }

    /**
     * The app's 42 inputs and window from the recorded minutes and per-strike option bars (IV: the lake's, as R123 read it) against
     * R123's. The index, VIX, breadth and calendar inputs and the window match to rounding. The chain inputs match except where
     * Dhan's own ATM (from its spot at that second) is one strike away from the app's (from the index close): counted and bounded.
     */
    @Test fun theInputsAndWindowMatchR123OnRecordedDays() {
        var rows = 0; var worstW = 0.0; var worstIdx = 0.0; var chainOff = 0; var worstEvSide = 0.0; var sameVerdict = 0; var verdicts = 0
        var appIvSame = 0; var worstAppIv = 0.0
        val flips = ArrayList<String>()
        val chainCols = setOf("atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew", "pcr",
            "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15")
        for (day in days) {
            val byDay = day.index.mapValues { (_, ss) -> ss.associateBy { it.date } }
            val vix = day.vix.associateBy { it.date }
            for (r in list(day.raw["rows"]).map { obj(it) }) {
                rows++
                val u = r["und"] as String
                val t = (r["row"] as Double).toInt()
                val ss = day.index.getValue(u)
                val past = list(obj(day.raw["past"])[u]).map { p -> val a = list(p); S5Trail.Past(LocalDate.parse(a[0] as String), a[1] as Double, a[2] as Double) }
                val m = S5Trail.Market(u, ss.dropLast(1).takeLast(3) + ss.last().through(t), byDay, vix, past)
                val win = m.window(t)
                val wR = list(r["seq"]).map { arr(it) }
                for (i in 0 until S5Trail.SEQ_N) for (k in 0 until 7) worstW = max(worstW, abs(win[i][k] - wR[i][k]))
                val cal = obj(obj(day.raw["cal"])[u])
                val calendar = S5Trail.Calendar((cal["to_expiry"] as Double?)?.toInt(), cal["is_expiry"] as Boolean, cal["is_budget"] as Boolean)
                // The chain: the lake's per-strike minutes laid on the app's offset grid by the app's own code.
                val bars = HashMap<Pair<Int, Boolean>, Array<DoubleArray>>()
                val ivs = HashMap<Triple<Int, Boolean, Int>, Double>()
                val dhanOff = HashMap<Triple<Int, Boolean, Int>, Int>()
                for (cb in list(obj(day.raw["chains"])[u]).map { obj(it) }) {
                    val k = (cb["strike"] as Double).toInt(); val call = cb["call"] as Boolean
                    val rr = undelta(cb["rows"]); val cl = arr(cb["close"]); val oi = undelta(cb["oi"]); val vol = undelta(cb["vol"])
                    val iv = arr(cb["iv"]); val off = undelta(cb["off"])
                    val a = Array(PassedArms.Session.N) { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN) }
                    for (i in rr.indices) {
                        val row = rr[i].toInt()
                        a[row] = doubleArrayOf(cl[i], oi[i], vol[i])
                        ivs[Triple(k, call, row)] = iv[i]
                        dhanOff[Triple(k, call, row)] = off[i].toInt()
                    }
                    bars[k to call] = a
                }
                val spot = DoubleArray(t + 1).also { a -> var last = Double.NaN; for (q in 0..t) { if (ss.last().c[q].isFinite()) last = ss.last().c[q]; a[q] = last } }
                val chain = S5Trail.Chain.of(u, t, spot, bars) { _, _, strike, row, call -> ivs[Triple(strike, call, row)] ?: Double.NaN }
                val x = S5Trail.inputs(m, t, calendar, chain)
                val xR = arr(r["tab"])
                // Does Dhan's offset grid agree with the app's at the rows the chain inputs read (t - 15 .. t)?
                val st = S5Trail.step(u)
                fun agrees(row: Int): Boolean = row < 0 || dhanOff.entries.filter { it.key.third == row }.all { (key, off) ->
                    (key.first - S5Trail.atm(spot[row], st)) / st == off }
                val gridAgrees = (t - 15..t).all { agrees(it) }
                var off = false
                for ((i, name) in S5Trail.TAB_COLS.withIndex()) {
                    val a = x[i]; val b = xR[i]
                    if (a.isNaN() && b.isNaN()) continue
                    val d = abs(a - b) / max(1.0, abs(b))
                    if (name in chainCols) {
                        val tol = if (name == "atm_iv" || name == "skew") 2e-4 else 1e-6
                        if (!(d <= tol)) { off = true; assertTrue(!gridAgrees, "$u ${day.date} row $t $name: $a vs R123 $b (the grids agree)") }
                    } else {
                        assertTrue(d <= 1e-6, "$u ${day.date} row $t $name: $a vs R123 $b")
                        worstIdx = max(worstIdx, d)
                    }
                }
                if (off) chainOff++
                // End to end: the network on the app's own inputs, the side EVs and the filter's verdicts.
                val ev = model.ev(u, model.predict(win, x))
                for (call in listOf(true, false)) {
                    val e = CisdNet.evSide(ev, call); val eR = (if (call) r["ev_c"] else r["ev_p"]) as Double
                    worstEvSide = max(worstEvSide, abs(e - eR))
                    verdicts++; if ((e > 0) == (eR > 0)) sameVerdict++
                }
                // The same with the IV the app computes itself (Black-76 on the index, rate 0, plus S5 Trail's measured offset): the
                // phone has no Dhan IV. Measured, not hidden: how far the side EVs move and whether the verdicts hold.
                val nearest = list(obj(day.raw["expiries"])[u]).map { LocalDate.parse(it as String) }.first { !it.isBefore(day.date) }
                val appChain = S5Trail.Chain.of(u, t, spot, bars) { price, s, strike, row, call ->
                    val off = (strike - S5Trail.atm(s, st)) / st
                    val iv = com.optionslab.engine.options.OptionMath.impliedVol(price, if (call) com.optionslab.engine.options.OptionType.CE
                        else com.optionslab.engine.options.OptionType.PE, s, strike.toDouble(), S5Trail.yearsToExpiry(day.date, row, nearest))
                    if (iv == null || !iv.isFinite()) Double.NaN else iv * 100 + (APP_IV_SHIFT[call to off] ?: 0.0)
                }
                val evApp = model.ev(u, model.predict(win, S5Trail.inputs(m, t, calendar, appChain)))
                for (call in listOf(true, false)) {
                    val e = CisdNet.evSide(evApp, call); val eR = (if (call) r["ev_c"] else r["ev_p"]) as Double
                    worstAppIv = max(worstAppIv, abs(e - eR))
                    if ((e > 0) == (eR > 0)) appIvSame++ else flips += "$u ${day.date} row $t ${if (call) "call" else "put"}: R123 ${"%.0f".format(eR)}, app IV ${"%.0f".format(e)}"
                }
            }
        }
        println("CISD parity: $rows rows; window max diff $worstW; index/VIX/breadth/calendar inputs max rel diff $worstIdx; " +
            "chain inputs differ on $chainOff rows (Dhan's ATM one strike from the app's); side EV max diff Rs $worstEvSide; " +
            "filter verdicts $sameVerdict of $verdicts the same; with the app's own IV: side EV max diff Rs $worstAppIv, verdicts $appIvSame of $verdicts the same $flips")
        assertTrue(rows >= 15)
        assertTrue(worstW <= 1e-6, "window off by $worstW")
        assertTrue(chainOff * 4 <= rows, "chain inputs differ on $chainOff of $rows rows")
        assertEquals(verdicts, sameVerdict, "the filter's verdicts")
        // Measured on 29 Oct and written down in CisdNet.IV_GAP (why the arm is built off): 2 verdicts move, both research trades
        // (FINNIFTY 11 Sep 10:15 call, SENSEX 11 Sep 13:55 call). A change here must update IV_GAP.
        assertEquals(verdicts - 2, appIvSame, "with the app's own IV: $flips")
        assertTrue(flips.all { it.contains("2026-09-11") && it.contains("call") }, "$flips")
    }

    /** S5 Trail's measured Dhan-minus-app IV offset by side and strike offset (app S5Arm.IV_SHIFT, research s5_app_check.IV_SHIFT). */
    private val APP_IV_SHIFT = mapOf((true to -3) to -2.02, (true to 0) to -1.53, (true to 3) to -1.21, (false to -3) to 0.97, (false to 0) to 1.31,
        (false to 3) to 1.79)

    @Test fun theCalendarFromTheListedExpiriesIsR123s() {
        for (day in days) {
            val after = list(day.raw["trading_after"]).map { LocalDate.parse(it as String) }
            for ((u, c) in obj(day.raw["cal"])) {
                val exp = list(obj(day.raw["expiries"])[u]).map { LocalDate.parse(it as String) }.firstOrNull { !it.isBefore(day.date) }
                assertEquals((obj(c)["to_expiry"] as Double?)?.toInt(), S5Trail.sessionsToExpiry(day.date, exp, after), "$u ${day.date}")
                assertEquals(obj(c)["is_expiry"] as Boolean, exp == day.date)
            }
        }
    }
}
