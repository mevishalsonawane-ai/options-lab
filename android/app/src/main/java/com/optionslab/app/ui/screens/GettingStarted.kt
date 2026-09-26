package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow

const val GETTING_STARTED = "tour.done"

/** Only for the very first use: shown once, then never again (kept for a future "show again" button). */
val showGettingStarted = MutableStateFlow(false)

private data class TourPage(val title: String, val lines: List<String>, val action: Pair<String, String>? = null)

/**
 * The first screen after Zerodha is linked: what the app does and, above all, how
 * to start automated trading (the ORB switch on Home). [onGo] gets "orb", "chart",
 * "trade" or "" (just close).
 */
@Composable
fun GettingStarted(onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var i by remember { mutableStateOf(0) }
    val pages = listOf(
        TourPage("Welcome to IraAlgo", listOf(
            "Your Zerodha account is linked.",
            "The badge at the top shows where orders go: PAPER TRADING (practice money, nothing reaches Zerodha) or LIVE TRADING (real money). Tap it to switch.",
            "Start on Paper until you trust a strategy.",
        )),
        TourPage("Start automated trading", listOf(
            "On Home, find the Strategies card with ORB and ORB Fresh.",
            "Tap the switch next to ORB (or ORB Fresh) to turn it on, and choose Automatic.",
            "From then on it trades BANKNIFTY by itself every trading day: it buys on the breakout after 10:05 and sells at the stop, the target or 15:10.",
            "In Live, turning it on asks for your PIN or fingerprint once. Turn the switch off to stop it.",
        ), "Take me to the ORB switch" to "orb"),
        TourPage("Stay in control", listOf(
            "Stop for today (on the Strategies card) closes today's ORB trades; it runs again tomorrow.",
            "The kill switch in Bot settings blocks every new order at once.",
            "Every live order you place yourself asks for your PIN or fingerprint.",
            "You get a morning check at 09:00, a login reminder at 09:10 and a day report at 15:45.",
        )),
        TourPage("Find your way", listOf(
            "Home: the market, your ORB arms and alerts.",
            "Chart: live candles; BUY / SELL and price alerts sit above it.",
            "Trade: your paper and Zerodha account, orders and strategies.",
            "P&L: a calendar of every day's profit and loss.",
            "Options and Research: the option chain, OI and backtests.",
        ), "Open the chart" to "chart"),
    )
    val page = pages[i]
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy, dismissOnClickOutside = false),
        title = {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pages.indices.forEach { k -> Box(Modifier.weight(1f).height(4.dp).background(if (k <= i) p.verdigris else p.rule, RoundedCornerShape(2.dp))) }
                }
                Text(page.title, style = Type.title)
            }
        },
        text = {
            Column {
                page.lines.forEachIndexed { n, line ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("${n + 1}", style = Type.bodySmall.copy(color = p.gold, fontWeight = FontWeight.Bold), modifier = Modifier.width(20.dp))
                        Text(line, style = Type.bodySmall.copy(color = p.ink, fontSize = 14.sp))
                    }
                }
                page.action?.let { (label, dest) ->
                    BrassButton(label, Modifier.fillMaxWidth().padding(top = 12.dp), tone = p.verdigris) { onGo(dest) }
                }
            }
        },
        confirmButton = {
            if (i < pages.size - 1) TextButton({ i++ }) { Text("Next") } else TextButton({ onGo("orb") }) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (i > 0) TextButton({ i-- }) { Text("Back") }
                TextButton({ onGo("") }) { Text("Skip") }
            }
        },
    )
}
