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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.optionslab.app.ui.components.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.optionslab.app.ui.components.BrandLogo
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

private val secureDialog get() = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)

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
 * Before each Zerodha login: the API secret is opened by the fingerprint (its own
 * hardware-bound copy) when that is set up, otherwise by the app PIN, for this one login.
 */
@Composable
fun LoginPinDialog(model: AppModel) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val s by model.settings.collectAsState()
    val findings by model.integrity.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    val blob = remember { Broker.bioSealedSecret }
    val bio = s.biometric && activity != null && blob != null && !(findings.isNotEmpty() && com.optionslab.app.security.Integrity.compromised(findings))
    var usePin by remember { mutableStateOf(!bio) }
    LaunchedEffect(usePin) {
        if (!usePin && activity != null && blob != null) BiometricGate.openWithFingerprint(activity, blob) { secret, why ->
            when {
                secret != null -> model.loginWithSecret(secret)
                why == "invalid" -> { Broker.dropBioSealed(); err = "A fingerprint was added or removed on this phone, so the fingerprint copy of the secret was cleared. Use your PIN (or set up the keys again)."; usePin = true }
                why != null -> { err = "Fingerprint: $why"; usePin = true }
                else -> usePin = true
            }
        }
    }
    if (!usePin) return
    AlertDialog(
        onDismissRequest = { if (!checking) model.askLoginPin.value = false }, properties = secureDialog,
        title = { Text("Log in to Zerodha", style = Type.title) },
        text = {
            Column {
                Text("Enter your app PIN. It unseals the API secret for this login only.", style = Type.bodySmall)
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                com.optionslab.app.ui.components.AlertOn(err, throttle = false)
            }
        },
        // The PIN check and unsealing are slow on purpose (key stretching): off the screen's thread, as in CredentialsForm.
        confirmButton = {
            TextButton({
                val pn = pin; pin = ""; checking = true; err = null
                scope.launch {
                    err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { model.unlockForLogin(pn) }
                    checking = false
                }
            }, enabled = !checking) { Text(if (checking) "Checking…" else "Continue") }
        },
        dismissButton = { TextButton({ model.askLoginPin.value = false }, enabled = !checking) { Text("Cancel") } },
    )
}

// ---- confirming it is you ----------------------------------------------------------------

/**
 * A fresh proof of identity before a real order: the fingerprint when it is
 * enabled and strong, the PIN otherwise. Being unlocked is not enough - the
 * phone may have been handed over unlocked.
 */
