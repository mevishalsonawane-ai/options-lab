package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.ManualCover
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.ira.ManualCoverRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Boss, 23 Nov 2026 ("I want you to check live orders placed manually in kite immediately, and set a target of 10 points and stoploss of
 * 10points, a button, where i could stop/start the functionality"; the button "as a Home screen card"): Home's "Stop & target on my Kite
 * buys". The switch is Bot settings' own "Stop and target on my manual positions" ([ManualCover], the same saved key), so the two always
 * agree: off is one tap, on takes the PIN (as there). The stop's and the target's points (10 and 10 by default) are edited here: in Live,
 * widening the stop takes the PIN; narrowing it and any target change are one tap. It also shows the last position protected, or why the
 * last one was left alone. Not in IraGoldAlgo (it places no stop on manual positions).
 */
@Composable
internal fun ManualCoverHomeCard(model: AppModel) {
    val view by ManualCover.view.collectAsState(Dispatchers.Main.immediate)
    val settings by model.settings.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ManualCover.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    ManualCoverHomeCardFlow(view, settings.live, said,
        onOff = { save { ManualCover.setOn(false) } },
        onOnConfirmed = { save { ManualCover.setOn(true, pinConfirmed = true) } },
        onSave = { stop, target, pin -> save { ManualCover.setPoints(stop, target, pinConfirmed = pin) } },
        onSaid = { said = it }) { why, onOk, onCancel ->
        Reauth(model, onOk = onOk, onCancel = onCancel, why = why)
    }
}

/** What the PIN step says for each reason (the card's own flow; tests read them). */
internal const val MANUAL_COVER_PIN_ON = "Enter your app PIN to have the app place a stop and target on live options you buy yourself."
internal fun manualCoverPinWiden(from: Double, to: Double): String =
    "Enter your app PIN to widen the stop on your own buys from ${ManualCoverRule.points(from)} to ${ManualCoverRule.points(to)} points."

/**
 * The card and its PIN steps ([pin]: the PIN dialog with its reason; tests give their own). [live]: the app is in Live (widening the
 * stop then takes the PIN). [onSave]: the stop and target points as typed, and whether the PIN was given.
 */
@Composable
internal fun ManualCoverHomeCardFlow(view: ManualCover.View, live: Boolean, said: String?, onOff: () -> Unit, onOnConfirmed: () -> Unit,
                                     onSave: (Double, Double, Boolean) -> Unit, onSaid: (String) -> Unit,
                                     pin: @Composable (why: String, onOk: () -> Unit, onCancel: () -> Unit) -> Unit) {
    var askOn by remember { mutableStateOf(false) }
    var widen by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    ManualCoverHomeCardContent(view, said, onSwitch = { on -> if (on) askOn = true else onOff() }) { stopText, targetText ->
        val stop = stopText.trim().toDoubleOrNull()
        val target = targetText.trim().toDoubleOrNull()
        if (stop == null || target == null || !stop.isFinite() || !target.isFinite()) {
            onSaid("Points: a number from ${ManualCoverRule.points(ManualCoverRule.MIN_POINTS)} to ${ManualCoverRule.points(ManualCoverRule.MAX_POINTS)}.")
            return@ManualCoverHomeCardContent
        }
        // In Live a wider stop (more points) takes the PIN first; a narrower one and any target change go at once.
        if (live && ManualCoverRule.stopWidens(view.stopPoints, stop)) widen = stop to target else onSave(stop, target, false)
    }
    if (askOn) pin(MANUAL_COVER_PIN_ON, { askOn = false; onOnConfirmed() }, { askOn = false })
    widen?.let { (stop, target) ->
        pin(manualCoverPinWiden(view.stopPoints, ManualCoverRule.clampPoints(stop, view.stopPoints)), { widen = null; onSave(stop, target, true) }, { widen = null })
    }
}

/** The card from plain state (tests drive it): [onSavePoints] gets the two fields' text. */
@Composable
internal fun ManualCoverHomeCardContent(view: ManualCover.View, said: String?, onSwitch: (Boolean) -> Unit, onSavePoints: (String, String) -> Unit) {
    var stopText by remember(view.stopPoints) { mutableStateOf(ManualCoverRule.points(view.stopPoints)) }
    var targetText by remember(view.targetPoints) { mutableStateOf(ManualCoverRule.points(view.targetPoints)) }
    LedgerCard(title = "Stop & target on my Kite buys", modifier = Modifier.testTag("manualcover-home")) {
        Note("A live option you buy yourself, in Kite or here, that nothing in the app follows gets a stop " +
            "${ManualCoverRule.points(view.stopPoints)} points under your average price and a target sell ${ManualCoverRule.points(view.targetPoints)} " +
            "points over it, as soon as Zerodha reports your Kite buy filled.")
        // Until the saved switch is read, the card says so (never "On" from the default); the switch is not offered.
        if (!view.loaded) Note("Reading the switch...", Modifier.padding(top = 4.dp))
        else ToggleRow("Protect my own buys", if (view.on) "On: placed automatically in market hours (09:15-15:24)"
            else "Off: positions you buy yourself are left as they are", view.on, keepSub = true, onChange = onSwitch)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            OutlinedTextField(stopText, { stopText = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Stop: points under") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).padding(end = 4.dp).testTag("manualcover-home-stop"))
            OutlinedTextField(targetText, { targetText = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Target: points over") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("manualcover-home-target"))
        }
        // (Review: offered once the saved points are read - before that the card's "wider or narrower" could differ from the saved one's.)
        BrassButton("Save the points", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("manualcover-home-save"), enabled = view.loaded) {
            onSavePoints(stopText, targetText)
        }
        Note("Points on the option's price (bought at 120: stop 110, target 130), from ${ManualCoverRule.points(ManualCoverRule.MIN_POINTS)} to " +
            "${ManualCoverRule.points(ManualCoverRule.MAX_POINTS)}. Below about ₹${ManualCoverRule.points(view.stopPoints)} the stop can sit near zero; " +
            "the 5% stop is used only under 2 ticks. Positions already protected keep their levels. In Live, a wider stop takes your PIN; switching " +
            "it on takes your PIN.", Modifier.padding(top = 4.dp))
        Note("Only NSE and BSE options you bought: never shorts, futures, stock or MCX.", Modifier.padding(top = 2.dp))
        Note("Intraday (MIS): only the stop rests at Zerodha; the target is watched by the app (only while the app runs). Carried (NRML): " +
            "both rest at Zerodha, as one GTT overnight.", Modifier.padding(top = 2.dp))
        // 23 Nov 2026 (B6-I round 2): Zerodha may refuse a second full sale of his beside the app's SL - how to exit or set his own stop.
        Note(ManualCoverRule.OWN_EXIT_HELP, Modifier.padding(top = 2.dp).testTag("manualcover-home-help"))
        Note("Your own orders come first: move the app's stop or target in Kite and the app keeps your level; your own stop or GTT in Kite " +
            "brings the app's stop down to the rest, or takes it out when yours covers all you hold; your own target replaces the app's target " +
            "(never two exits). A buy of yours is " +
            "covered about 3 s after it fills (a stop you set at once wins); lots you add are covered too. Never on a position with your own " +
            "stop, target or GTT already working.", Modifier.padding(top = 2.dp))
        Note(view.last.ifEmpty { "Nothing protected or left alone yet." }, Modifier.padding(top = 4.dp).testTag("manualcover-home-last"))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
