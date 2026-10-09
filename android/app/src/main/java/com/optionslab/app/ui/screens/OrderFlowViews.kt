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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.optionslab.app.data.MoveRecorder
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
    val auctions by OrderFlowLive.auction.collectAsState(Dispatchers.Main.immediate)
    // The heatmap or the tape, opened from this sheet (each its own sheet over it).
    var tool by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp).background(p.paper, RoundedCornerShape(16.dp)).padding(14.dp)
            .verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order flow · ${OrderFlow.label(underlying)}", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Text(OrderFlow.word(r).replaceFirstChar { it.uppercase() }, style = Type.title.copy(color = sideColor(r), fontSize = 22.sp))
            // The auction regime against the prior day's value area, as one word (display only).
            auctions[underlying]?.regime?.let { rg ->
                Text("Auction: ${rg.chip}", maxLines = 1, softWrap = false,
                    style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = "Auction regime: ${rg.words}" }
                        .background(p.chip, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            // The read's notes (display only): a delta divergence on the future, a level that absorbed the flow.
            val notes = listOfNotNull(auctions[underlying]?.divergence?.words, OrderFlow.absorption(r))
            if (notes.isNotEmpty()) Text("⚠ " + notes.joinToString(" · "), style = Type.bodySmall.copy(color = p.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
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
            AuctionBlock(underlying, auctions[underlying], onHeat = { tool = "heat" }, onTape = { tool = "tape" })
            BigMovesBlock()
            Note("Strategies only log the flow beside their signals unless you set one to CONFIRM (Home → Strategies → Order flow). " +
                "It can only ever skip an entry; it never places, adds to, reverses or exits a trade.")
        }
    }
    when (tool) {
        "heat" -> HeatmapSheet(underlying) { tool = null }
        "tape" -> TapeSheet(underlying) { tool = null }
    }
}

/**
 * The detail's auction part (live readings and shadow-log fields; no strategy uses them): the regime against the prior
 * day's value area, the two profiles, delta and its divergence, VWAP, the market profile, the gamma regime, the per-minute
 * delta bars and the 15-minute footprint, and the buttons to the heatmap and the tape. Reads [OrderFlowLive.auction] and
 * [com.optionslab.app.data.GammaLive]'s state only.
 */
@Composable
private fun AuctionBlock(underlying: String, a: com.optionslab.ira.Auction.Snapshot?, onHeat: () -> Unit, onTape: () -> Unit) {
    val p = LocalPalette.current
    val gamma by com.optionslab.app.data.GammaLive.state.collectAsState(Dispatchers.Main.immediate)
    val conv by com.optionslab.app.data.GammaLive.convention.collectAsState(Dispatchers.Main.immediate)
    Text("Auction, delta, VWAP, market profile", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 12.dp))
    Note("Live readings from the future's trades, logged beside every signal for research. No strategy uses them.")
    val rows = (if (a == null) listOf("Auction" to "no live profile yet (the future's trades in market hours)") else com.optionslab.ira.Auction.lines(a)) +
        (if (underlying in com.optionslab.app.data.GammaLive.INDICES)
            listOf("Gamma (${conv.label.lowercase(java.util.Locale.ENGLISH)} sign)" to com.optionslab.ira.GammaRegime.line(gamma[underlying], conv).removePrefix("Gamma: "))
        else emptyList())
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
            Text(value, style = Type.figure.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
        }
    }
    if (a != null && a.deltas.isNotEmpty()) {
        Text("Delta per minute, last 30 minutes", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 8.dp))
        DeltaBars(a.deltas.map { it.second }, Modifier.fillMaxWidth().height(60.dp).padding(top = 4.dp))
    }
    if (a != null && a.footprint.isNotEmpty()) {
        Text("Footprint, last 15 minutes (bought × sold at each price)", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp),
            modifier = Modifier.padding(top = 8.dp))
        a.footprint.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text("%,.2f".format(java.util.Locale.ENGLISH, f.price), style = Type.figure.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
                Text("%,d".format(java.util.Locale.ENGLISH, f.buy), style = Type.figure.copy(color = p.verdigris, fontSize = 12.sp), modifier = Modifier.weight(1f))
                Text("%,d".format(java.util.Locale.ENGLISH, f.sell), style = Type.figure.copy(color = p.oxblood, fontSize = 12.sp), modifier = Modifier.weight(1f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onHeat) { Text("Liquidity heatmap") }
        TextButton(onTape) { Text("Time & sales") }
    }
}

