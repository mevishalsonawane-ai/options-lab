package com.optionslab.engine.pine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Pine Script (v5 subset) on the phone: check a script, run it over candles, backtest a
 * strategy with TradingView's broker rules, and draw an indicator's plots.
 *
 * What runs: variables and `var`, `:=`, if / for / while, user functions (one line or a
 * block, tuples), history `x[n]`, inputs, the common `ta.*` and `math.*` functions,
 * `plot`, `hline`, `plotshape` (as chart markers), `alertcondition` (as a signal), and
 * `strategy.entry/exit/close/close_all/cancel`.
 * What does not: request.security (other symbols or timeframes), arrays, matrices, maps,
 * methods, user types, switch. Label/line/box drawings are accepted and not drawn.
 *
 * Broker rules (TradingView's defaults): an order placed on a bar fills at the next bar's
 * open (or on this bar's close with process_orders_on_close); strategy.exit stops and
 * targets fill inside later bars, the bar's path taken as open -> nearer extreme -> far
 * extreme -> close, and a gap past a level fills at the open. Opposite entries reverse.
 */
object Pine {
    data class Problem(val line: Int, val col: Int, val message: String) {
        override fun toString() = "Line $line:$col  $message"
    }
    enum class Kind { INDICATOR, STRATEGY }
    data class InputDef(val key: String, val kind: String, val default: Any?, val options: List<String>, val min: Double?, val max: Double?)
    data class PlotDef(val title: String, val color: String, val style: String)
    data class ShapeDef(val title: String, val color: String, val style: String, val location: String, val text: String)
    data class Settings(
        val initialCapital: Double = 1_000_000.0, val qtyType: String = "fixed", val qtyValue: Double = 1.0,
        val pyramiding: Int = 1, val commissionType: String = "percent", val commissionValue: Double = 0.0,
        val processOnClose: Boolean = false,
    )

    class Script internal constructor(
        val kind: Kind, val title: String, val overlay: Boolean,
        val inputs: List<InputDef>, val plots: List<PlotDef>, val shapes: List<ShapeDef>, val signals: List<String>,
        val settings: Settings, val warnings: List<Problem>,
        internal val prog: List<Stmt>, internal val plotIndex: Map<Int, Int>, internal val shapeIndex: Map<Int, Int>,
        internal val signalIndex: Map<Int, Int>, internal val inputIndex: Map<Int, InputDef>,
    )

    sealed class Compiled {
        class Ok(val script: Script) : Compiled()
        class Failed(val errors: List<Problem>, val warnings: List<Problem>) : Compiled()
    }

    /** One candle; [time] in epoch seconds. */
    data class Bar(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

    data class Marker(val bar: Int, val above: Boolean, val shape: String, val color: String, val text: String)

    data class Trade(
        val entryId: String, val exitId: String, val long: Boolean, val qty: Double,
        val entryBar: Int, val entryTime: Long, val entryPrice: Double,
        val exitBar: Int, val exitTime: Long, val exitPrice: Double,
        val pnl: Double, val pnlPct: Double, val commission: Double, val open: Boolean,
    )

    data class Report(
        val initialCapital: Double, val netProfit: Double, val netProfitPct: Double, val grossProfit: Double, val grossLoss: Double,
        val profitFactor: Double?, val closedTrades: Int, val winners: Int, val losers: Int, val winRate: Double,
        val avgTrade: Double, val avgWin: Double, val avgLoss: Double, val largestWin: Double, val largestLoss: Double,
        val maxDrawdown: Double, val maxDrawdownPct: Double, val buyHoldPct: Double, val commission: Double,
        val openPnl: Double, val avgBarsInTrade: Double, val trades: List<Trade>, val equity: DoubleArray,
        val extra: Extra? = null,
    )

    /** Longs or shorts on their own. */
    data class Side(val trades: Int, val winners: Int, val winRate: Double, val net: Double, val avg: Double)
    /** A day's or a month's closed trades. */
    data class Period(val label: String, val pnl: Double, val trades: Int, val winners: Int)

    /** Everything else a trader reads off a backtest. */
    data class Extra(
        val long: Side, val short: Side,
        val maxConsecWins: Int, val maxConsecLosses: Int,
        /** Average win over average loss. */
        val payoffRatio: Double?,
        /** Net profit over the largest drawdown. */
        val recoveryFactor: Double?,
        /** Annualised from daily equity changes (252 trading days); null with under 5 days. */
        val sharpe: Double?, val sortino: Double?,
        /** Share of candles with a position open. */
        val exposurePct: Double,
        val avgMinutesInTrade: Double, val tradesPerDay: Double,
        val tradingDays: Int, val winningDays: Int, val losingDays: Int,
        val bestDay: Period?, val worstDay: Period?,
        val days: List<Period>, val months: List<Period>,
        val returnPct: Double, val maxDrawdownDays: Int,
    )

    class Run(
        val plots: List<DoubleArray>, val markers: List<Marker>, val signals: List<BooleanArray>,
        /** Position size after each bar (strategies): + long, - short, 0 flat. */
        val position: DoubleArray,
        /** Where the position goes when the orders placed on the last bar fill (next open). */
        val nextPosition: Double,
        val report: Report?, val error: Problem?,
    )

    /** Largest script accepted, in characters and lines. */
    const val MAX_CHARS = 100_000
    const val MAX_LINES = 5_000

    fun compile(src: String): Compiled = try {
        compileChecked(src)
    } catch (e: StackOverflowError) {
        Compiled.Failed(listOf(Problem(1, 1, "The script is nested too deeply to check")), emptyList())
    } catch (e: OutOfMemoryError) {
        Compiled.Failed(listOf(Problem(1, 1, "The script is too large to check")), emptyList())
    } catch (e: RuntimeException) {
        Compiled.Failed(listOf(Problem(1, 1, "Could not check the script: ${e.message ?: e.javaClass.simpleName}")), emptyList())
    }

    private fun compileChecked(src: String): Compiled {
        if (src.length > MAX_CHARS) return Compiled.Failed(listOf(Problem(1, 1, "The script is too long (over ${MAX_CHARS / 1000} thousand characters)")), emptyList())
        if (src.count { it == '\n' } >= MAX_LINES) return Compiled.Failed(listOf(Problem(MAX_LINES, 1, "The script is too long (over $MAX_LINES lines)")), emptyList())
        val prog = try { Parser(Lexer.lex(src)).program() }
        catch (e: PineError) { return Compiled.Failed(listOf(Problem(e.line, e.col, e.message ?: "Syntax error")), emptyList()) }
        val ch = Checker(prog)
        ch.run()
        val warnings = ch.warnings.map { Problem(it.line, it.col, it.message ?: "") }
        if (ch.errors.isNotEmpty()) return Compiled.Failed(ch.errors.map { Problem(it.line, it.col, it.message ?: "") }.sortedBy { it.line }, warnings)
        val decl = ch.declaration!!
        val kind = if (decl.name == "strategy") Kind.STRATEGY else Kind.INDICATOR
        val sig = Builtins.SIGS[decl.name]!!
        fun arg(name: String): Expr? = argOf(decl, sig, name)
        val title = (arg("title")?.let { constOf(it) } as? String) ?: if (kind == Kind.STRATEGY) "Strategy" else "Indicator"
        val overlay = (arg("overlay")?.let { constOf(it) } as? Boolean) ?: false
        fun num(name: String): Double? = (arg(name)?.let { constOf(it) } as? Double)?.takeIf { it.isFinite() }
        // Out-of-range settings fall back to TradingView's defaults rather than breaking the maths.
        val settings = if (kind == Kind.STRATEGY) Settings(
            initialCapital = num("initial_capital")?.takeIf { it > 0 && it <= 1e13 } ?: 1_000_000.0,
            qtyType = (arg("default_qty_type")?.let { constOf(it) } as? String)?.takeIf { it in setOf("fixed", "cash", "percent_of_equity") } ?: "fixed",
            qtyValue = num("default_qty_value")?.takeIf { it > 0 && it <= 1e9 } ?: 1.0,
            pyramiding = (num("pyramiding") ?: 0.0).coerceIn(0.0, 100.0).toInt().coerceAtLeast(1),
            commissionType = (arg("commission_type")?.let { constOf(it) } as? String)?.takeIf { it in setOf("percent", "cash_per_order", "cash_per_contract") } ?: "percent",
            commissionValue = num("commission_value")?.takeIf { it >= 0 && it <= 1e6 } ?: 0.0,
            processOnClose = (arg("process_orders_on_close")?.let { constOf(it) } as? Boolean) ?: false,
        ) else Settings()

        val inputIndex = LinkedHashMap<Int, InputDef>()
        ch.inputCalls.forEachIndexed { i, c ->
            val s = Builtins.SIGS[c.name]!!
            val def = argOf(c, s, "defval")?.let { constOf(it) }
            val t = argOf(c, s, "title")?.let { constOf(it) } as? String
            val kindName = when (c.name) {
                "input" -> when (def) { is Boolean -> "bool"; is String -> if (argOf(c, s, "defval").let { it is Name }) "source" else "string"; else -> "float" }
                else -> c.name.removePrefix("input.")
            }
            val defVal = if (kindName == "source") (argOf(c, s, "defval") as? Name)?.name ?: "close" else def
            @Suppress("UNCHECKED_CAST")
            val opts = (argOf(c, s, "options") as? TupleLit)?.items?.mapNotNull { constOf(it)?.toString() } ?: emptyList()
            inputIndex[c.id] = InputDef(t ?: "Input ${i + 1}", kindName, defVal, opts,
                argOf(c, s, "minval")?.let { constOf(it) as? Double }, argOf(c, s, "maxval")?.let { constOf(it) as? Double })
        }
        val plots = ArrayList<PlotDef>(); val plotIndex = HashMap<Int, Int>()
        for (c in ch.plotCalls) {
            val s = Builtins.SIGS[c.name]!!
            val t = argOf(c, s, "title")?.let { constOf(it) } as? String ?: if (c.name == "hline") "Level" else "Plot ${plots.size + 1}"
            val color = colorOf(argOf(c, s, "color")) ?: PALETTE[plots.size % PALETTE.size]
            val style = if (c.name == "hline") "hline" else when ((argOf(c, s, "style") as? Name)?.name) {
                "plot.style_histogram" -> "histogram"; "plot.style_columns" -> "column"
                "plot.style_circles", "plot.style_cross" -> "points"; else -> "line"
            }
            plotIndex[c.id] = plots.size
            plots += PlotDef(t, color, style)
        }
        val shapes = ArrayList<ShapeDef>(); val shapeIndex = HashMap<Int, Int>()
        val signals = ArrayList<String>(); val signalIndex = HashMap<Int, Int>()
        fun walk(list: List<Stmt>) {
            for (st in list) {
                val calls = ArrayList<Call>()
                fun ex(e: Expr) {
                    when (e) {
                        is Call -> { calls += e; e.args.forEach { ex(it.value) } }
                        is Index -> { ex(e.target); ex(e.offset) }
                        is Unary -> ex(e.e); is Binary -> { ex(e.a); ex(e.b) }
                        is Ternary -> { ex(e.c); ex(e.a); ex(e.b) }
                        is TupleLit -> e.items.forEach { ex(it) }
                        is IfExpr -> walk(listOf(e.stmt))
                        else -> {}
                    }
                }
                when (st) {
                    is ExprStmt -> ex(st.e); is Decl -> ex(st.value); is Assign -> ex(st.value); is TupleDecl -> ex(st.value)
                    is If -> { ex(st.cond); walk(st.then); st.orElse?.let { walk(it) } }
                    is For -> walk(st.body); is While -> walk(st.body); else -> {}
                }
                for (c in calls) {
                    val s = Builtins.SIGS[c.name] ?: continue
                    when (c.name) {
                        "plotshape", "plotchar", "plotarrow" -> {
                            val t = argOf(c, s, "title")?.let { constOf(it) } as? String ?: "Shape ${shapes.size + 1}"
                            val style = (argOf(c, s, "style") as? Name)?.name?.removePrefix("shape.") ?: if (c.name == "plotarrow") "arrowup" else "circle"
                            val loc = (argOf(c, s, "location") as? Name)?.name?.removePrefix("location.") ?: "abovebar"
                            val text = argOf(c, s, "text")?.let { constOf(it) } as? String ?: (if (c.name == "plotchar") argOf(c, s, "char")?.let { constOf(it) } as? String else null) ?: ""
                            val color = colorOf(argOf(c, s, "color")) ?: PALETTE[(shapes.size + 3) % PALETTE.size]
                            if (shapes.size >= 64) continue
                            shapeIndex[c.id] = shapes.size
                            shapes += ShapeDef(t, color, style, loc, text)
                            signalIndex[c.id] = signals.size; signals += t
                        }
                        "alertcondition" -> {
                            if (signals.size >= 128) continue
                            val t = argOf(c, s, "title")?.let { constOf(it) } as? String ?: "Alert ${signals.size + 1}"
                            signalIndex[c.id] = signals.size; signals += t
                        }
                    }
                }
            }
        }
        walk(prog.filter { it !is FuncDef })
        return Compiled.Ok(Script(kind, title, overlay, inputIndex.values.toList(), plots, shapes, signals, settings, warnings,
            prog, plotIndex, shapeIndex, signalIndex, inputIndex))
    }

    /**
     * Run [script] over [bars]. [inputs] overrides inputs by their title. [interval] is the
     * chart code (1m, 5m, 1h, 1D ...). [qty] overrides the strategy's order size.
     */
    fun run(script: Script, bars: List<Bar>, inputs: Map<String, Any?> = emptyMap(), symbol: String = "NIFTY",
            interval: String = "5m", qty: Double? = null, mintick: Double = 0.05, budgetMs: Long = 10_000): Run =
        Interp(script, bars, inputs, symbol, interval, qty?.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(1e9), mintick, budgetMs).go()

    /**
     * Trade an indicator's signals: [buy] goes long, [sell] goes short ([reverse]) or just
     * exits. Orders fill at the next candle's open, as a strategy's would. [qty] units per trade.
     */
    fun signalBacktest(bars: List<Bar>, buy: BooleanArray, sell: BooleanArray, reverse: Boolean, qty: Double = 1.0,
                       capital: Double = 100_000.0): Report {
        val markers = ArrayList<Marker>()
        val br = Broker(Settings(initialCapital = capital.takeIf { it > 0 && it.isFinite() } ?: 100_000.0), qty.coerceIn(1e-9, 1e9), 0.05, bars, markers)
        for (i in bars.indices) {
            br.beforeBar(i)
            when {
                buy.getOrElse(i) { false } -> br.entry("Buy", true, null, null, null, i, false)
                sell.getOrElse(i) { false } -> if (reverse) br.entry("Sell", false, null, null, null, i, false) else br.close(null, i, false)
            }
            br.afterScript(i)
        }
        return br.report()
    }

    /** The extra statistics for a report, from its trades and equity curve. */
    fun extraOf(r: Report, bars: List<Bar>): Extra {
        val closed = r.trades.filter { !it.open }
        fun side(l: List<Trade>): Side {
            val w = l.count { it.pnl > 0 }
            return Side(l.size, w, if (l.isEmpty()) 0.0 else w * 100.0 / l.size, l.sumOf { it.pnl }, if (l.isEmpty()) 0.0 else l.sumOf { it.pnl } / l.size)
        }
        var cw = 0; var cl = 0; var mw = 0; var ml = 0
        for (t in closed) {
            if (t.pnl > 0) { cw++; cl = 0 } else if (t.pnl < 0) { cl++; cw = 0 } else { cw = 0; cl = 0 }
            mw = maxOf(mw, cw); ml = maxOf(ml, cl)
        }
        val day = { t: Long -> Instant.ofEpochSecond(t).atZone(IST).toLocalDate() }
        val byDay = closed.groupBy { day(it.exitTime) }.toSortedMap().map { (d, l) -> Period(d.toString(), l.sumOf { it.pnl }, l.size, l.count { it.pnl > 0 }) }
        val byMonth = closed.groupBy { day(it.exitTime).withDayOfMonth(1) }.toSortedMap()
            .map { (d, l) -> Period("%s %d".format(java.util.Locale.ENGLISH, d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, d.year),
                l.sumOf { it.pnl }, l.size, l.count { it.pnl > 0 }) }
        // Daily returns from the equity at each day's last candle.
        val closes = LinkedHashMap<java.time.LocalDate, Double>()
        bars.forEachIndexed { i, b -> if (i < r.equity.size) closes[day(b.time)] = r.equity[i] }
        val eq = listOf(r.initialCapital) + closes.values
        val rets = (1 until eq.size).map { if (eq[it - 1] != 0.0) eq[it] / eq[it - 1] - 1 else 0.0 }
        val mean = if (rets.isEmpty()) 0.0 else rets.average()
        val sd = if (rets.size < 2) 0.0 else kotlin.math.sqrt(rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1))
        val down = rets.filter { it < 0 }
        val dsd = if (down.isEmpty()) 0.0 else kotlin.math.sqrt(down.sumOf { it * it } / rets.size)
        val enough = rets.size >= 5
        val inTrade = BooleanArray(bars.size)
        for (t in r.trades) for (i in t.entryBar..minOf(t.exitBar, bars.size - 1)) if (i >= 0) inTrade[i] = true
        // Longest time under the previous equity peak, in calendar days.
        var peak = r.initialCapital; var peakAt = bars.firstOrNull()?.time ?: 0L; var longest = 0L
        bars.forEachIndexed { i, b ->
            if (i >= r.equity.size) return@forEachIndexed
            if (r.equity[i] >= peak) { peak = r.equity[i]; peakAt = b.time } else longest = maxOf(longest, b.time - peakAt)
        }
        val tradingDays = closes.size
        return Extra(
            long = side(closed.filter { it.long }), short = side(closed.filter { !it.long }),
            maxConsecWins = mw, maxConsecLosses = ml,
            payoffRatio = if (r.avgLoss < 0 && r.avgWin > 0) r.avgWin / -r.avgLoss else null,
            recoveryFactor = if (r.maxDrawdown > 0) r.netProfit / r.maxDrawdown else null,
            sharpe = if (enough && sd > 0) mean / sd * kotlin.math.sqrt(252.0) else null,
            sortino = if (enough && dsd > 0) mean / dsd * kotlin.math.sqrt(252.0) else null,
            exposurePct = if (bars.isEmpty()) 0.0 else inTrade.count { it } * 100.0 / bars.size,
            avgMinutesInTrade = if (closed.isEmpty()) 0.0 else closed.map { (it.exitTime - it.entryTime) / 60.0 }.average(),
            tradesPerDay = if (tradingDays == 0) 0.0 else closed.size.toDouble() / tradingDays,
            tradingDays = tradingDays, winningDays = byDay.count { it.pnl > 0 }, losingDays = byDay.count { it.pnl < 0 },
            bestDay = byDay.maxByOrNull { it.pnl }?.takeIf { it.pnl > 0 }, worstDay = byDay.minByOrNull { it.pnl }?.takeIf { it.pnl < 0 },
            days = byDay, months = byMonth,
            returnPct = (r.equity.lastOrNull()?.let { it / r.initialCapital - 1 } ?: 0.0) * 100,
            maxDrawdownDays = (longest / 86400).toInt(),
        )
    }

    /** A colour the chart can use: #RRGGBB or #RRGGBBAA, else null. */
    fun safeColor(c: String?): String? = c?.takeIf { COLOR.matches(it) }
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")

    internal val PALETTE = listOf("#2962FF", "#FF6D00", "#00897B", "#AB47BC", "#F23645", "#FBC02D", "#26C6DA", "#8D6E63")
    internal val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    internal fun argOf(c: Call, sig: Sig, name: String): Expr? {
        c.args.firstOrNull { it.name == name }?.let { return it.value }
        val i = sig.params.indexOf(name)
        if (i < 0) return null
        val positional = c.args.filter { it.name == null }
        return positional.getOrNull(i)?.value
    }

    /** A literal value, when the expression is one (declarations and inputs use these). */
    internal fun constOf(e: Expr): Any? = when (e) {
        is Num -> e.v; is Str -> e.v; is Bool -> e.v; is ColorLit -> e.v
        is Unary -> if (e.op == "-") (constOf(e.e) as? Double)?.let { -it } else constOf(e.e)
        is Name -> Builtins.CONSTS[e.name]
        is Binary -> {
            val a = constOf(e.a) as? Double; val b = constOf(e.b) as? Double
            if (a == null || b == null) null else when (e.op) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b == 0.0) null else a / b; else -> null }
        }
        else -> null
    }

    internal fun colorOf(e: Expr?): String? = when (e) {
        null -> null
        is ColorLit -> e.v.take(7)
        is Name -> Builtins.CONSTS[e.name] as? String
        is Call -> if (e.name == "color.new") e.args.firstOrNull()?.let { colorOf(it.value) }
            else if (e.name == "color.rgb") {
                val v = e.args.take(3).map { (constOf(it.value) as? Double)?.toInt()?.coerceIn(0, 255) }
                if (v.size == 3 && v.all { it != null }) String.format("#%02X%02X%02X", v[0], v[1], v[2]) else null
            } else null
        is Ternary -> colorOf(e.a) ?: colorOf(e.b)
        else -> null
    }

    internal fun weekOfYear(t: Long) = Instant.ofEpochSecond(t).atZone(IST).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}
