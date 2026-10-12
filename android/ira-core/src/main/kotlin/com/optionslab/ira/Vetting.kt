package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * The paper test ("vetting"), research X1's change #1 (Boss's go, 12 Oct 2026). It replaces the old 15-trade rule (15 trades,
 * a profit factor of 1.2: X1 measured it calling a long-run loser "held up" 13-31% of the time and Liquidity "failed" 50-55%).
 *
 * Each arm's forward paper record is judged against ITS OWN BACKTEST: the expected net a trade per lot and the spread of
 * single trades ([Backtest], pinned with its source), as a 5-95% band for the SUM of that many trades (normal approximation:
 * n·mean ± [Z]·sd·√n - X1's bootstrap bands for 20 and 30 trades are within a few percent of it):
 *
 *  - fewer than [MIN_TRADES] trades or [MIN_DAYS] trading days since [START]: "too early (n/60, d/40)";
 *  - from [MIN_BAND_TRADES] trades on, a sum under the band: "below the backtest's range - investigate" (the app may not be
 *    trading the rules the backtest traded: check the fills and the evening replay); over it: "above the backtest's range -
 *    check the fills" (X1: ORB's +5,068 in 30 trades was lucky fills, not an edge);
 *  - at the minimums and inside the band: "passed" only when the backtest itself makes money and the record is up; an arm
 *    whose backtest loses can never pass (it is "within the backtest's normal range": losing as researched);
 *  - an arm with no backtest on the phone can never pass.
 *
 * Only trades from [START] count. Passing only informs Boss: nothing here (or anywhere it is read) switches an arm to Live,
 * arms, stops or sizes anything - going live is Boss's own step with his PIN. Pure: no clock (today is given), no storage.
 */
object Vetting {
    const val MIN_TRADES = 60
    const val MIN_DAYS = 40
    /** Below / above the band is said only from this many trades (one trade's luck swamps a smaller band). */
    const val MIN_BAND_TRADES = 10
    /** 5-95%: the band's half-width in standard deviations of the sum. */
    const val Z = 1.645

    /**
     * The paper test counts trades from 13 Oct 2026 only (Boss's 10 paper days start that Tuesday). Paper fills before the
     * bid/ask-spread fix (8 Oct) were about Rs 96 a round trip kinder than a real order book (research X3), and the arms'
     * rules last changed on 6-12 Oct (the R14 cap, bar-close entries, the protection fixes): what came before is another
     * arm's record. A constant on purpose: tests give their own trades and today, never the real date.
     */
    val START: LocalDate = LocalDate.of(2026, 10, 13)

    /** An arm's backtest per lot after charges: [perTrade] rupees a trade, [sd] the spread of single trades, [source]. */
    data class Backtest(val perTrade: Double, val sd: Double, val source: String) {
        /** The 5-95% band for the sum of [n] trades (null for none). */
        fun band(n: Int): Band? = if (n <= 0) null else {
            val mid = perTrade * n
            val half = Z * sd * sqrt(n.toDouble())
            Band(mid - half, mid, mid + half)
        }
    }

    data class Band(val low: Double, val mid: Double, val high: Double)

    /**
     * The backtests pinned by arm name (as the paper record names its owner). Liquidity BANKNIFTY: the app's own research
     * expectation ([ForwardCheck.LIQUIDITY]: TEST Jul 2024 - Oct 2026, 916 trades, Rs 227.66 a trade, sd Rs 2,064.33). The
     * ORB family: X1's 5-year replay per trade (ORB -101, ORB Fresh -103, ORB Sweep -176, Range Fade -148); the spread is
     * X1's 30-trade ORB band (-11,068 to +3,691 -> sd about Rs 820) for ORB and ORB Fresh, and the losers study's sd for
     * Sweep (SH1, Rs 2,635) and Fade (RP10, Rs 533). Solo: [ForwardCheck.SOLO]. Everything else has no band on the phone.
     */
    val BACKTESTS: Map<String, Backtest> = mapOf(
        LiquiditySplit.name("BANKNIFTY") to Backtest(ForwardCheck.LIQUIDITY.mean, ForwardCheck.LIQUIDITY.sd, ForwardCheck.LIQUIDITY.source),
        "ORB" to Backtest(-101.0, 820.0, "research X1 (5-year replay; sd from its 30-trade band)"),
        "ORB Fresh" to Backtest(-103.0, 820.0, "research X1 (5-year replay; sd as ORB's)"),
        "ORB Sweep" to Backtest(-176.0, 2_635.21, "research X1 (5-year replay); sd losers study SH1"),
        "Range Fade" to Backtest(-148.0, 533.36, "research X1 (5-year replay); sd losers study RP10"),
        "Solo" to Backtest(ForwardCheck.SOLO.mean, ForwardCheck.SOLO.sd, ForwardCheck.SOLO.source),
    )

    fun backtestOf(name: String): Backtest? = BACKTESTS[name.trim()]

    enum class State { TOO_EARLY, WITHIN, BELOW, ABOVE, PASSED, NO_BACKTEST }

    /** One closed paper trade: the day it closed and its net rupees after charges, per lot. */
    data class Trade(val day: LocalDate, val net: Double)

    data class Verdict(
        val name: String, val state: State, val trades: Int, val days: Int, val net: Double, val backtest: Backtest?, val band: Band?,
        val winRate: Double,
    ) {
        /** Inside the band so far (null: no band, or no trades). */
        val inBand: Boolean? get() = band?.let { net >= it.low && net <= it.high }

        private fun range(): String = band?.let { "the backtest's 5-95% range for $trades trades is ${rs(it.low)} to ${rs(it.high)}" } ?: ""

        /** The short state words: "too early (12/60, 9/40)", "within the backtest's normal range", ... */
        fun words(): String = when (state) {
            State.TOO_EARLY -> "too early ($trades/$MIN_TRADES, $days/$MIN_DAYS)"
            State.WITHIN -> "within the backtest's normal range"
            State.BELOW -> "below the backtest's range - investigate"
            State.ABOVE -> "above the backtest's range - check the fills"
            State.PASSED -> "passed"
            State.NO_BACKTEST -> "no backtest on the phone to judge against"
        }

        fun text(): String {
            // (The name is never part of a format string: a "%" in it must not break the sentence.)
            val record = "$trades trade${if (trades == 1) "" else "s"} since ${START.dayOfMonth} Oct, ${rs(net)}"
            val r = range()
            return when (state) {
                State.TOO_EARLY -> "$name's paper test: ${words()} - $record" +
                    (when (inBand) { true -> "; within the backtest's normal range so far ($r)"; false -> "; $r"; null -> "" }) + "."
                State.WITHIN -> "$name's paper test: ${words()} - $record; $r" +
                    (if (backtest != null && backtest.perTrade <= 0) ". Its backtest loses, so it cannot pass: losing as researched." else ".")
                State.BELOW -> "$name's paper test: ${words()} - $record; $r. Check its fills and the evening replay before trusting it."
                State.ABOVE -> "$name's paper test: ${words()} - $record; $r. Better than the backtest is usually luck or kind fills, not an edge."
                State.PASSED -> "$name passed its paper test: $record over $days trading days, within its backtest's range ($r)" +
                    ". Passing only informs you: going live stays your step, with your PIN."
                State.NO_BACKTEST -> "$name's paper test: $record; ${words()}, so it cannot pass."
            }
        }
    }

    /** Weekdays from [from] to [to], both counted (exchange holidays are not known here; a slight over-count). */
    fun tradingDays(from: LocalDate, to: LocalDate): Int {
        if (to.isBefore(from)) return 0
        var n = 0; var d = from
        while (!d.isAfter(to)) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) n++; d = d.plusDays(1) }
        return n
    }

    /**
     * [name]'s verdict on its closed paper [trades] (any order) from [from]; [today] counts the trading days of the test (null:
     * the days it traded on). [backtest]: its pinned band ([backtestOf] by default).
     */
    fun judge(name: String, trades: List<Trade>, today: LocalDate?, backtest: Backtest? = backtestOf(name), from: LocalDate = START): Verdict {
        val mine = trades.filter { !it.day.isBefore(from) }
        val n = mine.size
        val net = mine.sumOf { it.net }
        val days = if (today != null) tradingDays(from, today) else mine.map { it.day }.distinct().size
        val band = backtest?.band(n)
        val win = if (n == 0) 0.0 else mine.count { it.net > 0 }.toDouble() / n
        val early = n < MIN_TRADES || days < MIN_DAYS
        val state = when {
            band != null && n >= MIN_BAND_TRADES && net < band.low -> State.BELOW
            band != null && n >= MIN_BAND_TRADES && net > band.high -> State.ABOVE
            early -> State.TOO_EARLY
            backtest == null -> State.NO_BACKTEST
            backtest.perTrade > 0 && net > 0 -> State.PASSED
            else -> State.WITHIN
        }
        return Verdict(name, state, n, days, net, backtest, band, win)
    }

    /** "What passed": the passed first, then those to investigate, then the rest by trades. */
    fun say(vs: List<Verdict>): String {
        if (vs.isEmpty()) return "Nothing is on a paper test yet, Boss. Each arm is judged against its own backtest's range, " +
            "with at least $MIN_TRADES trades and $MIN_DAYS trading days from ${START.dayOfMonth} Oct before anything passes."
        val passed = vs.filter { it.state == State.PASSED }.sortedByDescending { it.net }
        val look = vs.filter { it.state == State.BELOW || it.state == State.ABOVE }.sortedBy { it.net }
        val rest = vs.filter { it !in passed && it !in look }.sortedByDescending { it.trades }
        val head = if (passed.isEmpty()) "Nothing has passed its paper test yet, Boss (it takes $MIN_TRADES trades and $MIN_DAYS trading days " +
            "from ${START.dayOfMonth} Oct, judged against each arm's own backtest). " else "Passed on paper, Boss: "
        return head + (passed + look + rest).joinToString(" ") { it.text() } +
            if (passed.isNotEmpty()) " Passing switches nothing: going live is your step, in Live with your PIN." else ""
    }

    /** Whole rupees: "+Rs 1,200", "-Rs 9,670". */
    fun rs(x: Double): String = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(java.util.Locale.ENGLISH, kotlin.math.abs(x))

    fun asked(text: String): Boolean = rx("(?i)\\b(what|which)( strategies| ideas| arms)? (held up|has held up|have held up|passed|works?)\\b|\\bpaper tests?\\b|\\bbest (idea|strategy|strategies)\\b|\\bwhat (should|can) i (take|trade) live\\b").containsMatchIn(text)
}
