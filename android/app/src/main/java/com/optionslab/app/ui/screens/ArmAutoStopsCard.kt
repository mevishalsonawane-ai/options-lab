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
import com.optionslab.app.data.ArmAutoStopsHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.ira.ArmAutoStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The card's title. */
internal const val ARM_AUTOSTOPS_TITLE = "Automatic stops on R51, R72 and Liquidity"
/** The PIN prompt's reason when one of them is switched off. */
internal const val ARM_AUTOSTOPS_PIN = "Enter your app PIN to switch this automatic stop off for R51, R72 and Liquidity (they then run until you stop them)."

/**
 * 19 Nov 2026 (Boss: "i want it to run until i stop them"; he chose all four, each a switch turned off by PIN; 19 Nov, the fifth: "Yes, Jarvis cutoff should leave them alone too"): Bot settings' five switches
 * ([ArmAutoStopsHost]) - the forward-test switch-off, Liquidity's live stop rule, its self-review park and pause, the paper account stop,
 * Jarvis's losing-arm cutoff -
 * each "applies to R51, R72 and Liquidity". On (as before 19 Nov) lowers risk: one tap. Off takes the PIN, always. Boss's one-time choice
 * switched them all off; every change is in the change timeline.
 */
@Composable
internal fun ArmAutoStopsCard(model: AppModel) {
    val view by ArmAutoStopsHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ArmAutoStopsHost.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    ArmAutoStopsCardFlow(view, said, onOn = { r -> save { ArmAutoStopsHost.set(r, true) } },
        onOffConfirmed = { r -> save { ArmAutoStopsHost.set(r, false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = ARM_AUTOSTOPS_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching one off; tests give their own). */
@Composable
internal fun ArmAutoStopsCardFlow(view: ArmAutoStopsHost.View, said: String?, onOn: (ArmAutoStops.Rule) -> Unit, onOffConfirmed: (ArmAutoStops.Rule) -> Unit,
                                  pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    // Which rule's off waits for the PIN (null: none).
    var askPin by remember { mutableStateOf<ArmAutoStops.Rule?>(null) }
    ArmAutoStopsCardContent(view, said) { r, on -> if (on) onOn(r) else askPin = r }
    askPin?.let { r -> pin({ askPin = null; onOffConfirmed(r) }, { askPin = null }) }
}

/** The card from plain state (tests drive it). */
@Composable
internal fun ArmAutoStopsCardContent(view: ArmAutoStopsHost.View, said: String?, onSwitch: (ArmAutoStops.Rule, Boolean) -> Unit) {
    LedgerCard(title = ARM_AUTOSTOPS_TITLE) {
        Note("${ArmAutoStops.CHOICE}. Off, a stop below no longer stops R51 Donchian 20, R72 Breadth Donchian or Liquidity 15+5 (every book): " +
            "they run until you stop them. Every other arm keeps them all. The kill switch, the day lock, the guard and daily loss limit, Stop " +
            "for today, every live gate (Live with your PIN, AI trades go live, each arm's Trade live switch) and every exit still apply. " +
            "Nothing is switched back on by this: an arm a stop had already switched off, parked or paused waits for you.")
        if (!view.loaded) Note("Reading the switches...", Modifier.padding(top = 4.dp))
        else {
            for (r in ArmAutoStops.Rule.entries) {
                ToggleRow(r.switch, if (view.isOn(r)) "On (as before 19 Nov): ${r.words}" else "Off - ${ArmAutoStops.ROW}: ${r.words} no longer stops them",
                    view.isOn(r), keepSub = true, onChange = { on -> onSwitch(r, on) })
            }
        }
        Note("On again is one tap (it lowers risk); switching one off takes your PIN, here only - never Jarvis or a voice command. Every " +
            "change is in the change timeline.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
