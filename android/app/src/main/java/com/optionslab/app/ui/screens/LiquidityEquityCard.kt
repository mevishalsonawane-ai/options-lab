package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.LiquidityEquity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Liquidity 15+5's "Paper record" under its row on the Strategies card: the record read by [load] off the main thread
 * ([com.optionslab.app.data.ForwardRecords.liquidityEquity] in the app) once, and again when [refresh] changes (a trade
 * closed). Nothing until it is read; a record that cannot be read shows as none. Reads only: it places, closes and arms
 * nothing. Never in the GOLD build.
 */
@Composable
internal fun LiquidityEquityCard(refresh: Any?, load: suspend () -> LiquidityEquity.Equity?) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    var record by remember { mutableStateOf<LiquidityEquity.Equity?>(null) }
    LaunchedEffect(refresh) {
        val r = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() ?: LiquidityEquity.of(emptyList()) }
        record = r
    }
    record?.let { LiquidityEquityContent(it) }
}

/**
 * The card: the running net per lot of the closed paper trades (solid, in the verdict's colour) over the backtest's expected
 * path (dashed) and its ±2·sd·√n band (shaded), the running drawdown under it (a small red area), the figures and the
 * forward check's line. Under [LiquidityEquity.MIN_POINTS] trades, a "waiting for trades" line instead. A tap on the chart
 * opens the larger view with every trade.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiquidityEquityContent(q: LiquidityEquity.Equity) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.chip, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("PAPER RECORD", style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp))
        Text(keepNumbersWhole(subtitle(q)), style = soft.copy(fontSize = 11.sp))
        if (!q.enough) {
            Text(keepNumbersWhole(LiquidityEquity.waiting(q)), style = soft, modifier = Modifier.padding(top = 4.dp))
        } else {
            val tone = recordTone(q.check.verdict)
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the paper record") { open = true }) {
                EquityChart(q, tone, null, Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp)
                    .semantics { contentDescription = "Paper record chart: ${q.trades} trades against the backtest's expected path. Tap to open." })
                DrawdownStrip(q, Modifier.fillMaxWidth().height(18.dp).padding(top = 2.dp))
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidityEquity.stats(q).forEach { (label, value) -> RecordStat(label, keepNumbersWhole(value)) }
            }
            Text(keepNumbersWhole(LiquidityEquity.verdict(q)), style = soft.copy(color = tone), modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (open && q.enough) LiquidityEquityDialog(q) { open = false }
}

private fun subtitle(q: LiquidityEquity.Equity): String =
    "${q.expectation.name} · closed paper trades" + (q.expectation.since?.let { " since ${LiquidityEquity.date(it)}" } ?: "") +
        ", per lot after charges, against the backtest"

/** The verdict's colour: in line / above in green, below in red, too few in the soft ink. */
@Composable
private fun recordTone(v: ForwardCheck.Verdict): Color {
    val p = LocalPalette.current
    return when (v) {
        ForwardCheck.Verdict.IN_LINE, ForwardCheck.Verdict.ABOVE -> p.verdigris
        ForwardCheck.Verdict.BELOW -> p.oxblood
        ForwardCheck.Verdict.TOO_FEW -> p.inkSoft
    }
}

