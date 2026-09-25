package com.optionslab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Aged paper: a warm vignette, foxing spots and a faint ledger ruling. */
@Composable
fun Parchment(modifier: Modifier = Modifier, ruled: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    val specks = remember { List(140) { Triple(Random(it * 31 + 7).nextFloat(), Random(it * 17 + 3).nextFloat(), Random(it * 13 + 1).nextFloat()) } }
    Box(
        modifier
            .fillMaxSize()
            .background(p.paper)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.Transparent, p.paperDeep.copy(alpha = if (p.dark) 0.9f else 0.75f)),
                    center = center, radius = max(size.width, size.height) * 0.75f))
                for ((x, y, r) in specks) {
                    drawCircle(p.inkFaint.copy(alpha = 0.05f + 0.06f * r), radius = 0.6.dp.toPx() + r * 2.2.dp.toPx(), center = Offset(x * size.width, y * size.height))
                }
                if (ruled) {
                    val step = 28.dp.toPx()
                    var y = step * 3
                    while (y < size.height) {
                        drawLine(p.rule.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 0.6f)
                        y += step
                    }
                }
            },
        content = content,
    )
}

/** A double-ruled engraver's frame with lozenges at the corners. */
fun DrawScope.engravedFrame(color: Color, inset: Float = 0f) {
    val o = 1.2.dp.toPx()
    val i = 4.5.dp.toPx()
    drawRoundRect(color, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(3.dp.toPx()), style = Stroke(o))
    drawRect(color.copy(alpha = 0.55f), Offset(inset + i, inset + i), Size(size.width - 2 * (inset + i), size.height - 2 * (inset + i)), style = Stroke(0.6.dp.toPx()))
    val d = 3.5.dp.toPx()
    for ((cx, cy) in listOf(inset + i to inset + i, size.width - inset - i to inset + i, inset + i to size.height - inset - i, size.width - inset - i to size.height - inset - i)) {
        val path = Path().apply { moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close() }
        drawPath(path, color)
    }
}

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val frame = accent ?: p.brass
    Column(
        modifier
            .fillMaxWidth()
            .background(p.card.copy(alpha = if (p.dark) 0.92f else 0.86f), RoundedCornerShape(4.dp))
            .drawBehind { engravedFrame(frame) }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        if (title != null) {
            Flourish(title, color = frame)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** A heading between two engraved rules: ——◆ TITLE ◆—— */
@Composable
fun Flourish(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.brass) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawLine(color.copy(alpha = 0.7f), Offset(0f, size.height / 2), Offset(size.width - 8.dp.toPx(), size.height / 2), 1f)
            diamond(Offset(size.width - 3.dp.toPx(), size.height / 2), 3.dp.toPx(), color)
        }
        Text(text.uppercase(), style = Type.label.copy(color = p.ink), modifier = Modifier.padding(horizontal = 10.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            diamond(Offset(3.dp.toPx(), size.height / 2), 3.dp.toPx(), color)
            drawLine(color.copy(alpha = 0.7f), Offset(8.dp.toPx(), size.height / 2), Offset(size.width, size.height / 2), 1f)
        }
    }
}

private fun DrawScope.diamond(c: Offset, r: Float, color: Color) {
    drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, color)
}

@Composable
fun Rule(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val y = size.height / 2
        drawLine(p.rule, Offset(0f, y - 1.5f), Offset(size.width, y - 1.5f), 0.8f)
        drawLine(p.rule, Offset(0f, y + 1.5f), Offset(size.width, y + 1.5f), 0.8f)
    }
}

/** A brass plate that sinks when pressed. */
@Composable
fun BrassButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    tone: Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    val base = tone ?: p.brass
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier
            .scale(scale)
            .height(46.dp)
            .background(Brush.verticalGradient(listOf(lighten(base, 0.28f), base, darken(base, 0.25f))), RoundedCornerShape(6.dp))
            .border(1.dp, darken(base, 0.4f).copy(alpha = alpha), RoundedCornerShape(6.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled && !busy) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) { CompassSpinner(Modifier.size(18.dp), color = Color(0xFFFFF4D6)); Spacer(Modifier.width(8.dp)) }
            Text(text.uppercase(), style = Type.label.copy(color = Color(0xFFFFF7E3).copy(alpha = alpha), fontSize = 12.sp), maxLines = 1)
        }
    }
}

fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)
fun darken(c: Color, f: Float) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f), c.alpha)

/** A small selectable token, like a brass tab in a card index. */
@Composable
fun Token(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "token")
    // The chip is drawn 30dp tall but its touch area is the 48dp Android asks for,
    // and TalkBack hears it as a selectable tab.
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .background(p.brass.copy(alpha = 0.12f + 0.75f * bg), RoundedCornerShape(14.dp))
                .border(1.dp, p.brass.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text, style = Type.figure.copy(fontSize = 13.sp, color = if (selected) Color(0xFFFFF7E3) else p.ink))
        }
    }
}

