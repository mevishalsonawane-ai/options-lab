package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * The day-10 go-live checklist for Liquidity BANKNIFTY (Boss, 12 Oct; the strategy scorecard's go-live criteria), worked out
 * from the app's own records: each line ✓, ✗ or — (not known yet) with its number. It only INFORMS: nothing here (or where it
 * is shown) switches anything to Live - going live stays Boss's step, in Live with his PIN. Pure.
 */
object LiquidityGoLive {
    const val MIN_TRADES = 6
    const val MIN_MATCH = 0.90
    const val MAX_ENTRY_S = 10.0
    const val MAX_FILL_PCT = 0.5
    const val MAX_HALF_SPREAD_PCT = 0.3
    /** A paper entry and the replay's agree when the entry came within this of the signal bar's close. */
    const val MATCH_WINDOW_S = 60L

    enum class Mark(val sign: String) { OK("✓"), NO("✗"), UNKNOWN("—") }

    data class Line(val mark: Mark, val text: String) {
        override fun toString(): String = "${mark.sign} $text"
    }

    /** One signal of the evening replay: its [book], the signal [bar] (its start), the side (+1 call) and the strike bought. */
    data class ReplaySignal(val book: String, val bar: LocalDateTime, val side: Int, val strike: Int)

    /** One paper entry of a BANKNIFTY book: its signal bar, side, strike (null: unknown), when it filled and closed; [minutes] the book's bar. */
    data class PaperEntry(val book: String, val signalBar: LocalDateTime, val side: Int, val strike: Int?, val entryTime: LocalDateTime,
                          val exitTime: LocalDateTime?, val minutes: Int)

    /** How the paper entries compare with the evening replay: [matched] of [compared]; [missed] replay signals taken while flat. */
    data class Fidelity(val matched: Int, val compared: Int, val missed: Int, val days: Int) {
        val share: Double? get() = if (compared == 0) null else matched.toDouble() / compared
    }

    /**
     * Each paper entry on a replayed day matches when the replay has the same book, signal bar, side and strike and the entry
     * filled within [MATCH_WINDOW_S] of the bar's close; each replay signal the paper book did not take while it was flat
     * counts as a miss. Days with no replay are left out.
     */
    fun fidelity(paper: List<PaperEntry>, replay: Map<LocalDate, List<ReplaySignal>>): Fidelity {
        var matched = 0; var compared = 0; var missed = 0
        for ((day, sigs) in replay) {
            val mine = paper.filter { it.signalBar.toLocalDate() == day }
            for (p in mine) {
                compared++
                val close = p.signalBar.plusMinutes(p.minutes.toLong())
                val ok = sigs.any { it.book == p.book && it.bar == p.signalBar && it.side == p.side && (p.strike == null || it.strike == p.strike) } &&
                    abs(java.time.Duration.between(close, p.entryTime).seconds) <= MATCH_WINDOW_S
                if (ok) matched++
            }
            for (s in sigs) {
                if (mine.any { it.book == s.book && it.signalBar == s.bar }) continue
                val tf = com.optionslab.engine.orb.LiquidityRules.BOOKS.firstOrNull { it.source == s.book }
                    ?.let { com.optionslab.engine.orb.LiquidityRules.minutesOf(it) } ?: 0
                val at = s.bar.plusMinutes(tf.toLong())
                // Holding a position of that book then: the replay's signal was not the app's to take.
                val holding = paper.any { it.book == s.book && !it.entryTime.isAfter(at) && (it.exitTime == null || it.exitTime.isAfter(at)) }
                if (!holding) { missed++; compared++ }
            }
        }
        return Fidelity(matched, compared, missed, replay.size)
    }

    data class Inputs(
        /** BANKNIFTY's closed paper trades, per lot. */
        val trades: List<Vetting.Trade>,
        val today: LocalDate,
        val fidelity: Fidelity?,
        /** Each paper entry's seconds after its bar's close. */
        val entryLagsS: List<Double>,
        val fills: FillQuality.Stats?,
        /** Serious order-review flaws on Liquidity's trades since the start (null: the review has not run). */
        val seriousFlaws: Int?,
        /** Protection or guard misfires on Liquidity's positions since the start (null: not known). */
        val misfires: Int?,
        /** Static IP registered and the phone's IP reached Zerodha's registered one (null: not checked yet). */
        val staticIp: Boolean?,
        val staticIpWhy: String? = null,
        /** Boss's own tick: he watched the first live trade. */
        val watched: Boolean = false,
    )

    private fun pct(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)

