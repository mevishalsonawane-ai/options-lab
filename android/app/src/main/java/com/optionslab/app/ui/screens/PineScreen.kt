package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.PineScripts
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.LinePlot
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.PlotLine
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val secureDialog get() = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

/**
 * Pine scripts: write or paste TradingView Pine (v5), see compile errors as you type,
 * backtest on NIFTY / BANKNIFTY candles, draw it on the chart, and let its signals trade.
 */
@Composable
fun PineScreen(model: AppModel, onOpenChart: () -> Unit = {}) {
    var editing by remember { mutableStateOf<Long?>(null) }         // 0 = a new script
    var draft by remember { mutableStateOf<PineScripts.Item?>(null) }
    val e = editing
    if (e == null) PineList(onOpen = { editing = it.id; draft = it }, onNew = { editing = 0; draft = it })
    else draft?.let { d -> PineEditor(model, d, onOpenChart, onClose = { editing = null; draft = null }) }
}

@Composable
private fun PineList(onOpen: (PineScripts.Item) -> Unit, onNew: (PineScripts.Item) -> Unit) {
    val p = LocalPalette.current
    val items by PineScripts.items.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Note("Write or paste a TradingView Pine script (v5). It is checked as you type, can be backtested on NIFTY or BANKNIFTY candles, drawn on the chart, and its buy/sell signals can place orders by themselves.")
        BrassButton("New script", Modifier.fillMaxWidth()) { choosing = true }
        if (items.isEmpty()) Text("No scripts yet. Start from an example: they compile and backtest as they are.",
            style = Type.bodySmall.copy(color = p.inkSoft))
        for (it in items.sortedByDescending { it.updated }) {
            val c = remember(it.code) { PineScripts.compile(it.code) }
            val ok = c as? Pine.Compiled.Ok
            LedgerCard(onClick = { onOpen(it) }, accent = if (it.auto.on) p.verdigris else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.name, style = Type.title.copy(color = p.ink, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Stamp(when {
                        ok == null -> "ERRORS"
                        ok.script.kind == Pine.Kind.STRATEGY -> "STRATEGY"
                        else -> "INDICATOR"
                    }, if (ok == null) p.oxblood else p.ink)
                }
                Spacer(Modifier.height(4.dp))
                val bits = buildList {
                    if (c is Pine.Compiled.Failed) add("${c.errors.size} error${if (c.errors.size == 1) "" else "s"} · line ${c.errors.first().line}")
                    if (it.onChart) add("on the chart")
                    if (it.auto.on) add("auto-trading ${it.auto.symbol} ${it.auto.interval}")
                    if (isEmpty()) add("tap to edit, backtest or put on the chart")
                }
                Text(bits.joinToString(" · "), style = Type.bodySmall.copy(color = if (c is Pine.Compiled.Failed) p.oxblood else p.inkSoft))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, confirmButton = {}, properties = secureDialog,
        dismissButton = { TextButton({ choosing = false }) { Text("Cancel") } },
        title = { Text("Start from") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PineScripts.SAMPLES.forEach { s ->
                    Text(s.name, style = Type.body.copy(color = p.ink), modifier = Modifier.fillMaxWidth()
                        .background(p.chip, RoundedCornerShape(10.dp)).clickable {
                            choosing = false
                            onNew(PineScripts.Item(0, s.name.substringBefore(" ("), s.code))
                        }.padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        })
}

@Composable
private fun PineEditor(model: AppModel, start: PineScripts.Item, onOpenChart: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf(start.name) }
    var code by remember { mutableStateOf(TextFieldValue(start.code)) }
    var result by remember { mutableStateOf<Pine.Compiled?>(null) }
    var saved by remember { mutableStateOf(start.id != 0L) }
    var tab by remember { mutableStateOf("code") }
    var deleting by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { onClose() }

    // Checked 0.4 s after the last keystroke.
    LaunchedEffect(code.text) {
        delay(400)
        result = withContext(Dispatchers.Default) { PineScripts.compile(code.text) }
    }
    val ok = (result as? Pine.Compiled.Ok)?.script
    val errors = (result as? Pine.Compiled.Failed)?.errors.orEmpty()
    val warnings = when (val r = result) { is Pine.Compiled.Ok -> r.script.warnings; is Pine.Compiled.Failed -> r.warnings; else -> emptyList() }
    val dirty = code.text != item.code || name != item.name
    // While it trades by itself its code and inputs stay as they were armed: a change would alter
    // real orders without the PIN. Switch auto-trade off to edit.
    val allItems by PineScripts.items.collectAsState()
    val armed = allItems.firstOrNull { it.id == item.id }?.auto?.on == true

    fun save(): PineScripts.Item {
        val s = PineScripts.put(item.copy(name = name.ifBlank { ok?.title ?: "Script" }, code = code.text))
        item = s; saved = true
        return s
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClose) { Text("‹ Scripts") }
            Spacer(Modifier.weight(1f))
            listOf("code" to "Code", "test" to "Backtest", "auto" to "Auto-trade").forEach { (k, l) ->
                com.optionslab.app.ui.components.Token(l, tab == k) { tab = k }
            }
        }
        // Status line: compiles, or how many errors.
        val status = when {
            result == null -> "Checking…" to p.inkSoft
            ok != null -> ("✓ Compiles · ${if (ok.kind == Pine.Kind.STRATEGY) "strategy" else "indicator"} · ${ok.plots.size} plot${if (ok.plots.size == 1) "" else "s"}" +
                (if (ok.signals.isNotEmpty()) " · ${ok.signals.size} signal${if (ok.signals.size == 1) "" else "s"}" else "") +
                (if (ok.inputs.isNotEmpty()) " · ${ok.inputs.size} input${if (ok.inputs.size == 1) "" else "s"}" else "")) to p.verdigris
            else -> "✗ ${errors.size} error${if (errors.size == 1) "" else "s"}" to p.oxblood
        }
        Text(status.first, style = Type.label.copy(color = status.second, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().background(p.paperDeep).padding(horizontal = 14.dp, vertical = 6.dp))
        when (tab) {
            "code" -> Column(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CodeField(code, { if (it.text.length <= Pine.MAX_CHARS) code = it }, errors.map { it.line }.toSet())
                    errors.forEach { er ->
                        Text("Line ${er.line}:${er.col}  ${er.message}", style = Type.bodySmall.copy(color = p.oxblood),
                            modifier = Modifier.fillMaxWidth().clickable { code = code.copy(selection = TextRange(offsetOf(code.text, er.line, er.col))) }
                                .padding(vertical = 2.dp))
                    }
                    warnings.forEach { w -> Text("Line ${w.line}: ${w.message}", style = Type.bodySmall.copy(color = p.amber)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrassButton(if (saved && !dirty) "Saved" else "Save", Modifier.weight(1f), enabled = (dirty || !saved) && !armed) { save() }
                        BrassButton("Delete", Modifier.weight(1f), tone = p.inkSoft, enabled = saved) { deleting = true }
                    }
                    if (armed && dirty) Note("Auto-trade is on: switch it off (Auto-trade tab) to save changes. Until then it trades with the code it was switched on with.")
                    if (ok != null) {
                        ToggleRow("Show on the chart", if (ok.overlay) "Drawn over the candles, with its buy/sell marks" else "Drawn in its own pane under the candles",
                            item.onChart) { on ->
                            val s = if (armed) item else save()
                            PineScripts.setOnChart(s.id, on); item = PineScripts.get(s.id) ?: s
                        }
                        if (item.onChart) TextButton(onOpenChart) { Text("Open the chart ›") }
                    } else if (errors.isNotEmpty()) Note("Fix the errors to backtest it or put it on the chart. Tap an error to jump to it.")
                    Text("Supported: variables, var, :=, if/for/while, functions and tuples, x[n] history, inputs, ta.* (sma ema rma wma vwma hma rsi atr stdev highest lowest crossover crossunder change macd bb supertrend stoch cci vwap dmi pivots linreg …), math.*, plot, hline, plotshape, alertcondition and strategy.entry / exit / close. Not yet: request.security, arrays, switch.",
                        style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
                    Spacer(Modifier.height(12.dp))
                }
                KeyStrip { ins -> code = insert(code, ins) }
            }
            "test" -> if (ok == null) Box(Modifier.fillMaxSize().padding(20.dp)) { Note("The script has errors: fix them in Code first.") }
                else PineBacktest(item, ok) { inputs -> if (!armed) item = PineScripts.put(save().copy(inputs = inputs)) }
            else -> if (ok == null) Box(Modifier.fillMaxSize().padding(20.dp)) { Note("The script has errors: fix them in Code first.") }
                else PineAutoPanel(model, item, ok, save = { save() }) { item = it }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, properties = secureDialog,
        confirmButton = { TextButton({ deleting = false; PineScripts.delete(item.id); onClose() }) { Text("Delete", color = p.oxblood) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep") } },
        title = { Text("Delete ${item.name}?") }, text = { Text("The script is removed from this phone and from the chart." +
            (if (armed) " It stops auto-trading and sells what it holds." else "") + " This cannot be undone.") })
}

private fun offsetOf(text: String, line: Int, col: Int): Int {
    var off = 0; var l = 1
    while (l < line && off < text.length) { val nl = text.indexOf('\n', off); if (nl < 0) return text.length; off = nl + 1; l++ }
    return (off + col - 1).coerceIn(0, text.length)
}

private fun insert(v: TextFieldValue, s: String): TextFieldValue {
    val a = v.selection.min; val b = v.selection.max
    val t = v.text.substring(0, a) + s + v.text.substring(b)
    return TextFieldValue(t, TextRange(a + s.length))
}

/** The code, with line numbers (error lines in red); no wrapping, so numbers stay on their lines. */
@Composable
private fun CodeField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, errorLines: Set<Int>) {
    val p = LocalPalette.current
    val lines = value.text.count { it == '\n' } + 1
    Row(Modifier.fillMaxWidth().heightIn(min = 260.dp).border(1.dp, p.rule, RoundedCornerShape(10.dp)).background(p.card, RoundedCornerShape(10.dp))
        .padding(vertical = 8.dp)) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp)) {
            for (i in 1..lines) Text("$i", style = Mono.copy(color = if (i in errorLines) p.oxblood else p.inkFaint,
                fontWeight = if (i in errorLines) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.widthIn(min = 22.dp))
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            BasicTextField(value, onChange, textStyle = Mono.copy(color = p.ink), cursorBrush = SolidColor(p.brass),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.widthIn(min = 280.dp).padding(end = 12.dp))
        }
    }
}

/** Keys a phone keyboard hides: indent, brackets, operators. */
@Composable
private fun KeyStrip(onKey: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.paperDeep).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("⇥" to "    ", "(" to "(", ")" to ")", "[" to "[", "]" to "]", "=" to "=", ":=" to " := ", "\"" to "\"", "," to ", ",
            "." to ".", "?" to " ? ", ":" to " : ", ">" to " > ", "<" to " < ", "_" to "_", "#" to "#").forEach { (l, s) ->
            Text(l, style = Mono.copy(color = p.ink, fontWeight = FontWeight.Bold), modifier = Modifier.background(p.chip, RoundedCornerShape(8.dp))
                .clickable { onKey(s) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

// ---- backtest ------------------------------------------------------------------------------

private data class TestRun(val run: Pine.Run, val bars: List<Pine.Bar>, val symbol: String, val interval: String)

@Composable
private fun PineBacktest(item: PineScripts.Item, s: Pine.Script, onInputs: (Map<String, String>) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var symbol by remember { mutableStateOf(item.auto.symbol) }
    var interval by remember { mutableStateOf(item.auto.interval) }
    val periods = when (interval) { "1m" -> listOf(5, 15, 30); "5m", "15m" -> listOf(30, 90, 180); "1h" -> listOf(90, 180, 365); else -> listOf(365, 1095, 1825) }
    var days by remember(interval) { mutableStateOf(periods[1]) }
    val inputs = remember { mutableStateMapOf<String, String>().apply { s.inputs.forEach { d -> put(d.key, item.inputs[d.key] ?: d.default?.let { v -> if (v is Double && v == Math.floor(v)) v.toLong().toString() else v.toString() } ?: "") } } }
    var qty by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var res by remember { mutableStateOf<TestRun?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ParamTokens("Symbol", listOf("NIFTY", "BANKNIFTY").map { it to (it == symbol) }) { symbol = listOf("NIFTY", "BANKNIFTY")[it] }
        ParamTokens("Candles", listOf("1m", "5m", "15m", "1h", "1D").map { it to (it == interval) }) { interval = listOf("1m", "5m", "15m", "1h", "1D")[it] }
        ParamTokens("Period", periods.map { (if (it >= 365) "${it / 365} yr" else "$it days") to (it == days) }) { days = periods[it] }
        if (s.inputs.isNotEmpty()) {
            Text("INPUTS", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 8.dp))
            s.inputs.forEach { d ->
                when (d.kind) {
                    "bool" -> ToggleRow(d.key, null, inputs[d.key] == "true") { inputs[d.key] = it.toString() }
                    "source" -> ParamTokens(d.key, listOf("close", "open", "high", "low", "hl2", "hlc3", "ohlc4").map { it to (it == inputs[d.key]) }) {
                        inputs[d.key] = listOf("close", "open", "high", "low", "hl2", "hlc3", "ohlc4")[it]
                    }
                    "int", "float", "price" -> OutlinedTextField(inputs[d.key] ?: "", { v -> inputs[d.key] = v.filter { it.isDigit() || it == '.' || it == '-' }.take(12) },
                        label = { Text(d.key) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    else -> OutlinedTextField(inputs[d.key] ?: "", { inputs[d.key] = it.take(40) }, label = { Text(d.key) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (s.kind == Pine.Kind.STRATEGY) OutlinedTextField(qty, { v -> qty = v.filter { it.isDigit() || it == '.' }.take(8) },
            label = { Text("Quantity per order (blank: the script's own, ${s.settings.qtyValue.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }} ${s.settings.qtyType.replace('_', ' ')})") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        BrassButton("Run backtest", Modifier.fillMaxWidth().padding(top = 6.dp), busy = busy) {
            busy = true; error = null
            val ins = inputs.toMap()
            onInputs(ins)
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val to = System.currentTimeMillis() / 1000
                        val bars = ChartFeed.bars(symbol, interval, to - days * 86400L, null).map { PineScripts.toPine(it) }
                        if (bars.isEmpty()) throw IllegalStateException("No $symbol candles for that period")
                        val values = PineScripts.inputValues(PineScripts.Item(0, "", "", inputs = ins), s)
                        TestRun(withContext(Dispatchers.Default) { Pine.run(s, bars, values, symbol, interval, qty.toDoubleOrNull()?.takeIf { it > 0 }, budgetMs = 60_000) }, bars, symbol, interval)
                    }
                }
                busy = false
                r.onSuccess { res = it }.onFailure { error = it.message ?: "The backtest failed" }
            }
        }
        error?.let { Text(it, style = Type.bodySmall.copy(color = p.oxblood)) }
        res?.let { BacktestResult(it, s) }
        Spacer(Modifier.height(24.dp))
    }
}

private fun fmtTime(t: Long, daily: Boolean): String = Instant.ofEpochSecond(t).atZone(IST)
    .format(DateTimeFormatter.ofPattern(if (daily) "d MMM yy" else "d MMM HH:mm", Locale.ENGLISH))

@Composable
private fun BacktestResult(t: TestRun, s: Pine.Script) {
    val p = LocalPalette.current
    val r = t.run
    val daily = t.interval == "1D"
    val first = t.bars.first().time; val last = t.bars.last().time
    Text("${t.symbol} · ${t.interval} · ${t.bars.size} candles · ${fmtTime(first, true)} – ${fmtTime(last, true)}",
        style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
    r.error?.let { Text("Stopped: $it", style = Type.bodySmall.copy(color = p.oxblood)) }
    val rep = r.report
    if (rep == null) {
        LedgerCard(title = "Signals") {
            if (s.signals.isEmpty()) Text("An indicator has no trades. Add plotshape(buy, \"Buy\") and plotshape(sell, \"Sell\") (or alertcondition) to get signals it can trade on.",
                style = Type.bodySmall.copy(color = p.inkSoft))
            s.signals.forEachIndexed { i, name ->
                val hits = r.signals[i].indices.filter { r.signals[i][it] }
                LedgerLine(name, "${hits.size} times" + (hits.lastOrNull()?.let { " · last ${fmtTime(t.bars[it].time, daily)}" } ?: ""))
            }
        }
        return
    }
    val gain = if (rep.netProfit >= 0) p.verdigris else p.oxblood
    LedgerCard(title = "Result") {
        LedgerLine("Net profit", "${rs(rep.netProfit, sign = true)}  (${String.format(Locale.ENGLISH, "%+.2f%%", rep.netProfitPct)})", gain)
        LedgerLine("Closed trades", "${rep.closedTrades}  ·  ${rep.winners} won, ${rep.losers} lost")
        LedgerLine("Win rate", String.format(Locale.ENGLISH, "%.1f%%", rep.winRate))
        LedgerLine("Profit factor", rep.profitFactor?.let { String.format(Locale.ENGLISH, "%.2f", it) } ?: "—")
        LedgerLine("Max drawdown", "${rs(-rep.maxDrawdown)}  (${String.format(Locale.ENGLISH, "%.2f%%", rep.maxDrawdownPct)})", p.oxblood)
        LedgerLine("Average trade", rs(rep.avgTrade, sign = true))
        LedgerLine("Largest win / loss", "${rs(rep.largestWin, sign = true)} / ${rs(rep.largestLoss, sign = true)}")
        LedgerLine("Average bars in a trade", String.format(Locale.ENGLISH, "%.1f", rep.avgBarsInTrade))
        LedgerLine("Commission", rs(rep.commission))
        if (rep.trades.any { it.open }) LedgerLine("Open trade (not counted)", rs(rep.openPnl, sign = true))
        LedgerLine("Buy & hold ${t.symbol}", String.format(Locale.ENGLISH, "%+.2f%%", rep.buyHoldPct))
        Spacer(Modifier.height(8.dp))
        val step = maxOf(1, rep.equity.size / 400)
        val eq = rep.equity.toList().filterIndexed { i, _ -> i % step == 0 }
        if (eq.size >= 2) {
            Text("EQUITY", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp))
            LinePlot(eq.indices.map { it.toDouble() }, listOf(PlotLine(eq, gain)), height = 140.dp)
        }
        Text("Fills follow TradingView: orders fill at the next candle's open; stops and targets inside a candle, open → nearer extreme → far extreme → close. Index points × quantity; no option premium, slippage or charges beyond the script's commission.",
            style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
    }
    var all by remember(r) { mutableStateOf(false) }
    val trades = rep.trades.asReversed()
    LedgerCard(title = "Trades (${rep.trades.size})") {
        (if (all) trades else trades.take(30)).forEach { tr ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (tr.long) "LONG" else "SHORT"} ${tr.entryId} · ${num(tr.qty)}", style = Type.label.copy(color = if (tr.long) p.verdigris else p.oxblood, fontSize = 12.sp))
                    Text("${fmtTime(tr.entryTime, daily)} @ ${num(tr.entryPrice)} → ${if (tr.open) "open" else fmtTime(tr.exitTime, daily)} @ ${num(tr.exitPrice)}" +
                        if (tr.open) "" else " · ${tr.exitId}", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
                }
                Text(rs(tr.pnl, sign = true), style = Type.figure.copy(color = if (tr.pnl >= 0) p.verdigris else p.oxblood, fontSize = 13.sp))
            }
            Rule()
        }
        if (!all && trades.size > 30) TextButton({ all = true }) { Text("Show all ${trades.size}") }
    }
}

private fun num(x: Double) = if (x == Math.floor(x) && kotlin.math.abs(x) < 1e12) String.format(Locale.ENGLISH, "%,d", x.toLong()) else String.format(Locale.ENGLISH, "%,.2f", x)


// ---- auto-trade ----------------------------------------------------------------------------

@Composable
private fun PineAutoPanel(model: AppModel, start: PineScripts.Item, s: Pine.Script, save: () -> PineScripts.Item, onItem: (PineScripts.Item) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val items by PineScripts.items.collectAsState()
    val item = items.firstOrNull { it.id == start.id } ?: start
    val settings by model.settings.collectAsState()
    val live = settings.live && settings.allowRealOrders
    val held by com.optionslab.app.data.PineAuto.held.collectAsState()
    val log by com.optionslab.app.data.PineAuto.log.collectAsState()
    var auth by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.optionslab.app.data.PineAuto.load() } }
    val a = item.auto
    val strategy = s.kind == Pine.Kind.STRATEGY
    val usable = strategy || s.signals.isNotEmpty()

    fun set(new: PineScripts.Auto) {
        val base = if (item.id == 0L) save() else item
        PineScripts.setAuto(base.id, new)
        PineScripts.get(base.id)?.let(onItem)
    }
    fun arm(on: Boolean, pin: Boolean) {
        busy = true
        scope.launch {
            val id = (if (item.id == 0L) save() else item).id
            withContext(Dispatchers.IO) { com.optionslab.app.data.PineAuto.arm(id, on, pin) }
            PineScripts.get(id)?.let(onItem)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Note("On every completed candle the script runs on the symbol below. When its signal changes the app buys the ATM option of the nearest expiry after today (a CALL on buy, a PUT on sell, or just exits) and sells the one it held. Market MIS orders; everything is sold at 15:15.")
        if (!usable) {
            Text("This indicator has no signals to trade on. Add for example:\nplotshape(buy, \"Buy\")\nplotshape(sell, \"Sell\")",
                style = Mono.copy(color = p.oxblood))
            return@Column
        }
        LedgerCard(accent = if (a.on) (if (live) p.oxblood else p.verdigris) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (a.on) "Auto-trading" else "Off", style = Type.title.copy(color = p.ink))
                    Text(if (live) "Follows the app switch: LIVE (real money at Zerodha)" else "Follows the app switch: PAPER",
                        style = Type.bodySmall.copy(color = if (live) p.oxblood else p.inkSoft))
                }
                androidx.compose.material3.Switch(a.on, { on ->
                    if (busy) return@Switch
                    if (on && live) auth = true else arm(on, false)
                })
            }
            held[item.id]?.let { h ->
                Spacer(Modifier.height(6.dp))
                LedgerLine("Holding", "${h.qty} ${h.kite ?: h.symbol}${if (h.live) " · LIVE" else ""}")
                LedgerLine("Bought at", String.format(Locale.ENGLISH, "%.2f", h.entry))
            }
        }
        val locked = a.on
        if (locked) Text("Switch it off to change these.", style = Type.bodySmall.copy(color = p.inkFaint))
        ParamTokens("Symbol", listOf("NIFTY", "BANKNIFTY").map { it to (it == a.symbol) }) { if (!locked) set(a.copy(symbol = listOf("NIFTY", "BANKNIFTY")[it])) }
        ParamTokens("Candles", listOf("1m", "5m", "15m", "1h").map { it to (it == a.interval) }) { if (!locked) set(a.copy(interval = listOf("1m", "5m", "15m", "1h")[it])) }
        ParamTokens("Lots", listOf(1, 2, 3, 5, 10).map { "$it" to (it == a.lots) }) { if (!locked) set(a.copy(lots = listOf(1, 2, 3, 5, 10)[it])) }
        if (strategy) {
            val opts = listOf("strategy") + s.signals
            ParamTokens("Go long on", opts.map { (if (it == "strategy") "the strategy's entries" else it) to (it == a.buy) }) { i ->
                if (!locked) set(a.copy(buy = opts[i], sell = if (opts[i] == "strategy") "strategy" else a.sell.takeIf { it != "strategy" } ?: s.signals.getOrElse(1) { s.signals.first() }))
            }
            if (a.buy != "strategy") ParamTokens("Go short on", s.signals.map { it to (it == a.sell) }) { i -> if (!locked) set(a.copy(sell = s.signals[i])) }
        } else {
            val buy = a.buy.takeIf { it in s.signals } ?: s.signals.first()
            val sell = a.sell.takeIf { it in s.signals } ?: s.signals.getOrElse(1) { s.signals.first() }
            if (buy != a.buy || sell != a.sell) LaunchedEffect(Unit) { set(a.copy(buy = buy, sell = sell)) }
            ParamTokens("Buy signal", s.signals.map { it to (it == buy) }) { i -> if (!locked) set(a.copy(buy = s.signals[i])) }
            ParamTokens("Sell signal", s.signals.map { it to (it == sell) }) { i -> if (!locked) set(a.copy(sell = s.signals[i])) }
        }
        ParamTokens("On a sell signal", listOf("Buy a PUT" to (a.shortWith == "put"), "Just exit" to (a.shortWith == "exit"))) { i ->
            if (!locked) set(a.copy(shortWith = if (i == 0) "put" else "exit"))
        }
        val mine = log.filter { it.script == item.id }.takeLast(40).asReversed()
        if (mine.isNotEmpty()) LedgerCard(title = "Activity") {
            mine.forEach { l ->
                Text("${Instant.ofEpochMilli(l.at).atZone(IST).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH))}  ${l.text}",
                    style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Text("It runs while the market watch runs (the app's background notification). A newly switched-on script waits for the next change of signal. The kill switch and the day's stop sell and stop it.",
            style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
        Spacer(Modifier.height(20.dp))
    }
    if (auth) Reauth(model, onOk = { auth = false; arm(true, true) }, onCancel = { auth = false },
        why = "Enter your app PIN to let this Pine script trade on Zerodha. It then places real orders by itself until you switch it off.")
}