/** A rubber stamp that thumps onto the page: PAPER, WIN, LOSS, SEALED. */
@Composable
fun Stamp(text: String, color: Color, modifier: Modifier = Modifier, angle: Float = -9f, animate: Boolean = true) {
    val s = remember { Animatable(if (animate) 2.2f else 1f) }
    val a = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(text) {
        if (animate) {
            a.animateTo(1f, tween(120))
            s.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 500f))
        }
    }
    Box(
        modifier
            .rotate(angle)
            .scale(s.value)
            .border(2.dp, color.copy(alpha = 0.85f * a.value), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = Type.label.copy(color = color.copy(alpha = 0.9f * a.value), fontSize = 13.sp, letterSpacing = 3.sp))
    }
}

/** A compass rose that turns while work is under way. */
@Composable
fun CompassSpinner(modifier: Modifier = Modifier, color: Color = LocalPalette.current.brass) {
    val t = rememberInfiniteTransition(label = "compass")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle")
    Canvas(modifier) {
        rotate(angle) { compassRose(color, min(size.width, size.height) / 2) }
    }
}

fun DrawScope.compassRose(color: Color, r: Float) {
    fun star(points: Int, outer: Float, inner: Float, rot: Double, c: Color) {
        val path = Path()
        for (i in 0 until points * 2) {
            val rr = if (i % 2 == 0) outer else inner
            val a = rot + i * PI / points
            val x = center.x + (rr * cos(a)).toFloat()
            val y = center.y + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, c)
    }
    star(8, r * 0.78f, r * 0.26f, -PI / 2 + PI / 8, color.copy(alpha = 0.55f))
    star(4, r, r * 0.22f, -PI / 2, color)
    drawCircle(color, r * 0.12f, center)
}

@Composable
fun FullSpinner(label: String, progress: Float? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompassSpinner(Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text(label, style = Type.italic.copy(color = p.inkSoft), textAlign = TextAlign.Center)
        if (progress != null && progress >= 0f) {
            Spacer(Modifier.height(10.dp))
            InkProgress(progress, Modifier.width(200.dp))
        }
    }
}

