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
import com.optionslab.app.data.StepTrailExits
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.engine.orb.StepTrail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 16 Nov 2026, fourth version (Boss: "For r51/r72 use Step lock (+₹500 at +₹1,000, and so on) And for liquidity use Breakeven at +₹1,000"):
 * the card names the step lock (R51, R72) and the breakeven at +₹1,000 (Liquidity) on top of the breakeven at +10%, under the same switch.
 * 15 Nov 2026 (Boss: "Switch to + picker + breakeven at +10% (V4)"; per lot; CLAUDE.md's fifth exception,
 * replacing the morning's ₹500 step trail): Bot settings' switch for the breakeven at +10% on Liquidity 15+5, R51 and R72, next to
 * the arms' live lots. On by default. Off puts the arms' own exits back alone for new trades, live too, so it takes the PIN where one
 * is asked ([com.optionslab.app.security.GuardPin]); on again is at once. Labelled "failed gates, Boss's exception".
 */
@Composable
internal fun StepTrailCard(model: AppModel) {
    val view by StepTrailExits.view.collectAsState(Dispatchers.Main.immediate)
    val s by model.settings.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { StepTrailExits.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    StepTrailCardFlow(view, said, pinAsked = { com.optionslab.app.security.GuardPin.asks(true, s.live) },
        onOn = { save { StepTrailExits.set(true) } },
        onOffConfirmed = { save { StepTrailExits.set(false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel,
            why = "Enter your app PIN to switch the step lock and breakeven off (Liquidity, R51 and R72 go back to their own exits alone, live too).")
    }
}

/** The card and its PIN step ([pin]: shown only when switching off and [pinAsked] says a PIN is asked; tests give their own). */
@Composable
internal fun StepTrailCardFlow(view: StepTrailExits.View, said: String?, pinAsked: () -> Boolean, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    StepTrailCardContent(view, said) { on -> if (on) onOn() else if (pinAsked()) askPin = true else onOffConfirmed() }
    if (askPin) pin({ askPin = false; onOffConfirmed() }, { askPin = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun StepTrailCardContent(view: StepTrailExits.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = "Step lock and breakeven") {
        Note("Liquidity 15+5 (every index), R51 Donchian 20 and R72 Breadth Donchian: each new trade keeps its arm's own stop (its -15%). " +
            "Once the option is 10% above the fill, the stop moves to breakeven (the fill plus the charges) - never down. " +
            "R51 and R72: once the best open P&L is +₹1,000 a lot after charges, the stop locks +₹500 a lot, then stays ₹500 a lot under each " +
            "further ₹500 step (+₹1,500 locks +₹1,000, +₹2,000 locks +₹1,500, ...). Liquidity: the stop moves to breakeven at +10% or " +
            "+₹1,000 a lot, whichever comes first. A fast market can sell below a stop. R72 and R51 keep the R151 picker. On paper and live; " +
            "the arms' own exits (ladder, time stop, square-off) stay and the first one wins. Live, the stop rests at Zerodha on the live fill " +
            "and is moved up there.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(StepTrail.SWITCH_V5, if (view.on) "On: new trades of the three arms get the step lock or breakeven at +₹1,000, and the breakeven at +10%"
            else "Off: new trades use the arms' own exits", view.on, keepSub = true, onChange = onSwitch)
        Note("Failed gates, Boss's exception: ${StepTrail.LOCK_RECORD}. Before it (breakeven at +10% alone, V4): ${StepTrail.RECORD}. " +
            "A trade keeps the rule it entered with (open trades from before keep the breakeven at +10% alone). Switching it off takes " +
            "your PIN (the arms' own exits alone come back, live too); on again is at once. Every change is in the change timeline.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
