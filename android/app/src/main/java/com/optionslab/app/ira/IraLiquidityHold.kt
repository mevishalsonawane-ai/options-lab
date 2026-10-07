package com.optionslab.app.ira

import com.optionslab.ira.LiquidityHold
import com.optionslab.ira.LiquidityRecord
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "How long does liquidity hold its trades" ([LiquidityHold], Boss, 07 Oct 2026): Liquidity 15+5's closed paper trades read
 * from the arms' own book ([com.optionslab.app.data.OrbArms.closedPaper], a short wait on the arms' lock - their pass may
 * hold it across a network read), timed from entry to exit over the span asked. Reads only: nothing is armed, placed,
 * closed or changed, no setting is touched. Nothing of the book is logged. In the chat only; not in IraGoldAlgo.
 */
internal object IraLiquidityHold {
    /** A short wait on the arms' lock. */
    private const val BOOK_MS = 3_000L

    /** The answer to [q]: the arm's book (null past its wait: then said as not read), timed. */
    suspend fun answer(q: LiquidityRecord.Q): String {
        val closed = withTimeoutOrNull(BOOK_MS) { com.optionslab.app.data.OrbArms.closedPaper() }
            ?: return "Liquidity 15+5's book took too long to read just now, Boss - ask again in a moment.\n${LiquidityHold.END}"
        val rows = LiquidityRecord.rows(closed.map { IraBots.tradeOf(it) })
        return LiquidityHold.answer(q, rows, com.optionslab.app.data.Market.today())
    }
}
