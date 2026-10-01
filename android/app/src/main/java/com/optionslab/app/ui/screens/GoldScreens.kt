package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.GoldPaper
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.AlertBanner
import com.optionslab.app.ui.components.BrandLogo
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.gold.GoldLiquidity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * IraGoldAlgo's whole app (the gold build only): Home (XAUUSD, the paper account, the one strategy), Trades (closed
 * trades and totals) and Settings (paper amount, lot size, theme, security, diagnostics). Paper only: nothing here
 * can send an order anywhere.
 */
@Composable
fun GoldMain(model: AppModel) {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf("home") }
    // While the app is open the pass runs every minute (the alarm does it every five in the background).
    LaunchedEffect(Unit) { while (true) { withContext(Dispatchers.IO) { GoldPaper.tick() }; delay(60_000) } }
    // The alert banner is a full-screen overlay (as in IraAlgo): it goes on top of the column, never inside it, where
    // its fillMaxSize took every pixel and left the tabs' content zero high (found on the emulator).
    Box(Modifier.fillMaxSize().background(p.paper)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(Modifier.weight(1f))
            Text("PAPER", style = Type.label.copy(color = p.brass, fontWeight = FontWeight.Bold))
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "trades" -> GoldTrades()
                "settings" -> GoldSettings(model)
                else -> GoldHome()
            }
        }
        Row(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding()) {
            listOf("home" to "Home", "trades" to "Trades", "settings" to "Settings").forEach { (k, label) ->
                Text(label, style = Type.label.copy(color = if (tab == k) p.brass else p.inkSoft, fontSize = 14.sp,
                    fontWeight = if (tab == k) FontWeight.Bold else FontWeight.Medium), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).clickable { tab = k }.padding(top = 16.dp))
            }
        }
    }
    AlertBanner()
    }
}

@Composable
private fun GoldHome() {
    val p = LocalPalette.current
    val b by GoldPaper.book.collectAsState()
    val scope = rememberCoroutineScope()
    val open = b.open(b.price)
    Page {
        item {
            LedgerCard(title = "XAUUSD") {
                Text(b.price?.let { "$%,.2f".format(Locale.ENGLISH, it) } ?: "—", style = Type.figureLarge.copy(color = p.ink))
                Note("COMEX gold futures (${GoldPaper.FEED_SYMBOL}), the free live feed: XM's XAUUSD sits a few dollars lower; the levels and signals are the same." +
                    (b.priceAt?.let { " Last candle ${it.toLocalTime()} UTC." } ?: ""))
            }
        }
        item {
            LedgerCard(title = "Paper account") {
                Text(GoldPaper.usd(b.balance + (open ?: 0.0)).removePrefix("+"), style = Type.figureLarge.copy(color = p.ink))
                LedgerLine("Balance (closed trades)", GoldPaper.usd(b.balance).removePrefix("+"))
                LedgerLine("Realised", GoldPaper.usd(b.realized), if (b.realized >= 0) p.verdigris else p.oxblood)
                open?.let { LedgerLine("Open trade", GoldPaper.usd(it), if (it >= 0) p.verdigris else p.oxblood) }
                LedgerLine("Size", "%.2f lot (%.0f oz)".format(Locale.ENGLISH, b.lots, b.lots * GoldLiquidity.OZ_PER_LOT))
            }
        }
        item {
            LedgerCard(title = "Liquidity 1h") {
                ToggleRow("Armed", "Buys only, 1-hour candles, London + New York (07:00-21:00 UTC). Paper: you get a notification on every buy and sell.",
                    b.armed) { on -> scope.launch(Dispatchers.IO) { GoldPaper.setArmed(on) } }
                LedgerLine("Status", b.status)
                b.position?.let { pos ->
                    Rule(Modifier.padding(vertical = 6.dp))
                    LedgerLine("Bought", "%.2f at %s UTC".format(Locale.ENGLISH, pos.entry, pos.entryTime.toLocalTime().withNano(0)))
                    LedgerLine("Broken level", "%.2f".format(Locale.ENGLISH, pos.level))
                    LedgerLine("Target (next liquidity)", pos.target?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none above: out on the other exits")
                }
                Note("Out at the first of: the next liquidity level, a candle closing back below the broken level, new liquidity above, or 20:40 UTC. " +
                    "Backtest (three years, 1 lot): +$21.4k, 59% won, t 1.62 - a candidate, not a proven edge.")
            }
        }
    }
}

@Composable
private fun GoldTrades() {
    val p = LocalPalette.current
    val b by GoldPaper.book.collectAsState()
    val today = GoldPaper.now().toLocalDate()
    Page {
        item {
            LedgerCard(title = "Results") {
                fun sum(f: (LocalDate) -> Boolean) = b.trades.filter { f(it.exitTime.toLocalDate()) }
                listOf("Today" to sum { it == today }, "This month" to sum { it.year == today.year && it.month == today.month }, "All" to b.trades)
                    .forEach { (label, ts) ->
                        val x = ts.sumOf { it.pnl }
                        LedgerLine("$label · ${ts.size} trades · ${if (ts.isEmpty()) 0 else Math.round(100.0 * ts.count { it.pnl > 0 } / ts.size)}% won",
                            GoldPaper.usd(x), if (x >= 0) p.verdigris else p.oxblood)
                    }
            }
        }
        item {
            LedgerCard(title = "Closed trades") {
                if (b.trades.isEmpty()) Note("No trades yet.")
                b.trades.asReversed().take(200).forEach { t ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        LedgerLine("${t.exitTime.toLocalDate()} · %.2f → %.2f".format(Locale.ENGLISH, t.entry, t.exit), GoldPaper.usd(t.pnl),
                            if (t.pnl >= 0) p.verdigris else p.oxblood)
                        Note("${t.entryTime.toLocalTime().withNano(0)}-${t.exitTime.toLocalTime().withNano(0)} UTC · %.2f lot · ".format(Locale.ENGLISH, t.lots) + GoldPaper.label(t.why))
                    }
                }
            }
        }
    }
}

