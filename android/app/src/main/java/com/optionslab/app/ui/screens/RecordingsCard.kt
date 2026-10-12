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
 * Settings → Data → "Recordings" (the Data Brain's live recording, [LiveRecorder]): its switch, days kept, size, the last
 * write, how long days are kept, and "Export" - one zip to Android's share sheet (Boss's trade files stay encrypted; no
 * token or key is ever in it). Everything is read off the main thread.
 */
@Composable
fun RecordingsCard(model: AppModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<LiveRecord.Status?>(null) }
    var on by remember { mutableStateOf<Boolean?>(null) }
    var reread by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reread) {
        withContext(Dispatchers.IO) {
            on = runCatching { LiveRecorder.on }.getOrDefault(true)
            status = runCatching { LiveRecorder.status() }.getOrNull()
        }
    }
    LedgerCard(title = "Recordings") {
        Note("At every strategy signal and every fill (paper and live): the option chain at the money ±10 strikes with OI and IV, " +
            "the traded option's book, the index, its future and VIX, and the order's timing. While the market is open, every " +
            "minute: NIFTY and BANKNIFTY's minute bars and their chains. Only what the app already holds; it never changes a trade. " +
            "Your trades are encrypted; never in a backup.")
        on?.let { v ->
            ToggleRow("Record live data", "Every day not recorded is lost", v) { nv ->
                on = nv
                model.viewModelScope.launch(Dispatchers.IO) { LiveRecorder.on = nv; reread++ }
            }
        }
        val s = status
        if (s == null) LedgerLine("Days kept", "…")
        else LiveRecord.card(s, System.currentTimeMillis()).forEach { (label, value) -> LedgerLine(label, value) }
        val keep = s?.keepDays ?: LiveRecord.DEFAULT_KEEP_DAYS
        BrassButton("Keep $keep days (tap to change)", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft) {
            val next = LiveRecord.KEEP_CHOICES.let { c -> c[(c.indexOf(keep) + 1).mod(c.size)] }
            model.viewModelScope.launch(Dispatchers.IO) { LiveRecorder.keepDays = next; reread++ }
        }
        BrassButton("Export recordings (zip)", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = (s?.days ?: 0) > 0, busy = busy) {
            busy = true
            model.viewModelScope.launch {
                val said = runCatching { LiveRecorder.share(ctx.applicationContext) }.getOrNull()
                busy = false
                if (said != null) com.optionslab.app.work.Alerts.success(said)
                else com.optionslab.app.work.Alerts.error("Could not write the recordings zip.")
            }
        }
    }
}
