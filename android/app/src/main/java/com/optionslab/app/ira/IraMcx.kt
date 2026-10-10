package com.optionslab.app.ira

import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.data.McxMarket
import com.optionslab.app.data.Paper
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxInstruments
import com.optionslab.ira.McxTalk

/**
 * Jarvis on MCX (9 Oct 2026): the price of crude, natural gas, gold or silver (each one's near-month future) and the MCX
 * positions held, paper and Zerodha. Reads only ([McxTalk] words it); Jarvis never trades MCX.
 */
object IraMcx {
    suspend fun answer(asked: McxTalk.Asked, locked: Boolean): String {
        val all = runCatching { McxMarket.contracts() }.getOrDefault(McxMarket.cached())
        val futs = asked.names.mapNotNull { McxInstruments.futures(all, it, Market.today(), 1).firstOrNull() }
        val q = runCatching { McxMarket.quotes(futs) }.getOrDefault(emptyMap())
        val prices = futs.mapNotNull { f -> q[f.tradingSymbol]?.let { McxTalk.Price(f.name, f.label, it.last, it.changePct, it.lastSession) } }
        val held = if (asked.positions && !locked) runCatching { held() }.getOrNull() else null
        return McxTalk.answer(asked, prices, held, McxMarket.isOpen(), locked)
    }

    /** Every MCX position held: the paper account's (units) and Zerodha's (its lots x the lot's multiplier). */
    private suspend fun held(): List<McxTalk.Held> {
        val out = ArrayList<McxTalk.Held>()
        val snap = Paper.snapshot(Paper.SHARED_QUOTE_MS)
        for (p in snap.positions.positions.filter { it.quantity != 0 && it.exchange == Mcx.EXCHANGE }) {
            out += McxTalk.Held(McxMarket.find(p.symbol)?.label ?: p.symbol, p.quantity, p.averagePrice, p.pnl, "Paper")
        }
        if (Broker.loggedIn) {
            val book = Broker.within(10_000) { Broker.positionBook() }
            for (p in book?.net.orEmpty().filter { it.exchange == Mcx.EXCHANGE && it.qty != 0 }) {
                val m = McxMarket.find(p.symbol)
                val mult = m?.multiplier ?: p.multiplier.toInt().coerceAtLeast(1)
                out += McxTalk.Held(m?.label ?: p.symbol, p.qty * mult, p.avg, p.pnl, "Zerodha")
            }
        }
        return out
    }
}
