package com.optionslab.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.FlowArm
import com.optionslab.app.data.KiteStream
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Palette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.signals.FlowRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val FLOW_TITLE = "IraThinker's flow"

/** What the stream and the market are doing now, read from memory for the page (no network, no disk). */
internal data class FlowStreamFacts(
    val loggedIn: Boolean, val status: KiteStream.Status, val instruments: Int, val lastTickAgoS: Long?, val reconnects: Int,
    val marketOpen: Boolean,
    /** Index -> (last price, change % from yesterday's close or null). */
    val prices: Map<String, Pair<Double, Double?>> = emptyMap(),
    val vix: Pair<Double, Double?>? = null,
    /** Options followed on the stream now (the option chain on screen, held options). */
    val options: Int = 0,
)

/** One source tile: its title, its lines, and why it is dimmed (null: on). */
internal data class FlowSource(val title: String, val lines: List<String>, val dim: String?)

/** One engine stage: how many of today's plans passed it, and why it is dimmed (null: on). */
internal data class FlowStageBox(val stage: FlowRules.Stage, val passed: Int, val dim: String?)

/** The whole page, from memory. [netToday] null while the app is locked (no rupees on a locked app). */
internal data class FlowPageState(
    val armed: Boolean, val sources: List<FlowSource>, val stages: List<FlowStageBox>, val ordersToday: Int, val openNow: Int,
    val skips: List<FlowArm.Candidate>, val candidates: List<FlowArm.Candidate>, val netToday: Double?, val engineDim: String?,
    val sessions: Int,
)

/** Seconds since the last tick after which the stream counts as stale for the page. */
internal const val FLOW_STALE_S = 60L

/** The stream's reason to dim, or null when it is live and fresh. */
internal fun flowStreamDim(f: FlowStreamFacts): String? = when {
    !f.loggedIn -> "logged out of Zerodha"
    !f.marketOpen -> "market closed"
    f.status == KiteStream.Status.OFF -> "Zerodha's stream is off"
    f.status != KiteStream.Status.LIVE -> "Zerodha's stream is reconnecting"
    f.lastTickAgoS == null -> "no tick yet"
    f.lastTickAgoS > FLOW_STALE_S -> "stale ticks (${f.lastTickAgoS} s since the last)"
    else -> null
}

private fun pct(x: Double?) = x?.let { "%+.2f%%".format(Locale.ENGLISH, it) } ?: ""
private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)

/** The page's state from the arm's [v] and the stream's [f]; [locked]: no rupee figure. Pure. */
internal fun flowPageState(v: FlowArm.View, f: FlowStreamFacts, locked: Boolean): FlowPageState {
    val dim = flowStreamDim(f)
    val streamLines = listOf(
        when (f.status) { KiteStream.Status.LIVE -> "connected"; KiteStream.Status.OFF -> "off"; else -> "reconnecting" },
        "${f.instruments} instruments",
        f.lastTickAgoS?.let { "last tick ${it}s ago" } ?: "no tick yet",
        "${f.reconnects} reconnects today",
    )
    val idxLines = FlowRules.INDICES.map { u -> f.prices[u]?.let { (p, c) -> "$u ${px(p)} ${pct(c)}".trim() } ?: "$u —" }
    val sources = listOf(
        FlowSource("Zerodha stream", streamLines, dim),
        FlowSource("Index prices", idxLines, dim ?: if (f.prices.isEmpty()) "no index tick yet" else null),
        FlowSource("India VIX", listOf(f.vix?.let { (p, c) -> "${px(p)} ${pct(c)}".trim() } ?: "—"), dim ?: if (f.vix == null) "no VIX tick yet" else null),
        FlowSource("Option chain", listOf("${f.options} options on the stream", "context only"), dim),
    )
    val engineDim = dim?.let { "paused: $it" } ?: if (!v.armed) "the Flow arm is off" else null
    val stages = FlowRules.Stage.entries.map { FlowStageBox(it, v.passed(it), engineDim) }
    return FlowPageState(v.armed, sources, stages, v.takenToday, v.open.size, v.skips, v.candidates,
        if (locked) null else v.today.sumOf { it.net ?: 0.0 }, engineDim, v.sessions)
}

