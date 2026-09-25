package com.optionslab.engine

import kotlin.math.max
import kotlin.math.min

/**
 * How many lots to trade, and how much capital to hold behind them.
 * Port of `strategy/sizing.py`.
 *
 * Sizing does not shrink the loss. It only decides whether the loss is
 * terminal. The short put's loss is unbounded and scales one-for-one with the
 * move past the strike; the sample contains no such day, so every number here
 * is payoff arithmetic, not an observed outcome.
 */
object Sizing {
    const val MEDIAN_CREDIT_RS = 315.0
    const val MEAN_NET_RS = 394.0
    const val EXPIRIES_PER_YEAR = 52
    const val EXCHANGE_MARGIN_RS = 107_264.0
    const val LOT_SIZE = 65
    const val MAX_LOTS_ON_DEPTH = 2
    const val DEFAULT_SURVIVE_MOVE_PCT = -0.06

    data class Plan(
        val lots: Int,
        val capitalPerLot: Double,
        val totalCapital: Double,
        val expectedAnnualRs: Double,
        val expectedAnnualPct: Double,
        val survivesMovePct: Double,
        val worstCaseRs: Double,
    )

    class InsufficientCapital(msg: String) : IllegalArgumentException(msg)

    /** Rupee loss if the index settles `movePct` lower. Positive means a loss. */
    fun lossAtMove(
        movePct: Double, forward: Double = 24_000.0, otmPct: Double = 0.0075,
        creditRs: Double = MEDIAN_CREDIT_RS, lotSize: Int = LOT_SIZE, lots: Int = 1,
    ): Double {
        val strike = forward * (1.0 - otmPct)
        val settlement = forward * (1.0 + movePct)
        val intrinsic = max(strike - settlement, 0.0)
        return max(0.0, intrinsic * lotSize * lots - creditRs * lots)
    }

    fun capitalNeeded(surviveMovePct: Double, lots: Int = 1): Double =
        EXCHANGE_MARGIN_RS * lots + lossAtMove(movePct = surviveMovePct, lots = lots)

    fun planPosition(
        totalCapital: Double,
        surviveMovePct: Double = DEFAULT_SURVIVE_MOVE_PCT,
        maxLots: Int = MAX_LOTS_ON_DEPTH,
    ): Plan {
        require(surviveMovePct < 0) {
            "survive_move_pct must be negative (a short put is hurt by a fall); got %+.3f".format(surviveMovePct)
        }
        val perLot = capitalNeeded(surviveMovePct, 1)
        val affordable = kotlin.math.floor(totalCapital / perLot).toInt()
        if (affordable < 1) {
            throw InsufficientCapital(
                "Rs %,.0f is not enough to hold one lot through a %.1f%% day: that needs Rs %,.0f (Rs %,.0f margin plus Rs %,.0f of loss).".format(
                    totalCapital, 100 * surviveMovePct, perLot, EXCHANGE_MARGIN_RS, lossAtMove(surviveMovePct)))
        }
        val lots = min(affordable, maxLots)
        val annual = MEAN_NET_RS * EXPIRIES_PER_YEAR * lots
        return Plan(
            lots = lots, capitalPerLot = perLot, totalCapital = totalCapital,
            expectedAnnualRs = annual, expectedAnnualPct = annual / totalCapital,
            survivesMovePct = surviveMovePct,
            worstCaseRs = lossAtMove(movePct = surviveMovePct, lots = lots),
        )
    }

    /** The tail table from the module docstring, computed rather than typed. */
    fun tailTable(moves: List<Double> = listOf(-0.0111, -0.03, -0.06, -0.13)): List<Triple<Double, Double, Double>> =
        moves.map { m ->
            val loss = lossAtMove(m)
            Triple(m, loss, loss / MEDIAN_CREDIT_RS)
        }
}
