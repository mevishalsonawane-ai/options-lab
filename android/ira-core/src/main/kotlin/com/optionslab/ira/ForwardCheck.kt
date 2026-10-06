package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Live vs backtest: does a running strategy behave like its research said? Each strategy's closed FORWARD trades (paper,
 * from the day its current rules started, rupees after charges PER LOT) are set against its research expectation - the
 * TEST period of the study that chose it (never the period it was fitted on): its rupees a trade, their spread, its win
 * rate and its worst drawdown.
 *
 *   band     the mean a trade the research expects after n trades: mean ± 2·sd/√n (about 95% of honest n-trade averages
 *            fall inside it when the strategy is what the research says)
 *   verdict  fewer than [MIN_TRADES] trades: "too few trades (<20)"; else the forward average a trade inside the band is
 *            "in line", under it "below expectation (n trades)", over it "above expectation". A lottery (Hero: a few huge
 *            winners make all of its mean) is judged by drawdown, not mean: a forward drawdown worse than the research's
 *            worst is "below expectation", otherwise "in line" (or "too few trades" under [MIN_TRADES]).
 *   early    a one-sided CUSUM (Page) that flags a SUSTAINED run below the research before the band can - and overrides
 *            "too few trades" and "in line" with "below expectation":
 *              z_i = clamp((x_i - mean) / sd, -[CUSUM_CLIP], +[CUSUM_CLIP])     (each trade in research spreads; one
 *                                                                                outsized loss counts as 3 at most)
 *              S_0 = 0,  S_i = max(0, S_(i-1) - z_i - [CUSUM_K])                 (grows only while trades keep coming
 *                                                                                in more than half a spread below)
 *              alarm when S_i >= [CUSUM_H]
 *            k = 0.5 and h = 5 were picked on the research's own TEST trades (bootstrap, 2000 runs of 200 trades): with the
 *            strategy exactly as researched, a false alarm within 100 trades in 0.05% of runs (Liquidity) and 2.6% (Solo,
 *            more skewed); with the strategy half a spread a trade worse (Liquidity about -₹800 a trade below, Solo about
 *            -₹1,550), the alarm comes after a median of 21 (Liquidity) and 25 (Solo) trades. It cannot see a slide to "no
 *            edge" (mean 0): that is about a tenth of a spread a trade and takes hundreds of trades for any test - the band
 *            says it in time. Not run for a lottery (its z is skewed by design).
 *
 * The expectations are pinned below with the file each number came from (the research folders of 06 Oct 2026). Pure: no
 * clock, no storage.
 */
object ForwardCheck {
    const val MIN_TRADES = 20
    const val CUSUM_K = 0.5
    const val CUSUM_H = 5.0
    const val CUSUM_CLIP = 3.0

    /** One closed forward trade: the day it was entered and its net rupees after charges, per lot. */
    data class Trade(val day: LocalDate, val net: Double)

    /**
     * A strategy's research expectation on its TEST period: [trades] and [net] there, [mean] / [sd] rupees a trade (per
     * [unit]), [winRate] 0..1, [profitFactor], [maxDrawdown] (negative), [source] the report it is pinned from. [since]:
     * the day its current rules started running (forward trades before it are another strategy; null: every trade).
     * [lottery]: judged by drawdown, not mean.
     */
    data class Expectation(
        val key: String, val name: String, val trades: Int, val net: Double, val mean: Double, val sd: Double, val winRate: Double,
        val profitFactor: Double?, val maxDrawdown: Double, val source: String, val unit: String = "per lot", val since: LocalDate? = null,
        val lottery: Boolean = false,
    )

    enum class Verdict { TOO_FEW, IN_LINE, BELOW, ABOVE }

    /** The expected mean a trade after [n] trades: [low] .. [high]; [half] = 2·sd/√n. */
    data class Band(val low: Double, val high: Double, val half: Double)