@Composable
fun Reauth(model: AppModel, onOk: () -> Unit, onCancel: () -> Unit, pinOnly: Boolean = false, why: String = "Enter your app PIN to send this order to Zerodha.") {
    val s by model.settings.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    // A phone that failed the security check can fake a biometric callback: the PIN only, there.
    val findings by model.integrity.collectAsState()
    val compromised = findings.isNotEmpty() && com.optionslab.app.security.Integrity.compromised(findings)
    var usePin by remember { mutableStateOf(pinOnly || !(s.biometric && activity != null && !compromised)) }
    LaunchedEffect(usePin) {
        // Face unlock counts here too when the owner accepted it (More → Security); otherwise the fingerprint key.
        if (!usePin && activity != null) BiometricGate.authenticate(activity, allowWeakFace = s.allowWeakFace) { out ->
            when (out) {
                BiometricGate.Outcome.Success -> onOk()
                is BiometricGate.Outcome.Failed -> { com.optionslab.app.work.Alerts.error("Fingerprint: ${out.why}. Use your PIN."); usePin = true }
                is BiometricGate.Outcome.Invalidated -> { com.optionslab.app.work.Alerts.error(out.why); model.update { it.copy(biometric = false) }; usePin = true }
                BiometricGate.Outcome.UsePin -> usePin = true
            }
        }
    }
    if (usePin) {
        var pin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val pinScope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = onCancel, properties = secureDialog,
            title = { Text("Confirm it is you", style = Type.title) },
            text = {
                Column {
                    Text(why, style = Type.bodySmall)
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, singleLine = true, label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    com.optionslab.app.ui.components.AlertOn(err, throttle = false)
                }
            },
            confirmButton = {
                // The PIN check is slow on purpose (key stretching): off the screen's thread.
                TextButton({
                    checking = true; err = null
                    val typed = pin.toCharArray()
                    pinScope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinLock.verify(typed, s.wipeOnExhaustion) }
                        checking = false
                        when (r) {
                            PinLock.Result.Ok -> onOk()
                            is PinLock.Result.LockedOut -> err = "Locked for ${r.secondsLeft} s."
                            PinLock.Result.Wiped -> { onCancel(); com.optionslab.app.ui.eraseEverything() }
                            else -> { err = "Not the right PIN."; pin = "" }
                        }
                    }
                }, enabled = !checking) { Text(if (checking) "Checking…" else "Confirm") }
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
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy, usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth(0.94f).background(p.paper, RoundedCornerShape(20.dp))
                .padding(10.dp),
        ) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                com.optionslab.app.ui.components.InlineAlerts()
                OrderReviewBody(model)
            }
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
        // The reason shows in the red banner; the review closes.
        is Load.Failed -> { com.optionslab.app.ui.components.AlertOn(pl.why); LaunchedEffect(pl) { model.dismissPlan() } }
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
            com.optionslab.app.ui.components.AlertOn(plan.refusals.getOrNull(i)?.takeIf { it.isNotEmpty() }?.joinToString(" "))
        }
        plan.margin?.let { m ->
            Rule(Modifier.padding(vertical = 6.dp))
            LedgerLine("Margin needed (with hedge benefit)", rs(m.required), if (m.short) p.oxblood else p.ink)
            LedgerLine("Available", rs(m.available), if (m.short) p.oxblood else p.verdigris)
            if (m.charges > 0) LedgerLine("Charges, estimated", rs(m.charges))
            com.optionslab.app.ui.components.AlertOn(if (m.short) "Short of margin by ${rs(m.required - m.available)}: not sendable." else null)
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
            is Load.Failed -> com.optionslab.app.ui.components.AlertOn(sending.why)
            is Load.Done -> sending.value.forEach { f -> LedgerLine(f.orderId.takeLast(8), "${f.status} ${f.filled} @ ${"%.2f".format(f.avgPrice)}", if (f.status == "COMPLETE") p.verdigris else p.oxblood) }
            Load.Idle -> {
                if (!allowed) Note("This is Paper mode. To send real orders, tap the PAPER TRADING badge at the top and switch to Live.")
                HoldToSend("Hold to send to Zerodha", allowed && plan.sendable, onSend)
            }
        }
        Spacer(Modifier.height(8.dp))
        BrassButton("Close", Modifier.fillMaxWidth(), tone = p.inkFaint, onClick = onClose)
    }
}

// ---- static IP (SEBI) --------------------------------------------------------------------------

/**
 * SEBI requires API orders to come from an IP registered with Zerodha. This card shows
 * whether the phone is on that IP right now, keeps the registered IP (new live positions
 * are refused from any other), and walks through setting up the relay and the VPN.
 */