/**
 * Battery (android/BATTERY_AUDIT_2026-10-27.md): the diagram animates only while the page is on screen with the app in the
 * foreground ([resumed]); it is drawn still under Android's battery saver, or at 30% or less while not charging.
 */
internal fun flowAnimates(resumed: Boolean, saver: Boolean, percent: Int?, charging: Boolean): Boolean =
    resumed && !saver && !(percent != null && percent <= 30 && !charging)

/** The stream and market facts from memory (the stream's last ticks and counters). */
private fun streamFacts(): FlowStreamFacts {
    val now = System.currentTimeMillis()
    fun quote(name: String): Pair<Double, Double?>? = runCatching {
        val t = KiteStream.indexToken(name)?.let { KiteStream.seen(it) }?.tick ?: return@runCatching null
        if (t.last <= 0) null else t.last to t.close.takeIf { it > 0 }?.let { (t.last / it - 1) * 100 }
    }.getOrNull()
    val prices = FlowRules.INDICES.mapNotNull { u -> quote(u)?.let { u to it } }.toMap()
    val following = runCatching { KiteStream.following() }.getOrDefault(0)
    return FlowStreamFacts(
        loggedIn = runCatching { com.optionslab.app.data.Broker.loggedIn }.getOrDefault(false),
        status = KiteStream.status.value,
        instruments = following,
        lastTickAgoS = KiteStream.lastTickAt.takeIf { it > 0 }?.let { ((now - it) / 1000).coerceAtLeast(0) },
        reconnects = KiteStream.reconnectsToday(),
        marketOpen = runCatching { com.optionslab.app.data.Market.isOpen() }.getOrDefault(false),
        prices = prices,
        vix = quote(FlowRules.VIX),
        options = (following - KiteStream.SESSION_TOKENS.size).coerceAtLeast(0),
    )
}

/**
 * Cabinet → Automation → IraThinker's flow (28 Oct 2026, Boss: "Also check flow page on Irathinker and implement that too"): IraThinker's
 * animated flow, for the INDICES only - Zerodha's stream, the index prices, India VIX and the option chain feeding the engine
 * (Scanner → IraScore → Time Machine → Risk Gate, with the Flow arm's live counts), and the results (Flow orders today, open Flow
 * positions, skips). Plain cards below: today's candidates, their scores and odds, why each was taken or skipped. No MCX, no
 * Global, no Alerts node: the flow takes paper orders, it never alerts. Memory reads only; no worker or polling loop of its own.
 */
@Composable
fun FlowPage(@Suppress("UNUSED_PARAMETER") model: AppModel) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val v by FlowArm.view.collectAsState(Dispatchers.Main.immediate)
    val status by KiteStream.status.collectAsState(Dispatchers.Main.immediate)
    val locked by com.optionslab.app.security.SessionLock.locked.collectAsState(Dispatchers.Main.immediate)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val life by owner.lifecycle.currentStateFlow.collectAsState()
    val resumed = life.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val context = LocalContext.current
    var power by remember { mutableStateOf<Triple<Boolean, Int?, Boolean>?>(null) }
    // The battery and the saver read once each time the page is resumed (Android's cached reading, off the main thread).
    LaunchedEffect(resumed) {
        if (resumed) power = withContext(Dispatchers.IO) {
            runCatching { com.optionslab.app.work.Battery.reading(context).let { r -> Triple(com.optionslab.app.work.Battery.powerSave(context), r.percent, r.charging) } }.getOrNull()
        }
    }
    val animate = power?.let { (saver, pc, ch) -> flowAnimates(resumed, saver, pc, ch) } ?: false
    // Prices redraw with the stream's version (at most 4 a second) only while animating; still, the page follows the arm and the status.
    val version by (if (animate) KiteStream.version else remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { FlowArm.refresh() } } }
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val facts = remember(version, status, v) { streamFacts() }
    FlowContent(flowPageState(v, facts, locked), animate, said,
        onArm = { on -> scope.launch { said = withContext(Dispatchers.IO) { runCatching { FlowArm.setArmed(on) }.getOrElse { "Could not switch it (${it.javaClass.simpleName})." } } } })
}

