package com.optionslab.app.ira

import com.optionslab.ira.LiquidityDrawdown
import com.optionslab.ira.LiquidityRecord
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "How deep has liquidity fallen from its best" ([LiquidityDrawdown], Boss, 07 Oct 2026): Liquidity 15+5's closed paper
 * trades read from the arms' own book ([com.optionslab.app.data.OrbArms.closedPaper], a short wait on the arms' lock - their
 * pass may hold it across a network read), as a running total per lot: its best, the drawdown now, the worst ever and its
 * recovery, the longest losing streak. Reads only: nothing is armed, placed, closed or changed, no setting is touched.
 * Nothing of the book is logged. In the chat only; not in IraGoldAlgo.
 */
internal object IraLiquidityDrawdown {
    /** A short wait on the arms' lock. */
    private const val BOOK_MS = 3_000L

    /** The answer: the arm's book (null past its wait: then said as not read), read for its falls. */
    suspend fun answer(): String {
        val closed = withTimeoutOrNull(BOOK_MS) { com.optionslab.app.data.OrbArms.closedPaper() }
            ?: return "Liquidity 15+5's book took too long to read just now, Boss - ask again in a moment.\n${LiquidityDrawdown.END}"
        return LiquidityDrawdown.answer(LiquidityRecord.rows(closed.map { IraBots.tradeOf(it) }))
    }
}
