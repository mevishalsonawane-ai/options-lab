package com.optionslab.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
    ALMANAC("Almanac", Icons.Filled.Home),
    TICKET("Ticket", Icons.Filled.Edit),
    TRADE("Trade", Icons.Filled.ShoppingCart),
    TOOLS("Tools", Icons.Filled.Search),
    LAB("Lab", Icons.Filled.DateRange),
    CABINET("Cabinet", Icons.Filled.Build),
}

@Composable
fun Root(activity: MainActivity) {
    val model: AppModel = viewModel()
    val settings by model.settings.collectAsState()
    val locked by SessionLock.locked.collectAsState()
    val findings by model.integrity.collectAsState()

    IraAlgoTheme(settings.theme) {
        val compromised = findings.isNotEmpty() && Integrity.compromised(findings)
        if (compromised && settings.refuseCompromised) {
            RefusedScreen(findings.filter { it.severity == Integrity.Severity.DANGER }.map { "${it.name}: ${it.detail}" }) { activity.finishAndRemoveTask() }
            return@IraAlgoTheme
        }
        AnimatedContent(
            targetState = locked || !PinLock.isSet,
            transitionSpec = { (fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 1.04f)) togetherWith (fadeOut(tween(400)) + scaleOut(tween(400), targetScale = 0.96f)) },
            label = "seal",
        ) { sealed ->
            if (sealed) Gate(activity, settings, compromised) else Main(model)
        }
    }
}

@Composable
private fun Gate(activity: MainActivity, settings: AppSettings, compromised: Boolean) {
    val setup = !PinLock.isSet
    var notice by remember { mutableStateOf<String?>(if (compromised) "This device shows signs of compromise; biometrics are withdrawn and the PIN is required." else null) }
    val kind = remember { BiometricGate.available(activity) }
    // A compromised device can fake a biometric callback; it cannot fake PBKDF2.
    val bioAllowed = settings.biometric && !compromised &&
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
fun eraseEverything() {
    com.optionslab.app.data.Ledger.wipe()
    com.optionslab.app.data.Alarms.wipe()
    com.optionslab.app.data.Paper.wipe()
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
    val message by model.message.collectAsState()
    val kiteLogin by model.showKiteLogin.collectAsState()

    LaunchedEffect(requested) {
        when (requested) {
            "almanac" -> tab = Tab.ALMANAC
            "ticket" -> tab = Tab.TICKET
            "trade" -> tab = Tab.TRADE
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
            Masthead(settings.live)
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        if (settings.reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        else (slideInHorizontally(tween(380)) { it * dir / 3 } + fadeIn(tween(380))) togetherWith
                            (slideOutHorizontally(tween(320)) { -it * dir / 3 } + fadeOut(tween(260)))
                    },
                    label = "page",
                ) { t ->
                    when (t) {
                        Tab.ALMANAC -> AlmanacScreen(model, onGo = { dest ->
                            when (dest) {
                                "trials" -> { tab = Tab.LAB; labPage = "trials" }
                                "ticket" -> tab = Tab.TICKET
                                "health" -> { tab = Tab.LAB; labPage = "health" }
                                else -> { tab = Tab.CABINET; cabinetPage = dest }
                            }
                        })
                        Tab.TICKET -> TicketScreen(model)
                        Tab.TRADE -> TradeScreen(model)
                        Tab.TOOLS -> ToolsScreen(model)
                        Tab.LAB -> LabScreen(model, labPage) { labPage = it }
                        Tab.CABINET -> CabinetScreen(model, cabinetPage) { cabinetPage = it }
                    }
                }
                Toast(message) { model.message.value = null }
            }
            TabBar(tab) { if (it == tab && it == Tab.CABINET) cabinetPage = null; tab = it }
        }
        if (kiteLogin) com.optionslab.app.ui.screens.KiteLoginPage(model)
    }
}

@Composable
private fun Masthead(live: Boolean) {
    val p = LocalPalette.current
    var now by remember { mutableStateOf(Market.now()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = Market.now() } }
    val open = Market.isOpen()
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.optionslab.app.ui.components.BrandEmblem(26.dp, calm = true)
            Spacer(Modifier.width(8.dp))
            Text("THE IRAALGO ALMANAC", style = Type.masthead.copy(color = p.ink, fontSize = 18.sp, letterSpacing = 3.sp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm 'IST'", Locale.ENGLISH)),
                style = Type.italic.copy(color = p.inkSoft, fontSize = 14.sp))
            Spacer(Modifier.width(8.dp))
            StatusDot(if (open) p.verdigris else p.inkFaint, pulsing = open)
            Spacer(Modifier.width(4.dp))
            Text(if (open) "MARKET OPEN" else "MARKET SHUT", style = Type.label.copy(color = if (open) p.verdigris else p.inkFaint, fontSize = 9.sp))
            Spacer(Modifier.width(8.dp))
            Text(if (live) "· LIVE · ZERODHA" else "· SANDBOX", style = Type.label.copy(color = if (live) p.oxblood else p.inkFaint, fontSize = 9.sp))
        }
        Canvas(Modifier.fillMaxWidth().height(7.dp).padding(top = 3.dp)) {
            drawLine(p.ink, Offset(0f, 0f), Offset(size.width, 0f), 2f)
            drawLine(p.ink, Offset(0f, 4.dp.toPx()), Offset(size.width, 4.dp.toPx()), 0.7f)
        }
    }
}

@Composable
private fun TabBar(current: Tab, onPick: (Tab) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.paperDeep.copy(alpha = 0.96f)).navigationBarsPadding()) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) { drawLine(p.brass, Offset(0f, 1f), Offset(size.width, 1f), 1.5f) }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val sel = t == current
                val s by animateFloatAsState(if (sel) 1.12f else 1f, tween(260), label = "tab")
                Column(
                    Modifier.clickable { onPick(t) }.padding(horizontal = 6.dp, vertical = 2.dp).scale(s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(34.dp).background(if (sel) p.brass.copy(alpha = 0.22f) else p.paperDeep.copy(alpha = 0f), RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(t.icon, contentDescription = t.label, tint = if (sel) p.brass else p.inkFaint, modifier = Modifier.size(20.dp)) }
                    Text(t.label.uppercase(), style = Type.label.copy(fontSize = 9.sp, color = if (sel) p.ink else p.inkFaint))
                }
            }
        }
    }
}

/** A slip of paper that slides up with a message, then withdraws. */
@Composable
private fun Toast(text: String?, onGone: () -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(text) { if (text != null) { delay(4200); onGone() } }
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().background(p.ink.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).clickable { onGone() }.padding(14.dp),
            ) { Text(text ?: "", style = Type.bodySmall.copy(color = p.paper)) }
        }
    }
}
