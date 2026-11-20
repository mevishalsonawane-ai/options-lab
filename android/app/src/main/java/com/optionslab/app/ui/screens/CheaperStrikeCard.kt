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
import com.optionslab.app.data.CheaperStrike
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN prompt's reason when the cheaper strike is switched on. */
internal const val CHEAPER_STRIKE_PIN = "Enter your app PIN to switch the cheaper strike on (the arms may buy a cheaper option live when the cash is short)."
/** The switch's row title. */
internal const val CHEAPER_STRIKE_SWITCH = "Cheaper strike when funds are short (Boss's exception, 20 Nov)"

/**
 * 20 Nov 2026 (Boss's exception: "try cheaper options increment it 1 by 1"): Bot settings' switch for the cheaper strike ([CheaperStrike]),
 * beside the funds check. On by default. Off lowers risk: one tap. On adds live trades: it takes the PIN (always: a live switch). Each flip
 * goes to the Autopilot change timeline (its Undo there takes the PIN when it turns it on).
 */
@Composable
internal fun CheaperStrikeCard(model: AppModel) {
    val view by CheaperStrike.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { CheaperStrike.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    CheaperStrikeCardFlow(view, said, onOff = { save { CheaperStrike.set(false) } }, onOnConfirmed = { save { CheaperStrike.set(true, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = CHEAPER_STRIKE_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching on; tests give their own). */
@Composable
internal fun CheaperStrikeCardFlow(view: CheaperStrike.View, said: String?, onOff: () -> Unit, onOnConfirmed: () -> Unit,
                                   pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    CheaperStrikeCardContent(view, said) { on -> if (on) askPin = true else onOff() }
    if (askPin) pin({ askPin = false; onOnConfirmed() }, { askPin = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun CheaperStrikeCardContent(view: CheaperStrike.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = CheaperStrike.SHORT) {
        Note("R51 Donchian 20, R72 Breadth Donchian and Liquidity 15+5 (every book): a live option buy the free cash cannot pay for even one lot " +
            "(after the lots are cut 3 -> 2 -> 1), or that Zerodha refuses for funds, moves one strike further out at a time - a put lower, a " +
            "call higher, same expiry - up to ${CheaperStrike.MAX_STEPS} strikes, and buys 1 lot of the first that fits, through every live gate " +
            "again. R51 / R72's paper trade keeps its own strike. Live at once, by your choice (not shadow first).")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(CHEAPER_STRIKE_SWITCH, if (view.on) "On: such a buy steps to a cheaper strike (1 lot)"
            else "Off: such buys go (or are skipped by the funds check) as before", view.on, keepSub = true, onChange = onSwitch)
        Note("Today: ${view.boughtToday} bought at a cheaper strike, ${view.gaveUpToday} gave up. Off is one tap; switching it on takes your PIN.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
