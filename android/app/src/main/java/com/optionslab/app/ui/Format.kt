package com.optionslab.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

fun rs(x: Double, sign: Boolean = false): String =
    if (sign) String.format(Locale.ENGLISH, "Rs %+,.0f", x) else String.format(Locale.ENGLISH, "Rs %,.0f", x)

fun rs1(x: Double, sign: Boolean = true): String =
    if (sign) String.format(Locale.ENGLISH, "Rs %+,.1f", x) else String.format(Locale.ENGLISH, "Rs %,.1f", x)

fun pct(x: Double, digits: Int = 2): String = String.format(Locale.ENGLISH, "%.${digits}f%%", 100 * x)
fun num(x: Double, digits: Int = 1): String = String.format(Locale.ENGLISH, "%,.${digits}f", x)

/**
 * A polling loop that runs only while the app is in the foreground: it pauses
 * when the screen goes off or another app is opened, and restarts on return.
 * (A plain LaunchedEffect keeps calling Zerodha from a phone in a pocket.)
 */
@Composable
fun PollWhileStarted(vararg keys: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED, block)
    }
}

/** Every page scrolls as one column of cards, with room above the tab bar. */
@Composable
fun Page(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    // A Settings row asked for by a tapped notification ([SettingFocus]) further down than is laid out yet: scroll on
    // until it is (it then brings itself fully into view and pulses). Gives up at the end of the page.
    val wanted by SettingFocus.wanted.collectAsState()
    androidx.compose.runtime.LaunchedEffect(wanted) {
        val w = wanted ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        var steps = 0
        while (SettingFocus.pending(w) && SettingFocus.reached.value != w && state.canScrollForward && steps < 40) {
            state.animateScrollBy(500f); steps++
            kotlinx.coroutines.delay(40)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        content = content,
    )
}
