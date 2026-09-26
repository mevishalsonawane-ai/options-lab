package com.optionslab.engine.pine

/** A built-in function's parameters, in order; the first [required] must be given. */
internal class Sig(val params: List<String>, val required: Int, val variadic: Boolean = false)

internal object Builtins {
    private fun s(required: Int, vararg p: String) = Sig(p.toList(), required)
    private fun v(required: Int) = Sig(emptyList(), required, variadic = true)

    /** Array functions: argument count range (the array itself counts). */
    val ARRAY_FNS: Map<String, IntRange> = mapOf(
        "new" to 0..2, "new_float" to 0..2, "new_int" to 0..2, "new_bool" to 0..2, "new_string" to 0..2, "new_color" to 0..2,
        "from" to 0..1000, "size" to 1..1, "get" to 2..2, "set" to 3..3, "push" to 2..2, "pop" to 1..1, "shift" to 1..1,
        "unshift" to 2..2, "insert" to 3..3, "remove" to 2..2, "clear" to 1..1, "sum" to 1..1, "avg" to 1..1, "min" to 1..2,
        "max" to 1..2, "stdev" to 1..2, "median" to 1..1, "includes" to 2..2, "indexof" to 2..2, "lastindexof" to 2..2,
        "first" to 1..1, "last" to 1..1, "slice" to 3..3, "copy" to 1..1, "sort" to 1..2, "reverse" to 1..1, "fill" to 2..4,
        "concat" to 2..2, "join" to 1..2, "range" to 1..1, "variance" to 1..2, "mode" to 1..1,
    )

    private val DECL_COMMON = arrayOf("title", "shorttitle", "overlay", "format", "precision", "scale", "max_bars_back",
        "timeframe", "timeframe_gaps", "explicit_plot_zorder", "max_lines_count", "max_labels_count", "max_boxes_count",
        "calc_bars_count", "max_polylines_count", "dynamic_requests", "behind_chart")
    private val STRATEGY_EXTRA = arrayOf("pyramiding", "calc_on_order_fills", "calc_on_every_tick", "backtest_fill_limits_assumption",
        "default_qty_type", "default_qty_value", "initial_capital", "currency", "slippage", "commission_type", "commission_value",
        "process_orders_on_close", "close_entries_rule", "margin_long", "margin_short", "risk_free_rate", "use_bar_magnifier",
        "fill_orders_on_standard_ohlc")
    private val INPUT_TAIL = arrayOf("tooltip", "inline", "group", "confirm", "display", "active")
    private val PLOT_TAIL = arrayOf("editable", "show_last", "display", "format", "precision", "force_overlay")

