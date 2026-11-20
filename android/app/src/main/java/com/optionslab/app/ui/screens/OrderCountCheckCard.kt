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
import com.optionslab.app.data.OrderCountCheck
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN prompt's reason when the order count check is switched off. */
internal const val ORDER_COUNT_CHECK_PIN = "Enter your app PIN to switch the order count check off (live entries go again while today's order count cannot be read)."
/** The switch's row title. */
internal const val ORDER_COUNT_CHECK_SWITCH = "Refuse live entries while today's order count cannot be read"

/**
 * 20 Nov 2026 (audit B5, item 6; coordinator: a new live skip starts in shadow): Bot settings' switch for the order count check
 * ([OrderCountCheck]), beside the funds check. Off by default: shadow - the entry goes as before and the card counts today's entries it
 * would have refused. On only lowers risk, at once; off adds live entries back, so it takes the PIN.
 */
@Composable
internal fun OrderCountCheckCard(model: AppModel) {
    val view by OrderCountCheck.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { OrderCountCheck.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    OrderCountCheckCardFlow(view, said, onOn = { save { OrderCountCheck.set(true) } }, onOffConfirmed = { save { OrderCountCheck.set(false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = ORDER_COUNT_CHECK_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching off; tests give their own). */
@Composable
internal fun OrderCountCheckCardFlow(view: OrderCountCheck.View, said: String?, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                     pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    LedgerCard(title = OrderCountCheck.LABEL) {
        Note("Your trades-per-day limit counts today's orders at Zerodha. When that count cannot be read (read twice), the limit cannot " +
            "be checked. Off, a live entry goes as before and each is logged; on, it waits until the count can be read. Exits and stops " +
            "never look at it.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(ORDER_COUNT_CHECK_SWITCH, if (view.on) "On: such an entry is refused until the count can be read"
            else "Off (shadow): such entries go as before; each is logged", view.on, keepSub = true) { on -> if (on) onOn() else askPin = true }
        Note("Today: ${view.wouldToday} it would have refused" + (if (view.refusedToday > 0) ", ${view.refusedToday} refused" else "") +
            ". On only lowers risk (no PIN); switching it off takes your PIN.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
    if (askPin) pin({ askPin = false; onOffConfirmed() }, { askPin = false })
}
