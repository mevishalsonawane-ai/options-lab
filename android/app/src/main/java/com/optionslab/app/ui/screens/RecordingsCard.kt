package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.optionslab.app.data.LiveRecorder
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.ira.LiveRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Data → "Recordings" (the Data Brain's live recording, [LiveRecorder], and the tick zone, [TickZoneRecorder]):
 * its switch, days kept, size, the last write, how long days are kept, the tick zone's lines, and "Export recordings (zip)"
 * (12 Oct): Boss's PIN first ([Reauth], PIN only), then a file he picks with the system file picker; the market and tick files
 * go in as stored and his trade files DECRYPTED, on this phone, into that file (nothing is sent anywhere by the app; no
 * token or key is ever in it). Everything is read and written off the main thread.
 */
@Composable
fun RecordingsCard(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<LiveRecord.Status?>(null) }
    var on by remember { mutableStateOf<Boolean?>(null) }
    var reread by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var ticks by remember { mutableStateOf<com.optionslab.ira.TickZone.Status?>(null) }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(reread) {
        withContext(Dispatchers.IO) {
            on = runCatching { LiveRecorder.on }.getOrDefault(true)
            status = runCatching { LiveRecorder.status() }.getOrNull()
            ticks = runCatching { com.optionslab.app.data.TickZoneRecorder.status() }.getOrNull()
        }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult          // Boss cancelled the file picker
        busy = true
        val resolver = ctx.applicationContext.contentResolver
        // In the app's scope: leaving the page does not cut the file short.
        model.viewModelScope.launch {
            val days = withContext(Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri)?.use { LiveRecorder.exportDecrypted(it) } ?: error("no stream") }.getOrNull()
            }
            busy = false
            reread++
            if (days != null) com.optionslab.app.work.Alerts.success("Recordings exported: $days day${if (days == 1) "" else "s"} in one zip, your trades decrypted. Keep the file private.")
            else com.optionslab.app.work.Alerts.error("Could not write the recordings zip.")
        }
    }
    LedgerCard(title = "Recordings") {
        Note("At every strategy signal and every fill (paper and live): the option chain at the money ±10 strikes with OI and IV, " +
            "the traded option's book, the index, its future and VIX, and the order's timing. While the market is open, every " +
            "minute: NIFTY and BANKNIFTY's minute bars and their chains. Every second: the order flow of both futures, their ATM ±3 " +
            "options and VIX (kept 10 days; the minute summaries as long as the rest). Only what the app already holds; it never " +
            "changes a trade. Your trades are encrypted; never in a backup.")
        on?.let { v ->
            ToggleRow("Record live data", "Every day not recorded is lost", v) { nv ->
                on = nv
                model.viewModelScope.launch(Dispatchers.IO) { LiveRecorder.on = nv; reread++ }
            }
        }
        val s = status
        if (s == null) LedgerLine("Days kept", "…")
        else LiveRecord.card(s, System.currentTimeMillis()).forEach { (label, value) -> LedgerLine(label, value) }
        // The tick zone: every second's order flow of BANKNIFTY and NIFTY futures, their ATM ±3 options and VIX.
        ticks?.let { t -> com.optionslab.ira.TickZone.card(t, System.currentTimeMillis()).forEach { (label, value) -> LedgerLine(label, value) } }
        val keep = s?.keepDays ?: LiveRecord.DEFAULT_KEEP_DAYS
        BrassButton("Keep $keep days (tap to change)", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft) {
            val next = LiveRecord.KEEP_CHOICES.let { c -> c[(c.indexOf(keep) + 1).mod(c.size)] }
            model.viewModelScope.launch(Dispatchers.IO) { LiveRecorder.keepDays = next; reread++ }
        }
        BrassButton("Export recordings (zip)", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = (s?.days ?: 0) > 0, busy = busy) {
            askPin = true
        }
    }
    // Your trades are decrypted into the file: your PIN first (never the fingerprint alone), then the file picker.
    if (askPin) Reauth(model, pinOnly = true, why = "Enter your app PIN to export the recordings. Your trades are decrypted into the file you pick.",
        onOk = { askPin = false; save.launch("iraalgo-recordings-${com.optionslab.app.data.Market.today()}.zip") },
        onCancel = { askPin = false })
}
