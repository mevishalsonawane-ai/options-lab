package com.optionslab.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The banners at the top of the screen: green for success, red for an error.
 * Each hides by itself after 2 seconds, or at once when swiped away (sideways
 * or up). Only events from the last few seconds show.
 */
@Composable
fun AlertBanner(modifier: Modifier = Modifier) {
    val queue by Alerts.queue.collectAsState()
    val fresh = queue.filter { System.currentTimeMillis() - it.at < 15_000 }
    // Old ones (posted while the app was closed) are dropped without showing.
    LaunchedEffect(queue) { queue.filter { it !in fresh }.forEach { Alerts.dismiss(it.id) } }
    Box(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.TopCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fresh.takeLast(3).forEach { a -> key(a.id) { Banner(a) } }
        }
    }
}

@Composable
private fun Banner(a: Alerts.Alert) {
    val p = LocalPalette.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(a.id) {
        // A short tick for good news, a firmer buzz for an error.
        haptics.performHapticFeedback(if (a.kind == Alerts.Kind.ERROR) androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
            else androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        delay(2_000); shown.targetState = false
    }
    // Once the exit animation has finished, the alert leaves the queue.
    LaunchedEffect(shown.currentState, shown.targetState) { if (!shown.targetState && !shown.currentState) Alerts.dismiss(a.id) }
    val bg = when (a.kind) {
        Alerts.Kind.SUCCESS -> p.verdigris
        Alerts.Kind.ERROR -> p.oxblood
        Alerts.Kind.INFO -> p.ink
    }
    val fg = if (a.kind == Alerts.Kind.INFO) p.paper else Color.White
    AnimatedVisibility(
        visibleState = shown,
        enter = slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .offset { IntOffset(dx.roundToInt(), dy.coerceAtMost(0f).roundToInt()) }
                .alpha((1f - abs(dx) / 600f).coerceIn(0.2f, 1f))
                .background(bg, RoundedCornerShape(14.dp))
                .pointerInput(a.id) {
                    detectDragGestures(
                        onDragEnd = {
                            if (abs(dx) > 120f || dy < -50f) shown.targetState = false
                            dx = 0f; dy = 0f
                        },
                        onDragCancel = { dx = 0f; dy = 0f },
                    ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            a.title?.let { Text(it, style = Type.bodySmall.copy(color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), maxLines = 1) }
            Text(a.text, style = Type.bodySmall.copy(color = fg, fontSize = 13.sp), maxLines = 3)
        }
    }
}

/** The same alerts, inside a dialog (a dialog sits above the screen's own banner). */
@Composable
fun InlineAlerts() {
    val queue by Alerts.queue.collectAsState()
    val fresh = queue.filter { System.currentTimeMillis() - it.at < 15_000 }.takeLast(2)
    if (fresh.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fresh.forEach { a -> key(a.id) { Banner(a) } }
    }
}

/** Post [text] as a red alert whenever it appears (a refusal, a failed load, a form error). */
@Composable
fun AlertOn(text: String?, kind: Alerts.Kind = Alerts.Kind.ERROR, throttle: Boolean = true) {
    LaunchedEffect(text) { if (!text.isNullOrBlank()) Alerts.post(text, kind, throttle = throttle) }
}

/**
 * material3's AlertDialog with the alerts shown at the top of its content, so a
 * validation error raised while a dialog is open is seen there too.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier, dismissButton = dismissButton,
    title = title, text = { Column { InlineAlerts(); text?.invoke() } }, properties = properties,
)