    val SIGS: Map<String, Sig> = buildMap {
        put("indicator", s(1, *DECL_COMMON)); put("study", s(1, *DECL_COMMON))
        put("strategy", s(1, *DECL_COMMON, *STRATEGY_EXTRA))
        put("input", s(1, "defval", "title", "options", "minval", "maxval", "step", *INPUT_TAIL))
        put("input.int", s(1, "defval", "title", "minval", "maxval", "step", "options", *INPUT_TAIL))
        put("input.float", s(1, "defval", "title", "minval", "maxval", "step", "options", *INPUT_TAIL))
        put("input.bool", s(1, "defval", "title", *INPUT_TAIL))
        put("input.string", s(1, "defval", "title", "options", *INPUT_TAIL))
        put("input.source", s(1, "defval", "title", *INPUT_TAIL))
        put("input.timeframe", s(1, "defval", "title", "options", *INPUT_TAIL))
        put("input.session", s(1, "defval", "title", "options", *INPUT_TAIL))
        put("input.color", s(1, "defval", "title", *INPUT_TAIL))
        put("input.price", s(1, "defval", "title", *INPUT_TAIL))
        put("input.symbol", s(1, "defval", "title", *INPUT_TAIL))
        put("input.time", s(1, "defval", "title", *INPUT_TAIL))
        put("input.text_area", s(1, "defval", "title", *INPUT_TAIL))
        put("plot", s(1, "series", "title", "color", "linewidth", "style", "trackprice", "histbase", "offset", "join", *PLOT_TAIL))
        put("plotshape", s(1, "series", "title", "style", "location", "color", "offset", "text", "textcolor", "size", *PLOT_TAIL))
        put("plotchar", s(1, "series", "title", "char", "location", "color", "offset", "text", "textcolor", "size", *PLOT_TAIL))
        put("plotarrow", s(1, "series", "title", "colorup", "colordown", "offset", "minheight", "maxheight", *PLOT_TAIL))
        put("plotcandle", v(4)); put("plotbar", v(4))
        put("bgcolor", s(1, "color", "offset", "editable", "show_last", "title", "display", "force_overlay"))
        put("barcolor", s(1, "color", "offset", "editable", "show_last", "title", "display"))
        put("fill", v(2))
        put("hline", s(1, "price", "title", "color", "linestyle", "linewidth", "editable", "display"))
        put("alertcondition", s(1, "condition", "title", "message"))
        put("alert", s(1, "message", "freq"))
        val entry = arrayOf("id", "direction", "qty", "limit", "stop", "oca_name", "oca_type", "comment", "alert_message", "disable_alert", "when")
        put("strategy.entry", s(2, *entry)); put("strategy.order", s(2, *entry))
        put("strategy.close", s(1, "id", "comment", "qty", "qty_percent", "alert_message", "immediately", "disable_alert", "when"))
        put("strategy.close_all", s(0, "comment", "alert_message", "immediately", "disable_alert", "when"))
        put("strategy.exit", s(1, "id", "from_entry", "qty", "qty_percent", "profit", "limit", "loss", "stop", "trail_price", "trail_points",
            "trail_offset", "oca_name", "comment", "comment_profit", "comment_loss", "comment_trailing", "alert_message", "alert_profit",
            "alert_loss", "alert_trailing", "disable_alert", "when"))
        put("strategy.cancel", s(1, "id", "when")); put("strategy.cancel_all", s(0, "when"))
        for (n in listOf("ta.sma", "ta.ema", "ta.rma", "ta.wma", "ta.vwma", "ta.hma", "ta.rsi", "ta.mom", "ta.roc", "ta.cci",
                "ta.rising", "ta.falling", "math.sum", "ta.median")) put(n, s(2, "source", "length"))
        put("ta.stdev", s(2, "source", "length", "biased")); put("ta.variance", s(2, "source", "length", "biased"))
        put("ta.highest", s(1, "source", "length")); put("ta.lowest", s(1, "source", "length"))
        put("ta.highestbars", s(1, "source", "length")); put("ta.lowestbars", s(1, "source", "length"))
        put("ta.atr", s(1, "length")); put("ta.tr", s(0, "handle_na"))
        put("ta.crossover", s(2, "source1", "source2")); put("ta.crossunder", s(2, "source1", "source2")); put("ta.cross", s(2, "source1", "source2"))
        put("ta.change", s(1, "source", "length")); put("ta.cum", s(1, "source"))
        put("ta.macd", s(4, "source", "fastlen", "slowlen", "siglen"))
        put("ta.bb", s(3, "series", "length", "mult"))
        put("ta.supertrend", s(2, "factor", "atrPeriod"))
        put("ta.stoch", s(4, "source", "high", "low", "length"))
        put("ta.vwap", s(0, "source", "anchor", "stdev_mult"))
        put("ta.barssince", s(1, "condition")); put("ta.valuewhen", s(3, "condition", "source", "occurrence"))
        put("ta.dmi", s(2, "diLength", "adxSmoothing"))
        put("ta.pivothigh", s(2, "source", "leftbars", "rightbars")); put("ta.pivotlow", s(2, "source", "leftbars", "rightbars"))
        put("ta.linreg", s(3, "source", "length", "offset"))
        put("ta.wpr", s(1, "length"))
        put("math.abs", s(1, "number")); put("math.floor", s(1, "number")); put("math.ceil", s(1, "number"))
        put("math.sqrt", s(1, "number")); put("math.log", s(1, "number")); put("math.log10", s(1, "number"))
        put("math.exp", s(1, "number")); put("math.sign", s(1, "number"))
        put("math.round", s(1, "number", "precision")); put("math.pow", s(2, "base", "exponent"))
        put("math.max", v(1)); put("math.min", v(1)); put("math.avg", v(1))
        put("nz", s(1, "source", "replacement")); put("na", s(1, "x")); put("fixnan", s(1, "source"))
        put("int", s(1, "x")); put("float", s(1, "x")); put("bool", s(1, "x"))
        put("color.new", s(2, "color", "transp")); put("color.rgb", s(3, "red", "green", "blue", "transp"))
        put("color.from_gradient", s(5, "value", "bottom_value", "top_value", "bottom_color", "top_color"))
        put("str.tostring", s(1, "value", "format")); put("str.format", v(1))
        put("time", s(1, "timeframe", "session", "timezone", "bars_back")); put("timestamp", v(1))
        put("runtime.error", s(1, "message"))
        put("max_bars_back", s(2, "var", "num"))
        put("log.info", v(1)); put("log.warning", v(1)); put("log.error", v(1))
        val sec = arrayOf("symbol", "timeframe", "expression", "gaps", "lookahead", "ignore_invalid_symbol", "currency", "calc_bars_count")
        put("request.security", s(3, *sec)); put("security", s(3, *sec))
        put("timeframe.change", s(1, "timeframe")); put("timeframe.in_seconds", s(0, "timeframe"))
        for ((f, range) in ARRAY_FNS) put("array.$f", v(range.first))
    }

