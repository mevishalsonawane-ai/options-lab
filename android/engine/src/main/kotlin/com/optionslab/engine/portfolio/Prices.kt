package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.abs

/**
 * Calendar-aligned closes for a set of symbols. Port of `portfolio/data.py`,
 * with the store/broker read replaced by bars the caller supplies.
 *
 * Inner join: a portfolio return needs every holding priced on the same
 * session, so a date any symbol lacks is dropped rather than forward-filled,
 * and a late listing moves the whole matrix's start. Split-like moves are
 * reported, never adjusted.
 */
class PriceMatrix internal constructor(
    val dates: List<LocalDate>,
    val symbols: List<String>,
    /** closes[symbol][session] */
    internal val closes: Array<DoubleArray>,
    val source: String,
    /** symbol -> [(date, return)] for moves above 35%, likely an unadjusted corporate action. */
    val warnings: Map<String, List<Pair<LocalDate, Double>>>,
) {
    val sessions get() = dates.size
    val start: LocalDate get() = dates.first()
    val end: LocalDate get() = dates.last()

    fun close(symbol: String): DoubleArray = closes[symbols.indexOf(symbol)]

    /** Simple daily returns, first row dropped (not zero-filled). */
    internal fun returns(): Array<DoubleArray> =
        Array(symbols.size) { s -> DoubleArray(sessions - 1) { closes[s][it + 1] / closes[s][it] - 1.0 } }

    internal fun slice(from: Int, to: Int) = PriceMatrix(
        dates.subList(from, to), symbols, Array(symbols.size) { closes[it].copyOfRange(from, to) }, source, emptyMap(),
    )

    companion object {
        val SUPPORTED_EXCHANGES = listOf("NSE", "BSE")
        val BENCHMARK_EXCHANGES = listOf("NSE_INDEX", "BSE_INDEX", "GLOBAL_INDEX")

        /** `split_artifacts`: sessions whose absolute move exceeds `threshold`. */
        fun splitArtifacts(dates: List<LocalDate>, symbols: List<String>, closes: Array<DoubleArray>, threshold: Double = 0.35):
            Map<String, List<Pair<LocalDate, Double>>> {
            val out = LinkedHashMap<String, List<Pair<LocalDate, Double>>>()
            symbols.forEachIndexed { s, symbol ->
                val hits = (1 until dates.size).mapNotNull { i ->
                    val r = closes[s][i] / closes[s][i - 1] - 1.0
                    if (abs(r) > threshold) Pair(dates[i], r) else null
                }
                if (hits.isNotEmpty()) out[symbol] = hits
            }
            return out
        }

        internal fun normaliseExchange(exchange: String?, allowed: List<String>): String {
            val ex = (exchange ?: "").trim().uppercase()
            if (ex !in allowed) {
                throw PortfolioException(422, "${ex.ifEmpty { "(blank)" }} is not supported here; expected ${allowed.joinToString(" or ")}")
            }
            return ex
        }

        /** One symbol's closes in [start, end]: unparseable closes dropped, last write wins per date. */
        internal fun seriesFor(symbol: String, bars: List<DailyBar>?, start: LocalDate, end: LocalDate): Ser {
            val inWindow = bars.orEmpty().filter { it.date >= start && it.date <= end }
            if (inWindow.isEmpty()) {
                throw PortfolioException(422, "$symbol: no D history for $start..$end")
            }
            val byDate = java.util.TreeMap<LocalDate, Double>()
            for (b in inWindow) if (!b.close.isNaN()) byDate[b.date] = b.close
            return Ser(byDate.keys.toList(), byDate.values.toDoubleArray())
        }

        /**
         * `load_prices` + `_assemble`: validate the exchanges, read each symbol's
         * bars, inner-join them and refuse anything that could not be backtested.
         */
        fun load(
            symbols: List<String>,
            exchanges: List<String>,
            start: LocalDate,
            end: LocalDate,
            prices: Map<String, List<DailyBar>>,
            source: String = "api",
            minSessions: Int = 2,
            allowedExchanges: List<String> = SUPPORTED_EXCHANGES,
        ): PriceMatrix {
            if (symbols.isEmpty()) throw PortfolioException(422, "no symbols requested")
            if (exchanges.size != symbols.size) {
                throw PortfolioException(422, "${symbols.size} symbols but ${exchanges.size} exchanges; pass one exchange or one per symbol")
            }
            exchanges.forEach { normaliseExchange(it, allowedExchanges) }
            val series = LinkedHashMap<String, Ser>()
            for (symbol in symbols) series[symbol] = seriesFor(symbol, prices[symbol], start, end)
            val cols = series.keys.toList()
            var common: Set<LocalDate> = series.values.first().dates.toSet()
            for (s in series.values) common = common.intersect(s.dates.toSet())
            val dates = common.sorted()
            val closes = Array(cols.size) { c ->
                val s = series.getValue(cols[c])
                val at = HashMap<LocalDate, Double>(s.size * 2)
                s.dates.forEachIndexed { i, d -> at[d] = s.v[i] }
                DoubleArray(dates.size) { at.getValue(dates[it]) }
            }
            if (closes.any { col -> col.any { !it.isFinite() || it <= 0 } }) {
                throw PortfolioException(422, "price matrix must contain only positive finite closes")
            }
            if (dates.size < minSessions) {
                throw PortfolioException(
                    422,
                    "only ${dates.size} session(s) are common to all ${symbols.size} symbols between $start and $end; " +
                        "a shorter window or a symbol with a later listing date is likely",
                )
            }
            return PriceMatrix(dates, cols, closes, source, splitArtifacts(dates, cols, closes))
        }
    }
}
