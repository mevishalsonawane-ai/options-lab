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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
fun ChartScreen(model: AppModel, symbol: String, exchange: String, visible: Boolean = true) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(symbol to exchange) }
    var order by remember { mutableStateOf<Pair<ChainPick, Pair<Boolean, Double?>>?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val holder = remember { arrayOfNulls<WebView>(1) }

    fun openOrder(buy: Boolean, price: Double?) {
        val (sym, _) = current
        scope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { ChartFeed.contract(sym) }.getOrNull() }
            if (c == null) { hint = "Indices cannot be traded. Search an option in the chart (e.g. NIFTY 24800 CE) to buy or sell it."; return@launch }
            order = ChainPick(c.underlying, c.expiry, c.strike, c.right, price, null, null, c.lotSize) to (buy to price)
        }
    }

    // Kept loaded between tabs; it stops polling while hidden.
    DisposableEffect(visible) {
        holder[0]?.evaluateJavascript("window.__iraPause && window.__iraPause(${!visible})", null)
        onDispose { }
    }

    // A new symbol asked for from elsewhere (Home, the option chain) while the chart is open.
    DisposableEffect(symbol, exchange) {
        if (current != symbol to exchange) {
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
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(current.first, style = Type.label.copy(color = p.ink, fontSize = 13.sp), maxLines = 1, modifier = Modifier.weight(1f))
            listOf(true to "BUY", false to "SELL").forEach { (isBuy, label) ->
                Text(label, textAlign = TextAlign.Center, style = Type.label.copy(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(if (isBuy) p.verdigris else p.oxblood, RoundedCornerShape(50))
                        .clickable { openOrder(isBuy, null) }.padding(horizontal = 22.dp, vertical = 8.dp))
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView.setWebContentsDebuggingEnabled(false)
                WebView(ctx).apply {
                    holder[0] = this
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
                    addJavascriptInterface(Bridge(this, scope, onSymbol = { s, e -> current = s to e; hint = null },
                        onOrder = { buy, price -> openOrder(buy, price) }), "IraBridge")
                    webViewClient = object : WebViewClient() {
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
                    }
                    val theme = if (p.dark) "dark" else "light"
                    loadUrl("${ORIGIN}terminal.html?symbol=${android.net.Uri.encode(symbol)}&exchange=${android.net.Uri.encode(exchange)}&theme=$theme")
                }
            },
            onRelease = { w -> holder[0] = null; w.removeJavascriptInterface("IraBridge"); w.stopLoading(); w.destroy() },
        )
    }
    order?.let { (pick, how) ->
        OptionOrderSheet(model, pick, initialBuy = how.first, initialLimit = how.second) { order = null }
    }
}

private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** What the chart page may ask of the app. Each answer goes back through window.__iraReply. */
private class Bridge(
    private val web: WebView,
    private val scope: CoroutineScope,
    private val onSymbol: (String, String) -> Unit,
    private val onOrder: (Boolean, Double?) -> Unit,
) {
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
            } catch (e: Exception) {
                reply(id, false, "Could not load $symbol: ${e.message ?: "no data"}")
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

    /** A long-press menu order from the chart: side and, for limit/stop, the price under the finger. */
    @JavascriptInterface
    fun order(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val buy = o.optString("side") == "BUY"
        val price = if (o.isNull("price") || o.optString("type") == "MARKET") null else o.optDouble("price").takeIf { !it.isNaN() }
        web.post { onOrder(buy, price) }
    }
}