@Composable
private fun StaticIpCard(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var status by remember { mutableStateOf<com.optionslab.app.data.StaticIp.Status?>(null) }
    var checking by remember { mutableStateOf(false) }
    var ipText by remember { mutableStateOf(com.optionslab.app.data.StaticIp.registered.orEmpty()) }
    var guide by rememberSaveable { mutableStateOf(false) }
    val relay = com.optionslab.app.data.Relay
    var pub by remember { mutableStateOf(relay.publicKey) }
    var relayOn by remember { mutableStateOf(relay.enabled) }
    var hostText by remember { mutableStateOf(relay.host ?: com.optionslab.app.data.StaticIp.registered.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var relayMsg by remember { mutableStateOf<String?>(null) }
    fun check() { checking = true; scope.launch { status = com.optionslab.app.data.StaticIp.status(force = true); checking = false } }
    LaunchedEffect(Unit) { check() }
    fun open(url: String) = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @Composable fun link(label: String, url: String) = Text("↗  $label", style = Type.body.copy(color = p.verdigris,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().clickable { open(url) }.padding(vertical = 6.dp))
    @Composable fun step(n: String, title: String, body: String) = Column(Modifier.padding(top = 10.dp)) {
        Text("$n. $title", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        Text(body, style = Type.bodySmall.copy(color = p.inkSoft))
    }
    val st = status
    val tone = when { st == null -> p.inkSoft; st.registered == null -> p.amber; st.matches -> p.verdigris; else -> p.oxblood }
    LedgerCard(title = "Static IP (needed for live orders)", accent = if (st != null && st.registered != null && !st.matches) p.oxblood else null) {
        Text(when {
            st == null -> "Checking…"
            st.registered == null -> "Not set up yet. SEBI requires API orders to come from an IP you have registered with Zerodha."
            st.current == null -> "Could not read this phone's IP just now."
            st.matches -> "✓ This phone is on your registered IP ${st.registered}. Live orders can go."
            else -> "✗ This phone is on ${st.current}, not your registered ${st.registered}. New live positions are refused until the relay below is connected (exits still go)."
        }, style = Type.body.copy(color = tone))
        st?.let { LedgerLine("Relay", if (com.optionslab.app.data.Relay.enabled && com.optionslab.app.data.Relay.connected) "connected" else if (it.vpn) "VPN on" else "off", if (it.vpn) p.verdigris else p.inkSoft) }
        st?.current?.let { LedgerLine("This phone's IP now", it) }
        androidx.compose.material3.OutlinedTextField(ipText, { ipText = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
            label = { Text("Your registered static IP (your server's IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton("Save IP", Modifier.weight(1f)) {
                when {
                    ipText.isBlank() -> { com.optionslab.app.data.StaticIp.registered = null; model.say("Static IP cleared: orders are no longer checked against it."); check() }
                    !com.optionslab.app.data.StaticIp.valid(ipText) -> com.optionslab.app.work.Alerts.error("That is not an IP address like 13.235.10.20.")
                    else -> { com.optionslab.app.data.StaticIp.registered = ipText.trim(); com.optionslab.app.work.Alerts.success("Static IP saved."); check() }
                }
            }
            BrassButton(if (checking) "Checking…" else "Check now", Modifier.weight(1f), tone = p.inkSoft, enabled = !checking) { check() }
        }
        // ---- the built-in relay: 4 steps, nothing to install -----------------------------------
        Text("Set up with your own server (recommended)", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            modifier = Modifier.padding(top = 14.dp))
        Note("IraAlgo sends your Zerodha orders through your own cloud server, so Zerodha sees the server's fixed IP. Nothing to install on the phone or the server.")
        step("1", "Create the app's key", "IraAlgo makes a key only your server will accept. Copy it for step 2.")
        if (pub == null) BrassButton("Create key", Modifier.fillMaxWidth().padding(top = 6.dp)) {
            scope.launch { pub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { relay.newKey() } }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pub!!.take(40) + "…", style = Type.figure.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f), maxLines = 1)
                Text("Copy", style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                    modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(pub!!)); model.say("Key copied: paste it in Oracle's SSH keys box") }.padding(start = 12.dp))
            }
        }
        step("2", "Create the server in Oracle and paste the key",
            "Oracle console → Compute → Instances → Create instance. Image: Ubuntu or Oracle Linux (the app finds the right login). Under \"Add SSH keys\" choose \"Paste public keys\" and paste. " +
                "Under networking choose \"Do not assign a public IPv4 address\", then Create. When it is running: the instance → Attached VNICs → the VNIC → " +
                "IPv4 Addresses → ⋮ → Edit → Public IP: Reserved → pick your reserved IP (e.g. 144.24.125.164) → Update.")
        link("Open the Oracle console", "https://cloud.oracle.com/compute/instances")
        step("3", "Connect", "Enter the server's IP and tap Connect & test. It shows the IP Zerodha will see.")
        androidx.compose.material3.OutlinedTextField(hostText, { hostText = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
            label = { Text("Server IP") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        BrassButton(if (testing) "Connecting…" else "Connect & test", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !testing && pub != null) {
            if (!com.optionslab.app.data.StaticIp.valid(hostText)) { com.optionslab.app.work.Alerts.error("Enter the server's IP, like 144.24.125.164."); return@BrassButton }
            testing = true; relayMsg = null
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        relay.host = hostText.trim(); relay.enabled = true
                        relay.proxy()                                        // throws with the reason if the server refuses or is unreachable
                        com.optionslab.app.data.StaticIp.status(force = true)
                    }
                }
                testing = false
                r.onSuccess { st ->
                    relayOn = true
                    if (com.optionslab.app.data.StaticIp.registered == null && st.current != null) { com.optionslab.app.data.StaticIp.registered = st.current; ipText = st.current }
                    relayMsg = if (st.current != null) "✓ Connected. Zerodha will see your orders from ${st.current}." else "Connected, but the IP check did not answer; try again."
                    status = st
                }.onFailure { e ->
                    relay.enabled = false; relayOn = false
                    relayMsg = e.message ?: "Could not connect"
                }
            }
        }
        relayMsg?.let { Text(it, style = Type.bodySmall.copy(color = if (it.startsWith("✓")) p.verdigris else p.oxblood), modifier = Modifier.padding(top = 6.dp)) }
        step("4", "Register that IP with Zerodha", "On the Kite developer site open your app and enter the same IP in its static IP setting.")
        link("Open My apps on developers.kite.trade", "https://developers.kite.trade/apps")
        if (pub != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (relayOn) "Orders go through your server" else "Relay off: orders go direct", style = Type.bodySmall.copy(color = if (relayOn) p.verdigris else p.inkSoft), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = relayOn, onCheckedChange = { on ->
                if (on && relay.host == null) com.optionslab.app.work.Alerts.error("Connect & test first.") else { relay.enabled = on; relayOn = on; check() }
            })
        }
        if (pub != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("New key", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.clickable {
                scope.launch { pub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { relay.newKey() }; model.say("New key made: paste it into the server again") }
            }.padding(4.dp))
            Text("Forget server", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.clickable { relay.forgetServer(); model.say("Server identity forgotten; the next connect trusts it anew") }.padding(4.dp))
        }

        Text(if (guide) "Hide the VPN steps ▲" else "Advanced: use a WireGuard VPN instead ▼", style = Type.label.copy(color = p.ink, fontSize = 14.sp),
            modifier = Modifier.padding(top = 12.dp).clickable { guide = !guide }.padding(vertical = 4.dp))
        if (guide) {
            Note("Why: a phone on mobile data or home Wi-Fi has an IP that keeps changing, so Zerodha would reject its orders. " +
                "You rent a tiny server with a fixed IP and send the phone's traffic through it with a VPN (WireGuard). " +
                "The server only forwards encrypted traffic: it never sees your keys, orders or passwords. Setup takes about 20 minutes, once.")
            step("1", "Rent a small server with a static IP",
                "Any provider with a fixed public IPv4 in India (Mumbai or Bangalore region), the smallest plan, Ubuntu 22.04 or 24.04. " +
                    "In its firewall allow UDP port 51820. Note the server's public IP.")
            link("Oracle Cloud Always Free (₹0: free VM with a reserved IP, Mumbai / Hyderabad)", "https://www.oracle.com/cloud/free/")
            Note("Already have home broadband with a static IP (many Jio Fiber / Airtel Xstream plans sell one as an add-on)? Run the same relay on an always-on home PC or router instead and register that IP.")
            link("AWS Lightsail (Mumbai)", "https://lightsail.aws.amazon.com/")
            link("DigitalOcean (Bangalore)", "https://www.digitalocean.com/products/droplets")
            link("Vultr (Mumbai / Bangalore)", "https://www.vultr.com/products/cloud-compute/")
            step("2", "Install the VPN relay on it (one command)",
                "From a computer, copy android/tools/wg-relay-setup.sh from the IraAlgo code to the server and run it. It installs WireGuard and prints the IP to register and a QR code for the phone.")
            val cmd = "sudo bash wg-relay-setup.sh"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(cmd, style = Type.figure.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
                Text("Copy", style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                    modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(cmd)); model.say("Command copied") }.padding(start = 12.dp))
            }
            link("Full guide (static-ip-relay.md)", "https://github.com/mevishalsonawane-ai/options-lab/blob/main/android/docs/static-ip-relay.md")
            step("3", "Register that IP with Zerodha",
                "On the Kite developer site open your app and enter the server's IP in its static IP / IP whitelist setting, exactly as the script printed it.")
            link("Open My apps on developers.kite.trade", "https://developers.kite.trade/apps")
            step("4", "Install WireGuard on this phone and scan the QR code",
                "Install the official WireGuard app, tap +, choose Scan from QR code, scan the code the script printed, and switch the tunnel on.")
            link("WireGuard on the Play Store", "https://play.google.com/store/apps/details?id=com.wireguard.android")
            step("5", "Keep the VPN always on",
                "In Android Settings → Network → VPN → WireGuard (gear icon), turn on Always-on VPN and Block connections without VPN, so no order ever leaves outside the tunnel.")
            BrassButton("Open the phone's VPN settings", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) {
                runCatching { ctx.startActivity(android.content.Intent("android.settings.VPN_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            step("6", "Enter the IP here and check",
                "Type the server's IP in the box above, tap Save IP, then Check now: it must say ✓. If it says ✗, the VPN is off or connected to a different server.")
        }
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
        item { StaticIpCard(model) }
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
                ParamTokens("Trading mode", listOf("Live · Zerodha" to s.live, "Paper · simulated" to !s.live)) { i ->
                    if (i == 0 && !b.configured) model.say("Set up Zerodha first.")
                    else model.update { it.copy(mode = if (i == 0) "live" else "sandbox", allowRealOrders = i == 0) }
                }
                Note(if (s.live) "Every live figure - index levels, the option chain, the ticket, its live mark, settlement, the expiry calendar, the market watch and alarms - comes from Zerodha only. Without today's login the app says so rather than showing another feed."
                else "Live figures come from Upstox's public candles and tickets stay paper; nothing touches your broker. Analysis (Backtests, Health, the IC table, Signal Lab) is the same in both modes.")
            }
        }
        item {
            LedgerCard(title = "Real orders") {
                Note("Live trading sends real orders to Zerodha; Paper never does. Switch with the PAPER / LIVE badge at the top. Every order still needs your review, a long press and your PIN or fingerprint.")
                ToggleRow("Prepare the expiry order at 11:01", "Builds today's ticket and notifies you to review it. It is never sent by itself.", s.prepareRealOrder) { on ->
                    model.update { it.copy(prepareRealOrder = on) }
                }
                ParamTokens("Product", listOf("NRML" to (s.orderProduct == "NRML"), "MIS" to (s.orderProduct == "MIS"))) { i -> model.update { it.copy(orderProduct = if (i == 0) "NRML" else "MIS") } }
                if (s.orderProduct == "MIS") Note("MIS positions are squared off by Zerodha before the close. The expiry put holds to settlement, so its orders are refused under MIS.")
                // Order limits live in one place (TODO A6): More -> Bot -> Bot settings.
                LedgerLine("Sent today", "${Broker.sentToday()}" + if (s.guardMaxTrades > 0) " of ${s.guardMaxTrades}" else "")
                Note("Order limits (trades per day, lots, order value) are set in More → Bot → Bot settings and apply to paper and live alike.")
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

/**
 * Shown after unlock until a Zerodha account is linked: the app has no other
 * way in and no close button. Step 1 saves the Kite app's key and secret;
 * step 2 logs in once, which links the account and opens the app.
 */
@Composable
fun ConnectZerodhaScreen(model: AppModel) {
    val p = LocalPalette.current
    val b by model.broker.collectAsState()
    Box(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp)
                .background(p.card, RoundedCornerShape(20.dp)).border(1.dp, p.rule, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            BrandLogo()
            Spacer(Modifier.height(18.dp))
            Text("Connect to Zerodha", style = Type.masthead.copy(color = p.ink, fontSize = 22.sp))
            Text("IraAlgo works with your Zerodha account. Link it once to open the app.", style = Type.bodySmall.copy(color = p.inkSoft))
            Spacer(Modifier.height(12.dp))
            if (!b.configured) SetupGuide(model)
            else {
                Text("Keys saved ✓", style = Type.body.copy(color = p.verdigris, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                Text("3. Log in to Zerodha", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                Note("Sign in with your Zerodha ID, password and TOTP. Your account is linked as soon as the login finishes.")
                Spacer(Modifier.height(10.dp))
                BrassButton("Log in to Zerodha", Modifier.fillMaxWidth()) { model.startKiteLogin() }
                Spacer(Modifier.height(6.dp))
                BrassButton("Change keys", Modifier.fillMaxWidth(), tone = p.inkSoft) { model.forgetBroker() }
            }
        }
    }
}

/**
 * First-time setup, step by step, for someone who has never used the Kite Connect API:
 * what is needed, where the developer site is, how to create the app, where the key and
 * secret are, then the form to save them. Can be skipped by someone who has them.
 */
@Composable
private fun SetupGuide(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var step by rememberSaveable { mutableStateOf(0) }
    fun open(url: String) = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { model.say("No browser found to open $url") }
    val bold = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    @Composable fun point(n: String, text: String) = Row(Modifier.padding(vertical = 3.dp)) {
        Text(n, style = Type.bodySmall.copy(color = p.gold, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), modifier = Modifier.width(22.dp))
        Text(text, style = Type.bodySmall.copy(color = p.ink))
    }
    // A tappable link that opens in the browser.
    @Composable fun link(label: String, url: String) = Text("↗  $label", style = Type.body.copy(color = p.verdigris,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().clickable { open(url) }.padding(vertical = 7.dp))
    val titles = listOf("What you need", "Open the Kite developer site", "Create your app", "Copy the API key and secret", "Save them in IraAlgo")
    // Progress: a bar per step.
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        titles.indices.forEach { i ->
            Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) p.verdigris else p.rule, RoundedCornerShape(2.dp)))
        }
    }
    Text("Step ${step + 1} of ${titles.size}", style = Type.label.copy(color = p.inkSoft))
    Text(titles[step], style = Type.title.copy(color = p.ink, fontSize = 18.sp), modifier = Modifier.padding(bottom = 8.dp))
    when (step) {
        0 -> {
            point("1", "A Zerodha trading account with F&O (derivatives) enabled.")
            point("2", "Two-factor login (TOTP) set up on it: in the Kite app, open Account → Settings → Password & Security → External 2FA TOTP. You will type this code every time you log in.")
            point("3", "A Kite Connect developer account at developers.kite.trade. This is Zerodha's official API; it is what lets IraAlgo read your account and place your orders. Check the current API pricing on that site.")
            point("4", "About 10 minutes, and this phone's fingerprint set up (Settings → Security) so IraAlgo can protect your keys with it.")
            link("No Zerodha account yet? Open one", "https://zerodha.com/open-account")
            link("Set up TOTP on Kite web (Profile → Password & Security)", "https://kite.zerodha.com/")
            link("Zerodha help centre", "https://support.zerodha.com/")
            Note("IraAlgo never sees your Zerodha password or TOTP: you type them on Zerodha's own login page.")
        }
        1 -> {
            point("1", "Tap the button below; the Kite developer site opens in your browser.")
            point("2", "Sign up (or log in) with your email and mobile number and verify them.")
            point("3", "If the site asks you to add credits or pick a plan for API access, do that there.")
            point("4", "Keep that page open and come back here for the next step.")
            BrassButton("Open developers.kite.trade", Modifier.fillMaxWidth().padding(top = 8.dp)) { open("https://developers.kite.trade/") }
            link("Sign up on the developer site", "https://developers.kite.trade/signup")
            link("Already signed up? Log in", "https://developers.kite.trade/login")
            link("What Kite Connect is (official docs)", "https://kite.trade/docs/connect/v3/")
        }
        2 -> {
            point("1", "On the developer site open \"My apps\" and tap \"Create new app\".")
            point("2", "Type: Connect.")
            point("3", "App name: IraAlgo (any name works).")
            point("4", "Zerodha Client ID: your Zerodha user ID, for example AB1234.")
            point("5", "Redirect URL: copy it from the box below and paste it exactly.")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Broker.REDIRECT, style = Type.figure.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
                Text("Copy", style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                    modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(Broker.REDIRECT)); model.say("Redirect URL copied") }.padding(start = 12.dp))
            }
            point("6", "Postback URL: leave it empty. Description: anything, e.g. \"personal trading\".")
            point("7", "Tap Create.")
            link("Open My apps on the developer site", "https://developers.kite.trade/apps")
            Note("The redirect address never opens a website: IraAlgo catches it inside the app when you log in.")
        }
        3 -> {
            point("1", "In \"My apps\", open the app you just created.")
            point("2", "The API key is shown on that page: copy it.")
            point("3", "Tap \"Show API secret\" and copy the secret too. Treat it like a password: never share it or send a screenshot of it.")
            point("4", "Come back here: the next step has Paste buttons for both.")
            link("Open My apps to copy them", "https://developers.kite.trade/apps")
            Note("Only one thing is copied at a time, so copy the key, paste it in the next step, then go back to the site for the secret.")
        }
        else -> {
            CredentialsForm(model) { }
            Note("After saving: tap \"Log in to Zerodha\", sign in with your Zerodha ID, password and TOTP. Zerodha ends the session early each morning, so you log in once every trading day; IraAlgo reminds you at 09:10.")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (step > 0) BrassButton("Back", Modifier.weight(1f), tone = p.inkSoft) { step-- }
        if (step < titles.size - 1) BrassButton(if (step == 0) "Start" else "Next", Modifier.weight(1f)) { step++ }
    }
    if (step < titles.size - 1) Text("I already have my API key and secret", style = Type.label.copy(color = p.inkSoft),
        modifier = Modifier.padding(top = 10.dp).clickable { step = titles.size - 1 }.padding(4.dp))
}

