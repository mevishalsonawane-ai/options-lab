package com.optionslab.app.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.optionslab.app.MainActivity
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Market
import com.optionslab.app.security.BiometricGate
import com.optionslab.app.security.Integrity
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.security.Vault
import com.optionslab.app.ui.components.Parchment
import com.optionslab.app.ui.components.StatusDot
import com.optionslab.app.ui.screens.AlmanacScreen
import com.optionslab.app.ui.screens.CabinetScreen
import com.optionslab.app.ui.screens.HealthScreen
import com.optionslab.app.ui.screens.LockScreen
import com.optionslab.app.ui.screens.RefusedScreen
import com.optionslab.app.ui.screens.TradeScreen
import com.optionslab.app.ui.screens.TradeHub
import com.optionslab.app.ui.screens.ToolsScreen
import com.optionslab.app.ui.screens.LabScreen
import com.optionslab.app.ui.screens.TrialsScreen
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A calendar grid for the P&L tab (the core icon set has none). */
private val CalendarIcon: ImageVector = ImageVector.Builder("pnlcal", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M7,2h2v2h6V2h2v2h2a2,2 0 0 1 2,2v13a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2h2V2z M5,9v10h14V9H5z " +
            "M7,11h3v3H7v-3z M11,11h3v3h-3v-3z M15,11h2v3h-2v-3z M7,15h3v3H7v-3z M11,15h3v3h-3v-3z").toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
}.build()

/** A candlestick glyph for the Chart tab (the core icon set has none). */
private val ChartIcon: ImageVector = ImageVector.Builder("chart", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M5,4h2v3h1v9H7v4H5v-4H4V7h1V4z M11,8h2v2h1v6h-1v4h-2v-4h-1v-6h1V8z M17,3h2v4h1v6h-1v5h-2v-5h-1V7h1V3z").toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
}.build()

enum class Tab(val label: String, val icon: ImageVector) {
    ALMANAC("Home", Icons.Filled.Home),
    CHART("Chart", ChartIcon),
    TRADE("Trade", Icons.Filled.List),
    PNL("P&L", CalendarIcon),
    TOOLS("Options", Icons.Filled.Search),
    LAB("Research", Icons.Filled.DateRange),
    CABINET("More", Icons.Filled.Menu),
}

