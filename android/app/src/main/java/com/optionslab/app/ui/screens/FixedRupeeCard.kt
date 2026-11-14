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
import com.optionslab.app.data.FixedRupeeExits
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.engine.orb.FixedRupee
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 14 Nov 2026 (Boss: "Okay, now just make a change in liquidity, r51, r72. Make it 2k profit and 1k loss fix, dont break any thing";
 * CLAUDE.md's fifth exception): Bot settings' switch for the fixed ₹2,000 target / ₹1,000 stop on Liquidity 15+5, R51 and R72, next to
 * the arms' live lots. On by default. Off puts the arms' own wider exits back for new trades, live too, so it takes the PIN where one
 * is asked ([com.optionslab.app.security.GuardPin]); on again is at once. Labelled "failed gates, Boss's exception".
 */
@Composable
internal fun FixedRupeeCard(model: AppModel) {
    val view by FixedRupeeExits.view.collectAsState(Dispatchers.Main.immediate)
    val s by model.settings.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { FixedRupeeExits.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    FixedRupeeCardFlow(view, said, pinAsked = { com.optionslab.app.security.GuardPin.asks(true, s.live) },
        onOn = { save { FixedRupeeExits.set(true) } },
        onOffConfirmed = { save { FixedRupeeExits.set(false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel,
            why = "Enter your app PIN to switch the fixed ₹2k target / ₹1k stop off (Liquidity, R51 and R72 go back to their own wider exits, live too).")
    }
}

/** The card and its PIN step ([pin]: shown only when switching off and [pinAsked] says a PIN is asked; tests give their own). */
@Composable
internal fun FixedRupeeCardFlow(view: FixedRupeeExits.View, said: String?, pinAsked: () -> Boolean, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    FixedRupeeCardContent(view, said) { on -> if (on) onOn() else if (pinAsked()) askPin = true else onOffConfirmed() }
    if (askPin) pin({ askPin = false; onOffConfirmed() }, { askPin = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun FixedRupeeCardContent(view: FixedRupeeExits.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = "₹2k target / ₹1k stop") {
        Note("Liquidity 15+5 (every index), R51 Donchian 20 and R72 Breadth Donchian: each new trade is sold once its open P&L reaches " +
            "+₹2,000 or -₹1,000 for the whole position (all its lots; (price - fill) x quantity, before charges, as Kite shows it), on " +
            "paper and live. The arms' own exits stay and the first one wins; the ₹1,000 stop is used only where it is tighter than the " +
            "arm's own stop. Live, the ₹1,000 stop rests at Zerodha on the live fill and lots.")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(FixedRupee.SWITCH, if (view.on) "On: new trades of the three arms get the ₹2k target / ₹1k stop"
            else "Off: new trades use the arms' own exits", view.on, keepSub = true, onChange = onSwitch)
        Note("Failed gates, Boss's exception: ${FixedRupee.RECORD}. A trade keeps the exits it entered with. Switching it off takes " +
            "your PIN (the wider exits come back, live too); on again is at once. Every change is in the change timeline.",
            Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
