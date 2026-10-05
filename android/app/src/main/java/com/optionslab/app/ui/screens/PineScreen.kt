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
import com.optionslab.app.ui.components.TextButton
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
/** Indices a Pine script can run on (option data for premium backtests: NIFTY and BANKNIFTY). */
private val PINE_SYMBOLS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

private val secureDialog get() = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

/**
 * Pine scripts: write or paste TradingView Pine (v5), see compile errors as you type,
 * backtest on NIFTY / BANKNIFTY candles, draw it on the chart, and let its signals trade.
 */
@Composable
fun PineScreen(model: AppModel, onOpenChart: () -> Unit = {}) {
    PineContent(PineEnv(model.viewModelScope, model.settings, reauth = { why, onOk, onCancel -> Reauth(model, onOk = onOk, onCancel = onCancel, why = why) }),
        onOpenChart)
}

/**
 * What the Pine page needs from the app: a scope that outlives the page (saves and backtests land even if it is
 * left), the Paper/Live switch, the PIN or fingerprint prompt, and the candles a backtest runs on. The app passes
 * the model's scope and settings, [Reauth] and [ChartFeed.bars]; tests pass their own, so no [AppModel] is built.
 */
internal class PineEnv(
    val scope: kotlinx.coroutines.CoroutineScope,
    val settings: kotlinx.coroutines.flow.StateFlow<com.optionslab.app.data.AppSettings>,
    val reauth: @Composable (why: String, onOk: () -> Unit, onCancel: () -> Unit) -> Unit,
    val bars: suspend (symbol: String, interval: String, fromSec: Long) -> List<com.optionslab.engine.Upstox.Bar> =
        { sym, iv, from -> ChartFeed.bars(sym, iv, from, null) },
)

@Composable
internal fun PineContent(env: PineEnv, onOpenChart: () -> Unit = {}) {
    // A draft from before "erase everything" is dropped with the data.
    val wiped by com.optionslab.app.ui.wipes.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val d = PineSession.open?.takeIf { it.wipe == wiped }
    if (d == null) PineList(onOpen = { PineSession.open = PineDraft(it, wiped) }, onNew = { PineSession.open = PineDraft(it, wiped) })
    else androidx.compose.runtime.key(d) { PineEditor(env, d, onOpenChart, onClose = { PineSession.open = null }) }
}

/**
 * The script being edited, held outside composition: the app composes only the page on show, so
 * a tab switch or another Lab page would otherwise drop unsaved edits and the last backtest.
 * Memory only: nothing here is written until Save.
 */
private class PineDraft(start: PineScripts.Item, val wipe: Int) {
    var item by mutableStateOf(start)                      // as last saved (id 0 = a new script)
    var name by mutableStateOf(start.name)
    var code by mutableStateOf(TextFieldValue(start.code))
    var saved by mutableStateOf(start.id != 0L)
    var tab by mutableStateOf("code")
    // The backtest: its result is kept, and a run carries on if the page is left meanwhile.
    var test by mutableStateOf<TestRun?>(null)
    var testing by mutableStateOf(false)
    var stage by mutableStateOf<String?>(null)
    var testError by mutableStateOf<String?>(null)
    /** One save at a time, so a new script is never created twice. */
    val saving = kotlinx.coroutines.sync.Mutex()
}

private object PineSession {
    var open by mutableStateOf<PineDraft?>(null)
}

/** The index a backtest asked the chart to show, taken (once) by the app when the Lab opens the chart. */
private var pineChartAsk: Pair<String, String>? = null
fun takePineChartAsk(): Pair<String, String>? = pineChartAsk.also { pineChartAsk = null }

