package com.optionslab.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.security.BiometricGate
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.darken
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val secureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

// ---- the Zerodha login page ------------------------------------------------------------

/**
 * Kite's own login page, inside the app. It may only show https pages on
 * zerodha.com; the registered redirect is caught BEFORE it loads (so even an
 * http://127.0.0.1 redirect never touches the network), and on the way out
 * every cookie, cache entry and form value the page left is wiped - the app
 * keeps only the day's access token, in the vault.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KiteLoginPage(model: AppModel) {
    val p = LocalPalette.current
    var loading by remember { mutableStateOf(true) }
    var blocked by remember { mutableStateOf<String?>(null) }
    BackHandler { model.closeKiteLogin() }
    Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ZERODHA LOGIN", style = Type.title.copy(color = p.ink))
                Text(blocked ?: if (loading) "Loading kite.zerodha.com…" else "Log in with your Zerodha ID, password and TOTP", style = Type.italic.copy(color = if (blocked != null) p.oxblood else p.inkSoft, fontSize = 13.sp))
            }
            BrassButton("Close", tone = p.inkFaint) { model.closeKiteLogin() }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView.setWebContentsDebuggingEnabled(false)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.safeBrowsingEnabled = true
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    isSaveEnabled = false
                    // No autofill service gets to save the Zerodha password.
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    webViewClient = object : WebViewClient() {
                        private fun guard(view: WebView, url: String): Boolean {
                            if (model.onKiteNavigation(url)) { view.stopLoading(); return true }
                            val u = android.net.Uri.parse(url)
                            val host = u.host ?: ""
                            val ok = u.scheme == "https" && (host == "zerodha.com" || host.endsWith(".zerodha.com"))
                            if (!ok) { blocked = "Blocked a page outside zerodha.com"; view.stopLoading(); return true }
                            return false
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            guard(view, request.url.toString())

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (guard(view, url)) return
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String) { loading = false }
                    }
                    // Start from nothing: a session left behind by a killed process must not be reused.
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); loadUrl(Broker.loginUrl()) }
                }
            },
            onRelease = { w ->
                w.stopLoading()
                w.clearHistory()
                w.clearCache(true)
                w.clearFormData()
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                android.webkit.WebStorage.getInstance().deleteAllData()
                w.destroy()
            },
        )
    }
}

/**
 * Before each Zerodha login: the API secret is sealed with the app PIN, so the
 * PIN (not a fingerprint) opens it for this one login.
 */
@Composable
fun LoginPinDialog(model: AppModel) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { model.askLoginPin.value = false }, properties = secureDialog,
        title = { Text("Log in to Zerodha", style = Type.title) },
        text = {
            Column {
                Text("Enter your app PIN. It unseals the API secret for this login only.", style = Type.bodySmall)
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                err?.let { Text(it, style = Type.italic.copy(color = LocalPalette.current.oxblood)) }
            }
        },
        confirmButton = { TextButton({ err = model.unlockForLogin(pin); pin = "" }) { Text("Continue") } },
        dismissButton = { TextButton({ model.askLoginPin.value = false }) { Text("Cancel") } },
    )
}

// ---- confirming it is you ----------------------------------------------------------------

/**
 * A fresh proof of identity before a real order: fingerprint/face when it is
 * enabled and strong, the PIN otherwise. Being unlocked is not enough - the
 * phone may have been handed over unlocked.
 */
@Composable
fun Reauth(model: AppModel, onOk: () -> Unit, onCancel: () -> Unit) {
    val s by model.settings.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    var usePin by remember { mutableStateOf(!(s.biometric && activity != null)) }
    LaunchedEffect(usePin) {
        if (!usePin && activity != null) BiometricGate.authenticate(activity, allowWeakFace = false) { out ->
            if (out == BiometricGate.Outcome.Success) onOk() else usePin = true
        }
    }
    if (usePin) {
        var pin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onCancel, properties = secureDialog,
            title = { Text("Confirm it is you", style = Type.title) },
            text = {
                Column {
                    Text("Enter your app PIN to send this order to Zerodha.", style = Type.bodySmall)
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    err?.let { Text(it, style = Type.italic.copy(color = LocalPalette.current.oxblood)) }
                }
            },
            confirmButton = {
                TextButton({
                    when (val r = PinLock.verify(pin.toCharArray(), s.wipeOnExhaustion)) {
                        PinLock.Result.Ok -> onOk()
                        is PinLock.Result.LockedOut -> err = "Locked for ${r.secondsLeft} s."
                        PinLock.Result.Wiped -> { onCancel(); com.optionslab.app.ui.eraseEverything() }
                        else -> { err = "Not the right PIN."; pin = "" }
                    }
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onCancel) { Text("Cancel") } },
        )
    }
}

