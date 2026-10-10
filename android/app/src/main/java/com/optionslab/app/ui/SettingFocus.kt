package com.optionslab.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A Settings row asked for by a tapped notification ("Log in to Zerodha", "the backup", "the model"...): its page
 * scrolls it into view and it pulses for a moment ([SettingSpot]). Only for a short while after the tap: a row
 * reached later by hand does not light up.
 */
object SettingFocus {
    /** The row wanted ([com.optionslab.app.work.NoticeCards.SETTINGS] key), until it has been shown. */
    val wanted = MutableStateFlow<String?>(null)
    /** The wanted row is laid out (its page stops scrolling to find it). */
    val reached = MutableStateFlow<String?>(null)
    /** The row last highlighted (kept, so a test can see which one it was). */
    val lit = MutableStateFlow<String?>(null)
    @Volatile private var askedAt = 0L
    const val FRESH_MS = 15_000L

    fun ask(key: String) {
        reached.value = null; lit.value = null
        askedAt = System.currentTimeMillis()
        wanted.value = key
    }

    /** Still to be shown (asked a moment ago, not shown yet). */
    fun pending(key: String?): Boolean = key != null && wanted.value == key && System.currentTimeMillis() - askedAt < FRESH_MS

    fun done(key: String) { if (wanted.value == key) wanted.value = null }

    /**
     * A Settings page asked for from inside the app (Today's notes' "Turn these off"): the main screen opens it and clears
     * this; its row [key] is then brought into view and pulses, as for a tapped notification. Navigation only.
     */
    val pageWanted = MutableStateFlow<String?>(null)

    fun open(page: String, key: String) {
        ask(key)
        pageWanted.value = page
    }

    fun reset() { wanted.value = null; reached.value = null; lit.value = null; pageWanted.value = null; askedAt = 0L }
}

/**
 * A Settings row a notification can point at, by its stable [key]: when it is the row asked for ([SettingFocus]) it
 * brings itself fully into view and its edge pulses three times. Otherwise it only wraps [content].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingSpot(key: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val wanted by SettingFocus.wanted.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    var flashes by remember { mutableIntStateOf(0) }
    LaunchedEffect(wanted) {
        if (wanted != key || !SettingFocus.pending(key)) return@LaunchedEffect
        SettingFocus.reached.value = key
        delay(150)
        requester.bringIntoView()
        SettingFocus.lit.value = key
        flashes++
        // Last: clearing it restarts this effect (as a no-op); the pulse runs in its own effect below.
        SettingFocus.done(key)
    }
    LaunchedEffect(flashes) {
        if (flashes == 0) return@LaunchedEffect
        repeat(3) {
            glow.animateTo(1f, tween(300))
            glow.animateTo(0.15f, tween(450))
        }
        glow.animateTo(0f, tween(600))
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth()
            .testTag("setting:$key")
            .bringIntoViewRequester(requester)
            .drawWithContent {
                drawContent()
                val g = glow.value
                if (g > 0f) drawRoundRect(p.amber.copy(alpha = 0.18f * g), cornerRadius = CornerRadius(14.dp.toPx()))
            }
            .border(2.dp, p.amber.copy(alpha = glow.value), shape),
    ) { content() }
}