    /** Functions accepted but not drawn on the phone chart. */
    val IGNORED = setOf("plotcandle", "plotbar", "bgcolor", "barcolor", "fill", "alert",
        "max_bars_back", "log.info", "log.warning", "log.error")
    val DRAWING_NS = setOf("label", "line", "box", "table", "linefill", "polyline", "chart.point")
    val UNSUPPORTED_NS = setOf("matrix", "map", "ticker", "strategy.risk", "str")


    val SERIES = setOf("open", "high", "low", "close", "volume", "hl2", "hlc3", "ohlc4", "hlcc4", "time", "time_close", "time_tradingday",
        "bar_index", "last_bar_index", "last_bar_time", "year", "month", "weekofyear", "dayofmonth", "dayofweek", "hour", "minute", "second",
        "timenow", "na",
        "barstate.isfirst", "barstate.islast", "barstate.ishistory", "barstate.isrealtime", "barstate.isnew", "barstate.isconfirmed",
        "barstate.islastconfirmedhistory",
        "syminfo.ticker", "syminfo.tickerid", "syminfo.root", "syminfo.mintick", "syminfo.pointvalue", "syminfo.timezone",
        "syminfo.currency", "syminfo.type", "syminfo.description", "syminfo.prefix", "syminfo.session",
        "timeframe.period", "timeframe.multiplier", "timeframe.isintraday", "timeframe.isdaily", "timeframe.isweekly",
        "timeframe.ismonthly", "timeframe.isminutes", "timeframe.isseconds", "timeframe.isdwm",
        "strategy.position_size", "strategy.position_avg_price", "strategy.equity", "strategy.netprofit", "strategy.openprofit",
        "strategy.opentrades", "strategy.closedtrades", "strategy.wintrades", "strategy.losstrades", "strategy.initial_capital",
        "strategy.grossprofit", "strategy.grossloss", "strategy.max_drawdown", "strategy.position_entry_name",
        "ta.tr", "ta.vwap", "ta.obv", "ta.accdist", "math.pi", "math.e", "math.phi")

    val COLORS = mapOf("red" to "#F23645", "green" to "#089981", "blue" to "#2962FF", "white" to "#FFFFFF", "black" to "#363A45",
        "gray" to "#787B86", "orange" to "#FF9800", "yellow" to "#FFEB3B", "purple" to "#9C27B0", "teal" to "#009688",
        "aqua" to "#00BCD4", "lime" to "#00E676", "maroon" to "#880E4F", "navy" to "#311B92", "olive" to "#808000",
        "silver" to "#B2B5BE", "fuchsia" to "#E040FB")

