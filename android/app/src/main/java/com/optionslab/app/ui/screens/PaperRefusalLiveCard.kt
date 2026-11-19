package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.PassedArmsHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN prompt's reason when the switch is turned on. */
internal const val REFUSAL_LIVE_PIN = "Enter your app PIN to let entries the paper book refused go live (it adds live entries)."
/** The switch's row title. */
internal const val REFUSAL_LIVE_SWITCH = "Send the live copy when only paper refused the entry"

/**
 * 19 Nov 2026 (audit batch 2, coordinator's decision B2-B): Bot settings' switch for [PassedArmsHost.setRefusalLive] - R51, R52, Solo 2.0
 * and R72 entries that could go live but whose paper side was refused (another arm holds the option on paper, the paper account guard,
 * no fresh paper price). Off by default: shadow - live is skipped as it always was, and the card counts today's "would have gone live".
 * On adds live entries, so it takes the PIN; off is one tap.
 */
@Composable
internal fun PaperRefusalLiveCard(model: AppModel) {
    val view by PassedArmsHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun save(f: suspend () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    PaperRefusalLiveCardFlow(view.refusalLiveOn, view.refusalShadowToday, said, loaded = view.loaded, onOff = { save { PassedArmsHost.setRefusalLive(false) } },
        onOnConfirmed = { save { PassedArmsHost.setRefusalLive(true, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = REFUSAL_LIVE_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching on; tests give their own). */
@Composable
internal fun PaperRefusalLiveCardFlow(on: Boolean, today: Int, said: String?, onOff: () -> Unit, onOnConfirmed: () -> Unit, loaded: Boolean = true,
                                      pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    LedgerCard(title = "Paper refusals and live") {
        Note("An R51, R52, Solo 2.0 or R72 entry that could go live, but whose paper side was refused (another arm holds the option on " +
            "paper, the paper account guard, no fresh paper price). Off, live is skipped as it always was and each is logged; on, its live " +
            "copy goes through every live gate.")
        if (!loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(REFUSAL_LIVE_SWITCH, if (on) "On: such entries go live (every live gate applies)" else "Off (shadow): skipped live; each is logged",
            on, keepSub = true) { want -> if (want) askPin = true else onOff() }
        Note("Today: $today would have gone live. On adds live entries, so it takes your PIN; off is one tap.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
    if (askPin) pin({ askPin = false; onOnConfirmed() }, { askPin = false })
}