/** Press and HOLD for a second and a half; a tap does nothing. */
@Composable
fun HoldToSend(text: String, enabled: Boolean, onComplete: () -> Unit) {
    val p = LocalPalette.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(54.dp)
            .background(if (enabled) p.oxblood else p.inkFaint, RoundedCornerShape(27.dp))
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    val job = scope.launch {
                        progress.animateTo(1f, tween(1500))
                        onComplete()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f, tween(200)) } }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(darken(p.oxblood, 0.35f), size = Size(size.width * progress.value, size.height), cornerRadius = CornerRadius(27.dp.toPx()))
        }
        Text(text, style = Type.label.copy(color = Color.White, fontSize = 15.sp))
    }
}

// ---- reviewing and sending an order ---------------------------------------------------------

/**
 * The order review, as one secure dialog over whatever page is open: it opens
 * where the order was asked for, never at the top of a page scrolled away.
 * Shown once, from the app's root.
 */
@Composable
fun OrderReviewDialog(model: AppModel) {
    val p = LocalPalette.current
    val plan by model.plan.collectAsState()
    val sending by model.sending.collectAsState()
    if (plan == Load.Idle) return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (sending !is Load.Busy) model.dismissPlan() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth(0.94f).background(p.paper, RoundedCornerShape(20.dp))
                .padding(10.dp),
        ) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { OrderReviewBody(model) }
        }
    }
}

@Composable
private fun OrderReviewBody(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val plan by model.plan.collectAsState()
    val sending by model.sending.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val st by model.stuck.collectAsState()
    var stuckAction by remember { mutableStateOf<String?>(null) }
    when (val pl = plan) {
        Load.Idle -> Unit
        is Load.Busy -> LedgerCard { FullSpinner(pl.label) }
        is Load.Failed -> LedgerCard(accent = p.oxblood) {
            Note(pl.why)
            BrassButton("Close", tone = p.inkFaint) { model.dismissPlan() }
        }
        is Load.Done -> {
            PlanCard(pl.value, s.allowRealOrders && s.live, sending, onPrice = model::setLegPrice, onSend = { confirming = true }, onClose = model::dismissPlan)
            st?.takeIf { it.plan == pl.value }?.let { stk -> StuckCard(stk) { stuckAction = it } }
        }
    }
    if (confirming) Reauth(model, onOk = { confirming = false; model.sendPlan() }, onCancel = { confirming = false })
    stuckAction?.let { a ->
        Reauth(model, onOk = {
            stuckAction = null
            when (a) { "cancel" -> model.cancelStuck(); "reprice" -> model.repriceStuck(); else -> model.continueAfterStuck() }
        }, onCancel = { stuckAction = null })
    }
}