    val CONSTS: Map<String, Any> = buildMap {
        for ((k, c) in COLORS) put("color.$k", c)
        put("strategy.long", "long"); put("strategy.short", "short")
        put("strategy.fixed", "fixed"); put("strategy.cash", "cash"); put("strategy.percent_of_equity", "percent_of_equity")
        put("strategy.commission.percent", "percent"); put("strategy.commission.cash_per_order", "cash_per_order")
        put("strategy.commission.cash_per_contract", "cash_per_contract")
        put("strategy.direction.all", "all"); put("strategy.direction.long", "long"); put("strategy.direction.short", "short")
        put("strategy.oca.none", "none"); put("strategy.oca.cancel", "cancel"); put("strategy.oca.reduce", "reduce")
        listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday").forEachIndexed { i, d -> put("dayofweek.$d", (i + 1).toDouble()) }
    }

    /** Namespaces whose members are styling constants: any member is accepted and means nothing to the maths. */
    val STYLE_NS = setOf("shape", "location", "size", "plot", "display", "hline", "extend", "xloc", "yloc", "format", "currency",
        "text", "font", "position", "alert", "barmerge", "order", "scale", "adjustment", "session", "line", "label", "dividends",
        "earnings", "splits", "settlement_as_close", "backadjustment")

    fun isKnownName(n: String): Boolean {
        if (n in SERIES || n in CONSTS) return true
        val dot = n.lastIndexOf('.')
        if (dot > 0) {
            val ns = n.substring(0, dot)
            if (ns in STYLE_NS) return true
        }
        return false
    }
}

/**
 * Compile-time checks that TradingView would also refuse: unknown names and functions,
 * wrong arguments, reassigning undeclared variables, declarations in the wrong place.
 */
internal class Checker(private val prog: List<Stmt>) {
    val errors = ArrayList<PineError>()
    val warnings = ArrayList<PineError>()
    private val funcs = HashMap<String, FuncDef>()
    private val scopes = ArrayList<HashSet<String>>()
    private var loopDepth = 0
    private var inFunc = false
    private var current: String? = null
    val plotCalls = ArrayList<Call>()
    val inputCalls = ArrayList<Call>()
    var declaration: Call? = null
    private val warned = HashSet<String>()

    private fun err(line: Int, col: Int, msg: String) { if (errors.size < 25) errors += PineError(line, col, msg) }
    private fun warn(line: Int, col: Int, msg: String) { if (warned.add(msg)) warnings += PineError(line, col, msg) }

    fun run() {
        scopes.add(HashSet())
        stmts(prog, top = true)
        if (declaration == null && errors.isEmpty())
            err(1, 1, "The script needs a declaration: indicator(\"My indicator\") or strategy(\"My strategy\")")
    }

    private fun declared(n: String) = scopes.any { n in it }

    private fun stmts(list: List<Stmt>, top: Boolean = false) { for (s in list) stmt(s, top) }

    private fun block(list: List<Stmt>) { scopes.add(HashSet()); stmts(list); scopes.removeAt(scopes.size - 1) }

