package com.optionslab.engine.pine

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val NA = Double.NaN
private fun box(d: Double): Any? = if (d.isNaN() || d.isInfinite()) null else d
private fun num(v: Any?): Double = when (v) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; else -> NA }
private fun truthy(v: Any?): Boolean = when (v) { is Boolean -> v; is Double -> !v.isNaN() && v != 0.0; else -> false }
private fun str(v: Any?): String = when (v) {
    null -> "NaN"
    is Double -> if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else String.format(java.util.Locale.ENGLISH, "%.4f", v).trimEnd('0').trimEnd('.')
    else -> v.toString()
}

private class BreakSignal : RuntimeException(null, null, false, false)
private class ContinueSignal : RuntimeException(null, null, false, false)
private val BREAK = BreakSignal()
private val CONTINUE = ContinueSignal()

/** A series kept by calls: put() once per bar (a second call on the same bar replaces it). */
private class H {
    val v = ArrayList<Double>()
    private var last = -1
    fun put(bar: Int, x: Double) { if (last == bar && v.isNotEmpty()) v[v.size - 1] = x else { v.add(x); last = bar } }
    fun get(k: Int): Double = if (k >= 0 && k < v.size) v[v.size - 1 - k] else NA
    val size get() = v.size
    fun window(len: Int): DoubleArray? = if (len <= 0 || v.size < len) null else DoubleArray(len) { v[v.size - len + it] }
}

/** A recursive value (ema, cum ...): the value it had on the previous bar, and this bar's. */
private class R {
    private var bar = -1
    var prev = NA; var cur = NA
    inline fun step(b: Int, f: (Double) -> Double): Double { if (b != bar) { prev = cur; bar = b }; cur = f(prev); return cur }
}

private class Avg(val kind: Char) {            // 'e' ema, 'r' rma
    val h = H(); val r = R()
    fun step(b: Int, x: Double, len: Int): Double {
        h.put(b, x)
        val alpha = if (kind == 'e') 2.0 / (len + 1) else 1.0 / len
        return r.step(b) { prev ->
            if (prev.isNaN()) h.window(len)?.let { w -> if (w.any { it.isNaN() }) NA else w.average() } ?: NA
            else if (x.isNaN()) prev else alpha * x + (1 - alpha) * prev
        }
    }
}

private fun wmaOf(h: H, len: Int): Double {
    val w = h.window(len) ?: return NA
    var s = 0.0; var norm = 0.0
    for (i in 0 until len) { val wt = (i + 1).toDouble(); s += w[i] * wt; norm += wt }
    return s / norm
}

private class RsiS { val h = H(); val up = Avg('r'); val dn = Avg('r') }
private class StS { var bar = -1; val prev = DoubleArray(5) { NA }; val cur = DoubleArray(5) { NA }; val atr = Avg('r') }
private class DmiS { val tr = Avg('r'); val p = Avg('r'); val m = Avg('r'); val adx = Avg('r') }

private class Slot(var v: Any?) { val hist = ArrayList<Any?>(); var first = -1 }
private class Scope(val parent: Scope?) { val vars = HashMap<String, Slot>() }

/** Values kept per bar for `expr[n]` on anything that is not a global variable. */
private class G {
    val v = ArrayList<Any?>()
    fun set(b: Int, x: Any?) { while (v.size < b) v.add(null); if (v.size == b) v.add(x) else v[b] = x }
    fun at(b: Int): Any? = if (b >= 0 && b < v.size) v[b] else null
}

private object Missing

/** A Pine array: a list of values, shared by reference like TradingView's. */
internal class PArr(val v: ArrayList<Any?>) { override fun toString() = v.joinToString(", ", "[", "]") }

/** Most items one array may hold. */
private const val MAX_ITEMS = 100_000

/** Longest text a script may build. */
private const val MAX_TEXT = 4_000
/** Longest lookback a ta.* function may use (TradingView's own limit is 5000 bars). */
private const val MAX_LEN = 5_000

