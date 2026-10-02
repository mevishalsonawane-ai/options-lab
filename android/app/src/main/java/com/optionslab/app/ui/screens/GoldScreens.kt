package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * IraGoldAlgo's whole app (the gold build only): Home (XAUUSD, the paper account, the one strategy), Chart (IraAlgo's
 * chart on gold, without its order buttons), P&L (IraAlgo's calendar over the gold trades), Trades (closed trades and totals) and Settings (paper amount, lot size, theme, security, diagnostics). Paper only: nothing here
 * can send an order anywhere.
 */
@Composable
fun GoldMain(model: AppModel) {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf("home") }
    // While the app is open the pass runs every minute (the alarm does it every five in the background).
    LaunchedEffect(Unit) { while (true) { withContext(Dispatchers.IO) { GoldPaper.tick() }; delay(60_000) } }
    // Android 13+: the buy / sell notifications need the owner's permission, asked once (IraAlgo asks on its own main
    // screen, which this app never shows - without this the gold alerts were silently blocked).
    val notify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !com.optionslab.app.security.SecurePrefs.getBoolean("asked.notify", false)) {
            com.optionslab.app.security.SecurePrefs.put("asked.notify", true)
            notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
                "chart" -> GoldChartTab()
                "pnl" -> GoldPnlCalendar()
                "trades" -> GoldTrades()
                "settings" -> GoldSettings(model)
                else -> GoldHome()
            }
        }
        Row(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding()) {
            listOf("home" to "Home", "chart" to "Chart", "pnl" to "P&L", "trades" to "Trades", "settings" to "Settings").forEach { (k, label) ->
                Text(label, style = Type.label.copy(color = if (tab == k) p.brass else p.inkSoft, fontSize = 14.sp,
                    fontWeight = if (tab == k) FontWeight.Bold else FontWeight.Medium), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).clickable { tab = k }.padding(top = 16.dp))
            }
        }
    }
    AlertBanner()
    }
}

/** Gold's candles for IraAlgo's chart: no contracts, no stream, nothing to trade. */
internal object GoldChartSource : ChartSource {
    override fun contract(symbol: String): com.optionslab.engine.Upstox.Contract? = null
    override suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?) =
        com.optionslab.app.data.GoldChart.bars(interval, fromSec, toSec)
    override fun search(text: String) = com.optionslab.app.data.GoldChart.search(text)
    override fun contracts(): List<com.optionslab.engine.Upstox.Contract> = emptyList()
    override suspend fun streamToken(symbol: String): Long? = null
}

/** The Chart tab: IraAlgo's chart (advanced, or the basic one) on gold; no BUY / SELL, ALERT or option chain. */
@Composable
private fun GoldChartTab() {
    ChartPane(com.optionslab.app.data.GoldChart.SYMBOL, "COMEX", visible = true, ask = 0, live = false, source = GoldChartSource,
        orderSheet = { _, _, _, _ -> }, alertDialog = { _, _ -> }, chainDialog = { _, _, _ -> }, trading = false,
        marketOpen = { GoldLiquidity.inSession(GoldPaper.now()) })
}