    private fun stmt(s: Stmt, top: Boolean) {
        when (s) {
            is Decl -> {
                expr(s.value)
                if (scopes.size == 1 && (s.value as? Call)?.name?.let { it == "input" || it.startsWith("input.") } == true) inputGlobals += s.name
                if (s.name in scopes.last()) err(s.line, s.col, "'${s.name}' is already defined: use ':=' to give it a new value")
                if (s.name in funcs) err(s.line, s.col, "'${s.name}' is already a function name")
                scopes.last() += s.name
            }
            is TupleDecl -> {
                expr(s.value)
                for (n in s.names) {
                    if (n in scopes.last()) err(s.line, s.col, "'$n' is already defined: use ':=' to give it a new value")
                    scopes.last() += n
                }
            }
            is Assign -> {
                expr(s.value)
                if (!declared(s.name)) err(s.line, s.col, if (Builtins.isKnownName(s.name)) "'${s.name}' is built in and cannot be changed"
                    else "Undeclared identifier '${s.name}': declare it first with '${s.name} = ...'")
            }
            is If -> { expr(s.cond); block(s.then); s.orElse?.let { block(it) } }
            is For -> {
                expr(s.from); expr(s.to); s.by?.let { expr(it) }
                scopes.add(hashSetOf(s.v)); loopDepth++
                stmts(s.body)
                loopDepth--; scopes.removeAt(scopes.size - 1)
            }
            is While -> { expr(s.cond); loopDepth++; block(s.body); loopDepth-- }
            is Switch -> {
                s.subject?.let { expr(it) }
                for ((c, body) in s.cases) { c?.let { expr(it) }; block(body) }
            }
            is ForIn -> {
                expr(s.over)
                scopes.add(hashSetOf(s.item).apply { s.index?.let { add(it) } }); loopDepth++
                stmts(s.body)
                loopDepth--; scopes.removeAt(scopes.size - 1)
            }
            is Jump -> if (loopDepth == 0) err(s.line, s.col, "'${if (s.brk) "break" else "continue"}' can only be used inside a loop")
            is ExprStmt -> expr(s.e, statementLevel = true, top = top)
            is FuncDef -> {
                if (!top) err(s.line, s.col, "Functions must be declared at the top level, not inside a block")
                if (s.name in funcs) err(s.line, s.col, "Function '${s.name}' is already defined")
                if (Builtins.SIGS.containsKey(s.name)) warn(s.line, s.col, "'${s.name}' replaces the built-in function of that name")
                val was = inFunc; inFunc = true
                current = s.name
                scopes.add(s.params.map { it.first }.toHashSet())
                s.params.forEach { (_, d) -> d?.let { expr(it) } }
                stmts(s.body)
                scopes.removeAt(scopes.size - 1)
                inFunc = was; current = null
                // Registered after its body: a function cannot call itself (Pine has no recursion).
                funcs[s.name] = s
            }
        }
    }

    /**
     * [fn] is the user function whose body [x] is in (null at the top of the
     * expression), [locals] its parameters and variables, [seen] the functions
     * already walked for this call - a function's body runs on the higher
     * timeframe too, so the globals it reads are held to the same rule.
     */
    private fun secNames(x: Expr, at: Call, fn: String? = null, locals: Set<String> = emptySet(), seen: MutableSet<String> = HashSet()) {
        fun go(y: Expr) = secNames(y, at, fn, locals, seen)
        when (x) {
            is Name -> if (x.name !in locals && !Builtins.isKnownName(x.name) && x.name !in inputGlobals && scopes.first().contains(x.name))
                err(x.line, x.col, if (fn == null) "request.security can only use built-in values, inputs and functions, not '${x.name}': compute it inside the expression"
                    else "request.security can only use built-in values, inputs and functions, not '${x.name}' (read by ${fn}()): pass it to ${fn}() as an argument computed inside the expression")
            is Index -> { go(x.target); go(x.offset) }
            is Unary -> go(x.e)
            is Binary -> { go(x.a); go(x.b) }
            is Ternary -> { go(x.c); go(x.a); go(x.b) }
            is TupleLit -> x.items.forEach { go(it) }
            is IfExpr -> secStmts(listOf(x.stmt), at, fn, locals, seen)
            is Call -> {
                if (x.name.startsWith("strategy.") || x.name == "plot") err(x.line, x.col, "${x.name}() cannot run inside request.security")
                // a.size(): a method on a chart variable reads that variable just as a bare name would.
                val recv = x.name.substringBefore('.', "")
                if (recv.isNotEmpty() && x.name !in funcs && Builtins.SIGS[x.name] == null && recv !in locals && !Builtins.isKnownName(recv) &&
                    recv !in inputGlobals && scopes.first().contains(recv))
                    err(x.line, x.col, if (fn == null) "request.security can only use built-in values, inputs and functions, not '$recv': compute it inside the expression"
                        else "request.security can only use built-in values, inputs and functions, not '$recv' (read by ${fn}()): pass it to ${fn}() as an argument computed inside the expression")
                x.args.forEach { go(it.value) }
                val user = funcs[x.name]
                if (user != null && seen.add(user.name)) {
                    val own = HashSet<String>().apply { user.params.forEach { add(it.first) }; declaredIn(user.body, this) }
                    user.params.forEach { (_, d) -> d?.let { secNames(it, at, user.name, own, seen) } }
                    secStmts(user.body, at, user.name, own, seen)
                }
            }
            else -> {}
        }
    }

