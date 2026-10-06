package com.optionslab.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.optionslab.app.ui.components.TextButton
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.Market
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.data.Store
import com.optionslab.app.security.BiometricGate
import com.optionslab.app.security.Integrity
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.InkProgress
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.eraseEverything
import com.optionslab.app.ui.num
import com.optionslab.app.ui.pct
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.Jobs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Manifest
import java.time.format.DateTimeFormatter


/** An alarm's "EXCHANGE:SYMBOL" (compiled once, not at every keystroke). */
private val ALARM_SYMBOL = Regex("^[A-Z]{2,4}:[A-Z0-9&_-]{1,32}$")

@Composable
fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    // The whole row is the switch: a 48 dp target (the bare switch was 52x32 dp) read with its title by TalkBack.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(color = p.ink))
            if (sub != null) Text(sub, style = Type.italic.copy(color = p.inkSoft, fontSize = 13.sp))
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, null, colors = SwitchDefaults.colors(
            checkedThumbColor = p.card, checkedTrackColor = p.brass, uncheckedThumbColor = p.inkFaint, uncheckedTrackColor = p.paperDeep,
            uncheckedBorderColor = p.rule))
    }
}

private val secure get() = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)

// ---- Alarms ----------------------------------------------------------------------

@Composable
fun AlarmsPage(model: AppModel) {
    val p = LocalPalette.current
    val alarms by model.alarms.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val quotes by model.quotes.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var symbol by remember { mutableStateOf("NIFTY") }
    var above by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Page {
        item { PageTitle("Alarms", "Checked every minute by the market watch, and whenever the Home screen is open") }
        item {
            LedgerCard(title = "Set an alarm") {
                val idx = listOf("NIFTY", "BANKNIFTY", "INDIAVIX")
                ParamTokens("On", idx.map { it to (it == symbol) } + ("Any instrument" to (symbol !in idx))) { i ->
                    symbol = if (i < idx.size) idx[i] else "NSE:"
                }
                if (symbol !in idx) {
                    OutlinedTextField(symbol, { symbol = it.uppercase().filter { c -> c.isLetterOrDigit() || c in ":-&_" }.take(40) },
                        label = { Text("EXCHANGE:SYMBOL, e.g. NSE:INFY or NFO:NIFTY26SEP24500PE") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Note("Priced from Zerodha, so it rings only in LIVE mode while you are logged in.")
                }
                ParamTokens("When it", listOf("falls below" to !above, "rises above" to above)) { above = it == 1 }
                quotes[symbol]?.let { Note("Now ${num(it.last, 2)}") }
                OutlinedTextField(level, { level = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Level") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(80) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                val symbolOk = symbol in listOf("NIFTY", "BANKNIFTY", "INDIAVIX") || ALARM_SYMBOL.matches(symbol)
                BrassButton("Set alarm", Modifier.fillMaxWidth(), enabled = level.toDoubleOrNull() != null && symbolOk) {
                    model.saveAlarm(PriceAlarm(System.currentTimeMillis(), symbol, above, level.toDouble(), note = note.trim()))
                    level = ""; note = ""
                    model.say("Alarm set. It rings once, then rests 30 minutes.")
                }
            }
        }
        item {
            LedgerCard(title = "Standing alarms") {
                if (alarms.isEmpty()) Note("None yet.")
                alarms.forEachIndexed { i, a ->
                    if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
                    ToggleRow(a.describe(), a.note.ifBlank { null } ?: if (a.firedAtMillis > 0) "last rang ${java.time.Instant.ofEpochMilli(a.firedAtMillis).atZone(com.optionslab.engine.IST).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}" else null,
                        a.enabled) { on -> model.saveAlarm(a.copy(enabled = on)) }
                    BrassButton("Remove", tone = p.inkFaint) { model.removeAlarm(a.id) }
                }
            }
        }
        item {
            val st by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
            LedgerCard(title = "Account P&L alerts") {
                val losses = listOf(0.0, 2_000.0, 5_000.0, 10_000.0, 25_000.0)
                ParamTokens("When today's loss reaches", losses.map { (if (it == 0.0) "off" else "-" + rs(it)) to (it == st.pnlLossAlert) }) { i -> model.update { it.copy(pnlLossAlert = losses[i]) } }
                val gains = listOf(0.0, 5_000.0, 10_000.0, 25_000.0, 50_000.0)
                ParamTokens("When today's profit reaches", gains.map { (if (it == 0.0) "off" else rs(it)) to (it == st.pnlProfitAlert) }) { i -> model.update { it.copy(pnlProfitAlert = gains[i]) } }
                Note("Checked every minute by the market watch from your Zerodha positions (LIVE mode, logged in). Each alert rings once a day.")
            }
        }
        item {
            LedgerCard {
                Note("Risk alerts on an open paper ticket are separate and automatic: one when the index comes within ${pct(model.settings.value.riskAlertPct)} of the breakeven, one when the put goes in the money.")
            }
        }
    }
}

// ---- Data & Harvest ----------------------------------------------------------------

@Composable
fun DataPage(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val job by model.jobState.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val prov by model.provenance.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var confirmWipe by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The figures walk the phone's storage, so they are read off the main thread: on opening,
    // after a harvest, and after a wipe ([reread]). Null until the first read is done.
    var reread by remember { mutableStateOf(0) }
    var device by remember { mutableStateOf<List<java.time.LocalDate>?>(null) }
    var bytes by remember { mutableStateOf<Long?>(null) }
    var manifests by remember { mutableStateOf(mapOf<String, List<Manifest.Entry>>()) }
    LaunchedEffect(job.running, reread) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val d = Store.deviceExpiryDays()
            val b = Store.deviceBytes()
            val m = listOf("NIFTY", "BANKNIFTY").associateWith { Store.manifest(it) }
            Triple(d, b, m)
        }.let { (d, b, m) -> device = d; bytes = b; manifests = m }
    } }
    Page {
        item { PageTitle("Data & Harvest", "No free source serves expired contracts: a day not collected is gone") }
        item {
            LedgerCard(title = "The Record") {
                LedgerLine("Bundled expiry chains", "170 (2023-01-05 .. 2026-04-13)")
                LedgerLine("Captured on this phone", device?.let { d -> "${d.size}" + (d.lastOrNull()?.let { ", latest $it" } ?: "") } ?: "…")
                LedgerLine("Stored on device", bytes?.let { "%.1f MB".format(it / 1e6) } ?: "…")
                ToggleRow("Include this phone's captures", "They extend the record forward - genuinely out of sample", s.includeDeviceSessions) { on ->
                    model.update { it.copy(includeDeviceSessions = on) }; Store.invalidate()
                }
            }
        }
        item {
            LedgerCard(title = "The Harvest") {
                Note("After the close: the instrument master, then every NIFTY and BANKNIFTY option on the nearest three expiries, plus both indices and India VIX, one-minute bars with open interest. On an expiry day the expiring chain is also kept for the backtest.")
                SecurePrefs.getString("harvest.last")?.let { LedgerLine("Last", it) }
                if (job.running) {
                    Spacer(Modifier.height(6.dp))
                    Text(job.stage, style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                    if (job.progress >= 0) InkProgress(job.progress, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                BrassButton("Harvest now", Modifier.fillMaxWidth(), busy = job.running) { model.startJob(Jobs.Kind.HARVEST) }
            }
        }
        // The market recorder (Boss's 06 Oct approval): its switch, what it keeps, and the export.
        if (!com.optionslab.app.BuildConfig.GOLD) item { MarketRecorderCard(model) }
        for ((u, rows) in manifests) item {
            LedgerCard(title = "Manifest · $u") {
                val same = rows.count { it.scope == Manifest.SAME_DAY }
                LedgerLine("Sessions", "${rows.size}: $same same-day, ${rows.size - same} backfilled")
                rows.takeLast(8).reversed().forEach { e ->
                    LedgerLine("${e.session}", "${e.scope} · ${e.nContracts} contracts", if (e.scope == Manifest.SAME_DAY) p.verdigris else p.inkSoft)
                }
                Note("Chain-aggregate features are valid on same-day sessions only; a backfilled partition is missing its expired front chain.")
            }
        }
        item {
            LedgerCard(title = "Provenance") {
                Note("Re-reads every bundled chain and checks it against the recorded row count, strike count and settlement bars. A silently truncated or edited file would otherwise move every result with no trace.")
                Spacer(Modifier.height(8.dp))
                BrassButton("Verify the record", Modifier.fillMaxWidth(), busy = prov is Load.Busy) { model.verifyProvenance() }
                when (val v = prov) {
                    is Load.Busy -> FullSpinner(v.label, v.progress)
                    is Load.Failed -> com.optionslab.app.ui.components.AlertOn(v.why)
                    is Load.Done -> if (v.value.isEmpty()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Stamp("Intact", p.verdigris); Spacer(Modifier.width(10.dp)); Note("All 170 sessions match their provenance.")
                    } else v.value.forEach { d -> LedgerLine(d.kind, d.why, p.oxblood) }
                    Load.Idle -> Unit
                }
            }
        }
        item { BrassButton("Delete this phone's harvested data", Modifier.fillMaxWidth(), tone = p.oxblood, busy = wiping) { if (!wiping) confirmWipe = true } }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false }, properties = secure,
        title = { Text("Delete harvested data?", style = Type.title) },
        text = { Text("Partitions and captured expiry chains collected on this phone are deleted. They cannot be collected again: expired contracts are gone for good. The bundled record and your ledger are untouched.", style = Type.bodySmall) },
        confirmButton = { TextButton({
            confirmWipe = false; wiping = true
            // Deleting the partitions walks the whole folder: off the main thread, then the figures are read again.
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Store.wipeDeviceData() }.isSuccess }
                wiping = false; reread++
                model.say(if (ok) "Harvested data deleted." else "Could not delete all of the harvested data.")
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmWipe = false }) { Text("Keep") } },
    )
}

// ---- Security ----------------------------------------------------------------------

@Composable
fun SecurityPage(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val findings by model.integrity.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var changing by remember { mutableStateOf(false) }
    val pinScope = androidx.compose.runtime.rememberCoroutineScope()
    var erasing by remember { mutableStateOf(false) }
    // Switches that lower the protection ask for the PIN (or fingerprint) first.
    var guarded by remember { mutableStateOf<Triple<String, Boolean, () -> Unit>?>(null) }
    fun guard(why: String, pinOnly: Boolean = false, action: () -> Unit) { guarded = Triple(why, pinOnly, action) }
    guarded?.let { (why, pinOnly, action) ->
        Reauth(model, onOk = { guarded = null; action() }, onCancel = { guarded = null }, pinOnly = pinOnly, why = why)
    }
    // Re-read while the page is open, so adding a fingerprint in the phone's Settings shows up on return.
    var kind by remember { mutableStateOf((context as? androidx.fragment.app.FragmentActivity)?.let { BiometricGate.available(it) } ?: BiometricGate.Kind.NONE) }
    com.optionslab.app.ui.PollWhileStarted {
        while (true) {
            kind = (context as? androidx.fragment.app.FragmentActivity)?.let { BiometricGate.available(it) } ?: BiometricGate.Kind.NONE
            kotlinx.coroutines.delay(2_000)
        }
    }
    Page {
        item { PageTitle("Security", "Nothing personal leaves this phone, and nothing is logged") }
        // The Zerodha PIN and IraAlgo's backup are IraAlgo's; the gold build has neither.
        if (!com.optionslab.app.BuildConfig.GOLD) item { KitePinCard(model) }
        if (!com.optionslab.app.BuildConfig.GOLD) item { com.optionslab.app.ui.SettingSpot("security.backup") { BackupCard(model, s.wipeOnExhaustion) } }
        item {
            LedgerCard(title = "Home-screen widget") {
                ToggleRow("Show my P&L on the widget", "Off by default: a home screen is seen by anyone holding the unlocked phone. Index levels are always shown; the Open widget shows nothing else without it.", s.widgetPnl) { on ->
                    model.update { it.copy(widgetPnl = on) }
                    runCatching { com.optionslab.app.widget.OpenWidget.refresh(context, on) }
                }
            }
        }
        item {
            LedgerCard(title = "Device integrity") {
                findings.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = Type.body.copy(color = p.ink))
                            Text(f.detail, style = Type.italic.copy(color = p.inkSoft, fontSize = 13.sp))
                        }
                        Stamp(f.severity.name, when (f.severity) { Integrity.Severity.OK -> p.verdigris; Integrity.Severity.NOTICE -> p.amber; else -> p.oxblood }, animate = false)
                    }
                }
                BrassButton("Check again", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft) { model.refreshIntegrity() }
                ToggleRow("Refuse compromised devices", "Do not open on a rooted, hooked or debugged phone", s.refuseCompromised) { on ->
                    if (on) model.update { it.copy(refuseCompromised = true) } else guard("Enter your app PIN to let IraAlgo open on a compromised phone.") { model.update { it.copy(refuseCompromised = false) } }
                }
            }
        }
        item {
            val held = remember { Integrity.heldPermissions(context) }
            LedgerCard(title = "The sandbox") {
                Note("This app lives in its own box. Android enforces it, the build refuses to produce an APK that asks for more, and the app re-checks itself above.")
                Spacer(Modifier.height(6.dp))
                Text("IT CAN", style = Type.label.copy(color = p.verdigris))
                held.forEach { perm -> Text("◆  ${plainPermission(perm)}", style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp)) }
                Text("◆  Read the one file you pick yourself when importing or exporting a ledger - that file only, that once.",
                    style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(8.dp))
                Text("IT CANNOT", style = Type.label.copy(color = p.oxblood))
                listOf(
                    "Read SMS or MMS, or send them",
                    "Read your mail, or see which accounts are on the phone",
                    "Read contacts, call history or calendar",
                    "Browse your files, photos, videos or downloads",
                    "See which apps you have installed, or read any app's data",
                    "Read other apps' notifications or screens (no accessibility or notification-listener service)",
                    "Draw over other apps, or read the clipboard in the background",
                    "Use the camera, microphone or location",
                ).forEach { Text("✕  $it", style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        item { com.optionslab.app.ui.SettingSpot("security.unlock") {
            LedgerCard(title = "Unlocking") {
                ToggleRow("Fingerprint", when (kind) {
                    BiometricGate.Kind.STRONG -> "Unlocks the app, confirms orders and opens the Zerodha secret; tied to a hardware key that dies if a finger is added or removed"
                    BiometricGate.Kind.WEAK -> "Face unlock is not used; add a fingerprint"
                    BiometricGate.Kind.NONE -> "No fingerprint added on this phone"
                }, s.biometric && kind != BiometricGate.Kind.NONE) { on ->
                    if (on) {
                        // The PIN, never a finger: a finger added by someone else must not be able to trust itself.
                        guard("Enter your app PIN to switch the fingerprint on. Every finger on this phone can then approve orders.", pinOnly = true) {
                            runCatching { if (kind == BiometricGate.Kind.STRONG) BiometricGate.enrol() }
                                .onFailure { model.say("Could not enable the fingerprint: ${it.message}") }
                                .onSuccess { model.update { it.copy(biometric = kind != BiometricGate.Kind.NONE, allowWeakFace = false) } }
                        }
                    } else {
                        BiometricGate.forget()
                        model.update { it.copy(biometric = false, allowWeakFace = false) }
                    }
                }
                // Say plainly why it would not be offered, and let the owner try it here.
                val danger = findings.filter { it.severity == com.optionslab.app.security.Integrity.Severity.DANGER }
                when {
                    s.biometric && danger.isNotEmpty() -> Text("Paused: this phone failed the security check (" + danger.joinToString { it.name + ": " + it.detail } +
                        "). The PIN is asked instead until the check passes.", style = Type.bodySmall.copy(color = p.oxblood), modifier = Modifier.padding(vertical = 4.dp))
                    kind == BiometricGate.Kind.NONE -> Text("Add a fingerprint in the phone's Settings → Security, then switch this on.",
                        style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(vertical = 4.dp))
                }
                var capture by remember { mutableStateOf(com.optionslab.app.security.Capture.allowed) }
                ToggleRow("Allow screenshots and screen recording",
                    if (capture) "On: anyone with the phone can capture any screen, keys and P&L included. Turn it off when you are done."
                    else "Off: every screen and popup is blocked from screenshots, recordings and the recent-apps preview.", capture) { on ->
                    if (!on) { capture = false; com.optionslab.app.security.Capture.set(context as? android.app.Activity, false) }
                    else guard("Enter your app PIN to allow screenshots and screen recording.") {
                        capture = true; com.optionslab.app.security.Capture.set(context as? android.app.Activity, true)
                    }
                }
                val idles = listOf(60, 120, 300, 600, 900)
                ParamTokens("Lock after idle for", idles.map { "${it / 60} min" to (it == s.idleSeconds) }) { i ->
                    // A shorter lock is always allowed; a longer one needs the PIN.
                    if (idles[i] <= s.idleSeconds) model.update { it.copy(idleSeconds = idles[i]) }
                    else guard("Enter your app PIN to keep the app open longer when idle.") { model.update { it.copy(idleSeconds = idles[i]) } }
                }
                ToggleRow("Erase after ${PinLock.WIPE_AFTER} wrong PINs", "Destroys the encryption key; all app data becomes unreadable", s.wipeOnExhaustion) { on ->
                    if (on) model.update { it.copy(wipeOnExhaustion = true) } else guard("Enter your app PIN to switch off erasing after wrong PINs.") { model.update { it.copy(wipeOnExhaustion = false) } }
                }
                ToggleRow("Hide figures on the lock screen", "Notifications show only \"Unlock to read\" while the phone is locked", s.hideAmountsOnLockScreen) { on -> model.update { it.copy(hideAmountsOnLockScreen = on) } }
                Spacer(Modifier.height(8.dp))
                Row {
                    BrassButton("Change PIN", Modifier.weight(1f), tone = p.inkSoft) { changing = true }
                    Spacer(Modifier.width(10.dp))
                    BrassButton("Seal now", Modifier.weight(1f)) { SessionLock.lock() }
                }
            }
        } }
        item {
            LedgerCard(title = "What protects you") {
                listOf(
                    "Screenshots, screen recording, casting and the recents preview see a blank window.",
                    "The ledger, alarms and settings are AES-256-GCM encrypted with a key held in the Android Keystore (StrongBox where present).",
                    "The PIN is never stored: only a salted PBKDF2 verifier, compared in constant time, with escalating lockouts.",
                    "HTTPS only, and only the system's certificate authorities - a user-installed CA cannot read the traffic.",
                    "Zerodha API key, secret and the day's access token live only in the encrypted vault; a real order needs your review, a swipe and (unless switched off) a fresh PIN or fingerprint, and is refused on a compromised device.",
                    "Nothing is written to the system log. Errors never carry a URL, an instrument key or a response.",
                    "No backups, no device transfer, no exported components beyond the launcher.",
                    "Touches through another app's overlay are ignored.",
                ).forEach { Text("◆  $it", style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }
        item { BrassButton("Erase everything personal", Modifier.fillMaxWidth(), tone = p.oxblood) { erasing = true } }
    }
    if (changing) {
        var cur by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { changing = false }, properties = secure,
            title = { Text("Change PIN", style = Type.title) },
            text = {
                Column {
                    OutlinedTextField(cur, { cur = it.filter(Char::isDigit).take(12) }, label = { Text("Current PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(next, { next = it.filter(Char::isDigit).take(PinLock.LENGTH) }, label = { Text("New PIN (${PinLock.LENGTH} digits)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    com.optionslab.app.ui.components.AlertOn(err, throttle = false)
                }
            },
            confirmButton = {
                TextButton({
                  err = null
                  pinScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinLock.verify(cur.toCharArray(), s.wipeOnExhaustion) }
                    when (r) {
                        PinLock.Result.Ok -> try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                PinLock.setPin(next.toCharArray())
                                // The Zerodha API secret is sealed with the PIN: re-seal it under the new one.
                                com.optionslab.app.data.Broker.resealSecret(cur.toCharArray(), next.toCharArray())
                            }
                            changing = false; model.say("PIN changed.")
                        } catch (e: IllegalArgumentException) { err = e.message }
                        is PinLock.Result.LockedOut -> err = "Locked for ${r.secondsLeft} s."
                        PinLock.Result.Wiped -> { changing = false; eraseEverything() }
                        else -> err = "The current PIN is not right."
                    }
                  }
                }) { Text("Change") }
            },
            dismissButton = { TextButton({ changing = false }) { Text("Cancel") } },
        )
    }
    if (erasing) AlertDialog(
        onDismissRequest = { erasing = false }, properties = secure,
        title = { Text("Erase everything personal?", style = Type.title) },
        text = { Text("The vault key is destroyed: the paper ledger, alarms, settings and PIN are gone and cannot be recovered. Market data stays.", style = Type.bodySmall) },
        confirmButton = { TextButton({ erasing = false; eraseEverything() }) { Text("Erase", color = p.oxblood) } },
        dismissButton = { TextButton({ erasing = false }) { Text("Keep") } },
    )
}

// ---- Schedules, notifications, appearance ----------------------------------------

@Composable
fun SchedulePage(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    Page {
        item { PageTitle("Schedules & Notices", "The strategy's day, kept by the phone") }
        item { com.optionslab.app.ui.SettingSpot("schedule.holidays") { HolidaysCard(model) } }
        item { com.optionslab.app.ui.SettingSpot("schedule.notifications") {
            LedgerCard(title = "Notifications") {
                Note("You always get three: a buy filled, a sell filled, and an order or strategy start waiting for your approval. " +
                    "While your orders and strategies are watched in market hours, Android requires one ongoing notice: it is kept at the lowest " +
                    "priority (collapsed, no status-bar icon) and shows no market data, only your open positions.")
                ToggleRow("Other notifications", "Risk and P&L alerts, price alarms, reminders, health changes and warnings", s.otherAlerts) { on ->
                    model.update { it.copy(otherAlerts = on) }
                }
            }
        } }
        item {
            LedgerCard(title = "The Day") {
                val rows = listOf(
                    Triple(Jobs.Kind.REMIND, "Entry reminder 10:55", "Expiry days only"),
                    Triple(Jobs.Kind.TICKET, "Paper ticket 11:01", "Expiry days: records the ticket for you"),
                    Triple(Jobs.Kind.SETTLE, "Settle 15:35", "Settles today's open ticket at the official window"),
                    Triple(Jobs.Kind.HARVEST, "Harvest 15:45", "Then re-runs the health check"),
                )
                rows.forEach { (k, title, sub) ->
                    val on = Jobs.enabled(k, s)
                    // The switch shows the owner's choice; whether the job can run yet is said beside it. (It showed
                    // whether the job runs: without Zerodha linked a switched-on job read "off", and tapping it
                    // changed nothing.)
                    val chosen = when (k) {
                        Jobs.Kind.LIVE -> true
                        Jobs.Kind.REMIND -> s.entryReminder
                        Jobs.Kind.TICKET -> s.autoTicket
                        Jobs.Kind.SETTLE -> s.autoSettle
                        Jobs.Kind.HARVEST -> s.nightlyHarvest
                    }
                    val note = when {
                        on -> "$sub · next ${Jobs.nextRun(k).format(fmt)}"
                        chosen -> "$sub · runs once Zerodha is linked"
                        else -> sub
                    }
                    ToggleRow(title, note, chosen) { v ->
                        model.update {
                            when (k) {
                                Jobs.Kind.LIVE -> it.copy(liveWatch = v)
                                Jobs.Kind.REMIND -> it.copy(entryReminder = v)
                                Jobs.Kind.TICKET -> it.copy(autoTicket = v)
                                Jobs.Kind.SETTLE -> it.copy(autoSettle = v)
                                Jobs.Kind.HARVEST -> it.copy(nightlyHarvest = v)
                            }
                        }
                    }
                }
                ToggleRow("Tell me when health changes", "PASS → WARN → FAIL, after each harvest", s.healthAlerts) { v -> model.update { it.copy(healthAlerts = v) } }
                val risks = listOf(0.001, 0.0025, 0.005, 0.01)
                ParamTokens("Warn when the index is within", risks.map { pct(it) to (it == s.riskAlertPct) }) { i -> model.update { it.copy(riskAlertPct = risks[i]) } }
                val expiries by androidx.compose.runtime.produceState(-1) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Market.upcomingExpiries().size } }
                Note("The expiry calendar comes from the instrument master; " + (if (expiries < 0) "reading it…" else "$expiries upcoming NIFTY expiries are known."))
            }
        }
        item { com.optionslab.app.ui.SettingSpot("schedule.permissions") {
            LedgerCard(title = "Permissions") {
                val exact = Jobs.canExact(context)
                val notif = Notifier.canPost(context)
                LedgerLine("Notifications", if (notif) "allowed" else "blocked", if (notif) p.verdigris else p.oxblood)
                LedgerLine("Precise alarms", if (exact) "allowed" else "not allowed", if (exact) p.verdigris else p.amber)
                if (!exact) Note("Without precise alarms the phone may run jobs late, and cannot start the all-day market watch on its own.")
                // The order watch's notices and the morning check point here when Android battery-optimizes the app.
                val battery = BatteryCheck.unrestricted(context)
                LedgerLine("Battery", if (battery) "Unrestricted" else "optimized", if (battery) p.verdigris else p.oxblood)
                if (!battery) {
                    Note("Android may stop the order watch - and the stops, targets and strategy exits it checks - while IraAlgo is battery-optimized. Set IraAlgo's battery to Unrestricted.")
                    Spacer(Modifier.height(8.dp))
                    BrassButton("Set battery to Unrestricted", Modifier.fillMaxWidth()) { BatteryCheck.ask(context) }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    BrassButton("Notification settings", Modifier.weight(1f), tone = p.inkSoft) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(Modifier.width(10.dp))
                        BrassButton("Allow alarms", Modifier.weight(1f)) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
        } }
        item {
            LedgerCard(title = "Appearance") {
                val themes = listOf("system" to "Follow the phone", "light" to "Light", "dark" to "Dark")
                ParamTokens("Theme", themes.map { it.second to (it.first == s.theme) }) { i -> model.update { it.copy(theme = themes[i].first) } }
                ToggleRow("Calm motion", "Fewer and shorter animations", s.reduceMotion) { v -> model.update { it.copy(reduceMotion = v) } }
            }
        }
    }
}

/** A permission name in words a person would use. */
private fun plainPermission(p: String): String = when (p.substringAfterLast('.')) {
    "INTERNET" -> "Reach the internet - Upstox's public market data, and Zerodha (kite.zerodha.com, api.kite.trade) once you connect it"
    "ACCESS_NETWORK_STATE" -> "Tell whether the phone is online"
    "ACCESS_LOCAL_NETWORK" -> "Local network (added by Android itself to every internet app; IraAlgo never uses it)"
    "POST_NOTIFICATIONS" -> "Show its own notifications"
    "USE_BIOMETRIC", "USE_FINGERPRINT" -> "Ask Android to check your fingerprint (the app never sees it)"
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC" -> "Keep the market watch running during market hours"
    "VIBRATE" -> "Vibrate for an alert"
    "SCHEDULE_EXACT_ALARM" -> "Wake at the strategy's set times"
    "RECEIVE_BOOT_COMPLETED" -> "Re-set those times after a restart"
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> "Ask to be left out of battery saving, so the market watch is not stopped"
    "WAKE_LOCK" -> "Stay awake while a scheduled job finishes"
    "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" -> "Talk to itself privately (no other app can use this)"
    else -> p.substringAfterLast('.')
}

/** NSE trading holidays: fetched from NSE, correctable by hand. */
@Composable
private fun HolidaysCard(model: AppModel) {
    val p = LocalPalette.current
    val h by model.holidays.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var text by remember { mutableStateOf("") }
    LedgerCard(title = "Market holidays") {
        val up = h.upcoming(com.optionslab.app.data.Market.today())
        if (up.isEmpty()) Note("No holiday list yet. Without one, a holiday is treated as a trading day: the watch runs and alarms fire.")
        else if (h.fetched == null) Note("Not updated online yet: these are the app's built-in NSE dates.")
        up.take(12).forEach { (d, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))} · $name", style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.weight(1f))
                com.optionslab.app.ui.components.TextButton({ model.removeHoliday(d) }) { Text("✕", style = Type.label.copy(color = p.oxblood)) }
            }
        }
        LedgerLine("Updated online", h.fetched?.let { "on $it" } ?: "never")
        BrassButton("Update holidays now", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { model.refreshHolidays() }
        androidx.compose.material3.OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
            label = { Text("Add a holiday (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        BrassButton("Add", Modifier.fillMaxWidth().padding(top = 4.dp), tone = p.inkSoft, enabled = runCatching { java.time.LocalDate.parse(text) }.isSuccess) {
            model.addHoliday(java.time.LocalDate.parse(text)); text = ""
        }
        Note("Updated by itself every week (Upstox's public list, or NSE's), so each new year's dates arrive once they are published. " +
            "On a holiday the watch, the expiry jobs, the harvest and strategy schedules stand down.")
    }
}

/** The pinned certificate authorities for api.kite.trade (trust on first use). */
@Composable
private fun KitePinCard(model: AppModel) {
    val p = LocalPalette.current
    var reauth by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val pins = remember(tick) { com.optionslab.app.security.KitePin.pins }
    val since = remember(tick) { com.optionslab.app.security.KitePin.since }
    LedgerCard(title = "Zerodha certificate") {
        if (pins.isEmpty()) Note("Not pinned yet: the first connection to api.kite.trade records its certificate authorities, on a network you trust.")
        else {
            LedgerLine("Pinned CAs", "${pins.size}")
            LedgerLine("Since", java.time.Instant.ofEpochMilli(since).atZone(com.optionslab.engine.IST).format(DateTimeFormatter.ofPattern("d MMM yyyy")))
            Note("A chain through any other CA is refused before anything is sent, even if Android trusts it.")
        }
        com.optionslab.app.ui.components.AlertOn(if (com.optionslab.app.security.KitePin.mismatch) "The last Zerodha connection was refused: the certificate chain did not match." else null)
        if (pins.isNotEmpty()) BrassButton("Re-trust (Zerodha changed its CA)", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { reauth = true }
    }
    if (reauth) Reauth(model, onOk = {
        reauth = false; com.optionslab.app.security.KitePin.reset(); tick++
        model.say("Pins cleared. The next connection, on a network you trust, records them again.")
    }, onCancel = { reauth = false })
}

/**
 * The account-wide guard: limits every paper and live order must pass, from a
 * strategy or by hand. Exits (closing what is held) are stopped only by the
 * kill switch.
 */
@Composable
fun RiskPage(model: AppModel) {
    Page {
        item { PageTitle("Bot settings", "Limits on every order the bot or you place, paper and live") }
        item { com.optionslab.app.ui.SettingSpot("risk.guards") { GuardCard(model) } }
    }
}

@Composable
private fun GuardCard(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var confirmKill by remember { mutableStateOf<Boolean?>(null) }
    confirmKill?.let { turnOn ->
        AlertDialog(
            onDismissRequest = { confirmKill = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text(if (turnOn) "Turn the kill switch on?" else "Clear the kill switch?", style = Type.title) },
            text = { Text(if (turnOn) "No new position is opened until you clear it. Exits and the expiry square-off still go through." else "Orders are allowed again, within these limits.", style = Type.bodySmall) },
            confirmButton = { TextButton({ model.update { it.copy(guardKill = turnOn) }; confirmKill = null }) { Text(if (turnOn) "Turn on" else "Clear", color = if (turnOn) p.oxblood else p.verdigris) } },
            dismissButton = { TextButton({ confirmKill = null }) { Text("Cancel") } },
        )
    }
    fun rupees(x: Double) = if (x >= 100_000) "₹${(x / 100_000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}L" else "₹%,.0f".format(Locale.ENGLISH, x)
    LedgerCard(title = "Account guard", accent = if (s.guardKill) p.oxblood else null) {
        Note("Checked on every Zerodha order, from a strategy or by hand (paper is never refused). Closing a position is never blocked, not even by the kill switch.")
        ToggleRow("Kill switch", if (s.guardKill) "ON: new entries are refused until you turn it off; exits and square-offs still go through" else "Off. Turn on to stop all new entries at once", s.guardKill) { on -> confirmKill = on }
        val loss = listOf(1_000.0, 2_000.0, 5_000.0, 10_000.0, 0.0)
        ParamTokens("Daily loss limit", loss.map { (if (it == 0.0) "off" else rupees(it)) to (it == s.guardDailyLoss) }) { i -> model.update { it.copy(guardDailyLoss = loss[i]) } }
        val dd = listOf(5.0, 10.0, 20.0, 0.0)
        ParamTokens("Max drawdown (from capital and from peak)", dd.map { (if (it == 0.0) "off" else "${it.toInt()}%") to (it == s.guardDrawdownPct) }) { i -> model.update { it.copy(guardDrawdownPct = dd[i]) } }
        val open = listOf(1, 2, 3, 5, 0)
        ParamTokens("Max open positions", open.map { (if (it == 0) "off" else "$it") to (it == s.guardMaxOpen) }) { i -> model.update { it.copy(guardMaxOpen = open[i]) } }
        val trades = listOf(5, 10, 20, 0)
        ParamTokens("Max orders per day (entries, stops and exits)", trades.map { (if (it == 0) "off" else "$it") to (it == s.guardMaxTrades) }) { i -> model.update { it.copy(guardMaxTrades = trades[i]) } }
        val value = listOf(100_000.0, 200_000.0, 500_000.0, 1_000_000.0, 0.0)
        ParamTokens("Max value per order", value.map { (if (it == 0.0) "off" else rupees(it)) to (it == s.guardMaxValue) }) { i -> model.update { it.copy(guardMaxValue = value[i]) } }
        val lots = listOf(1, 2, 5, 0)
        ParamTokens("Max lots per instrument", lots.map { (if (it == 0) "off" else "$it") to (it == s.guardMaxLots) }) { i -> model.update { it.copy(guardMaxLots = lots[i]) } }
        val expo = listOf(100_000.0, 200_000.0, 500_000.0, 0.0)
        ParamTokens("Max held in one instrument", expo.map { (if (it == 0.0) "off" else rupees(it)) to (it == s.guardMaxExposure) }) { i -> model.update { it.copy(guardMaxExposure = expo[i]) } }
        val cut = listOf(14 * 60, 14 * 60 + 30, 14 * 60 + 55, 15 * 60, -1)
        ParamTokens("No new entries after", cut.map { (if (it < 0) "off" else "%02d:%02d".format(it / 60, it % 60)) to (it == s.guardCutoff) }) { i -> model.update { it.copy(guardCutoff = cut[i]) } }
        Text("Paper account", style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp))
        Note("Paper is practice: the limits above and the kill switch apply to Zerodha only. On paper your own orders and the bots (ORB, Pine, strategies) all go through, side by side; \"Stop for today\" still stops the bots.")
        Note("Hitting the Zerodha drawdown limit also turns the kill switch on, as the desktop does.")
        ToggleRow("Square off on expiry day at 15:05", "Closes every option position expiring today, paper and live, MIS and NRML", s.expirySquareOff) { on ->
            model.update { it.copy(expirySquareOff = on) }
        }
        if (s.expirySquareOff) ToggleRow("Keep the Expiry Put to settlement", "Its legs are left for the 15:30 settlement, as the strategy intends", s.keepExpiryPut) { on ->
            model.update { it.copy(keepExpiryPut = on) }
        }
        ToggleRow("Block naked option shorts", "Selling an option to open needs a bought option of the same index, expiry and type held first", s.guardNakedShort) { on ->
            model.update { it.copy(guardNakedShort = on) }
        }
        TextButton({ com.optionslab.app.data.Guard.resetPeak(s.live); model.say("Drawdown peak restarts from today's equity.") }) {
            Text("Restart the drawdown peak (${if (s.live) "Zerodha" else "paper"})", style = Type.label.copy(color = p.inkSoft))
        }
    }
}


/** Runs [block] now when on the main thread, else posts it there (for calls only the main thread may make). */
private fun onMainThread(block: () -> Unit) {
    val main = android.os.Looper.getMainLooper()
    if (android.os.Looper.myLooper() == main) block() else android.os.Handler(main).post(block)
}

/**
 * Encrypted backup and restore (a file sealed with a backup passphrase; older PIN-sealed
 * files still open with their PIN). Zerodha credentials, the PIN and the pinned
 * certificates are never in it.
 */
@Composable
private fun BackupCard(model: AppModel, wipeOnExhaustion: Boolean) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val minPass = com.optionslab.app.data.Backup.MIN_PASSPHRASE
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }          // "backup" | "restore": which prompt is open
    var pending by remember { mutableStateOf<ByteArray?>(null) }   // a backup made, waiting for its file / a file read, waiting for its secret
    var fileVersion by remember { mutableStateOf(0) }              // the picked file: 1 = old, PIN-sealed; 2 = passphrase-sealed
    var opened by remember { mutableStateOf<com.optionslab.app.data.Backup.Contents?>(null) }
    var restoreAuth by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }            // a restore has started: it runs once, to the restart
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending; pending = null
        if (uri == null) return@rememberLauncherForActivityResult          // the owner cancelled the file picker
        if (bytes == null) {
            com.optionslab.app.work.Alerts.error("The backup was lost before it could be saved (the app was closed meanwhile). Tap Back up now again.")
            return@rememberLauncherForActivityResult
        }
        val resolver = ctx.applicationContext.contentResolver
        // Written off the main thread, in the app's scope so leaving the page does not cut the file short.
        model.viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream"); true }
                catch (e: Exception) { false }
            }
            if (ok) com.optionslab.app.work.Alerts.success("Backup saved. Only its passphrase opens it: keep the passphrase safe, it cannot be recovered.")
            else com.optionslab.app.work.Alerts.error("Could not write the backup file.")
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = ctx.applicationContext.contentResolver
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // Read and checked off the main thread.
            val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = try { resolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                bytes to bytes?.let { b -> try { com.optionslab.app.data.Backup.version(b) } catch (e: Exception) { null } }
            }
            val (bytes, ver) = read
            when {
                bytes == null -> com.optionslab.app.work.Alerts.error("Could not read that file.")
                ver == null -> com.optionslab.app.work.Alerts.error("This is not an IraAlgo backup.")
                else -> { pending = bytes; fileVersion = ver; ask = "restore" }
            }
        }
    }
    LedgerCard(title = "Backup and restore") {
        Note("One file with your settings, strategies, ORB arms, paper account, ledger, alarms, protections, journal and trade history, " +
            "sealed with a backup passphrase you choose (at least $minPass characters). " +
            "Zerodha keys and sessions are never in it: after a restore, link Zerodha again.")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            BrassButton("Back up now", Modifier.weight(1f), busy = busy && ask == null) { ask = "backup" }
            BrassButton("Restore…", Modifier.weight(1f), tone = p.inkSoft) { pick.launch(arrayOf("application/octet-stream", "*/*")) }
        }
    }
    ask?.let { mode ->
        // A backup needs the app PIN (to authorise it) and a new passphrase (to seal it); a restore
        // needs whichever the file was sealed with.
        val usePin = mode == "backup" || fileVersion == 1
        val usePass = mode == "backup" || fileVersion == 2
        var pin by remember(mode) { mutableStateOf("") }
        var pass by remember(mode) { mutableStateOf("") }
        var again by remember(mode) { mutableStateOf("") }
        com.optionslab.app.ui.components.AlertDialog(
            onDismissRequest = { ask = null; if (mode == "restore") pending = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text(if (mode == "backup") "Seal the backup" else "Open the backup", style = Type.title) },
            text = {
                // Scrolls: at a large font on a small or landscape screen the fields do not all fit (they were squeezed flat).
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(when {
                        mode == "backup" -> "Enter your app PIN, then choose a backup passphrase. The file is sealed with the passphrase: " +
                            "it cannot be recovered, so keep it somewhere safe."
                        fileVersion == 1 -> "An older backup, sealed with a PIN. Enter the PIN it was made with."
                        else -> "Enter the passphrase the backup was sealed with."
                    }, style = Type.bodySmall)
                    if (usePin) androidx.compose.material3.OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text(if (mode == "backup") "App PIN" else "PIN") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                    if (usePass) androidx.compose.material3.OutlinedTextField(pass, { pass = it.take(128) }, label = { Text(if (mode == "backup") "Backup passphrase" else "Passphrase") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                    if (mode == "backup") {
                        androidx.compose.material3.OutlinedTextField(again, { again = it.take(128) }, label = { Text("Passphrase again") }, singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                        val probe = pass.toCharArray()
                        val strength = com.optionslab.app.data.Backup.strength(probe)
                        probe.fill('\u0000')
                        val (hint, tone) = when (strength) {
                            0 -> "${pass.length} of at least $minPass characters" to p.inkSoft
                            1 -> "Weak: make it longer, or mix words, digits and symbols" to p.oxblood
                            2 -> "Fair: a few more words would make it strong" to p.brass
                            else -> "Strong" to p.verdigris
                        }
                        Text(hint, style = Type.bodySmall, color = tone)
                        if (again.isNotEmpty() && again != pass) Text("The two passphrases differ.", style = Type.bodySmall, color = p.oxblood)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (mode == "backup" && pass.length < minPass) com.optionslab.app.work.Alerts.error("The backup passphrase needs at least $minPass characters.")
                    else if (mode == "backup" && pass != again) com.optionslab.app.work.Alerts.error("The two passphrases differ.")
                    else if (mode == "backup" && pass.toCharArray().let { a -> com.optionslab.app.data.Backup.strength(a).also { a.fill('\u0000') } } <= 1)
                        com.optionslab.app.work.Alerts.error("That passphrase is too easy to guess: make it longer, or mix words, digits and symbols.")
                    else {
                        val typed = pin.toCharArray()
                        val phrase = pass.toCharArray()
                        busy = true
                        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            try {
                                if (mode == "backup") {
                                    // Only the owner may make a backup: the app PIN authorises it; the passphrase seals it.
                                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinLock.verify(typed.copyOf(), wipeOnExhaustion) }
                                    when (r) {
                                        PinLock.Result.Ok -> Unit
                                        PinLock.Result.Wiped -> { ask = null; eraseEverything(); return@launch }
                                        is PinLock.Result.LockedOut -> { com.optionslab.app.work.Alerts.error("Too many wrong PINs. Try again in ${r.secondsLeft} s."); return@launch }
                                        else -> { com.optionslab.app.work.Alerts.error("Not the right PIN."); return@launch }
                                    }
                                    typed.fill('\u0000')
                                    // create() reads the data on IO and stretches the passphrase on Default.
                                    pending = com.optionslab.app.data.Backup.create(ctx, phrase)
                                    ask = null
                                    // The file picker opens from the main thread only; after the off-thread work above
                                    // this coroutine may be elsewhere (under a dispatcher that does not confine it).
                                    val name = "iraalgo-backup-${com.optionslab.app.data.Market.today()}.irabk"
                                    onMainThread { save.launch(name) }
                                } else {
                                    val bytes = pending ?: return@launch
                                    val secret = if (fileVersion == 1) typed else phrase
                                    opened = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.optionslab.app.data.Backup.open(bytes, secret) }
                                    ask = null; pending = null
                                }
                            } catch (e: Exception) {
                                com.optionslab.app.work.Alerts.error(e.message ?: "That did not work.")
                            } finally { typed.fill('\u0000'); phrase.fill('\u0000'); busy = false }
                        }
                    }
                }, enabled = !busy) { Text(if (busy) "Working…" else "Continue") }
            },
            dismissButton = { TextButton({ ask = null; if (mode == "restore") pending = null }) { Text("Cancel") } },
        )
    }
    if (!restoreAuth && !restoring) opened?.let { c ->
        com.optionslab.app.ui.components.AlertDialog(
            onDismissRequest = { opened = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text("Restore this backup?", style = Type.title) },
            text = {
                Text("Made ${java.time.Instant.ofEpochMilli(c.createdAt).atZone(com.optionslab.engine.IST).toLocalDateTime().toString().replace('T', ' ').take(16)} · " +
                    "${c.files} data files · ${c.prefs} settings.\n\nIt REPLACES this phone's strategies, paper account, ledger, alarms, journal and settings. " +
                    "Your Zerodha link and PIN here stay as they are. IraAlgo closes when done; open it again.", style = Type.bodySmall)
            },
            confirmButton = {
                TextButton({ restoreAuth = true }) { Text("Replace and restart", color = p.oxblood) }
            },
            dismissButton = { TextButton({ opened = null }) { Text("Cancel") } },
        )
    }
    // The backup's passphrase (or old PIN) is whoever made the file's choice: replacing this phone's data needs THIS app's PIN.
    if (restoreAuth && !restoring) opened?.let { c ->
        Reauth(model, onCancel = { restoreAuth = false }, why = "Enter this phone's app PIN to replace its data with the backup.", onOk = {
                    restoreAuth = false
                    if (!restoring) {
                        // Once: the dialog cannot come back and a second restore cannot start.
                        restoring = true; opened = null
                        val app = ctx.applicationContext
                        // In the app's scope and not cancellable: leaving the page must not stop it halfway through the files.
                        model.viewModelScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try { com.optionslab.app.data.Backup.restore(app, c) } catch (e: Exception) { null }
                                }
                                // Files may have been replaced either way: the app always restarts, never runs on half-restored data.
                                when {
                                    done == null -> com.optionslab.app.work.Alerts.error("The restore did not complete. IraAlgo is closing; open it again and restore once more.")
                                    done.changePin -> com.optionslab.app.work.Alerts.error("Restored. This backup was sealed with a PIN, which anyone holding the file can guess: " +
                                        "if it is this phone's PIN, change it now (More → Security → Change PIN). IraAlgo is closing; open it again.")
                                    else -> com.optionslab.app.work.Alerts.success("Restored. IraAlgo is closing; open it again.")
                                }
                                kotlinx.coroutines.delay(if (done?.changePin == true) 6_000 else 1_200)
                                // Every store is cached in memory: a fresh start reads the restored files.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                    }
        })
    }
}