@Composable
private fun GoldHome() {
    val p = LocalPalette.current
    val b by GoldPaper.book.collectAsState()
    val chart by GoldPaper.chart.collectAsState()
    val tb by com.optionslab.app.data.GoldTrendPaper.book.collectAsState()
    val scope = rememberCoroutineScope()
    val open = b.open(b.price)
    val trendOpen = tb.open(b.price)
    val anyOpen = if (open == null && trendOpen == null) null else (open ?: 0.0) + (trendOpen ?: 0.0)
    Page {
        item {
            LedgerCard(title = "Gold (COMEX futures)") {
                Text(b.price?.let { "$%,.2f".format(Locale.ENGLISH, it) } ?: "—", style = Type.figureLarge.copy(color = p.ink))
                if (chart.size >= 2) {
                    com.optionslab.app.ui.components.Sparkline(chart, p.brass, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 6.dp))
                    Note("The last ${chart.size} one-hour candles.")
                }
                if (GoldPaper.stale(b)) LedgerLine("Price feed", "delayed: last price ${b.priceAt?.let { GoldPaper.when_(it) } ?: "none yet"}", p.oxblood)
                else b.priceAt?.let { LedgerLine("Last price", GoldPaper.when_(it)) }
                Note("GC=F, the free live gold feed. XM's XAUUSD (spot) sits a few dollars lower; the levels and signals are the same.")
            }
        }
        item {
            LedgerCard(title = "Paper account") {
                val realized = b.realized + tb.realized
                Text(GoldPaper.usd(b.balance + tb.realized + (anyOpen ?: 0.0)).removePrefix("+"), style = Type.figureLarge.copy(color = p.ink))
                LedgerLine("Balance (closed trades)", GoldPaper.usd(b.balance + tb.realized).removePrefix("+"))
                LedgerLine("Realised", GoldPaper.usd(realized), if (realized >= 0) p.verdigris else p.oxblood)
                anyOpen?.let { LedgerLine("Open trades", GoldPaper.usd(it), if (it >= 0) p.verdigris else p.oxblood) }
                LedgerLine("Size", "%.2f lot (%.0f oz)".format(Locale.ENGLISH, b.lots, b.lots * GoldLiquidity.OZ_PER_LOT))
            }
        }
        item {
            LedgerCard(title = "Liquidity 1h") {
                ToggleRow("Armed", "Buys only, 1-hour candles, 24x5: Monday 05:30 IST to Saturday 02:30 IST, held overnight. Paper: a notification on every buy and sell.",
                    b.armed) { on -> scope.launch(Dispatchers.IO) { GoldPaper.setArmed(on) } }
                LedgerLine("Status", b.status)
                if (b.armed) GoldBackgroundCheck(compact = true)
                if (b.armed && b.position == null) LedgerLine("Next decision", "about " + GoldPaper.nextDecision(GoldPaper.now()))
                LedgerLine("Last signal", b.lastSignal ?: "none yet")
                b.position?.let { pos ->
                    Rule(Modifier.padding(vertical = 6.dp))
                    LedgerLine("Bought", "%.2f at %s".format(Locale.ENGLISH, pos.entry, GoldPaper.when_(pos.entryTime)))
                    LedgerLine("Broken level", "%.2f".format(Locale.ENGLISH, pos.level))
                    LedgerLine("Target (2nd level up)", pos.target?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none above: out on the other exits")
                }
                Note("Each candle is decided about 10 minutes after it closes: the gold price feed (COMEX, via Yahoo) runs 10 minutes late.")
                Note("Buys at any trading hour (not 05:30 IST, the 02:30-03:30 IST break, or Friday after 00:30 IST). Held overnight until the first of: " +
                    "the second liquidity level above, a candle closing back below the broken level, new liquidity above, or Saturday 02:10 IST before the weekend. " +
                    "Backtest (three years, 1 lot): +$119.5k before swap (XM's overnight swap varies), 46% won, t 3.09, deepest drawdown -$7.3k, " +
                    "most of it in the last year - a candidate, not a proven edge.")
            }
        }
        item {
            LedgerCard(title = com.optionslab.app.data.GoldTrendPaper.NAME) {
                ToggleRow("Armed", "Buys only while gold's 4-hour trend (Supertrend 10, 3) points up; held overnight and over weekends. Paper: a notification on every buy and sell.",
                    tb.armed) { on -> scope.launch(Dispatchers.IO) { com.optionslab.app.data.GoldTrendPaper.setArmed(on) } }
                LedgerLine("Status", tb.status)
                tb.up?.let { up -> LedgerLine("4-hour trend", tb.line?.let { l ->
                    if (up) "up · line %.2f".format(Locale.ENGLISH, l) else "down · turns up on a close above %.2f".format(Locale.ENGLISH, l)
                } ?: if (up) "up" else "down", if (up) p.verdigris else p.oxblood) }
                if (tb.armed && tb.position == null) LedgerLine("Next decision", "about " + com.optionslab.app.data.GoldTrendPaper.nextDecision(GoldPaper.now()))
                LedgerLine("Last signal", tb.lastSignal ?: "none yet")
                tb.position?.let { pos ->
                    Rule(Modifier.padding(vertical = 6.dp))
                    LedgerLine("Bought", "%.2f at %s".format(Locale.ENGLISH, pos.entry, GoldPaper.when_(pos.entryTime)))
                    trendOpen?.let { LedgerLine("Open P&L", GoldPaper.usd(it), if (it >= 0) p.verdigris else p.oxblood) }
                    LedgerLine("Profit lock", tb.stop?.let { "sells below %.2f".format(Locale.ENGLISH, it) }
                        ?: "starts at %.2f (1 ATR up)".format(Locale.ENGLISH, pos.entry + pos.atr))
                }
                Note("Each 4-hour candle (closing 00:00, 04:00 ... 20:00 UTC) is decided about 10 minutes after it closes. Sells when a 4-hour candle closes " +
                    "with the trend down, or by the profit lock: once 1 ATR up, a fall of 4 ATRs from the top. After a lock sale it waits for the trend to turn down and up again.")
                Note("Backtest (three years, 1 lot, swap included): +$321.9k, 52 trades, 63% won, t 2.79, deepest drawdown -$39.2k - in years when gold rose about 140%. " +
                    "Its falls are deep for a small account: about $2,000 per 0.01 lot.")
            }
        }
    }
}