/** The page from plain state (tests drive it). [animate] false: the diagram is drawn still. */
@Composable
internal fun FlowContent(s: FlowPageState, animate: Boolean, said: String?, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("flow-page")) {
        LedgerCard(title = FLOW_TITLE) {
            Text(FlowRules.NOT_PROVEN, style = Type.label.copy(color = p.oxblood, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("flow-not-proven"))
            Note("IraThinker's own flow, run inside IraAlgo on Zerodha's live stream: index options only (${FlowRules.INDICES.joinToString()}). " +
                "A plan that passes all four stages becomes a paper order - no alert, no notification. Live only the standard way: Live with " +
                "your PIN, \"AI trades go live\" and its own Trade live switch (off until you turn it on).")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.armed) "ON · PAPER · INDEX OPTIONS ONLY" else "OFF", style = Type.label.copy(color = if (s.armed) p.verdigris else p.inkFaint,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Switch(checked = s.armed, onCheckedChange = onArm,
                    modifier = Modifier.semantics { contentDescription = "On paper: switch $FLOW_TITLE" }.testTag("flow-switch"),
                    colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card))
            }
            said?.let { Text(it, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.testTag("flow-said")) }
        }
        Spacer(Modifier.height(10.dp))
        LedgerCard {
            FlowDiagram(s, animate)
            // Each dimmed part with its plain reason.
            s.sources.filter { it.dim != null }.distinctBy { it.dim }.forEach { src ->
                Text("${src.title}: ${src.dim}", style = Type.bodySmall.copy(color = p.amber), modifier = Modifier.testTag("flow-dim"))
            }
            s.engineDim?.let { Text("Engine: $it", style = Type.bodySmall.copy(color = p.amber), modifier = Modifier.testTag("flow-engine-dim")) }
            Text(if (animate) "Moving while this page is open." else "Drawn still (battery saver, battery at 30% or less, or the app in the background).",
                style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp), modifier = Modifier.testTag(if (animate) "flow-moving" else "flow-still"))
        }
        Spacer(Modifier.height(10.dp))
        LedgerCard(title = "Today") {
            Text("Flow orders today: ${s.ordersToday} · open Flow positions: ${s.openNow} · skipped: ${s.skips.size}",
                style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.testTag("flow-results"))
            s.netToday?.let { n -> if (s.ordersToday > 0) Text("Today on paper: " + (if (n < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(n)) +
                " after charges", style = Type.bodySmall.copy(color = if (n < 0) p.oxblood else p.verdigris), modifier = Modifier.testTag("flow-net")) }
            Text("Time Machine history: ${s.sessions} Zerodha session${if (s.sessions == 1) "" else "s"} recorded by this arm (it needs about two before it can replay).",
                style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
            if (s.candidates.isEmpty()) Text("No index judged yet today.", style = Type.bodySmall.copy(color = p.inkSoft))
            s.candidates.forEach { c ->
                Rule(Modifier.padding(vertical = 6.dp))
                Text("${c.index} · ${if (c.taken) "TAKEN (paper)" else c.stage}${if (c.setup != "NONE") " · ${c.setup}" else ""}",
                    style = Type.label.copy(color = if (c.taken) p.verdigris else p.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("flow-cand-${c.index}"))
                val nums = listOfNotNull(c.score?.let { "IraScore $it" }, c.lite?.takeIf { it != c.score }?.let { "lite $it" },
                    c.targetFirst?.let { tf -> "target first %.0f%% vs stop first %.0f%%".format(Locale.ENGLISH, tf, c.stopFirst ?: 0.0) },
                    c.analogs?.let { "$it past firings" })
                if (nums.isNotEmpty()) Text(nums.joinToString(" · "), style = Type.bodySmall.copy(color = p.inkSoft))
                Text((if (c.taken) "" else if (c.final) "Skipped: " else "Now: ") + c.why, style = Type.bodySmall.copy(color = p.ink))
            }
        }
        Spacer(Modifier.height(10.dp))
        LedgerCard(title = "How it decides") {
            Note("Scanner: the opening range 09:15-09:30 and the first 5-minute close beyond it. IraScore: IraThinker's 0-100 score; above " +
                "${FlowRules.MIN_SCORE}, at least ${FlowRules.MIN_TARGET_POINTS.toInt()} points to target, a range at most ${FlowRules.MAX_RANGE_WIDTH_PCT}% wide, and " +
                "India VIX broken up first (so only breakdowns: a PUT). Time Machine: ${FlowRules.PATHS} replays from Zerodha's own recorded sessions; target first " +
                "at least stop first. Risk Gate: reward to risk 0.9+, the max-loss budget, 09:30-15:00. Then every guard: kill switch, day lock, account " +
                "guard, paper limits, same-direction cap, ${FlowRules.DAILY_TOP_N} a day, one per index; the index's own expiry day is skipped.")
            Note("Exits: a premium stop 20% under the fill resting in the paper book; IraThinker's index stop (the range midpoint) and 2R target; " +
                "out by 15:15. Signal source: ${FlowRules.SIGNAL}.")
        }
    }
}

/** The animated diagram ([animate] false: one still frame). Colours from the theme, light and dark. */
@Composable
private fun FlowDiagram(s: FlowPageState, animate: Boolean) {
    val p = LocalPalette.current
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "flow")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase").value
    } else 0f
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(430.dp).testTag(if (animate) "flow-diagram-live" else "flow-diagram-static")
        .semantics { contentDescription = "IraThinker's flow diagram: sources, Scanner, IraScore, Time Machine, Risk Gate, results" }) {
        drawFlow(s, phase, p, tm)
    }
}