    /**
     * The check: [trades] forward, their [net], [perTrade] (null: none), [winRate] (null: none), [drawdown] (0 or negative),
     * [band] at n (null: none), [verdict], the CUSUM's last value [cusum] and the trade it first alarmed at ([alarmAt],
     * 1-based; null: never), [cumulative] the running net after each trade (0 first).
     */
    data class Result(
        val expectation: Expectation, val trades: Int, val net: Double, val perTrade: Double?, val winRate: Double?, val drawdown: Double,
        val band: Band?, val verdict: Verdict, val cusum: Double, val alarmAt: Int?, val cumulative: List<Double>,
    ) {
        val alarm: Boolean get() = alarmAt != null
    }

    private const val LOSERS = "scratchpad/losers/out"
    private val SINCE_06_OCT: LocalDate = LocalDate.of(2026, 10, 6)

    /**
     * Liquidity 15+5 under its new rules (skip a break without one index stop of room, one strike in the money), 1 lot.
     * losers/out/finalists_test.csv row "liq,base" (TEST 2024-07-02..2026-10-05): 916 trades, net +₹2,08,536, win 40.8%,
     * PF 1.49, max DD −₹29,726; sd of the 916 trades' net from losers/out/trades_all.csv.gz (variant base, arm liquidity*,
     * day >= 2024-07-01): ₹2,064.33. Since 06 Oct 2026 (the day the new rules went on, LiquidityShadow.SINCE).
     */
    val LIQUIDITY = Expectation("liquidity", "Liquidity 15+5", 916, 208_536.03, 227.66, 2_064.33, 0.4083, 1.49, -29_726.34,
        "$LOSERS/finalists_test.csv (liq, base: TEST Jul 2024 - Oct 2026)", since = SINCE_06_OCT)

    /**
     * Solo, midday and risk-reduced, 1 lot (Solo buys one lot). solo2/out/riskreduced_trades.csv, rows with day >= 2024-07-01
     * (TEST): 196 trades, net +₹31,330, ₹159.85 a trade, sd ₹3,107.56, win 50.0%, PF 1.14, max DD −₹24,589 (trade order).
     * No start day pinned: every closed Solo paper trade counts until the risk-reduced rules' day is set here.
     */
    val SOLO = Expectation("solo", "Solo", 196, 31_330.0, 159.85, 3_107.56, 0.50, 1.14, -24_589.30,
        "scratchpad/solo2/out/riskreduced_trades.csv (TEST rows, Jul 2024 - Oct 2026)")

    /**
     * Hero (expiry) with the deep study's F07 exits, one ₹5,000 ticket a day (the arm's own budget). hero_deep/out/
     * finalists_fit_test.csv row "F07,TEST": 49 trades, 9 won (18.4%), net +₹1,26,313 (₹2,577.82 a trade), PF 2.02, max DD
     * −₹53,035; sd ₹15,395.35 from hero_deep/out/v2_trades.parquet (vid F07, day >= 2024-07-01, one entry a day x index).
     * A LOTTERY: −₹42,999 without its 3 best days (hero_deep/REPORT.md) - judged by drawdown, not mean. Since 06 Oct 2026
     * (the F07 exits went on that day).
     */
    val HERO = Expectation("hero", "Hero (expiry)", 49, 126_313.34, 2_577.82, 15_395.35, 0.1837, 2.02, -53_035.0,
        "scratchpad/hero_deep/out/finalists_fit_test.csv (F07, TEST Jul 2024 - Oct 2026)", unit = "per ₹5,000 ticket",
        since = SINCE_06_OCT, lottery = true)

