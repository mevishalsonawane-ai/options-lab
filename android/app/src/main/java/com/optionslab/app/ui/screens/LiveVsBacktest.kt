package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ForwardRecords
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.ForwardCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.round

/** The P&L tab's "Live vs backtest" card, read from the strategies' own records ([ForwardRecords]) off the main thread. */
@Composable
internal fun LiveVsBacktest(tick: Int) {
    val rows by produceState<List<ForwardRecords.Row>?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching { ForwardRecords.rows() }.getOrDefault(emptyList()) }
    }
    rows?.let { LiveVsBacktestCard(it) }
}

/**
 * Live vs backtest ([ForwardCheck]), one block a strategy: its name and verdict in plain words, a sparkline of the forward
 * running net against the research's expected line and band, the numbers, and a sentence saying what they mean.
 */
@Composable
internal fun LiveVsBacktestCard(rows: List<ForwardRecords.Row>) {
    val p = LocalPalette.current
    LedgerCard {
        Text("LIVE VS BACKTEST", style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp))
        if (rows.isEmpty()) { Note("No running strategy to compare yet.", Modifier.padding(top = 6.dp)); return@LedgerCard }
        rows.forEach { ForwardBlock(it) }
        Note("Forward paper trades since each strategy's current rules (Liquidity per lot), against the research's TEST period. " +
            "Liquidity per lot: a trade of several lots counts its net over its lots, and the per-order brokerage it paid is shared " +
            "among them - such a lot pays a little less in charges than a one-lot trade (the research's). " +
            "The band is the backtest's mean ± 2 spreads / √trades; a run of trades well below it is flagged early.", Modifier.padding(top = 10.dp))
    }
}

/** The verdict's colour: in line / above in green, below in red, too few in the soft ink. */
@Composable
private fun toneOf(v: ForwardCheck.Verdict): Color {
    val p = LocalPalette.current
    return when (v) {
        ForwardCheck.Verdict.IN_LINE, ForwardCheck.Verdict.ABOVE -> p.verdigris
        ForwardCheck.Verdict.BELOW -> p.oxblood
        ForwardCheck.Verdict.TOO_FEW -> p.inkSoft
    }
}

private fun rs(x: Double, sign: Boolean = false): String =
    (if (x < 0) "−₹" else if (sign && x > 0) "+₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(round(x)))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForwardBlock(row: ForwardRecords.Row) {
    val p = LocalPalette.current
    val r = row.result
    val e = r.expectation
    val tone = toneOf(r.verdict)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.title, style = Type.body.copy(color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            Text(ForwardCheck.words(r).replaceFirstChar { it.uppercase() }, style = Type.body.copy(color = tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
        if (row.shadow) Text("Shadow · no orders", style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
        if (e.lottery) Text("Lottery: judge by drawdown, not mean", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
        Spark(r, tone, Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp)
            .semantics { contentDescription = "${row.title}: forward running net against the backtest's expected line and band" })
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Trades", "${r.trades}")
            Stat("Net", rs(r.net, sign = true))
            Stat("A trade", r.perTrade?.let { rs(it) } ?: "—")
            Stat("Win", r.winRate?.let { "${round(100 * it).toInt()}%" } ?: "—")
            Stat("Drawdown", rs(r.drawdown))
            Stat("Expected", rs(e.mean) + (r.band?.let { " ± " + rs(it.half) } ?: ""))
        }
        Text(keepNumbersWhole(ForwardCheck.plain(r)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val p = LocalPalette.current
    Column {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp), maxLines = 1)
        Text(value, style = Type.figure.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** The forward running net (solid, in the verdict's colour) over the expected line (dashed) and its band (shaded). */
@Composable
private fun Spark(r: ForwardCheck.Result, tone: Color, modifier: Modifier) {
    val p = LocalPalette.current
    val n = r.cumulative.size - 1
    // With no trade yet, the expectation over the next MIN_TRADES trades is drawn alone.
    val span = maxOf(n, ForwardCheck.MIN_TRADES)
    val exp = ForwardCheck.expectedPath(r.expectation, span)
    val band = p.rule.copy(alpha = 0.45f)
    val mid = p.inkFaint
    Canvas(modifier) {
        val ys = exp.flatMap { listOf(it.first, it.third) } + r.cumulative
        val lo = minOf(0.0, ys.min()); val hi = maxOf(0.0, ys.max()); val h = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = 2f + i.toFloat() / span * (size.width - 4f)
        fun y(v: Double) = (size.height - 2f) - ((v - lo) / h).toFloat() * (size.height - 4f)
        val area = Path().apply {
            exp.forEachIndexed { i, t -> if (i == 0) moveTo(x(i), y(t.third)) else lineTo(x(i), y(t.third)) }
            for (i in exp.indices.reversed()) lineTo(x(i), y(exp[i].first))
            close()
        }
        drawPath(area, band)
        drawLine(mid.copy(alpha = 0.5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        val expected = Path().apply { exp.forEachIndexed { i, t -> if (i == 0) moveTo(x(i), y(t.second)) else lineTo(x(i), y(t.second)) } }
        drawPath(expected, mid, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        if (n >= 1) {
            val line = Path().apply { r.cumulative.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            drawPath(line, tone, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(tone, 4.5f, Offset(x(n), y(r.cumulative.last())))
        }
    }
}
