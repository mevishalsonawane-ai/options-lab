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
 * 15 Nov 2026 (Boss: "Make it 1.5k stoploss" and "Instead 10% breakeven, make it 5% breakeven"; per lot; CLAUDE.md's fifth exception,
 * replacing the morning's ₹500 step trail): Bot settings' switch for the ₹1,500 stop a lot + breakeven at +5% on Liquidity 15+5, R51 and R72, next to
 * the arms' live lots. On by default. Off puts the arms' own wider exits back for new trades, live too, so it takes the PIN where one
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
            why = "Enter your app PIN to switch the ₹1,500 stop and breakeven off (Liquidity, R51 and R72 go back to their own wider exits, live too).")
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
    LedgerCard(title = "₹1,500 stop a lot + breakeven at +5%") {
        Note("Liquidity 15+5 (every index), R51 Donchian 20 and R72 Breadth Donchian: each new trade starts with its stop where it loses " +
            "₹1,500 a lot after charges (only where that is tighter than the arm's own stop; a cheap option keeps its 15%). Once the option " +
            "is 5% above the fill, the stop moves to breakeven (the fill plus the charges) - never down. A fast market can sell below a stop. " +
            "On paper and live; the arms' own exits (ladder, time stop, square-off) stay and the first one wins. Live, the stop rests at " +
            "Zerodha on the live fill and is moved up there. It replaced the ₹500 step trail of the morning.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(StepTrail.SWITCH, if (view.on) "On: new trades of the three arms get the ₹1,500 stop and the breakeven at +5%"
            else "Off: new trades use the arms' own exits", view.on, keepSub = true, onChange = onSwitch)
        Note("Failed gates, Boss's exception: ${StepTrail.RECORD}. A trade keeps the rule it entered with. Switching it off takes " +
            "your PIN (the wider exits come back, live too); on again is at once. Every change is in the change timeline.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
