package com.optionslab.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import com.optionslab.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.LiquidityRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val HOST = "appassets.androidplatform.net"
private const val ORIGIN = "https://$HOST/"

/**
 * The Chart tab: the PC app's IraAlgo Charts terminal (toolbar, drawing rail,
 * indicators, chart types, long-press order menu) running on bundled files in
 * a WebView that can load nothing else and reach no network. Candles and
 * symbol search come from the app; Buy / Sell open the app's own order sheet,
 * so paper stays paper and live orders still need the review, the swipe
 * and the PIN or fingerprint.
 */
@Composable
fun ChartScreen(model: AppModel, symbol: String, exchange: String, visible: Boolean = true, ask: Int = 0) {
    // Named apart from the WebView's own `settings`, which the pane's factory configures.
    val appSettings by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    ChartPane(symbol, exchange, visible, ask, live = appSettings.live, source = FeedChartSource,
        orderSheet = { pick, buy, limit, close -> OptionOrderSheet(model, pick, initialBuy = buy, initialLimit = limit, area = "Chart", onClose = close) },
        alertDialog = { sym, close -> ChartAlertDialog(sym, FeedChartSource, onSave = { alarm, said -> model.saveAlarm(alarm); model.say(said) }, onClose = close) },
        chainDialog = { u, close, pick -> ChartChainDialog(model, u, close, pick) },
        liquidity = if (com.optionslab.app.BuildConfig.GOLD) null else ArmLiquiditySource,
        // The live order flow of the charted index (its future), in the toolbar; never in the gold build.
        flowChip = if (com.optionslab.app.BuildConfig.GOLD) null else FlowChipSlot,
        // A level's alert is one of the app's own price alarms (the Alarms page's store, the watch's minute check); the
        // gold build has no NSE watch to ring it, and no layer.
        levelAlarms = if (com.optionslab.app.BuildConfig.GOLD) null else remember(model) { StoreLevelAlarms(model.alarms) })
}

/**
 * Where the chart's candles, contracts and symbol search come from: [ChartFeed] and the day's
 * contract list in the app ([FeedChartSource]); a fake in tests, so nothing reaches the network.
 */
internal interface ChartSource {
    fun contract(symbol: String): com.optionslab.engine.Upstox.Contract?
    suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?): List<com.optionslab.engine.Upstox.Bar>
    fun search(text: String): List<ChartFeed.Match>
    /** Today's listed options (an option picked off the chain is charted by its trading symbol from here). */
    fun contracts(): List<com.optionslab.engine.Upstox.Contract>
    /** The Zerodha instrument token streamed for a chart symbol, or null. */
    suspend fun streamToken(symbol: String): Long?
}

internal object FeedChartSource : ChartSource {
    override fun contract(symbol: String) = ChartFeed.contract(symbol)
    override suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?) = ChartFeed.bars(symbol, interval, fromSec, toSec)
    override fun search(text: String) = ChartFeed.search(text)
    override fun contracts() = com.optionslab.app.data.Market.contracts()
    override suspend fun streamToken(symbol: String) = streamTokenOf(symbol)
}

