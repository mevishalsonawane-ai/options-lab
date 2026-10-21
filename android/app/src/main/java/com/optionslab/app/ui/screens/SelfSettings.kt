package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.optionslab.app.ira.LearningFreeze
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.FreezeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Bot settings → Learning freeze (S14): whether every automatic change is frozen and why, each account's fall
 * since the agents' first change against the line, Boss's own line (empty: the default), and Unfreeze - no PIN while only
 * paper is on, the PIN while the live account is on. Never by voice, never from a backup. None in IraGoldAlgo.
 */
@Composable
internal fun LearningFreezeCard(model: AppModel) {
    if (!LearningFreeze.enabled()) return
    val state by LearningFreeze.state.collectAsState(Dispatchers.Main.immediate)
    val verdicts by LearningFreeze.verdicts.collectAsState(Dispatchers.Main.immediate)
    var line by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(false) }
    var pinLine by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val kept = withContext(Dispatchers.IO) { runCatching { LearningFreeze.ensureLoaded(); LearningFreeze.refresh(); LearningFreeze.thresholdSetting() }.getOrDefault(0.0) }
        line = if (kept > 0) String.format(java.util.Locale.ENGLISH, "%.0f", kept) else ""
    }
    fun unfreeze(confirmed: Boolean) {
        scope.launch { said = withContext(Dispatchers.IO) { runCatching { LearningFreeze.unfreeze(confirmed) }.getOrElse { "Could not unfreeze just now." } } }
    }
    LearningFreezeContent(state, verdicts, line, said, onLine = { line = it.filter { c -> c.isDigit() }.take(7) },
        onSaveLine = {
            scope.launch {
                val needs = withContext(Dispatchers.IO) { runCatching { LearningFreeze.needsPin() }.getOrDefault(true) }
                if (needs) pinLine = true
                else said = withContext(Dispatchers.IO) { LearningFreeze.setThreshold(line.toDoubleOrNull() ?: 0.0) }
            }
        },
        onUnfreeze = {
            scope.launch {
                val needs = withContext(Dispatchers.IO) { runCatching { LearningFreeze.needsPin() }.getOrDefault(true) }
                if (needs) pin = true else unfreeze(false)
            }
        })
    if (pin) Reauth(model, pinOnly = false, why = "Enter your app PIN to unfreeze learning while the live account is on: the agents may then change things by themselves again.",
        onOk = { pin = false; unfreeze(true) }, onCancel = { pin = false })
    if (pinLine) Reauth(model, pinOnly = false, why = "Enter your app PIN to change the learning freeze line while the live account is on.",
        onOk = {
            pinLine = false
            scope.launch { said = withContext(Dispatchers.IO) { LearningFreeze.setThreshold(line.toDoubleOrNull() ?: 0.0, pinConfirmed = true) } }
        }, onCancel = { pinLine = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun LearningFreezeContent(state: FreezeRule.State, verdicts: List<FreezeRule.Verdict>, line: String, said: String?,
                                   onLine: (String) -> Unit, onSaveLine: () -> Unit, onUnfreeze: () -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "Learning freeze", accent = if (state.frozen) p.oxblood else null) {
        Text(if (state.frozen) "FROZEN: no automatic change is applied" else "Not frozen", style = Type.body.copy(color = if (state.frozen) p.oxblood else p.ink,
            fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("freeze-state"))
        if (state.frozen) Note(state.why, Modifier.padding(top = 2.dp))
        verdicts.forEach { v -> Note("${v.account.words.replaceFirstChar { it.uppercase() }}: ${v.words}.", Modifier.padding(top = 2.dp)) }
        Note("If paper's or live's total P&L falls more than the line since the agents' first automatic change of the last 5 trading days, " +
            "Autopilot, the strategy lab's promotions, the health tuner and the specialists' self-tuning stop applying changes; they keep " +
            "recording. The default line is twice your average losing day of the last 60 days, at least ₹3,000.", Modifier.padding(top = 6.dp))
        OutlinedTextField(line, onLine, label = { Text("Your line in ₹ (empty: the default)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("freeze-line-field"))
        BrassButton("Save the line", Modifier.fillMaxWidth().padding(top = 4.dp), tone = p.inkSoft, onClick = onSaveLine)
        if (state.frozen) BrassButton("Unfreeze learning", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("unfreeze"), tone = p.oxblood, onClick = onUnfreeze)
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * Settings → Diagnostics (S17): the exportable health report - the last 14 days of stalls with where they were stuck, the
 * ANRs and crashes with their stack traces, the order path's timings - redacted (no token, key, PIN or account id) and
 * handed to the share sheet; and "Copy diagnostics" as before.
 */
@Composable
fun DiagnosticsPage() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Page {
        item { PageTitle("Diagnostics", "The app's own health, for whoever helps") }
        item {
            LedgerCard(title = "Health report") {
                Note("The last 14 days of screen stalls with where they were stuck, the ANRs and crashes with their stack traces, and the order " +
                    "path's timings, as a zip to share. Tokens, keys, your PIN, account and client ids are removed before it is written.")
                BrassButton(if (busy) "Preparing…" else "Share health report", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("share-health-report"), enabled = !busy) {
                    busy = true
                    scope.launch {
                        said = runCatching { com.optionslab.app.data.HealthReport.share(ctx) }.getOrElse { "Could not prepare the report just now." }
                        busy = false
                    }
                }
                said?.let { Note(it, Modifier.padding(top = 4.dp)) }
            }
        }
        item {
            LedgerCard(title = "Diagnostics diary") {
                Note("The app's own diary (banners, self-test, bot notes), redacted, copied for you to paste to whoever helps.")
                BrassButton("Copy diagnostics", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { com.optionslab.app.data.Diag.report() }
                        com.optionslab.app.ui.SensitiveClip.copy(ctx, "IraAlgo diagnostics", text)
                        com.optionslab.app.work.Alerts.success("Diagnostics copied: paste them in the chat. Keys, tokens and passwords are never included.")
                    }
                }
            }
        }
    }
}