    /**
     * The shadows (ShadowRules / ShadowStudy variant ids; 1 lot, no orders), each on its research's TEST from 2024-07-01. None
     * made money there: "in line" means losing as researched; "above expectation" is the fix holding up live. From
     * losers/out/finalists_test.csv (trades, net, PF, DD) with the sd from trades_all.csv.gz + trades_extra.csv.gz (variant
     * c_max1_pull10_rvge20 / c_max1_hold, day >= 2024-07-01), arms/orb/out/
     * test_trades/V43.csv, arms/fade/out/trades/S17_oi_struct_mid_TEST.csv and arms/momo/out/test_trades/O08_ALL.csv (u NIFTY).
     * R20 and S14 have no TEST trade file: no expectation (no card).
     */
    val SHADOWS: Map<String, Expectation> = listOf(
        Expectation("orb_p10", "ORB · OP10", 393, -39_266.88, -99.92, 532.85, 0.2952, 0.58, -39_357.79, "$LOSERS/trades_extra.csv.gz (orb, c_max1_pull10_rvge20)"),
        Expectation("orbf_p10", "ORB Fresh · FP10", 314, -36_164.08, -115.17, 506.47, 0.2707, 0.52, -36_164.08, "$LOSERS/trades_extra.csv.gz (orb_fresh, c_max1_pull10_rvge20)"),
        Expectation("fade_p10", "Range Fade · RP10", 327, -50_584.05, -154.69, 533.36, 0.2599, 0.42, -51_081.92, "$LOSERS/trades_extra.csv.gz (range_fade, c_max1_pull10_rvge20)"),
        Expectation("sweep_h1", "ORB Sweep · SH1", 456, -57_912.68, -127.00, 2_635.21, 0.2215, 0.85, -88_626.68, "$LOSERS/trades_extra.csv.gz (orb_sweep, c_max1_hold)"),
        Expectation("orb_v43", "ORB / ORB Fresh · V43", 236, -114_138.0, -483.64, 1_811.29, 0.161, 0.50, -114_146.0, "scratchpad/arms/orb/out/test_trades/V43.csv"),
        Expectation("sweep_s17", "ORB Sweep · S17", 91, -6_314.0, -69.39, 1_157.72, 0.2527, 0.85, -15_158.0, "scratchpad/arms/fade/out/trades/S17_oi_struct_mid_TEST.csv"),
        Expectation("momo_o08", "Midday momentum · O08", 113, 34_463.0, 304.98, 4_423.92, 0.5044, 1.21, -26_876.0, "scratchpad/arms/momo/out/test_trades/O08_ALL.csv (NIFTY)"),
    ).associateBy { it.key }

    /** The expected mean a trade after [n] trades (null for none). */
    fun band(e: Expectation, n: Int): Band? {
        if (n <= 0) return null
        val half = 2 * e.sd / sqrt(n.toDouble())
        return Band(e.mean - half, e.mean + half, half)
    }

    /** The CUSUM's value after each trade of [nets] (first value after the first trade). */
    fun cusum(e: Expectation, nets: List<Double>): List<Double> {
        var s = 0.0
        return nets.map { x ->
            val z = if (e.sd > 0) ((x - e.mean) / e.sd).coerceIn(-CUSUM_CLIP, CUSUM_CLIP) else 0.0
            s = max(0.0, s - z - CUSUM_K)
            s
        }
    }

    /** The deepest fall of [nets]' running total from its best so far (counting from 0): 0 or negative. */
    fun drawdown(nets: List<Double>): Double {
        var run = 0.0; var peak = 0.0; var dd = 0.0
        nets.forEach { run += it; peak = max(peak, run); dd = min(dd, run - peak) }
        return dd
    }

    /** The check of [trades] (any order: taken by day, ties as given) against [e]; trades before [Expectation.since] are left out. */
    fun check(e: Expectation, trades: List<Trade>): Result {
        val mine = trades.filter { t -> e.since?.let { !t.day.isBefore(it) } ?: true }.sortedBy { it.day }.map { it.net }
        val n = mine.size
        val net = mine.sum()
        val per = if (n == 0) null else net / n
        val dd = drawdown(mine)
        val b = band(e, n)
        val path = if (e.lottery) emptyList() else cusum(e, mine)
        val alarmAt = path.indexOfFirst { it >= CUSUM_H }.takeIf { it >= 0 }?.plus(1)
        val verdict = when {
            e.lottery && dd < e.maxDrawdown -> Verdict.BELOW
            alarmAt != null -> Verdict.BELOW
            n < MIN_TRADES -> Verdict.TOO_FEW
            e.lottery -> Verdict.IN_LINE
            per!! < b!!.low -> Verdict.BELOW
            per > b.high -> Verdict.ABOVE
            else -> Verdict.IN_LINE
        }
        val cum = ArrayList<Double>(n + 1).apply { add(0.0); var r = 0.0; mine.forEach { r += it; add(r) } }
        return Result(e, n, net, per, if (n == 0) null else mine.count { it > 0 }.toDouble() / n, dd, b, verdict,
            path.lastOrNull() ?: 0.0, alarmAt, cum)
    }

