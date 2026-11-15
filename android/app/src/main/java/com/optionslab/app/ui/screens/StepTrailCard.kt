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
 * 15 Nov 2026 (Boss: "when a trade is bought the stop loss should be only 500 rs ... keep a 500 rs gap on every 500 rs gain"; per lot;
 * CLAUDE.md's fifth exception): Bot settings' switch for the ₹500 step trail on Liquidity 15+5, R51 and R72, next to
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
            why = "Enter your app PIN to switch the ₹500 step trail off (Liquidity, R51 and R72 go back to their own wider exits, live too).")
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
    LedgerCard(title = "₹500 step trail per lot") {
        Note("Liquidity 15+5 (every index), R51 Donchian 20 and R72 Breadth Donchian: each new trade starts with its stop at -₹500 a lot " +
            "(only where that is tighter than the arm's own stop). Once its best open P&L reaches +₹1,000 a lot, the stop moves to +₹500 a " +
            "lot, and up ₹500 a lot for every further ₹500 a lot - never down. P&L as Kite shows it ((price - fill) x quantity, before " +
            "charges), on paper and live; the arms' own exits stay and the first one wins. Live, the stop rests at Zerodha on the live " +
            "fill and lots and is moved up there.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(StepTrail.SWITCH, if (view.on) "On: new trades of the three arms get the ₹500 step trail"
            else "Off: new trades use the arms' own exits", view.on, keepSub = true, onChange = onSwitch)
        Note("Failed gates, Boss's exception: ${StepTrail.RECORD}. A trade keeps the rule it entered with. Switching it off takes " +
            "your PIN (the wider exits come back, live too); on again is at once. Every change is in the change timeline.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