@Composable
fun Root(activity: MainActivity) {
    val model: AppModel = viewModel()
    // After an erase the view model outlives the data: reload it so the gates and the mode start clean.
    val wiped by wipes.collectAsState()
    LaunchedEffect(wiped) { if (wiped > 0) model.resetAfterWipe() }
    val settings by model.settings.collectAsState()
    val locked by SessionLock.locked.collectAsState()
    val findings by model.integrity.collectAsState()

    IraAlgoTheme(settings.theme) {
        // Status and navigation bar icons follow the app's theme, not only the phone's.
        val dark = com.optionslab.app.ui.theme.LocalPalette.current.dark
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val compromised = findings.isNotEmpty() && Integrity.compromised(findings)
        // The app stopped unexpectedly last time: show why, once, so it can be reported.
        var crash by remember { mutableStateOf(runCatching { java.io.File(activity.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()) }
        crash?.let { text ->
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            com.optionslab.app.ui.components.AlertDialog(
                onDismissRequest = {},
                properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
                title = { Text("IraAlgo closed unexpectedly last time", style = Type.title) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Text("This is what went wrong. Copy it and send it to get it fixed; it contains no keys, PIN or balances.", style = Type.bodySmall)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(text, style = Type.bodySmall.copy(fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy") } },
                dismissButton = { androidx.compose.material3.TextButton({
                    runCatching { java.io.File(activity.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE).delete() }; crash = null
                }) { Text("Dismiss") } },
            )
        }
        var vaultBad by remember { mutableStateOf(SecurePrefs.unreadable) }
        if (vaultBad) {
            VaultUnreadable(onRetry = { vaultBad = !SecurePrefs.reload() }, onErase = { eraseEverything(); vaultBad = false })
            return@IraAlgoTheme
        }
        if (compromised && settings.refuseCompromised) {
            RefusedScreen(findings.filter { it.severity == Integrity.Severity.DANGER }.map { "${it.name}: ${it.detail}" }) { activity.finishAndRemoveTask() }
            return@IraAlgoTheme
        }
        val brokerNow by model.broker.collectAsState()
        // The idle lock: checked every few seconds while the session is open.
        LaunchedEffect(locked) { while (!locked) { delay(5_000); SessionLock.checkIdle() } }
        // Battery: checked on every start; the app stays closed until it is unrestricted.
        val appCtx = androidx.compose.ui.platform.LocalContext.current
        var batteryOk by remember(locked) { mutableStateOf(com.optionslab.app.ui.screens.BatteryCheck.unrestricted(appCtx)) }
        AnimatedContent(
            targetState = locked || !PinLock.isSet,
            // The keyboard takes its room from every screen (edge-to-edge draws under it otherwise),
            // so the field being typed in scrolls into view above it.
            modifier = Modifier.fillMaxSize().imePadding(),
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
            label = "seal",
        ) { sealed ->
            // Biometrics are offered only once the device check has run (a report is never empty).
            if (sealed) Gate(activity, model, settings, compromised, checked = findings.isNotEmpty())
            else if (!batteryOk) com.optionslab.app.ui.screens.BatteryScreen { batteryOk = true }
            // TESTING: the Zerodha setup gate is switched off (SKIP_ZERODHA_GATE) so other features can be tried
            // without linking; the Zerodha page in More still links an account. TODO.md L4: switch it back on.
            else if (!brokerNow.linked && !SKIP_ZERODHA_GATE) ConnectGate(model)
            else Main(model)
        }
    }
}

@Composable
private fun Gate(activity: MainActivity, model: AppModel, settings: AppSettings, compromised: Boolean, checked: Boolean) {
    val setup = !PinLock.isSet
    var notice by remember { mutableStateOf<String?>(if (compromised) "This phone failed the security check (More → Security shows why); unlock with your PIN." else null) }
    val kind = remember { BiometricGate.available(activity) }
    // Just created a PIN: ask once whether to unlock with the fingerprint.
    var offerBio by remember { mutableStateOf(false) }
    var offerError by remember { mutableStateOf<String?>(null) }
    // A compromised device can fake a biometric callback; it cannot fake PBKDF2.
    val bioAllowed = settings.biometric && checked && !compromised && kind != BiometricGate.Kind.NONE &&
        (kind == BiometricGate.Kind.STRONG || settings.allowWeakFace)
    val label = if (!bioAllowed) null else "Use fingerprint"
    // Switched on but not offered: say why rather than silently asking for the PIN.
    LaunchedEffect(settings.biometric, checked, kind) {
        if (notice == null && settings.biometric && checked && !compromised && kind == BiometricGate.Kind.NONE)
            notice = "No fingerprint is set up on this phone any more; unlock with your PIN."
    }

    if (offerBio) {
        BiometricOffer(
            activity = activity,
            error = offerError,
            onUse = {
                // Prove it works on this phone before switching it on.
                runCatching { if (kind == BiometricGate.Kind.STRONG) BiometricGate.enrol() }
                BiometricGate.authenticate(activity, allowWeakFace = false) { out ->
                    when (out) {
                        BiometricGate.Outcome.Success -> {
                            model.update { it.copy(biometric = true, allowWeakFace = false) }
                            offerBio = false; SessionLock.unlock()
                        }
                        is BiometricGate.Outcome.Failed -> offerError = "Fingerprint did not work: ${out.why}"
                        else -> Unit
                    }
                }
            },
            onSkip = { model.update { it.copy(biometric = false) }; offerBio = false; SessionLock.unlock() },
        )
        return
    }

    LockScreen(
        setup = setup,
        biometricLabel = label,
        onBiometric = {
            BiometricGate.authenticate(activity, settings.allowWeakFace) { out ->
                when (out) {
                    BiometricGate.Outcome.Success -> SessionLock.unlock()
                    is BiometricGate.Outcome.Invalidated -> {
                        notice = out.why
                        model.update { it.copy(biometric = false) }
                    }
                    is BiometricGate.Outcome.Failed -> notice = out.why
                    BiometricGate.Outcome.UsePin -> Unit
                }
            }
        },
        onPin = { pin ->
            val r = PinLock.verify(pin, settings.wipeOnExhaustion)
            when (r) {
                PinLock.Result.Ok -> SessionLock.unlock()
                // The check runs off the main thread; the erase (cookies, web storage) belongs on it.
                PinLock.Result.Wiped -> android.os.Handler(android.os.Looper.getMainLooper()).post { eraseEverything() }
                else -> Unit
            }
            r
        },
        onCreate = { pin ->
            try {
                PinLock.setPin(pin)
                if (!compromised && (kind != BiometricGate.Kind.NONE || BiometricGate.notEnrolled(activity))) offerBio = true
                else SessionLock.unlock()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        },
        notice = notice,
        calm = settings.reduceMotion,
    )
}

/** After the PIN is set: unlock with the fingerprint as well? */
@Composable
private fun BiometricOffer(activity: MainActivity, error: String?, onUse: () -> Unit, onSkip: () -> Unit) {
    val p = LocalPalette.current
    var enrolled by remember { mutableStateOf(BiometricGate.available(activity) != BiometricGate.Kind.NONE) }
    // Re-checked every second, so adding a fingerprint in Settings and coming back updates this screen.
    PollWhileStarted { while (true) { enrolled = BiometricGate.available(activity) != BiometricGate.Kind.NONE; delay(1000) } }
    Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(p.chip, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Face, contentDescription = null, tint = p.ink, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Unlock with your fingerprint?", style = Type.masthead.copy(color = p.ink, fontSize = 22.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(if (enrolled) "Use the fingerprint already set up on this phone instead of typing your PIN. Your PIN still works, and you can change this in More → Security."
            else "This phone has no fingerprint added yet. Add one in the phone's Settings, then come back and tap Use.",
            style = Type.bodySmall.copy(color = p.inkSoft), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (error != null) Text(error, style = Type.bodySmall.copy(color = p.oxblood), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
        if (enrolled) com.optionslab.app.ui.components.BrassButton("Use fingerprint", Modifier.fillMaxWidth(), onClick = onUse)
        else com.optionslab.app.ui.components.BrassButton("Open phone settings", Modifier.fillMaxWidth()) {
            runCatching {
                activity.startActivity(if (Build.VERSION.SDK_INT >= 30) android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL)
                    else android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        Spacer(Modifier.height(10.dp))
        com.optionslab.app.ui.components.BrassButton("Not now", Modifier.fillMaxWidth(), tone = p.inkSoft, onClick = onSkip)
    }
}

/** Destroy the vault key and every personal file. Market data is public and stays. */
/** The settings vault exists but cannot be decrypted: fail closed, never "choose a new PIN". */
@Composable
private fun VaultUnreadable(onRetry: () -> Unit, onErase: () -> Unit) {
    val p = com.optionslab.app.ui.theme.LocalPalette.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(p.paper).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("THE VAULT IS SEALED SHUT", style = com.optionslab.app.ui.theme.Type.title.copy(color = p.oxblood))
        Spacer(Modifier.height(12.dp))
        Text("IraAlgo's encrypted settings could not be read on this start. That is usually a passing Android Keystore fault; " +
            "it can also mean the file was altered. Nothing has been changed and no new PIN can be set over it.",
            style = com.optionslab.app.ui.theme.Type.body.copy(color = p.ink))
        Spacer(Modifier.height(20.dp))
        com.optionslab.app.ui.components.BrassButton("Try again", Modifier.fillMaxWidth(), onClick = onRetry)
        Spacer(Modifier.height(10.dp))
        com.optionslab.app.ui.components.BrassButton(if (confirm) "Tap again: erase ALL IraAlgo data" else "Erase everything and start again",
            Modifier.fillMaxWidth(), tone = p.oxblood) { if (confirm) onErase() else confirm = true }
    }
}

fun eraseEverything() {
    com.optionslab.app.data.Ledger.wipe()
    com.optionslab.app.data.Alarms.wipe()
    com.optionslab.app.data.Paper.wipe()
    com.optionslab.app.data.OrbArms.wipe()
    com.optionslab.app.data.Protections.wipe()
    com.optionslab.app.data.TradeBook.wipe()
    com.optionslab.app.data.Journal.wipe()
    com.optionslab.app.data.Strategies.wipe()
    com.optionslab.app.data.History.wipe()
    com.optionslab.app.data.Market.wipe()
    runCatching { com.optionslab.app.data.Broker.forget() }
    runCatching { com.optionslab.app.data.Store.wipeDeviceData() }
    runCatching {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
    SecurePrefs.wipe()
    BiometricGate.forget()
    Vault.destroy()
    SessionLock.lock()
    wipes.value = wipes.value + 1
}

/** Bumped by [eraseEverything]: the screen state held in memory is dropped with the data. */
val wipes = kotlinx.coroutines.flow.MutableStateFlow(0)

@Composable
private fun Main(model: AppModel) {
    val p = LocalPalette.current
    val settings by model.settings.collectAsState()
    val requested by MainActivity.tabRequests.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.ALMANAC) }
    var cabinetPage by rememberSaveable { mutableStateOf<String?>(null) }
    var labPage by rememberSaveable { mutableStateOf("trials") }
    var tradePage by rememberSaveable { mutableStateOf("account") }
    var toolsView by rememberSaveable { mutableStateOf("chain") }
    var chartAsk by remember { mutableStateOf("BANKNIFTY" to "NSE") }
    // Bumped on every ask, so asking again for the same symbol after browsing another one still switches back.
    var chartNonce by remember { mutableStateOf(0) }
    var chartOpened by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab == Tab.CHART) chartOpened = true }
    val message by model.message.collectAsState()
    val kiteLogin by model.showKiteLogin.collectAsState()
    // Trading (the Ticket and Trade tabs, live or paper) appears only once a Zerodha account is linked.
    val broker by model.broker.collectAsState()
    val linked = broker.linked
    val tabs = if (linked) Tab.entries else Tab.entries.filter { it != Tab.TRADE }
    LaunchedEffect(linked) {
        if (!linked && tab !in tabs) tab = Tab.ALMANAC
        if (!linked && settings.live) model.update { it.copy(mode = "sandbox", allowRealOrders = false) }
    }

    LaunchedEffect(requested) {
        when (requested) {
            "almanac" -> tab = Tab.ALMANAC
            "ticket" -> { tab = Tab.TOOLS; toolsView = "expiryput" }
            "chart" -> tab = Tab.CHART
            "trade" -> if (linked) { tab = Tab.TRADE; tradePage = "account" } else { tab = Tab.CABINET; cabinetPage = "broker" }
            "strategy" -> if (linked) { tab = Tab.TRADE; tradePage = "strategies" } else { tab = Tab.CABINET; cabinetPage = "broker" }
            "health" -> { tab = Tab.LAB; labPage = "health" }
            "trials" -> { tab = Tab.LAB; labPage = "trials" }
            "tools" -> tab = Tab.TOOLS
            "pnl" -> tab = Tab.PNL
            "cabinet" -> { tab = Tab.CABINET; cabinetPage = "data" }
            "alarms" -> { tab = Tab.CABINET; cabinetPage = "alarms" }
            "broker" -> { tab = Tab.CABINET; cabinetPage = "broker" }
        }
        MainActivity.tabRequests.value = null
    }
    // "Close…" on a Zerodha position's notification: that position's close popup, over the Trade tab.
    val closeAsk by MainActivity.closeRequests.collectAsState()
    LaunchedEffect(closeAsk) {
        val sym = closeAsk ?: return@LaunchedEffect
        if (linked) { tab = Tab.TRADE; tradePage = "account"; model.openLiveClose(sym) }
        MainActivity.closeRequests.value = null
    }

    // The market watch runs by itself on market days; opening the app restarts it if Android stopped it.
    LaunchedEffect(Unit) { model.ensureWatch() }
    // Price the NIFTY chain in the background, so the Options tab opens with it ready.
    LaunchedEffect(Unit) { delay(1500); if (model.tools.value is Load.Idle) model.loadTools("NIFTY", quiet = true) }

    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !SecurePrefs.getBoolean("asked.notify", false)) {
            SecurePrefs.put("asked.notify", true)
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = cabinetPage != null || tab != Tab.ALMANAC) {
        if (cabinetPage != null) cabinetPage = null else tab = Tab.ALMANAC
    }

    Parchment(ruled = true) {
        Column(Modifier.fillMaxSize()) {
            // Turned sideways, the chart takes the whole screen.
            val fullChart = tab == Tab.CHART && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (!fullChart) Masthead(settings.live, settings.reduceMotion, linked, onMode = { live -> model.update { it.copy(mode = if (live) "live" else "sandbox", allowRealOrders = live) } })
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        if (settings.reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        else (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it * dir / 12 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(180)) { -it * dir / 12 } + fadeOut(tween(140)))
                    },
                    label = "page",
                ) { t ->
                    when (t) {
                        Tab.ALMANAC -> AlmanacScreen(model, onGo = { dest ->
                            when (dest) {
                                "trials" -> { tab = Tab.LAB; labPage = "trials" }
                                "trade" -> { tab = Tab.TRADE; tradePage = "account" }
                                "strategy" -> { tab = Tab.TRADE; tradePage = "strategies" }
                                "ticket" -> { tab = Tab.TOOLS; toolsView = "expiryput" }
                                "chart" -> { chartAsk = "BANKNIFTY" to "NSE"; chartNonce++; tab = Tab.CHART }
                                "health" -> { tab = Tab.LAB; labPage = "health" }
                                else -> { tab = Tab.CABINET; cabinetPage = dest }
                            }
                        })
                        Tab.CHART -> Box(Modifier.fillMaxSize())   // the chart itself is kept alive below
                        Tab.TRADE -> TradeHub(model, tradePage) { tradePage = it }
                        Tab.PNL -> com.optionslab.app.ui.screens.PnlCalendarScreen(model)
                        Tab.TOOLS -> ToolsScreen(model, toolsView, { toolsView = it }) { s, e -> chartAsk = s to e; chartNonce++; tab = Tab.CHART }
                        Tab.LAB -> LabScreen(model, labPage) { labPage = it }
                        Tab.CABINET -> CabinetScreen(model, cabinetPage) { cabinetPage = it }
                    }
                }
                // The chart stays loaded once opened, so returning to it is instant.
                if (chartOpened) Box(if (tab == Tab.CHART) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) {
                    com.optionslab.app.ui.screens.ChartScreen(model, chartAsk.first, chartAsk.second, visible = tab == Tab.CHART, ask = chartNonce)
                }
            }
            // While typing, the tab bar steps aside so the field keeps the room.
            val typing = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            if (!fullChart && !typing) TabBar(tab, tabs) { if (it == tab && it == Tab.CABINET) cabinetPage = null; tab = it }
        }
        // Order reviews open over any page, wherever the order was asked for.
        // First use only: a short guide the first time the app opens after Zerodha is linked, never again.
        var tour by remember { mutableStateOf(!SecurePrefs.getBoolean(com.optionslab.app.ui.screens.GETTING_STARTED, false)) }
        val again by com.optionslab.app.ui.screens.showGettingStarted.collectAsState()
        if (tour || again) com.optionslab.app.ui.screens.GettingStarted(onGo = { dest ->
            SecurePrefs.put(com.optionslab.app.ui.screens.GETTING_STARTED, true); tour = false
            com.optionslab.app.ui.screens.showGettingStarted.value = false
            when (dest) {
                "orb" -> tab = Tab.ALMANAC
                "chart" -> { chartAsk = "BANKNIFTY" to "NSE"; chartNonce++; tab = Tab.CHART }
                "trade" -> { tab = Tab.TRADE; tradePage = "account" }
            }
        })
        com.optionslab.app.ui.screens.OrderReviewDialog(model)
        // Tapping any order, position or trade opens its close / cancel popup.
        com.optionslab.app.ui.screens.RowActionPopup(model)
        if (kiteLogin) com.optionslab.app.ui.screens.KiteLoginPage(model)
        val askPin by model.askLoginPin.collectAsState()
        if (askPin) com.optionslab.app.ui.screens.LoginPinDialog(model)
        // Every event, success or error, drops in at the top of the screen.
        com.optionslab.app.ui.components.AlertBanner()
    }
}

/** Testing only: open the app without the Zerodha setup gate. Set back to false before going live. */
const val SKIP_ZERODHA_GATE = true

/** Until a Zerodha account is linked the app shows only this: no tabs, no close. */
@Composable
private fun ConnectGate(model: AppModel) {
    val message by model.message.collectAsState()
    val kiteLogin by model.showKiteLogin.collectAsState()
    val askPin by model.askLoginPin.collectAsState()
    LaunchedEffect(Unit) { model.refreshBroker() }
    Box(Modifier.fillMaxSize()) {
        com.optionslab.app.ui.screens.ConnectZerodhaScreen(model)
        if (kiteLogin) com.optionslab.app.ui.screens.KiteLoginPage(model)
        if (askPin) com.optionslab.app.ui.screens.LoginPinDialog(model)
        com.optionslab.app.ui.components.AlertBanner()
    }
}

@Composable
private fun Masthead(live: Boolean, calm: Boolean, linked: Boolean, onMode: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var confirmLive by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Market.now()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = Market.now() } }
    val open = Market.isOpen()
    Column(Modifier.fillMaxWidth().background(p.paperDeep).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).then(if (p.dark) Modifier.background(Color.White, RoundedCornerShape(8.dp)) else Modifier).padding(3.dp),
                contentAlignment = Alignment.Center,
            ) { com.optionslab.app.ui.components.BrandEmblem(24.dp, calm = true) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("IraAlgo", style = Type.masthead.copy(color = p.ink, fontSize = 20.sp), maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)) + "  ·  ",
                        style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
                    StatusDot(if (open) p.verdigris else p.inkFaint, pulsing = open && !calm, modifier = Modifier.size(6.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (open) "Market open" else "Market closed", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
                }
            }
            // The trading mode, on every screen. Tap to switch; going live asks first.
            if (linked) {
                val tint = if (live) p.oxblood else p.verdigris
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(if (live) p.oxblood else tint.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .selectable(selected = live, role = androidx.compose.ui.semantics.Role.Switch) {
                            if (live) onMode(false) else confirmLive = true
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Box(Modifier.size(7.dp).background(if (live) Color.White else tint, androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (live) "LIVE TRADING" else "PAPER TRADING",
                        style = Type.label.copy(color = if (live) Color.White else tint, fontSize = 12.sp, letterSpacing = 0.4.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1)
                    Text("  ▾", style = Type.label.copy(color = if (live) Color.White else tint, fontSize = 11.sp))
                }
            }
        }
        // A red line under the bar while live, so real-money mode is never mistaken.
        Box(Modifier.fillMaxWidth().height(if (linked && live) 2.dp else 1.dp).background(if (linked && live) p.oxblood else p.rule))
    }
    if (confirmLive) com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = { confirmLive = false },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Switch to live trading?", style = Type.title) },
        text = { Text("Prices, positions and orders will come from your Zerodha account. Orders you send will use real money. Each order still needs your review, a long press and your PIN or fingerprint.", style = Type.bodySmall) },
        confirmButton = { androidx.compose.material3.TextButton({ confirmLive = false; onMode(true) }) { Text("Go live", color = p.oxblood) } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmLive = false }) { Text("Stay on paper") } },
    )
}

@Composable
private fun TabBar(current: Tab, tabs: List<Tab>, onPick: (Tab) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.paperDeep).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { t ->
                val sel = t == current
                Column(
                    Modifier.weight(1f)
                        .selectable(selected = sel, role = androidx.compose.ui.semantics.Role.Tab) { onPick(t) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, contentDescription = null, tint = if (sel) p.ink else p.inkFaint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = Type.label.copy(fontSize = 11.sp, color = if (sel) p.ink else p.inkFaint,
                        fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** A message that slides up from the bottom, then withdraws. */
@Composable
private fun Toast(text: String?, onGone: () -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(text) { if (text != null) { delay(4200); onGone() } }
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        enter = androidx.compose.animation.slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(160)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().background(p.ink, RoundedCornerShape(12.dp)).clickable { onGone() }.padding(14.dp),
            ) { Text(text ?: "", style = Type.bodySmall.copy(color = p.paper)) }
        }
    }
}
