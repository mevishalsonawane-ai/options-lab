package com.optionslab.engine

import java.math.BigInteger
import java.time.LocalDate

/**
 * Dated lot-size history for index options. Port of `lots.py`.
 *
 * READ FROM NSE BHAVCOPY (`NewBrdLotQty`), not assumed. Dates before coverage
 * RAISE rather than extrapolate backwards; a silently wrong lot is a 2.6x error.
 */
object Lots {
    const val MIN_MOVE_AGREEMENT = 0.95
    const val TOP_MOVE_SIZES = 5

    class NoLotSize(msg: String) : NoSuchElementException(msg)

    val LOT_HISTORY: Map<String, List<Pair<LocalDate, Int>>> = mapOf(
        "NIFTY" to listOf(
            LocalDate.of(2024, 1, 1) to 50,
            LocalDate.of(2024, 4, 26) to 25,
            LocalDate.of(2024, 12, 27) to 75,
            LocalDate.of(2025, 12, 31) to 65,
        ),
        "BANKNIFTY" to listOf(
            LocalDate.of(2024, 1, 1) to 15,
            LocalDate.of(2025, 1, 31) to 30,
        ),
    )

    private fun entries(underlying: String) = LOT_HISTORY[underlying.uppercase()]
        ?: throw NoLotSize("no lot history for '$underlying'; known: ${LOT_HISTORY.keys.sorted().joinToString()}")

    /** The lot the nearest expiry carried on `day`. Forward extrapolation is intended. */
    fun lotSizeOn(underlying: String, day: LocalDate): Int {
        val e = entries(underlying)
        var latest: Int? = null
        for ((effective, lot) in e) {
            if (!effective.isAfter(day)) latest = lot else break
        }
        return latest ?: throw NoLotSize(
            "${underlying.uppercase()} lot history starts ${e[0].first}; $day is before it. " +
                "Verify the lot from NSE bhavcopy rather than assuming a value.")
    }

    fun coverageStart(underlying: String): LocalDate = entries(underlying)[0].first

    /**
     * The lot the contracts in THIS chain carry, read off open interest.
     *
     * Upstox publishes OI lot-multiplied, so the most common non-zero OI moves
     * are whole lots. The gcd of the five most FREQUENT move sizes, required to
     * divide at least 95% of all moves. Null means "ask something else", never
     * "lot 1".
     */
    fun lotFromChain(series: List<Series>, minAgreement: Double = MIN_MOVE_AGREEMENT): Int? {
        // Counts in first-occurrence order, so ties rank as pandas' value_counts does.
        val counts = LinkedHashMap<Long, Int>()
        val all = ArrayList<Long>()
        // pandas diffs within each (strike, right) group after a time sort and
        // then counts in row order; the per-series walk visits the same moves.
        for (s in series) {
            if (s.right == Right.IX) continue
            for (i in 1 until s.size) {
                val d = s.oi[i] - s.oi[i - 1]
                if (d != 0L) {
                    val a = kotlin.math.abs(d)
                    all += a
                    counts[a] = (counts[a] ?: 0) + 1
                }
            }
        }
        if (all.isEmpty()) return null
        val common = counts.entries.sortedByDescending { it.value }.take(TOP_MOVE_SIZES).map { it.key }
        var g = BigInteger.ZERO
        for (c in common) g = g.gcd(BigInteger.valueOf(c))
        val lot = g.toLong()
        if (lot <= 1) return null
        val agree = all.count { it % lot == 0L }.toDouble() / all.size
        if (agree < minAgreement) return null
        return lot.toInt()
    }
}