/**
 * The detail's "Big moves" (display only): the big-move recorder's saved minutes, newest first - time, index, size and
 * direction, each read from its file's first line - and a tap opens that event's summary in place (the futures' flow
 * before and during, calls against puts, the book in the way thinning, VIX, what stood out first, the context), read off
 * the main thread. The recorder's switch is here too (it also keeps the price stream on in market hours).
 */
@Composable
private fun BigMovesBlock() {
    val p = LocalPalette.current
    val version by MoveRecorder.version.collectAsState(Dispatchers.Main.immediate)
    val on by MoveRecorder.onFlow.collectAsState(Dispatchers.Main.immediate)
    var items by remember { mutableStateOf<List<MoveRecorder.Item>?>(null) }
    var controls by remember { mutableStateOf(0) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(version) {
        val got = withContext(Dispatchers.IO) {
            runCatching { MoveRecorder.refreshSwitch(); MoveRecorder.items(20) to MoveRecorder.controls() }.getOrNull()
        }
        items = got?.first.orEmpty(); controls = got?.second ?: 0
    }
    LaunchedEffect(openFile) {
        lines = null
        val f = items?.firstOrNull { it.file.name == openFile }?.file ?: return@LaunchedEffect
        lines = withContext(Dispatchers.IO) { runCatching { MoveRecorder.summary(f)?.let { com.optionslab.ira.MoveEvents.lines(it) } }.getOrNull() }
            ?: listOf("Summary" to "could not be read")
    }
    Text("Big moves (saved minutes)", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 12.dp))
    Note("A futures minute 4.7 times its usual size for that time of day: the 5 minutes before and after are saved second by " +
        "second, with the 5-level book, for research. Recording only; no strategy uses it.")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "Recorder on: the price stream stays on 9:15-15:40 (not in battery saver)" else "Recorder off",
            style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
        TextButton({ scope.launch(Dispatchers.IO) { runCatching { MoveRecorder.on = !on } } }) { Text(if (on) "Turn off" else "Turn on") }
    }
    val list = items
    when {
        list == null -> Note("Reading the saved minutes…")
        list.isEmpty() -> Note("None saved yet: it needs the Zerodha stream in market hours, and a big minute.")
        else -> list.forEach { m ->
            val up = m.kind == com.optionslab.ira.MoveEvents.Kind.BIG_UP
            val day = "%d %s".format(java.util.Locale.ENGLISH, m.day.dayOfMonth, m.day.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { openFile = if (openFile == m.file.name) null else m.file.name }
                .padding(vertical = 4.dp).semantics { contentDescription = "Big move $day ${m.line}, show its summary" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(day, maxLines = 1, softWrap = false, style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(end = 8.dp))
                Text(m.line, style = Type.figure.copy(color = if (up) p.verdigris else p.oxblood, fontSize = 12.sp), modifier = Modifier.weight(1f))
                Text(if (openFile == m.file.name) "▴" else "▾", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            if (openFile == m.file.name) {
                val l = lines
                if (l == null) Note("Reading its minutes…")
                else l.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
                        Text(value, style = Type.figure.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (controls > 0) Note("Also saved: $controls ordinary minute${if (controls == 1) "" else "s"} to compare against (two a day per index).")
}

/** Signed bars around a middle line: buying minutes up in verdigris, selling minutes down in oxblood. */
@Composable
private fun DeltaBars(values: List<Long>, modifier: Modifier) {
    val p = LocalPalette.current
    val up = p.verdigris; val dn = p.oxblood; val mid = p.rule
    Canvas(modifier.semantics { contentDescription = "Delta per minute over the last 30 minutes" }) {
        val h = size.height; val w = size.width
        drawLine(mid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
        val mx = values.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: return@Canvas
        val bw = w / 30f
        values.takeLast(30).forEachIndexed { i, v ->
            val bh = (kotlin.math.abs(v).toFloat() / mx) * (h / 2)
            val x = w - (values.takeLast(30).size - i) * bw
            drawRect(if (v >= 0) up else dn, Offset(x + bw * 0.15f, if (v >= 0) h / 2 - bh else h / 2),
                androidx.compose.ui.geometry.Size(bw * 0.7f, maxOf(1f, bh)))
        }
    }
}

/** The future's or the charted option's (when one is followed) choice at the top of the heatmap and the tape. */
@Composable
private fun WhichInstrument(underlying: String, watch: Boolean, onWatch: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var option by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { option = withContext(Dispatchers.Default) { runCatching { OrderFlowLive.watchingName() }.getOrNull() } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false to "${OrderFlow.short(underlying)} future", true to "Charted option").forEach { (w, label) ->
            if (!w || option == underlying) Text(label, maxLines = 1, softWrap = false,
                style = Type.label.copy(color = if (w == watch) p.onPrimary else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(if (w == watch) p.brass else p.chip, RoundedCornerShape(50)).clickable { onWatch(w) }
                    .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}

/**
 * The liquidity heatmap (Bookmap-style, display only): the 5-level book's resting size by price × time over the last 30
 * minutes (one column per few seconds, darker = more size; bids green, offers red), pulled size (rings) and big prints
 * (dots). Copied off the main thread every 2 s while open.
 */
@Composable
internal fun HeatmapSheet(underlying: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    var watch by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<com.optionslab.ira.TapeHeat.Frame?>(null) }
    LaunchedEffect(watch) {
        while (true) {
            frame = withContext(Dispatchers.Default) { runCatching { OrderFlowLive.heat(underlying, watch) }.getOrNull() }
            kotlinx.coroutines.delay(2_000)
        }
    }
    val bid = p.verdigris; val ask = p.oxblood; val pull = p.amber; val big = p.ink; val grid = p.rule
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).heightIn(max = 640.dp).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Liquidity heatmap · ${OrderFlow.label(underlying)}", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            WhichInstrument(underlying, watch) { watch = it }
            val f = frame
            if (f == null || f.columns.isEmpty()) Note("No book recorded yet: it needs the Zerodha price stream in market hours.")
            else {
                Canvas(Modifier.fillMaxWidth().height(320.dp).semantics { contentDescription = "Resting size by price and time" }) {
                    val w = size.width; val h = size.height
                    val span = (f.high - f.low).coerceAtLeast(0.05)
                    fun y(px: Double) = (h - (px - f.low) / span * (h - 8) - 4).toFloat()
                    drawLine(grid, Offset(0f, h - 1), Offset(w, h - 1), 1f)
                    val cw = w / f.columns.size
                    val t0 = f.columns.first().sec; val t1 = f.columns.last().sec.coerceAtLeast(t0 + 1)
                    f.columns.forEachIndexed { i, c ->
                        for ((px, q) in c.bids) drawRect(bid.copy(alpha = (0.12f + 0.88f * q.toFloat() / f.maxQty.coerceAtLeast(1L)).coerceIn(0f, 1f)), Offset(i * cw, y(px) - 2f),
                            androidx.compose.ui.geometry.Size(maxOf(1f, cw), 4f))
                        for ((px, q) in c.asks) drawRect(ask.copy(alpha = (0.12f + 0.88f * q.toFloat() / f.maxQty.coerceAtLeast(1L)).coerceIn(0f, 1f)), Offset(i * cw, y(px) - 2f),
                            androidx.compose.ui.geometry.Size(maxOf(1f, cw), 4f))
                    }
                    for (d in f.dots) {
                        val x = ((d.sec - t0).toFloat() / (t1 - t0)) * w
                        val pulled = d.kind == com.optionslab.ira.TapeHeat.DotKind.PULL_BID || d.kind == com.optionslab.ira.TapeHeat.DotKind.PULL_ASK
                        if (pulled) drawCircle(pull, 5f, Offset(x, y(d.price)), style = Stroke(width = 2f))
                        else drawCircle(big, 4f, Offset(x, y(d.price)))
                    }
                }
                Text("%,.2f – %,.2f · last %d min".format(java.util.Locale.ENGLISH, f.low, f.high, ((f.columns.last().sec - f.columns.first().sec) / 60 + 1)),
                    style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
            }
            Note("Green: bids, red: offers, darker = more size resting. Rings: size pulled without trading; dots: big prints. " +
                "From Zerodha's 5 best levels once a second, so deeper orders are not seen. Display only.")
        }
    }
}

/**
 * Time & sales (display only): each volume change of the stream as a print - time, price, quantity, buyer or seller by the
 * tick rule - newest first, big prints (over 5× the median size) in bold. Copied off the main thread every second while open.
 */
@Composable
internal fun TapeSheet(underlying: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    var watch by remember { mutableStateOf(false) }
    var prints by remember { mutableStateOf<List<com.optionslab.ira.TapeHeat.Print>>(emptyList()) }
    LaunchedEffect(watch) {
        while (true) {
            prints = withContext(Dispatchers.Default) { runCatching { OrderFlowLive.tape(underlying, watch) }.getOrDefault(emptyList()) }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val hms = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ENGLISH) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time & sales · ${OrderFlow.label(underlying)}", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            WhichInstrument(underlying, watch) { watch = it }
            Note("Kite sends snapshots, not every trade: several trades between two updates show as one row. Bold: over 5× the usual size.")
            if (prints.isEmpty()) Note("No prints yet: they need the Zerodha price stream in market hours.")
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(prints.size) { i ->
                    val pr = prints[i]
                    val c = when { pr.side > 0 -> p.verdigris; pr.side < 0 -> p.oxblood; else -> p.inkSoft }
                    val wt = if (pr.big) FontWeight.Bold else FontWeight.Normal
                    Row(Modifier.fillMaxWidth().background(if (pr.big) p.chip else p.paper).padding(vertical = 2.dp)) {
                        Text(java.time.Instant.ofEpochMilli(pr.ms).atZone(com.optionslab.engine.IST).format(hms),
                            style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
                        Text("%,.2f".format(java.util.Locale.ENGLISH, pr.price), style = Type.figure.copy(color = c, fontSize = 12.sp, fontWeight = wt), modifier = Modifier.weight(1f))
                        Text("%,d".format(java.util.Locale.ENGLISH, pr.qty), style = Type.figure.copy(color = c, fontSize = 12.sp, fontWeight = wt), modifier = Modifier.weight(1f))
                        Text(when { pr.side > 0 -> "B"; pr.side < 0 -> "S"; else -> "·" }, style = Type.figure.copy(color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

/** Home's auction and gamma line for [underlying] ("BN: inside · Gamma: positive (choppy) · zero-γ 52,300 ..."). */
internal fun homeAuctionLine(underlying: String, a: com.optionslab.ira.Auction.Snapshot?, g: com.optionslab.ira.GammaRegime.Result?,
                             c: com.optionslab.ira.GammaRegime.Convention): String =
    com.optionslab.ira.Auction.homeWord(underlying, a) + if (underlying in com.optionslab.app.data.GammaLive.INDICES) " · " + com.optionslab.ira.GammaRegime.line(g, c) else ""

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
        // The auction regime and the gamma regime of BankNifty and Nifty (live readings; no strategy uses them).
        val auctions by OrderFlowLive.auction.collectAsState(Dispatchers.Main.immediate)
        val gamma by com.optionslab.app.data.GammaLive.state.collectAsState(Dispatchers.Main.immediate)
        val conv by com.optionslab.app.data.GammaLive.convention.collectAsState(Dispatchers.Main.immediate)
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.GammaLive.loadConvention() } } }
        listOf("BANKNIFTY", "NIFTY").forEach { u ->
            Text(homeAuctionLine(u, auctions[u], gamma[u], conv), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp),
                modifier = Modifier.heightIn(min = 32.dp).clickable { detail = u }.padding(vertical = 4.dp))
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
