package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import com.optionslab.app.data.Backup
import com.optionslab.app.data.GitHubSync
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.Alerts
import com.optionslab.ira.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Settings → Data & Harvest → "GitHub sync" (TODO G1/G2, 13 Oct; [GitHubSync]): the token (pasted here, checked with GitHub,
 * kept sealed by the PIN), the backup passphrase that seals every upload, the last upload (date · size · ✓/✗), the days
 * waiting, the last backup, "Upload now" and "Restore from GitHub" (pick a date, the backup passphrase, then this phone's PIN,
 * through the same [Backup.restore] as a file: everything comes back switched off; the PIN, Live/AI-live, tokens and the
 * paper-only period are never in a backup). Every read, check and upload runs off the main thread.
 */
@Composable
fun GitHubSyncCard(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by model.settings.collectAsState(Dispatchers.Main.immediate)
    var reread by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<CloudSync.Status?>(null) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var deviceOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }      // "token" | "pass" | "unlock" | "forget" | "pick" | "open"
    var choices by remember { mutableStateOf<List<GitHubSync.Saved>>(emptyList()) }
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var opened by remember { mutableStateOf<Backup.Contents?>(null) }
    LaunchedEffect(reread) {
        withContext(Dispatchers.IO) {
            val st = runCatching { GitHubSync.status() }.getOrNull()
            status = st
            lines = st?.let { runCatching { GitHubSync.card(it) }.getOrDefault(emptyList()) } ?: emptyList()
            deviceOk = st?.tokenSet != true || runCatching { GitHubSync.deviceCopyOk() }.getOrDefault(false)
        }
    }

    /** The app PIN, checked as everywhere else (lockout, the optional wipe). On the main thread after the check. */
    suspend fun pinOk(pin: CharArray): Boolean {
        val r = withContext(Dispatchers.Default) { PinLock.verify(pin.copyOf(), s.wipeOnExhaustion) }
        return when (r) {
            PinLock.Result.Ok -> true
            PinLock.Result.Wiped -> { ask = null; com.optionslab.app.ui.eraseEverything(); false }
            is PinLock.Result.LockedOut -> { Alerts.error("Too many wrong PINs. Try again in ${r.secondsLeft} s."); false }
            else -> { Alerts.error("Not the right PIN."); false }
        }
    }

    LedgerCard(title = "GitHub sync") {
        Note("Every evening after 15:45, on Wi-Fi while charging, the phone uploads each recorded day and a phone backup to " +
            "GitHub (options-lab's releases, never the code). Both are sealed on the phone with your backup passphrase: GitHub " +
            "sees only noise. A strategy or Pine change is backed up a few minutes later. The token stays sealed by your PIN.")
        if (status == null) LedgerLine("Status", "…") else lines.forEach { (k, v) ->
            LedgerLine(k, v, if (v.contains("✗") || k == "Token") p.oxblood else null)
        }
        val st = status
        if (st?.tokenSet == true && !deviceOk) {
            Note("The phone's copy of the token cannot be opened (its key was reset). Unlock it once with your PIN.")
            BrassButton("Unlock with PIN", Modifier.fillMaxWidth().padding(top = 8.dp)) { ask = "unlock" }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton(if (st?.tokenSet == true) "New token…" else "Paste token…", Modifier.weight(1f)) { ask = "token" }
            BrassButton(if (st?.keyReady == true) "Change passphrase…" else "Set passphrase…", Modifier.weight(1f), tone = p.inkSoft) { ask = "pass" }
        }
        if (st?.tokenSet == true && st.keyReady) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrassButton("Upload now", Modifier.weight(1f), busy = busy && ask == null) {
                    runCatching { com.optionslab.app.work.GitHubSyncWorker.now(ctx.applicationContext) }
                    Alerts.success("Upload queued: it starts as soon as the phone is on Wi-Fi (recorded days only outside 09:00-15:45).")
                }
                BrassButton("Restore from GitHub…", Modifier.weight(1f), tone = p.inkSoft, busy = busy && ask == "pick") {
                    busy = true
                    scope.launch(Dispatchers.Main) {
                        try {
                            choices = GitHubSync.backups()
                            if (choices.isEmpty()) Alerts.error("No phone backup on GitHub yet.") else ask = "pick"
                        } catch (e: Exception) {
                            Alerts.error(CloudSync.redact(e.message ?: "Could not read the backups on GitHub."))
                        } finally { busy = false }
                    }
                }
            }
        }
        if (st?.tokenSet == true) BrassButton("Remove the token", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.oxblood) { ask = "forget" }
    }

    when (ask) {
        "token" -> {
            var token by remember { mutableStateOf("") }
            var until by remember { mutableStateOf(LocalDate.now().plusDays(90).toString()) }
            var pin by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!busy) ask = null },
                properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
                title = { Text("GitHub token", style = Type.title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Paste a fine-grained token made for options-lab only, with Contents: read and write (MAINTENANCE.md, " +
                            "\"GitHub token\"). Enter the expiry date you chose, and your app PIN: the token is sealed with it.", style = Type.bodySmall)
                        OutlinedTextField(token, { token = it.trim().take(300) }, label = { Text("Token (github_pat_…)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                        OutlinedTextField(until, { until = it.take(10) }, label = { Text("Expires on (YYYY-MM-DD)") }, singleLine = true)
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("App PIN") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    }
                },
                confirmButton = {
                    TextButton({
                        val date = runCatching { LocalDate.parse(until.trim()) }.getOrNull()
                        when {
                            !CloudSync.tokenLooksRight(token) -> Alerts.error("That is not a fine-grained GitHub token (it starts with github_pat_).")
                            date == null || date.isBefore(LocalDate.now()) -> Alerts.error("Enter the token's expiry date as YYYY-MM-DD (a future date).")
                            else -> {
                                val typed = pin.toCharArray()
                                busy = true
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        if (!pinOk(typed)) return@launch
                                        val who = GitHubSync.saveToken(token, date, typed)
                                        token = ""
                                        ask = null
                                        Alerts.success("GitHub token checked and saved (account $who). Set the backup passphrase if you have not.")
                                    } catch (e: Exception) {
                                        Alerts.error(CloudSync.redact(e.message ?: "GitHub did not accept the token."))
                                    } finally { typed.fill('\u0000'); busy = false; reread++ }
                                }
                            }
                        }
                    }, enabled = !busy) { Text(if (busy) "Checking…" else "Check and save") }
                },
                dismissButton = { TextButton({ ask = null }, enabled = !busy) { Text("Cancel") } },
            )
        }
        "pass" -> {
            var pass by remember { mutableStateOf("") }
            var again by remember { mutableStateOf("") }
            var pin by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!busy) ask = null },
                properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
                title = { Text("Backup passphrase", style = Type.title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Every upload is sealed with this passphrase. It opens the phone backups on a new phone and the recorded " +
                            "days on a PC (research/data/decrypt_live.py). It cannot be recovered: keep it somewhere safe. " +
                            "Uploads made before a change keep the old passphrase.", style = Type.bodySmall)
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("App PIN") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        OutlinedTextField(pass, { pass = it.take(128) }, label = { Text("Backup passphrase") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        OutlinedTextField(again, { again = it.take(128) }, label = { Text("Passphrase again") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        val probe = pass.toCharArray()
                        val strength = Backup.strength(probe)
                        probe.fill('\u0000')
                        val (hint, tone) = when (strength) {
                            0 -> "${pass.length} of at least ${Backup.MIN_PASSPHRASE} characters" to p.inkSoft
                            1 -> "Weak: make it longer, or mix words, digits and symbols" to p.oxblood
                            2 -> "Fair: a few more words would make it strong" to p.brass
                            else -> "Strong" to p.verdigris
                        }
                        Text(hint, style = Type.bodySmall, color = tone)
                        if (again.isNotEmpty() && again != pass) Text("The two passphrases differ.", style = Type.bodySmall, color = p.oxblood)
                    }
                },
                confirmButton = {
                    TextButton({
                        val strong = pass.toCharArray().let { a -> Backup.strength(a).also { a.fill('\u0000') } }
                        when {
                            pass != again -> Alerts.error("The two passphrases differ.")
                            strong <= 1 -> Alerts.error("That passphrase is too easy to guess: make it longer, or mix words, digits and symbols.")
                            else -> {
                                val typed = pin.toCharArray()
                                val phrase = pass.toCharArray()
                                busy = true
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        if (!pinOk(typed)) return@launch
                                        GitHubSync.setPassphrase(phrase)
                                        pass = ""; again = ""
                                        ask = null
                                        Alerts.success("Backup passphrase set. Keep it safe: without it the uploads cannot be opened.")
                                    } catch (e: Exception) {
                                        Alerts.error(e.message ?: "Could not set the passphrase.")
                                    } finally { typed.fill('\u0000'); phrase.fill('\u0000'); busy = false; reread++ }
                                }
                            }
                        }
                    }, enabled = !busy) { Text(if (busy) "Working…" else "Set") }
                },
                dismissButton = { TextButton({ ask = null }, enabled = !busy) { Text("Cancel") } },
            )
        }
        "unlock" -> {
            var pin by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!busy) ask = null },
                properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
                title = { Text("Unlock the GitHub token", style = Type.title) },
                text = {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("App PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                },
                confirmButton = {
                    TextButton({
                        val typed = pin.toCharArray()
                        busy = true
                        scope.launch(Dispatchers.Main) {
                            try {
                                if (!pinOk(typed)) return@launch
                                if (GitHubSync.reopen(typed)) { ask = null; Alerts.success("GitHub token unlocked.") }
                                else Alerts.error("The token was sealed with another PIN. Paste a new token instead.")
                            } catch (e: Exception) {
                                Alerts.error("Could not unlock the token.")
                            } finally { typed.fill('\u0000'); busy = false; reread++ }
                        }
                    }, enabled = !busy) { Text("Unlock") }
                },
                dismissButton = { TextButton({ ask = null }, enabled = !busy) { Text("Cancel") } },
            )
        }
        "forget" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Remove the GitHub token?", style = Type.title) },
            text = { Text("Uploads stop until a new token is pasted. What is on GitHub stays. Revoke the token on GitHub too " +
                "(Settings → Developer settings → Fine-grained tokens).", style = Type.bodySmall) },
            confirmButton = {
                TextButton({
                    ask = null
                    scope.launch(Dispatchers.Main) { withContext(Dispatchers.IO) { runCatching { GitHubSync.forgetToken() } }; reread++ }
                }) { Text("Remove", color = p.oxblood) }
            },
            dismissButton = { TextButton({ ask = null }) { Text("Keep") } },
        )
        "pick" -> AlertDialog(
            onDismissRequest = { if (!busy) ask = null },
            title = { Text("Restore from GitHub", style = Type.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pick a backup (newest first). Next: its backup passphrase, then this phone's PIN.", style = Type.bodySmall)
                    choices.take(60).forEach { b ->
                        TextButton({
                            busy = true
                            scope.launch(Dispatchers.Main) {
                                try {
                                    val bytes = GitHubSync.download(b)
                                    val ver = withContext(Dispatchers.Default) { runCatching { Backup.version(bytes) }.getOrNull() }
                                    if (ver != 2) { Alerts.error("That file is not a passphrase-sealed IraAlgo backup."); return@launch }
                                    picked = bytes
                                    ask = "open"
                                } catch (e: Exception) {
                                    Alerts.error(CloudSync.redact(e.message ?: "Could not download that backup."))
                                } finally { busy = false }
                            }
                        }, enabled = !busy) {
                            Text("${b.at.toLocalDate()} ${b.at.toLocalTime().toString().take(5)} · ${CloudSync.size(b.bytes)}", style = Type.body)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ ask = null }, enabled = !busy) { Text(if (busy) "Downloading…" else "Cancel") } },
        )
        "open" -> {
            var pass by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!busy) { ask = null; picked = null } },
                properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
                title = { Text("Open the backup", style = Type.title) },
                text = {
                    Column {
                        Text("Enter the backup passphrase it was sealed with.", style = Type.bodySmall)
                        OutlinedTextField(pass, { pass = it.take(128) }, label = { Text("Backup passphrase") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                },
                confirmButton = {
                    TextButton({
                        val bytes = picked
                        if (bytes == null) ask = null
                        else {
                            val phrase = pass.toCharArray()
                            busy = true
                            scope.launch(Dispatchers.Main) {
                                try {
                                    opened = withContext(Dispatchers.Default) { Backup.open(bytes, phrase) }
                                    ask = null; picked = null; pass = ""
                                } catch (e: Exception) {
                                    Alerts.error(e.message ?: "That passphrase does not open this backup.")
                                } finally { phrase.fill('\u0000'); busy = false }
                            }
                        }
                    }, enabled = !busy) { Text(if (busy) "Opening…" else "Continue") }
                },
                dismissButton = { TextButton({ ask = null; picked = null }, enabled = !busy) { Text("Cancel") } },
            )
        }
    }
    opened?.let { c -> GitHubRestoreConfirm(model, c) { opened = null } }
}

