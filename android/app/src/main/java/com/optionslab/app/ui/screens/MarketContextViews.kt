package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.MarketContext
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.EventShield
import kotlinx.coroutines.Dispatchers

/**
 * Home's "Market context" card (TODO I6, I10, I11; 13 Oct): the event warning, the expiry-day card and "days like today",
 * from [MarketContext.card] (memory; built on the context's own thread). Nothing shown on an ordinary day before 09:21.
 * Context, not a forecast: every figure carries its sample size.
 */
@Composable
internal fun MarketContextCard() {
    if (!MarketContext.enabled()) return
    val c by MarketContext.card.collectAsState(Dispatchers.Main.immediate)
    if (c.empty) return
    MarketContextContent(c)
}

@Composable
internal fun MarketContextContent(c: MarketContext.Card) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    LedgerCard(Modifier.padding(top = 10.dp), title = "Market context", accent = if (c.event != null) p.brass else null, onClick = { open = !open }) {
        c.event?.let { Text(it, style = Type.bodySmall.copy(color = p.ink)) }
        if (c.event != null && c.pause != EventShield.Mode.OFF)
            Note("Pause paper arms on event days: ${c.pause.words}.", Modifier.padding(top = 4.dp))
        c.expiryTitle?.let { t ->
            Text(t, style = Type.label.copy(color = p.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            (if (open) c.expiry else c.expiry.take(1)).forEach { Text("• $it", style = Type.bodySmall.copy(color = p.ink)) }
        }
        for ((und, lines) in c.days) {
            Text(if (open) lines.joinToString(" ") else lines.take(2).joinToString(" "), style = Type.bodySmall.copy(color = p.ink),
                modifier = Modifier.padding(top = 8.dp))
            if (und == c.days.keys.last() && !open) Note("Tap for more (each arm on those days, the nearest dates).", Modifier.padding(top = 2.dp))
        }
    }
}

/** Bot settings: Boss's switch "pause paper arms on event days" (TODO I6): OFF by default; SHADOW logs only; ON pauses paper. */
@Composable
internal fun EventShieldSwitchCard() {
    if (!MarketContext.enabled()) return
    val p = LocalPalette.current
    val m by MarketContext.mode.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    LedgerCard(Modifier.padding(top = 10.dp), title = "Pause paper arms on event days") {
        Note("On RBI, Budget, US CPI / Fed and results days (the event days above, and the ones you add): Off changes nothing (the default). " +
            "Shadow logs which paper entries it would have paused. On skips paper arms' new entries that day. Live orders, exits " +
            "and Liquidity BANKNIFTY (frozen until 26 Oct) are never touched. Research: BANKNIFTY moved 1.5x a normal day on RBI/Budget " +
            "days (15 days); ORB Fresh's event-day losses were not confirmed (research/EVENT_SHIELD.md).")
        EventShield.Mode.entries.forEach { x ->
            TextButton({ said = MarketContext.setMode(x) }) {
                Text((if (m == x) "● " else "○ ") + x.words.replaceFirstChar { it.uppercase() },
                    style = Type.label.copy(color = if (m == x) p.ink else p.inkSoft, fontWeight = if (m == x) FontWeight.Bold else FontWeight.Normal))
            }
        }
        val shadowed by MarketContext.shadowDays.collectAsState(Dispatchers.Main.immediate)
        Note(if (shadowed.isEmpty()) "On unlocks after Shadow has run through one event day." else "Event days run in Shadow: ${shadowed.size}.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/** Jarvis on one candle (TODO I13): what the app holds for those minutes, missing pieces said plainly. */
@Composable
internal fun CandleExplainDialog(text: String?, onClose: () -> Unit) {
    val p = LocalPalette.current
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Jarvis on this candle", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (text == null) Text("Reading what the app holds for those minutes…", style = Type.bodySmall.copy(color = p.inkSoft))
                else text.split('\n').forEach { Text(it, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}
