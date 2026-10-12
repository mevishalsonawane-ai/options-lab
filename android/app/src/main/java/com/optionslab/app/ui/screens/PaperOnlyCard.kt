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
import com.optionslab.app.data.Market
import com.optionslab.app.data.PaperOnly
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.PaperOnlyPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Bot settings → Paper-only period (Boss, 12 Oct: "For 10 days I will be on paper only"): how many days and why.
 * Until the end date, switching to Live asks the PIN and shows the reason and the end date; exits are never blocked. Starting
 * one or a later end needs no PIN (it lowers risk); an earlier end or "End now" asks the PIN. The only door to [PaperOnly.set]
 * and [PaperOnly.end]: never by voice, a backup or an automatic change. It never switches the mode itself.
 */
@Composable
internal fun PaperOnlyCard(model: AppModel) {
    val p = LocalPalette.current
    val period by PaperOnly.period.collectAsState(Dispatchers.Main.immediate)
    var days by remember { mutableStateOf("10") }
    var reason by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var pinSave by remember { mutableStateOf(false) }
    var pinEnd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { PaperOnly.ensureLoaded() } } }
    val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }
    val active = PaperOnlyPeriod.active(period, today)
    fun until(): java.time.LocalDate? = days.toLongOrNull()?.takeIf { it >= 1 }?.let { today.plusDays(it - 1) }
    fun save(pin: Boolean) {
        val u = until() ?: run { said = "Days: a whole number, 1 or more."; return }
        scope.launch { said = withContext(Dispatchers.IO) { runCatching { PaperOnly.set(u, reason, pinConfirmed = pin) }.getOrElse { "Could not save it just now." } } }
    }
    fun endNow(pin: Boolean) {
        scope.launch { said = withContext(Dispatchers.IO) { runCatching { PaperOnly.end(pinConfirmed = pin) }.getOrElse { "Could not end it just now." } } }
    }
    LedgerCard(title = "Paper-only period", accent = if (active) p.verdigris else null, modifier = Modifier.testTag("paper-only-card")) {
        Text(PaperOnlyPeriod.status(period, today), style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("paper-only-status"))
        Note("Until the end date, switching to Live asks your PIN and shows this reason. Exits and square-offs are never blocked. " +
            "Starting it or a later end needs no PIN; an earlier end or ending it now asks for it. Only here: never by voice, a backup or an automatic change.",
            Modifier.padding(top = 4.dp))
        OutlinedTextField(days, { days = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Days from today") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("paper-only-days"))
        OutlinedTextField(reason, { reason = it.take(PaperOnlyPeriod.MAX_REASON) }, label = { Text("Why (shown before going Live)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("paper-only-reason"))
        BrassButton(if (active) "Save the new end date" else "Start the paper-only period", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("paper-only-save")) {
            val u = until()
            val next = u?.let { PaperOnlyPeriod.Period(it, reason, today) }
            if (u != null && PaperOnlyPeriod.needsPin(period, next, today)) pinSave = true else save(false)
        }
        if (active) BrassButton("End it now", Modifier.fillMaxWidth().padding(top = 4.dp).testTag("paper-only-end"), tone = p.oxblood) { pinEnd = true }
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
    if (pinSave) Reauth(model, pinOnly = false, why = "Enter your app PIN to end the paper-only period earlier.",
        onOk = { pinSave = false; save(true) }, onCancel = { pinSave = false })
    if (pinEnd) Reauth(model, pinOnly = false, why = "Enter your app PIN to end the paper-only period now.",
        onOk = { pinEnd = false; endNow(true) }, onCancel = { pinEnd = false })
}
