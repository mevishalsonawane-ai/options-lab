package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.FlowGate
import com.optionslab.app.data.OrderFlowLive
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.FlowShadow
import com.optionslab.ira.OrderFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The live order flow on screen (Boss, 9 Oct 2026): a chip in the Chart's toolbar, a line in the option chain's header, the
 * Order flow section of Home's Strategies card, and the detail they open. Every one reads [OrderFlowLive.reads] - memory,
 * published at most every 250 ms - and nothing else; the modes go through [FlowGate.set] (one settings write a change).
 * Never in the gold build.
 */

/** The index whose flow the Chart shows for [symbol] (the index itself, its future or one of its options), or null. */
internal fun flowUnderlyingOf(symbol: String): String? {
    val u = symbol.uppercase(java.util.Locale.ENGLISH).replace(" ", "")
    return when {
        u.startsWith("MIDCPNIFTY") || u == "NIFTYMIDSELECT" -> "MIDCPNIFTY"
        u.startsWith("FINNIFTY") || u == "NIFTYFINSERVICE" -> "FINNIFTY"
        u.startsWith("BANKNIFTY") || u == "NIFTYBANK" -> "BANKNIFTY"
        u == "NIFTY" || u == "NIFTY50" || (u.startsWith("NIFTY") && u.length > 5 && u[5].isDigit()) -> "NIFTY"
        else -> null
    }
}

/** The colour of a read's side. */
@Composable
private fun sideColor(r: OrderFlow.Read?): Color {
    val p = LocalPalette.current
    if (r == null || !r.warm) return p.inkFaint
    return when (r.side) {
        OrderFlow.Side.BUYERS -> p.verdigris
        OrderFlow.Side.SELLERS -> p.oxblood
        OrderFlow.Side.BALANCED -> p.inkSoft
    }
}

/** The Chart toolbar's order-flow slot ([ChartPane]'s `flowChip`): [OrderFlowChip]. */
internal val FlowChipSlot: @Composable (String, Modifier) -> Unit = { u, m -> OrderFlowChip(u, m) }

/** The Chart's chip for the charted index (its future's flow): "Flow buyers 72"; a tap opens the detail. */
@Composable
internal fun OrderFlowChip(underlying: String, modifier: Modifier = Modifier) {
    val u = underlying.uppercase(java.util.Locale.ENGLISH)
    if (u !in OrderFlow.INDICES) return
    LaunchedEffect(u) { withContext(Dispatchers.IO) { runCatching { OrderFlowLive.focus(u) } } }
    val reads by OrderFlowLive.reads.collectAsState(Dispatchers.Main.immediate)
    var open by remember { mutableStateOf(false) }
    val r = reads[u]
    val p = LocalPalette.current
    Text("Flow ${OrderFlow.word(r)}" + if (r != null && r.flags.isNotEmpty()) " ⚠" else "", maxLines = 1, softWrap = false,
        style = Type.label.copy(color = sideColor(r), fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.semantics { contentDescription = "Order flow ${OrderFlow.word(r)}, open the detail" }
            .background(p.chip, RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp))
    if (open) OrderFlowSheet(u) { open = false }
}

/** The option chain header's line: "Order flow: buyers 72 (future)"; a tap opens the detail. */
@Composable
internal fun OrderFlowLine(underlying: String) {
    val u = underlying.uppercase(java.util.Locale.ENGLISH)
    if (com.optionslab.app.BuildConfig.GOLD) return
    val reads by OrderFlowLive.reads.collectAsState(Dispatchers.Main.immediate)
    var open by remember { mutableStateOf(false) }
    val r = reads[u]
    Text("Order flow: ${OrderFlow.word(r)}" + if (r != null) " (its future)" else " (needs the Zerodha stream)",
        style = Type.bodySmall.copy(color = sideColor(r), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 6.dp).clickable { open = true })
    if (open) OrderFlowSheet(u) { open = false }
}

/**
 * The detail: the side and its strength, each part with its z-score against the last 30 minutes, and the buyers' share
 * every 10 s over those 30 minutes. Reads only.
 */
@Composable
internal fun OrderFlowSheet(underlying: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val reads by OrderFlowLive.reads.collectAsState(Dispatchers.Main.immediate)
    val r = reads[underlying]
    // The history is copied off the main thread when the read changes (at most every 250 ms while open).
    var hist by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    LaunchedEffect(r?.atSec) { hist = withContext(Dispatchers.Default) { runCatching { OrderFlowLive.history(underlying) }.getOrDefault(emptyList()) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp).background(p.paper, RoundedCornerShape(16.dp)).padding(14.dp)
            .verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order flow · ${OrderFlow.label(underlying)}", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Text(OrderFlow.word(r).replaceFirstChar { it.uppercase() }, style = Type.title.copy(color = sideColor(r), fontSize = 22.sp))
            Note("From the near future's full-mode ticks (5-level book, total buy / sell quantity, volume, OI) and the ATM ±2 options of the " +
                "index in focus. Each part is scored against its own last 30 minutes. A live read, not a forecast.")
            if (r == null) Note("No live flow now: it needs the Zerodha price stream in market hours.")
            else OrderFlow.components(r).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(label, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
                    Text(value, style = Type.figure.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
                }
            }
            Text("Last 30 minutes (buyers' share; the line is 50)", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp),
                modifier = Modifier.padding(top = 10.dp))
            FlowHistory(hist, Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp))
            Note("Strategies only log the flow beside their signals unless you set one to CONFIRM (Home → Strategies → Order flow). " +
                "It can only ever skip an entry; it never places, adds to, reverses or exits a trade.")
        }
    }
}

