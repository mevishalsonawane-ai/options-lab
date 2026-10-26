package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 26 Oct (R105 lead 7): on paper R51's 14 Supertrend-filtered trades lost Rs 16,188, far worse than the backtest, so the phone's
 * Supertrend (10, 3) is held to research R73's own code (research/hunt/r73/r73rules.py `supertrend_dir`, `trend_ok("T7")`), on the
 * fixture tools/supertrend_parity_fixture.py writes from it: 60 sessions of an index's 5-minute bars with a flat stretch, a gap
 * open and bars missing (a feed gap). The same Wilder ATR (an RMA seeded by the mean of the first 10 true ranges, not an SMA),
 * the same hl2 ± 3 ATR final bands and flip, on the same index 5-minute bars, judged at the signal bar's close.
 */
class SupertrendParityTest {
    @Suppress("UNCHECKED_CAST")
    private val fx: Map<String, Any?> by lazy {
        MiniJson.parse(javaClass.getResourceAsStream("/passedarms/supertrend_parity.json")!!.bufferedReader().readText()) as Map<String, Any?>
    }
    private val d0 = LocalDate.of(2026, 1, 5)

    @Suppress("UNCHECKED_CAST")
    private val series: List<PassedArms.Bar5> by lazy {
        (fx["bars"] as List<List<Double>>).map { r -> PassedArms.Bar5(d0.plusDays(r[0].toLong()), r[1].toInt(), r[2], r[3], r[4], r[5]) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun doubles(k: String): List<Double> = (fx[k] as List<Double?>).map { it ?: Double.NaN }

    /** Every bar's direction, ATR and final bands are R73's, to 1e-6. */
    @Suppress("UNCHECKED_CAST")
    @Test fun theDirectionAtrAndBandsAreR73sOnEveryBar() {
        val want = (fx["dir"] as List<Double>).map { it.toInt() }
        val got = DonchianArm.supertrendLines(series)
        assertEquals(want.size, series.size)
        assertEquals(want, got.dir.toList())
        for ((k, line) in listOf("atr" to got.atr, "up" to got.up, "dn" to got.dn)) {
            val w = doubles(k)
            for (i in series.indices) {
                if (w[i].isNaN()) assertTrue(line[i].isNaN(), "$k[$i]: the research has none")
                else assertEquals(w[i], line[i], 1e-6, "$k[$i]")
            }
        }
        // The fixture is worth its name: Supertrend flips often, both ways.
        assertTrue(got.dir.toList().zipWithNext().count { (a, b) -> a != 0 && b != 0 && a != b } >= 20)
        // The ATR is Wilder's (an RMA), not a simple mean of the last 10: they differ on this series.
        val tr = series.indices.map { i -> if (i == 0) series[0].high - series[0].low else maxOf(series[i].high - series[i].low,
            kotlin.math.abs(series[i].high - series[i - 1].close), kotlin.math.abs(series[i].low - series[i - 1].close)) }
        val sma = tr.subList(series.size - 10, series.size).average()
        assertTrue(kotlin.math.abs(sma - got.atr.last()) > 1e-3, "an SMA would not match")
    }

    /** Every Donchian break in the entry window is the research's, and the filter takes exactly the ones R73's T7 takes. */
    @Suppress("UNCHECKED_CAST")
    @Test fun theFilterDecisionsAreR73sT7AtTheSignalBarsClose() {
        val breaks = (fx["breaks"] as List<List<Any?>>).map { Triple((it[0] as Double).toInt(), (it[1] as Double).toInt(), it[2] as Boolean) }
        assertTrue(breaks.count { !it.third } >= 10, "breaks Supertrend is against")
        assertTrue(breaks.count { it.third } >= 100, "breaks it agrees with")
        val days = series.map { it.date }.distinct()
        val sigs = days.flatMap { d -> DonchianArm.signals("NIFTY", series, d) }
        assertEquals(breaks.map { series[it.first] to it.second }, sigs.map { it.bar to it.side }, "the Donchian breaks themselves")
        for ((i, s, t7) in breaks) {
            val sig = sigs.single { it.bar == series[i] }
            assertEquals(s, sig.side)
            // The first session has no earlier one: the phone does not judge it (null, not taken: [DonchianArm.ST_MIN_SESSIONS]),
            // on purpose, where the research's series ran on from before. Every later break: the research's verdict.
            if (series[i].date == series.first().date) { assertEquals(null, DonchianArm.supertrendAgrees(series, sig)); continue }
            assertEquals(t7, DonchianArm.supertrendAgrees(series, sig) == true, "break at bar $i")
        }
    }

    /**
     * The warm-up: the phone runs Supertrend on the sessions it holds (about 11 earlier ones, its 16-day prefetch), the research on
     * one series since Nov 2024. On every break from the 12th session on, 11 earlier sessions give the research's verdict.
     */
    @Test fun elevenEarlierSessionsGiveTheResearchsVerdictOnEveryBreak() {
        val days = series.map { it.date }.distinct()
        var compared = 0
        for ((k, d) in days.withIndex()) {
            if (k < 11) continue
            val held = days.subList(k - 11, k + 1).toSet()
            val short = series.filter { it.date in held }
            for (sig in DonchianArm.signals("NIFTY", series, d)) {
                assertEquals(DonchianArm.supertrendAgrees(series, sig) == true, DonchianArm.supertrendAgrees(short, sig) == true, "$d bar ${sig.bar.b}")
                compared++
            }
        }
        assertTrue(compared >= 400, "breaks compared: $compared")
        // With only today's bars (no earlier session) nothing is judged: not taken.
        val d = days.last()
        val today = series.filter { it.date == d }
        DonchianArm.signals("NIFTY", series, d).forEach { assertEquals(null, DonchianArm.supertrendAgrees(today, it)) }
    }

    /** No look-ahead: bars after the signal bar (here scrambled) never change its verdict. */
    @Test fun nothingAfterTheSignalBarIsRead() {
        val rnd = java.util.Random(7)
        val d = series.map { it.date }.distinct()[30]
        for (sig in DonchianArm.signals("NIFTY", series, d)) {
            val i = series.indexOf(sig.bar)
            val later = series.subList(i + 1, series.size).map { it.copy(high = it.high * (1 + rnd.nextDouble() / 50), low = it.low * (1 - rnd.nextDouble() / 50),
                close = it.close * (1 + (rnd.nextDouble() - 0.5) / 25)) }
            assertEquals(DonchianArm.supertrendAgrees(series, sig), DonchianArm.supertrendAgrees(series.subList(0, i + 1) + later, sig))
        }
    }
}