private const val DRAFT_KEY = "draft.kite.key"
private const val DRAFT_SECRET = "draft.kite.secret"

@Composable
private fun CredentialsForm(model: AppModel, onDone: () -> Unit) {
    val p = LocalPalette.current
    // What was typed is kept (encrypted, in the vault) until it is saved, so stepping out to the
    // Kite site, an idle lock or Android closing the app in the background never loses it.
    var key by remember { mutableStateOf(com.optionslab.app.security.SecurePrefs.getString(DRAFT_KEY).orEmpty()) }
    var secret by remember { mutableStateOf(com.optionslab.app.security.SecurePrefs.getString(DRAFT_SECRET).orEmpty()) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // With the fingerprint on, it seals the secret too (the PIN then becomes optional).
    val st by model.settings.collectAsState()
    val findings by model.integrity.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    val fingerprint = st.biometric && activity != null && BiometricGate.available(activity) == BiometricGate.Kind.STRONG &&
        !(findings.isNotEmpty() && com.optionslab.app.security.Integrity.compromised(findings))
    fun draft(k: String, v: String) = com.optionslab.app.security.SecurePrefs.put(k, v.ifEmpty { null })
    fun pasteInto(set: (String) -> Unit) = clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let(set)
    Column(Modifier.padding(top = 10.dp)) {
        Text("1. Create a Kite Connect app", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        Note("Sign in at developers.kite.trade and create an app (type: Connect). Paste this as its Redirect URL:")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Broker.REDIRECT, style = Type.figure.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            Text("Copy", style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                modifier = Modifier.clickable {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(Broker.REDIRECT))
                    model.say("Redirect URL copied")
                }.padding(start = 12.dp))
        }
        Note("IraAlgo catches this address inside the app during login, so it never opens and needs no website. Postback URL can stay empty.")
        Spacer(Modifier.height(10.dp))
        Text("2. Paste the app's key and secret", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        OutlinedTextField(key, { key = it.trim(); draft(DRAFT_KEY, key) }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { TextButton({ pasteInto { key = it; draft(DRAFT_KEY, it) } }) { Text("Paste") } })
        OutlinedTextField(secret, { secret = it.trim(); draft(DRAFT_SECRET, secret) }, label = { Text("API secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { TextButton({
                pasteInto { secret = it; draft(DRAFT_SECRET, it) }
                // The secret must not linger on the clipboard (or in the keyboard's clipboard history).
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(""))
            }) { Text("Paste") } })
        if (key.isNotEmpty() || secret.isNotEmpty()) Note("Kept on this phone (encrypted) until you save, so you can switch to the Kite site and back.")
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text(if (fingerprint) "App PIN (optional: a backup if the fingerprint changes)" else "Your app PIN (seals the secret)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        com.optionslab.app.ui.components.AlertOn(err, throttle = false)
        Spacer(Modifier.height(8.dp))
        if (fingerprint) Note("Your fingerprint seals the secret and opens it at each Zerodha login. Adding the PIN too keeps a way in if a fingerprint is ever added or removed on this phone.")
        BrassButton(if (fingerprint) "Save with fingerprint" else "Save to the vault", Modifier.fillMaxWidth(), busy = saving, enabled = !saving) {
            // The PIN check and the sealing are slow on purpose (key stretching): off the screen's thread.
            val k = key; val s = secret; val pn = pin
            saving = true; err = null
            fun finish(bioBlob: String?) = scope.launch {
                val e = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { model.saveBrokerCredentials(k, s, pn, bioBlob) }
                saving = false; err = e; pin = ""
                if (e == null) {
                    draft(DRAFT_KEY, ""); draft(DRAFT_SECRET, "")
                    key = ""; secret = ""; onDone()
                    model.say("Saved, the secret sealed with your " + when { bioBlob != null && pn.isNotBlank() -> "fingerprint and PIN"; bioBlob != null -> "fingerprint"; else -> "PIN" } + ". Now log in to Zerodha.")
                }
            }
            if (fingerprint && activity != null && s.isNotBlank()) BiometricGate.sealWithFingerprint(activity, s.trim()) { blob, why ->
                if (blob == null && pn.isBlank()) { saving = false; err = why?.let { "Fingerprint: $it" } ?: "Cancelled. Use the fingerprint, or enter your PIN." }
                else finish(blob)
            } else finish(null)
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
        // Every listed strike (the dropdown opens on the one nearest the index).
        strikes = all
        if (strike.toDoubleOrNull() !in all) strike = s0?.let { x -> all.minByOrNull { kotlin.math.abs(it - x) } }?.let { com.optionslab.engine.fmtG(it) } ?: ""
    }
    LedgerCard(title = "Place an order") {
        ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
        ParamTokens("Expiry", expiries.map { it.toString().substring(5) to (it == expiry) }) { expiry = expiries[it] }
        ParamTokens("Option", listOf("PE" to (right == Right.PE), "CE" to (right == Right.CE))) { right = if (it == 0) Right.PE else Right.CE }
        ParamTokens("Side", listOf("SELL" to (side == Kite.Side.SELL), "BUY" to (side == Kite.Side.BUY))) { side = if (it == 0) Kite.Side.SELL else Kite.Side.BUY }
        ParamTokens("Lots", (1..(s.guardMaxLots.takeIf { it > 0 } ?: 5)).map { "$it" to (it == lots) }) { lots = it + 1 }
        loadErr?.let { Text(it, style = Type.bodySmall.copy(color = p.oxblood)) }
        com.optionslab.app.ui.components.StrikeDropdown(strikes, spot, strike, underlying) { strike = it }
        OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Limit price (blank = best bid/offer)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Spacer(Modifier.height(8.dp))
        BrassButton("Review the order", Modifier.fillMaxWidth(), enabled = expiry != null && strike.toDoubleOrNull()?.let { it in strikes } == true) {
            model.planManual(underlying, expiry!!, strike.toDouble(), right, side, lots, s.orderProduct, price.toDoubleOrNull())
        }
        Note("Nothing is sent from here: the order opens for review over this page.")
    }
}
