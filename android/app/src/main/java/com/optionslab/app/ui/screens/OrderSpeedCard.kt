package com.optionslab.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.OrderTiming
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.OrderSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The card's title (Settings → Zerodha, beside the self-test and "Copy diagnostics"). */
internal const val ORDER_SPEED_TITLE = "Order speed"

/**
 * Order speed (Boss, 9 Oct): today's typical and worst time for each step from a signal to the fill, paper and live apart,
 * the relay's and the direct round trip, the phone clock's offset, the warnings, and where the relay is. Read off the main
 * thread every 5 s while on screen ([OrderTiming.card]: memory only, no network). It only shows: nothing here changes the
 * relay or any setting.
 */
@Composable
internal fun OrderSpeedCard() {
    var card by remember { mutableStateOf<OrderTiming.Card?>(null) }
    var relayOn by remember { mutableStateOf(false) }
    var flow by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            card = withContext(Dispatchers.IO) { runCatching { OrderTiming.card() }.getOrNull() }
            // The order flow's coverage: instruments in full mode, ticks a second, Kite's subscription count (memory only).
            flow = if (com.optionslab.app.BuildConfig.GOLD) null else withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.OrderFlowLive.coverageLine() }.getOrNull() }
            relayOn = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.Relay.enabled }.getOrDefault(false) }
            delay(5_000)
        }
    }
    OrderSpeedContent(card, relayOn, region = null, flowLine = flow)
}

/** The card from plain values ([card] null: not read yet); [region] the relay's own word for where it is, when it says one. */
@Composable
internal fun OrderSpeedContent(card: OrderTiming.Card?, relayOn: Boolean, region: String?, flowLine: String? = null) {
    val p = LocalPalette.current
    LedgerCard(title = ORDER_SPEED_TITLE, accent = if (card?.warnings?.isNotEmpty() == true) p.amber else null) {
        Note("From a signal to Zerodha's fill, step by step, today: p50 (typical, the median) and p95 (the worst one in twenty); where each decision's price came from; the price stream's drops.")
        if (card == null) { Note("Reading…"); return@LedgerCard }
        card.warnings.forEach { w ->
            Text("⚠ $w", style = Type.bodySmall.copy(color = p.oxblood), modifier = Modifier.padding(vertical = 2.dp))
        }
        card.lines.forEach { l -> Text(l, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp)) }
        flowLine?.let { Text(it, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp)) }
        if (relayOn) OrderSpeed.relayAdvice(card.relayMedianMs, region)?.let { Note(it) }
        Note("Orders still go only through the relay when it is on; nothing here changes it.")
    }
}
