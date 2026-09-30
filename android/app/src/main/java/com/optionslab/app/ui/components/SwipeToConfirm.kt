package com.optionslab.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Slide to close", like the phone's slide-to-power-off: the knob must be
 * dragged two thirds of the way across before [onConfirm] runs, so a stray tap can
 * never close a position or cancel an order. Let go early and it springs back.
 */
@Composable
fun SwipeToConfirm(label: String, tone: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val knob = 52.dp
    // The knob follows the finger in the same frame (a plain state, no coroutine per move); only the release animates.
    var x by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    BoxWithConstraints(
        // At least 60 dp, taller when a large font wraps the label (it was cut at 60).
        modifier.fillMaxWidth().heightIn(min = 60.dp).background(tone.copy(alpha = 0.14f), RoundedCornerShape(50))
            // Screen readers cannot drag: the same confirmation as a button action (any PIN step still follows).
            .semantics(mergeDescendants = true) { role = Role.Button; if (enabled) onClick(label = label) { onConfirm(); true } else disabled() }
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart,
    ) {
        val maxPx = with(density) { (maxWidth - knob - 8.dp).toPx() }.coerceAtLeast(1f)
        fun settle(to: Float, ms: Int, then: () -> Unit = {}) = scope.launch {
            animate(x, to, animationSpec = tween(ms)) { v, _ -> x = v }
            then()
        }
        Box(
            Modifier.matchParentSize()
                // The whole track takes the slide, not only the knob: a thumb anywhere on it moves the knob.
                .pointerInput(maxPx, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { done = false },
                        onDragEnd = {
                            // Two thirds of the way is enough (it was 85%, which felt stuck); a stray tap still does nothing.
                            if (!done && x >= maxPx * 0.66f) {
                                done = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onConfirm()
                                settle(maxPx, 80)
                            } else settle(0f, 160)
                        },
                        onDragCancel = { settle(0f, 160) },
                    ) { change, drag ->
                        change.consume()
                        x = (x + drag).coerceIn(0f, maxPx)
                    }
                },
        )
        // The label fades as the knob travels, as on the power-off slider.
        Text("$label  ›››", style = Type.label.copy(color = tone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center).padding(start = knob, top = 6.dp, bottom = 6.dp).alpha(1f - (x / maxPx).coerceIn(0f, 1f)))
        Box(
            Modifier.padding(4.dp).offset { IntOffset(x.roundToInt(), 0) }.size(knob).background(tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("›", style = Type.title.copy(color = p.card, fontSize = 26.sp))
        }
    }
}