@Composable
fun InkProgress(progress: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val v by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "progress")
    Canvas(modifier.height(6.dp)) {
        drawRoundRect(p.rule, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1f))
        drawRoundRect(p.brass, size = Size(size.width * v, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

/** A number that counts to its new value rather than jumping. */
@Composable
fun RollingFigure(value: Double, format: (Double) -> String, style: TextStyle, modifier: Modifier = Modifier, calm: Boolean = false) {
    val anim = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) { if (calm) anim.snapTo(value.toFloat()) else anim.animateTo(value.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(format(anim.value.toDouble()), style = style, modifier = modifier)
}

/**
 * The equity curve, drawn as if by a pen: the stroke lengthens from the first
 * session to the last. Losses are marked with an oxblood blot, and the sealed
 * holdout is fenced off by a dashed rule.
 */
@Composable
fun InkCurve(
    values: List<Double>,
    losses: List<Int>,
    sealFrom: Int?,
    modifier: Modifier = Modifier,
    calm: Boolean = false,
) {
    val p = LocalPalette.current
    val draw = remember(values) { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(values) { if (!calm) { draw.snapTo(0f); draw.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) } }
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (values.size < 2) return@Canvas
        val lo = min(0.0, values.min())
        val hi = max(0.0, values.max())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val padT = 8.dp.toPx(); val padB = 8.dp.toPx()
        fun x(i: Int) = size.width * i / (values.size - 1)
        fun y(v: Double) = (padT + (size.height - padT - padB) * (1 - (v - lo) / span)).toFloat()
        drawLine(p.rule, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        if (sealFrom != null && sealFrom in values.indices) {
            val sx = x(sealFrom)
            drawRect(p.brass.copy(alpha = 0.08f), Offset(sx, 0f), Size(size.width - sx, size.height))
            drawLine(p.brass, Offset(sx, 0f), Offset(sx, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
        }
        val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * draw.value, partial, true)
        drawPath(partial, p.ink, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        val shown = (values.size * draw.value).toInt()
        for (i in losses) if (i < shown) {
            drawCircle(p.oxblood.copy(alpha = 0.25f), 7.dp.toPx(), Offset(x(i), y(values[i])))
            drawCircle(p.oxblood, 3.dp.toPx(), Offset(x(i), y(values[i])))
        }
        if (draw.value > 0.98f) drawCircle(p.gold, 4.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
    }
}

/** A small line for the day's quote. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = size.width * i / (values.size - 1)
            val py = (size.height * (1 - (v - lo) / span)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * The health dial: a brass semicircle banded PASS / WARN / FAIL, whose needle
 * swings on a spring to the verdict. The verdict is the WORST check.
 */
@Composable
fun VerdictDial(level: Int, modifier: Modifier = Modifier, calm: Boolean = false) {
    val p = LocalPalette.current
    val target = when (level) { 0 -> 0.17f; 1 -> 0.5f; else -> 0.83f }
    val needle = remember { Animatable(0f) }
    LaunchedEffect(level) {
        if (calm) needle.snapTo(target) else {
            needle.animateTo(1f, tween(500))
            needle.animateTo(target, spring(dampingRatio = 0.28f, stiffness = 60f))
        }
    }
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        val r = min(size.width / 2, size.height) * 0.92f
        val c = Offset(size.width / 2, size.height * 0.98f)
        val bands = listOf(p.verdigris, p.amber, p.oxblood)
        bands.forEachIndexed { i, col ->
            drawArc(col.copy(alpha = 0.85f), 180f + 60f * i + 1f, 58f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(14.dp.toPx()))
        }
        drawArc(p.brass, 180f, 180f, false, Offset(c.x - r * 1.1f, c.y - r * 1.1f), Size(2.2f * r, 2.2f * r), style = Stroke(1.2.dp.toPx()))
        for (t in 0..12) {
            val a = PI + PI * t / 12
            val o = r * 1.1f; val i = r * (if (t % 2 == 0) 1.02f else 1.05f)
            drawLine(p.brass, Offset(c.x + (o * cos(a)).toFloat(), c.y + (o * sin(a)).toFloat()), Offset(c.x + (i * cos(a)).toFloat(), c.y + (i * sin(a)).toFloat()), 1.5f)
        }
        val a = PI + PI * needle.value
        val tip = Offset(c.x + (r * 0.86f * cos(a)).toFloat(), c.y + (r * 0.86f * sin(a)).toFloat())
        drawLine(p.ink, c, tip, 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(p.brass, 8.dp.toPx(), c)
        drawCircle(p.ink, 3.dp.toPx(), c)
    }
}

/** A wax seal with a compass-rose die. Cracks and lifts when the app unlocks. */
@Composable
fun WaxSeal(size: Dp, modifier: Modifier = Modifier, broken: Boolean = false, pulse: Boolean = true) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "seal")
    val glow by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glow")
    val lift by animateFloatAsState(if (broken) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "lift")
    Canvas(modifier.size(size).scale(if (pulse && !broken) glow else 1f + 0.3f * lift)) {
        val r = this.size.minDimension / 2
        val path = Path()
        val n = 28
        for (i in 0 until n * 2) {
            val rr = if (i % 2 == 0) r else r * 0.93f
            val a = i * PI / n
            val x = center.x + (rr * cos(a)).toFloat(); val y = center.y + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val alpha = 1f - lift
        drawPath(path, Brush.radialGradient(listOf(lighten(p.seal, 0.2f), p.seal, darken(p.seal, 0.35f)), center, r), alpha = alpha)
        drawCircle(darken(p.seal, 0.3f).copy(alpha = alpha), r * 0.72f, style = Stroke(2.dp.toPx()))
        drawCircle(lighten(p.seal, 0.25f).copy(alpha = 0.5f * alpha), r * 0.66f, style = Stroke(1.dp.toPx()))
        compassRose(Color(0xFFE9C46A).copy(alpha = 0.9f * alpha), r * 0.55f)
        if (lift > 0f) {
            val crack = Path().apply { moveTo(center.x - r * 0.2f, center.y - r); lineTo(center.x + r * 0.05f, center.y - r * 0.2f); lineTo(center.x - r * 0.1f, center.y + r * 0.3f); lineTo(center.x + r * 0.15f, center.y + r) }
            drawPath(crack, p.paper, style = Stroke(3.dp.toPx() * (1 + lift)))
        }
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.size(9.dp).background(color.copy(alpha = if (pulsing) a else 1f), CircleShape))
}

/** Label and figure on one line, dotted leader between - a ledger row. */
@Composable
fun LedgerLine(label: String, value: String, valueColor: Color? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, style = Type.bodySmall.copy(color = p.inkSoft))
        Canvas(Modifier.weight(1f).height(12.dp).padding(horizontal = 6.dp)) {
            drawLine(p.rule, Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
        }
        Text(value, style = Type.figure.copy(color = valueColor ?: p.ink))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text, style = Type.italic.copy(color = p.inkSoft), modifier = modifier)
}

@Composable
fun Spaced(space: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(space), content = content)
