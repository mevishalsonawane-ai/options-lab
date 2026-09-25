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
    val labelStyle = remember(p) { TextStyle(color = p.inkSoft, fontSize = 10.sp, fontFeatureSettings = "tnum") }
    val up = values.isEmpty() || values.last() >= (reference ?: values.first())
    val line = if (up) p.verdigris else p.oxblood
    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        if (values.size < 2) return@Canvas
        val right = 48.dp.toPx(); val bottom = 18.dp.toPx(); val top = 8.dp.toPx()
        val w = size.width - right; val h = size.height - bottom - top
        val all = if (reference != null) values + reference else values
        var lo = all.min(); var hi = all.max()
        val pad = ((hi - lo) * 0.08).coerceAtLeast(1.0); lo -= pad; hi += pad
        // Round grid steps: 1, 2 or 5 times a power of ten, about four lines.
        val raw = (hi - lo) / 4
        val mag = Math.pow(10.0, floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
        fun y(v: Double) = (top + h * (1 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = (w * i / (slots - 1).coerceAtLeast(1)).toFloat()
        var t = ceil(lo / step) * step
        while (t <= hi) {
            drawLine(p.rule, Offset(0f, y(t)), Offset(w, y(t)), 1f)
            val txt = measurer.measure(String.format(Locale.ENGLISH, if (step < 1) "%,.2f" else "%,.0f", t), labelStyle)
            drawText(txt, topLeft = Offset(w + 6.dp.toPx(), y(t) - txt.size.height / 2f))
            t += step
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
        xLabels.forEach { (i, s) ->
            val txt = measurer.measure(s, labelStyle)
            val cx = x(i.coerceIn(0, slots - 1)) - txt.size.width / 2f
            drawText(txt, topLeft = Offset(cx.coerceIn(0f, w - txt.size.width), size.height - txt.size.height))
        }
    }
}
