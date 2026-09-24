package com.optionslab.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.Jobs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Manifest
import java.time.format.DateTimeFormatter

@Composable
fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(color = p.ink))
            if (sub != null) Text(sub, style = Type.italic.copy(color = p.inkSoft, fontSize = 13.sp))
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = p.card, checkedTrackColor = p.brass, uncheckedThumbColor = p.inkFaint, uncheckedTrackColor = p.paperDeep,
            uncheckedBorderColor = p.rule))
    }
}

private val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

// ---- Alarms ----------------------------------------------------------------------

@Composable
fun AlarmsPage(model: AppModel) {
    val p = LocalPalette.current
    val alarms by model.alarms.collectAsState()
    val quotes by model.quotes.collectAsState()
    var symbol by remember { mutableStateOf("NIFTY") }
    var above by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Page {
        item { PageTitle("Alarms", "Checked every minute by the live watch, and whenever the Almanac is open") }
        item {
            LedgerCard(title = "Set an alarm") {
                ParamTokens("Index", listOf("NIFTY", "BANKNIFTY", "INDIAVIX").map { it to (it == symbol) }) { symbol = listOf("NIFTY", "BANKNIFTY", "INDIAVIX")[it] }
                ParamTokens("When it", listOf("falls below" to !above, "rises above" to above)) { above = it == 1 }
                quotes[symbol]?.let { Note("Now ${num(it.last, 2)}") }
                OutlinedTextField(level, { level = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Level") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(80) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                BrassButton("Set alarm", Modifier.fillMaxWidth(), enabled = level.toDoubleOrNull() != null) {
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
    val s by model.settings.collectAsState()
    val job by model.jobState.collectAsState()
    val prov by model.provenance.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf(Store.deviceExpiryDays()) }
    var manifests by remember { mutableStateOf(mapOf<String, List<Manifest.Entry>>()) }
    LaunchedEffect(job.running) {
        device = Store.deviceExpiryDays()
        manifests = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { listOf("NIFTY", "BANKNIFTY").associateWith { Store.manifest(it) } }
    }
    Page {
        item { PageTitle("Data & Harvest", "No free source serves expired contracts: a day not collected is gone") }
        item {
            LedgerCard(title = "The Record") {
                LedgerLine("Bundled expiry chains", "170 (2023-01-05 .. 2026-04-13)")
                LedgerLine("Captured on this phone", "${device.size}" + (device.lastOrNull()?.let { ", latest $it" } ?: ""))
                LedgerLine("Stored on device", "%.1f MB".format(Store.deviceBytes() / 1e6))
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
                    is Load.Failed -> Note(v.why)
                    is Load.Done -> if (v.value.isEmpty()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Stamp("Intact", p.verdigris); Spacer(Modifier.width(10.dp)); Note("All 170 sessions match their provenance.")
                    } else v.value.forEach { d -> LedgerLine(d.kind, d.why, p.oxblood) }
                    Load.Idle -> Unit
                }
            }
        }
        item { BrassButton("Delete this phone's harvested data", Modifier.fillMaxWidth(), tone = p.oxblood) { confirmWipe = true } }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false }, properties = secure,
        title = { Text("Delete harvested data?", style = Type.title) },
        text = { Text("Partitions and captured expiry chains collected on this phone are deleted. They cannot be collected again: expired contracts are gone for good. The bundled record and your ledger are untouched.", style = Type.bodySmall) },
        confirmButton = { TextButton({ Store.wipeDeviceData(); confirmWipe = false; device = emptyList(); model.say("Harvested data deleted.") }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmWipe = false }) { Text("Keep") } },
    )
}

// ---- Security ----------------------------------------------------------------------

@Composable
fun SecurityPage(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s by model.settings.collectAsState()
    val findings by model.integrity.collectAsState()
    var changing by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    val kind = remember { (context as? androidx.fragment.app.FragmentActivity)?.let { BiometricGate.available(it) } ?: BiometricGate.Kind.NONE }
    Page {
        item { PageTitle("Security", "Nothing personal leaves this phone, and nothing is logged") }
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
                ToggleRow("Refuse compromised devices", "Keep the ledger sealed on a rooted, hooked or debugged phone", s.refuseCompromised) { on -> model.update { it.copy(refuseCompromised = on) } }
            }
        }
        item {
            LedgerCard(title = "Unlocking") {
                ToggleRow("Fingerprint / face", when (kind) {
                    BiometricGate.Kind.STRONG -> "Strong biometrics: unlock a Keystore key that dies if a new finger or face is enrolled"
                    BiometricGate.Kind.WEAK -> "This phone offers face unlock only at the weaker class"
                    BiometricGate.Kind.NONE -> "No biometrics enrolled on this phone"
                }, s.biometric && kind != BiometricGate.Kind.NONE) { on ->
                    if (on) runCatching { if (kind == BiometricGate.Kind.STRONG) BiometricGate.enrol() }
                        .onFailure { model.say("Could not enable biometrics: ${it.message}"); return@ToggleRow }
                    else BiometricGate.forget()
                    model.update { it.copy(biometric = on && kind != BiometricGate.Kind.NONE, allowWeakFace = if (kind == BiometricGate.Kind.WEAK) on else it.allowWeakFace) }
                }
                if (kind == BiometricGate.Kind.STRONG) ToggleRow("Also accept weak face unlock", "Convenience only - not a cryptographic gate", s.allowWeakFace) { on -> model.update { it.copy(allowWeakFace = on) } }
                val graces = listOf(0, 30, 60, 300)
                ParamTokens("Re-seal after leaving the app", graces.map { (if (it == 0) "at once" else if (it < 60) "${it}s" else "${it / 60} min") to (it == s.graceSeconds) }) { i ->
                    model.update { it.copy(graceSeconds = graces[i]) }
                }
                ToggleRow("Erase after ${PinLock.WIPE_AFTER} wrong PINs", "Destroys the vault key; the ledger becomes unreadable", s.wipeOnExhaustion) { on -> model.update { it.copy(wipeOnExhaustion = on) } }
                ToggleRow("Hide figures on the lock screen", "Notifications show only \"Unlock to read\" while the phone is locked", s.hideAmountsOnLockScreen) { on -> model.update { it.copy(hideAmountsOnLockScreen = on) } }
                Spacer(Modifier.height(8.dp))
                Row {
                    BrassButton("Change PIN", Modifier.weight(1f), tone = p.inkSoft) { changing = true }
                    Spacer(Modifier.width(10.dp))
                    BrassButton("Seal now", Modifier.weight(1f)) { SessionLock.lock() }
                }
            }
        }
        item {
            LedgerCard(title = "What protects you") {
                listOf(
                    "Screenshots, screen recording, casting and the recents preview see a blank window.",
                    "The ledger, alarms and settings are AES-256-GCM encrypted with a key held in the Android Keystore (StrongBox where present).",
                    "The PIN is never stored: only a salted PBKDF2 verifier, compared in constant time, with escalating lockouts.",
                    "HTTPS only, and only the system's certificate authorities - a user-installed CA cannot read the traffic.",
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
                    OutlinedTextField(next, { next = it.filter(Char::isDigit).take(12) }, label = { Text("New PIN (${PinLock.MIN_LENGTH}+ digits)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    err?.let { Text(it, style = Type.italic.copy(color = p.oxblood)) }
                }
            },
            confirmButton = {
                TextButton({
                    when (val r = PinLock.verify(cur.toCharArray(), s.wipeOnExhaustion)) {
                        PinLock.Result.Ok -> try { PinLock.setPin(next.toCharArray()); changing = false; model.say("PIN changed.") } catch (e: IllegalArgumentException) { err = e.message }
                        is PinLock.Result.LockedOut -> err = "Locked for ${r.secondsLeft} s."
                        PinLock.Result.Wiped -> { changing = false; eraseEverything() }
                        else -> err = "The current PIN is not right."
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
    val s by model.settings.collectAsState()
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    Page {
        item { PageTitle("Schedules & Notices", "The strategy's day, kept by the phone") }
        item {
            LedgerCard(title = "The Day") {
                val rows = listOf(
                    Triple(Jobs.Kind.LIVE, "Live watch from 09:14", "Ongoing notification; risk alerts; price alarms"),
                    Triple(Jobs.Kind.REMIND, "Entry reminder 10:55", "Expiry days only"),
                    Triple(Jobs.Kind.TICKET, "Paper ticket 11:01", "Expiry days: records the ticket for you"),
                    Triple(Jobs.Kind.SETTLE, "Settle 15:35", "Settles today's open ticket at the official window"),
                    Triple(Jobs.Kind.HARVEST, "Harvest 15:45", "Then re-runs the health check"),
                )
                rows.forEach { (k, title, sub) ->
                    val on = Jobs.enabled(k, s)
                    ToggleRow(title, if (on) "$sub · next ${Jobs.nextRun(k).format(fmt)}" else sub, on) { v ->
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
                Note("The expiry calendar comes from the instrument master; ${Market.upcomingExpiries().size} upcoming NIFTY expiries are known.")
            }
        }
        item {
            LedgerCard(title = "Permissions") {
                val exact = Jobs.canExact(context)
                val notif = Notifier.canPost(context)
                LedgerLine("Notifications", if (notif) "allowed" else "blocked", if (notif) p.verdigris else p.oxblood)
                LedgerLine("Precise alarms", if (exact) "allowed" else "not allowed", if (exact) p.verdigris else p.amber)
                if (!exact) Note("Without precise alarms the phone may run jobs late, and cannot start the all-day live watch on its own.")
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
        }
        item {
            LedgerCard(title = "Appearance") {
                val themes = listOf("system" to "Follow the phone", "parchment" to "Parchment", "mahogany" to "Mahogany")
                ParamTokens("Theme", themes.map { it.second to (it.first == s.theme) }) { i -> model.update { it.copy(theme = themes[i].first) } }
                ToggleRow("Calm motion", "Fewer and shorter animations", s.reduceMotion) { v -> model.update { it.copy(reduceMotion = v) } }
            }
        }
    }
}
