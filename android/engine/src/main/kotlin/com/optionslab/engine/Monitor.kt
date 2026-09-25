package com.optionslab.engine

/**
 * Health checks against the expiry put's documented kill conditions.
 * Port of `monitor.py`.
 *
 * THIS IS NOT A LEARNER, deliberately. It adapts nothing and fits nothing. It
 * answers one question - are the conditions the strategy depends on still
 * true? - from a trade ledger, so it runs on paper fills exactly as on
 * backtested ones.
 */
object Monitor {
    const val CREDIT_FLOOR = 2.00
    const val CREDIT_WARN = 3.00
    const val MARGIN_WARN_PTS = 40.0
    const val MARGIN_FAIL_PTS = 15.0
    const val REGIME_WARN_FRACTION = 0.80
    const val REGIME_FAIL_FRACTION = 1.00
    const val ASSUMED_LOT = 65

    enum class Status(val rank: Int, val mark: String) { PASS(0, "PASS"), WARN(1, "WARN"), FAIL(2, "FAIL") }

    data class Check(val name: String, val status: Status, val measured: String, val threshold: String, val why: String) {
        fun format(): String = "[${status.mark}] %-22s %-28s vs %s".format(name, measured, threshold)
    }

    /** The subset of a trade the checks read - backtest trades and paper rows both map onto it. */
    data class Row(
        val session: java.time.LocalDate,
        val strike: Double,
        val credit: Double,
        val settlement: Double,
        val forward: Double,
        val lotSize: Int,
        val intrinsic: Double? = null,
        val otmRealised: Double? = null,
    )

    fun rows(trades: List<ExpiryPut.Trade>): List<Row> = trades.map {
        Row(it.session!!, it.strike, it.credit, it.settlement, it.forward, it.lotSize, it.intrinsic,
            it.otmRealised.takeIf { v -> !v.isNaN() })
    }

    class EmptyLedger : IllegalArgumentException("empty trade ledger; no trades is not the same as no problems")

    private fun require(trades: List<Row>) { if (trades.isEmpty()) throw EmptyLedger() }

    fun checkCredit(trades: List<Row>): Check {
        require(trades)
        val med = Stats.median(trades.map { it.credit })
        val status = if (med < CREDIT_FLOOR) Status.FAIL else if (med < CREDIT_WARN) Status.WARN else Status.PASS
        return Check("credit level", status, "median Rs %.2f/unit".format(med),
            "floor Rs %.2f, warn below Rs %.2f".format(CREDIT_FLOOR, CREDIT_WARN),
            "below the floor the flat Rs 20/order brokerage is a quarter of gross and the trade is not worth doing")
    }

    fun observedLot(trades: List<Row>): Int {
        require(trades)
        return trades.maxBy { it.session }.lotSize
    }

    fun checkLotSize(current: Int, assumed: Int = ASSUMED_LOT, trades: List<Row>? = null): Check {
        val seen = trades?.map { it.lotSize }?.toSortedSet()?.toList() ?: listOf(current)
        val mixed = seen.size > 1
        val status = if (mixed || current != assumed) Status.FAIL else Status.PASS
        return Check("lot size", status,
            if (mixed) seen.joinToString(" and ") else "$current",
            "$assumed (what the economics were measured at)" + if (mixed) ", and one lot across the window" else "",
            "flat brokerage does not scale with lot size, so a lot change moves the whole cost fraction; at lot 25 this trade won 94.1%, not 100%")
    }

    fun checkMarginOfSafety(trades: List<Row>): Check {
        require(trades)
        val margin = trades.map { it.settlement - (it.strike - it.credit) }
        val worst = margin.min()
        val status = if (worst < MARGIN_FAIL_PTS) Status.FAIL else if (worst < MARGIN_WARN_PTS) Status.WARN else Status.PASS
        return Check("margin of safety", status,
            "min %,.1f pts, median %,.1f".format(worst, Stats.median(margin)),
            "warn below %.0f, fail below %.0f pts".format(MARGIN_WARN_PTS, MARGIN_FAIL_PTS),
            "the settlement reconstruction has a ~6.3 point standard deviation, so a margin inside that band means the win is inside the noise")
    }

    fun checkRegime(trades: List<Row>, otmPct: Double): Check {
        require(trades)
        val falls = trades.map { (it.forward - it.settlement) / it.forward }
        val ratios = trades.mapIndexedNotNull { i, t ->
            val d = t.otmRealised ?: otmPct
            if (d > 0) falls[i] / d else null
        }.filter { !it.isNaN() }
        val worst = falls.max()
        val ratio = if (ratios.isNotEmpty()) ratios.max() else Double.POSITIVE_INFINITY
        val status = if (ratio >= REGIME_FAIL_FRACTION) Status.FAIL else if (ratio >= REGIME_WARN_FRACTION) Status.WARN else Status.PASS
        return Check("regime", status,
            "worst fall %+.3f%% = %.0f%% of its own strike".format(100 * worst, 100 * ratio),
            "warn at %.0f%%, fail at %.0f%% of %.2f%%".format(100 * REGIME_WARN_FRACTION, 100 * REGIME_FAIL_FRACTION, 100 * otmPct),
            "the win record is a property of a bull market, not of the payoff; falls reaching the strike are what turns it over")
    }

    fun checkVariancePremium(trades: List<Row>): Check {
        require(trades)
        val paid = trades.map { it.intrinsic ?: maxOf(it.strike - it.settlement, 0.0) }
        val realised = paid.average()
        val credit = trades.map { it.credit }.average()
        val status = if (realised > credit) Status.FAIL else if (realised > 0.5 * credit) Status.WARN else Status.PASS
        return Check("variance premium", status,
            "credit Rs %.2f vs mean payout %.2f pts".format(credit, realised),
            "credit must exceed the average realised payout",
            "premium selling pays because options are dearer than the movement they insure; when that inverts the edge is gone, not smaller")
    }

    fun runChecks(trades: List<Row>, otmPct: Double, lotSize: Int): List<Check> {
        require(trades)
        return listOf(
            checkCredit(trades),
            checkLotSize(lotSize, trades = trades),
            checkMarginOfSafety(trades),
            checkRegime(trades, otmPct),
            checkVariancePremium(trades),
        )
    }

    /** The overall verdict is the WORST individual check, never an average. */
    fun verdict(checks: List<Check>): Status {
        if (checks.isEmpty()) throw IllegalArgumentException("no checks to summarise")
        return checks.maxBy { it.status.rank }.status
    }

    /** The `regime` command: the last `last` sessions (0 = all). */
    fun healthOf(rows: List<Row>, last: Int, otmPct: Double, pinnedLot: Int?): Pair<List<Row>, List<Check>> {
        val sorted = rows.sortedBy { it.session }
        val recent = if (last > 0) sorted.takeLast(last) else sorted
        val lot = pinnedLot ?: observedLot(recent)
        return recent to runChecks(recent, otmPct, lot)
    }
}