/**
 * The restore of an opened backup, as the file restore does it (ControlPages' Backup card): what it replaces, then THIS phone's
 * PIN, then [Backup.restore] once, in the app's scope and not cancellable, and the app closes so every store reads the
 * restored files. Strategies, ORB arms and Pine scripts come back switched off with their protections ([Backup.DISARM]).
 */
@Composable
private fun GitHubRestoreConfirm(model: AppModel, c: Backup.Contents, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var auth by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    if (!auth && !restoring) AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Restore this backup?", style = Type.title) },
        text = {
            Text("Made ${java.time.Instant.ofEpochMilli(c.createdAt).atZone(com.optionslab.engine.IST).toLocalDateTime().toString().replace('T', ' ').take(16)} · " +
                "${c.files} data files · ${c.prefs} settings.\n\nIt REPLACES this phone's strategies, paper account, ledger, alarms, journal and settings. " +
                "Strategies and Pine scripts come back switched off. Your Zerodha link, PIN, Live and AI-live switches stay as they are. " +
                "IraAlgo closes when done; open it again.", style = Type.bodySmall)
        },
        confirmButton = { TextButton({ auth = true }) { Text("Replace and restart", color = p.oxblood) } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
    if (auth && !restoring) Reauth(model, onCancel = { auth = false }, why = "Enter this phone's app PIN to replace its data with the backup.", onOk = {
        auth = false
        if (!restoring) {
            restoring = true
            val app = ctx.applicationContext
            model.viewModelScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val done = withContext(Dispatchers.IO) {
                        try {
                            Backup.restore(app, c).also { runCatching { com.optionslab.app.data.WhatsNewStore.reload() } }
                        } catch (e: Exception) { null }
                    }
                    if (done == null) Alerts.error("The restore did not complete. IraAlgo is closing; open it again and restore once more.")
                    else Alerts.success("Restored from GitHub. IraAlgo is closing; open it again.")
                    kotlinx.coroutines.delay(1_200)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    })
}
