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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * so paper stays paper and live orders still need the review, the long press
 * and the PIN or fingerprint.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ChartScreen(model: AppModel, symbol: String, exchange: String, visible: Boolean = true, ask: Int = 0) {
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

    fun openOrder(buy: Boolean, price: Double?) {
        val (sym, _) = current
        scope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { ChartFeed.contract(sym) }.getOrNull() }
            if (c == null) { hint = "Indices cannot be traded. Search an option in the chart (e.g. NIFTY 24800 CE) to buy or sell it."; return@launch }
            order = ChainPick(c.underlying, c.expiry, c.strike, c.right, price, null, null, c.lotSize) to (buy to price)
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
    // Named apart from the WebView's own `settings`, which the factory below configures.
    val appSettings by model.settings.collectAsState()
    val streamStatus by com.optionslab.app.data.KiteStream.status.collectAsState()
    val streaming = appSettings.live && streamStatus == com.optionslab.app.data.KiteStream.Status.LIVE
    var liveToken by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current, streaming) {
        liveToken = if (!streaming) null else withContext(Dispatchers.IO) { runCatching { streamToken(current.first) }.getOrNull() }
        com.optionslab.app.data.KiteStream.want("chart", listOfNotNull(liveToken))
    }
    DisposableEffect(Unit) { onDispose { com.optionslab.app.data.KiteStream.want("chart", emptyList()) } }
    val tickVersion by com.optionslab.app.data.KiteStream.version.collectAsState()
    LaunchedEffect(tickVersion, liveToken, visible, ready) {
        val t = liveToken?.let { com.optionslab.app.data.KiteStream.tick(it) } ?: return@LaunchedEffect
        // Pre-open and after-close ticks would draw candles the exchange never had.
        if (!visible || !ready || !com.optionslab.app.data.Market.isOpen()) return@LaunchedEffect
        val at = t.exchangeTime ?: (System.currentTimeMillis() / 1000)
        holder[0]?.evaluateJavascript("window.__iraTick && window.__iraTick(${t.last}, $at)", null)
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
    val pineRev by com.optionslab.app.data.PineScripts.chartRev.collectAsState()
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
            holder[0]?.evaluateJavascript("window.__iraSetSymbol && window.__iraSetSymbol(${JSONObject.quote(symbol)}, ${JSONObject.quote(exchange)})", null)
        }
        onDispose { }
    }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        // Buy / Sell sit above the chart so nothing covers its time axis at the bottom.
        hint?.let {
            Text(it, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.fillMaxWidth().background(p.chip).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().background(p.paperDeep).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            returnTo?.let { (s0, e0) ->
                Text("‹ $s0", style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(p.chip, RoundedCornerShape(50)).clickable { returnTo = null; showSymbol(s0, e0) }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Text(current.first, style = Type.label.copy(color = p.ink, fontSize = 13.sp), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The option chain of the index: tap a price to chart that option (and buy or sell it there).
            if (chainUnderlying != null) Text("OPT", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(p.chip, RoundedCornerShape(50)).clickable { chainFor = chainUnderlying }.padding(horizontal = 10.dp, vertical = 8.dp))
            // Basic (drawn by the app) or Advanced (indicators, drawings; needs the phone's WebView).
            Text(if (basic) "BASIC" else "ADV", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(p.chip, RoundedCornerShape(50)).clickable {
                    if (basic) { basicChosen = false; autoBasic = null; com.optionslab.app.security.SecurePrefs.put("chart.basic", false); if (failed) { failed = false; retried = false; ready = false; gen++ } }
                    else { basicChosen = true; com.optionslab.app.security.SecurePrefs.put("chart.basic", true) }
                }.padding(horizontal = 10.dp, vertical = 8.dp))
            // A price alert on whatever is charted, at a level you choose.
            Text("ALERT", textAlign = TextAlign.Center, style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(p.chip, RoundedCornerShape(50)).clickable { alerting = true }.padding(horizontal = 10.dp, vertical = 8.dp))
            listOf(true to "BUY", false to "SELL").forEach { (isBuy, label) ->
                Text(label, textAlign = TextAlign.Center, style = Type.label.copy(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(if (isBuy) p.verdigris else p.oxblood, RoundedCornerShape(50))
                        .clickable { openOrder(isBuy, null) }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    addJavascriptInterface(Bridge(this, scope, onSymbol = { s, e -> current = s to e; hint = null },
                        onOrder = { buy, price -> openOrder(buy, price) }, onData = { if (holder[0] === this) { ready = true; failed = false; why = null } },
                        onPainted = { w, h, n -> if (holder[0] === this) {
                            painted = "${w}×${h}, $n bars"
                            paintedOk = w >= 50 && h >= 50
                        } },
                        onFail = { m -> if (holder[0] === this && !ready) { failed = true; why = m } },
                        onPageError = { m -> if (holder[0] === this) { ready = false; failed = true; why = m } }), "IraBridge")
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
                    loadUrl("${ORIGIN}terminal.html?symbol=${android.net.Uri.encode(symbol)}&exchange=${android.net.Uri.encode(exchange)}&theme=$theme")
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
            NativeChart(current.first, Modifier.weight(1f))
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
        }
    }
    if (alerting) ChartAlertDialog(model, current.first) { alerting = false }
    chainFor?.let { u ->
        ChartChainDialog(model, u, onClose = { chainFor = null }) { pick ->
            chainFor = null
            scope.launch {
                val sym = withContext(Dispatchers.IO) {
                    runCatching { com.optionslab.app.data.Market.contracts().firstOrNull { c ->
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
        OptionOrderSheet(model, pick, initialBuy = how.first, initialLimit = how.second) { order = null }
    }
}

/** Set a price alert on the charted symbol: above or below is decided from where the price is now. */
@Composable
private fun ChartAlertDialog(model: AppModel, symbol: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    var now by remember { mutableStateOf<Double?>(null) }
    var level by remember { mutableStateOf("") }
    LaunchedEffect(symbol) {
        val t = System.currentTimeMillis() / 1000
        now = withContext(Dispatchers.IO) { runCatching { ChartFeed.bars(symbol, "1m", t - 3 * 86400, t).lastOrNull()?.close }.getOrNull() }
        if (level.isEmpty()) now?.let { level = String.format(java.util.Locale.ENGLISH, "%.2f", it) }
    }
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
                    model.saveAlarm(com.optionslab.app.data.PriceAlarm(System.currentTimeMillis(), com.optionslab.app.data.PriceAlarm.CHART + symbol, lv >= cur, lv))
                    model.say("Alert set: $symbol at ${level}")
                    onClose()
                } else com.optionslab.app.work.Alerts.error("Enter a price above zero.")
            }) { Text("Set alert") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

/** The Zerodha instrument token for a chart symbol: an index by name, an option through the day's instrument list. */
private suspend fun streamToken(symbol: String): Long? {
    com.optionslab.app.data.Broker.indexToken(symbol.uppercase())?.let { return it }
    val c = ChartFeed.contract(symbol) ?: return null
    val list = com.optionslab.app.data.Broker.cachedInstruments() ?: return null
    return com.optionslab.app.data.Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token
}

private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** What the chart page may ask of the app. Each answer goes back through window.__iraReply. */
private class Bridge(
    private val web: WebView,
    private val scope: CoroutineScope,
    private val onSymbol: (String, String) -> Unit,
    private val onOrder: (Boolean, Double?) -> Unit,
    private val onData: () -> Unit = {},
    private val onFail: (String) -> Unit = {},
    private val onPageError: (String) -> Unit = {},
    private val onPainted: (Int, Int, Int) -> Unit = { _, _, _ -> },
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
                val bars = ChartFeed.bars(symbol, interval, from.toDoubleOrNull()?.toLong(), to.toDoubleOrNull()?.toLong())
                val a = JSONArray()
                bars.forEach { b ->
                    a.put(JSONObject().put("time", b.epochSecond).put("open", b.open).put("high", b.high)
                        .put("low", b.low).put("close", b.close).put("volume", b.volume))
                }
                reply(id, true, a.toString())
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
                ChartFeed.search(text).forEach { a.put(JSONObject().put("symbol", it.symbol).put("exchange", it.exchange).put("name", it.name)) }
                reply(id, true, a.toString())
            } catch (e: Exception) { reply(id, true, "[]") }
        }
    }

    @JavascriptInterface
    fun symbol(symbol: String, exchange: String) { web.post { onSymbol(symbol, exchange) } }

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
        val price = if (o.isNull("price") || o.optString("type") == "MARKET") null else o.optDouble("price").takeIf { !it.isNaN() }
        web.post { onOrder(buy, price) }
    }
}

/** The index's option chain over the chart: tap a CE or PE price to chart that option. */
@Composable
private fun ChartChainDialog(model: AppModel, underlying: String, onClose: () -> Unit, onPick: (ChainPick) -> Unit) {
    val p = LocalPalette.current
    val snap by model.tools.collectAsState()
    val source by model.toolsSource.collectAsState()
    LaunchedEffect(underlying) { model.loadTools(underlying) }
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
                        com.optionslab.app.ui.components.BrassButton("Try again", Modifier.fillMaxWidth()) { model.loadTools(underlying) }
                    }
                    is com.optionslab.app.ui.Load.Busy -> com.optionslab.app.ui.components.FullSpinner(l.label)
                    else -> com.optionslab.app.ui.components.FullSpinner("Pricing the $underlying chain")
                }
            }
        }
    }
}