internal class Interp(
    private val sc: Pine.Script, private val chartBars: List<Pine.Bar>, private val overrides: Map<String, Any?>,
    private val symbol: String, private val chartInterval: String, qty: Double?, private val mintick: Double,
    private val budgetMs: Long = 10_000,
    costs: Pine.Costs = Pine.Costs(),
) {
    /** A script that runs past its time budget stops with an error (an endless loop must not hang the app). */
    private val deadline = System.nanoTime() + budgetMs * 1_000_000
    private var steps = 0L
    private fun tick(line: Int, col: Int) {
        if ((++steps and 1023L) == 0L && System.nanoTime() > deadline)
            fail(line, col, "The script takes too long (over ${budgetMs / 1000.0} s): simplify its loops")
    }
    // The candles the script is running on: the chart's, or a higher timeframe's inside request.security.
    private var bars = chartBars
    private var n = chartBars.size
    private var interval = chartInterval
    private var htf = false
    private var bar = 0
    private val plots = List(sc.plots.size) { DoubleArray(chartBars.size) { NA } }
    private val markers = ArrayList<Pine.Marker>()
    private val signals = List(sc.signals.size) { BooleanArray(chartBars.size) }
    private val position = DoubleArray(chartBars.size)
    private val global = Scope(null)
    private val funcs = sc.prog.filterIsInstance<FuncDef>().associateBy { it.name }
    private val state = HashMap<String, Any>()
    private var path = ""
    private var depth = 0
    private var loopOps = 0L
    private val broker = if (sc.kind == Pine.Kind.STRATEGY) Broker(sc.settings, qty, mintick, chartBars, markers, costs) else null
    private val top = sc.prog.filter { it !is FuncDef }

    private fun fail(line: Int, col: Int, msg: String): Nothing = throw PineError(line, col, msg)

    fun go(): Pine.Run {
        var error: Pine.Problem? = null
        var cur = 0
        try {
            for (i in chartBars.indices) {
                bar = i; loopOps = 0; cur = i
                broker?.beforeBar(i)
                for (s in top) exec(s, global)
                broker?.afterScript(i)
                for (s in global.vars.values) {
                    if (s.first < 0) s.first = i
                    while (s.first + s.hist.size < i) s.hist.add(null)
                    s.hist.add(s.v)
                }
                position[i] = broker?.size() ?: 0.0
            }
        } catch (e: PineError) {
            error = Pine.Problem(e.line, e.col, "Bar ${bar + 1}: ${e.message}")
        } catch (e: StackOverflowError) {
            error = Pine.Problem(0, 0, "The script recursed too deeply")
        } catch (e: OutOfMemoryError) {
            error = Pine.Problem(0, 0, "The script used too much memory at bar ${bar + 1}")
        } catch (e: RuntimeException) {
            error = Pine.Problem(0, 0, "Stopped at bar ${bar + 1}: ${e.message ?: e.javaClass.simpleName}")
        }
        if (error != null) broker?.halt(cur)
        val report = broker?.report()
        return Pine.Run(plots, markers, signals, position, broker?.nextPosition() ?: 0.0, report, error)
    }

    // ---- statements ----------------------------------------------------------------------

    private fun lookup(name: String, scope: Scope): Slot? {
        var s: Scope? = scope
        while (s != null) { s.vars[name]?.let { return it }; s = s.parent }
        return null
    }

    private fun block(list: List<Stmt>, scope: Scope): Any? {
        var last: Any? = null
        for (s in list) last = exec(s, scope)
        return last
    }

    private fun exec(s: Stmt, scope: Scope): Any? {
        tick(s.line, s.col)
        return when (s) {
            is Decl -> {
                if (s.persistent) {
                    if (scope === global) {
                        val have = global.vars[s.name]
                        if (have != null) return have.v
                        val v = eval(s.value, scope)
                        global.vars[s.name] = Slot(v); return v
                    }
                    val key = path + "v" + s.line + ":" + s.col
                    val slot = state.getOrPut(key) { Slot(eval(s.value, scope)) } as Slot
                    scope.vars[s.name] = slot
                    return slot.v
                }
                val v = eval(s.value, scope)
                val slot = scope.vars[s.name]
                if (slot != null && scope === global) slot.v = v else scope.vars[s.name] = Slot(v)
                return v
            }
            is TupleDecl -> {
                val v = eval(s.value, scope)
                val list = v as? List<*> ?: fail(s.line, s.col, "The right side does not return ${s.names.size} values")
                if (list.size < s.names.size) fail(s.line, s.col, "Expected ${s.names.size} values, got ${list.size}")
                s.names.forEachIndexed { i, nm ->
                    val slot = scope.vars[nm]
                    if (slot != null && scope === global) slot.v = list[i] else scope.vars[nm] = Slot(list[i])
                }
                return v
            }
            is Assign -> {
                val slot = lookup(s.name, scope) ?: fail(s.line, s.col, "Undeclared identifier '${s.name}'")
                val v = eval(s.value, scope)
                slot.v = if (s.op == ":=") v else arith(s.op.dropLast(1), slot.v, v, s.line, s.col)
                return slot.v
            }
            is If -> {
                return if (truthy(eval(s.cond, scope))) block(s.then, Scope(scope))
                else s.orElse?.let { block(it, Scope(scope)) }
            }
            is For -> {
                val a = num(eval(s.from, scope)); val b = num(eval(s.to, scope))
                if (a.isNaN() || b.isNaN()) return null
                val step = s.by?.let { num(eval(it, scope)) } ?: if (b >= a) 1.0 else -1.0
                if (step == 0.0 || step.isNaN()) fail(s.line, s.col, "The loop step cannot be 0")
                val loop = Scope(scope)
                val iv = Slot(a); loop.vars[s.v] = iv
                var i = a; var last: Any? = null
                while (if (step > 0) i <= b else i >= b) {
                    if (++loopOps > 500_000) fail(s.line, s.col, "This loop takes too long (over 500,000 steps on one bar)")
                    iv.v = i
                    try { last = block(s.body, Scope(loop)) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
                    i += step
                }
                return last
            }
            is Switch -> {
                val subj = s.subject?.let { eval(it, scope) }
                for ((c, body) in s.cases) {
                    val hit = when {
                        c == null -> true
                        s.subject != null -> { val v = eval(c, scope); v != null && subj != null && (if (v is Double || subj is Double) num(v) == num(subj) else v == subj) }
                        else -> truthy(eval(c, scope))
                    }
                    if (hit) return block(body, Scope(scope))
                }
                return null
            }
            is ForIn -> {
                val arr = eval(s.over, scope) as? PArr ?: fail(s.line, s.col, "for...in needs an array")
                val loop = Scope(scope)
                val item = Slot(null); loop.vars[s.item] = item
                val idx = s.index?.let { Slot(null).also { sl -> loop.vars[it] = sl } }
                var last: Any? = null
                // A copy: the body may change the array.
                val items = ArrayList(arr.v)
                for ((i, x) in items.withIndex()) {
                    if (++loopOps > 500_000) fail(s.line, s.col, "This loop takes too long (over 500,000 steps on one bar)")
                    item.v = x; idx?.v = i.toDouble()
                    try { last = block(s.body, Scope(loop)) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
                }
                return last
            }
            is While -> {
                var last: Any? = null
                while (truthy(eval(s.cond, scope))) {
                    if (++loopOps > 500_000) fail(s.line, s.col, "This loop takes too long (over 500,000 steps on one bar)")
                    try { last = block(s.body, Scope(scope)) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
                }
                return last
            }
            is Jump -> throw if (s.brk) BREAK else CONTINUE
            is ExprStmt -> return eval(s.e, scope)
            is FuncDef -> return null
        }
    }

    // ---- expressions ---------------------------------------------------------------------

    private fun eval(e: Expr, scope: Scope): Any? = when (e) {
        is Num -> e.v
        is Str -> e.v
        is Bool -> e.v
        is ColorLit -> e.v
        is Name -> name(e, scope)
        is Unary -> when (e.op) {
            "-" -> box(-num(eval(e.e, scope)))
            "+" -> eval(e.e, scope)
            else -> !truthy(eval(e.e, scope))
        }
        is Binary -> binary(e, scope)
        is Ternary -> if (truthy(eval(e.c, scope))) eval(e.a, scope) else eval(e.b, scope)
        is TupleLit -> e.items.map { eval(it, scope) }
        is IfExpr -> exec(e.stmt, scope)
        is Index -> index(e, scope)
        is Call -> call(e, scope)
    }

    private fun binary(e: Binary, scope: Scope): Any? {
        when (e.op) {
            "and" -> return truthy(eval(e.a, scope)) && truthy(eval(e.b, scope))
            "or" -> return truthy(eval(e.a, scope)) || truthy(eval(e.b, scope))
        }
        val a = eval(e.a, scope); val b = eval(e.b, scope)
        return when (e.op) {
            "==", "!=" -> {
                if (a == null || b == null) false
                else {
                    val eq = if (a is Double || b is Double) num(a) == num(b) else a == b
                    if (e.op == "==") eq else !eq
                }
            }
            "<", ">", "<=", ">=" -> {
                if (a is String && b is String) { val c = a.compareTo(b); when (e.op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 } }
                else {
                    val x = num(a); val y = num(b)
                    if (x.isNaN() || y.isNaN()) false else when (e.op) { "<" -> x < y; ">" -> x > y; "<=" -> x <= y; else -> x >= y }
                }
            }
            else -> arith(e.op, a, b, e.line, e.col)
        }
    }

    private fun arith(op: String, a: Any?, b: Any?, line: Int, col: Int): Any? {
        if (op == "+" && (a is String || b is String)) {
            if (a == null || b == null) return null
            val sa = str(a); val sb = str(b)
            if (sa.length + sb.length > MAX_TEXT) fail(line, col, "Text longer than $MAX_TEXT characters")
            return sa + sb
        }
        if (a is String || b is String) fail(line, col, "Cannot use '$op' on text")
        val x = num(a); val y = num(b)
        return box(when (op) {
            "+" -> x + y; "-" -> x - y; "*" -> x * y
            "/" -> if (y == 0.0) NA else x / y
            "%" -> if (y == 0.0) NA else x % y
            else -> fail(line, col, "Unknown operator $op")
        })
    }

    private fun index(e: Index, scope: Scope): Any? {
        val off = num(eval(e.offset, scope))
        if (off.isNaN()) return null
        if (off < 0) fail(e.line, e.col, "A history reference cannot look into the future ([${off.toInt()}])")
        val k = off.toInt()
        val t = e.target
        if (t is Name) {
            val local = lookupLocal(t.name, scope)
            if (local == null) {
                val g = global.vars[t.name]
                if (g != null) {
                    if (k == 0) return g.v
                    val at = bar - k - g.first
                    return if (g.first >= 0 && at >= 0 && at < g.hist.size) g.hist[at] else null
                }
                if (t.name in SERIES_AT) return seriesAt(t.name, bar - k)
            }
        }
        val key = path + "h" + e.id
        val h = state.getOrPut(key) { G() } as G
        h.set(bar, eval(t, scope))
        return h.at(bar - k)
    }

    /** A variable from a local scope (a function or a block), not a global. */
    private fun lookupLocal(name: String, scope: Scope): Slot? {
        var s: Scope? = scope
        while (s != null && s !== global) { s.vars[name]?.let { return it }; s = s.parent }
        return null
    }

    private val SERIES_AT = setOf("open", "high", "low", "close", "volume", "hl2", "hlc3", "ohlc4", "hlcc4", "time", "bar_index")

    private fun seriesAt(name: String, i: Int): Any? {
        if (i < 0 || i >= n) return null
        val b = bars[i]
        return when (name) {
            "open" -> b.open; "high" -> b.high; "low" -> b.low; "close" -> b.close; "volume" -> b.volume
            "hl2" -> (b.high + b.low) / 2; "hlc3" -> (b.high + b.low + b.close) / 3
            "ohlc4" -> (b.open + b.high + b.low + b.close) / 4; "hlcc4" -> (b.high + b.low + 2 * b.close) / 4
            "time" -> b.time * 1000.0; "bar_index" -> i.toDouble()
            else -> null
        }
    }

    private fun zoned(i: Int): ZonedDateTime = Instant.ofEpochSecond(bars[i].time).atZone(Pine.IST)

    private fun name(e: Name, scope: Scope): Any? {
        lookup(e.name, scope)?.let { return it.v }
        seriesAt(e.name, bar)?.let { return it }
        val b = bars[bar]
        return when (e.name) {
            "na" -> null
            "time_close", "last_bar_time" -> (if (e.name == "last_bar_time") bars[n - 1].time else b.time + stepSeconds()) * 1000.0
            "time_tradingday" -> zoned(bar).toLocalDate().atStartOfDay(Pine.IST).toEpochSecond() * 1000.0
            "last_bar_index" -> (n - 1).toDouble()
            "year" -> zoned(bar).year.toDouble(); "month" -> zoned(bar).monthValue.toDouble()
            "dayofmonth" -> zoned(bar).dayOfMonth.toDouble()
            "dayofweek" -> (zoned(bar).dayOfWeek.value % 7 + 1).toDouble()
            "hour" -> zoned(bar).hour.toDouble(); "minute" -> zoned(bar).minute.toDouble(); "second" -> zoned(bar).second.toDouble()
            "weekofyear" -> Pine.weekOfYear(b.time).toDouble()
            "timenow" -> System.currentTimeMillis().toDouble()
            "barstate.isfirst" -> bar == 0
            "barstate.islast", "barstate.islastconfirmedhistory" -> bar == n - 1
            "barstate.ishistory", "barstate.isnew", "barstate.isconfirmed" -> true
            "barstate.isrealtime" -> false
            "syminfo.ticker", "syminfo.root", "syminfo.description" -> symbol
            "syminfo.tickerid" -> "NSE:$symbol"; "syminfo.prefix" -> "NSE"
            "syminfo.mintick" -> mintick; "syminfo.pointvalue" -> 1.0
            "syminfo.timezone" -> "Asia/Kolkata"; "syminfo.currency" -> "INR"; "syminfo.type" -> "index"; "syminfo.session" -> "regular"
            "timeframe.period" -> tfPeriod(); "timeframe.multiplier" -> tfMultiplier()
            "timeframe.isintraday" -> stepSeconds() < 86400; "timeframe.isdaily" -> tfPeriod() == "D"
            "timeframe.isweekly" -> tfPeriod() == "W"; "timeframe.ismonthly" -> tfPeriod() == "M"
            "timeframe.isminutes" -> stepSeconds() < 86400; "timeframe.isseconds" -> false
            "timeframe.isdwm" -> stepSeconds() >= 86400
            "math.pi" -> Math.PI; "math.e" -> Math.E; "math.phi" -> 1.618033988749895
            "ta.tr" -> box(tr(false))
            "ta.vwap" -> vwap(path + "n" + e.id, (b.high + b.low + b.close) / 3)
            "ta.obv" -> { val r = st(path + "n" + e.id) { R() }; val c1 = if (bar > 0) bars[bar - 1].close else NA
                box(r.step(bar) { p -> (if (p.isNaN()) 0.0 else p) + (if (c1.isNaN()) 0.0 else Math.signum(b.close - c1) * b.volume) }) }
            "ta.accdist" -> { val r = st(path + "n" + e.id) { R() }
                val mf = if (b.high == b.low) 0.0 else ((b.close - b.low) - (b.high - b.close)) / (b.high - b.low) * b.volume
                box(r.step(bar) { p -> (if (p.isNaN()) 0.0 else p) + mf }) }
            else -> {
                if (e.name.startsWith("strategy.") && broker != null) broker.value(e.name)?.let { return it }
                if (e.name.startsWith("strategy.")) return if (e.name in Builtins.CONSTS) Builtins.CONSTS[e.name] else null
                Builtins.CONSTS[e.name] ?: e.name                   // styling constants are their own names
            }
        }
    }

    private fun stepSeconds(): Long = tfSeconds(interval)

    /** Seconds in a timeframe: Pine's ("5", "60", "D", "W", "M") or the chart's ("5m", "1h", "1D"). */
    private fun tfSeconds(tf: String): Long {
        val m = Regex("^(\\d*)([a-zA-Z]?)$").find(tf.trim()) ?: return 300
        val k = m.groupValues[1].ifEmpty { "1" }.toLongOrNull()?.coerceIn(1, 100_000) ?: 1
        return k * when (m.groupValues[2]) {
            "", "m" -> 60L; "h", "H" -> 3600L; "d", "D" -> 86400L; "w", "W" -> 604800L; "M" -> 2592000L; "s", "S" -> 1L; else -> 60L
        }
    }

    /**
     * The start (epoch seconds) of the [tf] candle holding time [t]: days, weeks (Monday) and
     * months in IST; minute and hour candles counted from the 09:15 open.
     */
    private fun bucketStart(t: Long, tf: String): Long {
        val z = Instant.ofEpochSecond(t).atZone(Pine.IST)
        val secs = tfSeconds(tf)
        val day = z.toLocalDate()
        return when {
            secs >= 2592000 -> day.withDayOfMonth(1).atStartOfDay(Pine.IST).toEpochSecond()
            secs >= 604800 -> day.minusDays((day.dayOfWeek.value - 1).toLong()).atStartOfDay(Pine.IST).toEpochSecond()
            secs >= 86400 -> day.atStartOfDay(Pine.IST).toEpochSecond()
            else -> {
                val open = day.atStartOfDay(Pine.IST).toEpochSecond() + 555 * 60
                open + Math.floorDiv(t - open, secs) * secs
            }
        }
    }

    // ---- request.security: the same symbol on a higher timeframe --------------------------

    private class Sec(val vals: Array<Any?>, val groupOf: IntArray, val last: IntArray, val lookahead: Boolean)
    private object SameTf

    private fun security(c: Call, scope: Scope): Any? {
        if (htf) fail(c.line, c.col, "request.security cannot be nested")
        val sig = Builtins.SIGS[c.name]!!
        val key = path + "sec" + c.id
        val done = state[key]
        val expr = Pine.argOf(c, sig, "expression") ?: fail(c.line, c.col, "request.security needs an expression")
        if (done === SameTf) return eval(expr, scope)
        if (done is Sec) return pick(done)
        val sym = Pine.argOf(c, sig, "symbol")?.let { eval(it, scope) }?.toString()?.trim().orEmpty()
        val bare = sym.substringAfter(':').uppercase()
        if (sym.isNotEmpty() && bare != symbol.uppercase())
            fail(c.line, c.col, "request.security on another symbol ($sym) is not supported: only $symbol")
        val tf = Pine.argOf(c, sig, "timeframe")?.let { eval(it, scope) }?.toString()?.trim().orEmpty()
        val look = Pine.argOf(c, sig, "lookahead")?.let { eval(it, scope) }.let { it == true || it.toString().endsWith("lookahead_on") }
        val step = stepSeconds()
        val tfs = if (tf.isEmpty()) step else tfSeconds(tf)
        if (tfs < step) fail(c.line, c.col, "request.security to a lower timeframe ($tf) than the chart's is not supported")
        if (tfs == step) { state[key] = SameTf; return eval(expr, scope) }
        // Group the chart's candles into the higher timeframe's.
        val groupOf = IntArray(chartBars.size)
        val lastIdx = ArrayList<Int>()
        val hb = ArrayList<Pine.Bar>()
        var cur = Long.MIN_VALUE
        for ((i, b) in chartBars.withIndex()) {
            val k = bucketStart(b.time, tf)
            if (k != cur) {
                cur = k
                hb += Pine.Bar(b.time, b.open, b.high, b.low, b.close, b.volume); lastIdx += i
            } else {
                val h = hb.last()
                hb[hb.size - 1] = Pine.Bar(h.time, h.open, max(h.high, b.high), min(h.low, b.low), b.close, h.volume + b.volume)
                lastIdx[lastIdx.size - 1] = i
            }
            groupOf[i] = hb.size - 1
        }
        val vals = arrayOfNulls<Any?>(hb.size)
        val saved = arrayOf<Any?>(bars, n, bar, path, interval)
        bars = hb; n = hb.size; interval = tf; htf = true; path = path + "S" + c.id + "/"
        try {
            for (i in 0 until n) { bar = i; vals[i] = eval(expr, scope) }
        } finally {
            @Suppress("UNCHECKED_CAST")
            bars = saved[0] as List<Pine.Bar>; n = saved[1] as Int; bar = saved[2] as Int; path = saved[3] as String; interval = saved[4] as String
            htf = false
        }
        val sec = Sec(vals, groupOf, lastIdx.toIntArray(), look)
        state[key] = sec
        return pick(sec)
    }

    /** No look-ahead: a higher-timeframe value counts from the chart candle that completes it. */
    private fun pick(s: Sec): Any? {
        val g = s.groupOf[bar]
        return if (s.lookahead || s.last[g] == bar) s.vals[g] else if (g > 0) s.vals[g - 1] else null
    }

    // ---- arrays -------------------------------------------------------------------------------

    private fun same(x: Any?, y: Any?) = if (x is Double || y is Double) num(x) == num(y) else x == y

    private fun array(c: Call, a: List<Any?>): Any? {
        val f = c.name.removePrefix("array.")
        fun arr(i: Int = 0): PArr = a.getOrNull(i) as? PArr ?: fail(c.line, c.col, "${c.name}(): expected an array")
        fun cap(p: PArr): PArr { if (p.v.size > MAX_ITEMS) fail(c.line, c.col, "An array may hold at most $MAX_ITEMS items"); return p }
        fun idx(p: PArr, x: Any?): Int {
            val d = num(x); if (d.isNaN()) fail(c.line, c.col, "${c.name}(): the index is na")
            var i = d.toInt(); if (i < 0) i += p.v.size
            if (i !in p.v.indices) fail(c.line, c.col, "${c.name}(): index ${d.toInt()} is out of range (the array has ${p.v.size})")
            return i
        }
        fun nums(p: PArr) = p.v.map { num(it) }.filter { !it.isNaN() }
        return when (f) {
            "new", "new_float", "new_int", "new_bool", "new_string", "new_color" -> {
                val size = a.getOrNull(0)?.let { num(it) }?.takeIf { !it.isNaN() }?.toInt() ?: 0
                if (size < 0 || size > MAX_ITEMS) fail(c.line, c.col, "An array may hold 0 to $MAX_ITEMS items")
                PArr(ArrayList<Any?>(size).apply { repeat(size) { add(a.getOrNull(1)) } })
            }
            "from" -> cap(PArr(ArrayList(a)))
            "size" -> arr().v.size.toDouble()
            "get" -> arr().let { it.v[idx(it, a.getOrNull(1))] }
            "set" -> { arr().let { it.v[idx(it, a.getOrNull(1))] = a.getOrNull(2) }; null }
            "push" -> { cap(arr()).v.add(a.getOrNull(1)); cap(arr()); null }
            "unshift" -> { arr().v.add(0, a.getOrNull(1)); cap(arr()); null }
            "insert" -> { val p = arr(); val i = num(a.getOrNull(1)).let { if (it.isNaN()) 0 else it.toInt() }.coerceIn(0, p.v.size); p.v.add(i, a.getOrNull(2)); cap(p); null }
            "pop" -> arr().let { if (it.v.isEmpty()) fail(c.line, c.col, "${c.name}(): the array is empty") else it.v.removeAt(it.v.size - 1) }
            "shift" -> arr().let { if (it.v.isEmpty()) fail(c.line, c.col, "${c.name}(): the array is empty") else it.v.removeAt(0) }
            "remove" -> arr().let { it.v.removeAt(idx(it, a.getOrNull(1))) }
            "clear" -> { arr().v.clear(); null }
            "sum" -> box(nums(arr()).sum())
            "avg" -> nums(arr()).let { if (it.isEmpty()) null else box(it.average()) }
            "min", "max" -> {
                val l = nums(arr()).sorted().let { if (f == "max") it.reversed() else it }
                val k = a.getOrNull(1)?.let { num(it).toInt() } ?: 0
                l.getOrNull(k)?.let { box(it) }
            }
            "range" -> nums(arr()).let { if (it.isEmpty()) null else box(it.max() - it.min()) }
            "stdev", "variance" -> {
                val l = nums(arr()); if (l.isEmpty()) return null
                val m = l.average(); val biased = a.getOrNull(1)?.let { truthy(it) } ?: true
                val v = l.sumOf { (it - m) * (it - m) } / (if (biased || l.size < 2) l.size else l.size - 1)
                box(if (f == "stdev") sqrt(v) else v)
            }
            "median" -> nums(arr()).sorted().let { if (it.isEmpty()) null else box(if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2) }
            "mode" -> nums(arr()).groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key?.let { box(it) }
            "includes" -> arr().v.any { same(it, a.getOrNull(1)) }
            "indexof" -> arr().v.indexOfFirst { same(it, a.getOrNull(1)) }.toDouble()
            "lastindexof" -> arr().v.indexOfLast { same(it, a.getOrNull(1)) }.toDouble()
            "first" -> arr().v.firstOrNull() ?: if (arr().v.isEmpty()) fail(c.line, c.col, "${c.name}(): the array is empty") else null
            "last" -> arr().v.lastOrNull() ?: if (arr().v.isEmpty()) fail(c.line, c.col, "${c.name}(): the array is empty") else null
            "slice" -> {
                val p = arr()
                val from = num(a.getOrNull(1)).toInt().coerceIn(0, p.v.size); val to = num(a.getOrNull(2)).toInt().coerceIn(from, p.v.size)
                PArr(ArrayList(p.v.subList(from, to)))
            }
            "copy" -> PArr(ArrayList(arr().v))
            "sort" -> {
                val p = arr(); val desc = a.getOrNull(1)?.toString()?.endsWith("descending") == true
                if (p.v.all { it is String }) p.v.sortBy { it as String } else p.v.sortBy { num(it).let { d -> if (d.isNaN()) Double.MAX_VALUE else d } }
                if (desc) p.v.reverse()
                null
            }
            "reverse" -> { arr().v.reverse(); null }
            "fill" -> {
                val p = arr()
                val from = a.getOrNull(2)?.let { num(it).toInt() }?.coerceIn(0, p.v.size) ?: 0
                val to = a.getOrNull(3)?.let { num(it).toInt() }?.coerceIn(from, p.v.size) ?: p.v.size
                for (i in from until to) p.v[i] = a.getOrNull(1)
                null
            }
            "concat" -> { val p = arr(); p.v.addAll(arr(1).v); cap(p) }
            "join" -> {
                val sb = StringBuilder(); val sep = a.getOrNull(1)?.toString() ?: ","
                for ((i, x) in arr().v.withIndex()) { if (i > 0) sb.append(sep); sb.append(str(x)); if (sb.length > MAX_TEXT) fail(c.line, c.col, "Text longer than $MAX_TEXT characters") }
                sb.toString()
            }
            else -> fail(c.line, c.col, "${c.name}() is not supported yet")
        }
    }

    private fun tfPeriod(): String {
        val s = stepSeconds()
        return when { s >= 2592000 -> "M"; s >= 604800 -> "W"; s >= 86400 -> "D"; else -> (s / 60).toString() }
    }
    private fun tfMultiplier(): Double { val s = stepSeconds(); return if (s < 86400) (s / 60).toDouble() else 1.0 }

    @Suppress("UNCHECKED_CAST")
    private inline fun <T : Any> st(key: String, make: () -> T): T = state.getOrPut(key, make) as T

    // ---- calls ---------------------------------------------------------------------------

    private fun call(c: Call, scope: Scope): Any? {
        funcs[c.name]?.let { return callUser(it, c, scope) }
        if (c.name == "request.security" || c.name == "security") return security(c, scope)
        val sig = Builtins.SIGS[c.name]
        if (sig == null) {
            // arr.push(x) and friends: a method call on an array variable.
            val dot = c.name.indexOf('.')
            if (dot > 0) {
                val holder = lookup(c.name.substring(0, dot), scope)
                if (holder != null) {
                    val fn = "array." + c.name.substring(dot + 1)
                    if (Builtins.SIGS.containsKey(fn)) return array(Call(fn, listOf(Arg(null, Name(c.name.substring(0, dot), c.line, c.col))) + c.args, c.line, c.col),
                        listOf(holder.v) + c.args.map { eval(it.value, scope) })
                }
            }
            val ns = c.name.substringBeforeLast('.', "")
            if (ns in Builtins.DRAWING_NS || c.name.startsWith("strategy.risk.") || c.name.startsWith("chart.point")) {
                c.args.forEach { eval(it.value, scope) }; return null
            }
            fail(c.line, c.col, "Unknown function '${c.name}'")
        }
        if (c.name.startsWith("array.")) return array(c, c.args.map { eval(it.value, scope) })
        if (sig.variadic) return builtinVar(c, c.args.map { eval(it.value, scope) }, scope)
        val a = arrayOfNulls<Any?>(sig.params.size)
        java.util.Arrays.fill(a, Missing)
        var pos = 0
        for (arg in c.args) {
            val i = if (arg.name != null) sig.params.indexOf(arg.name) else pos++
            if (i >= 0 && i < a.size) a[i] = eval(arg.value, scope)
        }
        return builtin(c, a, scope)
    }

    private fun callUser(f: FuncDef, c: Call, scope: Scope): Any? {
        tick(c.line, c.col)
        val fs = Scope(global)
        val given = arrayOfNulls<Any?>(f.params.size); val set = BooleanArray(f.params.size)
        var pos = 0
        for (arg in c.args) {
            val i = if (arg.name != null) f.params.indexOfFirst { it.first == arg.name } else pos++
            if (i < 0 || i >= given.size) continue
            given[i] = eval(arg.value, scope); set[i] = true
        }
        f.params.forEachIndexed { i, (pn, def) -> fs.vars[pn] = Slot(if (set[i]) given[i] else def?.let { eval(it, global) }) }
        val saved = path
        path = path + c.id + "/"
        if (++depth > 100) fail(c.line, c.col, "${f.name}() calls itself too deeply")
        try { return block(f.body, fs) } finally { path = saved; depth-- }
    }

    private fun given(a: Array<Any?>, i: Int) = i < a.size && a[i] !== Missing
    private fun arg(a: Array<Any?>, i: Int): Any? = if (given(a, i)) a[i] else null
    private fun d(a: Array<Any?>, i: Int, def: Double = NA): Double = if (given(a, i)) num(a[i]) else def
    private fun len(c: Call, a: Array<Any?>, i: Int, def: Int? = null): Int {
        val v = if (given(a, i)) num(a[i]) else def?.toDouble() ?: NA
        if (v.isNaN() || v < 1) fail(c.line, c.col, "${c.name}(): the length must be 1 or more (got ${if (v.isNaN()) "na" else str(v)})")
        if (v > MAX_LEN) fail(c.line, c.col, "${c.name}(): the length must be at most $MAX_LEN (got ${str(v)})")
        return v.toInt()
    }

    private fun tr(handleNa: Boolean): Double {
        val b = bars[bar]
        if (bar == 0) return if (handleNa) b.high - b.low else NA
        val pc = bars[bar - 1].close
        return max(b.high - b.low, max(abs(b.high - pc), abs(b.low - pc)))
    }

    private fun vwap(key: String, src: Double): Any? {
        class VW { var bar = -1; var day = -1L; var pv = 0.0; var v = 0.0; var ppv = 0.0; var pvv = 0.0; var pday = -1L }
        val s = st(key) { VW() }
        val day = zoned(bar).toLocalDate().toEpochDay()
        if (s.bar != bar) { s.ppv = s.pv; s.pvv = s.v; s.pday = s.day; s.bar = bar }
        val vol = bars[bar].volume
        val basePv = if (day != s.pday) 0.0 else s.ppv
        val baseV = if (day != s.pday) 0.0 else s.pvv
        s.day = day; s.pv = basePv + src * vol; s.v = baseV + vol
        return if (s.v > 0) s.pv / s.v else null
    }

    private fun sessionOk(spec: String, i: Int): Boolean {
        val parts = spec.split(':')
        val range = parts[0]
        val days = parts.getOrNull(1)
        val z = zoned(i)
        if (days != null) {
            val dow = (z.dayOfWeek.value % 7 + 1).toString()
            if (!days.contains(dow)) return false
        }
        val m = Regex("(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(range) ?: return true
        val (h1, m1, h2, m2) = m.destructured
        val start = h1.toInt() * 60 + m1.toInt(); val end = h2.toInt() * 60 + m2.toInt()
        val t = z.hour * 60 + z.minute
        return if (start <= end) t in start until end else t >= start || t < end
    }

    private fun input(c: Call, a: Array<Any?>): Any? {
        val def = sc.inputIndex[c.id] ?: return arg(a, 0)
        val o = overrides[def.key]
        if (def.kind == "source") {
            val src = (o as? String) ?: (def.default as? String) ?: "close"
            return seriesAt(src, bar) ?: seriesAt("close", bar)
        }
        if (o != null) return when (def.kind) {
            "int" -> num(o).let { if (it.isNaN()) arg(a, 0) else floor(it) }
            "float", "price" -> num(o).let { if (it.isNaN()) arg(a, 0) else it }
            "bool" -> o == true || o == "true"
            else -> o.toString()
        }
        return arg(a, 0)
    }

    private fun builtinVar(c: Call, v: List<Any?>, scope: Scope): Any? = when (c.name) {
        "math.max" -> box(v.map { num(it) }.let { l -> if (l.any { it.isNaN() }) NA else l.max() })
        "math.min" -> box(v.map { num(it) }.let { l -> if (l.any { it.isNaN() }) NA else l.min() })
        "math.avg" -> box(v.map { num(it) }.average())
        "str.format" -> {
            var s = (v.firstOrNull()?.toString() ?: "").take(MAX_TEXT)
            v.drop(1).take(20).forEachIndexed { i, x ->
                s = s.replace(Regex("\\{$i(,[^}]*)?\\}"), Regex.escapeReplacement(str(x)))
                if (s.length > MAX_TEXT) fail(c.line, c.col, "Text longer than $MAX_TEXT characters")
            }
            s
        }
        "timestamp" -> {
            val nums = v.filterIsInstance<Double>()
            if (v.size == 1 && v[0] is String) {
                runCatching { LocalDateTime.parse((v[0] as String).trim().replace(' ', 'T')).atZone(Pine.IST).toEpochSecond() * 1000.0 }.getOrNull()
            } else if (nums.size >= 3) {
                val (y, mo, dd) = Triple(nums[0].toInt(), nums[1].toInt(), nums[2].toInt())
                runCatching { LocalDateTime.of(y, mo, dd, nums.getOrElse(3) { 0.0 }.toInt(), nums.getOrElse(4) { 0.0 }.toInt(), nums.getOrElse(5) { 0.0 }.toInt())
                    .atZone(Pine.IST).toEpochSecond() * 1000.0 }.getOrNull()
            } else null
        }
        "fill", "plotcandle", "plotbar", "log.info", "log.warning", "log.error" -> null
        else -> fail(c.line, c.col, "Unknown function '${c.name}'")
    }

    private fun builtin(c: Call, a: Array<Any?>, scope: Scope): Any? {
        val key = path + c.id
        val b = bars[bar]
        // Inside request.security nothing is drawn, signalled or traded: only the value comes back.
        if (htf && (c.name.startsWith("plot") || c.name == "hline" || c.name == "alertcondition" || c.name.startsWith("strategy.")))
            fail(c.line, c.col, "${c.name}() cannot run inside request.security")
        when (c.name) {
            "indicator", "study", "strategy" -> return null
            "plot", "hline" -> {
                val i = sc.plotIndex[c.id] ?: return null
                plots[i][bar] = d(a, 0)
                return null
            }
            "plotshape", "plotchar", "plotarrow" -> {
                val v = arg(a, 0)
                val hit = when (v) { is Boolean -> v; is Double -> !v.isNaN() && v != 0.0; else -> false }
                sc.signalIndex[c.id]?.let { signals[it][bar] = hit }
                val si = sc.shapeIndex[c.id] ?: return null
                if (hit) {
                    val def = sc.shapes[si]
                    val color = Pine.safeColor((if (c.name == "plotarrow") null else arg(a, 4) as? String)?.take(7)) ?: def.color
                    val style = if (c.name == "plotarrow") (if (num(v) > 0) "arrowup" else "arrowdown") else def.style
                    val above = when (def.location) { "belowbar" -> false; "abovebar" -> true; "bottom" -> false; "top" -> true
                        else -> !(style.endsWith("up")) }
                    val shape = when (style) {
                        "triangleup", "arrowup" -> "triangleUp"; "triangledown", "arrowdown" -> "triangleDown"
                        "labelup" -> "labelUp"; "labeldown" -> "labelDown"; else -> "circle"
                    }
                    markers += Pine.Marker(bar, if (c.name == "plotarrow") num(v) < 0 else above, shape, color, def.text)
                }
                return null
            }
            "alertcondition" -> { sc.signalIndex[c.id]?.let { signals[it][bar] = truthy(arg(a, 0)) }; return null }
            "bgcolor", "barcolor", "alert", "max_bars_back" -> return null
            "runtime.error" -> fail(c.line, c.col, arg(a, 0)?.toString() ?: "runtime.error")
        }
        if (c.name == "input" || c.name.startsWith("input.")) return input(c, a)
        if (c.name.startsWith("strategy.")) {
            val br = broker ?: fail(c.line, c.col, "${c.name}() needs a strategy() declaration")
            when (c.name) {
                "strategy.entry", "strategy.order" -> {
                    if (given(a, 10) && !truthy(a[10])) return null
                    val id = arg(a, 0)?.toString() ?: fail(c.line, c.col, "${c.name}() needs an id")
                    val dir = arg(a, 1)
                    val long = when (dir) { "long" -> true; "short" -> false; is Boolean -> dir
                        else -> fail(c.line, c.col, "${c.name}(): direction must be strategy.long or strategy.short") }
                    val q = d(a, 2).takeIf { !it.isNaN() }
                    if (q != null && (q <= 0 || q > 1e9)) fail(c.line, c.col, "${c.name}(): qty must be above 0 and at most 1e9")
                    br.entry(id.take(64), long, q, d(a, 3).takeIf { !it.isNaN() }, d(a, 4).takeIf { !it.isNaN() }, bar, c.name == "strategy.order")
                }
                "strategy.close" -> {
                    if (given(a, 7) && !truthy(a[7])) return null
                    br.close(arg(a, 0)?.toString(), bar, truthy(arg(a, 5)),
                        d(a, 2).takeIf { !it.isNaN() && it > 0 }, d(a, 3).takeIf { !it.isNaN() && it > 0 }?.coerceAtMost(100.0))
                }
                "strategy.close_all" -> {
                    if (given(a, 4) && !truthy(a[4])) return null
                    br.close(null, bar, truthy(arg(a, 2)))
                }
                "strategy.exit" -> {
                    if (given(a, 21) && !truthy(a[21])) return null
                    val id = arg(a, 0)?.toString() ?: fail(c.line, c.col, "strategy.exit() needs an id")
                    fun opt(i: Int) = d(a, i).takeIf { !it.isNaN() }
                    val eq = opt(2)?.takeIf { it > 0 }; val ep = opt(3)?.takeIf { it > 0 }?.coerceAtMost(100.0)
                    br.exit(Broker.Exit(id, arg(a, 1)?.toString(), opt(4), opt(6), opt(5), opt(7), opt(9), opt(10), opt(8), eq, ep))
                }
                "strategy.cancel" -> { if (!given(a, 1) || truthy(a[1])) br.cancel(arg(a, 0)?.toString()) }
                "strategy.cancel_all" -> { if (!given(a, 0) || truthy(a[0])) br.cancel(null) }
            }
            return null
        }
        return when (c.name) {
            // ---- ta ----
            "ta.sma" -> { val h = st(key) { H() }; h.put(bar, d(a, 0)); box(h.window(len(c, a, 1))?.average() ?: NA) }
            "ta.ema" -> box(st(key) { Avg('e') }.step(bar, d(a, 0), len(c, a, 1)))
            "ta.rma" -> box(st(key) { Avg('r') }.step(bar, d(a, 0), len(c, a, 1)))
            "ta.wma" -> { val h = st(key) { H() }; h.put(bar, d(a, 0)); box(wmaOf(h, len(c, a, 1))) }
            "ta.vwma" -> {
                val (pv, v) = st(key) { H() to H() }
                val src = d(a, 0); val l = len(c, a, 1)
                pv.put(bar, src * b.volume); v.put(bar, b.volume)
                val sv = v.window(l)?.sum() ?: NA
                box(if (sv == 0.0) NA else (pv.window(l)?.sum() ?: NA) / sv)
            }
            "ta.hma" -> {
                val (h, h2) = st(key) { H() to H() }
                val l = len(c, a, 1)
                h.put(bar, d(a, 0))
                h2.put(bar, 2 * wmaOf(h, max(1, l / 2)) - wmaOf(h, l))
                box(wmaOf(h2, max(1, floor(sqrt(l.toDouble())).toInt())))
            }
            "ta.rsi" -> {
                val s = st(key) { RsiS() }
                val l = len(c, a, 1)
                val src = d(a, 0); s.h.put(bar, src)
                val ch = src - s.h.get(1)
                val u = s.up.step(bar, if (ch.isNaN()) NA else max(ch, 0.0), l)
                val dn = s.dn.step(bar, if (ch.isNaN()) NA else max(-ch, 0.0), l)
                box(when { u.isNaN() || dn.isNaN() -> NA; dn == 0.0 -> 100.0; u == 0.0 -> 0.0; else -> 100 - 100 / (1 + u / dn) })
            }
            "ta.tr" -> box(tr(truthy(arg(a, 0))))
            "ta.atr" -> box(st(key) { Avg('r') }.step(bar, tr(true), len(c, a, 0)))
            "ta.stdev", "ta.variance" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val l = len(c, a, 1)
                val w = h.window(l) ?: return null
                val m = w.average()
                val biased = if (given(a, 2)) truthy(a[2]) else true
                val v = w.sumOf { (it - m) * (it - m) } / (if (biased || l < 2) l else l - 1)
                box(if (c.name == "ta.stdev") sqrt(v) else v)
            }
            "ta.highest", "ta.lowest", "ta.highestbars", "ta.lowestbars" -> {
                val hi = c.name.startsWith("ta.highest")
                val oneArg = !given(a, 1)
                val l = if (oneArg) len(c, a, 0) else len(c, a, 1)
                val src = if (oneArg) (if (hi) b.high else b.low) else d(a, 0)
                val h = st(key) { H() }; h.put(bar, src)
                val w = h.window(l) ?: return null
                var best = 0
                for (i in w.indices) if (if (hi) w[i] >= w[best] else w[i] <= w[best]) best = i
                if (c.name.endsWith("bars")) (best - (l - 1)).toDouble() else box(w[best])
            }
            "ta.crossover", "ta.crossunder", "ta.cross" -> {
                val (h1, h2) = st(key) { H() to H() }
                h1.put(bar, d(a, 0)); h2.put(bar, d(a, 1))
                val x0 = h1.get(0); val y0 = h2.get(0); val x1 = h1.get(1); val y1 = h2.get(1)
                if (listOf(x0, y0, x1, y1).any { it.isNaN() }) false
                else when (c.name) {
                    "ta.crossover" -> x0 > y0 && x1 <= y1
                    "ta.crossunder" -> x0 < y0 && x1 >= y1
                    else -> (x0 > y0 && x1 <= y1) || (x0 < y0 && x1 >= y1)
                }
            }
            "ta.change", "ta.mom", "ta.roc" -> {
                val raw = arg(a, 0)
                val h = st(key) { H() }
                val l = if (given(a, 1)) len(c, a, 1) else if (c.name == "ta.change") 1 else len(c, a, 1)
                h.put(bar, num(raw))
                val prev = h.get(l)
                if (raw is Boolean) (if (prev.isNaN()) false else (num(raw) != prev))
                else box(when (c.name) { "ta.roc" -> if (prev == 0.0) NA else 100 * (num(raw) - prev) / prev; else -> num(raw) - prev })
            }
            "ta.cum" -> { val x = d(a, 0); box(st(key) { R() }.step(bar) { p -> (if (p.isNaN()) 0.0 else p) + (if (x.isNaN()) 0.0 else x) }) }
            "ta.macd" -> {
                val (f, s, g) = st(key) { Triple(Avg('e'), Avg('e'), Avg('e')) }
                val src = d(a, 0)
                val m = f.step(bar, src, len(c, a, 1)) - s.step(bar, src, len(c, a, 2))
                val sig = g.step(bar, m, len(c, a, 3))
                listOf(box(m), box(sig), box(m - sig))
            }
            "ta.bb" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val w = h.window(len(c, a, 1)) ?: return listOf(null, null, null)
                val m = w.average(); val sd = sqrt(w.sumOf { (it - m) * (it - m) } / w.size) * d(a, 2)
                listOf(box(m), box(m + sd), box(m - sd))
            }
            "ta.supertrend" -> {
                val s = st(key) { StS() }
                if (s.bar != bar) { s.cur.copyInto(s.prev); s.bar = bar }
                val atr = s.atr.step(bar, tr(true), len(c, a, 1))
                val f = d(a, 0)
                val src = (b.high + b.low) / 2
                var up = src + f * atr; var lo = src - f * atr
                val pl = if (s.prev[0].isNaN()) 0.0 else s.prev[0]; val pu = if (s.prev[1].isNaN()) 0.0 else s.prev[1]
                val pc = if (bar > 0) bars[bar - 1].close else NA
                lo = if (lo > pl || pc < pl) lo else pl
                up = if (up < pu || pc > pu) up else pu
                val dir = when {
                    s.prev[4].isNaN() -> 1.0
                    s.prev[2] == pu -> if (b.close > up) -1.0 else 1.0
                    else -> if (b.close < lo) 1.0 else -1.0
                }
                val stv = if (dir < 0) lo else up
                s.cur[0] = lo; s.cur[1] = up; s.cur[2] = stv; s.cur[3] = dir; s.cur[4] = atr
                if (atr.isNaN()) listOf(null, dir) else listOf(box(stv), dir)
            }
            "ta.stoch" -> {
                val (hh, ll) = st(key) { H() to H() }
                hh.put(bar, d(a, 1)); ll.put(bar, d(a, 2))
                val l = len(c, a, 3)
                val hi = hh.window(l)?.max() ?: return null; val lo = ll.window(l)?.min() ?: return null
                box(if (hi == lo) NA else 100 * (d(a, 0) - lo) / (hi - lo))
            }
            "ta.cci" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val w = h.window(len(c, a, 1)) ?: return null
                val m = w.average(); val md = w.sumOf { abs(it - m) } / w.size
                box(if (md == 0.0) NA else (d(a, 0) - m) / (0.015 * md))
            }
            "ta.vwap" -> vwap(key, if (given(a, 0)) d(a, 0) else (b.high + b.low + b.close) / 3)
            "ta.barssince" -> { val cnd = truthy(arg(a, 0)); box(st(key) { R() }.step(bar) { p -> if (cnd) 0.0 else if (p.isNaN()) NA else p + 1 }) }
            "ta.valuewhen" -> {
                val list = st(key) { ArrayList<Pair<Int, Any?>>() }
                if (list.isNotEmpty() && list.last().first == bar) list.removeAt(list.size - 1)
                if (truthy(arg(a, 0))) list += bar to arg(a, 1)
                val occ = d(a, 2, 0.0).toInt()
                list.getOrNull(list.size - 1 - occ)?.second
            }
            "ta.rising", "ta.falling" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val l = len(c, a, 1)
                if (h.size <= l) false
                else (1..l).all { k -> if (c.name == "ta.rising") h.get(0) > h.get(k) else h.get(0) < h.get(k) }
            }
            "ta.dmi" -> {
                val s = st(key) { DmiS() }
                val dl = len(c, a, 0); val al = len(c, a, 1)
                val up = if (bar > 0) b.high - bars[bar - 1].high else NA
                val dn = if (bar > 0) bars[bar - 1].low - b.low else NA
                val pdm = if (up.isNaN()) NA else if (up > dn && up > 0) up else 0.0
                val mdm = if (dn.isNaN()) NA else if (dn > up && dn > 0) dn else 0.0
                val trr = s.tr.step(bar, tr(false), dl)
                val plus = 100 * s.p.step(bar, pdm, dl) / trr; val minus = 100 * s.m.step(bar, mdm, dl) / trr
                val sum = plus + minus
                val adx = 100 * s.adx.step(bar, if (plus.isNaN() || minus.isNaN()) NA else abs(plus - minus) / (if (sum == 0.0) 1.0 else sum), al)
                listOf(box(plus), box(minus), box(adx))
            }
            "ta.pivothigh", "ta.pivotlow" -> {
                val hi = c.name == "ta.pivothigh"
                val twoArg = !given(a, 2)
                val left = if (twoArg) len(c, a, 0) else len(c, a, 1)
                val right = if (twoArg) len(c, a, 1) else len(c, a, 2)
                val src = if (twoArg) (if (hi) b.high else b.low) else d(a, 0)
                val h = st(key) { H() }; h.put(bar, src)
                if (h.size < left + right + 1) return null
                val p = h.get(right)
                if (p.isNaN()) return null
                val ok = (0 until right).all { k -> if (hi) h.get(k) < p else h.get(k) > p } &&
                    (right + 1..right + left).all { k -> if (hi) h.get(k) <= p else h.get(k) >= p }
                if (ok) p else null
            }
            "ta.linreg" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val l = len(c, a, 1)
                val w = h.window(l) ?: return null
                val xm = (l - 1) / 2.0; val ym = w.average()
                var sxy = 0.0; var sxx = 0.0
                for (i in 0 until l) { sxy += (i - xm) * (w[i] - ym); sxx += (i - xm) * (i - xm) }
                val slope = if (sxx == 0.0) 0.0 else sxy / sxx
                box(ym - slope * xm + slope * (l - 1 - d(a, 2, 0.0)))
            }
            "ta.wpr" -> {
                val (hh, ll) = st(key) { H() to H() }
                hh.put(bar, b.high); ll.put(bar, b.low)
                val l = len(c, a, 0)
                val hi = hh.window(l)?.max() ?: return null; val lo = ll.window(l)?.min() ?: return null
                box(if (hi == lo) NA else -100 * (hi - b.close) / (hi - lo))
            }
            "ta.median" -> {
                val h = st(key) { H() }; h.put(bar, d(a, 0))
                val w = h.window(len(c, a, 1))?.sorted() ?: return null
                box(if (w.size % 2 == 1) w[w.size / 2] else (w[w.size / 2 - 1] + w[w.size / 2]) / 2)
            }
            "math.sum" -> { val h = st(key) { H() }; h.put(bar, d(a, 0)); box(h.window(len(c, a, 1))?.sum() ?: NA) }
            // ---- math and values ----
            "math.abs" -> box(abs(d(a, 0)))
            "math.floor" -> box(floor(d(a, 0)))
            "math.ceil" -> box(kotlin.math.ceil(d(a, 0)))
            "math.sqrt" -> box(sqrt(d(a, 0)))
            "math.log" -> box(kotlin.math.ln(d(a, 0)))
            "math.log10" -> box(kotlin.math.log10(d(a, 0)))
            "math.exp" -> box(kotlin.math.exp(d(a, 0)))
            "math.sign" -> box(Math.signum(d(a, 0)))
            "math.pow" -> box(Math.pow(d(a, 0), d(a, 1)))
            "math.round" -> {
                val x = d(a, 0); val p = d(a, 1, 0.0).let { if (it.isNaN()) 0 else it.coerceIn(0.0, 12.0).toInt() }
                if (x.isNaN()) null else { val f = Math.pow(10.0, p.toDouble()); Math.round(x * f) / f }
            }
            "nz" -> arg(a, 0).let { v -> if (v == null || (v is Double && v.isNaN())) (if (given(a, 1)) arg(a, 1) else 0.0) else v }
            "na" -> arg(a, 0).let { it == null || (it is Double && it.isNaN()) }
            "fixnan" -> { val x = d(a, 0); box(st(key) { R() }.step(bar) { p -> if (x.isNaN()) p else x }) }
            "int" -> box(d(a, 0).let { if (it.isNaN()) NA else if (it < 0) kotlin.math.ceil(it) else floor(it) })
            "float" -> box(d(a, 0))
            "bool" -> truthy(arg(a, 0))
            "color.new" -> {
                val base = (arg(a, 0) as? String)?.take(7) ?: return null
                val t = d(a, 1, 0.0).coerceIn(0.0, 100.0)
                base + String.format("%02X", ((100 - t) / 100 * 255).toInt())
            }
            "color.rgb" -> String.format("#%02X%02X%02X", d(a, 0).toInt().coerceIn(0, 255), d(a, 1).toInt().coerceIn(0, 255), d(a, 2).toInt().coerceIn(0, 255))
            "color.from_gradient" -> {
                val v = d(a, 0); val lo = d(a, 1); val hi = d(a, 2)
                if (v.isNaN() || hi == lo) arg(a, 3) else if ((v - lo) / (hi - lo) < 0.5) arg(a, 3) else arg(a, 4)
            }
            "str.tostring" -> {
                val v = arg(a, 0)
                val f = arg(a, 1) as? String
                if (v is Double && f != null) {
                    val dec = (if (f.startsWith("format.")) 2 else f.substringAfter('.', "").length).coerceAtMost(12)
                    String.format(java.util.Locale.ENGLISH, "%.${dec}f", v)
                } else str(v)
            }
            "time" -> {
                val sess = arg(a, 1) as? String
                val tf = (arg(a, 0) as? String)?.takeIf { it.isNotBlank() }
                if (sess != null && sess.isNotBlank() && !sessionOk(sess, bar)) null
                else if (tf == null || tfSeconds(tf) <= stepSeconds()) b.time * 1000.0
                else bucketStart(b.time, tf) * 1000.0
            }
            "timeframe.change" -> {
                val tf = arg(a, 0) as? String ?: return false
                bar > 0 && bucketStart(b.time, tf) != bucketStart(bars[bar - 1].time, tf)
            }
            "timeframe.in_seconds" -> tfSeconds((arg(a, 0) as? String)?.takeIf { it.isNotBlank() } ?: tfPeriod()).toDouble()
            else -> fail(c.line, c.col, "${c.name}() is not supported yet")
        }
    }
}