/** A leg still working at Zerodha after the send stopped: the owner decides, the rest wait. */
@Composable
private fun StuckCard(st: com.optionslab.app.ui.AppModel.StuckLeg, onAction: (String) -> Unit) {
    val p = LocalPalette.current
    val leg = st.plan.legs[st.index]
    LedgerCard(title = "Leg ${st.index + 1} is still working", accent = p.amber, modifier = Modifier.padding(top = 10.dp)) {
        Text("${leg.side} ${leg.tradingSymbol} ×${leg.quantity} · ${st.status.lowercase()} · order …${st.orderId.takeLast(6)}", style = Type.figure.copy(fontSize = 12.sp))
        val rest = st.plan.legs.size - st.index - 1
        Note(if (rest > 0) "$rest more leg${if (rest > 1) "s" else ""} held back: they go only after this one has completely filled." else "This was the last leg.")
        BrassButton("Move to the best price", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { onAction("reprice") }
        if (rest > 0) BrassButton("It filled: send the remaining legs", Modifier.fillMaxWidth().padding(top = 6.dp)) { onAction("continue") }
        BrassButton("Cancel this leg (send nothing more)", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.oxblood) { onAction("cancel") }
    }
}

@Composable
private fun PlanCard(
    plan: OrderPlan, allowed: Boolean, sending: Load<List<Broker.Fill>>,
    onPrice: (Int, Double) -> Unit, onSend: () -> Unit, onClose: () -> Unit,
) {
    val p = LocalPalette.current
    LedgerCard(title = "Real Order · Zerodha", accent = p.oxblood) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plan.title, style = Type.title.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            Stamp("Live money", p.oxblood, animate = false)
        }
        plan.legs.forEachIndexed { i, leg ->
            Rule(Modifier.padding(vertical = 6.dp))
            val q = plan.quotes["${leg.exchange}:${leg.tradingSymbol}"]
            Text("${i + 1}. ${leg.side} ${leg.exchange}:${leg.tradingSymbol}", style = Type.figure.copy(color = if (leg.side == Kite.Side.SELL) p.oxblood else p.verdigris))
            LedgerLine("Quantity", if (leg.lotSize > 1) "${leg.quantity} (${leg.lots} lot × ${leg.lotSize})" else "${leg.quantity}")
            LedgerLine("Product / type", "${leg.product} / ${leg.orderType}")
            if (q != null) LedgerLine("Bid / offer / last", "${q.bid?.let { "%.2f".format(it) } ?: "—"} / ${q.ask?.let { "%.2f".format(it) } ?: "—"} / ${"%.2f".format(q.last)}")
            var text by remember(leg.price) { mutableStateOf(leg.price?.let { "%.2f".format(it) } ?: "") }
            OutlinedTextField(text, { t -> text = t.filter { it.isDigit() || it == '.' }; text.toDoubleOrNull()?.let { onPrice(i, it) } },
                label = { Text("Limit price") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth())
            plan.refusals.getOrNull(i)?.forEach { Text("✕ $it", style = Type.bodySmall.copy(color = p.oxblood)) }
        }
        plan.margin?.let { m ->
            Rule(Modifier.padding(vertical = 6.dp))
            LedgerLine("Margin needed (with hedge benefit)", rs(m.required), if (m.short) p.oxblood else p.ink)
            LedgerLine("Available", rs(m.available), if (m.short) p.oxblood else p.verdigris)
            if (m.charges > 0) LedgerLine("Charges, estimated", rs(m.charges))
            if (m.short) Text("✕ Short of margin by ${rs(m.required - m.available)}: not sendable.", style = Type.bodySmall.copy(color = p.oxblood))
        }
        plan.marginNote?.let { Note(it) }
        Spacer(Modifier.height(8.dp))
        if (plan.exit) Note("Closing orders only: each reduces what you hold, so the lot, value and daily-count caps do not block them. Before sending, the position is re-read; if it changed, nothing is sent." +
            if (plan.legs.size > 1) " Shorts are bought back first; a long is sold only after the short before it has filled." else "")
        else if (plan.legs.size > 1) Note("The wing is bought first. The put is sold only after the wing has filled completely, so the account is never left holding a naked short by accident.")
        if (plan.holdToSettlement) Note("NRML, held to cash settlement. Nothing will close it for you: the loss below the strike is unbounded unless a wing is bought.")
        Spacer(Modifier.height(8.dp))
        when (sending) {
            is Load.Busy -> FullSpinner(sending.label)
            is Load.Failed -> Text(sending.why, style = Type.body.copy(color = p.oxblood))
            is Load.Done -> sending.value.forEach { f -> LedgerLine(f.orderId.takeLast(8), "${f.status} ${f.filled} @ ${"%.2f".format(f.avgPrice)}", if (f.status == "COMPLETE") p.verdigris else p.oxblood) }
            Load.Idle -> {
                if (!allowed) Note("To send: LIVE mode and \"Allow real orders\" must both be on (More → Zerodha).")
                HoldToSend("Hold to send to Zerodha", allowed && plan.sendable, onSend)
            }
        }
        Spacer(Modifier.height(8.dp))
        BrassButton("Close", Modifier.fillMaxWidth(), tone = p.inkFaint, onClick = onClose)
    }
}

// ---- the Zerodha page (Cabinet) ------------------------------------------------------------

