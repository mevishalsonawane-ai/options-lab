package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.DayStop

/** 30 Oct: the MCX card's own stop control's words. */
internal const val STOP_MCX = "Stop commodities"
internal const val START_MCX = "Start again"

/**
 * 30 Oct (Boss: "Make stop bot different for index and comodities"): the commodity side's own, smaller stop control on the MCX card.
 * It says the side's state and reason ("Commodity bots: stopped by you 10:42", "... stopped by the daily loss limit") and offers
 * "Stop commodities" (no new MCX entries today; optionally sell what the MCX arms hold through their own exits) or "Start again"
 * (refused while a loss stop holds: plans only lower risk). The main "Stop index bots" button never touches this side; Jarvis's
 * "stop all" stops both. Plain state and callbacks, so tests need no [AppModel].
 */
@Composable
internal fun McxStopBar(st: AppModel.McxStopState, onStop: (sell: Boolean) -> Unit, onStart: () -> Unit) {
    val p = LocalPalette.current
    var asking by remember { mutableStateOf(false) }
    val tone = if (st.stopped) p.oxblood else p.verdigris
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).background(tone.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(DayStop.sideLine(DayStop.Side.MCX, st.kept, st.loss), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f))
        // Smaller than the main button: a plain text button.
        val label = if (st.stopped) START_MCX else STOP_MCX
        Text(label, style = Type.bodySmall.copy(color = if (st.stopped) p.verdigris else p.oxblood, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.semantics { contentDescription = label }.clickable { asking = true }
                .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 14.dp))
    }
    if (asking) McxStopDialog(st, onStop = { sell -> asking = false; onStop(sell) }, onStart = { asking = false; onStart() }) { asking = false }
}

@Composable
private fun McxStopDialog(st: AppModel.McxStopState, onStop: (Boolean) -> Unit, onStart: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var sell by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text(when { st.loss -> "Stopped by the daily loss limit"; st.stopped -> "Start the commodity bots again?"; else -> "Stop the commodity bots for today?" }, style = Type.title) },
        text = {
            Column {
                Text(when {
                    st.loss -> "A daily loss limit stopped the commodity bots today: they stay stopped until tomorrow, and starting them again is refused."
                    st.stopped -> "The armed MCX arms trade again today. Nothing that is switched off is switched on. The index bots are not changed."
                    else -> "No new MCX entries today. The index bots keep trading: the main \"Stop index bots\" button is theirs. " +
                        "Open MCX positions keep their own stops, targets and exits."
                }, style = Type.bodySmall)
                if (!st.stopped) Row(Modifier.padding(top = 10.dp).clickable { sell = !sell }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(sell, { sell = it })
                    Text("Also sell what the MCX arms hold now (through their own exits)", style = Type.bodySmall.copy(color = p.ink))
                }
            }
        },
        confirmButton = {
            TextButton({
                when {
                    st.loss -> onClose()
                    st.stopped -> onStart()
                    else -> onStop(sell)
                }
            }) { Text(when { st.loss -> "OK"; st.stopped -> "Start"; else -> STOP_MCX }, color = if (st.stopped) p.verdigris else p.oxblood) }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
