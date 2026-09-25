package com.optionslab.app.ui

import android.Manifest
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
import com.optionslab.app.ui.screens.TicketScreen
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

enum class Tab(val label: String, val icon: ImageVector) {
    ALMANAC("Home", Icons.Filled.Home),
    TICKET("Ticket", Icons.Filled.Edit),
    TRADE("Trade", Icons.Filled.List),
    TOOLS("Options", Icons.Filled.Search),
    LAB("Research", Icons.Filled.DateRange),
    CABINET("More", Icons.Filled.Menu),
}

@Composable
fun Root(activity: MainActivity) {
    val model: AppModel = viewModel()
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
        AnimatedContent(
            targetState = locked || !PinLock.isSet,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
            label = "seal",
        ) { sealed ->
            // Biometrics are offered only once the device check has run (a report is never empty).
            if (sealed) Gate(activity, model, settings, compromised, checked = findings.isNotEmpty())
            else if (!brokerNow.linked) ConnectGate(model)
            else Main(model)
        }
    }
}

@Composable
private fun Gate(activity: MainActivity, model: AppModel, settings: AppSettings, compromised: Boolean, checked: Boolean) {
    val setup = !PinLock.isSet
    var notice by remember { mutableStateOf<String?>(if (compromised) "This device shows signs of compromise; biometrics are withdrawn and the PIN is required." else null) }
    val kind = remember { BiometricGate.available(activity) }
    // A compromised device can fake a biometric callback; it cannot fake PBKDF2.
    val bioAllowed = settings.biometric && checked && !compromised &&
        (kind == BiometricGate.Kind.STRONG || (kind == BiometricGate.Kind.WEAK && settings.allowWeakFace))
    val label = if (!bioAllowed) null else if (kind == BiometricGate.Kind.STRONG) "Use fingerprint or face" else "Use face unlock"

    LockScreen(
        setup = setup,
        biometricLabel = label,
        onBiometric = {
            BiometricGate.authenticate(activity, settings.allowWeakFace) { out ->
                when (out) {
                    BiometricGate.Outcome.Success -> SessionLock.unlock()
                    is BiometricGate.Outcome.Invalidated -> {
                        notice = out.why
                        SecurePrefs.put("sec.bio", false)
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
                PinLock.Result.Wiped -> eraseEverything()
                else -> Unit
            }
            r
        },
        onCreate = { pin ->
            try {
                PinLock.setPin(pin)
                // Fingerprint / face on from the start when the phone has it; it can be turned off in More -> Security.
                if (kind != BiometricGate.Kind.NONE && !compromised) {
                    val ok = kind != BiometricGate.Kind.STRONG || runCatching { BiometricGate.enrol() }.isSuccess
                    if (ok) model.update { it.copy(biometric = true, allowWeakFace = kind == BiometricGate.Kind.WEAK || it.allowWeakFace) }
                }
                SessionLock.unlock()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        },
        notice = notice,
        calm = settings.reduceMotion,
    )
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
}

@Composable
private fun Main(model: AppModel) {
    val p = LocalPalette.current
    val settings by model.settings.collectAsState()
    val requested by MainActivity.tabRequests.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.ALMANAC) }
    var cabinetPage by rememberSaveable { mutableStateOf<String?>(null) }
    var labPage by rememberSaveable { mutableStateOf("trials") }
    var tradePage by rememberSaveable { mutableStateOf("account") }
    val message by model.message.collectAsState()
    val kiteLogin by model.showKiteLogin.collectAsState()
    // Trading (the Ticket and Trade tabs, live or paper) appears only once a Zerodha account is linked.
    val broker by model.broker.collectAsState()
    val linked = broker.linked
    val tabs = if (linked) Tab.entries else Tab.entries.filter { it != Tab.TICKET && it != Tab.TRADE }
    LaunchedEffect(linked) {
        if (!linked && tab !in tabs) tab = Tab.ALMANAC
        if (!linked && settings.live) model.update { it.copy(mode = "sandbox") }
    }

    LaunchedEffect(requested) {
        when (requested) {
            "almanac" -> tab = Tab.ALMANAC
            "ticket" -> if (linked) tab = Tab.TICKET else { tab = Tab.CABINET; cabinetPage = "broker" }
            "trade" -> if (linked) { tab = Tab.TRADE; tradePage = "account" } else { tab = Tab.CABINET; cabinetPage = "broker" }
            "strategy" -> if (linked) { tab = Tab.TRADE; tradePage = "strategies" } else { tab = Tab.CABINET; cabinetPage = "broker" }
            "health" -> { tab = Tab.LAB; labPage = "health" }
            "trials" -> { tab = Tab.LAB; labPage = "trials" }
            "tools" -> tab = Tab.TOOLS
            "cabinet" -> { tab = Tab.CABINET; cabinetPage = "data" }
            "alarms" -> { tab = Tab.CABINET; cabinetPage = "alarms" }
            "broker" -> { tab = Tab.CABINET; cabinetPage = "broker" }
        }
        MainActivity.tabRequests.value = null
    }

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
            Masthead(settings.live, settings.reduceMotion, linked, onMode = { live -> model.update { it.copy(mode = if (live) "live" else "sandbox") } })
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
                                "ticket" -> if (linked) tab = Tab.TICKET else { tab = Tab.CABINET; cabinetPage = "broker" }
                                "health" -> { tab = Tab.LAB; labPage = "health" }
                                else -> { tab = Tab.CABINET; cabinetPage = dest }
                            }
                        })
                        Tab.TICKET -> TicketScreen(model)
                        Tab.TRADE -> TradeHub(model, tradePage) { tradePage = it }
                        Tab.TOOLS -> ToolsScreen(model)
                        Tab.LAB -> LabScreen(model, labPage) { labPage = it }
                        Tab.CABINET -> CabinetScreen(model, cabinetPage) { cabinetPage = it }
                    }
                }
                Toast(message) { model.message.value = null }
            }
            TabBar(tab, tabs) { if (it == tab && it == Tab.CABINET) cabinetPage = null; tab = it }
        }
        // Order reviews open over any page, wherever the order was asked for.
        com.optionslab.app.ui.screens.OrderReviewDialog(model)
        if (kiteLogin) com.optionslab.app.ui.screens.KiteLoginPage(model)
        val askPin by model.askLoginPin.collectAsState()
        if (askPin) com.optionslab.app.ui.screens.LoginPinDialog(model)
    }
}

