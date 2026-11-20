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
import com.optionslab.app.data.LiquidityFreshBar
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN prompt's reason when the fresh-bar check is switched off. */
internal const val FRESH_BAR_PIN = "Enter your app PIN to switch Liquidity's fresh-bar check off (live entries on a late or stale bar go to Zerodha again)."
/** The switch's row title. */
internal const val FRESH_BAR_SWITCH = "Skip Liquidity live entries on a late or stale bar"
/** The PIN prompt's reason when the list wait is switched on. */
internal const val LIST_WAIT_PIN = "Enter your app PIN to switch Liquidity's list wait on (a signal waits for today's instrument list, then may enter live)."
/** The list wait's row title. */
internal const val LIST_WAIT_SWITCH = "Let a Liquidity signal wait for a late instrument list"

/**
 * 20 Nov 2026 (audit: "Liquidity has no fresh-bar check"): Bot settings' switches for [LiquidityFreshBar], beside the funds check.
 *  - The fresh-bar check: off by default (shadow: entries go as before and the card counts today's it would have skipped). On only lowers
 *    risk, at once; off adds live entries back, so it takes the PIN.
 *  - The list wait (its sibling): off by default (as before: such a signal is lost, logged and counted). On adds live entries, so it takes
 *    the PIN; off is at once.
 */
@Composable
internal fun LiquidityFreshBarCard(model: AppModel) {
    val view by LiquidityFreshBar.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { LiquidityFreshBar.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    LiquidityFreshBarCardFlow(view, said, onOn = { save { LiquidityFreshBar.set(true) } },
        onOffConfirmed = { save { LiquidityFreshBar.set(false, pinConfirmed = true) } },
        onListOnConfirmed = { save { LiquidityFreshBar.setListWait(true, pinConfirmed = true) } },
        onListOff = { save { LiquidityFreshBar.setListWait(false) } }) { why, onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = why)
    }
}

/** The card and its PIN steps ([pin]: shown for the fresh-bar check's off and the list wait's on, with its reason; tests give their own). */
@Composable
internal fun LiquidityFreshBarCardFlow(view: LiquidityFreshBar.View, said: String?, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                       onListOnConfirmed: () -> Unit = {}, onListOff: () -> Unit = {},
                                       pin: @Composable (why: String, onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var ask by remember { mutableStateOf<String?>(null) }
    LiquidityFreshBarCardContent(view, said, onSwitch = { on -> if (on) onOn() else ask = FRESH_BAR_PIN },
        onListSwitch = { on -> if (on) ask = LIST_WAIT_PIN else onListOff() })
    ask?.let { why ->
        pin(why, { ask = null; if (why == FRESH_BAR_PIN) onOffConfirmed() else onListOnConfirmed() }, { ask = null })
    }
}

/** The card from plain state (tests drive it). */
@Composable
internal fun LiquidityFreshBarCardContent(view: LiquidityFreshBar.View, said: String?, onSwitch: (Boolean) -> Unit,
                                          onListSwitch: (Boolean) -> Unit = {}) {
    LedgerCard(title = LiquidityFreshBar.LABEL) {
        Note("A Liquidity 15+5 signal decided on a bar seen past its fill window (its close + 2 min), missing its last minute, or with the " +
            "index's candles behind could buy minutes late. Paper is never skipped (logged only).")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else {
            ToggleRow(FRESH_BAR_SWITCH, if (view.on) "On: such a live entry is not made; the bar is decided again if fresh candles land in time"
                else "Off (shadow): such entries go as before; each is logged", view.on, keepSub = true, onChange = onSwitch)
            ToggleRow(LIST_WAIT_SWITCH, if (view.listOn) "On: a signal decided before today's instrument list loads waits for it, within its fill window"
                else "Off: such a signal is lost as before (no contract); each is logged", view.listOn, keepSub = true, onChange = onListSwitch)
        }
        Note("Today: ${view.wouldSkipToday} it would have skipped" + (if (view.skippedToday > 0) ", ${view.skippedToday} skipped" else "") +
            ", ${view.listWouldWaitToday} it would have waited for the list. The check on and the list wait off need no PIN; the check off and " +
            "the list wait on (it adds live entries) take your PIN.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