@Composable
private fun PineList(onOpen: (PineScripts.Item) -> Unit, onNew: (PineScripts.Item) -> Unit) {
    val p = LocalPalette.current
    val items by PineScripts.items.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Note("Write or paste a TradingView Pine script (v5). It is checked as you type, can be backtested on NIFTY or BANKNIFTY candles, drawn on the chart, and its buy/sell signals can place orders by themselves.")
        BrassButton("New script", Modifier.fillMaxWidth()) { choosing = true }
        if (items.isEmpty()) Text("No scripts yet. Start from an example: they compile and backtest as they are.",
            style = Type.bodySmall.copy(color = p.inkSoft))
        for (it in items.sortedByDescending { it.updated }) androidx.compose.runtime.key(it.id) {
            // Compiled off the main thread (a long script takes a while); null until it is done.
            val src = it.code
            val c = androidx.compose.runtime.produceState<Pine.Compiled?>(null, src) {
                value = withContext(Dispatchers.Default) { PineScripts.compile(src) }
            }.value
            val ok = c as? Pine.Compiled.Ok
            LedgerCard(onClick = { onOpen(it) }, accent = if (it.auto.on) p.verdigris else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.name, style = Type.title.copy(color = p.ink, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Stamp(when {
                        c == null -> "…"
                        ok == null -> "ERRORS"
                        ok.script.kind == Pine.Kind.STRATEGY -> "STRATEGY"
                        else -> "INDICATOR"
                    }, if (c is Pine.Compiled.Failed) p.oxblood else p.ink)
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PineEditor(env: PineEnv, d: PineDraft, onOpenChart: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var result by remember { mutableStateOf<Pine.Compiled?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }

    // Checked 0.4 s after the last keystroke (at once on coming back to the page).
    LaunchedEffect(d.code.text) {
        val text = d.code.text
        if (result != null) delay(400)
        result = withContext(Dispatchers.Default) { PineScripts.compile(text) }
    }
    val ok = (result as? Pine.Compiled.Ok)?.script
    // Auto-trade runs the SAVED code, so its panel is built from that, never from the draft.
    val savedCode = d.item.code
    val savedResult = androidx.compose.runtime.produceState<Pine.Compiled?>(null, savedCode) {
        value = null                                        // never the previous code's signals while this one compiles
        value = withContext(Dispatchers.Default) { PineScripts.compile(savedCode) }
    }.value
    val savedOk = (savedResult as? Pine.Compiled.Ok)?.script
    val errors = (result as? Pine.Compiled.Failed)?.errors.orEmpty()
    val warnings = when (val r = result) { is Pine.Compiled.Ok -> r.script.warnings; is Pine.Compiled.Failed -> r.warnings; else -> emptyList() }
    val dirty = d.code.text != d.item.code || d.name != d.item.name
    // While it trades by itself its code and inputs stay as they were armed: a change would alter
    // real orders without the PIN. Switch auto-trade off to edit.
    val allItems by PineScripts.items.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val armed = allItems.firstOrNull { it.id == d.item.id }?.auto?.on == true

    // Saving writes the encrypted vault: off the main thread, one save at a time, and in the app's
    // scope so a save already started still lands in the draft if the page is left meanwhile.
    suspend fun saveNow(inputs: Map<String, String>? = null): PineScripts.Item = d.saving.withLock {
        val want = d.item.copy(name = d.name.ifBlank { ok?.title ?: "Script" }, code = d.code.text)
        val s = withContext(Dispatchers.IO) { PineScripts.put(if (inputs != null) want.copy(inputs = inputs) else want) }
        d.item = s; d.saved = true
        s
    }
    fun later(what: suspend () -> Unit) {
        writing = true
        env.scope.launch {
            try { what() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { com.optionslab.app.work.Alerts.error("Could not save the script: ${e.message ?: e.javaClass.simpleName}") }
            finally { writing = false }
        }
    }
    // Unsaved edits are never dropped without asking.
    fun close() { if (dirty) leaving = true else onClose() }
    androidx.activity.compose.BackHandler { close() }

    Column(Modifier.fillMaxSize()) {
        // The tabs move under "‹ Scripts" when a large font leaves no room beside it (they were pushed off the screen).
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ close() }, Modifier.align(Alignment.CenterVertically)) { Text("‹ Scripts") }
            Spacer(Modifier.weight(1f))
            listOf("code" to "Code", "test" to "Backtest", "auto" to "Auto-trade").forEach { (k, l) ->
                com.optionslab.app.ui.components.Token(l, d.tab == k, Modifier.align(Alignment.CenterVertically)) { d.tab = k }
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
        val notYet = if (result == null) "Checking the script…" else "The script has errors: fix them in Code first."
        when (d.tab) {
            "code" -> Column(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(d.name, { d.name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CodeField(d.code, { if (it.text.length <= Pine.MAX_CHARS) d.code = it }, errors.map { it.line }.toSet())
                    errors.forEach { er ->
                        Text("Line ${er.line}:${er.col}  ${er.message}", style = Type.bodySmall.copy(color = p.oxblood),
                            modifier = Modifier.fillMaxWidth().clickable { d.code = d.code.copy(selection = TextRange(offsetOf(d.code.text, er.line, er.col))) }
                                .padding(vertical = 2.dp))
                    }
                    warnings.forEach { w -> Text("Line ${w.line}: ${w.message}", style = Type.bodySmall.copy(color = p.amber)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrassButton(if (d.saved && !dirty) "Saved" else "Save", Modifier.weight(1f), busy = writing,
                            enabled = (dirty || !d.saved) && !armed && !writing) { later { saveNow() } }
                        BrassButton("Delete", Modifier.weight(1f), tone = p.inkSoft, enabled = d.saved && !writing) { deleting = true }
                    }
                    if (armed && dirty) Note("Auto-trade is on: switch it off (Auto-trade tab) to save changes. Until then it trades with the code it was switched on with.")
                    if (ok != null) {
                        ToggleRow("Show on the chart", if (ok.overlay) "Drawn over the candles, with its buy/sell marks" else "Drawn in its own pane under the candles",
                            d.item.onChart) { on ->
                            later {
                                val s = if (armed) d.item else saveNow()
                                withContext(Dispatchers.IO) { PineScripts.setOnChart(s.id, on) }
                                d.item = PineScripts.get(s.id) ?: s
                            }
                        }
                        if (d.item.onChart) TextButton(onOpenChart) { Text("Open the chart ›") }
                    } else if (errors.isNotEmpty()) Note("Fix the errors to backtest it or put it on the chart. Tap an error to jump to it.")
                    Text("Supported: variables, var, :=, if / switch / for / for…in / while, functions and tuples, x[n] history, arrays (array.* and a.push()), inputs, request.security on higher timeframes of the same symbol, ta.* and math.*, plot, hline, plotshape, alertcondition, strategy.entry / exit (partial too) / close / cancel. Not yet: other symbols, matrices, maps, user types.",
                        style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
                    Spacer(Modifier.height(12.dp))
                }
                KeyStrip { ins -> d.code = insert(d.code, ins) }
            }
            "test" -> if (ok == null) Box(Modifier.fillMaxSize().padding(20.dp)) { Note(notYet) }
                else PineBacktest(env, d, ok, onOpenChart) { inputs -> if (!armed) later { saveNow(inputs) } }
            else -> if (savedOk == null) Box(Modifier.fillMaxSize().padding(20.dp)) {
                    Note(if (savedResult == null) "Checking the script…" else "The saved script has errors: fix them in Code and Save first.")
                }
                else PineAutoPanel(env, d.item, savedOk, dirty = dirty, save = { saveNow() }) { d.item = it }
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, properties = secureDialog,
        confirmButton = { TextButton({ leaving = false; onClose() }) { Text("Discard", color = p.oxblood) } },
        dismissButton = { TextButton({ leaving = false }) { Text("Keep editing") } },
        title = { Text("Discard your changes?") },
        text = { Text("The changes to ${d.name.ifBlank { "this script" }} are not saved." +
            (if (armed) " Auto-trade is on, so they cannot be saved until it is switched off." else " Keep editing and tap Save to keep them.")) })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, properties = secureDialog,
        confirmButton = { TextButton({
            deleting = false
            if (!writing) {
                writing = true
                // Under the save lock and off the main thread, so a save already started cannot re-add it afterwards.
                env.scope.launch {
                    try {
                        d.saving.withLock { withContext(Dispatchers.IO) { PineScripts.delete(d.item.id) } }
                        if (PineSession.open === d) onClose()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) { com.optionslab.app.work.Alerts.error("Could not delete the script: ${e.message ?: e.javaClass.simpleName}")
                    } finally { writing = false }
                }
            }
        }) { Text("Delete", color = p.oxblood) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep") } },
        title = { Text("Delete ${d.item.name}?") }, text = { Text("The script is removed from this phone and from the chart." +
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
                // Named for TalkBack: the code box has no visible label (it was read as an unnamed edit box).
                modifier = Modifier.widthIn(min = 280.dp).padding(end = 12.dp)
                    .semantics { contentDescription = "Script code" })
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

private data class TestRun(val run: Pine.Run, val report: Pine.Report?, val bars: List<Pine.Bar>, val symbol: String, val interval: String,
                            val how: String, val note: String? = null, val signals: List<String> = emptyList())

@Composable
private fun PineBacktest(env: PineEnv, d: PineDraft, s: Pine.Script, onChart: () -> Unit, onInputs: (Map<String, String>) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val item = d.item
    // Coming back to a kept result: the symbol and candles it was run on.
    var symbol by remember { mutableStateOf(d.test?.symbol ?: item.auto.symbol) }
    var interval by remember { mutableStateOf(d.test?.interval ?: item.auto.interval) }
    val periods = when (interval) { "1m" -> listOf(5, 15, 30); "5m", "15m" -> listOf(30, 90, 180); "1h" -> listOf(90, 180, 365); else -> listOf(365, 1095, 1825) }
    var days by remember(interval) { mutableStateOf(periods[1]) }
    val inputs = remember { mutableStateMapOf<String, String>().apply { s.inputs.forEach { d -> put(d.key, item.inputs[d.key] ?: d.default?.let { v -> if (v is Double && v == Math.floor(v)) v.toLong().toString() else v.toString() } ?: "") } } }
    var qty by remember { mutableStateOf("") }
    var capital by remember { mutableStateOf("100000") }
    // How P&L is counted: index points x quantity, or the ATM option the auto-trader would buy.
    var premium by remember { mutableStateOf(false) }
    var lots by remember { mutableStateOf("1") }
    var slippage by remember { mutableStateOf("") }
    var perOrder by remember { mutableStateOf("") }
    // An indicator trades its signals: which one buys, which one sells, and what a sell does.
    val guessBuy = s.signals.firstOrNull { it.contains("buy", true) || it.contains("long", true) } ?: s.signals.firstOrNull()
    val guessSell = s.signals.firstOrNull { it.contains("sell", true) || it.contains("short", true) } ?: s.signals.getOrNull(1)
    var buySig by remember { mutableStateOf(item.auto.buy.takeIf { it in s.signals } ?: guessBuy) }
    var sellSig by remember { mutableStateOf(item.auto.sell.takeIf { it in s.signals } ?: guessSell) }
    var reverse by remember { mutableStateOf(item.auto.shortWith != "exit") }
    val busy = d.testing
    val scroll = rememberScrollState()
    var resultTop by remember { mutableStateOf(0) }
    // Asked to bring the result into view (counted): scrolled from an effect, after the result is composed - never from
    // the click's coroutine while a layout pass is running (CI, 4 Oct: "performMeasureAndLayout called during measure").
    var scrollAsk by remember { mutableStateOf(0) }
    LaunchedEffect(scrollAsk) { if (scrollAsk > 0) scroll.animateScrollTo(resultTop) }
    val strategy = s.kind == Pine.Kind.STRATEGY

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Fixed while a backtest runs: it reads the instrument it started with.
        ParamTokens("Symbol", PINE_SYMBOLS.map { it to (it == symbol) }) { if (!busy) symbol = PINE_SYMBOLS[it] }
        ParamTokens("Candles", listOf("1m", "5m", "15m", "1h", "1D").map { it to (it == interval) }) { if (!busy) interval = listOf("1m", "5m", "15m", "1h", "1D")[it] }
        ParamTokens("Period", periods.map { (if (it >= 365) "${it / 365} yr" else "$it days") to (it == days) }) { if (!busy) days = periods[it] }
        if (!strategy) {
            if (s.signals.isEmpty()) Note("This indicator has no buy/sell signals to trade. Add plotshape(buy, \"Buy\") and plotshape(sell, \"Sell\") (or alertcondition) and it can be backtested for P&L.")
            else {
                ParamTokens("Buy on", s.signals.map { it to (it == buySig) }) { buySig = s.signals[it] }
                ParamTokens("Sell on", s.signals.map { it to (it == sellSig) }) { sellSig = s.signals[it] }
                ParamTokens("On a sell signal", listOf("Reverse to short" to reverse, "Just exit" to !reverse)) { reverse = it == 0 }
            }
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(qty, { v -> qty = v.filter { it.isDigit() || it == '.' }.take(8) },
                label = { Text(if (strategy) "Qty (blank: script's ${s.settings.qtyValue.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }})" else "Qty per trade (units)") },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (!strategy) OutlinedTextField(capital, { v -> capital = v.filter { it.isDigit() }.take(10) }, label = { Text("Capital") },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        ParamTokens("P&L counted in", listOf("Index points" to !premium, "Option premium (ATM)" to premium)) { premium = it == 1 }
        if (premium) {
            OutlinedTextField(lots, { v -> lots = v.filter { it.isDigit() }.take(3) }, label = { Text("Lots per trade") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Note("Each trade is re-priced on the ATM CALL (long) or PUT (short) of the nearest expiry after the entry day, at its own 1-minute prices, with Zerodha's charges. Only days with option data on the phone can be priced (about a month bundled, plus every day the harvester collects).")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(slippage, { v -> slippage = v.filter { it.isDigit() || it == '.' }.take(6) }, label = { Text("Slippage (points a side)") },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (!premium) OutlinedTextField(perOrder, { v -> perOrder = v.filter { it.isDigit() || it == '.' }.take(6) }, label = { Text("₹ per order (brokerage)") },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        val canTrade = strategy || (buySig != null && sellSig != null)
        BrassButton(if (busy) "Running…" else "Run backtest", Modifier.fillMaxWidth().padding(top = 6.dp), busy = busy) {
            if (d.testing) return@BrassButton
            d.testing = true; d.testError = null; d.test = null; d.stage = "Loading $symbol $interval candles…"
            val ins = inputs.toMap()
            val sym = symbol; val iv = interval; val nDays = days      // the run never reads the tokens again
            val bs = buySig; val ss = sellSig; val rev = reverse
            val q = qty.toDoubleOrNull()?.takeIf { it > 0 }
            val cap = capital.toDoubleOrNull()?.takeIf { it > 0 } ?: 100_000.0
            val costs = Pine.Costs(slippagePoints = if (premium) 0.0 else slippage.toDoubleOrNull() ?: 0.0, perOrder = if (premium) 0.0 else perOrder.toDoubleOrNull() ?: 0.0)
            val prem = premium; val nLots = lots.toIntOrNull()?.coerceIn(1, 100) ?: 1; val slipPrem = slippage.toDoubleOrNull() ?: 0.0
            runCatching { onInputs(ins) }                          // remembering the inputs must never stop the run
            // The run lives in the app's scope and reports into the draft, so leaving the page
            // does not throw away a run of up to a minute; this page only scrolls to the result.
            val job = env.scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val to = System.currentTimeMillis() / 1000
                        val raw = env.bars(sym, iv, to - nDays * 86400L)
                        if (raw.isEmpty()) throw IllegalStateException("No $sym $iv candles came back for that period. Check the connection, or pick a shorter period.")
                        val bars = raw.map { PineScripts.toPine(it) }
                        withContext(Dispatchers.Main) { d.stage = "Running the script on ${"%,d".format(bars.size)} candles…" }
                        val values = PineScripts.inputValues(PineScripts.Item(0, "", "", inputs = ins), s)
                        val run = withContext(Dispatchers.Default) { Pine.run(s, bars, values, sym, iv, if (strategy) q else null, budgetMs = 60_000, costs = costs) }
                        var report = when {
                            strategy -> run.report
                            canTrade && run.error == null -> withContext(Dispatchers.Default) {
                                Pine.signalBacktest(bars, run.signals[s.signals.indexOf(bs!!)], run.signals[s.signals.indexOf(ss!!)], rev, q ?: 1.0, cap, costs)
                            }
                            else -> null
                        }
                        var note: String? = null
                        if (prem && report != null) {
                            withContext(Dispatchers.Main) { d.stage = "Pricing the trades on option data…" }
                            val pr = withContext(Dispatchers.Default) {
                                com.optionslab.engine.pine.PinePremium.run(report!!.trades, bars, { day -> com.optionslab.app.data.Store.barSession(sym, day) },
                                    com.optionslab.app.data.PineAuto.strikeStep(sym), nLots, if (strategy) s.settings.initialCapital else cap,
                                    shortsBuyPuts = strategy || rev, slippage = slipPrem)
                            }
                            note = "Option premium: priced ${pr.priced} of ${pr.priced + pr.skipped} trades" +
                                (if (pr.reasons.isNotEmpty()) " (skipped: " + pr.reasons.entries.joinToString { "${it.value} ${it.key}" } + ")" else "")
                            report = pr.report
                        }
                        val how = (if (strategy) "the strategy's own orders" else "buy on \"$bs\", ${if (rev) "reverse to short" else "exit"} on \"$ss\"") +
                            if (prem) " · ATM options × $nLots lot${if (nLots == 1) "" else "s"}" else ""
                        Result.success(TestRun(run, report, bars, sym, iv, how, note, s.signals))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) { Result.failure(e) }
                }
                d.testing = false; d.stage = null
                r.onSuccess { d.test = it }.onFailure { d.testError = "Backtest failed: " + (it.message ?: it.javaClass.simpleName) }
            }
            scope.launch {
                job.join()
                // The result sits below the inputs: bring it into view.
                scrollAsk++
            }
        }
        d.stage?.let { Text(it, style = Type.bodySmall.copy(color = p.inkSoft)) }
        Box(Modifier.fillMaxWidth().height(1.dp).onGloballyPositioned { resultTop = it.positionInParent().y.toInt() })
        d.testError?.let { LedgerCard(accent = p.oxblood) { Text(it, style = Type.bodySmall.copy(color = p.oxblood)) } }
        d.test?.let { t ->
            BacktestResult(t)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                ExportCsv(t, Modifier.weight(1f))
                BrassButton("Show on chart", Modifier.weight(1f), tone = p.inkSoft) {
                    val id = d.item.id
                    env.scope.launch {
                        try {
                            if (id != 0L) withContext(Dispatchers.IO) { PineScripts.setOnChart(id, true) }
                            // The chart opens on the index the backtest ran on.
                            pineChartAsk = t.symbol to (if (t.symbol == "SENSEX") "BSE" else "NSE")
                            onChart()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Throwable) { com.optionslab.app.work.Alerts.error("Could not put the script on the chart: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
        }
        if (s.inputs.any { it.kind == "int" || it.kind == "float" }) Optimiser(env, s, strategy, symbol, interval, days, inputs, buySig, sellSig, reverse,
            qty.toDoubleOrNull()?.takeIf { it > 0 }, capital.toDoubleOrNull()?.takeIf { it > 0 } ?: 100_000.0,
            Pine.Costs(slippagePoints = slippage.toDoubleOrNull() ?: 0.0, perOrder = perOrder.toDoubleOrNull() ?: 0.0)) { picked ->
            picked.forEach { (k, v) -> inputs[k] = if (v == Math.floor(v)) v.toLong().toString() else v.toString() }
            scope.launch { scroll.animateScrollTo(0) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun fmtTime(t: Long, daily: Boolean): String = Instant.ofEpochSecond(t).atZone(IST)
    .format(DateTimeFormatter.ofPattern(if (daily) "d MMM yy" else "d MMM HH:mm", Locale.ENGLISH))

private fun f2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
private fun pc(x: Double) = String.format(Locale.ENGLISH, "%.1f%%", x)
private fun spc(x: Double) = String.format(Locale.ENGLISH, "%+.2f%%", x)
private fun dur(minutes: Double): String = when {
    minutes < 60 -> "${minutes.toInt()} min"
    minutes < 60 * 24 -> String.format(Locale.ENGLISH, "%.1f h", minutes / 60)
    else -> String.format(Locale.ENGLISH, "%.1f days", minutes / 1440)
}

/** One headline figure. */
@Composable
private fun Tile(label: String, value: String, tone: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.background(p.card, RoundedCornerShape(12.dp)).border(1.dp, p.rule, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        // At a large font on a narrow screen the label wraps and the figure shrinks to stay whole (it was cut to "Rs -8…").
        Text(label.uppercase(), style = Type.label.copy(color = p.inkSoft, fontSize = 9.sp))
        com.optionslab.app.ui.components.FitText(value, Type.figure.copy(color = tone, fontSize = 15.sp), minSize = 6.sp)
    }
}

@Composable
private fun BacktestResult(t: TestRun) {
    val p = LocalPalette.current
    val r = t.run
    val daily = t.interval == "1D"
    val first = t.bars.first().time; val last = t.bars.last().time
    Text("${t.symbol} · ${t.interval} · ${"%,d".format(t.bars.size)} candles · ${fmtTime(first, true)} – ${fmtTime(last, true)} · ${t.how}",
        style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
    r.error?.let { Text("Stopped: $it", style = Type.bodySmall.copy(color = p.oxblood)) }
    t.note?.let { Text(it, style = Type.bodySmall.copy(color = p.amber)) }
    // Every signal the script gave, and how often: the names it was run with (the code may have changed since).
    if (t.signals.isNotEmpty()) LedgerCard(title = "Signals") {
        t.signals.forEachIndexed { i, name ->
            val sig = r.signals.getOrNull(i) ?: return@forEachIndexed
            val hits = sig.indices.filter { sig[it] && it < t.bars.size }
            LedgerLine(name, "${hits.size} times" + (hits.lastOrNull()?.let { " · last ${fmtTime(t.bars[it].time, daily)}" } ?: ""))
        }
    }
    val rep = t.report ?: return
    if (rep.trades.isEmpty()) {
        LedgerCard(title = "No trades") {
            Text("It ran on every candle but never entered: the conditions were not met in this period. Try a longer period, other candles or other inputs.",
                style = Type.bodySmall.copy(color = p.inkSoft))
        }
        return
    }
    val x = rep.extra
    val gain = if (rep.netProfit >= 0) p.verdigris else p.oxblood
    fun tone(v: Double) = if (v >= 0) p.verdigris else p.oxblood

    // Headline figures.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tile("Net P&L", rs(rep.netProfit, sign = true), gain, Modifier.weight(1f))
        Tile("Win rate", pc(rep.winRate), if (rep.winRate >= 50) p.verdigris else p.ink, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tile("Profit factor", rep.profitFactor?.let { f2(it) } ?: "—", if ((rep.profitFactor ?: 0.0) >= 1) p.verdigris else p.oxblood, Modifier.weight(1f))
        Tile("Max drawdown", rs(-rep.maxDrawdown), p.oxblood, Modifier.weight(1f))
        Tile("Trades", "${rep.closedTrades}", p.ink, Modifier.weight(1f))
    }
    val step = maxOf(1, rep.equity.size / 400)
    val eq = rep.equity.toList().filterIndexed { i, _ -> i % step == 0 }
    if (eq.size >= 2) LedgerCard(title = "Equity") {
        LinePlot(eq.indices.map { it.toDouble() }, listOf(PlotLine(eq, gain)), height = 140.dp)
        Text("${rs(rep.initialCapital)} → ${rs(rep.equity.last())}", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
    }
    LedgerCard(title = "Performance") {
        LedgerLine("Net profit", "${rs(rep.netProfit, sign = true)} (${spc(rep.netProfitPct)})", gain)
        LedgerLine("Gross profit / loss", "${rs(rep.grossProfit)} / ${rs(-rep.grossLoss)}")
        LedgerLine("Won / lost", "${rep.winners} / ${rep.losers}" + (if (rep.closedTrades - rep.winners - rep.losers > 0) " · ${rep.closedTrades - rep.winners - rep.losers} even" else ""))
        LedgerLine("Average trade (expectancy)", rs(rep.avgTrade, sign = true), tone(rep.avgTrade))
        LedgerLine("Average win / loss", "${rs(rep.avgWin, sign = true)} / ${rs(rep.avgLoss, sign = true)}")
        x?.payoffRatio?.let { LedgerLine("Payoff ratio (avg win ÷ avg loss)", f2(it)) }
        LedgerLine("Largest win / loss", "${rs(rep.largestWin, sign = true)} / ${rs(rep.largestLoss, sign = true)}")
        x?.let { LedgerLine("Most wins / losses in a row", "${it.maxConsecWins} / ${it.maxConsecLosses}") }
        LedgerLine("Max drawdown", "${rs(-rep.maxDrawdown)} (${f2(rep.maxDrawdownPct)}%)", p.oxblood)
        x?.let { if (it.maxDrawdownDays > 0) LedgerLine("Longest time below a peak", "${it.maxDrawdownDays} days") }
        x?.recoveryFactor?.let { LedgerLine("Recovery factor (net ÷ drawdown)", f2(it)) }
        x?.sharpe?.let { LedgerLine("Sharpe ratio (annualised)", f2(it)) }
        x?.sortino?.let { LedgerLine("Sortino ratio (annualised)", f2(it)) }
        x?.let { LedgerLine("Return on capital", spc(it.returnPct), tone(it.returnPct)) }
        LedgerLine("Buy & hold ${t.symbol}", spc(rep.buyHoldPct))
        LedgerLine("Commission paid", rs(rep.commission))
        if (rep.trades.any { it.open }) LedgerLine("Open trade (not counted)", rs(rep.openPnl, sign = true), tone(rep.openPnl))
    }
    x?.let { e ->
        LedgerCard(title = "Long vs short") {
            Row { listOf("", "Trades", "Win rate", "Net P&L").forEachIndexed { i, h ->
                Text(h, style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.weight(if (i == 0) 0.8f else 1f)) } }
            listOf("Long" to e.long, "Short" to e.short).forEach { (name, sd) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(name, style = Type.label.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(0.8f))
                    Text("${sd.trades}", style = Type.figure.copy(fontSize = 12.sp, color = p.ink), modifier = Modifier.weight(1f))
                    Text(if (sd.trades == 0) "—" else pc(sd.winRate), style = Type.figure.copy(fontSize = 12.sp, color = p.ink), modifier = Modifier.weight(1f))
                    Text(rs(sd.net, sign = true), style = Type.figure.copy(fontSize = 12.sp, color = tone(sd.net)), modifier = Modifier.weight(1f))
                }
            }
        }
        LedgerCard(title = "Time") {
            LedgerLine("Average time in a trade", dur(e.avgMinutesInTrade))
            LedgerLine("Average candles in a trade", String.format(Locale.ENGLISH, "%.1f", rep.avgBarsInTrade))
            LedgerLine("In the market", pc(e.exposurePct))
            LedgerLine("Trades per day", String.format(Locale.ENGLISH, "%.2f", e.tradesPerDay))
            LedgerLine("Trading days", "${e.tradingDays} · ${e.winningDays} up, ${e.losingDays} down")
            e.bestDay?.let { LedgerLine("Best day", "${it.label} · ${rs(it.pnl, sign = true)}", p.verdigris) }
            e.worstDay?.let { LedgerLine("Worst day", "${it.label} · ${rs(it.pnl, sign = true)}", p.oxblood) }
        }
        if (e.months.isNotEmpty()) LedgerCard(title = "Month by month") { PeriodRows(e.months, false) }
        if (e.days.isNotEmpty()) LedgerCard(title = "Day by day") { PeriodRows(e.days.asReversed(), true) }
    }
    var all by remember(t) { mutableStateOf(false) }
    val trades = rep.trades.asReversed()
    LedgerCard(title = "Trades (${rep.trades.size})") {
        (if (all) trades else trades.take(30)).forEach { tr ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (tr.long) "LONG" else "SHORT"} ${tr.entryId} · ${num(tr.qty)}", style = Type.label.copy(color = if (tr.long) p.verdigris else p.oxblood, fontSize = 12.sp))
                    Text("${fmtTime(tr.entryTime, daily)} @ ${num(tr.entryPrice)} → ${if (tr.open) "open" else fmtTime(tr.exitTime, daily)} @ ${num(tr.exitPrice)}" +
                        (if (tr.open) "" else " · ${tr.exitId}") + " · ${dur((tr.exitTime - tr.entryTime) / 60.0)}",
                        style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rs(tr.pnl, sign = true), style = Type.figure.copy(color = tone(tr.pnl), fontSize = 13.sp))
                    Text(spc(tr.pnlPct), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 10.sp))
                }
            }
            Rule()
        }
        if (!all && trades.size > 30) TextButton({ all = true }) { Text("Show all ${trades.size}") }
    }
    Text("Fills follow TradingView: orders fill at the next candle's open; stops and targets inside a candle, open → nearer extreme → far extreme → close. " +
        "P&L is as chosen above - index points × quantity, or the ATM option's own premium - after the commission, charges and slippage set there.",
        style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
}

/** The trades as a CSV file the owner saves where they like (a spreadsheet, the cloud). */
@Composable
private fun ExportCsv(t: TestRun, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rep = t.report
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && rep != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(tradesCsv(rep).toByteArray(Charsets.UTF_8)) } ?: error("could not open the file")
        }.onSuccess { com.optionslab.app.work.Alerts.success("Saved ${rep.trades.size} trades") }
            .onFailure { com.optionslab.app.work.Alerts.error("Could not save the CSV: ${it.message}") }
    }
    BrassButton("Export CSV", modifier, tone = p.inkSoft, enabled = rep != null && rep.trades.isNotEmpty()) {
        launcher.launch("pine-${t.symbol.lowercase()}-${t.interval}-${java.time.LocalDate.now()}.csv")
    }
}

/** One row per trade. Text from the script is quoted, and a leading = + - @ is defused so a spreadsheet never runs it. */
internal fun tradesCsv(rep: Pine.Report): String {
    val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    fun t(x: Long) = Instant.ofEpochSecond(x).atZone(IST).format(f)
    fun q(x: String): String { val v = if (x.isNotEmpty() && x[0] in "=+-@\t\r") "'$x" else x; return "\"" + v.replace("\"", "\"\"") + "\"" }
    fun n(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    val sb = StringBuilder("entry_time,exit_time,side,entry,exit,qty,entry_price,exit_price,pnl,pnl_pct,charges,status\n")
    for (tr in rep.trades) sb.append(listOf(t(tr.entryTime), t(tr.exitTime), if (tr.long) "LONG" else "SHORT", q(tr.entryId), q(tr.exitId),
        n(tr.qty), n(tr.entryPrice), n(tr.exitPrice), n(tr.pnl), n(tr.pnlPct), n(tr.commission), if (tr.open) "open" else "closed").joinToString(",")).append('\n')
    return sb.toString()
}

/**
 * Try a range of values for one or two inputs, rank them, and check the best on the part of
 * the period they were not picked on. Tap a row to use its values.
 */
@Composable
private fun Optimiser(
    env: PineEnv, s: Pine.Script, strategy: Boolean, symbol: String, interval: String, days: Int, inputs: Map<String, String>,
    buySig: String?, sellSig: String?, reverse: Boolean, qty: Double?, capital: Double, costs: Pine.Costs,
    onApply: (Map<String, Double>) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val numeric = s.inputs.filter { it.kind == "int" || it.kind == "float" }
    var open by remember { mutableStateOf(false) }
    var p1 by remember { mutableStateOf(numeric.first().key) }
    var p2 by remember { mutableStateOf<String?>(null) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var split by remember { mutableStateOf(70) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<com.optionslab.engine.pine.PineOptimise.Result?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun def(k: String): Double = inputs[k]?.toDoubleOrNull() ?: (numeric.first { it.key == k }.default as? Double) ?: 10.0
    fun isInt(k: String) = numeric.first { it.key == k }.kind == "int"
    fun field(k: String, which: String): String = fields["$k|$which"] ?: run {
        val d = def(k)
        val v = when (which) {
            "from" -> if (isInt(k)) maxOf(1.0, Math.floor(d / 2)) else d / 2
            "to" -> if (isInt(k)) Math.ceil(d * 1.5) else d * 1.5
            else -> if (isInt(k)) maxOf(1.0, Math.round(d / 5.0).toDouble()) else d / 5
        }
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ENGLISH, "%.4f", v).trimEnd('0').trimEnd('.')
    }
    fun range(k: String) = com.optionslab.engine.pine.PineOptimise.Range(k, field(k, "from").toDoubleOrNull() ?: def(k),
        field(k, "to").toDoubleOrNull() ?: def(k), field(k, "step").toDoubleOrNull() ?: 1.0)

    LedgerCard(title = "Optimise inputs") {
        if (!open) {
            Text("Try a range of values for one or two inputs and rank them. The best are then checked on the last part of the period they were not chosen on, so an over-fitted pick shows up.",
                style = Type.bodySmall.copy(color = p.inkSoft))
            BrassButton("Set up", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft) { open = true }
            return@LedgerCard
        }
        ParamTokens("First input", numeric.map { it.key to (it.key == p1) }) { p1 = numeric[it].key; if (p2 == p1) p2 = null }
        val others = numeric.filter { it.key != p1 }
        if (others.isNotEmpty()) ParamTokens("Second input", (listOf("None") + others.map { it.key }).map { it to (it == (p2 ?: "None")) }) { i ->
            p2 = if (i == 0) null else others[i - 1].key
        }
        listOfNotNull(p1, p2).forEach { k ->
            Text(k, style = Type.label.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("from", "to", "step").forEach { w ->
                    OutlinedTextField(field(k, w), { v -> fields["$k|$w"] = v.filter { it.isDigit() || it == '.' }.take(8) }, label = { Text(w) },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
        ParamTokens("Check on unseen data", listOf("None" to (split == 100), "Last 30%" to (split == 70), "Last 40%" to (split == 60))) {
            split = listOf(100, 70, 60)[it]
        }
        val ranges = listOfNotNull(p1, p2).map { range(it) }
        val combos = com.optionslab.engine.pine.PineOptimise.combos(ranges)
        Text("$combos combination${if (combos == 1) "" else "s"}" + (if (combos > 400) " (the first 400 are tried)" else ""),
            style = Type.bodySmall.copy(color = if (combos > 400) p.amber else p.inkSoft))
        BrassButton(if (busy) "Optimising…" else "Run optimiser", Modifier.fillMaxWidth().padding(top = 6.dp), busy = busy,
            enabled = strategy || (buySig != null && sellSig != null)) {
            busy = true; failure = null; result = null; progress = "Loading candles…"
            val base = PineScripts.inputValues(PineScripts.Item(0, "", "", inputs = inputs.toMap()), s)
            val sig = if (strategy) null else com.optionslab.engine.pine.PineOptimise.Signals(s.signals.indexOf(buySig!!), s.signals.indexOf(sellSig!!),
                reverse, qty ?: 1.0, capital)
            val plan = com.optionslab.engine.pine.PineOptimise.Plan(ranges, split, sig, if (strategy) qty else null, costs)
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val to = System.currentTimeMillis() / 1000
                        val bars = env.bars(symbol, interval, to - days * 86400L).map { PineScripts.toPine(it) }
                        if (bars.size < 50) throw IllegalStateException("Too few candles to optimise on")
                        Result.success(withContext(Dispatchers.Default) {
                            com.optionslab.engine.pine.PineOptimise.run(s, bars, base, plan, symbol, interval) { done, all -> progress = "Tried $done of $all…" }
                        })
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) { Result.failure(e) }
                }
                busy = false; progress = null
                r.onSuccess { result = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            }
        }
        progress?.let { Text(it, style = Type.bodySmall.copy(color = p.inkSoft)) }
        failure?.let { Text("Optimiser failed: $it", style = Type.bodySmall.copy(color = p.oxblood)) }
        result?.let { res ->
            if (res.stoppedEarly) Text("Tried ${res.tried} of ${res.total} (limit or time reached).", style = Type.bodySmall.copy(color = p.amber))
            res.splitTime?.let { Text("Chosen on candles before ${fmtTime(it, true)}, checked on the rest.", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp)) }
            Row(Modifier.padding(top = 6.dp)) {
                listOf("Values" to 1.4f, "Net" to 1.1f, "Trades" to 0.7f, "Win" to 0.7f, "PF" to 0.6f, "Unseen" to 1.1f).forEach { (h, w) ->
                    Text(h, style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.weight(w))
                }
            }
            res.rows.take(20).forEach { row ->
                Row(Modifier.fillMaxWidth().clickable { onApply(row.values) }.padding(vertical = 4.dp)) {
                    val st = Type.figure.copy(fontSize = 11.sp, color = p.ink)
                    Text(row.values.values.joinToString(" / ") { if (it == Math.floor(it)) it.toLong().toString() else f2(it) }, style = st, modifier = Modifier.weight(1.4f))
                    Text(rs(row.inSample.net, sign = true), style = st.copy(color = if (row.inSample.net >= 0) p.verdigris else p.oxblood), modifier = Modifier.weight(1.1f))
                    Text("${row.inSample.trades}", style = st, modifier = Modifier.weight(0.7f))
                    Text(if (row.inSample.trades == 0) "—" else String.format(Locale.ENGLISH, "%.0f%%", row.inSample.winRate), style = st, modifier = Modifier.weight(0.7f))
                    Text(row.inSample.profitFactor?.let { f2(it) } ?: "—", style = st, modifier = Modifier.weight(0.6f))
                    Text(row.outSample?.let { rs(it.net, sign = true) } ?: "—",
                        style = st.copy(color = row.outSample?.let { if (it.net >= 0) p.verdigris else p.oxblood } ?: p.inkFaint), modifier = Modifier.weight(1.1f))
                }
                Rule()
            }
            Text("Tap a row to use its values, then Run backtest. Prefer values that also made money on the unseen part.",
                style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Months or days: trades, win rate and P&L, the most recent 60 unless expanded. */
@Composable
private fun PeriodRows(rows: List<Pine.Period>, days: Boolean) {
    val p = LocalPalette.current
    var all by remember(rows) { mutableStateOf(false) }
    Row { listOf(if (days) "Day" else "Month", "Trades", "Won", "P&L").forEachIndexed { i, h ->
        Text(h, style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.weight(if (i == 0) 1.3f else if (i == 3) 1.2f else 0.8f)) } }
    (if (all) rows else rows.take(if (days) 20 else 24)).forEach { m ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(m.label, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1.3f))
            Text("${m.trades}", style = Type.figure.copy(fontSize = 12.sp, color = p.ink), modifier = Modifier.weight(0.8f))
            Text(if (m.trades == 0) "—" else pc(m.winners * 100.0 / m.trades), style = Type.figure.copy(fontSize = 12.sp, color = p.ink), modifier = Modifier.weight(0.8f))
            Text(rs(m.pnl, sign = true), style = Type.figure.copy(fontSize = 12.sp, color = if (m.pnl >= 0) p.verdigris else p.oxblood), modifier = Modifier.weight(1.2f))
        }
    }
    val cap = if (days) 20 else 24
    if (!all && rows.size > cap) TextButton({ all = true }) { Text("Show all ${rows.size}") }
}

private fun num(x: Double) = if (x == Math.floor(x) && kotlin.math.abs(x) < 1e12) String.format(Locale.ENGLISH, "%,d", x.toLong()) else String.format(Locale.ENGLISH, "%,.2f", x)


// ---- auto-trade ----------------------------------------------------------------------------

@Composable
private fun PineAutoPanel(env: PineEnv, start: PineScripts.Item, s: Pine.Script, dirty: Boolean, save: suspend () -> PineScripts.Item, onItem: (PineScripts.Item) -> Unit) {
    val p = LocalPalette.current
    val items by PineScripts.items.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val item = items.firstOrNull { it.id == start.id } ?: start
    val settings by env.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val live = settings.live && settings.allowRealOrders
    val held by com.optionslab.app.data.PineAuto.held.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val log by com.optionslab.app.data.PineAuto.log.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var auth by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.optionslab.app.data.PineAuto.load() } }
    val a = item.auto
    val strategy = s.kind == Pine.Kind.STRATEGY
    val usable = strategy || s.signals.isNotEmpty()

    // Each change is written to the vault off the main thread, applied to the settings as they
    // are then (so two quick taps never undo each other), in the app's scope so it still lands
    // if the page closes meanwhile.
    suspend fun write(change: (PineScripts.Auto) -> PineScripts.Auto) {
        val id = (if (item.id == 0L) save() else item).id
        val now = withContext(Dispatchers.IO) {
            synchronized(PineScripts) { PineScripts.get(id)?.let { PineScripts.setAuto(id, change(it.auto)) } }
            PineScripts.get(id)
        }
        now?.let(onItem)
    }
    fun set(change: (PineScripts.Auto) -> PineScripts.Auto) {
        env.scope.launch {
            try { write(change) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { com.optionslab.app.work.Alerts.error("Could not save the auto-trade setting: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
    // The number boxes: what is typed waits here and is written 0.6 s after the last keystroke,
    // before switching on, and when the panel closes, so the last value is never lost.
    val typed = remember { mutableStateMapOf<String, Double>() }
    fun withTyped(au: PineScripts.Auto, m: Map<String, Double>) =
        if (au.on) au    // switched on meanwhile: its settings stay as they were armed
        else au.copy(stopPts = m["stop"] ?: au.stopPts, targetPts = m["target"] ?: au.targetPts, maxDayLoss = m["day"] ?: au.maxDayLoss)
    suspend fun flushTyped() {
        val m = typed.toMap()
        if (m.isEmpty()) return
        write { withTyped(it, m) }
        m.forEach { (k, v) -> if (typed[k] == v) typed.remove(k) }
    }
    val pending = typed.toMap()
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        delay(600)
        env.scope.launch { runCatching { flushTyped() } }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (typed.isNotEmpty()) env.scope.launch { runCatching { flushTyped() } } }
    }
    // It trades the saved code: unsaved edits must be saved (and seen) before it is switched on.
    val unsaved = "Save the script first (Code tab): auto-trade runs the saved code, not unsaved edits."
    fun arm(on: Boolean, pin: Boolean) {
        if (on && dirty) { com.optionslab.app.work.Alerts.error(unsaved); return }
        busy = true
        env.scope.launch {
            try {
                val id = (if (item.id == 0L) save() else item).id
                if (on) runCatching { flushTyped() }               // what was just typed counts
                withContext(Dispatchers.IO) { com.optionslab.app.data.PineAuto.arm(id, on, pin) }
                PineScripts.get(id)?.let(onItem)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { com.optionslab.app.work.Alerts.error("Could not switch auto-trade ${if (on) "on" else "off"}: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
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
                    Text(if (a.mode == "alert") "Alerts only: a notification on each signal, no orders"
                        else if (live) "Follows the app switch: LIVE (real money at Zerodha)" else "Follows the app switch: PAPER",
                        style = Type.bodySmall.copy(color = if (live && a.mode != "alert") p.oxblood else p.inkSoft))
                }
                androidx.compose.material3.Switch(a.on, { on ->
                    if (busy) return@Switch
                    if (on && dirty) { com.optionslab.app.work.Alerts.error(unsaved); return@Switch }
                    // Alerts only places nothing: no PIN needed.
                    if (on && live && a.mode != "alert") auth = true else arm(on, false)
                }, Modifier.semantics { contentDescription = "Auto-trade" })
            }
            held[item.id]?.let { h ->
                Spacer(Modifier.height(6.dp))
                LedgerLine("Holding", "${h.qty} ${h.kite ?: h.symbol}${if (h.live) " · LIVE" else ""}")
                LedgerLine("Bought at", String.format(Locale.ENGLISH, "%.2f", h.entry))
            }
        }
        com.optionslab.app.data.PineAuto.todayOf(item.id)?.takeIf { it != 0.0 }?.let {
            LedgerLine("Closed trades today", rs(it, sign = true), if (it >= 0) p.verdigris else p.oxblood)
        }
        if (dirty && !a.on) Note(unsaved)
        val locked = a.on
        if (locked) Text("Switch it off to change these.", style = Type.bodySmall.copy(color = p.inkFaint))
        ParamTokens("What it does", listOf("Place orders" to (a.mode != "alert"), "Alerts only" to (a.mode == "alert"))) { i ->
            if (!locked) set { au -> au.copy(mode = if (i == 1) "alert" else "trade") }
        }
        ParamTokens("Symbol", PINE_SYMBOLS.map { it to (it == a.symbol) }) { if (!locked) set { au -> au.copy(symbol = PINE_SYMBOLS[it]) } }
        if (a.symbol == "SENSEX" && a.mode != "alert") Note("SENSEX options trade on BSE, where the app does not place orders: choose Alerts only for SENSEX.")
        ParamTokens("Candles", listOf("1m", "5m", "15m", "1h").map { it to (it == a.interval) }) { if (!locked) set { au -> au.copy(interval = listOf("1m", "5m", "15m", "1h")[it]) } }
        ParamTokens("Lots", listOf(1, 2, 3, 5, 10).map { "$it" to (it == a.lots) }) { if (!locked) set { au -> au.copy(lots = listOf(1, 2, 3, 5, 10)[it]) } }
        if (strategy) {
            val opts = listOf("strategy") + s.signals
            ParamTokens("Go long on", opts.map { (if (it == "strategy") "the strategy's entries" else it) to (it == a.buy) }) { i ->
                if (!locked) set { au -> au.copy(buy = opts[i], sell = if (opts[i] == "strategy") "strategy" else a.sell.takeIf { it != "strategy" } ?: s.signals.getOrElse(1) { s.signals.first() }) }
            }
            if (a.buy != "strategy") ParamTokens("Go short on", s.signals.map { it to (it == a.sell) }) { i -> if (!locked) set { au -> au.copy(sell = s.signals[i]) } }
        } else {
            val buy = a.buy.takeIf { it in s.signals } ?: s.signals.first()
            val sell = a.sell.takeIf { it in s.signals } ?: s.signals.getOrElse(1) { s.signals.first() }
            // Only while off: an armed script's settings never change without the PIN.
            if (!a.on && (buy != a.buy || sell != a.sell)) LaunchedEffect(buy, sell) { set { au -> if (au.on) au else au.copy(buy = buy, sell = sell) } }
            ParamTokens("Buy signal", s.signals.map { it to (it == buy) }) { i -> if (!locked) set { au -> au.copy(buy = s.signals[i]) } }
            ParamTokens("Sell signal", s.signals.map { it to (it == sell) }) { i -> if (!locked) set { au -> au.copy(sell = s.signals[i]) } }
        }
        if (a.mode != "alert") {
            ParamTokens("On a sell signal", listOf("Buy a PUT" to (a.shortWith == "put"), "Just exit" to (a.shortWith == "exit"))) { i ->
                if (!locked) set { au -> au.copy(shortWith = if (i == 0) "put" else "exit") }
            }
            Text("PROTECTION ON THE OPTION", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AutoNum("Stop-loss (pts)", a.stopPts, locked, Modifier.weight(1f)) { typed["stop"] = it }
                AutoNum("Target (pts)", a.targetPts, locked, Modifier.weight(1f)) { typed["target"] = it }
                AutoNum("Day loss ₹", a.maxDayLoss, locked, Modifier.weight(1f)) { typed["day"] = it }
            }
            Text("Checked every pass on the option's own price: below the stop or above the target it is sold at once; past the day's loss it is sold and the script trades no more today. 0 = off. The Bot settings daily loss limit also stops every bot.",
                style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
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
    if (auth) env.reauth("Enter your app PIN to let this Pine script trade on Zerodha. It then places real orders by itself until you switch it off.",
        { auth = false; arm(true, true) }, { auth = false })
}

/** A number box for an auto-trade setting: reported when it parses, 0 when cleared (the panel saves it). */
@Composable
private fun AutoNum(label: String, value: Double, locked: Boolean, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (value == 0.0) "" else if (value == Math.floor(value)) value.toLong().toString() else value.toString()) }
    OutlinedTextField(text, { v ->
        if (locked) return@OutlinedTextField
        text = v.filter { it.isDigit() || it == '.' }.take(8)
        val d = text.toDoubleOrNull() ?: if (text.isEmpty()) 0.0 else return@OutlinedTextField
        onValue(d)
    }, label = { Text(label, fontSize = 11.sp) }, singleLine = true, enabled = !locked, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