    fun lines(i: Inputs): List<Line> {
        val out = ArrayList<Line>()
        val bn = LiquiditySplit.name("BANKNIFTY")
        val v = Vetting.judge(bn, i.trades, i.today)
        out += Line(if (v.trades >= MIN_TRADES) Mark.OK else Mark.NO, "At least $MIN_TRADES trades since ${Vetting.START.dayOfMonth} Oct: ${v.trades}")
        val f = i.fidelity
        out += when {
            f == null || f.compared == 0 -> Line(Mark.UNKNOWN, "At least 90% match the evening replay: no replayed trade yet")
            else -> Line(if (f.share!! >= MIN_MATCH) Mark.OK else Mark.NO,
                "At least 90% match the evening replay: ${f.matched} of ${f.compared} (${(f.share!! * 100).toInt()}%)" +
                    if (f.missed > 0) "; ${f.missed} replay signal${if (f.missed == 1) "" else "s"} not taken" else "")
        }
        val lag = FillQuality.median(i.entryLagsS)
        out += if (lag == null) Line(Mark.UNKNOWN, "Entry within 10 s of the bar close: no entry yet")
            else Line(if (lag <= MAX_ENTRY_S) Mark.OK else Mark.NO, "Entry within 10 s of the bar close: median %.1f s (worst %.1f s)".format(Locale.ENGLISH, lag, i.entryLagsS.max()))
        val s = i.fills
        out += if (s?.fillVsSignal == null) Line(Mark.UNKNOWN, "Fill at most 0.5% above the signal: not measured yet")
            else Line(if (s.fillVsSignal <= MAX_FILL_PCT) Mark.OK else Mark.NO, "Fill at most 0.5%% above the signal: median %+.2f%% (worst %+.2f%%)".format(Locale.ENGLISH, s.fillVsSignal, s.worstFillVsSignal))
        out += if (s?.halfSpread == null) Line(Mark.UNKNOWN, "Half-spread at the signal at most 0.3%: not measured yet")
            else Line(if (s.halfSpread <= MAX_HALF_SPREAD_PCT) Mark.OK else Mark.NO, "Half-spread at the signal at most 0.3%: median ${pct(s.halfSpread)} (worst ${pct(s.worstHalfSpread!!)})")
        out += when {
            v.trades == 0 || v.band == null -> Line(Mark.UNKNOWN, "Net not worse than the backtest's normal range: no trades yet")
            v.net >= v.band.low -> Line(Mark.OK, "Net not worse than the backtest's normal range: ${Vetting.rs(v.net)} (range from ${Vetting.rs(v.band.low)} for ${v.trades} trades)")
            else -> Line(Mark.NO, "Net not worse than the backtest's normal range: ${Vetting.rs(v.net)}, below ${Vetting.rs(v.band.low)} for ${v.trades} trades")
        }
        out += when (val n = i.seriousFlaws) {
            null -> Line(Mark.UNKNOWN, "No serious order-review flaw on its trades: the review has not run yet")
            0 -> Line(Mark.OK, "No serious order-review flaw on its trades: none")
            else -> Line(Mark.NO, "No serious order-review flaw on its trades: $n")
        }
        out += when (val n = i.misfires) {
            null -> Line(Mark.UNKNOWN, "No protection or guard misfire on its positions: not known yet")
            0 -> Line(Mark.OK, "No protection or guard misfire on its positions: none")
            else -> Line(Mark.NO, "No protection or guard misfire on its positions: $n")
        }
        out += when (i.staticIp) {
            null -> Line(Mark.UNKNOWN, "Static IP set and reachable: " + (i.staticIpWhy ?: "not checked yet"))
            true -> Line(Mark.OK, "Static IP set and reachable" + (i.staticIpWhy?.let { ": $it" } ?: ""))
            false -> Line(Mark.NO, "Static IP set and reachable: " + (i.staticIpWhy ?: "no"))
        }
        out += if (i.watched) Line(Mark.OK, "First live trade watched: ticked by you")
            else Line(Mark.UNKNOWN, "First live trade watched: your tick, after you watch it with the app open")
        return out
    }

    /** "Go-live checklist: 4 ✓ · 1 ✗ · 5 —". */
    fun head(lines: List<Line>): String = "Go-live checklist (Liquidity BANKNIFTY): " +
        "${lines.count { it.mark == Mark.OK }} ✓ · ${lines.count { it.mark == Mark.NO }} ✗ · ${lines.count { it.mark == Mark.UNKNOWN }} —"

    private val ASKED = Regex("(?i)\\bgo[- ]?live (check ?list|checks)\\b|\\bliquidity\\b.{0,30}\\b(ready|readiness) (to|for) (go )?live\\b")

    /** "Liquidity go-live checklist", "is Liquidity ready to go live". */
    fun asked(q: String): Boolean = ASKED.containsMatchIn(q)

    /** What it means, said under the lines. */
    const val NOTE = "Information only: nothing switches to Live by itself. Going live stays your step, in Live with your PIN."
}
