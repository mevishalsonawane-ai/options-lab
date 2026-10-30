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
import com.optionslab.app.data.FlowArm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.signals.FlowRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * IraThinker's flow's row on Home's Strategies card (28 Oct, Boss's ask; [FlowArm]): labelled "not proven (R110)", its rules, signal
 * source and live path in its name's dialog, today's flow orders and skips, and its switch - on paper. The page with the animated
 * pipeline is Cabinet → Automation → IraThinker's flow. Reads [FlowArm.view] (memory only); every change goes through the arm off
 * the main thread. No rupee figure here (its page shows today's paper result while the app is unlocked).
 */
@Composable
internal fun FlowRow() {
    val v by FlowArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { FlowArm.refresh() } } }
    FlowRowContent(v, onArm = { on -> scope.launch(Dispatchers.IO) { runCatching { FlowArm.setArmed(on) } } })
}

/** What its name opens: the label first, the rules, the signal source, the live path, today's candidates. */
internal fun flowExplanation(v: FlowArm.View): List<String> = listOf(
    FlowRules.NOT_PROVEN + ".",
    "IraThinker's flow, ported: Scanner (the 09:15-09:30 opening range broken by a 5-minute close), IraScore (above ${FlowRules.MIN_SCORE}, " +
        "India VIX broken up first: breakdowns only), Time Machine (target first at least stop first) and Risk Gate (09:30-15:00). " +
        "A plan that passes all four is a paper order - no alert.",
    "Signal source: ${FlowRules.SIGNAL}.",
    "Index options only: ${FlowRules.INDICES.joinToString()}. At most ${FlowRules.DAILY_TOP_N} a day, one per index; the index's own expiry day is skipped.",
    "Exits: a premium stop 20% under the fill resting in the paper book, IraThinker's index stop and 2R target, out by 15:15.",
    "Live: only in Live (your PIN) with \"AI trades go live\" on and its own Trade live switch on (off until you turn it on).",
    "The animated flow: Cabinet → Automation → $FLOW_TITLE.",
) + v.candidates.map { c -> "${c.index}: ${if (c.taken) "taken" else "${c.stage} - ${c.why}"}" }

@Composable
internal fun FlowRowContent(v: FlowArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(FlowRules.LABEL, flowExplanation(v))
            Text(if (v.armed) "ON · PAPER + ITS LIVE SWITCH · NOT PROVEN (R110)" else "OFF · SHADOW · NOT PROVEN (R110)", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint,
                fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            if (v.open.isNotEmpty()) RowBadge("HOLDING ${v.open.size} · PAPER", p.verdigris, Modifier.padding(top = 2.dp))
            Text("Today: ${v.takenToday} flow order${if (v.takenToday == 1) "" else "s"}, ${v.skips.size} skipped · ${v.nets.size} closed so far",
                style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "On paper: switch ${FlowRules.LABEL}" },
            checked = v.armed, onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
