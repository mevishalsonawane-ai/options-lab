package com.optionslab.app.ui.components

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text drawn on a chart canvas (axis labels, the last-price tag). A canvas has fixed room for them - a price
 * gutter, a time axis - so they grow with the system font only up to [AXIS_FONT_CAP] times, are measured on one
 * line (never wrapped: a wrapped "130.0 / 0" is a wrong number), and a label that would touch the one before is
 * left out rather than drawn over it.
 */
const val AXIS_FONT_CAP = 1.3f

/** [base] (sp) as drawn at [fontScale], but never larger than [cap] times [base]. */
fun axisFontSize(base: TextUnit, fontScale: Float, cap: Float = AXIS_FONT_CAP): TextUnit =
    if (fontScale > cap) (base.value * cap / fontScale).sp else base

/** [text] laid out on a single line, unconstrained: its full width, never wrapped. */
fun TextMeasurer.measureLine(text: String, style: TextStyle): TextLayoutResult =
    measure(text, style, softWrap = false, maxLines = 1)

/**
 * Which of [spans] (left, right edges, in drawing order left to right) to draw so that none comes within [gap] of
 * the last one drawn: the indices kept. The first is always kept.
 */
fun nonOverlapping(spans: List<Pair<Float, Float>>, gap: Float): List<Int> {
    val kept = ArrayList<Int>()
    var edge = Float.NEGATIVE_INFINITY
    spans.forEachIndexed { i, (l, r) ->
        if (l >= edge + gap || kept.isEmpty()) { kept += i; edge = r }
    }
    return kept
}