@Composable
private fun RecordStat(label: String, value: String) {
    val p = LocalPalette.current
    Column {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp), maxLines = 1)
        Text(value, style = Type.figure.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** The larger view: the chart with a point a trade (a tap picks the nearest), the drawdown, the figures and every trade. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiquidityEquityDialog(q: LiquidityEquity.Equity, onClose: () -> Unit) {
    val p = LocalPalette.current
    var picked by remember(q) { mutableStateOf(q.trades) }
    val tone = recordTone(q.check.verdict)
    val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
    val small = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Paper record", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text(keepNumbersWhole(subtitle(q)), style = soft)
                EquityChart(q, tone, picked, Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)
                    .pointerInput(q) {
                        detectTapGestures { o -> picked = LiquidityEquity.nearest(q.trades, (o.x / size.width.coerceAtLeast(1)).toDouble()) }
                    }
                    .semantics { contentDescription = "Paper record, larger: ${q.trades} trades, one point each. Tap a point for its trade." })
                DrawdownStrip(q, Modifier.fillMaxWidth().height(48.dp).padding(top = 2.dp))
                Text("Solid: the paper record · dashed: the backtest's expected path · shaded: its band · red: the drawdown",
                    style = soft.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
                q.points.getOrNull(picked - 1)?.let { pt ->
                    Text(keepNumbersWhole(LiquidityEquity.pointLine(pt)), style = small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    Text(keepNumbersWhole(LiquidityEquity.expectedLine(q, pt.n)), style = soft)
                }
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidityEquity.stats(q).forEach { (label, value) -> RecordStat(label, keepNumbersWhole(value)) }
                }
                Text(keepNumbersWhole(LiquidityEquity.verdict(q)), style = soft.copy(color = tone), modifier = Modifier.padding(top = 4.dp))
                Text("Every trade, newest first", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    modifier = Modifier.padding(top = 12.dp))
                q.points.asReversed().forEach { pt ->
                    val on = pt.n == picked
                    Text(keepNumbersWhole(LiquidityEquity.pointLine(pt)),
                        style = small.copy(color = if (on) tone else p.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Show this trade on the chart") { picked = pt.n }
                            .padding(vertical = 4.dp))
                }
                Text("A read of the closed paper trades: it places, closes and arms nothing.", style = soft, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

/**
 * The running net (solid, [tone]) over the expected path (dashed) and its band (shaded), with the zero line. [picked]
 * (the larger view): a point a trade, the picked one ringed; null (the card): the last point only.
 */
@Composable
private fun EquityChart(q: LiquidityEquity.Equity, tone: Color, picked: Int?, modifier: Modifier) {
    val p = LocalPalette.current
    val band = p.rule.copy(alpha = 0.45f)
    val mid = p.inkFaint
    val n = q.trades
    val totals = q.totals
    val (lo, hi) = LiquidityEquity.range(q)
    Canvas(modifier) {
        if (n < 1) return@Canvas
        val h = hi - lo
        fun x(i: Int) = 4f + i.toFloat() / n * (size.width - 8f)
        fun y(v: Double) = (size.height - 4f) - ((v - lo) / h).toFloat() * (size.height - 8f)
        val area = Path().apply {
            q.band.forEach { b -> if (b.n == 0) moveTo(x(0), y(b.high)) else lineTo(x(b.n), y(b.high)) }
            for (b in q.band.asReversed()) lineTo(x(b.n), y(b.low))
            close()
        }
        drawPath(area, band)
        drawLine(mid.copy(alpha = 0.5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        val expected = Path().apply { q.band.forEach { b -> if (b.n == 0) moveTo(x(0), y(b.expected)) else lineTo(x(b.n), y(b.expected)) } }
        drawPath(expected, mid, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        val line = Path().apply { totals.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        drawPath(line, tone, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (picked == null) {
            drawCircle(tone, 4.5f, Offset(x(n), y(totals[n])))
        } else {
            for (i in 1..n) drawCircle(tone, 3.5f, Offset(x(i), y(totals[i])))
            if (picked in 1..n) {
                drawCircle(tone, 9f, Offset(x(picked), y(totals[picked])), style = Stroke(width = 2.5f))
            }
        }
    }
}

/** The running drawdown under the chart: 0 at the top, the deepest at the bottom, a small red area. */
@Composable
private fun DrawdownStrip(q: LiquidityEquity.Equity, modifier: Modifier) {
    val p = LocalPalette.current
    val n = q.trades
    val dd = q.drawdowns
    val floor = LiquidityEquity.floor(q)
    Canvas(modifier) {
        if (n < 1) return@Canvas
        fun x(i: Int) = 4f + i.toFloat() / n * (size.width - 8f)
        fun y(v: Double) = (v / floor).toFloat().coerceIn(0f, 1f) * size.height
        drawLine(p.rule, Offset(0f, 0f), Offset(size.width, 0f), 1f)
        val area = Path().apply {
            moveTo(x(0), 0f)
            dd.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
            lineTo(x(n), 0f)
            close()
        }
        drawPath(area, p.oxblood.copy(alpha = 0.22f))
        val edge = Path().apply { dd.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        drawPath(edge, p.oxblood.copy(alpha = 0.7f), style = Stroke(width = 1.5f))
    }
}
