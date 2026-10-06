package com.optionslab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.data.DhanSource
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.InkProgress
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.DhanImportWorker
import com.optionslab.app.work.DhanWorker
import com.optionslab.ira.dhan.DhanUniverse
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val dhanSecure get() = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)

private fun size(b: Long?): String = when {
    b == null -> "…"
    b >= 1_000_000_000L -> "%.2f GB".format(java.util.Locale.ENGLISH, b / 1e9)
    else -> "%.1f MB".format(java.util.Locale.ENGLISH, b / 1e6)
}

/**
 * Dhan as a market-data source (More -> Dhan data): Boss types his Dhan client ID and access token (kept in the vault,
 * never shown again - only the token's expiry date), chooses what to download, taps Download (or lets it run by itself on
 * Wi-Fi while charging), sees the progress and the space used, and can delete it all. Dhan places no order here.
 * Not in IraGoldAlgo.
 */
@Composable
fun DhanDataPage(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (com.optionslab.app.BuildConfig.GOLD) {
        Page { item { PageTitle("Dhan data", "IraGoldAlgo keeps no Dhan data") } }
        return
    }
    val prog by DhanSource.progress.collectAsState()
    val imp by DhanSource.importing.collectAsState()
    // Import a data pack: the zip parts picked with the system file picker (several at once), read only through its grant.
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult       // Boss closed the picker
        if (prog.running) { model.say("A Dhan download is running: stop it first, then import."); return@rememberLauncherForActivityResult }
        scope.launch {
            when (withContext(Dispatchers.IO) { runCatching { DhanImportWorker.start(context, uris) }.getOrDefault(DhanImportWorker.Started.NOTHING) }) {
                DhanImportWorker.Started.STARTED -> model.say("Importing ${uris.size} part(s) of the Dhan data pack.")
                DhanImportWorker.Started.BUSY -> model.say("An import is already running: let it finish (or stop it), then pick the parts again.")
                DhanImportWorker.Started.NOTHING -> Unit
            }
        }
    }
    var configured by remember { mutableStateOf(DhanSource.configured) }
    var editing by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf(DhanSource.choice()) }
    var auto by remember { mutableStateOf(SecurePrefs.getBoolean(DhanSource.K_AUTO, false)) }
    var research by remember { mutableStateOf(DhanSource.research) }
    var confirmDelete by remember { mutableStateOf(false) }
    var askNetwork by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Import from GitHub: the index is read first and shown; the download starts only once Boss confirms it.
    var checkingGithub by remember { mutableStateOf(false) }
    var githubOffer by remember { mutableStateOf<DhanSource.GithubOffer.Ready?>(null) }
    var githubNetwork by remember { mutableStateOf<String?>(null) }      // the confirmed index's sha, asking Wi-Fi or mobile data
    fun startGithub(sha: String, wifiOnly: Boolean) {
        scope.launch {
            when (withContext(Dispatchers.IO) { runCatching { DhanImportWorker.startGithub(context, sha, wifiOnly) }.getOrDefault(DhanImportWorker.Started.NOTHING) }) {
                DhanImportWorker.Started.STARTED -> model.say(if (wifiOnly) "The data pack will download from GitHub on Wi-Fi, then be imported." else "Downloading the data pack from GitHub on mobile data, then importing it.")
                DhanImportWorker.Started.BUSY -> model.say("An import is already running or waiting: let it finish (or stop it) first.")
                DhanImportWorker.Started.NOTHING -> Unit
            }
        }
    }
    // The store's figures walk its folder: read off the main thread on opening, after a run and after a delete.
    var reread by remember { mutableStateOf(0) }
    var manifest by remember { mutableStateOf<Files.Manifest?>(null) }
    LaunchedEffect(prog.running, imp.running, reread) {
        manifest = withContext(Dispatchers.IO) { runCatching { DhanSource.filesOrNull()?.manifest() }.getOrNull() }
    }

    fun keepChoice(c: Plan.Choice) { choice = c; scope.launch(Dispatchers.IO) { runCatching { DhanSource.setChoice(c) } } }

    Page {
        item { PageTitle("Dhan data", "Market data only, downloaded to this phone for Jarvis and the replays to learn from") }
        item {
            LedgerCard(title = "Dhan (data only)") {
                Note("Dhan is used here for market data alone: candles, expired options and the option chain. It never places, changes or reads an order, a position or your funds.")
                if (configured && !editing) {
                    LedgerLine("Client ID", DhanSource.clientShown() ?: "…")
                    LedgerLine("Access token valid until", DhanSource.tokenExpiry()?.toString() ?: "not shown in the token")
                    DhanSource.tokenExpiry()?.let { exp ->
                        if (exp.isBefore(com.optionslab.app.data.Market.today().plusDays(2)))
                            LedgerLine("Token", if (exp.isBefore(com.optionslab.app.data.Market.today())) "expired - enter a new one" else "expires soon", p.oxblood)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ editing = true; err = null }) { Text("Enter a new token") }
                        Spacer(Modifier.width(8.dp))
                        TextButton({
                            scope.launch(Dispatchers.IO) { runCatching { DhanWorker.auto(context, false); DhanSource.forget() } }
                            configured = false; auto = false
                            model.say("Dhan token removed from this phone.")
                        }) { Text("Remove the token") }
                    }
                } else {
                    Note("Create an access token in Dhan's web app (DhanHQ, Data APIs), then type or paste it here with your client ID. It is kept encrypted on this phone, never in a backup, a log or an export.")
                    OutlinedTextField(clientId, { v -> clientId = v.filter { it.isDigit() }.take(20) }, label = { Text("Dhan client ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(token, { v -> token = v.trim() }, label = { Text("Access token") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    com.optionslab.app.ui.components.AlertOn(err, throttle = false)
                    Spacer(Modifier.height(8.dp))
                    BrassButton("Save to the vault", Modifier.fillMaxWidth(), busy = saving, enabled = !saving) {
                        val id = clientId; val tk = token
                        saving = true; err = null
                        scope.launch {
                            val e = withContext(Dispatchers.IO) { runCatching { DhanSource.save(id, tk) }.getOrElse { "Could not save it just now." } }
                            saving = false
                            if (e == null) { token = ""; clientId = ""; configured = true; editing = false; model.say("Dhan token saved on this phone.") }
                            else err = e
                        }
                    }
                    if (configured) TextButton({ editing = false; token = ""; clientId = ""; err = null }) { Text("Keep the saved one") }
                }
            }
        }
        item {
            LedgerCard(title = "What to download") {
                Note("NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, NIFTY NEXT 50, SENSEX, BANKEX and India VIX: daily candles from the earliest Dhan has, one-minute candles, today's option chain and the expired options 10 strikes either side of the money (one-minute, with the index beside each minute).")
                YearsRow("Index one-minute candles", choice.indexMinuteYears) { keepChoice(choice.copy(indexMinuteYears = it)) }
                YearsRow("Expired index options", choice.optionYears) { keepChoice(choice.copy(optionYears = it)) }
                ToggleRow("Index futures", "The near contract's candles with open interest", choice.futures) { keepChoice(choice.copy(futures = it)) }
                ToggleRow("The companies and banks in those indices", "${DhanUniverse.allConstituents().size} of them: daily candles from the earliest, and one-minute candles", choice.stocks) {
                    keepChoice(choice.copy(stocks = it))
                }
                if (choice.stocks) YearsRow("Company one-minute candles", choice.stockMinuteYears) { keepChoice(choice.copy(stockMinuteYears = it)) }
                if (choice.stocks) ToggleRow("Their expired options too", "Slow: tens of thousands of requests, over several days", choice.stockOptions) {
                    keepChoice(choice.copy(stockOptions = it))
                }
                Note("Index members as of ${DhanUniverse.CONSTITUENTS_AS_OF}; a company Dhan's list does not have is skipped.")
            }
        }
        item {
            LedgerCard(title = "Download") {
                LedgerLine("Stored on this phone", size(manifest?.bytes))
                SecurePrefs.getString(DhanSource.K_LAST)?.let { LedgerLine("Last", it) }
                if (prog.running) {
                    Spacer(Modifier.height(6.dp))
                    Text(prog.stage, style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                    InkProgress(prog.fraction, Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("${prog.done} of ${prog.total} chunks" + if (prog.failed > 0) " · ${prog.failed} failed" else "",
                        style = Type.bodySmall.copy(color = p.inkSoft))
                }
                Spacer(Modifier.height(10.dp))
                if (prog.running) BrassButton("Stop", Modifier.fillMaxWidth()) { DhanWorker.stopNow(context) }
                else BrassButton("Download", Modifier.fillMaxWidth(), enabled = configured && !imp.running) {
                    // A download is gigabytes: on mobile data Boss chooses (wait for Wi-Fi, or use mobile data).
                    if (DhanWorker.metered(context)) askNetwork = true
                    else { DhanWorker.now(context, wifiOnly = true); model.say("Dhan download started. It resumes where it stopped.") }
                }
                ToggleRow("Download by itself", "Once a day at most, only on Wi-Fi while the phone is charging", auto) { on ->
                    auto = on
                    scope.launch(Dispatchers.IO) { runCatching { DhanWorker.auto(context, on) } }
                }
                ToggleRow("Use it in the strategy replays", "The ORB arms' and Strategy Lab replays also read the downloaded days (paper research only)", research) { on ->
                    research = on
                    scope.launch(Dispatchers.IO) { runCatching { SecurePrefs.put(DhanSource.K_RESEARCH, on) } }
                }
            }
        }
        item {
            LedgerCard(title = "Import a data pack") {
                Note("A Dhan data pack prepared on a computer (IraAlgo-dhan-pack-01.zip, -02.zip, ...): pick all its parts at once. Each part is checked - only Dhan market-data files, within the size limits, matching the pack's checksums - and merged with what is here: a newer file on this phone is never replaced, and the download skips what was imported once the pack's fetch dates show it complete (the rest is fetched again from its last candle). No internet is used and the token is not touched.")
                SecurePrefs.getString(DhanSource.K_IMPORT_LAST)?.let { LedgerLine("Last import", it) }
                if (imp.running) {
                    Spacer(Modifier.height(6.dp))
                    Text(imp.stage, style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                    InkProgress(imp.fraction, Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(if (imp.downloading) "Part ${imp.part} of ${imp.parts} · ${size(imp.read)} of ${size(imp.size)}"
                        else "Part ${imp.part} of ${imp.parts} · ${imp.files} files · ${size(imp.bytes)}", style = Type.bodySmall.copy(color = p.inkSoft))
                    Spacer(Modifier.height(10.dp))
                    BrassButton("Stop the import", Modifier.fillMaxWidth()) { DhanImportWorker.stop(context) }
                } else {
                    Spacer(Modifier.height(10.dp))
                    BrassButton("Import data pack", Modifier.fillMaxWidth(), enabled = !prog.running) {
                        runCatching { pickPack.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
                            .onFailure { model.say("No file picker is available on this phone.") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Note("Or import the pack published on GitHub: the parts are downloaded over HTTPS from raw.githubusercontent.com (public data - no token or login is sent), checked against their listed checksums, imported the same way and then deleted from the phone.")
                    BrassButton("Import from GitHub", Modifier.fillMaxWidth(), tone = p.ink, busy = checkingGithub, enabled = !prog.running && !checkingGithub) {
                        checkingGithub = true
                        scope.launch {
                            val o = withContext(Dispatchers.IO) { DhanSource.githubIndex() }
                            checkingGithub = false
                            when (o) {
                                is DhanSource.GithubOffer.Ready -> githubOffer = o
                                is DhanSource.GithubOffer.None -> model.say(o.message)
                            }
                        }
                    }
                }
            }
        }
        manifest?.takeIf { it.files > 0 }?.let { m ->
            item {
                LedgerCard(title = "Kept") {
                    for (u in m.indices) {
                        val from = m.dailyFrom[u]?.let { "daily from $it" } ?: "no daily yet"
                        val min = m.minuteFrom[u]?.let { ", minutes from $it" } ?: ""
                        LedgerLine(u, from + min)
                    }
                    if (m.stocks > 0) LedgerLine("Companies", "${m.stocks}")
                    if (m.futures.isNotEmpty()) LedgerLine("Futures", m.futures.joinToString(", "))
                    for ((u, n) in m.optionWindows) LedgerLine("$u expired options", "$n windows of ${Plan.OPT_WINDOW_DAYS} days")
                    m.lastDay?.let { LedgerLine("Newest day", it.toString()) }
                    Note("Ask Jarvis \"what Dhan data do you have?\" or \"how did BankNifty move on the last expiry in the Dhan data?\".")
                }
            }
        }
        item {
            BrassButton("Delete downloaded data", Modifier.fillMaxWidth(), tone = p.oxblood, busy = deleting) { if (!deleting) confirmDelete = true }
        }
    }
    if (askNetwork) AlertDialog(
        onDismissRequest = { askNetwork = false }, properties = dhanSecure,
        title = { Text("You are on mobile data", style = Type.title) },
        text = { Text("The Dhan download can be several gigabytes (expired options most of all). Wait for Wi-Fi - it starts by itself when the phone joins one - or use mobile data now.", style = Type.bodySmall) },
        confirmButton = { TextButton({
            askNetwork = false
            DhanWorker.now(context, wifiOnly = true); model.say("The Dhan download will start on Wi-Fi.")
        }) { Text("Wait for Wi-Fi") } },
        dismissButton = { TextButton({
            askNetwork = false
            DhanWorker.now(context, wifiOnly = false); model.say("Dhan download started on mobile data. It resumes where it stopped.")
        }) { Text("Use mobile data") } },
    )
    githubOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { githubOffer = null }, properties = dhanSecure,
            title = { Text("Import the data pack from GitHub?", style = Type.title) },
            text = { Text("${offer.index.say()} (packed ${offer.index.created}). It needs about " +
                "${size(com.optionslab.ira.dhan.GithubPack.spaceNeeded(offer.index, 0))} free while it downloads and unpacks; " +
                "the downloaded parts are deleted after the import. Each part is checked against its checksum, and the import " +
                "checks every file again.", style = Type.bodySmall) },
            confirmButton = { TextButton({
                githubOffer = null
                // Gigabytes: on mobile data Boss chooses, as for the Download button.
                if (DhanWorker.metered(context)) githubNetwork = offer.sha else startGithub(offer.sha, wifiOnly = true)
            }) { Text("Download and import") } },
            dismissButton = { TextButton({ githubOffer = null }) { Text("Not now") } },
        )
    }
    githubNetwork?.let { sha ->
        AlertDialog(
            onDismissRequest = { githubNetwork = null }, properties = dhanSecure,
            title = { Text("You are on mobile data", style = Type.title) },
            text = { Text("The data pack can be several gigabytes. Wait for Wi-Fi - it starts by itself when the phone joins one - or use mobile data now.", style = Type.bodySmall) },
            confirmButton = { TextButton({ githubNetwork = null; startGithub(sha, wifiOnly = true) }) { Text("Wait for Wi-Fi") } },
            dismissButton = { TextButton({ githubNetwork = null; startGithub(sha, wifiOnly = false) }) { Text("Use mobile data") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, properties = dhanSecure,
        title = { Text("Delete the Dhan data?", style = Type.title) },
        text = { Text("Everything downloaded from Dhan is deleted from this phone. Your token stays until you remove it; the bundled record and your harvest are untouched.", style = Type.bodySmall) },
        confirmButton = { TextButton({
            confirmDelete = false; deleting = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { DhanSource.deleteData() }.getOrDefault(false) }
                deleting = false; reread++
                model.say(if (ok) "Dhan data deleted." else "Could not delete all of the Dhan data.")
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
    )
}

/** A number of years, 0 to 5, with - and +. */
@Composable
private fun YearsRow(title: String, years: Int, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.body.copy(color = p.ink), modifier = Modifier.weight(1f))
        TextButton({ if (years > 0) onChange(years - 1) }, enabled = years > 0) { Text("−") }
        Text(if (years == 0) "off" else "$years yr", style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
        TextButton({ if (years < 5) onChange(years + 1) }, enabled = years < 5) { Text("+") }
    }
}
