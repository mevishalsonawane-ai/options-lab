package com.optionslab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
 * dragged most of the way across before [onConfirm] runs, so a stray tap can
 * never close a position or cancel an order. Let go early and it springs back.
 */
@Composable
fun SwipeToConfirm(label: String, tone: Color, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val knob = 52.dp
    val x = androidx.compose.runtime.remember { Animatable(0f) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(60.dp).background(tone.copy(alpha = 0.14f), RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val maxPx = with(density) { (maxWidth - knob - 8.dp).toPx() }.coerceAtLeast(1f)
        // The label fades as the knob travels, as on the power-off slider.
        Text("$label  ›››", style = Type.label.copy(color = tone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center).padding(start = knob).alpha(1f - (x.value / maxPx).coerceIn(0f, 1f)))
        Box(
            Modifier.padding(4.dp).offset { IntOffset(x.value.roundToInt(), 0) }.size(knob).background(tone, CircleShape)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (x.value >= maxPx * 0.85f) {
                                    x.animateTo(maxPx, tween(120))
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirm()
                                } else x.animateTo(0f, tween(220))
                            }
                        },
                        onDragCancel = { scope.launch { x.animateTo(0f, tween(220)) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { x.snapTo((x.value + drag).coerceIn(0f, maxPx)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("›", style = Type.title.copy(color = p.card, fontSize = 26.sp))
        }
    }
}