/** The buyers' share over 30 minutes: a line, 0 at the bottom, 100 at the top, a faint rule at 50. */
@Composable
private fun FlowHistory(points: List<Pair<Long, Int>>, modifier: Modifier) {
    val p = LocalPalette.current
    val line = p.ink
    val mid = p.rule
    Canvas(modifier.semantics { contentDescription = "Buyers' share over the last 30 minutes" }) {
        val h = size.height; val w = size.width
        drawLine(mid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
        if (points.size < 2) return@Canvas
        val t0 = points.first().first; val t1 = points.last().first
        val span = (t1 - t0).coerceAtLeast(1L).toFloat()
        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            val x = (t - t0) / span * w
            val y = h - v / 100f * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2f))
    }
}

/**
 * The Order flow section of Home's Strategies card: each index's live read in one line (a tap opens its detail), how many
 * strategies confirm with it, and the button to the per-strategy settings and record. Paper and live alike, the flow only
 * ever skips an entry; never in the gold build.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OrderFlowSection(model: AppModel) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val p = LocalPalette.current
    val reads by OrderFlowLive.reads.collectAsState(Dispatchers.Main.immediate)
    val modes by FlowGate.modes.collectAsState(Dispatchers.Main.immediate)
    var detail by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { FlowGate.setting("orb") } } }   // the modes read once, off the main thread
    Rule()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Order flow", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        // "Flow BN: buyers 72 · NF: balanced 51": the read of each index the stream gives, BankNifty first.
        val order = listOf("BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY")
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            order.forEach { u ->
                val r = reads[u]
                Text(OrderFlow.line(u, r),
                    style = Type.bodySmall.copy(color = sideColor(r), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.heightIn(min = 32.dp).clickable { detail = u }.padding(vertical = 6.dp))
            }
        }
        val confirming = modes.count { it.value.mode == OrderFlow.Mode.CONFIRM }
        Note(if (confirming == 0) "Logged beside every strategy's signal (SHADOW); no entry is skipped by it." else
            "$confirming strateg${if (confirming == 1) "y skips" else "ies skip"} entries the flow does not agree with (CONFIRM). Unproven: it may also skip winners.")
        BrassButton("Order flow per strategy", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { settings = true }
    }
    detail?.let { u -> OrderFlowSheet(u) { detail = null } }
    if (settings) OrderFlowModesSheet(model) { settings = false }
}

/**
 * Every strategy that takes a direction with its order-flow mode (OFF / SHADOW / CONFIRM), CONFIRM's threshold (50-80), and
 * its paper record with the flow against its record against it, from the log. CONFIRM on a strategy that can reach Zerodha,
 * in Live, asks for the PIN or fingerprint first.
 */
