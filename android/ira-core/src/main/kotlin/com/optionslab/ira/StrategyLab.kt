package com.optionslab.ira

import com.optionslab.engine.pine.Pine
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Jarvis's strategy lab: a pattern Ira found becomes a Pine strategy, backtested on the candles it has, judged, and
 * offered to the owner. Nothing here arms or trades - the app arms an approved strategy, on paper, through IraAlgo's own
 * Pine auto-trade. The rules of every generated strategy are the same and plain:
 *   entry   the pattern's candle closes (between 09:15 and 14:30): BUY at the next candle's open - a bullish pattern
 *           as a long (a call in the app), a bearish one as a short (a put)
 *   exits   a stop 1 ATR(14) away, a target 2 ATRs away, after [HOLD] candles, and before the close
 * Only directional patterns that Pine can state exactly become strategies (not doji, inside bar or double top/bottom).
 */
object StrategyLab {
    const val HOLD = 4
    const val STOP_ATR = 1.0
    const val TARGET_ATR = 2.0
    /** A strategy is offered as worth a paper trial only with at least this many trades... */
    const val MIN_TRADES = 30
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    val SUPPORTED = listOf(PatternKind.BULLISH_ENGULFING, PatternKind.BEARISH_ENGULFING, PatternKind.HAMMER, PatternKind.SHOOTING_STAR,
        PatternKind.THREE_WHITE_SOLDIERS, PatternKind.THREE_BLACK_CROWS, PatternKind.BREAKOUT_UP, PatternKind.BREAKOUT_DOWN)

    /** Markets a strategy can be armed on in IraAlgo (index options). */
    val MARKETS = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    fun supported(k: PatternKind, m: Market) = k in SUPPORTED && m in MARKETS

    /** The pattern as a Pine condition on the current candle, matching [Patterns.at]. */
    private fun condition(k: PatternKind): String = when (k) {
        PatternKind.BULLISH_ENGULFING -> "close[1] < open[1] and close > open and close >= open[1] and open <= close[1] and body > body[1]"
        PatternKind.BEARISH_ENGULFING -> "close[1] > open[1] and close < open and close <= math.min(close[1], open[1]) and open >= close[1] and body > body[1]"
        PatternKind.HAMMER -> "rng > 0 and body > 0 and not isDoji and lowerW >= 2 * body and upperW <= 0.5 * math.max(body, rng * 0.1)"
        PatternKind.SHOOTING_STAR -> "rng > 0 and body > 0 and not isDoji and upperW >= 2 * body and lowerW <= 0.5 * math.max(body, rng * 0.1)"
        PatternKind.THREE_WHITE_SOLDIERS -> "close > open and close[1] > open[1] and close[2] > open[2] and close[1] > close[2] and close > close[1] and " +
            "body >= 0.6 * avgBody and body[1] >= 0.6 * avgBody and body[2] >= 0.6 * avgBody"
        PatternKind.THREE_BLACK_CROWS -> "close < open and close[1] < open[1] and close[2] < open[2] and close[1] < close[2] and close < close[1] and " +
            "body >= 0.6 * avgBody and body[1] >= 0.6 * avgBody and body[2] >= 0.6 * avgBody"
        PatternKind.BREAKOUT_UP -> "close > ta.highest(high, 20)[1]"
        PatternKind.BREAKOUT_DOWN -> "close < ta.lowest(low, 20)[1]"
        else -> throw IllegalArgumentException("${k.label} is not a strategy pattern")
    }

