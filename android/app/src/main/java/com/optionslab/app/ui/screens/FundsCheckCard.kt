package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.FundsCheck
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN prompt's reason when the funds check is switched off. */
internal const val FUNDS_CHECK_PIN = "Enter your app PIN to switch the funds check off (live entries the free cash cannot pay for go to Zerodha again)."
/** The switch's row title. */
internal const val FUNDS_CHECK_SWITCH = "Skip live entries the free cash cannot pay for (one lot)"

/**
 * 15 Nov 2026 (Boss: "Yes" to building it in shadow): Bot settings' switch for the funds check ([FundsCheck]), beside the other live
 * switches. Off by default: shadow - the order goes as before and the card counts today's entries it would have skipped. On only
 * lowers risk, at once; off adds live entries back, so it takes the PIN (always: it is a live switch).
 */
@Composable
internal fun FundsCheckCard(model: AppModel) {
    val view by FundsCheck.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { FundsCheck.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    FundsCheckCardFlow(view, said, onOn = { save { FundsCheck.set(true) } }, onOffConfirmed = { save { FundsCheck.set(false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = FUNDS_CHECK_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching off; tests give their own). */
@Composable
internal fun FundsCheckCardFlow(view: FundsCheck.View, said: String?, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    FundsCheckCardContent(view, said) { on -> if (on) onOn() else askPin = true }
    if (askPin) pin({ askPin = false; onOffConfirmed() }, { askPin = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun FundsCheckCardContent(view: FundsCheck.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = FundsCheck.LABEL) {
        Note("An arm's live option buy (index or stock options: the arms' live copies, ORB and Liquidity 15+5) that the free cash cannot pay for even one " +
            "lot: Zerodha refuses it (\"Insufficient funds\"), as it did 25 times on 2-6 Nov. The check reads the free cash the entry " +
            "already read; when it is not known it says nothing. A sale, a future and MCX are never asked.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(FUNDS_CHECK_SWITCH, if (view.on) "On: such an entry is not sent; it is kept as a would-be entry"
            else "Off (shadow): such entries go as before; each is logged", view.on, keepSub = true, onChange = onSwitch)
        Note("Today: ${view.wouldSkipToday} it would have skipped" + (if (view.skippedToday > 0) ", ${view.skippedToday} not sent" else "") +
            ". On only lowers risk (no PIN); switching it off takes your PIN.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
