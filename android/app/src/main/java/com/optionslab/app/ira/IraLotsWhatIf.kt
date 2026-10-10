package com.optionslab.app.ira

import com.optionslab.ira.LiquidityRecord
import com.optionslab.ira.LotsWhatIf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "What if liquidity traded 3 lots" ([LotsWhatIf], Boss, 07 Oct 2026): Liquidity 15+5's closed paper trades read from the
 * arms' own book ([com.optionslab.app.data.OrbArms.closedPaper], a short wait on the arms' lock - their pass may hold it
 * across a network read), recomputed at the lots asked. Reads only: the arm's lots are never changed here (the Lots setting
 * and "set liquidity to 3 lots" each ask first), nothing is armed, placed or closed. Nothing of the book is logged. In the
 * chat only; not in IraGoldAlgo.
 */
internal object IraLotsWhatIf {
    /** A short wait on the arms' lock. */
    private const val BOOK_MS = 3_000L

    /** The answer to [q]: the arm's book (null past its wait: then said as not read) and the research, at the lots asked. */
    suspend fun answer(q: LotsWhatIf.Q): String {
        val closed = withTimeoutOrNull(BOOK_MS) { com.optionslab.app.data.OrbArms.closedPaper() }
            ?: return "Liquidity 15+5's book took too long to read just now, Boss - ask again in a moment.\n${LotsWhatIf.END}"
        val rows = LiquidityRecord.rows(closed.map { IraBots.tradeOf(it) })
        return LotsWhatIf.answer(q, rows, com.optionslab.app.data.Market.today())
    }
}
