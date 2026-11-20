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

/** The PIN prompt's reason when the stale-history check is switched off. */
internal const val STALE_HISTORY_PIN = "Enter your app PIN to switch the stale-history check off (such R51 and R72 breaks go live again)."
/** The PIN prompt's reason when it is switched on. */
internal const val STALE_HISTORY_PIN_ON = "Enter your app PIN to switch the stale-history check on (a better history then replaces the one in use, which can change live breaks)."
/** The switch's row title. */
internal const val STALE_HISTORY_SWITCH = "Hold R51 / R72 breaks on a stale history"

/**
 * 20 Nov 2026 (audit B5, item 1): Bot settings' switch for [PassedArmsHost.setStaleSkip] - an R51 or R72 break whose 20-bar channel reads
 * an earlier session that does not reach the last trading day (yesterday missing, or read in part). Off by default: shadow - the break is
 * taken as before and the card counts today's "would have skipped". On (the break waits while the history is read again, and is let go if it
 * is not whole in time; a better history also replaces the one in use, which can change live breaks) and off (those live entries come
 * back) both take the PIN (20 Nov 2026, deep review).
 */
@Composable
internal fun StaleHistoryCard(model: AppModel) {
    val view by PassedArmsHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun save(f: suspend () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    StaleHistoryCardFlow(view.staleOn, view.staleShadowToday, said, loaded = view.loaded,
        onOnConfirmed = { save { PassedArmsHost.setStaleSkip(true, pinConfirmed = true) } },
        onOffConfirmed = { save { PassedArmsHost.setStaleSkip(false, pinConfirmed = true) } }) { want, onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = if (want) STALE_HISTORY_PIN_ON else STALE_HISTORY_PIN)
    }
}

/** The card and its PIN step ([pin]: shown for either way, with the way asked ([want]); tests give their own). */
@Composable
internal fun StaleHistoryCardFlow(on: Boolean, today: Int, said: String?, onOnConfirmed: () -> Unit, onOffConfirmed: () -> Unit, loaded: Boolean = true,
                                  pin: @Composable (want: Boolean, onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf<Boolean?>(null) }
    LedgerCard(title = "R51 / R72 stale history") {
        Note("R51 and R72 read the index's earlier sessions for their first breaks of the day (09:20 - 10:55). When those do not reach the " +
            "last trading day (yesterday missing, or read in part), a break can be false. The history is read again 1, 2, then every 5 " +
            "minutes either way.")
        if (!loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(STALE_HISTORY_SWITCH, if (on) "On: such a break waits while the history is read again, else it is let go (paper and live)"
            else "Off (shadow): taken as before; each is logged", on, keepSub = true) { want -> askPin = want }
        Note("Today: $today would have skipped. Switching it on or off takes your PIN: on, a better history also replaces the one in use, " +
            "which can change live breaks.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
    askPin?.let { want -> pin(want, { askPin = null; if (want) onOnConfirmed() else onOffConfirmed() }, { askPin = null }) }
}
