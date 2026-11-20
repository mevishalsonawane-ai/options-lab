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

/**
 * 20 Nov 2026 (audit: "Liquidity has no fresh-bar check"): Bot settings' switch for [LiquidityFreshBar], beside the funds check. Off by
 * default: shadow - entries go as before and the card counts today's it would have skipped. On only lowers risk, at once; off adds live
 * entries back, so it takes the PIN.
 */
@Composable
internal fun LiquidityFreshBarCard(model: AppModel) {
    val view by LiquidityFreshBar.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { LiquidityFreshBar.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    LiquidityFreshBarCardFlow(view, said, onOn = { save { LiquidityFreshBar.set(true) } },
        onOffConfirmed = { save { LiquidityFreshBar.set(false, pinConfirmed = true) } }) { onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = FRESH_BAR_PIN)
    }
}

/** The card and its PIN step ([pin]: shown only when switching off; tests give their own). */
@Composable
internal fun LiquidityFreshBarCardFlow(view: LiquidityFreshBar.View, said: String?, onOn: () -> Unit, onOffConfirmed: () -> Unit,
                                       pin: @Composable (onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    LiquidityFreshBarCardContent(view, said) { on -> if (on) onOn() else askPin = true }
    if (askPin) pin({ askPin = false; onOffConfirmed() }, { askPin = false })
}

/** The card from plain state (tests drive it). */
@Composable
internal fun LiquidityFreshBarCardContent(view: LiquidityFreshBar.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = LiquidityFreshBar.LABEL) {
        Note("A Liquidity 15+5 signal decided on a bar seen past its fill window (its close + 2 min), missing its last minute, or with the " +
            "index's candles behind could buy minutes late. Paper is never skipped (logged only).")
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow(FRESH_BAR_SWITCH, if (view.on) "On: such a live entry is not made; the bar is decided again if fresh candles land in time"
            else "Off (shadow): such entries go as before; each is logged", view.on, keepSub = true, onChange = onSwitch)
        Note("Today: ${view.wouldSkipToday} it would have skipped" + (if (view.skippedToday > 0) ", ${view.skippedToday} skipped" else "") +
            ". On only lowers risk (no PIN); switching it off takes your PIN.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
