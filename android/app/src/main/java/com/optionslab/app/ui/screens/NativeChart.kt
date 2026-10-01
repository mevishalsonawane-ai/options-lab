package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.Market
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.components.axisFontSize
import com.optionslab.app.ui.components.measureLine
import com.optionslab.app.ui.components.nonOverlapping
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Upstox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A candlestick chart drawn by the app itself (no WebView): intervals, the last
 * sessions (the last one the market was open when it is closed), drag to scroll,
 * tap for a candle's OHLC, the last price marked, refreshed every 15 s while open.
 * Used when the advanced (web) chart cannot draw on this phone. [feed] gives the candles for (symbol,
 * interval): [ChartFeed] in the app, a fake in tests.
 */
@Composable
fun NativeChart(
    symbol: String, modifier: Modifier = Modifier, visible: Boolean = true,
    open: () -> Boolean = { Market.isOpen() },
    feed: suspend (symbol: String, interval: String) -> List<Upstox.Bar> = { s, iv -> ChartFeed.bars(s, iv, null, null) },
) {
    val p = LocalPalette.current
    var interval by remember { mutableStateOf("5m") }
    var bars by remember(symbol) { mutableStateOf<List<Upstox.Bar>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var offset by remember(symbol, interval) { mutableFloatStateOf(0f) }        // candles scrolled back from the newest
    var picked by remember(symbol, interval) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Axis text grows with the font only so far (the gutter and the time axis have fixed room); tabular digits.
    val axisStyle = remember(p, density.fontScale) {
        TextStyle(color = p.inkSoft, fontSize = axisFontSize(10.sp, density.fontScale), fontFeatureSettings = "tnum")
    }

    // Polls only while shown and the app is in front: a hidden or pocketed chart fetches nothing.
    com.optionslab.app.ui.PollWhileStarted(symbol, interval, visible) {
        if (!visible) return@PollWhileStarted
        if (bars.isEmpty()) loading = true
        error = null
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { feed(symbol, interval) } }
            r.onSuccess { bars = it; error = if (it.isEmpty()) "No candles for $symbol $interval yet." else null }
                .onFailure { error = "Could not load $symbol: ${it.message ?: "no data"}" }
            loading = false
            delay(if (open()) 15_000 else 300_000)
        }
    }

    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("1m", "5m", "15m", "1h", "1D").forEach { iv -> Token(iv, iv == interval) { interval = iv } }
        }
        val shown = bars
        // The price gutter fits the widest label it will show (grid prices and the last-price tag), on one line:
        // a fixed 64 dp wrapped "130.00" into "130.0 / 0" at a large font.
        val gutter = remember(shown, axisStyle, density) {
            if (shown.isEmpty()) 0f else {
                val hi = shown.maxOf { it.high }; val lo = shown.minOf { it.low }; val span = max(hi - lo, 2.0)
                val widest = listOf(priceLabel(hi + span * 0.1), priceLabel(max(0.0, lo - span * 0.1)), lastLabel(shown.last().close), lastLabel(hi))
                    .maxOf { measurer.measureLine(it, axisStyle).size.width }
                with(density) { widest + (GAP + 2 * TAG_PAD + 2).dp.toPx() }
            }
        }
        val last = shown.lastOrNull()
        val head = picked?.let { shown.getOrNull(it) } ?: last
        if (head != null) {
            val fmt = { x: Double -> String.format(Locale.ENGLISH, "%,.2f", x) }
            val t = Instant.ofEpochSecond(head.epochSecond).atZone(com.optionslab.engine.IST)
                .format(DateTimeFormatter.ofPattern(if (interval == "1D") "d MMM yyyy" else "d MMM HH:mm", Locale.ENGLISH))
            val up = head.close >= head.open
            Text("$t   O ${fmt(head.open)}  H ${fmt(head.high)}  L ${fmt(head.low)}  C ${fmt(head.close)}",
                style = Type.figure.copy(fontSize = 11.sp, color = if (up) p.verdigris else p.oxblood), modifier = Modifier.padding(horizontal = 12.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && shown.isEmpty() -> Text("Loading chart…", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.align(Alignment.Center))
                shown.isEmpty() -> Text(error ?: "No candles.", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.align(Alignment.Center).padding(20.dp))
                else -> {
                    val grid = p.rule; val ink = p.inkSoft; val upC = p.verdigris; val dnC = p.oxblood; val lastC = p.gold; val tagInk = p.paper
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(shown.size, interval) {
                            detectHorizontalDragGestures { _, drag ->
                                val w = 9.dp.toPx()
                                offset = (offset + drag / w).coerceIn(0f, max(0f, (shown.size - 20).toFloat()))
                            }
                        }
                        .pointerInput(shown.size, interval, offset, gutter) {
                            detectTapGestures { pos ->
                                val w = 9.dp.toPx(); val axis = gutter
                                val count = ((size.width - axis) / w).toInt()
                                val end = shown.size - offset.toInt()
                                val start = max(0, end - count)
                                val i = start + (pos.x / w).toInt()
                                picked = if (i in start until end) i else null
                            }
                        }
                    ) {
                        val axisW = gutter; val gap = GAP.dp.toPx(); val w = 9.dp.toPx()
                        val timeProbe = measurer.measureLine("28 Sep", axisStyle)
                        val timeH = timeProbe.size.height + 4.dp.toPx()
                        val plotW = size.width - axisW; val plotH = size.height - timeH
                        val count = max(1, (plotW / w).toInt())
                        val end = (shown.size - offset.toInt()).coerceIn(1, shown.size)
                        val start = max(0, end - count)
                        val view = shown.subList(start, end)
                        var lo = view.minOf { it.low }; var hi = view.maxOf { it.high }
                        if (hi - lo < 1e-9) { hi += 1; lo -= 1 }
                        val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
                        // Half a label of room above the top grid line, so its price is not cut at the canvas edge.
                        val topPad = timeProbe.size.height / 2f
                        fun y(v: Double) = (topPad + (plotH - topPad) * (1 - (v - lo) / (hi - lo))).toFloat()
                        // The last-price tag's text and where it will sit (grid prices under it are left out).
                        val lp = shown.last().close
                        val tag = if (lp in lo..hi) measurer.measureLine(lastLabel(lp), axisStyle.copy(color = tagInk)) else null
                        val tagTop = tag?.let { (y(lp) - it.size.height / 2f).coerceIn(0f, max(0f, plotH - it.size.height)) }
                        // Price grid and axis labels, five steps, each on one line and inside the plot's height.
                        for (k in 0..4) {
                            val v = lo + (hi - lo) * k / 4
                            val yy = y(v)
                            drawLine(grid, Offset(0f, yy), Offset(plotW, yy), 1f)
                            val t = measurer.measureLine(priceLabel(v), axisStyle)
                            val top = (yy - t.size.height / 2f).coerceIn(0f, max(0f, plotH - t.size.height))
                            if (tag != null && tagTop != null && top < tagTop + tag.size.height && top + t.size.height > tagTop) continue
                            drawText(t, topLeft = Offset(plotW + gap, top))
                        }
                        // Candles.
                        view.forEachIndexed { i, b ->
                            val cx = i * w + w / 2
                            val c = if (b.close >= b.open) upC else dnC
                            drawLine(c, Offset(cx, y(b.high)), Offset(cx, y(b.low)), 1.2f)
                            val top = y(max(b.open, b.close)); val bot = y(min(b.open, b.close))
                            drawRect(c, Offset(cx - w * 0.35f, top), Size(w * 0.7f, max(1f, bot - top)))
                            if (start + i == picked) drawLine(ink, Offset(cx, 0f), Offset(cx, plotH), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        }
                        // Time labels: short ("09:45", "25 Sep" where the day changes), a tick every few candles so the
                        // widest label fits between two, anchored to the candle so they do not jump while scrolling,
                        // and any label that would still touch the last one drawn is left out.
                        val widest = max(timeProbe.size.width, measurer.measureLine("00:00", axisStyle).size.width)
                        val every = timeTickEvery(widest.toFloat(), w, 10.dp.toPx())
                        val ticks = view.indices.filter { (start + it) % every == 0 }
                        val texts = timeTickLabels(ticks.map { view[it].epochSecond }, interval == "1D").map { measurer.measureLine(it, axisStyle) }
                        val spans = ticks.mapIndexed { j, i ->
                            val l = (i * w + w / 2 - texts[j].size.width / 2f).coerceIn(0f, max(0f, size.width - texts[j].size.width))
                            l to l + texts[j].size.width
                        }
                        for (j in nonOverlapping(spans, 6.dp.toPx())) drawText(texts[j], topLeft = Offset(spans[j].first, plotH + 2.dp.toPx()))
                        // Last price line and a filled tag in the gutter, kept inside the canvas.
                        if (tag != null && tagTop != null) {
                            val yy = y(lp)
                            drawLine(lastC, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                            val tp = TAG_PAD.dp.toPx()
                            val left = min(plotW + gap, size.width - tag.size.width - tp)
                            drawRect(lastC, Offset(left - tp, tagTop), Size(tag.size.width + 2 * tp, tag.size.height.toFloat()))
                            drawText(tag, topLeft = Offset(left, tagTop))
                        }
                    }
                }
            }
        }
        Text(if (open()) "Basic chart · refreshes every 15 s · drag to scroll, tap a candle" else "Basic chart · market closed: last sessions · drag to scroll, tap a candle",
            style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 10.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

private const val GAP = 4        // dp between the plot and the price labels
private const val TAG_PAD = 3    // dp either side of the last-price tag's text

private fun priceLabel(v: Double) = String.format(Locale.ENGLISH, "%,.1f", v)
private fun lastLabel(v: Double) = String.format(Locale.ENGLISH, "%,.2f", v)

/** A time tick every this many candles of [candle] px, so a label [labelWidth] px wide plus [gap] fits between two. */
internal fun timeTickEvery(labelWidth: Float, candle: Float, gap: Float): Int =
    max(1, kotlin.math.ceil((labelWidth + gap) / candle).toInt())

/**
 * The time axis's labels for candles at [epochs] (IST): "d MMM" for daily candles; otherwise "HH:mm", with "d MMM"
 * where the day changes from the tick before (and on the first tick when the ticks span more than one day).
 */
internal fun timeTickLabels(epochs: List<Long>, daily: Boolean): List<String> {
    val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    val at = epochs.map { Instant.ofEpochSecond(it).atZone(com.optionslab.engine.IST) }
    val manyDays = at.map { it.toLocalDate() }.distinct().size > 1
    return at.mapIndexed { j, t ->
        val newDay = if (j == 0) manyDays else t.toLocalDate() != at[j - 1].toLocalDate()
        t.format(if (daily || newDay) day else hm)
    }
}