@Composable
internal fun OrderFlowModesSheet(model: AppModel, onClose: () -> Unit) {
    val modes by FlowGate.modes.collectAsState(Dispatchers.Main.immediate)
    val records by FlowGate.records.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { withContext(Dispatchers.IO) { runCatching { FlowGate.refreshRecords() } } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun choose(key: String, mode: OrderFlow.Mode, threshold: Int) {
        scope.launch(Dispatchers.IO) {
            if (runCatching { FlowGate.needsPin(key, mode) }.getOrDefault(true)) { withContext(Dispatchers.Main) { pin = key to threshold }; return@launch }
            val msg = runCatching { FlowGate.set(key, mode, threshold) }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = msg }
        }
    }
    OrderFlowModesContent(modes, records, said, onChoose = { k, m, t -> choose(k, m, t) }, onClose = onClose)
    pin?.let { (key, th) ->
        Reauth(model, pinOnly = false, why = "Enter your app PIN to let the order flow skip this strategy's LIVE entries (it can only skip).",
            onOk = {
                pin = null
                scope.launch(Dispatchers.IO) {
                    val msg = runCatching { FlowGate.set(key, OrderFlow.Mode.CONFIRM, th, pinConfirmed = true) }.getOrElse { "Could not save that." }
                    withContext(Dispatchers.Main) { said = msg; refresh++ }
                }
            },
            onCancel = { pin = null })
    }
}

/** The settings sheet from plain state (tests drive it without an [AppModel]). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OrderFlowModesContent(
    modes: Map<String, FlowShadow.Setting>,
    records: List<FlowShadow.Record>,
    said: String?,
    onChoose: (String, OrderFlow.Mode, Int) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order flow per strategy", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Note("SHADOW (the default) logs the flow beside each signal and changes nothing. CONFIRM skips an entry unless the flow " +
                "agrees at the decision (a call needs buyers, a put sellers, with real trades behind them, at the threshold or more). " +
                "With no live flow, an unreliable feed or in a trap window it has no opinion and the entry goes ahead, logged. " +
                "It never places, adds to, reverses or exits a trade. Live: turning CONFIRM on asks for your PIN.")
            said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                var group = ""
                FlowShadow.STRATEGIES.forEach { st ->
                    if (st.group != group) {
                        group = st.group
                        Text(group.uppercase(java.util.Locale.ENGLISH), style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp),
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    val s = FlowShadow.setting(modes, st.key)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(st.label + if (st.liveCapable) "" else " · paper only",
                            style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OrderFlow.Mode.entries.forEach { m ->
                                val on = s.mode == m
                                Text(m.name, maxLines = 1, softWrap = false,
                                    style = Type.label.copy(color = if (on) p.onPrimary else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.semantics { contentDescription = "${st.label}: order flow ${m.name}" }
                                        .background(if (on) p.brass else p.chip, RoundedCornerShape(50))
                                        .clickable { if (!on) onChoose(st.key, m, s.threshold) }.heightIn(min = 36.dp)
                                        .padding(horizontal = 12.dp, vertical = 9.dp))
                            }
                            if (s.mode == OrderFlow.Mode.CONFIRM) {
                                Text("−", style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.semantics { contentDescription = "${st.label}: lower the threshold" }
                                        .background(p.chip, RoundedCornerShape(50))
                                        .clickable(enabled = s.threshold > OrderFlow.MIN_THRESHOLD) { onChoose(st.key, s.mode, s.threshold - 5) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp))
                                Text("at ${s.threshold}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(vertical = 8.dp))
                                Text("+", style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.semantics { contentDescription = "${st.label}: raise the threshold" }
                                        .background(p.chip, RoundedCornerShape(50))
                                        .clickable(enabled = s.threshold < OrderFlow.MAX_THRESHOLD) { onChoose(st.key, s.mode, s.threshold + 5) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                        if (s.mode == OrderFlow.Mode.CONFIRM)
                            Text("Unproven — may also skip winners", style = Type.label.copy(color = p.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(top = 4.dp))
                        Text(keepNumbersWhole(FlowShadow.recordLine(records.firstOrNull { it.key == st.key })),
                            style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
