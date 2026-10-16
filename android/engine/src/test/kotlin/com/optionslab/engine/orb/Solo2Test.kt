package com.optionslab.engine.orb

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Solo 2.0" ([Solo2], research R57's A2): the model the app ships (app assets solo2/A2.json, byte for byte R57's) scores every
 * golden vector R57 exported within 1e-9; the 29 inputs match R57's own code on synthetic sessions (android/tools/
 * solo2_parity_fixture.py: research/brain/features + r57model.row_a); the threshold's edge; the take rule (time order, the
 * best at one minute, one trade a day); S1-S4 as R57 built them; sessions to expiry; the JSON reader's errors.
 */
class Solo2Test {
    private val asset = File(System.getProperty("olx.assets"), Solo2.ASSET)
    private val model: Solo2.Model by lazy { Solo2.parseModel(asset.readText()) }

    @Suppress("UNCHECKED_CAST")
    private fun res(path: String): Map<String, Any?> = MiniJson.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText()) as Map<String, Any?>

    @Test fun theShippedModelIsR57sA2() {
        assertTrue(asset.isFile, "the model ships in the app's assets")
        assertEquals(Solo2.FEATURES, model.features)
        assertEquals(0.4871002376817254, model.threshold)
        assertEquals(">=", model.rule)
        assertEquals(-0.16640088829705177, model.intercept)
        assertEquals(0.21680727409609596, model.coef[Solo2.FEATURES.indexOf("vix")])
    }

    @Test fun everyGoldenVectorScoresWithin1e9() {
        val g = res("/solo2/A2_golden.json")
        assertEquals("A2.json", g["model"])
        @Suppress("UNCHECKED_CAST") val rows = g["rows"] as List<Map<String, Any?>>
        assertEquals(25, rows.size)
        var worst = 0.0
        for (r in rows) {
            val x = (r["inputs"] as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()
            val s = model.score(x)
            worst = maxOf(worst, abs(s - (r["score"] as Double)))
        }
        assertTrue(worst <= 1e-9, "max abs diff $worst")
    }

    @Test fun theThresholdIsAtLeastNotAbove() {
        assertTrue(model.take(model.threshold))
        assertTrue(model.take(Math.nextUp(model.threshold)))
        assertFalse(model.take(Math.nextDown(model.threshold)))
        val strict = model.copy(rule = ">")
        assertFalse(strict.take(strict.threshold)); assertTrue(strict.take(Math.nextUp(strict.threshold)))
        // Every input missing: the training medians, one score.
        val med = model.score(DoubleArray(29) { Double.NaN })
        assertEquals(model.score(model.medians.copyOf()), med, 0.0)
        assertFailsWith<IllegalArgumentException> { model.score(DoubleArray(28)) }
        assertEquals(0.5, Solo2.sigmoid(0.0)); assertTrue(Solo2.sigmoid(-800.0) >= 0.0 && Solo2.sigmoid(800.0) <= 1.0)
    }

    @Test fun aModelThatIsNotA2IsRefused() {
        val text = asset.readText()
        assertFailsWith<IllegalArgumentException> { Solo2.parseModel(text.replace("\"kind\":\"logit\"", "\"kind\":\"hgb_clf\"")) }
        assertFailsWith<IllegalArgumentException> { Solo2.parseModel(text.replace("\"ret_15m\"", "\"ret_5m\"")) }
        assertFailsWith<IllegalArgumentException> { Solo2.parseModel(text.replace("\"rule\":\">=\"", "\"rule\":\"<\"")) }
        assertFailsWith<MiniJson.Error> { Solo2.parseModel("[1]") }
        assertFailsWith<IllegalArgumentException> { model.copy(scale = DoubleArray(29) { 0.0 }) }
        assertFailsWith<IllegalArgumentException> { model.copy(coef = DoubleArray(3)) }
        assertEquals(">=", Solo2.parseModel(text.replace(",\"rule\":\">=\"", "")).rule)
        assertFalse(model == model.copy()); assertEquals(model, model); assertEquals(System.identityHashCode(model), model.hashCode())
    }

    @Test fun theJsonReader() {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse(" {\"a\": [1, -2.5e-3, true, false, null], \"b\": {}, \"c\": [], \"d\": \"x\\\"y\\n\\u0041\\t\\r\\b\\f\\/\"} ") as Map<String, Any?>
        assertEquals(listOf(1.0, -0.0025, true, false, null), o["a"])
        assertEquals(emptyMap<String, Any?>(), o["b"]); assertEquals(emptyList<Any?>(), o["c"])
        assertEquals("x\"y\nA\t\r\b\u000c/", o["d"])
        for (bad in listOf("", "{", "[1,", "[1 2]", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "{1:2}", "\"abc", "\"a\\", "\"\\u12\"", "tru", "nul", "x", "1 2", "-", "[", "{\"a\":"))
            assertFailsWith<MiniJson.Error>(bad) { MiniJson.parse(bad) }
    }

    // ---- the 29 inputs ---------------------------------------------------------------------------------------------------

    private fun arr(x: Any?): DoubleArray = (x as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()

    @Test fun the29InputsAreR57sOnSyntheticSessions() {
        val f = res("/solo2/features_parity.json")
        assertEquals(Solo2.FEATURES, f["names"])
        @Suppress("UNCHECKED_CAST") val days = f["days"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val cases = f["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 16)
        for (c in cases) {
            val und = c["und"] as String
            val d = days.getValue(und)
            val bars = PassedArms.DayBars(LocalDate.parse(d["day"] as String), arr(d["o"]), arr(d["h"]), arr(d["l"]), arr(d["c"]))
            @Suppress("UNCHECKED_CAST") val ctx = d["ctx"] as Map<String, Double>
            val x = Solo2.Context(und, d["prev_close"] as Double, (d["prev_ranges"] as List<*>).map { (it as Double?) ?: Double.NaN },
                d["atr"] as Double, arr(d["vix"]), d["vix_prev"] as Double, d["dte"] as Double, ctx.getValue("pcr5"), ctx.getValue("iv_atm"), ctx.getValue("iv_skew"))
            val frow = (c["frow"] as Double).toInt()
            val setup = Solo2.Setup.valueOf(c["setup"] as String)
            val cand = Solo2.Candidate(und, setup, (c["side"] as Double).toInt(), frow + 1, listOf(0))
            val got = Solo2.features(bars, cand, x)
            val want = arr(c["expected"])
            for (i in want.indices) {
                if (want[i].isNaN()) assertTrue(got[i].isNaN(), "$und row $frow ${Solo2.FEATURES[i]}: want NaN, got ${got[i]}")
                else assertEquals(want[i], got[i], 1e-9 * maxOf(1.0, abs(want[i])), "$und row $frow ${Solo2.FEATURES[i]}")
            }
        }
    }

    private val day = LocalDate.of(2026, 10, 20)
    private fun session(px: (Int) -> Double, rows: Iterable<Int> = 0 until 375): PassedArms.DayBars {
        val o = DoubleArray(375) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        for (r in rows) { val p = px(r); o[r] = p; h[r] = p + 2; l[r] = p - 2; c[r] = p }
        return PassedArms.DayBars(day, o, h, l, c)
    }

    @Test fun missingInputsStayMissingForTheModelsMedian() {
        val d = session({ 25_000.0 + it })
        val c = Solo2.Candidate("NIFTY", Solo2.Setup.S2, 1, 101, listOf(25_000))
        // Nothing known about yesterday, VIX or the chain: those inputs are NaN (the model fills them).
        val x = Solo2.Context("NIFTY", Double.NaN, emptyList(), Double.NaN, null, Double.NaN, Double.NaN)
        val f = Solo2.features(d, c, x)
        for (n in listOf("gap_pct", "day_range_vs_adr10", "dist_prev_close_pct", "atr14d_pct", "dte", "vix", "vix_chg_pct", "pcr_oi", "iv_atm", "iv_skew"))
            assertTrue(f[Solo2.FEATURES.indexOf(n)].isNaN(), n)
        assertEquals(100.0, f[Solo2.FEATURES.indexOf("minute")])
        assertEquals(1.0, f[Solo2.FEATURES.indexOf("idx_NIFTY")]); assertEquals(0.0, f[Solo2.FEATURES.indexOf("idx_MIDCPNIFTY")])
        assertEquals(1.0, f[Solo2.FEATURES.indexOf("setup_S2")]); assertEquals(1.0, f[Solo2.FEATURES.indexOf("side")])
        assertTrue(model.score(f).isFinite())
        // A chain reading that is not positive counts as missing (PCR, IV); a negative skew is a real skew.
        val y = Solo2.Context("NIFTY", 25_000.0, List(10) { 200.0 }, 300.0, DoubleArray(375) { 14.0 }, 13.0, 2.0, pcr5 = 0.0, ivAtm = -1.0, ivSkew = -0.5)
        val g = Solo2.features(d, c, y)
        assertTrue(g[Solo2.FEATURES.indexOf("pcr_oi")].isNaN() && g[Solo2.FEATURES.indexOf("iv_atm")].isNaN())
        assertEquals(-0.5, g[Solo2.FEATURES.indexOf("iv_skew")])
        assertEquals(14.0, g[Solo2.FEATURES.indexOf("vix")]); assertEquals((14.0 / 13.0 - 1) * 100, g[Solo2.FEATURES.indexOf("vix_chg_pct")], 1e-12)
        // The ADR needs 8 of the 10 sessions.
        assertTrue(Solo2.adr10(List(7) { 100.0 } + List(3) { Double.NaN }).isNaN())
        assertEquals(100.0, Solo2.adr10(List(8) { 100.0 } + List(2) { Double.NaN }))
        assertEquals(150.0, Solo2.adr10(List(5) { 1000.0 } + List(10) { 150.0 }))
        // A missing minute at the signal row: the running high / low there are NaN (pandas), and so the range inputs.
        val gap = session({ 25_000.0 + it }, (0 until 100) + (101 until 375))
        val fg = Solo2.features(gap, c, y)
        assertTrue(fg[Solo2.FEATURES.indexOf("pos_in_day_range")].isNaN() && fg[Solo2.FEATURES.indexOf("ret_15m")].isNaN())
    }

    // ---- the setups and the take rule --------------------------------------------------------------------------------------

    @Test fun s1IsSoloMiddaysSignalWithR57sStrikesForEachIndex() {
        val at = day.atTime(12, 0)
        val sig = SoloMidday.Signal("MIDCPNIFTY", 1, 13_000.0, 13_100.0, 13_110.0, 12_990.0, 100.0, 13_105.0, at)
        val c = Solo2.s1("MIDCPNIFTY", SoloMidday.Decision("MIDCPNIFTY", sig, "signal"))!!
        // ATM 13,100 (25s), 4-ITM 13,000, then 13,025, 12,975 (the less in the money first).
        assertEquals(listOf(13_000, 13_025, 12_975), c.strikes)
        assertEquals(165, c.entryRow); assertEquals(164, c.signalRow)
        assertEquals(13_105.0, c.index); assertEquals(100.0, c.atr)
        assertEquals(at, c.entryTime(day))
        assertNull(Solo2.s1("NIFTY", SoloMidday.Decision("NIFTY", null, "move_too_small")))
        assertEquals("S1|MIDCPNIFTY|1|165", c.key)
    }

    @Test fun s3AndS4AsR57BuiltThem() {
        val lev = OovArm.Levels(25_000.0, 25_100.0, 24_900.0)
        val s3 = Solo2.s3("NIFTY", session({ 25_150.0 }, 0..4), lev)!!
        assertEquals(5, s3.entryRow); assertEquals(1, s3.side); assertEquals(listOf(25_100), s3.strikes)
        assertNull(Solo2.s3("NIFTY", session({ 25_000.0 }, 0..4), lev))
        // S4: the 09:15-09:29 range is 24,998-25,002; the bar 09:40-09:44 closes at 25,010: the call at 09:45.
        val d = session({ if (it < 25) 25_000.0 else 25_010.0 }, 0..29)
        val s4 = Solo2.s4("NIFTY", d)!!
        assertEquals(Solo2.Setup.S4, s4.setup); assertEquals(1, s4.side); assertEquals(30, s4.entryRow)
        assertEquals(listOf(24_950), s4.strikes)
        // Not broken yet, no range, the bar not complete: none. Down: the put.
        assertNull(Solo2.s4("NIFTY", session({ 25_000.0 }, 0..100)))
        assertNull(Solo2.s4("NIFTY", session({ 25_000.0 }, 20..100)))
        assertNull(Solo2.s4("NIFTY", session({ if (it < 25) 25_000.0 else 25_010.0 }, 0..28)))
        assertEquals(-1, Solo2.s4("NIFTY", session({ if (it < 25) 25_000.0 else 24_980.0 }, 0..40))!!.side)
        // A bar with no close is skipped; the first break after it counts. Breaks after 13:59 do not.
        assertEquals(35, Solo2.s4("NIFTY", session({ if (it < 30) 25_000.0 else 25_010.0 }, (0 until 15) + (30..40)))!!.entryRow)
        assertNull(Solo2.s4("NIFTY", session({ if (it < 285) 25_000.0 else 25_050.0 }, 0..300)))
    }

    @Test fun s2IsEveryDonchianBreakAsThePlainRuleWouldHaveTakenIt() {
        val b = { k: Int -> PassedArms.Bar5(day, k, 1.0, 1.0, 1.0, 1.0) }
        val sig = listOf(DonchianArm.Signal("NIFTY", 1, b(0), 25_000), DonchianArm.Signal("NIFTY", 1, b(2), 25_000),
            DonchianArm.Signal("NIFTY", 1, b(6), 25_000), DonchianArm.Signal("NIFTY", 1, b(20), 25_000), DonchianArm.Signal("NIFTY", -1, b(3), 25_100),
            DonchianArm.Signal("BANKNIFTY", 1, b(1), 53_000))
        val c = Solo2.s2(sig)
        assertTrue(c.all { it.setup == Solo2.Setup.S2 })
        val taken = Solo2.s2Taken(c)
        // NIFTY calls: 09:20 taken; 09:30 within its 30 minutes: not; 09:50 taken (the second); 11:00 would be a third: not.
        // The NIFTY put (09:35) and BANKNIFTY (09:25) stand alone.
        assertEquals(listOf(Triple("NIFTY", 5, 1), Triple("BANKNIFTY", 10, 1), Triple("NIFTY", 20, -1), Triple("NIFTY", 35, 1)),
            taken.map { Triple(it.underlying, it.entryRow, it.side) })
        assertEquals(4, taken.size)
    }

    @Test fun theFirstCandidateAtOrAboveTheThresholdIsTakenThenNothingMore() {
        val c = { row: Int, u: String -> Solo2.Candidate(u, Solo2.Setup.S2, 1, row, listOf(1)) }
        val thr = model.threshold
        val scored = listOf(Solo2.Scored(c(30, "NIFTY"), thr - 0.01), Solo2.Scored(c(40, "NIFTY"), thr + 0.02),
            Solo2.Scored(c(40, "BANKNIFTY"), thr + 0.05), Solo2.Scored(c(5, "FINNIFTY"), Double.NaN), Solo2.Scored(c(60, "NIFTY"), 0.99))
        // At 09:55 two pass: the higher score first.
        assertEquals("BANKNIFTY", Solo2.pick(scored, model)!!.c.underlying)
        assertNull(Solo2.pick(scored.take(1), model))
        assertNull(Solo2.pick(emptyList(), model))
        // Exactly at the threshold: taken.
        assertEquals(30, Solo2.pick(listOf(Solo2.Scored(c(30, "NIFTY"), thr)), model)!!.c.entryRow)
    }

    @Test fun eachSetupKeepsItsOwnExits() {
        assertEquals(SoloMidday.EXIT, Solo2.exits(Solo2.Setup.S1).squareOff)
        assertNull(Solo2.exits(Solo2.Setup.S1).stop)
        assertEquals(DonchianArm.EXITS, Solo2.exits(Solo2.Setup.S2))
        assertEquals(PassedArms.PremiumExits(0.30, null, 60, listOf(0.40 to 0.10), LocalTime.of(15, 10)), Solo2.exits(Solo2.Setup.S3))
        assertEquals(PassedArms.PremiumExits(0.25, 0.50, 60, listOf(0.40 to 0.10), LocalTime.of(15, 10)), Solo2.exits(Solo2.Setup.S4))
        assertEquals(listOf(4, 1, 1, 1), Solo2.Setup.entries.map { it.money })
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), Solo2.UNDERLYINGS)
        assertTrue(Solo2.ARM.paperOnly)
        for (w in listOf("paper only, forever", "0.4871", "nothing more that day", "expiry day")) assertTrue(w in Solo2.RULES, w)
        assertTrue(Solo2.RETRAIN_TODO.contains("offline retrain"))
        assertTrue(Solo2.bar().contains("60 trades") && Solo2.bar().contains("₹25,000"))
        val why = Solo2.why(Solo2.Candidate("NIFTY", Solo2.Setup.S3, -1, 5, listOf(1)), 0.51234, model)
        assertEquals("NIFTY put from S3 (open outside value (09:20)): score 0.5123, at or above 0.4871", why)
    }

    @Test fun sessionsToExpiryCountTradingDays() {
        val mon = LocalDate.of(2026, 10, 19)
        val td = { d: LocalDate -> d.dayOfWeek.value <= 5 && d != LocalDate.of(2026, 10, 21) }
        // Monday to Thursday with Wednesday a holiday: 2 sessions.
        assertEquals(2.0, Solo2.dte(mon, listOf(mon.plusDays(3), mon.plusDays(10)), td))
        assertEquals(0.0, Solo2.dte(mon, listOf(mon), td))
        assertTrue(Solo2.dte(mon, listOf(mon.minusDays(1)), td).isNaN())
        assertTrue(Solo2.dte(mon, emptyList(), td).isNaN())
    }

    @Test fun theChainContextAsR57ReadIt() {
        val om = com.optionslab.engine.options.OptionMath
        val ce = com.optionslab.engine.options.OptionType.CE; val pe = com.optionslab.engine.options.OptionType.PE
        val spot = 25_010.0; val yrs = 3.0 / 365
        // Calls priced at 14% vol, puts at 16% (+1 point a step further out for the puts): strikes 24,700 .. 25,300.
        val legs = (-6..6).flatMap { k ->
            val strike = 25_000.0 + 50 * k
            listOf(Solo2.Leg(strike, true, om.price(ce, spot, strike, yrs, 0.0, 0.14), 1_000L),
                Solo2.Leg(strike, false, om.price(pe, spot, strike, yrs, 0.0, 0.16 + 0.01 * maxOf(0, -k)), if (kotlin.math.abs(k) <= 5) 1_500L else 99_999L))
        }
        val c = Solo2.chainContext(spot, 50, legs, yrs)
        assertEquals(1.5, c.pcr5, 1e-12)                                // ATM-5..+5 only: 11 x 1,500 / 11 x 1,000
        assertEquals(15.0, c.ivAtm, 1e-4)
        assertEquals(19.0 - 14.0, c.ivSkew, 1e-4)
        // Missing parts are missing: no OI on one side, no price, no time left, no legs at all.
        val noPut = Solo2.chainContext(spot, 50, legs.filter { it.call }, yrs)
        assertTrue(noPut.pcr5.isNaN() && noPut.ivAtm.isNaN() && noPut.ivSkew.isNaN())
        assertTrue(Solo2.chainContext(spot, 50, legs.map { it.copy(ltp = null) }, yrs).ivAtm.isNaN())
        assertTrue(Solo2.chainContext(spot, 50, legs, 0.0).ivAtm.isNaN())
        assertTrue(Solo2.chainContext(spot, 50, legs.map { it.copy(ltp = 0.0) }, yrs).ivSkew.isNaN())
        assertTrue(Solo2.chainContext(spot, 50, emptyList(), yrs).pcr5.isNaN())
        assertTrue(Solo2.chainContext(0.0, 50, legs, yrs).pcr5.isNaN())
        assertTrue(Solo2.chainContext(spot, 50, legs.map { it.copy(ltp = 1e9) }, yrs).ivAtm.isNaN())
    }

    @Test fun aCandidatesEntryMinute() {
        val c = Solo2.Candidate("NIFTY", Solo2.Setup.S4, 1, 30, listOf(1))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 45), c.entryTime(day))
        assertNotNull(Solo2.LAYER)
    }

    // ---- why it took or skipped (15 Oct 2026: explanation only) --------------------------------------------------------

    /** The model's average for every input (each push 0), with one-hots set as a NIFTY S3 call. */
    private fun averageCandidate(): DoubleArray = model.mean.copyOf().also { v ->
        for (i in 20 until 29) v[i] = 0.0
        v[Solo2.FEATURES.indexOf("idx_NIFTY")] = 1.0; v[Solo2.FEATURES.indexOf("side")] = 1.0; v[Solo2.FEATURES.indexOf("setup_S3")] = 1.0
    }

    @Test fun thePushesAreTheLogitsOwnTerms() {
        val g = res("/solo2/A2_golden.json")
        @Suppress("UNCHECKED_CAST") val rows = g["rows"] as List<Map<String, Any?>>
        for (row in rows) {
            val x = arr(row["inputs"])
            val z = model.intercept + Solo2.pushes(x, model).sum()
            assertEquals(model.score(x), Solo2.sigmoid(z), 1e-12)
        }
        assertFailsWith<IllegalArgumentException> { Solo2.pushes(DoubleArray(3), model) }
    }

    @Test fun theReasonNamesWhatRaisedAndLoweredTheScore() {
        val v = averageCandidate()
        v[Solo2.FEATURES.indexOf("vix")] = 25.0 // VIX is A2's biggest positive weight
        v[Solo2.FEATURES.indexOf("iv_atm")] = 30.0 // ATM IV its biggest negative one
        val r = Solo2.reason(v, model)
        assertTrue(r.startsWith("raised by India VIX high"), r)
        assertTrue("lowered by options dear (ATM IV high)" in r, r)
        // The opposite readings give the opposite words.
        v[Solo2.FEATURES.indexOf("vix")] = 9.0; v[Solo2.FEATURES.indexOf("iv_atm")] = 9.0
        val r2 = Solo2.reason(v, model)
        assertTrue("raised by options cheap (ATM IV low)" in r2, r2)
        assertTrue("lowered by India VIX low" in r2, r2)
        // At most k on each side.
        assertEquals(1, Solo2.reason(v, model, k = 1).substringAfter("raised by ").substringBefore(", lowered").split(" and ").size)
    }

    @Test fun theReasonSkipsMissingInputsAndOneHotsThatAreOff() {
        val v = DoubleArray(29) { Double.NaN }
        // Everything missing (each at its median) and one-hots off: nothing to say about this candidate but its side.
        assertTrue(Solo2.reason(v, model).let { it == "" || "being a call" in it || "being a put" in it })
        v[Solo2.FEATURES.indexOf("side")] = -1.0
        assertEquals("lowered by being a put", Solo2.reason(v, model))
        v[Solo2.FEATURES.indexOf("setup_S4")] = 1.0 // S4 has a negative weight
        v[Solo2.FEATURES.indexOf("idx_MIDCPNIFTY")] = 1.0 // MIDCPNIFTY a positive one
        val r = Solo2.reason(v, model)
        assertTrue("raised by the index (MIDCPNIFTY)" in r && "the setup (S4)" in r, r)
        assertFalse("NaN" in r)
    }

    @Test fun noReasonWithoutInputs() {
        assertEquals("", Solo2.reason(null, model))
        assertEquals("", Solo2.reason(DoubleArray(5), model))
        assertEquals("", Solo2.reason(averageCandidate(), model, k = 0))
    }

    @Test fun everyInputHasBothWords() {
        val v = averageCandidate()
        for (i in 0 until 20) {
            v[i] = model.mean[i] + model.scale[i]
            val hi = Solo2.reason(v, model, k = 29)
            v[i] = model.mean[i] - model.scale[i]
            val lo = Solo2.reason(v, model, k = 29)
            v[i] = model.mean[i]
            assertTrue(hi != lo, "input ${Solo2.FEATURES[i]} reads the same both ways")
        }
        assertTrue("traps" in Solo2.CONTEXT_NOTE && "not part of any decision" in Solo2.CONTEXT_NOTE)
    }
}