/** Until a Zerodha account is linked the app shows only this: no tabs, no close. */
@Composable
private fun ConnectGate(model: AppModel) {
    val message by model.message.collectAsState()
    val kiteLogin by model.showKiteLogin.collectAsState()
    val askPin by model.askLoginPin.collectAsState()
    LaunchedEffect(Unit) { model.refreshBroker() }
    Box(Modifier.fillMaxSize()) {
        com.optionslab.app.ui.screens.ConnectZerodhaScreen(model)
        Toast(message) { model.message.value = null }
        if (kiteLogin) com.optionslab.app.ui.screens.KiteLoginPage(model)
        if (askPin) com.optionslab.app.ui.screens.LoginPinDialog(model)
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
                Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.background(if (open) p.verdigris.copy(alpha = 0.12f) else p.chip, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                StatusDot(if (open) p.verdigris else p.inkFaint, pulsing = open && !calm)
                Spacer(Modifier.width(6.dp))
                Text(if (open) "NSE open" else "NSE closed", style = Type.label.copy(color = if (open) p.verdigris else p.inkSoft, fontSize = 12.sp))
            }
        }
        // Trading mode, on every screen: a Paper | Live switch once Zerodha is linked.
        if (linked) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Live trading · Zerodha, real money" else "Paper trading · simulated, no real orders",
                    style = Type.bodySmall.copy(color = if (live) p.oxblood else p.inkSoft, fontSize = 12.sp,
                        fontWeight = if (live) androidx.compose.ui.text.font.FontWeight.SemiBold else null),
                    modifier = Modifier.weight(1f), maxLines = 1)
                Row(Modifier.background(p.chip, RoundedCornerShape(50)).padding(2.dp)) {
                    listOf(false to "Paper", true to "Live").forEach { (isLive, label) ->
                        val sel = live == isLive
                        Text(label, style = Type.label.copy(color = if (sel) Color.White else p.inkSoft, fontSize = 12.sp),
                            modifier = Modifier
                                .background(if (sel) (if (isLive) p.oxblood else p.verdigris) else Color.Transparent, RoundedCornerShape(50))
                                .selectable(selected = sel, role = androidx.compose.ui.semantics.Role.RadioButton) {
                                    if (!sel) { if (isLive) confirmLive = true else onMode(false) }
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }
        }
        // A red line under the bar while live, so real-money mode is never mistaken.
        Box(Modifier.fillMaxWidth().height(if (linked && live) 2.dp else 1.dp).background(if (linked && live) p.oxblood else p.rule))
    }
    if (confirmLive) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmLive = false },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
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
