package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.optionslab.app.data.Market
import com.optionslab.app.data.MarketRecorder
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.ira.MarketRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The market recorder's card (Data & Harvest): its switch (on by default, Boss's 06 Oct approval), what is kept - storage
 * used, days recorded, the last write, the gaps - and "Export recorded data (CSV/zip)" to a file Boss picks with the system
 * file picker (the days are decrypted into that file on this phone; nothing is sent anywhere by the app), and "Open recorded
 * data": the read-only [MarketDataScreen].
 */
@Composable
fun MarketRecorderCard(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(MarketRecorder.on) }
    var status by remember { mutableStateOf<MarketRecord.Status?>(null) }
    var reread by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    // The figures list the folder: read off the main thread, on opening, after the switch and after an export.
    LaunchedEffect(reread) {
        status = withContext(Dispatchers.IO) { runCatching { MarketRecorder.status() }.getOrNull() }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult          // the owner cancelled the file picker
        busy = true
        val resolver = ctx.applicationContext.contentResolver
        // In the app's scope: leaving the page does not cut the file short.
        model.viewModelScope.launch {
            val days = withContext(Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri)?.use { MarketRecorder.export(it) } ?: error("no stream") }.getOrNull()
            }
            busy = false
            reread++
            if (days != null) com.optionslab.app.work.Alerts.success("Market data exported: $days day${if (days == 1) "" else "s"} as CSV in one zip.")
            else com.optionslab.app.work.Alerts.error("Could not write the market data file.")
        }
    }
    LedgerCard(title = "Market recorder") {
        Note("On trading days from 09:00 to 15:35, inside the market watch: the news and official notices as first seen, the " +
            "event calendar, FII/DII figures and, with Zerodha logged in, the index futures with their basis, the order book at the " +
            "money and the option chain every 5 minutes. Also GIFT Nifty (NSE IX) from 06:30, every 5 minutes in hours and at 23:30, " +
            "and NSE's participant-wise OI (Client, DII, FII, Pro) after 18:30. Encrypted on this phone, never in a backup, 12 months kept. " +
            "It never holds up trading.")
        ToggleRow("Record market data", "What the app already reads, kept for a later study", on) { v ->
            MarketRecorder.on = v; on = v; reread++
        }
        val today = Market.today()
        val s = status
        if (s == null) LedgerLine("Storage used", "…")
        else MarketRecord.card(s, today).forEach { (label, value) ->
            LedgerLine(label, value, if (label == "Storage used" && s.overBudget) p.oxblood else null)
        }
        // What is kept, read back on this phone: the day's headlines and the moves after them, the futures, the book, the lessons.
        BrassButton("Open recorded data", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = (s?.days ?: 0) > 0, tone = p.inkSoft) {
            viewing = true
        }
        BrassButton("Export recorded data (CSV/zip)", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = (s?.days ?: 0) > 0, busy = busy) {
            save.launch("iraalgo-market-data-$today.zip")
        }
    }
    if (viewing) MarketDataScreen { viewing = false }
}