@Composable
fun BrokerPage(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val b by model.broker.collectAsState()
    var editing by remember { mutableStateOf(!b.configured) }
    var forgetting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshBroker(); if (Broker.loggedIn) model.loadAccount() }
    Page {
        item { PageTitle("Zerodha", "Your broker, as the PC trading app uses it: Kite Connect") }
        item {
            LedgerCard(title = "Connection") {
                LedgerLine("API key", b.maskedKey)
                LedgerLine("Session", if (b.loggedIn) "${b.user ?: "logged in"} · until ${b.expires?.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}" else "not logged in today",
                    if (b.loggedIn) p.verdigris else p.amber)
                Note("Zerodha gives individual developers no refresh token: the access token ends at about 06:00 IST, so you log in once each trading day - as on the PC.")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (b.configured) {
                        if (b.loggedIn) BrassButton("Log out", Modifier.weight(1f), tone = p.inkSoft) { model.brokerLogout() }
                        else BrassButton("Log in to Zerodha", Modifier.weight(1f)) { model.startKiteLogin() }
                    }
                    BrassButton(if (b.configured) "Change keys" else "Set up", Modifier.weight(1f), tone = p.inkSoft) { editing = !editing }
                }
                if (editing) CredentialsForm(model) { editing = false }
                if (b.configured) BrassButton("Erase Zerodha keys from this phone", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.oxblood) { forgetting = true }
            }
        }
        item {
            LedgerCard(title = "Mode") {
                ParamTokens("Live data from", listOf("LIVE · Zerodha" to s.live, "SANDBOX · public data" to !s.live)) { i ->
                    if (i == 0 && !b.configured) model.say("Set up Zerodha first.")
                    else model.update { it.copy(mode = if (i == 0) "live" else "sandbox") }
                }
                Note(if (s.live) "Every live figure - index levels, the option chain, the ticket, its live mark, settlement, the expiry calendar, the live watch and alarms - comes from Zerodha only. Without today's login the app says so rather than showing another feed."
                else "Live figures come from Upstox's public candles and tickets stay paper; nothing touches your broker. Analysis (Backtests, Health, the IC table, Signal Lab) is the same in both modes.")
            }
        }
        item {
            LedgerCard(title = "Real orders") {
                ToggleRow("Allow real orders", "Off by default. Even when on, every order needs your review, a long press and your PIN or fingerprint.", s.allowRealOrders) { on ->
                    model.update { it.copy(allowRealOrders = on) }
                }
                ToggleRow("Prepare the expiry order at 11:01", "Builds today's ticket and notifies you to review it. It is never sent by itself.", s.prepareRealOrder) { on ->
                    model.update { it.copy(prepareRealOrder = on) }
                }
                ParamTokens("Product", listOf("NRML" to (s.orderProduct == "NRML"), "MIS" to (s.orderProduct == "MIS"))) { i -> model.update { it.copy(orderProduct = if (i == 0) "NRML" else "MIS") } }
                if (s.orderProduct == "MIS") Note("MIS positions are squared off by Zerodha before the close. The expiry put holds to settlement, so its orders are refused under MIS.")
                val counts = listOf(2, 4, 8, 20)
                ParamTokens("Orders per day", counts.map { "$it" to (it == s.maxOrdersPerDay) }) { i -> model.update { it.copy(maxOrdersPerDay = counts[i]) } }
                val lots = listOf(1, 2, 4)
                ParamTokens("Lots per order", lots.map { "$it" to (it == s.maxLotsPerOrder) }) { i -> model.update { it.copy(maxLotsPerOrder = lots[i]) } }
                val values = listOf(50_000.0, 200_000.0, 500_000.0)
                ParamTokens("Order value cap", values.map { rs(it) to (it == s.maxOrderValue) }) { i -> model.update { it.copy(maxOrderValue = values[i]) } }
                LedgerLine("Sent today", "${Broker.sentToday()} of ${s.maxOrdersPerDay}")
            }
        }
        if (b.loggedIn) {
            item { ManualOrder(model) }
            item {
                LedgerCard(title = "Your account") {
                    Note("Positions, the order book, trades, holdings, funds and the day's P&L are on the Trade tab.")
                }
            }
        }
        item {
            LedgerCard {
                Note("The API secret and the day's access token are held in the encrypted vault and are never shown, logged or sent anywhere but to api.kite.trade. The Zerodha login page keeps no cookies or passwords once it closes.")
            }
        }
    }
    if (forgetting) AlertDialog(
        onDismissRequest = { forgetting = false }, properties = secureDialog,
        title = { Text("Erase the Zerodha keys?", style = Type.title) },
        text = { Text("The API key, secret and today's session are deleted from this phone. Your Zerodha account and orders are untouched.", style = Type.bodySmall) },
        confirmButton = { TextButton({ forgetting = false; model.forgetBroker() }) { Text("Erase") } },
        dismissButton = { TextButton({ forgetting = false }) { Text("Keep") } },
    )
}

