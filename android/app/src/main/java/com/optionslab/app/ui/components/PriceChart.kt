package com.optionslab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A price line with a soft area fill, a light grid with prices on the right,
 * an optional dashed reference (the previous close) and a dot at the latest
 * price. [slots] is how many points a full range holds (375 minutes for a
 * day), so a day in progress fills only part of the width. [xLabels] are
 * (slot index, text) pairs along the bottom.
 */
@Composable
fun PriceChart(
    values: List<Double>,
    reference: Double?,
    slots: Int,
    xLabels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    // Axis labels grow with the font only so far (the chart has fixed room), measured on one line.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val labelStyle = remember(p, fontScale) { TextStyle(color = p.inkSoft, fontSize = axisFontSize(10.sp, fontScale), fontFeatureSettings = "tnum") }
    val up = values.isEmpty() || values.last() >= (reference ?: values.first())
    val line = if (up) p.verdigris else p.oxblood
    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        if (values.size < 2) return@Canvas
        val all = if (reference != null) values + reference else values
        var lo = all.min(); var hi = all.max()
        val pad = ((hi - lo) * 0.08).coerceAtLeast(1.0); lo -= pad; hi += pad
        // Round grid steps: 1, 2 or 5 times a power of ten, about four lines.
        val raw = (hi - lo) / 4
        val mag = Math.pow(10.0, floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val grid = generateSequence(ceil(lo / step) * step) { it + step }.takeWhile { it <= hi }.toList()
        val gridText = grid.map { measurer.measureLine(String.format(Locale.ENGLISH, if (step < 1) "%,.2f" else "%,.0f", it), labelStyle) }
        val xText = xLabels.map { (i, s) -> i to measurer.measureLine(s, labelStyle) }
        // The price gutter is as wide as the widest price (it was a fixed 48 dp: "52,200" ran off the edge).
        val gap = 6.dp.toPx()
        val right = (gridText.maxOfOrNull { it.size.width } ?: 0) + gap + 2.dp.toPx()
        val bottom = (xText.maxOfOrNull { it.second.size.height } ?: 0) + 4.dp.toPx(); val top = 8.dp.toPx()
        val w = size.width - right; val h = size.height - bottom - top
        fun y(v: Double) = (top + h * (1 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = (w * i / (slots - 1).coerceAtLeast(1)).toFloat()
        grid.forEachIndexed { k, t ->
            drawLine(p.rule, Offset(0f, y(t)), Offset(w, y(t)), 1f)
            val txt = gridText[k]
            drawText(txt, topLeft = Offset(w + gap, (y(t) - txt.size.height / 2f).coerceIn(0f, (size.height - bottom - txt.size.height).coerceAtLeast(0f))))
        }
        if (reference != null) drawLine(p.inkSoft, Offset(0f, y(reference)), Offset(w, y(reference)), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply { addPath(path); lineTo(x(values.lastIndex), top + h); lineTo(0f, top + h); close() }
        drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f)), startY = top, endY = top + h))
        drawPath(path, line, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = Offset(x(values.lastIndex), y(values.last()))
        drawCircle(line.copy(alpha = 0.18f), 7.dp.toPx(), end)
        drawCircle(line, 3.5.dp.toPx(), end)
        // Time labels: centred on their slot, kept inside the chart, and one that would touch the last one drawn is left out.
        val spans = xText.map { (i, txt) ->
            val l = (x(i.coerceIn(0, slots - 1)) - txt.size.width / 2f).coerceIn(0f, (size.width - txt.size.width).coerceAtLeast(0f))
            l to l + txt.size.width
        }
        for (k in nonOverlapping(spans, 4.dp.toPx())) drawText(xText[k].second, topLeft = Offset(spans[k].first, size.height - xText[k].second.size.height))
    }
}