@Composable
private fun GoldTrades() {
    val p = LocalPalette.current
    val book by GoldPaper.book.collectAsState()
    val tb by com.optionslab.app.data.GoldTrendPaper.book.collectAsState()
    val trades = remember(book.trades, tb.trades) { (book.trades + tb.trades).sortedBy { it.exitTime } }
    val b = book.copy(trades = trades)
    val today = GoldPaper.now().toLocalDate()
    Page {
        item {
            LedgerCard(title = "Results") {
                fun sum(f: (LocalDate) -> Boolean) = b.trades.filter { f(it.exitTime.toLocalDate()) }
                listOf("Today" to sum { it == today }, "This month" to sum { it.year == today.year && it.month == today.month }, "All" to b.trades)
                    .forEach { (label, ts) ->
                        val x = ts.sumOf { it.pnl }
                        val won = if (ts.isEmpty()) "—" else "${Math.round(100.0 * ts.count { it.pnl > 0 } / ts.size)}% won"
                        LedgerLine("$label · ${ts.size} trades · $won", if (ts.isEmpty()) "—" else GoldPaper.usd(x),
                            if (ts.isEmpty()) null else if (x >= 0) p.verdigris else p.oxblood)
                    }
                if (tb.trades.isNotEmpty()) listOf("Liquidity 1h" to book.trades, com.optionslab.app.data.GoldTrendPaper.NAME to tb.trades).forEach { (arm, ts) ->
                    val x = ts.sumOf { it.pnl }
                    LedgerLine("$arm · ${ts.size} trades", if (ts.isEmpty()) "—" else GoldPaper.usd(x), if (ts.isEmpty()) null else if (x >= 0) p.verdigris else p.oxblood)
                }
                if (b.trades.size >= 2) {
                    // The paper balance after each closed trade, from the starting amount.
                    var run = b.start
                    val curve = listOf(b.start) + b.trades.map { run += it.pnl; run }
                    var peak = b.start; var dd = 0.0
                    curve.forEach { v -> peak = maxOf(peak, v); dd = minOf(dd, v - peak) }
                    Rule(Modifier.padding(vertical = 6.dp))
                    com.optionslab.app.ui.components.Sparkline(curve, if (curve.last() >= b.start) p.verdigris else p.oxblood,
                        Modifier.fillMaxWidth().height(56.dp).padding(vertical = 6.dp))
                    Note("The paper balance after each closed trade.")
                    LedgerLine("Best trade", GoldPaper.usd(b.trades.maxOf { it.pnl }), p.verdigris)
                    LedgerLine("Worst trade", GoldPaper.usd(b.trades.minOf { it.pnl }), p.oxblood)
                    LedgerLine("Deepest drawdown", GoldPaper.usd(dd), if (dd < 0) p.oxblood else null)
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
                        Note("${GoldPaper.arm(t)} · ${GoldPaper.when_(t.entryTime)} → ${GoldPaper.when_(t.exitTime)} · %.2f lot · ".format(Locale.ENGLISH, t.lots) + GoldPaper.label(t.why))
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
            LedgerCard(title = "Running in the background") { GoldBackgroundCheck(compact = false) }
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

/**
 * What the arm needs from the phone to decide every candle on time with the app closed: notifications (or the buys and
 * sells are silent), precise alarms (or Android runs the 5-minute check late, and a check more than 20 minutes after a
 * candle's prices arrive skips it) and no battery optimisation (or Android stops the checks). [compact]: only what is missing, on Home.
 */
@Composable
internal fun GoldBackgroundCheck(compact: Boolean) {
    val p = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read every 2 s while shown, so coming back from Settings updates it.
    var n by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); n++ } }
    val notif = remember(n) { com.optionslab.app.work.Notifier.canPost(context) }
    val exact = remember(n) { com.optionslab.app.work.Jobs.canExact(context) }
    val battery = remember(n) { BatteryCheck.unrestricted(context) }
    if (compact && notif && exact && battery) return
    if (!compact || !notif) LedgerLine("Notifications", if (notif) "allowed" else "blocked: buys and sells are silent", if (notif) p.verdigris else p.oxblood)
    if (!compact || !exact) LedgerLine("Precise alarms", if (exact) "allowed" else "off: candles can be missed", if (exact) p.verdigris else p.oxblood)
    if (!compact || !battery) LedgerLine("Battery saving", if (battery) "app left out" else "on: Android may stop the checks", if (battery) p.verdigris else p.oxblood)
    if (!exact) Note("Without precise alarms Android runs the background check late when the phone is idle; a check more than 20 minutes after a candle's prices arrive cannot buy it.")
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!notif) BrassButton("Allow notifications", Modifier.weight(1f)) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!exact && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) BrassButton("Allow alarms", Modifier.weight(1f)) {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (!battery) BrassButton("Battery", Modifier.weight(1f)) { BatteryCheck.ask(context) }
    }
}