    private fun secStmts(list: List<Stmt>, at: Call, fn: String?, locals: Set<String>, seen: MutableSet<String>) {
        fun go(y: Expr) = secNames(y, at, fn, locals, seen)
        fun all(b: List<Stmt>) = secStmts(b, at, fn, locals, seen)
        for (s in list) when (s) {
            is Decl -> go(s.value)
            is TupleDecl -> go(s.value)
            is Assign -> go(s.value)
            is If -> { go(s.cond); all(s.then); s.orElse?.let { all(it) } }
            is For -> { go(s.from); go(s.to); s.by?.let { go(it) }; all(s.body) }
            is ForIn -> { go(s.over); all(s.body) }
            is While -> { go(s.cond); all(s.body) }
            is Switch -> { s.subject?.let { go(it) }; s.cases.forEach { (c, b) -> c?.let { go(it) }; all(b) } }
            is ExprStmt -> go(s.e)
            is Jump, is FuncDef -> {}
        }
    }

    /** Every name a function body declares, at any depth: its locals, never globals. */
    private fun declaredIn(list: List<Stmt>, out: MutableSet<String>) {
        for (s in list) when (s) {
            is Decl -> out += s.name
            is TupleDecl -> out += s.names
            is If -> { declaredIn(s.then, out); s.orElse?.let { declaredIn(it, out) } }
            is For -> { out += s.v; declaredIn(s.body, out) }
            is ForIn -> { out += s.item; s.index?.let { out += it }; declaredIn(s.body, out) }
            is While -> declaredIn(s.body, out)
            is Switch -> s.cases.forEach { declaredIn(it.second, out) }
            else -> {}
        }
    }

    /** Globals holding an input (constant over the run): request.security may use them. */
    private val inputGlobals = HashSet<String>()

    private fun expr(e: Expr, statementLevel: Boolean = false, top: Boolean = false) {
        when (e) {
            is Num, is Str, is Bool, is ColorLit -> {}
            is Name -> if (!declared(e.name) && !Builtins.isKnownName(e.name)) {
                val ns = e.name.substringBefore('.', "")
                err(e.line, e.col, when {
                    e.name in funcs || Builtins.SIGS.containsKey(e.name) -> "'${e.name}' is a function: call it with ()"
                    ns == "color" -> "Unknown colour '${e.name}'"
                    ns.isNotEmpty() && (ns in setOf("ta", "math", "strategy", "syminfo", "barstate", "timeframe")) -> "'${e.name}' is not a known ${ns}.* value"
                    else -> "Undeclared identifier '${e.name}'"
                })
            }
            is Index -> { expr(e.target); expr(e.offset) }
            is Unary -> expr(e.e)
            is Binary -> { expr(e.a); expr(e.b) }
            is Ternary -> { expr(e.c); expr(e.a); expr(e.b) }
            is TupleLit -> e.items.forEach { expr(it) }
            is IfExpr -> stmt(e.stmt, false)
            is Call -> call(e, statementLevel, top)
        }
    }

