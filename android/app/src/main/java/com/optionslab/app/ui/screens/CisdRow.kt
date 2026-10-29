package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.CisdArm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.CisdNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * CISD + network's row on Home's Strategies card (29 Oct, Boss's ask; [CisdArm]): named "CISD + network (failed gates, Boss's
 * exception)", its rules, signal source, record and today's setups in its name's dialog, and its one-tap switch - on paper. Reads
 * [CisdArm.view] (memory only); every change goes through the arm off the main thread. No rupee figure here.
 */
@Composable
internal fun CisdRow() {
    val v by CisdArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { CisdArm.refresh() } } }
    CisdRowContent(v, onArm = { on -> scope.launch(Dispatchers.IO) { runCatching { CisdArm.setArmed(on) } } })
}

/** What its name opens: the label first, the rules, the signal source, the record, the exits, today's setups. */
internal fun cisdExplanation(v: CisdArm.View): List<String> = listOf(
    CisdNet.NOT_PROVEN + ".",
    CisdNet.RULES + ".",
    "${CisdNet.SIGNAL}.",
    CisdNet.RECORD + ".",
    "Built switched off: " + CisdNet.IV_GAP + ".",
    "Out at 15:15 (the paper book's square-off; the research held to 15:20). Never live.",
) + v.looks.map { l -> "${l.index} ${l.time} ${if (l.call) "call" else "put"}: ${if (l.taken) "taken" else noRupees(l.why)}" } +
    listOfNotNull(v.model?.let { "The network could not be read ($it): nothing is bought." })

@Composable
internal fun CisdRowContent(v: CisdArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(CisdNet.SHOWN, cisdExplanation(v))
            Text(if (v.armed) "ON · PAPER · FAILED GATES, BOSS'S EXCEPTION" else "OFF · FAILED GATES, BOSS'S EXCEPTION",
                style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp))
            if (v.open.isNotEmpty()) RowBadge("HOLDING ${v.open.size} · PAPER", p.verdigris, Modifier.padding(top = 2.dp))
            Text("Today: ${v.today.size} taken, ${v.looks.count { !it.taken }} setups not · ${v.nets.size} closed so far, ${v.wins} won",
                style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "On paper: switch ${CisdNet.SHOWN}" },
            checked = v.armed, onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
