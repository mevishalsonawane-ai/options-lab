package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.SoloProgress
import com.optionslab.ira.TodayGlance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Solo's card shows at a glance: the forward test ([glance]) and [today]'s state in one line. */
internal data class SoloGlanceRead(val glance: SoloProgress.Glance, val today: String)

/**
 * Reads Solo (midday)'s forward test and today's state from its own records ([com.optionslab.app.ira.IraSolo]'s public
 * reads), with its switch [on] as the card shows it. Slow (the vault): call it off the main thread. Reads only.
 */
internal fun readSoloGlance(on: Boolean): SoloGlanceRead {
    val solo = com.optionslab.app.ira.IraSolo
    val mine = solo.midday(solo.all())
    val nets = mine.filter { it.closed && it.net != null }.map { it.net!! }
    val glance = SoloProgress.of(nets, solo.baseline(), on, solo.paused)
    val now = com.optionslab.app.data.Market.now().toLocalDateTime()
    val day = now.toLocalDate()
    val trading = runCatching { com.optionslab.app.data.Market.isTradingDay(day) }.getOrDefault(true)
    val t = mine.lastOrNull { it.day == day.toString() }
    val today = SoloProgress.today(now, trading, on, solo.dayRecord(day), t?.let { TodayGlance.SoloTrade(it.symbol, !it.closed, it.net) },
        solo.dayRecordSince)
    return SoloGlanceRead(glance, today)
}

/**
 * Solo (midday)'s forward test at a glance on its card: read by [load] on Dispatchers.IO once, and again when [refresh]
 * changes (its switch). Nothing until it is read, nothing when it cannot be read. Each read is handed to [onRead] (null:
 * not read), so the card leaves out its own copies of the glance's lines only once the glance shows them. Reads only:
 * the card's switch is untouched. Never in the GOLD build (Solo is not in it).
 */
@Composable
internal fun SoloForwardGlance(refresh: Any?, onRead: (SoloGlanceRead?) -> Unit = {}, load: suspend () -> SoloGlanceRead?) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    var read by remember { mutableStateOf<SoloGlanceRead?>(null) }
    LaunchedEffect(refresh) {
        val r = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
        read = r
        onRead(r)
    }
    read?.let { SoloGlanceContent(it) }
}

/**
 * The glance: "N of 60 forward-test trades" with its bar, the running net a trade (a sparkline, from 2 trades), the net so
 * far after charges (total and per lot) and the win rate, the drawdown against the −₹25,000 switch-off line with a second
 * bar (how far along to it), the switch-off state, today's line and the research line.
 */
@Composable
internal fun SoloGlanceContent(r: SoloGlanceRead) {
    val p = LocalPalette.current
    val g = r.glance
    val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
    val ink = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
    val tone = if (g.net < 0) p.oxblood else p.verdigris
    val danger = g.verdict == SoloMidday.Verdict.FAILED_DRAWDOWN || g.toLine >= 0.8f
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(p.chip, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("FORWARD TEST", style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp))
        Text(keepNumbersWhole(g.progressLine), style = ink.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp))
        GlanceBar(g.progress, p.brass, "Forward test: ${g.progressLine}")
        g.spark?.let { s ->
            Sparkline(s, tone, Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp)
                .semantics { contentDescription = "Solo's running net a trade: ${g.trades} trades, net ${SoloProgress.signed(g.net)}" })
        }
        Text(keepNumbersWhole(g.netLine), style = ink, modifier = Modifier.padding(top = 6.dp))
        g.verdictLine?.let { Text(keepNumbersWhole(it), style = ink.copy(color = if (g.verdict == SoloMidday.Verdict.PASSED) p.verdigris else p.oxblood)) }
        Text(keepNumbersWhole(g.drawdownLine), style = ink.copy(color = if (danger) p.oxblood else p.ink), modifier = Modifier.padding(top = 8.dp))
        GlanceBar(g.toLine, if (danger) p.oxblood else p.amber, "Drawdown against the switch-off line: ${g.drawdownLine}")
        g.offLine?.let { Text(keepNumbersWhole(it), style = ink.copy(color = p.oxblood, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp)) }
        Text(keepNumbersWhole(r.today), style = ink.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
        Text(keepNumbersWhole(g.research), style = soft, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A thin bar filled to [fraction] (0..1) in [color], with its words for TalkBack. */
@Composable
private fun GlanceBar(fraction: Float, color: Color, words: String) {
    val p = LocalPalette.current
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).background(p.rule.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
        .semantics { contentDescription = words; progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) }) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
    }
}

/** The running net a trade ([SoloProgress.spark]'s points), the zero line faint, the last point marked. */
@Composable
private fun Sparkline(s: SoloProgress.Spark, tone: Color, modifier: Modifier) {
    val p = LocalPalette.current
    val zero = p.inkFaint.copy(alpha = 0.5f)
    Canvas(modifier) {
        if (s.points.size < 2) return@Canvas
        val pad = 4f
        fun x(v: Float) = pad + v * (size.width - 2 * pad)
        fun y(v: Float) = (size.height - pad) - v * (size.height - 2 * pad)
        drawLine(zero, Offset(0f, y(s.zero)), Offset(size.width, y(s.zero)), 1f)
        val line = Path().apply { s.points.forEachIndexed { i, pt -> if (i == 0) moveTo(x(pt.x), y(pt.y)) else lineTo(x(pt.x), y(pt.y)) } }
        drawPath(line, tone, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = s.points.last()
        drawCircle(tone, 4f, Offset(x(last.x), y(last.y)))
    }
}