    fun name(k: PatternKind, m: Market, minutes: Int) = "Jarvis: ${k.label} ${m.label} ${chart(minutes)}"
    private fun chart(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

    /** The Pine strategy for pattern [k] on [m]'s [minutes]-minute chart. */
    fun pine(k: PatternKind, m: Market, minutes: Int): String {
        require(supported(k, m)) { "${k.label} on ${m.label} is not a strategy Jarvis can write" }
        val long = k.bias > 0
        val side = if (long) "strategy.long" else "strategy.short"
        val stop = if (long) "close - $STOP_ATR * atr" else "close + $STOP_ATR * atr"
        val limit = if (long) "close + $TARGET_ATR * atr" else "close - $TARGET_ATR * atr"
        val cut = 15 * 60 - minutes                     // the last candle that closes by 15:00 starts here (minutes after midnight)
        return """
            //@version=5
            strategy("${name(k, m, minutes)}", overlay = true, initial_capital = 100000, default_qty_type = strategy.fixed, default_qty_value = 1)
            // Written by Jarvis from the ${k.label} pattern: entry after the pattern's candle closes, stop ${STOP_ATR} ATR,
            // target ${TARGET_ATR} ATR, out after $HOLD candles or by 15:00.
            body = math.abs(close - open)
            rng = high - low
            upperW = high - math.max(open, close)
            lowerW = math.min(open, close) - low
            avgBody = ta.sma(body, 20)[1]
            isDoji = body <= 0.1 * rng and rng >= 0.5 * avgBody
            atr = ta.atr(14)
            mins = hour * 60 + minute
            canEnter = mins >= 555 and mins <= 870
            signal = ${condition(k)}
            var int held = 0
            if strategy.position_size != 0
                held := held + 1
            else
                held := 0
            if signal and canEnter and strategy.position_size == 0
                strategy.entry("J", $side)
                strategy.exit("JX", "J", stop = $stop, limit = $limit)
            if strategy.position_size != 0 and (held >= $HOLD or mins >= $cut)
                strategy.close("J")
        """.trimIndent()
    }

    /** A backtest's result, in index points (and in rupees when the app could price the options). */
    data class Result(
        val name: String, val kind: PatternKind, val market: Market, val minutes: Int, val script: String,
        val from: LocalDate?, val to: LocalDate?, val days: Int,
        val trades: Int, val winRate: Double, val netPoints: Double, val avgPoints: Double, val maxDrawdownPoints: Double,
        val firstHalfPoints: Double, val secondHalfPoints: Double,
        val rupees: Double? = null, val rupeeTrades: Int = 0,
        val recommended: Boolean, val verdict: String, val error: String? = null,
    ) {
        /** The result in a few plain lines, for the conversation. */
        fun summary(): String {
            if (error != null) return "I could not backtest $name: $error."
            val f = { x: Double -> "%+,.1f".format(Locale.ENGLISH, x) }
            val money = rupees?.let { " With real option prices on $rupeeTrades of the trades: ${"%+,.0f".format(Locale.ENGLISH, it).replace("+", "+Rs ").replace("-", "-Rs ")} for 1 lot after charges." } ?: ""
            return "Backtest of $name over $days days (${from} to ${to}): $trades trades, ${Math.round(winRate)}% won, " +
                "${f(netPoints)} index points in all (${f(avgPoints)} a trade), deepest fall ${f(-kotlin.math.abs(maxDrawdownPoints))} points. " +
                "First half ${f(firstHalfPoints)}, second half ${f(secondHalfPoints)}.$money $verdict"
        }
    }

    /**
     * Backtests pattern [k] on [m]'s [minutes]-minute chart over [bars] (1-minute candles, the market's clock). [premium]
     * (from the app) prices the trades with real option prices: (trades, bars) -> (rupees, priced trades), or null.
     */
    fun backtest(k: PatternKind, m: Market, minutes: Int, bars: List<Candle>,
                 premium: ((List<Pine.Trade>, List<Pine.Bar>) -> Pair<Double, Int>?)? = null): Result {
        val name = name(k, m, minutes)
        val script = runCatching { pine(k, m, minutes) }.getOrElse { return fail(name, k, m, minutes, "", it.message ?: "not supported") }
        val compiled = Pine.compile(script)
        if (compiled !is Pine.Compiled.Ok) return fail(name, k, m, minutes, script, "the strategy did not compile")
        val candles = Candles.fold(bars, minutes, m)
        if (candles.size < 50) return fail(name, k, m, minutes, script, "too few candles (${candles.size})")
        val pb = candles.map { Pine.Bar(it.t.atZone(IST).toEpochSecond(), it.o, it.h, it.l, it.c, 0.0) }
        val run = Pine.run(compiled.script, pb, symbol = m.name, interval = chart(minutes), qty = 1.0, budgetMs = 20_000)
        val rep = run.report ?: return fail(name, k, m, minutes, script, run.error?.message ?: "no report")
        val closed = rep.trades.filter { !it.open }
        val days = candles.map { it.t.toLocalDate() }.distinct()
        val mid = days.getOrNull(days.size / 2)?.atStartOfDay(IST)?.toEpochSecond() ?: Long.MAX_VALUE
        val first = closed.filter { it.entryTime < mid }.sumOf { it.pnl }
        val second = closed.filter { it.entryTime >= mid }.sumOf { it.pnl }
        val net = closed.sumOf { it.pnl }
        val money = runCatching { premium?.invoke(closed, pb) }.getOrNull()
        val recommended = closed.size >= MIN_TRADES && first > 0 && second > 0 && (money == null || money.first > 0)
        val verdict = when {
            closed.size < MIN_TRADES -> "Too few trades (${closed.size}, under $MIN_TRADES) to judge: not offered as an arm yet."
            first <= 0 || second <= 0 -> "It did not make money in both halves of the history: not recommended."
            money != null && money.first <= 0 -> "The index moved its way, but the options lost after charges: not recommended."
            else -> "It made money in both halves: worth a paper trial if you approve it."
        }
        return Result(name, k, m, minutes, script, days.firstOrNull(), days.lastOrNull(), days.size, closed.size,
            if (closed.isEmpty()) 0.0 else 100.0 * closed.count { it.pnl > 0 } / closed.size, net,
            if (closed.isEmpty()) 0.0 else net / closed.size, rep.maxDrawdown, first, second,
            money?.first, money?.second ?: 0, recommended, verdict)
    }

    private fun fail(name: String, k: PatternKind, m: Market, minutes: Int, script: String, why: String) =
        Result(name, k, m, minutes, script, null, null, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, recommended = false, verdict = "", error = why)
}
