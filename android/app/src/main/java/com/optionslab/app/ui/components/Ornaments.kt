package com.optionslab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/*
 * IraAlgo's building blocks, in the monochrome style: flat surfaces, hairline
 * borders, pill-shaped controls, bold figures. The names are historical (the
 * app began with a ledger theme); every screen uses them, so they keep their
 * names and signatures and only their look has changed.
 */

private val CardShape = RoundedCornerShape(14.dp)
private val Pill = RoundedCornerShape(percent = 50)

/** The page background. [ruled] is ignored (kept for callers). */
@Composable
fun Parchment(modifier: Modifier = Modifier, ruled: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.fillMaxSize().background(p.paper), content = content)
}

/** A card: flat surface, 1dp border, 14dp corners. [accent] tints the border for state (e.g. a live order). */
@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val edge = if (accent != null && accent != p.brass) accent.copy(alpha = 0.55f) else p.rule
    Column(
        modifier
            .fillMaxWidth()
            .background(p.card, CardShape)
            .border(1.dp, edge, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (title != null) {
            Flourish(title)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** A section heading inside a card. */
@Composable
fun Flourish(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink) {
    Text(text, style = Type.title.copy(color = color, fontSize = 16.sp), modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A hairline divider. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(1.dp).background(p.rule))
}

/**
 * A pill button. With no [tone] it is the primary action (black on light,
 * white on dark). A red or green [tone] fills with that colour; the grey
 * tones (secondary / tertiary) draw an outlined button.
 */
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
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "press")
    val outlined = tone != null && (tone == p.inkSoft || tone == p.inkFaint || tone == p.ink)
    val fill = when {
        outlined -> Color.Transparent
        tone == null -> p.brass
        else -> tone
    }
    val textColor = when {
        outlined -> p.ink
        tone == null || tone == p.brass -> p.onPrimary
        else -> Color.White
    }
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier
            .scale(scale)
            .height(48.dp)
            .background(fill.copy(alpha = if (outlined) 0f else fill.alpha * alpha), Pill)
            .then(if (outlined) Modifier.border(1.dp, p.rule, Pill) else Modifier)
            .clickable(interactionSource = src, indication = null, enabled = enabled && !busy, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) { CircularProgressIndicator(Modifier.size(16.dp), color = textColor, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Type.label.copy(color = textColor.copy(alpha = alpha), fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)
fun darken(c: Color, f: Float) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f), c.alpha)

/** A selectable pill chip (tabs, filters, choices). 48dp touch area, Tab semantics. */
@Composable
fun Token(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .background(if (selected) p.brass else p.chip, Pill)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(text, style = Type.label.copy(fontSize = 13.sp, color = if (selected) p.onPrimary else p.ink), maxLines = 1)
        }
    }
}

/** A small status badge: LIVE, PAPER, WIN, LOSS. [angle] and [animate] are kept for callers. */
@Composable
fun Stamp(text: String, color: Color, modifier: Modifier = Modifier, angle: Float = 0f, animate: Boolean = false) {
    Box(
        modifier
            .background(color.copy(alpha = 0.12f), Pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = Type.label.copy(color = color, fontSize = 11.sp, letterSpacing = 0.6.sp), maxLines = 1)
    }
}

/** Work in progress. */
@Composable
fun CompassSpinner(modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink) {
    CircularProgressIndicator(modifier, color = color, strokeWidth = 2.5.dp)
}

@Composable
fun FullSpinner(label: String, progress: Float? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompassSpinner(Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, style = Type.bodySmall.copy(color = p.inkSoft), textAlign = TextAlign.Center)
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
    LinearProgressIndicator(progress = { v }, modifier = modifier.height(4.dp), color = p.brass, trackColor = p.chip, strokeCap = StrokeCap.Round)
}

/** A number that counts to its new value rather than jumping. */
@Composable
fun RollingFigure(value: Double, format: (Double) -> String, style: TextStyle, modifier: Modifier = Modifier, calm: Boolean = false) {
    val anim = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) { if (calm) anim.snapTo(value.toFloat()) else anim.animateTo(value.toFloat(), tween(600, easing = FastOutSlowInEasing)) }
    Text(format(anim.value.toDouble()), style = style, modifier = modifier)
}

/**
 * An equity curve: a line with a soft area fill (green above the start,
 * red below), losing trades as small red dots, and the held-out tail shaded.
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
    LaunchedEffect(values) { if (!calm) { draw.snapTo(0f); draw.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) } }
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
            drawRect(p.inkFaint.copy(alpha = 0.08f), Offset(sx, 0f), Size(size.width - sx, size.height))
        }
        val up = values.last() >= values.first()
        val line = if (up) p.verdigris else p.oxblood
        val shown = max(2, (values.size * draw.value).toInt())
        val path = Path().apply { for (i in 0 until shown) { if (i == 0) moveTo(x(i), y(values[i])) else lineTo(x(i), y(values[i])) } }
        val area = Path().apply { addPath(path); lineTo(x(shown - 1), size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.18f), line.copy(alpha = 0f))))
        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        for (i in losses) if (i < shown) drawCircle(p.oxblood, 2.5.dp.toPx(), Offset(x(i), y(values[i])))
        if (shown == values.size) drawCircle(line, 4.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
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
        drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The health gauge: a three-band arc (pass / warn / fail) and a needle at the verdict. */
@Composable
fun VerdictDial(level: Int, modifier: Modifier = Modifier, calm: Boolean = false) {
    val p = LocalPalette.current
    val target = when (level) { 0 -> 0.17f; 1 -> 0.5f; else -> 0.83f }
    val needle = remember { Animatable(if (calm) target else 0f) }
    LaunchedEffect(level) { if (calm) needle.snapTo(target) else needle.animateTo(target, tween(600, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val r = min(size.width / 2, size.height) * 0.9f
        val c = Offset(size.width / 2, size.height * 0.98f)
        listOf(p.verdigris, p.amber, p.oxblood).forEachIndexed { i, col ->
            drawArc(col, 180f + 60f * i + 1.5f, 57f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        }
        val a = PI + PI * needle.value
        val tip = Offset(c.x + (r * 0.78f * cos(a)).toFloat(), c.y + (r * 0.78f * sin(a)).toFloat())
        drawLine(p.ink, c, tip, 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(p.ink, 6.dp.toPx(), c)
    }
}

/** A live-state dot; pulses while something is running. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.size(8.dp).background(color.copy(alpha = if (pulsing) a else 1f), CircleShape))
}

/** A label on the left and its value on the right. */
@Composable
fun LedgerLine(label: String, value: String, valueColor: Color? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.weight(1f))
        Text(value, style = Type.figure.copy(color = valueColor ?: p.ink), textAlign = TextAlign.End)
    }
}

/** Secondary explanatory text. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text, style = Type.bodySmall.copy(color = p.inkSoft), modifier = modifier)
}

@Composable
fun Spaced(space: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(space), content = content)

/** Kept for callers that still draw one; draws nothing in this style. */
@Suppress("UNUSED_PARAMETER")
fun DrawScope.engravedFrame(color: Color, inset: Float = 0f) = Unit