@Composable
private fun GoldSettings(model: AppModel) {
    val p = LocalPalette.current
    val b by GoldPaper.book.collectAsState()
    val s by model.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var resetAsk by remember { mutableStateOf<Double?>(null) }
    var security by rememberSaveable { mutableStateOf(false) }
    if (security) {
        Column(Modifier.fillMaxSize()) {
            Text("‹ Settings", style = Type.label.copy(color = p.brass, fontSize = 15.sp),
                modifier = Modifier.heightIn(min = 48.dp).clickable { security = false }.padding(horizontal = 16.dp, vertical = 14.dp))
            Box(Modifier.weight(1f)) { SecurityPage(model) }
        }
        androidx.activity.compose.BackHandler { security = false }
        return
    }
    Page {
        item {
            LedgerCard(title = "Paper") {
                val sizes = listOf(0.01, 0.05, 0.10, 0.50, 1.0)
                ParamTokens("Lot size", sizes.map { "%.2f".format(Locale.ENGLISH, it) to (it == b.lots) }) { i -> scope.launch(Dispatchers.IO) { GoldPaper.setLots(sizes[i]) } }
                val amounts = listOf(300.0, 1_000.0, 5_000.0, 10_000.0)
                ParamTokens("Start again with", amounts.map { "$%,.0f".format(Locale.ENGLISH, it) to false }) { i -> resetAsk = amounts[i] }
                resetAsk?.let { amt ->
                    Note("Reset the paper account to $%,.0f? Its trades and any open trade go.".format(Locale.ENGLISH, amt))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrassButton("Reset", Modifier.weight(1f), tone = p.oxblood) { scope.launch(Dispatchers.IO) { GoldPaper.reset(amt) }; resetAsk = null }
                        BrassButton("Cancel", Modifier.weight(1f), tone = p.inkFaint) { resetAsk = null }
                    }
                }
                Note("Paper only: IraGoldAlgo never sends an order. Act on its notifications in your own broker app if you choose to.")
            }
        }
        item {
            LedgerCard(title = "Look") {
                val themes = listOf("system" to "Phone", "light" to "Light", "dark" to "Dark")
                ParamTokens("Theme", themes.map { it.second to (it.first == s.theme) }) { i -> model.update { it.copy(theme = themes[i].first) } }
            }
        }
        item {
            LedgerCard(title = "Security") {
                Note("App PIN, fingerprint, screenshots and screen recording, and the device check.")
                BrassButton("Open security", Modifier.fillMaxWidth()) { security = true }
            }
        }
        item {
            LedgerCard(title = "Help") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                com.optionslab.app.ui.components.TextButton({
                    scope.launch(Dispatchers.Main) {
                        val text = withContext(Dispatchers.IO) { com.optionslab.app.data.Diag.report() }
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("IraGoldAlgo diagnostics", text))
                        com.optionslab.app.work.Alerts.success("Diagnostics copied: paste them in the chat. Keys, tokens and passwords are never included.")
                    }
                }, Modifier.fillMaxWidth()) { Text("Copy diagnostics") }
                Note("This app: build ${com.optionslab.app.BuildConfig.COMMIT}")
            }
        }
    }
}