    private fun call(e: Call, statementLevel: Boolean, top: Boolean) {
        e.args.forEach { expr(it.value) }
        if (e.name == current) { err(e.line, e.col, "${e.name}() cannot call itself: Pine functions are not recursive"); return }
        val user = funcs[e.name]
        if (user != null) {
            val names = user.params.map { it.first }
            val positional = e.args.count { it.name == null }
            if (positional > names.size) err(e.line, e.col, "${e.name}() takes ${names.size} argument(s), got $positional")
            val given = HashSet<String>()
            e.args.forEachIndexed { i, a ->
                val n = a.name ?: names.getOrNull(i) ?: return@forEachIndexed
                if (n !in names) err(e.line, e.col, "${e.name}() has no parameter '$n'")
                given += n
            }
            user.params.filter { it.second == null && it.first !in given }.forEach { err(e.line, e.col, "${e.name}() is missing the argument '${it.first}'") }
            return
        }
        val ns = e.name.substringBeforeLast('.', "")
        val sig = Builtins.SIGS[e.name]
        if (sig == null) {
            when {
                ns in Builtins.DRAWING_NS || e.name.startsWith("chart.point") -> {
                    warn(e.line, e.col, "$ns.* drawings are accepted but not drawn on the phone chart"); return
                }
                e.name.startsWith("strategy.risk.") -> { warn(e.line, e.col, "strategy.risk.* rules are ignored in the phone backtest"); return }
                // arr.push(x): a method on an array variable.
                ns.isNotEmpty() && !ns.contains('.') && declared(ns) && Builtins.SIGS.containsKey("array.${e.name.substringAfter('.')}") -> {
                    val r = Builtins.ARRAY_FNS[e.name.substringAfter('.')]!!
                    if (e.args.size + 1 !in r) err(e.line, e.col, "${e.name}(): wrong number of arguments")
                }
                ns == "request" -> err(e.line, e.col, "${e.name}() is not supported: only request.security on the chart's own symbol")
                ns in Builtins.UNSUPPORTED_NS -> err(e.line, e.col, "${e.name}() is not supported yet")
                e.name in funcs -> {}
                else -> err(e.line, e.col, "Unknown function '${e.name}'")
            }
            return
        }
        if (!sig.variadic) {
            val positional = e.args.count { it.name == null }
            if (positional > sig.params.size) err(e.line, e.col, "${e.name}() takes at most ${sig.params.size} argument(s), got $positional")
            val given = HashSet<String>()
            e.args.forEachIndexed { i, a ->
                val n = a.name ?: sig.params.getOrNull(i) ?: return@forEachIndexed
                if (n !in sig.params) err(e.line, e.col, "${e.name}() has no parameter '$n'")
                if (!given.add(n)) err(e.line, e.col, "${e.name}(): '$n' is given twice")
            }
            val one = e.name in setOf("ta.highest", "ta.lowest", "ta.highestbars", "ta.lowestbars")
            if (!one) sig.params.take(sig.required).filter { it !in given }.forEach { err(e.line, e.col, "${e.name}() is missing the argument '$it'") }
        } else if (e.args.size < sig.required) err(e.line, e.col, "${e.name}() needs at least ${sig.required} argument(s)")
        else if (e.name.startsWith("array.")) Builtins.ARRAY_FNS[e.name.removePrefix("array.")]?.let { r ->
            if (e.args.size > r.last) err(e.line, e.col, "${e.name}() takes at most ${r.last} argument(s)")
        }
        if (e.name == "request.security" || e.name == "security") {
            // The expression runs on the higher timeframe's own candles: it may use built-in values,
            // inputs and functions, not the script's other variables (those live on the chart's candles).
            val x = e.args.firstOrNull { it.name == "expression" }?.value ?: e.args.filter { it.name == null }.getOrNull(2)?.value
            x?.let { secNames(it, e) }
        }
        if (e.name in Builtins.IGNORED) warn(e.line, e.col, "${e.name}() is accepted but not drawn on the phone chart")
        if (e.name == "strategy.exit" && e.args.any { it.name?.startsWith("oca") == true })
            warn(e.line, e.col, "OCA groups are ignored in the phone backtest")
        when (e.name) {
            "indicator", "study", "strategy" -> {
                if (!top || !statementLevel) err(e.line, e.col, "${e.name}() must be a statement of its own at the top level")
                else if (declaration != null) err(e.line, e.col, "Only one indicator() or strategy() declaration is allowed")
                else declaration = e
            }
            "plot", "hline" -> {
                if (plotCalls.size >= 64) err(e.line, e.col, "At most 64 plots per script")
                if (!top || inFunc) err(e.line, e.col, "Cannot use '${e.name}' in a local scope: plot at the top level (use a ternary for conditions)")
                else plotCalls += e
            }
        }
        if (e.name == "input" || e.name.startsWith("input.")) {
            if (inputCalls.size >= 100) err(e.line, e.col, "At most 100 inputs per script")
            if (inFunc) err(e.line, e.col, "Inputs must be declared at the top level")
            else inputCalls += e
        }
        if (e.name.startsWith("strategy.") && e.name != "strategy" && declaration != null && declaration!!.name != "strategy")
            err(e.line, e.col, "${e.name}() needs a strategy() declaration, not indicator()")
    }
}