@Composable
private fun CredentialsForm(model: AppModel, onDone: () -> Unit) {
    val p = LocalPalette.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf(Broker.redirect ?: "https://") }
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 10.dp)) {
        Note("From developers.kite.trade → your app. The redirect URL must be exactly the one registered there.")
        OutlinedTextField(key, { key = it.trim() }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(secret, { secret = it.trim() }, label = { Text("API secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(redirect, { redirect = it.trim() }, label = { Text("Redirect URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("Your app PIN (seals the secret)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        err?.let { Text(it, style = Type.italic.copy(color = p.oxblood)) }
        Spacer(Modifier.height(8.dp))
        BrassButton("Save to the vault", Modifier.fillMaxWidth()) {
            err = model.saveBrokerCredentials(key, secret, redirect, pin)
            pin = ""
            if (err == null) { key = ""; secret = ""; onDone(); model.say("Saved, the secret sealed with your PIN. Now log in to Zerodha.") }
        }
    }
}

@Composable
private fun ManualOrder(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    var underlying by remember { mutableStateOf("NIFTY") }
    var expiries by remember { mutableStateOf<List<java.time.LocalDate>>(emptyList()) }
    var expiry by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var strike by remember { mutableStateOf("") }
    var right by remember { mutableStateOf(Right.PE) }
    var side by remember { mutableStateOf(Kite.Side.SELL) }
    var lots by remember { mutableStateOf(1) }
    var price by remember { mutableStateOf("") }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var strikes by remember { mutableStateOf<List<Double>>(emptyList()) }
    var spot by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(underlying) {
        loadErr = null
        expiries = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Broker.instruments().filter { it.name == underlying && !it.expiry.isBefore(Market.today()) }.map { it.expiry }.distinct().sorted().take(6)
            }
        } catch (e: Exception) { loadErr = "Could not load Zerodha's contract list: ${e.message}"; emptyList() }
        expiry = expiries.firstOrNull()
        spot = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Broker.indexQuote(underlying)?.last } }.getOrNull()
    }
    // The strikes actually listed for this expiry and option, the eleven nearest the index.
    LaunchedEffect(underlying, expiry, right, spot) {
        val e = expiry ?: return@LaunchedEffect
        val all = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Broker.instruments().filter { it.name == underlying && it.expiry == e && it.right == right }.map { it.strike }.distinct().sorted()
        } }.getOrDefault(emptyList())
        val s0 = spot
        strikes = if (s0 == null) emptyList() else all.sortedBy { kotlin.math.abs(it - s0) }.take(11).sorted()
    }
    LedgerCard(title = "Place an order") {
        ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
        ParamTokens("Expiry", expiries.map { it.toString().substring(5) to (it == expiry) }) { expiry = expiries[it] }
        ParamTokens("Option", listOf("PE" to (right == Right.PE), "CE" to (right == Right.CE))) { right = if (it == 0) Right.PE else Right.CE }
        ParamTokens("Side", listOf("SELL" to (side == Kite.Side.SELL), "BUY" to (side == Kite.Side.BUY))) { side = if (it == 0) Kite.Side.SELL else Kite.Side.BUY }
        ParamTokens("Lots", (1..s.maxLotsPerOrder).map { "$it" to (it == lots) }) { lots = it + 1 }
        loadErr?.let { Text(it, style = Type.bodySmall.copy(color = p.oxblood)) }
        if (strikes.isNotEmpty()) {
            spot?.let { Note("${underlying} ${"%,.1f".format(it)}: nearest listed strikes") }
            ParamTokens("Strike", strikes.map { com.optionslab.engine.fmtG(it) to (strike == com.optionslab.engine.fmtG(it)) }) { i -> strike = com.optionslab.engine.fmtG(strikes[i]) }
        }
        OutlinedTextField(strike, { strike = it.filter(Char::isDigit) }, label = { Text("Strike (or pick above)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Limit price (blank = best bid/offer)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Spacer(Modifier.height(8.dp))
        BrassButton("Review the order", Modifier.fillMaxWidth(), enabled = expiry != null && strike.toDoubleOrNull() != null) {
            model.planManual(underlying, expiry!!, strike.toDouble(), right, side, lots, s.orderProduct, price.toDoubleOrNull())
        }
        Note("Nothing is sent from here: the order opens for review over this page.")
    }
}
