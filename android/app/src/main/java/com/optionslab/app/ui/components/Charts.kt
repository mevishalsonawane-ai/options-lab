package com.optionslab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.max

/** One line of a [LinePlot]; nulls leave a gap. */
data class PlotLine(val ys: List<Double?>, val color: Color, val dashed: Boolean = false, val width: Float = 2f)

/**
 * Lines over a shared x axis, inked in as the curve draws. [markX] draws a
 * brass rule (spot, ATM); the zero line is dashed when it is in range.
 */
@Composable
fun LinePlot(xs: List<Double>, lines: List<PlotLine>, modifier: Modifier = Modifier, markX: Double? = null, height: Dp = 180.dp,
             fillSign: Boolean = false) {
    val p = LocalPalette.current
    val draw = remember(xs, lines) { Animatable(0f) }
    LaunchedEffect(xs, lines) { draw.snapTo(0f); draw.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (xs.size < 2) return@Canvas
        val all = lines.flatMap { it.ys }.filterNotNull().filter { it.isFinite() }
        if (all.isEmpty()) return@Canvas
        var lo = all.min(); var hi = all.max()
        if (fillSign) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val x0 = xs.first(); val xSpan = (xs.last() - x0).takeIf { it != 0.0 } ?: 1.0
        fun x(v: Double) = (size.width * (v - x0) / xSpan).toFloat()
        fun y(v: Double) = (6f + (size.height - 12f) * (1 - (v - lo) / span)).toFloat()
        if (lo <= 0 && hi >= 0) drawLine(p.rule, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        markX?.let { if (it in xs.first()..xs.last()) drawLine(p.brass, Offset(x(it), 0f), Offset(x(it), size.height), 1.5f) }
        val limit = (xs.size * draw.value).toInt().coerceAtLeast(2)
        for (line in lines) {
            if (fillSign && !line.dashed) {
                for (i in 1 until minOf(limit, xs.size)) {
                    val a = line.ys[i - 1] ?: continue; val b = line.ys[i] ?: continue
                    val mid = (a + b) / 2
                    val col = (if (mid >= 0) p.verdigris else p.oxblood).copy(alpha = 0.14f)
                    val xa = x(xs[i - 1]); val xb = x(xs[i])
                    val path = Path().apply { moveTo(xa, y(0.0)); lineTo(xa, y(a)); lineTo(xb, y(b)); lineTo(xb, y(0.0)); close() }
                    drawPath(path, col)
                }
            }
            val path = Path()
            var pen = false
            for (i in 0 until minOf(limit, xs.size)) {
                val v = line.ys.getOrNull(i)
                if (v == null || !v.isFinite()) { pen = false; continue }
                if (!pen) path.moveTo(x(xs[i]), y(v)) else path.lineTo(x(xs[i]), y(v))
                pen = true
            }
            drawPath(path, line.color, style = Stroke(line.width.dp.toPx(),
                pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        }
    }
}

/**
 * Strike ladder bars: calls rise above the axis, puts hang below, so the
 * walls of open interest read at a glance. [markX] is spot.
 */
@Composable
fun ButterflyBars(xs: List<Double>, up: List<Double>, down: List<Double>, upColor: Color, downColor: Color,
                  modifier: Modifier = Modifier, markX: Double? = null, height: Dp = 180.dp) {
    val p = LocalPalette.current
    val grow = remember(xs) { Animatable(0f) }
    LaunchedEffect(xs) { grow.snapTo(0f); grow.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (xs.isEmpty()) return@Canvas
        val m = max(up.maxOrNull() ?: 0.0, down.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
        val slot = size.width / xs.size
        val mid = size.height / 2
        drawLine(p.rule, Offset(0f, mid), Offset(size.width, mid), 1f)
        xs.indices.forEach { i ->
            val left = i * slot + slot * 0.18f
            val w = slot * 0.64f
            val hu = (mid - 4) * (up.getOrElse(i) { 0.0 } / m).toFloat() * grow.value
            val hd = (mid - 4) * (down.getOrElse(i) { 0.0 } / m).toFloat() * grow.value
            drawRect(upColor, Offset(left, mid - hu), Size(w, hu))
            drawRect(downColor, Offset(left, mid), Size(w, hd))
        }
        markX?.let { s ->
            val i = xs.indexOfFirst { it >= s }
            if (i >= 0) drawLine(p.brass, Offset(i * slot, 0f), Offset(i * slot, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
}

/** Signed bars around zero (net GEX per strike). */
@Composable
fun SignedBars(xs: List<Double>, values: List<Double>, modifier: Modifier = Modifier, markX: Double? = null, height: Dp = 160.dp) {
    val p = LocalPalette.current
    val grow = remember(xs, values) { Animatable(0f) }
    LaunchedEffect(xs, values) { grow.snapTo(0f); grow.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (xs.isEmpty()) return@Canvas
        val m = values.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        val slot = size.width / xs.size
        val mid = size.height / 2
        drawLine(p.rule, Offset(0f, mid), Offset(size.width, mid), 1f)
        values.forEachIndexed { i, v ->
            val h = ((mid - 4) * abs(v) / m).toFloat() * grow.value
            val left = i * slot + slot * 0.18f
            drawRect(if (v >= 0) p.verdigris else p.oxblood, if (v >= 0) Offset(left, mid - h) else Offset(left, mid), Size(slot * 0.64f, h))
        }
        markX?.let { s ->
            val i = xs.indexOfFirst { it >= s }
            if (i >= 0) drawLine(p.brass, Offset(i * slot, 0f), Offset(i * slot, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
}