    /** The verdict in the words the card and Jarvis use. */
    fun words(r: Result): String = when (r.verdict) {
        Verdict.TOO_FEW -> "too few trades (<$MIN_TRADES)"
        Verdict.IN_LINE -> "in line"
        Verdict.BELOW -> "below expectation (${r.trades} trade${if (r.trades == 1) "" else "s"})"
        Verdict.ABOVE -> "above expectation"
    }

    internal fun rs(x: Double): String = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(round(x)))

    /**
     * Jarvis's one line: "Live vs backtest: in line (₹180/trade vs expected ₹228 ± ₹923)." A lottery says its drawdown
     * against the research's instead; with no forward trade, what is expected.
     */
    fun line(r: Result): String {
        val e = r.expectation
        val per = if (e.unit == "per lot") "/trade" else "/trade ${e.unit}"
        if (r.trades == 0) return "Live vs backtest: no forward trades yet (expected ${rs(e.mean)}$per)."
        val why = when {
            e.lottery -> "drawdown ${rs(r.drawdown)} vs the backtest's worst ${rs(e.maxDrawdown)}; a lottery: judged by drawdown, not mean"
            else -> "${rs(r.perTrade!!)}$per vs expected ${rs(e.mean)} ± ${rs(r.band!!.half)}"
        }
        val early = if (r.alarm && !e.lottery) "; a sustained run below the backtest since trade ${r.alarmAt}" else ""
        return "Live vs backtest: ${words(r)} ($why$early)."
    }

    /** The card's plain-words sentence: what the forward trades say against the research, in a sentence or two. */
    fun plain(r: Result): String {
        val e = r.expectation
        val unit = if (e.unit == "per lot") "a trade per lot" else "a trade (${e.unit.removePrefix("per ")})"
        val research = "The backtest made ${rs(e.mean)} $unit over ${e.trades} trades (win ${pct(e.winRate)}, worst drawdown ${rs(e.maxDrawdown)})."
        if (r.trades == 0) return "No forward trades yet. $research"
        val so = "${r.trades} forward trade${if (r.trades == 1) "" else "s"}, ${rs(r.perTrade!!)} $unit"
        val what = when {
            e.lottery && r.verdict == Verdict.BELOW -> "Its drawdown ${rs(r.drawdown)} is already worse than the backtest's worst - the research no longer describes it."
            e.lottery && r.verdict == Verdict.TOO_FEW -> "A lottery: judged by drawdown, not mean - so far within the backtest's worst, too few trades to say more."
            e.lottery -> "A lottery: judged by drawdown, not mean - still within the backtest's worst."
            r.alarm -> "It has kept coming in below the backtest since trade ${r.alarmAt}: a sustained shortfall, not just bad luck."
            r.verdict == Verdict.TOO_FEW -> "Too few to judge: one trade's luck swamps the average until $MIN_TRADES."
            r.verdict == Verdict.BELOW -> "Below what the backtest allows for ${r.trades} trades (${rs(r.band!!.low)} at least) - worse than luck explains."
            r.verdict == Verdict.ABOVE -> "Above what the backtest allows (${rs(r.band!!.high)} at most) - better than researched."
            else -> "Within what the backtest allows for ${r.trades} trades (${rs(r.band!!.low)} to ${rs(r.band.high)})."
        }
        return "$so. $what $research"
    }

    private fun pct(x: Double) = "${round(100 * x).toInt()}%"

    /**
     * The sparkline's lines after each trade i = 0..n: the expected running total mean·i and its band mean·i ± 2·sd·√i
     * (the forward line is [Result.cumulative]). A lottery's band is drawn the same way.
     */
    fun expectedPath(e: Expectation, n: Int): List<Triple<Double, Double, Double>> = (0..n).map { i ->
        val m = e.mean * i
        val w = 2 * e.sd * sqrt(i.toDouble())
        Triple(m - w, m, m + w)
    }
}
