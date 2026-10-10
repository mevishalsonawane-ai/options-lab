package com.optionslab.app.ira

import com.optionslab.ira.LiquidityRecord
import com.optionslab.ira.LiquidityWhen
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Which day does liquidity do best" ([LiquidityWhen], Boss, 07 Oct 2026): Liquidity 15+5's closed paper trades read from
 * the arms' own book ([com.optionslab.app.data.OrbArms.closedPaper], a short wait on the arms' lock - their pass may hold it
 * across a network read), split by weekday, expiry day or not (from the option expiries in its own book) and entry time.
 * Reads only: nothing is armed, placed, closed or changed, no setting is touched. Nothing of the book is logged. In the chat
 * only; not in IraGoldAlgo.
 */
internal object IraLiquidityWhen {
    /** A short wait on the arms' lock. */
    private const val BOOK_MS = 3_000L

    /** The answer to [focus]: the arm's book (null past its wait: then said as not read), split. */
    suspend fun answer(focus: LiquidityWhen.Focus): String {
        val closed = withTimeoutOrNull(BOOK_MS) { com.optionslab.app.data.OrbArms.closedPaper() }
            ?: return "Liquidity 15+5's book took too long to read just now, Boss - ask again in a moment.\n${LiquidityWhen.END}"
        return LiquidityWhen.answer(focus, LiquidityRecord.rows(closed.map { IraBots.tradeOf(it) }))
    }
}