/**
 * The Chart tab from plain values, a [source] and dialog slots (what [ChartScreen] shows; tests drive it,
 * and the page's IraBridge, without an [AppModel] or the network). [live]: Live mode is on.
 * [orderSheet]: (contract picked, buy, limit price or null for market, close).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChartPane(
    symbol: String,
    exchange: String,
    visible: Boolean,
    ask: Int,
    live: Boolean,
    source: ChartSource,
    orderSheet: @Composable (ChainPick, Boolean, Double?, () -> Unit) -> Unit,
    alertDialog: @Composable (String, () -> Unit) -> Unit,
    chainDialog: @Composable (String, () -> Unit, (ChainPick) -> Unit) -> Unit,
    trading: Boolean = true,
    marketOpen: () -> Boolean = { com.optionslab.app.data.Market.isOpen() },
    /** Liquidity 15+5's layer on BANKNIFTY / FINNIFTY / MIDCPNIFTY ([LiquidityPanel]); null: none (the gold build, most tests). */
    liquidity: LiquiditySource? = null,
    /** The price alarms a tapped liquidity level sets and lists ([LevelSheet]); null: the level's sheet has no alert button. */
    levelAlarms: LevelAlarms? = null,
    /** The order flow's chip for the charted index ([OrderFlowChip]); null: none (the gold build, most tests). */
    flowChip: (@Composable (String, Modifier) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(symbol to exchange) }
    var order by remember { mutableStateOf<Pair<ChainPick, Pair<Boolean, Double?>>?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var alerting by remember { mutableStateOf(false) }
    // The option chain over the chart ([chainFor] = its underlying), and the index chart to go back to
    // after an option was opened from it.
    var chainFor by remember { mutableStateOf<String?>(null) }
    var returnTo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val holder = remember { arrayOfNulls<WebView>(1) }
    // [gen] rebuilds the WebView (after its renderer died, or a load that never finished);
    // [ready] turns true once the chart has received its first candles.
    var gen by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }   // the load's own error, shown on the cover
    // The basic chart (drawn by the app, no WebView): chosen by the owner, or used automatically
    // when the advanced chart reports it could not draw on this phone.
    var basicChosen by remember { mutableStateOf(com.optionslab.app.security.SecurePrefs.getBoolean("chart.basic", false)) }
    // The WebView drawing mode (hardware / software).
    var softwareLayer by remember { mutableStateOf(com.optionslab.app.security.SecurePrefs.getBoolean("chart.sw", false)) }
    var painted by remember { mutableStateOf<String?>(null) }        // "w×h, n bars" once the web chart drew
    var paintedOk by remember { mutableStateOf(false) }
    var autoBasic by remember { mutableStateOf<String?>(null) }      // why the basic chart was switched in
    val basic = basicChosen || autoBasic != null
    // Liquidity 15+5's levels and trades on the index it runs on: on unless the owner switched them off (remembered).
    var liqOn by remember { mutableStateOf(com.optionslab.app.security.SecurePrefs.getBoolean("chart.liq", true)) }
    val liqCache = remember { LiquidityCache() }
    val liqUnderlying = current.first.uppercase().takeIf { liquidity != null && it in LiquidityRules.UNDERLYINGS }
    // A charted index option gets its own tape and heatmap in the order-flow detail (display only; off the main thread).
    LaunchedEffect(current.first, flowChip != null) {
        if (flowChip != null) withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.OrderFlowLive.watchSymbol(current.first) } }
    }

    fun openOrder(buy: Boolean, price: Double?, type: String = if (price == null) "MARKET" else "LIMIT") {
        // IraGoldAlgo: paper only, its strategy buys by itself; the chart can place nothing.
        if (!trading) { hint = "Paper only: the strategy buys and sells by itself. Nothing can be ordered from the chart."; return }
        // A stop entry would become a LIMIT at the stop level here and fill at once: refused, not converted.
        if (type != "MARKET" && type != "LIMIT") {
            hint = "Stop entries cannot be placed from the chart. Use BUY / SELL for a market or limit order."
            return
        }
        val (sym, _) = current
        // On the main thread, not the composition's dispatcher: under the UI tests' unconfined dispatcher the code after
        // withContext(IO) ran on the IO thread, and the hint written there recomposed and laid out the views off the main
        // thread (CalledFromWrongThreadException in ChartScreensLayoutTest.chartIndexBuyHint). Same as 05aa5cc's screens.
        scope.launch(Dispatchers.Main.immediate) {
            val c = withContext(Dispatchers.IO) { runCatching { source.contract(sym) }.getOrNull() }
            if (c == null) { hint = "Indices cannot be traded. Search an option in the chart (e.g. NIFTY 24800 CE) to buy or sell it."; return@launch }
            // The sheet's LTP is the last traded price, not the level under the finger.
            val t = System.currentTimeMillis() / 1000
            val last = withContext(Dispatchers.IO) { runCatching { source.bars(sym, "1m", t - 3 * 86400, t).lastOrNull()?.close }.getOrNull() }
            order = ChainPick(c.underlying, c.expiry, c.strike, c.right, last, null, null, c.lotSize) to (buy to price)
        }
    }

    // Kept loaded between tabs. While hidden it is switched off entirely - not drawn, not
    // rendering, not polling - so it can never paint over another page.
    DisposableEffect(visible) {
        holder[0]?.let { w ->
            w.evaluateJavascript("window.__iraPause && window.__iraPause(${!visible})", null)
            w.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            if (visible) w.onResume() else w.onPause()
        }
        onDispose { }
    }

    // Live mode: every trade of the charted instrument comes from the Zerodha stream and moves the
    // last candle at once (the chart page's own 15 s poll stands back while ticks arrive).
    val streamStatus by com.optionslab.app.data.KiteStream.status.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val streaming = live && streamStatus == com.optionslab.app.data.KiteStream.Status.LIVE
    var liveToken by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current, streaming) { withContext(Dispatchers.Main.immediate) {
        liveToken = if (!streaming) null else withContext(Dispatchers.IO) { runCatching { source.streamToken(current.first) }.getOrNull() }
        com.optionslab.app.data.KiteStream.want("chart", listOfNotNull(liveToken))
    } }
    DisposableEffect(Unit) { onDispose { com.optionslab.app.data.KiteStream.want("chart", emptyList()) } }
    // Speed, round 2: the stream's version (every 500 ms) is followed inside the effect, not read by the screen, so a
    // tick no longer recomposes the whole chart page (kept alive off screen too); each version moves the candle as before.
    LaunchedEffect(liveToken, visible, ready) {
        val token = liveToken ?: return@LaunchedEffect
        if (!visible || !ready) return@LaunchedEffect
        com.optionslab.app.data.KiteStream.version.collect {
            val t = com.optionslab.app.data.KiteStream.tick(token) ?: return@collect
            // Pre-open and after-close ticks would draw candles the exchange never had.
            if (!com.optionslab.app.data.Market.isOpen()) return@collect
            val at = t.exchangeTime ?: (System.currentTimeMillis() / 1000)
            holder[0]?.evaluateJavascript("window.__iraTick && window.__iraTick(${t.last}, $at)", null)
        }
    }

    // Data arrived but the page never said it drew: this phone's WebView is not drawing it.
    LaunchedEffect(ready, visible, gen) {
        if (!ready || !visible || paintedOk) return@LaunchedEffect
        kotlinx.coroutines.delay(8_000)
        if (!paintedOk) autoBasic = "The advanced chart could not draw on this phone."
    }
    // The page failed outright after the retry: the basic chart instead of an error.
    LaunchedEffect(failed) { if (failed && autoBasic == null) autoBasic = why ?: "The advanced chart could not load." }

    // A chart that has not drawn its first candles in 12 s is rebuilt once; after that, say so.
    LaunchedEffect(gen, visible) {
        if (!visible || ready) return@LaunchedEffect
        kotlinx.coroutines.delay(12_000)
        if (ready) return@LaunchedEffect
        if (!retried) { retried = true; ready = false; gen++ }
        else { failed = true; com.optionslab.app.work.Alerts.error("The chart could not load. Check the connection and tap Retry.") }
    }

    // Pine scripts changed in the app (code, shown on the chart, inputs): the page redraws them.
    val pineRev by com.optionslab.app.data.PineScripts.chartRev.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    LaunchedEffect(pineRev, ready, gen) {
        if (ready) holder[0]?.evaluateJavascript("window.__iraPine && window.__iraPine()", null)
    }

    fun showSymbol(sym: String, ex: String) {
        current = sym to ex; hint = null
        holder[0]?.evaluateJavascript("window.__iraSetSymbol && window.__iraSetSymbol(${JSONObject.quote(sym)}, ${JSONObject.quote(ex)})", null)
    }
    // Back from an option opened off the chain: the index chart again.
    androidx.activity.compose.BackHandler(enabled = visible && returnTo != null) {
        returnTo?.let { (s0, e0) -> returnTo = null; showSymbol(s0, e0) }
    }
    // The underlying whose chain the OPT button shows: the index charted, or the option's own index.
    val chainUnderlying = current.first.uppercase().let { u ->
        when {
            u == "NIFTY" || u == "BANKNIFTY" -> u
            u.startsWith("BANKNIFTY") -> "BANKNIFTY"
            u.startsWith("NIFTY") -> "NIFTY"
            else -> null
        }
    }

    // A new symbol asked for from elsewhere (Home, the option chain) while the chart is open.
    DisposableEffect(symbol, exchange, ask) {
        if (current != symbol to exchange) {
            returnTo = null
            // Set here too: when the web chart has failed, the page never reports the change back.
            current = symbol to exchange; hint = null
            holder[0]?.evaluateJavascript("window.__iraSetSymbol && window.__iraSetSymbol(${JSONObject.quote(symbol)}, ${JSONObject.quote(exchange)})", null)
        }
        onDispose { }
    }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        // Buy / Sell sit above the chart so nothing covers its time axis at the bottom.
        hint?.let {
            Text(it, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.fillMaxWidth().background(p.chip).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // One line when it fits; at a large font or on a narrow screen the chips flow onto a second line under the
        // symbol (in one row the symbol was ellipsized and SELL squeezed to nothing).
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().background(p.paperDeep).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val chip = Modifier.align(Alignment.CenterVertically).background(p.chip, RoundedCornerShape(50))
            returnTo?.let { (s0, e0) ->
                Text("‹ $s0", style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = chip.clickable { returnTo = null; showSymbol(s0, e0) }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
            // The symbol takes what is left of its line, and shrinks rather than being cut.
            com.optionslab.app.ui.components.FitText(current.first, Type.label.copy(color = p.ink, fontSize = 13.sp),
                Modifier.weight(1f).align(Alignment.CenterVertically), minSize = 9.sp)
            // The option chain of the index: tap a price to chart that option (and buy or sell it there).
            if (trading && chainUnderlying != null) Text("OPT", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = chip.clickable { chainFor = chainUnderlying }.padding(horizontal = 10.dp, vertical = 8.dp))
            // The live order flow of the charted index (its future): a tap opens its parts and its last 30 minutes.
            val flowUnderlying = flowUnderlyingOf(current.first)
            if (flowChip != null && flowUnderlying != null) flowChip(flowUnderlying, Modifier.align(Alignment.CenterVertically))
            // Basic (drawn by the app) or Advanced (indicators, drawings; needs the phone's WebView).
            Text(if (basic) "BASIC" else "ADV", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = chip.clickable {
                    if (basic) { basicChosen = false; autoBasic = null; com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("chart.basic" to false)); if (failed) { failed = false; retried = false; ready = false; gen++ } }
                    else { basicChosen = true; com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("chart.basic" to true)) }
                }.padding(horizontal = 10.dp, vertical = 8.dp))
            // The Liquidity 15+5 layer under the chart (beside it in landscape), on the two indices it trades.
            if (liqUnderlying != null) Box(Modifier.align(Alignment.CenterVertically).heightIn(min = 48.dp)
                .toggleable(value = liqOn, role = androidx.compose.ui.semantics.Role.Switch) {
                    liqOn = it; com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("chart.liq" to it))
                }, contentAlignment = Alignment.Center) {
                Text(if (liqOn) "✓ Liquidity levels" else "Liquidity levels", textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    style = Type.label.copy(color = if (liqOn) p.onPrimary else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(if (liqOn) p.brass else p.chip, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 8.dp))
            }
            // A price alert on whatever is charted, at a level you choose.
            if (trading) Text("ALERT", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = chip.clickable { alerting = true }.padding(horizontal = 10.dp, vertical = 8.dp))
            // Buy / Sell: a full 48 dp touch target each, the label whole on one line; one unit, so when the chips
            // wrap they move to the next line together (SELL alone on a line of its own looked like another toolbar).
            if (trading) Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "BUY", false to "SELL").forEach { (isBuy, label) ->
                    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp)
                        .background(if (isBuy) p.verdigris else p.oxblood, RoundedCornerShape(50))
                        .clickable { openOrder(isBuy, null) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(label, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                            style = Type.label.copy(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
        // The chart, and under it (beside it in landscape) the Liquidity 15+5 layer when shown.
        val chartArea: @Composable (Modifier) -> Unit = { area -> Box(area) {
        androidx.compose.runtime.key(gen) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            update = { w -> w.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE },
            factory = { ctx ->
                WebView.setWebContentsDebuggingEnabled(false)
                WebView(ctx).apply {
                    holder[0] = this
                    // Fill the space Compose gives it. Without MATCH_PARENT the WebView sizes its viewport to its
                    // content, and the chart sizes itself to the viewport: both settle at 0 px high (seen as "drawn 411×0").
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true            // the chart keeps its layout and drawings
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // The phone's font size would otherwise enlarge every label and push the time axis off the bottom.
                    settings.textZoom = 100
                    setBackgroundColor(if (p.dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    // Some phones show a blank WebView inside Compose with hardware drawing; software drawing is the fallback.
                    setLayerType(if (softwareLayer) android.view.View.LAYER_TYPE_SOFTWARE else android.view.View.LAYER_TYPE_HARDWARE, null)
                    addJavascriptInterface(Bridge(this, scope, source, onSymbol = { s, e -> current = s to e; hint = null },
                        onOrder = { buy, price, type -> openOrder(buy, price, type) }, onData = { if (holder[0] === this) { ready = true; failed = false; why = null } },
                        onPainted = { w, h, n -> if (holder[0] === this) {
                            painted = "${w}×${h}, $n bars"
                            paintedOk = w >= 50 && h >= 50
                        } },
                        onFail = { m -> if (holder[0] === this && !ready) { failed = true; why = m } },
                        // Only fatal before the first candles: one runtime error later must not drop the chart for the session.
                        onPageError = { m -> if (holder[0] === this && !ready) { failed = true; why = m } },
                        // The page's own 5-minute candles feed the liquidity layer, so it reads nothing more while they are fresh.
                        onBars = { s, iv, list -> val clock = liquidity; if (iv == "5m" && clock != null) liqCache.offer(s, list, clock.now()) }), "IraBridge")
                    // A script error in the chart page shows as a red alert (the bundled chart only; no account data).
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                                com.optionslab.app.work.Alerts.post("Chart: ${m.message().take(160)}", com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) com.optionslab.app.work.Alerts.post("Chart page did not load: ${error.description}",
                                com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
                        }

                        // Only the bundled chart files are served; every other request is refused.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val u = request.url
                            if (u.scheme == "https" && u.host == HOST) {
                                val path = u.path.orEmpty().trimStart('/')
                                if (path.matches(Regex("[A-Za-z0-9._-]+")) ) {
                                    val mime = when (path.substringAfterLast('.')) {
                                        "html" -> "text/html"; "mjs", "js" -> "text/javascript"; "css" -> "text/css"; else -> "text/plain"
                                    }
                                    return runCatching { WebResourceResponse(mime, "utf-8", view.context.assets.open("chart/$path")) }
                                        .getOrElse { refused() }
                                }
                            }
                            return refused()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        // Android killed the chart's renderer (memory): rebuild it instead of leaving a white page.
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            if (holder[0] === view) holder[0] = null
                            view.post { ready = false; gen++ }
                            return true
                        }
                    }
                    val theme = if (p.dark) "dark" else "light"
                    // What is on screen now, not the symbol the tab opened with: a rebuild keeps an option picked since.
                    val (sym, ex) = current
                    loadUrl("${ORIGIN}terminal.html?symbol=${android.net.Uri.encode(sym)}&exchange=${android.net.Uri.encode(ex)}&theme=$theme")
                }
            },
            onRelease = { w -> if (holder[0] === w) holder[0] = null; w.removeJavascriptInterface("IraBridge"); w.stopLoading(); w.destroy() },
        )
        }
        // The basic chart, drawn by the app: over the web chart when chosen or when that cannot draw.
        if (basic) Column(Modifier.fillMaxSize().background(p.paper)) {
            autoBasic?.let {
                Text("$it Showing the basic chart. Tap ADV / BASIC above to try the advanced one again.",
                    style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().background(p.chip).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // The order flow's optional lines (POC/VA, VWAP; off until tapped) on an index the flow follows; none elsewhere.
            val flowLevels by com.optionslab.app.data.OrderFlowLive.chartLevels.collectAsState(Dispatchers.Main.immediate)
            val lines = if (flowChip == null || !com.optionslab.app.data.ChartFeed.isIndex(current.first)) null
                else flowUnderlyingOf(current.first)?.let { flowLevels[it].orEmpty() }
            NativeChart(current.first, Modifier.weight(1f), visible, open = marketOpen, levels = lines) { s, iv -> source.bars(s, iv, null, null) }
        }
        // Covers the blank page until the first candles are drawn, so the chart never shows as a white sheet.
        if (!basic && !ready) Box(Modifier.fillMaxSize().background(p.paper), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (failed) (why ?: "The chart could not load") else "Loading chart…", style = Type.bodySmall.copy(color = p.inkSoft))
                if (failed) Text("Retry", style = Type.label.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 10.dp).background(p.chip, RoundedCornerShape(50))
                        .clickable { failed = false; retried = false; ready = false; gen++ }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
        } }
        val liqPanel: @Composable (Modifier) -> Unit = { area ->
            val u = liqUnderlying
            if (u != null && liqOn && liquidity != null) LiquidityPanel(u, visible, bars = { now ->
                liqCache.fresh(u, now) ?: source.bars(u, "5m", null, null).also { liqCache.offer(u, it, liquidity.now(), history = true) }
            }, source = liquidity, modifier = area, levelAlarms = levelAlarms, lastPrice = {
                // The last traded price, as the chart's own alert reads it (the 1-minute feed).
                val t = System.currentTimeMillis() / 1000
                source.bars(u, "1m", t - 3 * 86400, t).lastOrNull()?.close
            })
        }
        // The chart stays at one place in the composition whatever the layer does, so the WebView is never rebuilt: not
        // when the layer is switched on or off, and not when the keyboard shrinks the height (a Row / Column chosen from
        // the space left moved the chart between parents, which destroyed and reloaded the page). Beside it only in a
        // landscape orientation.
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (liquidity == null) chartArea(Modifier.weight(1f).fillMaxWidth())
        else androidx.compose.ui.layout.Layout(content = {
            chartArea(Modifier)
            liqPanel(Modifier)
        }, modifier = Modifier.weight(1f).fillMaxWidth()) { parts, c ->
            val w = c.maxWidth; val h = c.maxHeight
            fun fixed(cw: Int, ch: Int) = androidx.compose.ui.unit.Constraints.fixed(cw, ch)
            if (parts.size < 2) {
                val chart = parts.firstOrNull()?.measure(fixed(w, h))
                layout(w, h) { chart?.place(0, 0) }
            } else if (landscape) {
                val cw = w / 2
                val chart = parts[0].measure(fixed(cw, h)); val layer = parts[1].measure(fixed(w - cw, h))
                layout(w, h) { chart.place(0, 0); layer.place(cw, 0) }
            } else {
                // The chart over the layer, 1 : 0.9.
                val ch = (h / 1.9f).toInt()
                val chart = parts[0].measure(fixed(w, ch)); val layer = parts[1].measure(fixed(w, h - ch))
                layout(w, h) { chart.place(0, 0); layer.place(0, ch) }
            }
        }
    }
    if (alerting) alertDialog(current.first) { alerting = false }
    chainFor?.let { u ->
        chainDialog(u, { chainFor = null }) { pick ->
            chainFor = null
            scope.launch(Dispatchers.Main.immediate) {
                val sym = withContext(Dispatchers.IO) {
                    runCatching { source.contracts().firstOrNull { c ->
                        c.underlying == pick.underlying && c.expiry == pick.expiry && c.strike == pick.strike && c.right == pick.right }?.tradingSymbol }.getOrNull()
                }
                if (sym == null) { hint = "That option is not in today's contract list; try again in a moment."; return@launch }
                // Back (the ‹ chip or the phone's back) returns to the index chart.
                if (returnTo == null) returnTo = u to "NSE"
                showSymbol(sym, "NFO")
            }
        }
    }
    order?.let { (pick, how) ->
        orderSheet(pick, how.first, how.second) { order = null }
    }
}

/** Set a price alert on the charted symbol: above or below is decided from where the price is now. */
@Composable
internal fun ChartAlertDialog(symbol: String, source: ChartSource, onSave: (com.optionslab.app.data.PriceAlarm, String) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var now by remember { mutableStateOf<Double?>(null) }
    var level by remember { mutableStateOf("") }
    LaunchedEffect(symbol) { withContext(Dispatchers.Main.immediate) {
        val t = System.currentTimeMillis() / 1000
        now = withContext(Dispatchers.IO) { runCatching { source.bars(symbol, "1m", t - 3 * 86400, t).lastOrNull()?.close }.getOrNull() }
        if (level.isEmpty()) now?.let { level = String.format(java.util.Locale.ENGLISH, "%.2f", it) }
    } }
    val lv = level.toDoubleOrNull()
    val cur = now
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Alert on $symbol", style = Type.title) },
        text = {
            Column {
                Text(cur?.let { "Now ${String.format(java.util.Locale.ENGLISH, "%,.2f", it)}" } ?: "Reading the price…", style = Type.bodySmall.copy(color = p.inkSoft))
                PriceField(level, { level = it }, "Alert when the price reaches")
                if (lv != null && cur != null) Text(if (lv >= cur) "Fires when it rises to ${level}" else "Fires when it falls to ${level}",
                    style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(top = 6.dp))
                Text("Checked by the market watch every minute, on market days.", style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton({
                if (lv != null && lv > 0 && cur != null) {
                    onSave(com.optionslab.app.data.PriceAlarm(System.currentTimeMillis(), com.optionslab.app.data.PriceAlarm.CHART + symbol, lv >= cur, lv), "Alert set: $symbol at ${level}")
                    onClose()
                } else com.optionslab.app.work.Alerts.error("Enter a price above zero.")
            }) { Text("Set alert") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

/** The Zerodha instrument token for a chart symbol: an index by name, an option through the day's instrument list. */
private suspend fun streamTokenOf(symbol: String): Long? {
    com.optionslab.app.data.Broker.indexToken(symbol.uppercase())?.let { return it }
    // MCX (9 Oct): its token from the day's MCX list (futures too).
    com.optionslab.app.data.McxMarket.find(symbol)?.let { return it.token.takeIf { t -> t > 0 } }
    val c = ChartFeed.contract(symbol) ?: return null
    val list = com.optionslab.app.data.Broker.cachedInstruments() ?: return null
    return com.optionslab.app.data.Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token
}

private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** What the chart page may ask of the app. Each answer goes back through window.__iraReply. (Internal: tests call it as the page would.) */
internal class Bridge(
    private val web: WebView,
    private val scope: CoroutineScope,
    private val source: ChartSource,
    private val onSymbol: (String, String) -> Unit,
    private val onOrder: (Boolean, Double?, String) -> Unit,
    private val onData: () -> Unit = {},
    private val onFail: (String) -> Unit = {},
    private val onPageError: (String) -> Unit = {},
    private val onPainted: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** Every batch of candles the page was given (symbol, interval, candles), on the reading thread. */
    private val onBars: (String, String, List<com.optionslab.engine.Upstox.Bar>) -> Unit = { _, _, _ -> },
) {
    /** terminal.mjs: the chart drew its first candles at this size. */
    @JavascriptInterface
    fun painted(w: Int, h: Int, bars: Int) { web.post { onPainted(w, h, bars) } }

    /** boot.js: the chart page itself failed (a script error, or it never started). */
    @JavascriptInterface
    fun fail(message: String) {
        web.post { onPageError(message.take(300)) }
    }

    private fun reply(id: String, ok: Boolean, payload: String) {
        web.post { web.evaluateJavascript("window.__iraReply(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})", null) }
    }

    @JavascriptInterface
    fun bars(id: String, symbol: String, exchange: String, interval: String, from: String, to: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bars = source.bars(symbol, interval, from.toDoubleOrNull()?.toLong(), to.toDoubleOrNull()?.toLong())
                val a = JSONArray()
                bars.forEach { b ->
                    a.put(JSONObject().put("time", b.epochSecond).put("open", b.open).put("high", b.high)
                        .put("low", b.low).put("close", b.close).put("volume", b.volume))
                }
                reply(id, true, a.toString())
                runCatching { onBars(symbol, interval, bars) }
                web.post { onData() }
            } catch (e: Exception) {
                // Say why on the cover at once, instead of waiting for the watchdog to give up.
                val m = "Could not load $symbol: ${e.message ?: "no data"}"
                reply(id, false, m)
                web.post { onFail(m) }
            }
        }
    }

    @JavascriptInterface
    fun search(id: String, text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val a = JSONArray()
                source.search(text).forEach { a.put(JSONObject().put("symbol", it.symbol).put("exchange", it.exchange).put("name", it.name)) }
                reply(id, true, a.toString())
            } catch (e: Exception) { reply(id, true, "[]") }
        }
    }

    @JavascriptInterface
    fun symbol(symbol: String, exchange: String) { web.post { onSymbol(symbol, exchange) } }

    /**
     * terminal.mjs: the order flow's lines for [symbol] (an index the flow follows; "[]" otherwise) as JSON
     * [{"label","price","kind"}] - the "Ira:" indicators in the chart's indicator menu, off until Boss adds one. Memory only.
     */
    @JavascriptInterface
    fun flowLevels(symbol: String): String = runCatching {
        if (!com.optionslab.app.data.ChartFeed.isIndex(symbol)) return@runCatching "[]"
        val u = flowUnderlyingOf(symbol) ?: return@runCatching "[]"
        val arr = JSONArray()
        for (l in com.optionslab.app.data.OrderFlowLive.chartLevels.value[u].orEmpty())
            arr.put(JSONObject().put("label", l.label).put("price", l.price).put("kind", l.kind.name))
        arr.toString()
    }.getOrDefault("[]")

    /** terminal.mjs: the owner's Pine scripts, registered as indicators. */
    @JavascriptInterface
    fun pineList(): String = runCatching { com.optionslab.app.data.PineChart.list() }.getOrDefault("[]")

    /** terminal.mjs: one Pine script run over the chart's candles. */
    @JavascriptInterface
    fun pineCalc(id: String, symbol: String, interval: String, bars: String, inputs: String): String =
        com.optionslab.app.data.PineChart.calc(id, symbol, interval, bars, inputs)

    /** A long-press menu order from the chart: side and, for limit/stop, the price under the finger. */
    @JavascriptInterface
    fun order(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val buy = o.optString("side") == "BUY"
        val type = o.optString("type").uppercase().ifEmpty { "MARKET" }
        val price = if (o.isNull("price") || type == "MARKET") null else o.optDouble("price").takeIf { !it.isNaN() }
        // A LIMIT without a price would open as a market order: dropped instead.
        if (type == "LIMIT" && price == null) return
        web.post { onOrder(buy, price, type) }
    }
}

/** The index's option chain over the chart: tap a CE or PE price to chart that option. */
@Composable
private fun ChartChainDialog(model: AppModel, underlying: String, onClose: () -> Unit, onPick: (ChainPick) -> Unit) {
    val snap by model.tools.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val source by model.toolsSource.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    LaunchedEffect(underlying) { model.loadTools(underlying) }
    ChartChainContent(underlying, snap, source, onRetry = { model.loadTools(underlying) }, onClose = onClose, onPick = onPick)
}

/** The chain dialog from its state (what [ChartChainDialog] shows; tests drive it without an [AppModel]). [source]: where the prices came from. */
@Composable
internal fun ChartChainContent(
    underlying: String,
    snap: com.optionslab.app.ui.Load<com.optionslab.engine.options.ChainSnapshot>,
    source: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onPick: (ChainPick) -> Unit,
) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.86f)
            .background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$underlying options", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            if (source.isNotBlank()) Text(source, style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (val l = snap) {
                    is com.optionslab.app.ui.Load.Done -> if (l.value.underlying == underlying) {
                        val c = l.value
                        Text("Expiry ${c.expiry} · spot ${String.format(java.util.Locale.ENGLISH, "%,.2f", c.spot)} · lot ${c.lotSize}",
                            style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(vertical = 6.dp))
                        ChainCard(c, onPick)
                    } else com.optionslab.app.ui.components.FullSpinner("Pricing the $underlying chain")
                    is com.optionslab.app.ui.Load.Failed -> {
                        com.optionslab.app.ui.components.Note(l.why)
                        com.optionslab.app.ui.components.BrassButton("Try again", Modifier.fillMaxWidth(), onClick = onRetry)
                    }
                    is com.optionslab.app.ui.Load.Busy -> com.optionslab.app.ui.components.FullSpinner(l.label)
                    else -> com.optionslab.app.ui.components.FullSpinner("Pricing the $underlying chain")
                }
            }
        }
    }
}
