package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.PriceChart
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Upstox
import com.optionslab.engine.fmtG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One option's page, opened by tapping its price in the chain: the price,
 * today's change, a minute chart of the session, open/high/low, volume and
 * open interest, and Buy / Sell at the bottom. Refreshes every 30 s.
 */
@Composable
fun OptionChartPage(model: AppModel, pick: ChainPick, onFullChart: (String) -> Unit, onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val p = LocalPalette.current
    var bars by remember(pick) { mutableStateOf<List<Upstox.Bar>>(emptyList()) }
    var error by remember(pick) { mutableStateOf<String?>(null) }
    var loaded by remember(pick) { mutableStateOf(false) }
    var order by remember { mutableStateOf<Boolean?>(null) }   // true = buy, false = sell
    com.optionslab.app.ui.PollWhileStarted(pick) {
        while (true) {
            runCatching { model.optionIntraday(pick.underlying, pick.expiry, pick.strike, pick.right) }
                .onSuccess { bars = it; error = null }.onFailure { error = it.message }
            loaded = true
            delay(30_000)
        }
    }
    val title = "${pick.underlying} ${pick.expiry.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase()} ${fmtG(pick.strike)} ${pick.right.name}"
    val last = bars.lastOrNull()?.close ?: pick.ltp
    val open = bars.firstOrNull()?.open
    val change = if (last != null && open != null) last - open else null
    val up = (change ?: 0.0) >= 0

    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", style = Type.masthead.copy(color = p.ink, fontSize = 28.sp), modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = Type.title.copy(color = p.ink, fontSize = 16.sp), maxLines = 1)
                    Text("Lot ${pick.lotSize}" + (pick.ivPct?.let { " · IV %.1f%%".format(Locale.ENGLISH, it) } ?: "") +
                        (pick.delta?.let { " · Δ %.2f".format(Locale.ENGLISH, it) } ?: ""), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                }
                // The full chart: indicators, drawing tools, chart types.
                Text("Full chart ›", style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.clickable {
                    scope.launch {
                        val sym = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.optionslab.app.data.Market.contracts().firstOrNull { c ->
                                c.underlying == pick.underlying && c.expiry == pick.expiry && c.strike == pick.strike && c.right == pick.right }?.tradingSymbol }.getOrNull()
                        }
                        if (sym != null) onFullChart(sym)
                    }
                }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LedgerCard {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(last?.let { "₹%.2f".format(Locale.ENGLISH, it) } ?: "—", style = Type.figureHuge.copy(color = p.ink, fontSize = 34.sp), modifier = Modifier.weight(1f))
                        if (change != null && open != null && open > 0) Text(
                            "${if (up) "+" else "−"}%.2f (%.2f%%)".format(Locale.ENGLISH, kotlin.math.abs(change), kotlin.math.abs(100 * change / open)),
                            style = Type.figure.copy(color = if (up) p.verdigris else p.oxblood, fontSize = 15.sp), modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("Change since today's open", style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 12.sp))
                    if (bars.size >= 2) PriceChart(bars.map { it.close }, open, 375,
                        listOf(0 to "09:15", 105 to "11:00", 225 to "13:00", 374 to "15:30"), Modifier.padding(top = 10.dp))
                    else Note(when {
                        error != null -> "Could not load the chart: $error"
                        !loaded -> "Loading today's prices…"
                        else -> "No trades in this option yet today."
                    }, Modifier.padding(vertical = 28.dp))
                }
                if (bars.isNotEmpty()) LedgerCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Open", "%.2f".format(Locale.ENGLISH, bars.first().open), Modifier.weight(1f))
                        Stat("High", "%.2f".format(Locale.ENGLISH, bars.maxOf { it.high }), Modifier.weight(1f))
                        Stat("Low", "%.2f".format(Locale.ENGLISH, bars.minOf { it.low }), Modifier.weight(1f))
                    }
                    Rule(Modifier.padding(vertical = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Volume", "%,d".format(Locale.ENGLISH, bars.sumOf { it.volume }), Modifier.weight(1f))
                        Stat("Open interest", bars.last().oi.takeIf { it > 0 }?.let { "%,d".format(Locale.ENGLISH, it) } ?: "—", Modifier.weight(2f))
                    }
                }
            }
            // Buy / Sell, always in reach.
            Row(Modifier.fillMaxWidth().background(p.paperDeep).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(true to "BUY", false to "SELL").forEach { (isBuy, label) ->
                    Text(label, textAlign = TextAlign.Center, style = Type.label.copy(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f).background(if (isBuy) p.verdigris else p.oxblood, androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .clickable { order = isBuy }.padding(vertical = 14.dp))
                }
            }
        }
        order?.let { OptionOrderSheet(model, pick.copy(ltp = last ?: pick.ltp), initialBuy = it) { order = null } }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
        Spacer(Modifier.height(2.dp))
        Text(value, style = Type.figure.copy(color = p.ink, fontSize = 15.sp))
    }
}
