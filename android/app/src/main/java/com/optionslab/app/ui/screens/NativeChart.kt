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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.Market
import com.optionslab.app.ui.components.Token
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
 * Used when the advanced (web) chart cannot draw on this phone.
 */
@Composable
fun NativeChart(symbol: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var interval by remember { mutableStateOf("5m") }
    var bars by remember(symbol) { mutableStateOf<List<Upstox.Bar>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var offset by remember(symbol, interval) { mutableFloatStateOf(0f) }        // candles scrolled back from the newest
    var picked by remember(symbol, interval) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()

    LaunchedEffect(symbol, interval) {
        loading = true; error = null
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { ChartFeed.bars(symbol, interval, null, null) } }
            r.onSuccess { bars = it; error = if (it.isEmpty()) "No candles for $symbol $interval yet." else null }
                .onFailure { error = "Could not load $symbol: ${it.message ?: "no data"}" }
            loading = false
            delay(if (Market.isOpen()) 15_000 else 300_000)
        }
    }

    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("1m", "5m", "15m", "1h", "1D").forEach { iv -> Token(iv, iv == interval) { interval = iv } }
        }
        val shown = bars
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
                    val grid = p.rule; val ink = p.inkSoft; val upC = p.verdigris; val dnC = p.oxblood; val lastC = p.gold
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(shown.size, interval) {
                            detectHorizontalDragGestures { _, drag ->
                                val w = 9.dp.toPx()
                                offset = (offset + drag / w).coerceIn(0f, max(0f, (shown.size - 20).toFloat()))
                            }
                        }
                        .pointerInput(shown.size, interval, offset) {
                            detectTapGestures { pos ->
                                val w = 9.dp.toPx(); val axis = 64.dp.toPx()
                                val count = ((size.width - axis) / w).toInt()
                                val end = shown.size - offset.toInt()
                                val start = max(0, end - count)
                                val i = start + (pos.x / w).toInt()
                                picked = if (i in start until end) i else null
                            }
                        }
                    ) {
                        val axisW = 64.dp.toPx(); val timeH = 18.dp.toPx(); val w = 9.dp.toPx()
                        val plotW = size.width - axisW; val plotH = size.height - timeH
                        val count = max(1, (plotW / w).toInt())
                        val end = (shown.size - offset.toInt()).coerceIn(1, shown.size)
                        val start = max(0, end - count)
                        val view = shown.subList(start, end)
                        var lo = view.minOf { it.low }; var hi = view.maxOf { it.high }
                        if (hi - lo < 1e-9) { hi += 1; lo -= 1 }
                        val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
                        fun y(v: Double) = (plotH * (1 - (v - lo) / (hi - lo))).toFloat()
                        val label = TextStyle(color = ink, fontSize = 10.sp)
                        // Price grid and axis labels, five steps.
                        for (k in 0..4) {
                            val v = lo + (hi - lo) * k / 4
                            val yy = y(v)
                            drawLine(grid, Offset(0f, yy), Offset(plotW, yy), 1f)
                            drawText(measurer, String.format(Locale.ENGLISH, "%,.1f", v), Offset(plotW + 4.dp.toPx(), min(plotH - 12.dp.toPx(), max(0f, yy - 7.dp.toPx()))), label)
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
                        // Time labels: about four across.
                        val step = max(1, view.size / 4)
                        val tf = DateTimeFormatter.ofPattern(if (interval == "1D") "d MMM" else "d MMM HH:mm", Locale.ENGLISH)
                        for (i in view.indices step step) {
                            val s = Instant.ofEpochSecond(view[i].epochSecond).atZone(com.optionslab.engine.IST).format(tf)
                            drawText(measurer, s, Offset(i * w, plotH + 3.dp.toPx()), label)
                        }
                        // Last price line and tag.
                        val lp = shown.last().close
                        if (lp in lo..hi) {
                            val yy = y(lp)
                            drawLine(lastC, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                            drawText(measurer, String.format(Locale.ENGLISH, "%,.2f", lp), Offset(plotW + 4.dp.toPx(), max(0f, yy - 7.dp.toPx())),
                                TextStyle(color = lastC, fontSize = 10.sp))
                        }
                    }
                }
            }
        }
        Text(if (Market.isOpen()) "Basic chart · refreshes every 15 s · drag to scroll, tap a candle" else "Basic chart · market closed: last sessions · drag to scroll, tap a candle",
            style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 10.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