private fun DrawScope.drawFlow(s: FlowPageState, phase: Float, p: Palette, tm: androidx.compose.ui.text.TextMeasurer) {
    val w = size.width; val h = size.height
    val accent = p.verdigris; val warm = p.amber; val line = p.inkFaint
    val gap = 8.dp.toPx()
    val tileW = (w - gap * 3) / 4f; val tileH = 72.dp.toPx(); val top = 4.dp.toPx()
    val core = Offset(w / 2, h * 0.47f); val coreR = minOf(w, h) * 0.13f
    val hubTop = h * 0.78f
    fun label(text: String, at: Offset, style: TextStyle, center: Boolean = false) {
        val r = tm.measure(text, style)
        drawText(r, topLeft = if (center) Offset(at.x - r.size.width / 2f, at.y) else at)
    }
    val small = TextStyle(color = p.inkSoft, fontSize = 9.sp)
    val bold = TextStyle(color = p.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    // Ribbons: each source into the core, the core into the results. A dimmed source's ribbon is faint and carries nothing.
    s.sources.forEachIndexed { i, src ->
        val sx = i * (tileW + gap) + tileW / 2; val sy = top + tileH
        val ex = core.x + (i - 1.5f) * coreR * 0.45f; val ey = core.y - coreR
        val on = src.dim == null
        ribbon(Offset(sx, sy), Offset(ex, ey), if (on) (if (i % 2 == 0) accent else warm) else line, on, phase + i * 0.23f)
    }
    for (i in 0 until 3) {
        val sx = core.x + (i - 1) * coreR * 0.6f; val sy = core.y + coreR
        ribbon(Offset(sx, sy), Offset(w * (0.18f + i * 0.32f), hubTop), if (s.engineDim == null) accent else line, s.engineDim == null, phase + 0.4f + i * 0.31f)
    }
    // Source tiles.
    s.sources.forEachIndexed { i, src ->
        val x = i * (tileW + gap)
        val a = if (src.dim == null) 1f else 0.4f
        drawRoundRect(p.card, Offset(x, top), Size(tileW, tileH), CornerRadius(10.dp.toPx()))
        drawRoundRect((if (i % 2 == 0) accent else warm).copy(alpha = a), Offset(x, top), Size(tileW, tileH), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
        label(src.title, Offset(x + 6.dp.toPx(), top + 4.dp.toPx()), bold.copy(color = p.ink.copy(alpha = a)))
        src.lines.take(4).forEachIndexed { k, l -> label(l, Offset(x + 6.dp.toPx(), top + (18 + 12 * k).dp.toPx()), small.copy(color = p.inkSoft.copy(alpha = a))) }
    }
    // The engine core: rings that turn while animating, and the four stages around it, lit in sequence.
    val ea = if (s.engineDim == null) 1f else 0.4f
    drawCircle(accent.copy(alpha = 0.10f * ea), coreR * 1.9f, core)
    val turn = phase * 360f
    for (k in 0 until 3) drawArc(accent.copy(alpha = 0.8f * ea), turn + k * 120f, 52f, false, Offset(core.x - coreR, core.y - coreR), Size(coreR * 2, coreR * 2), style = Stroke(2.dp.toPx()))
    for (k in 0 until 3) drawArc(warm.copy(alpha = 0.8f * ea), -turn * 1.4f + k * 120f + 40f, 64f, false, Offset(core.x - coreR * 0.75f, core.y - coreR * 0.75f),
        Size(coreR * 1.5f, coreR * 1.5f), style = Stroke(2.dp.toPx()))
    for (i in 0 until 60) {
        val a = (i * 6 + turn * 0.3f) * PI.toFloat() / 180f
        val r1 = coreR * 1.06f; val r2 = coreR * (if (i % 5 == 0) 1.2f else 1.13f)
        drawLine(accent.copy(alpha = (if (i % 5 == 0) 0.8f else 0.35f) * ea), Offset(core.x + cos(a) * r1, core.y + sin(a) * r1), Offset(core.x + cos(a) * r2, core.y + sin(a) * r2), 1.dp.toPx())
    }
    drawCircle(p.card, coreR * 0.5f, core)
    label("ENGINE", Offset(core.x, core.y - 7.dp.toPx()), bold.copy(color = accent.copy(alpha = ea)), center = true)
    val lit = if (s.engineDim == null) (phase * 4).toInt().coerceIn(0, 3) else -1
    val spots = listOf(Offset(core.x - coreR * 1.45f, core.y - coreR * 0.55f), Offset(core.x - coreR * 1.45f, core.y + coreR * 0.55f),
        Offset(core.x + coreR * 1.45f, core.y - coreR * 0.55f), Offset(core.x + coreR * 1.45f, core.y + coreR * 0.55f))
    s.stages.forEachIndexed { i, st ->
        val at = spots[i]
        val color = if (st.dim != null) p.inkFaint else if (i == lit) p.ink else p.inkSoft
        label(st.stage.words, Offset(at.x, at.y - 8.dp.toPx()), bold.copy(color = color), center = true)
        label("${st.passed} passed", Offset(at.x, at.y + 5.dp.toPx()), small.copy(color = color), center = true)
    }
    // The results panel.
    drawRoundRect(p.card, Offset(0f, hubTop), Size(w, h - hubTop), CornerRadius(14.dp.toPx()))
    drawRoundRect(accent.copy(alpha = 0.6f), Offset(0f, hubTop), Size(w, h - hubTop), CornerRadius(14.dp.toPx()), style = Stroke(1.dp.toPx()))
    val results = listOf("Flow orders today" to "${s.ordersToday}", "Open Flow positions" to "${s.openNow}", "Skips" to "${s.skips.size}")
    results.forEachIndexed { i, (t, n) ->
        val cx = w * (0.18f + i * 0.32f)
        label(n, Offset(cx, hubTop + 10.dp.toPx()), TextStyle(color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold), center = true)
        label(t, Offset(cx, hubTop + 36.dp.toPx()), small, center = true)
    }
}

/** A curved stream from [a] to [b]; when [on], three packets ride it at [phase]. */
private fun DrawScope.ribbon(a: Offset, b: Offset, color: Color, on: Boolean, phase: Float) {
    val c1 = Offset(a.x, a.y + (b.y - a.y) * 0.45f); val c2 = Offset(b.x, b.y - (b.y - a.y) * 0.45f)
    val path = Path().apply { moveTo(a.x, a.y); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y) }
    drawPath(path, color.copy(alpha = if (on) 0.25f else 0.12f), style = Stroke(5.dp.toPx()))
    drawPath(path, color.copy(alpha = if (on) 0.7f else 0.25f), style = Stroke(1.4.dp.toPx()))
    if (!on) return
    for (k in 0 until 3) {
        val t = ((phase + k / 3f) % 1f)
        val u = 1 - t
        val x = u * u * u * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * b.x
        val y = u * u * u * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * b.y
        drawCircle(color.copy(alpha = 0.3f), 5.dp.toPx(), Offset(x, y))
        drawCircle(color, 2.dp.toPx(), Offset(x, y))
    }
}