/** TradingView's broker emulator, as far as the phone needs it. */
internal class Broker(
    private val s: Pine.Settings, private val qtyOverride: Double?, private val tick: Double,
    private val bars: List<Pine.Bar>, private val markers: MutableList<Pine.Marker>,
    private val costs: Pine.Costs = Pine.Costs(),
) {
    /** An open entry; [qty] and [comm] shrink as partial exits take pieces of it. */
    class Open(val id: String, val long: Boolean, var qty: Double, val price: Double, val bar: Int, var comm: Double, var best: Double,
               val fillTime: Long = 0, val intrabar: Boolean = false) {
        val origQty = qty
        /** Exit orders that already took their piece of this entry. */
        val done = HashSet<String>()
    }
    class Order(val id: String, val long: Boolean, val qty: Double?, val limit: Double?, val stop: Double?, val bar: Int, val plain: Boolean)
    class Exit(val id: String, val from: String?, val profit: Double?, val loss: Double?, val limit: Double?, val stop: Double?,
               val trailPts: Double?, val trailOff: Double?, val trailPrice: Double?, val qty: Double? = null, val qtyPct: Double? = null)

    /** Price after slippage: buys fill higher, sells lower. */
    private val slip = s.slippageTicks * tick + costs.slippagePoints
    private fun buyPx(px: Double) = px + slip
    private fun sellPx(px: Double) = px - slip

    private val open = ArrayList<Open>()
    private val orders = LinkedHashMap<String, Order>()
    private val closes = ArrayList<String?>()
    private val exits = LinkedHashMap<String, Exit>()
    private val trades = ArrayList<Pine.Trade>()
    private var realized = 0.0
    /** Net P&L of closed trades (what strategy.netprofit reports). */
    private var closedPnl = 0.0
    private var commission = 0.0
    /** Running peak and deepest fall of bar-close equity, for strategy.max_drawdown. */
    private var ddPeak = s.initialCapital
    private var ddMax = 0.0
    private val equity = DoubleArray(bars.size) { s.initialCapital }
    private var bar = 0
    /** When the fills being made now happen (epoch seconds), and whether inside the bar: see [Pine.Trade.entryFillTime]. */
    private var fillAt = 0L
    private var fillIntrabar = false

    fun size(): Double = open.sumOf { if (it.long) it.qty else -it.qty }
    private fun openPnl(px: Double) = open.sumOf { (px - it.price) * it.qty * (if (it.long) 1 else -1) }
    private fun equityAt(px: Double) = s.initialCapital + realized + openPnl(px)
    private fun comm(px: Double, q: Double) = when (s.commissionType) {
        "percent" -> px * q * s.commissionValue / 100; "cash_per_order" -> s.commissionValue; "cash_per_contract" -> s.commissionValue * q; else -> 0.0
    } + costs.perOrder + px * q * costs.percent / 100
    private fun qtyFor(o: Order, px: Double): Double = qtyOverride ?: o.qty ?: when (s.qtyType) {
        "cash" -> floor(s.qtyValue / px)
        "percent_of_equity" -> floor(equityAt(px) * s.qtyValue / 100 / px)
        else -> s.qtyValue
    }

    fun value(name: String): Any? {
        val px = bars[bar].close
        return when (name) {
            "strategy.position_size" -> size()
            "strategy.position_avg_price" -> if (open.isEmpty()) null else open.sumOf { it.price * it.qty } / open.sumOf { it.qty }
            "strategy.equity" -> equityAt(px)
            "strategy.netprofit" -> closedPnl
            "strategy.openprofit" -> openPnl(px) - open.sumOf { it.comm }
            "strategy.opentrades" -> open.size.toDouble()
            "strategy.closedtrades" -> trades.size.toDouble()
            "strategy.wintrades" -> trades.count { it.pnl > 0 }.toDouble()
            "strategy.losstrades" -> trades.count { it.pnl < 0 }.toDouble()
            "strategy.initial_capital" -> s.initialCapital
            "strategy.grossprofit" -> trades.filter { it.pnl > 0 }.sumOf { it.pnl }
            "strategy.grossloss" -> -trades.filter { it.pnl < 0 }.sumOf { it.pnl }
            "strategy.max_drawdown" -> equityAt(px).let { eq -> max(ddMax, max(ddPeak, eq) - eq) }
            "strategy.position_entry_name" -> open.firstOrNull()?.id ?: ""
            else -> null
        }
    }

    fun entry(id: String, long: Boolean, qty: Double?, limit: Double?, stop: Double?, bar: Int, plain: Boolean) {
        orders[id] = Order(id, long, qty, limit, stop, bar, plain)
    }
    private class CloseReq(val id: String?, val qty: Double?, val pct: Double?)
    private val partCloses = ArrayList<CloseReq>()

    fun close(id: String?, bar: Int, immediately: Boolean, qty: Double? = null, pct: Double? = null) {
        if (qty == null && pct == null) {
            if (immediately) closeWhere(bar, bars[bar].close, if (id == null) "Close all" else "Close") { id == null || it.id == id }
            else closes += id
            return
        }
        if (immediately) closeAmount(bar, bars[bar].close, CloseReq(id, qty, pct)) else partCloses += CloseReq(id, qty, pct)
    }

    /** strategy.close with qty / qty_percent: that much of the matching entries, oldest first. */
    private fun closeAmount(i: Int, px: Double, r: CloseReq) {
        val match = open.filter { r.id == null || it.id == r.id }
        val total = match.sumOf { it.qty }
        var left = (r.qty ?: r.pct?.let { total * it / 100 } ?: total).coerceIn(0.0, total)
        for (e in match) {
            if (left <= 1e-12) break
            val q = minOf(left, e.qty)
            closePart(i, px, e, q, if (r.id == null) "Close all" else "Close ${r.id}")
            left -= q
        }
    }
    fun exit(x: Exit) { exits[x.id] = x }
    /**
     * strategy.cancel / cancel_all: every pending order by that name - entries, strategy.exit
     * orders and waiting strategy.close orders (all of them when [id] is null), as TradingView.
     */
    fun cancel(id: String?) {
        if (id == null) { orders.clear(); exits.clear(); closes.clear(); partCloses.clear(); return }
        orders.remove(id); exits.remove(id)
        closes.removeAll { it == id }; partCloses.removeAll { it.id == id }
    }

    private fun enter(o: Order, i: Int, px: Double) {
        var q = qtyFor(o, px)
        if (!(q > 0)) return
        if (open.isNotEmpty() && open[0].long != o.long) {
            if (o.plain) {
                // strategy.order: an opposite order trades its own quantity - it reduces the
                // position (oldest entries first) and only what is left over opens the other way.
                for (e in open.toList()) {
                    if (q <= 1e-12) break
                    val take = minOf(q, e.qty)
                    closePart(i, px, e, take, o.id)
                    q -= take
                }
                if (q <= 1e-12) return
            } else closeWhere(i, px, o.id) { true }
        } else if (!o.plain && open.size >= s.pyramiding) return   // strategy.order ignores pyramiding
        val fill = if (o.long) buyPx(px) else sellPx(px)
        val c = comm(fill, q)
        realized -= c; commission += c
        open += Open(o.id, o.long, q, fill, i, c, fill, fillAt, fillIntrabar)
        markers += Pine.Marker(i, !o.long, if (o.long) "labelUp" else "labelDown", if (o.long) "#089981" else "#F23645", o.id)
    }

    private fun closeWhere(i: Int, px: Double, exitId: String, pred: (Open) -> Boolean) {
        for (e in open.filter(pred)) closePart(i, px, e, e.qty, exitId)
    }

    /** Close [q] of entry [e] at [px] (slippage applied here). The whole entry once [q] reaches its size. */
    private fun closePart(i: Int, px: Double, e: Open, q0: Double, exitId: String) {
        val q = minOf(q0, e.qty)
        if (q <= 1e-12) return
        val fill = if (e.long) sellPx(px) else buyPx(px)
        val c = comm(fill, q)
        val entryComm = e.comm * (q / e.qty)
        val gross = (fill - e.price) * q * (if (e.long) 1 else -1)
        realized += gross - c; commission += c
        val pnl = gross - entryComm - c
        closedPnl += pnl
        trades += Pine.Trade(e.id, exitId, e.long, q, e.bar, bars[e.bar].time, e.price, i, bars[i].time, fill,
            pnl, pnl / (e.price * q) * 100, entryComm + c, false,
            entryFillTime = e.fillTime, exitFillTime = fillAt, entryIntrabar = e.intrabar, exitIntrabar = fillIntrabar)
        e.comm -= entryComm; e.qty -= q
        markers += Pine.Marker(i, e.long, if (e.long) "triangleDown" else "triangleUp", "#787B86", exitId)
        if (e.qty <= 1e-9) {
            open.remove(e)
            exits.values.removeIf { x -> x.from == e.id && open.none { it.id == e.id } }
            if (open.isEmpty()) exits.values.removeIf { it.from == null }
        }
    }

    private fun fillMarket(i: Int, px: Double) {
        for (id in closes.toList()) closeWhere(i, px, if (id == null) "Close all" else "Close $id") { id == null || it.id == id }
        closes.clear()
        for (r in partCloses.toList()) closeAmount(i, px, r)
        partCloses.clear()
        for (o in orders.values.filter { it.limit == null && it.stop == null }) { orders.remove(o.id); enter(o, i, px) }
    }

    /** The bar's price path as TradingView walks it: open, the nearer extreme, the other, close. */
    private fun path(b: Pine.Bar): List<Double> =
        if ((b.high - b.open) < (b.open - b.low)) listOf(b.open, b.high, b.low, b.close) else listOf(b.open, b.low, b.high, b.close)

    /** A moment on a bar's path: on segment [k] (pts[k] -> pts[k+1]) at price [px], [d] along it from pts[k]. */
    private class At(val k: Int, val px: Double, val d: Double) {
        fun before(o: At) = k < o.k || k == o.k && d < o.d - 1e-12
    }
    private fun at(pts: List<Double>, k: Int, px: Double) = At(k, px, abs(px - pts[k]))

    /**
     * The first moment, from the cursor at price [p] on segment [k], that the price is at or
     * beyond [level]: rising to it when [up], else falling. Already beyond it: the cursor itself
     * (a gap at the open, or a level that was passed before its order existed).
     */
    private fun touch(pts: List<Double>, k: Int, p: Double, level: Double, up: Boolean): At? {
        if (if (up) p >= level else p <= level) return at(pts, k, p)
        for (j in k until pts.size - 1) {
            val a = if (j == k) p else pts[j]; val c = pts[j + 1]
            if (if (up) a <= level && c >= level else a >= level && c <= level) return at(pts, j, level)
        }
        return null
    }

    /** Where a waiting stop / limit entry is first triggered from the cursor (a stop-limit: where its stop arms it). */
    private fun entryTouch(o: Order, pts: List<Double>, k: Int, p: Double): At? = when {
        o.stop != null -> touch(pts, k, p, o.stop, o.long)
        o.limit != null -> touch(pts, k, p, o.limit, !o.long)
        else -> null
    }

    private fun trails(x: Exit) = x.trailOff != null && (x.trailPts != null || x.trailPrice != null)

    /** The stop and limit prices of exit [x] for entry [e] with the best price so far at [best], trailing included. */
    private fun levels(e: Open, x: Exit, best: Double): Pair<Double?, Double?> {
        val sign = if (e.long) 1 else -1
        var stop = x.stop ?: x.loss?.let { e.price - sign * it * tick }
        val limit = x.limit ?: x.profit?.let { e.price + sign * it * tick }
        if (trails(x)) {
            val act = x.trailPrice ?: (e.price + sign * x.trailPts!! * tick)
            val reached = if (e.long) best >= act else best <= act
            if (reached) {
                val t = best - sign * x.trailOff!! * tick
                stop = if (stop == null) t else if (e.long) max(stop, t) else min(stop, t)
            }
        }
        return stop to limit
    }

    /**
     * Where exit [x] first fills entry [e] from the cursor. The best price is carried along the
     * path, so a trailing stop activates and moves within the bar: a favourable leg can only
     * reach the target, an adverse leg only the stop (at the stop it had when the leg began).
     */
    private fun exitTouch(e: Open, x: Exit, pts: List<Double>, k: Int, p: Double): At? {
        if (x.stop == null && x.loss == null && x.limit == null && x.profit == null && !trails(x)) return null
        val long = e.long
        var best = if (long) max(e.best, p) else min(e.best, p)
        run {
            val (stop, limit) = levels(e, x, best)
            if (stop != null && (if (long) p <= stop else p >= stop)) return at(pts, k, p)
            if (limit != null && (if (long) p >= limit else p <= limit)) return at(pts, k, p)
        }
        for (j in k until pts.size - 1) {
            val a = if (j == k) p else pts[j]; val c = pts[j + 1]
            best = if (long) max(best, a) else min(best, a)
            val (stop, limit) = levels(e, x, best)
            if (stop != null && (if (long) a <= stop else a >= stop)) return at(pts, j, a)
            val favourable = if (long) c > a else c < a
            if (favourable) {
                if (limit != null && (if (long) c >= limit else c <= limit)) return at(pts, j, limit)
            } else if (stop != null && (if (long) c <= stop else c >= stop)) return at(pts, j, stop)
        }
        return null
    }

    /** Moving the cursor from [p0] on segment [k0] to [p1] on [k1]: every open entry's best price follows. */
    private fun advance(pts: List<Double>, k0: Int, p0: Double, k1: Int, p1: Double) {
        if (open.isEmpty()) return
        var hi = max(p0, p1); var lo = min(p0, p1)
        for (j in k0 + 1..k1) { hi = max(hi, pts[j]); lo = min(lo, pts[j]) }
        for (e in open) e.best = if (e.long) max(e.best, hi) else min(e.best, lo)
    }

    /**
     * The bar's stop / limit orders, entries and exits alike, fill in the order the price
     * reaches them along the bar's path (open -> nearer extreme -> far extreme -> close).
     * After each fill the rest are looked at again from that moment: a fill can open an
     * entry whose exits then see only the path after it, or close one whose exits go with it.
     * A level already passed at the open fills at the open. At the same moment, exits go first.
     */
    fun beforeBar(i: Int) {
        bar = i
        val b = bars[i]
        fillAt = b.time; fillIntrabar = false
        if (!s.processOnClose) fillMarket(i, b.open)
        val pts = path(b)
        var k = 0; var p = pts[0]
        var guard = 0
        while (guard++ < 10_000) {
            var first: At? = null
            var act: (() -> Unit)? = null
            for (e in open.toList()) for (x in exits.values.toList()) {
                if ((x.from != null && x.from != e.id) || x.id in e.done) continue
                val t = exitTouch(e, x, pts, k, p) ?: continue
                if (first == null || t.before(first)) { first = t; act = { fireExit(i, e, x, t.px) } }
            }
            for (o in orders.values.toList()) {
                if ((o.limit == null && o.stop == null) || o.bar >= i) continue
                val t = entryTouch(o, pts, k, p) ?: continue
                if (first == null || t.before(first)) { first = t; act = { fireEntry(i, o, t.px) } }
            }
            if (first == null || act == null) break
            advance(pts, k, p, first.k, first.px)
            k = first.k; p = first.px
            val atOpen = first.k == 0 && first.d == 0.0
            fillAt = if (atOpen) b.time else closeInstant(i); fillIntrabar = !atOpen
            act()
        }
        advance(pts, k, p, pts.size - 2, pts.last())
        // strategy.close(immediately = true) during the script fills at this bar's close.
        fillAt = closeInstant(i); fillIntrabar = false
    }

    private fun fireExit(i: Int, e: Open, x: Exit, px: Double) {
        if (e !in open || e.qty <= 1e-9) return
        val amount = x.qty ?: x.qtyPct?.let { e.origQty * it / 100 } ?: e.qty
        e.done += x.id
        closePart(i, px, e, amount, x.id)
    }

    private fun fireEntry(i: Int, o: Order, px: Double) {
        if (o.stop != null && o.limit != null && !(if (o.long) px <= o.limit else px >= o.limit)) {
            // A stop-limit armed where its limit is not on offer: from here on it is a plain limit order.
            orders[o.id] = Order(o.id, o.long, o.qty, o.limit, null, o.bar, o.plain)
            return
        }
        orders.remove(o.id)
        enter(o, i, px)
    }

    /** Seconds between candles: the smallest gap between neighbours (60 with a single candle). */
    private val step: Long = run {
        var m = Long.MAX_VALUE
        for (k in 1 until minOf(bars.size, 1000)) { val d = bars[k].time - bars[k - 1].time; if (d in 1 until m) m = d }
        if (m == Long.MAX_VALUE) 60L else m
    }

    /**
     * The moment bar [i] closes: the last second of its last minute. Its end is its start plus
     * the chart's interval, or the next bar's start when that is sooner, never past its IST day.
     */
    private fun closeInstant(i: Int): Long {
        val b = bars[i]
        var end = b.time + step
        bars.getOrNull(i + 1)?.let { if (it.time > b.time) end = minOf(end, it.time) }
        val dayEnd = java.time.Instant.ofEpochSecond(b.time).atZone(Pine.IST).toLocalDate().plusDays(1).atStartOfDay(Pine.IST).toEpochSecond()
        return maxOf(b.time, minOf(end, dayEnd) - 1)
    }

    fun afterScript(i: Int) {
        val b = bars[i]
        fillAt = closeInstant(i); fillIntrabar = false
        if (s.processOnClose) fillMarket(i, b.close)
        equity[i] = equityAt(b.close)
        ddPeak = max(ddPeak, equity[i]); ddMax = max(ddMax, ddPeak - equity[i])
    }

    /** A runtime error stopped the script at bar [i]: equity stays where it was from there on, not back at the capital. */
    fun halt(i: Int) {
        if (bars.isEmpty()) return
        val from = i.coerceIn(0, bars.size - 1)
        val v = equityAt(bars[from].close)
        for (j in from until equity.size) equity[j] = v
    }

    /** Where the position goes when the orders waiting after the last bar fill. */
    fun nextPosition(): Double {
        var pos = size()
        if (closes.contains(null)) pos = 0.0
        else for (id in closes) pos -= open.filter { it.id == id }.sumOf { if (it.long) it.qty else -it.qty }
        val px = bars.lastOrNull()?.close ?: return pos
        var entries = open.size
        for (o in orders.values.filter { it.limit == null && it.stop == null }) {
            val q = qtyFor(o, px)
            if (!(q > 0)) continue
            val signed = if (o.long) q else -q
            if (o.plain) { pos += signed; if (abs(pos) < 1e-12) pos = 0.0; entries++ }
            else if (pos == 0.0 || Math.signum(pos) != Math.signum(signed)) { pos = signed; entries = 1 }
            else if (entries < s.pyramiding) { pos += signed; entries++ }
        }
        return pos
    }

    private fun maxDd(upTo: Int): Pair<Double, Double> {
        var peak = s.initialCapital; var dd = 0.0; var ddPct = 0.0
        for (i in 0..upTo.coerceAtMost(equity.size - 1)) {
            peak = max(peak, equity[i])
            val d = peak - equity[i]
            if (d > dd) { dd = d; ddPct = if (peak > 0) d / peak * 100 else 0.0 }
        }
        return dd to ddPct
    }

    fun report(): Pine.Report {
        val last = bars.size - 1
        val lastPx = bars.lastOrNull()?.close ?: 0.0
        val openTrades = open.map { e ->
            val gross = (lastPx - e.price) * e.qty * (if (e.long) 1 else -1)
            Pine.Trade(e.id, "Open", e.long, e.qty, e.bar, bars[e.bar].time, e.price, last, bars.getOrNull(last)?.time ?: 0, lastPx,
                gross - e.comm, (gross - e.comm) / (e.price * e.qty) * 100, e.comm, true,
                entryFillTime = e.fillTime, entryIntrabar = e.intrabar)
        }
        val closed = trades
        val wins = closed.filter { it.pnl > 0 }; val losses = closed.filter { it.pnl < 0 }
        val gp = wins.sumOf { it.pnl }; val gl = -losses.sumOf { it.pnl }
        val net = closed.sumOf { it.pnl }
        val (dd, ddPct) = if (bars.isEmpty()) 0.0 to 0.0 else maxDd(last)
        val first = bars.firstOrNull()?.close ?: 0.0
        return Pine.Report(
            initialCapital = s.initialCapital, netProfit = net, netProfitPct = net / s.initialCapital * 100,
            grossProfit = gp, grossLoss = gl, profitFactor = if (gl > 0) gp / gl else null,
            closedTrades = closed.size, winners = wins.size, losers = losses.size,
            winRate = if (closed.isEmpty()) 0.0 else wins.size * 100.0 / closed.size,
            avgTrade = if (closed.isEmpty()) 0.0 else net / closed.size,
            avgWin = if (wins.isEmpty()) 0.0 else gp / wins.size, avgLoss = if (losses.isEmpty()) 0.0 else -gl / losses.size,
            largestWin = wins.maxOfOrNull { it.pnl } ?: 0.0, largestLoss = losses.minOfOrNull { it.pnl } ?: 0.0,
            maxDrawdown = dd, maxDrawdownPct = ddPct,
            buyHoldPct = if (first > 0) (lastPx - first) / first * 100 else 0.0,
            commission = commission, openPnl = openTrades.sumOf { it.pnl },
            avgBarsInTrade = if (closed.isEmpty()) 0.0 else closed.map { (it.exitBar - it.entryBar).toDouble() }.average(),
            trades = closed + openTrades, equity = equity.copyOf(),
        ).let { it.copy(extra = runCatching { Pine.extraOf(it, bars) }.getOrNull()) }
    }
}
