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
import com.optionslab.app.data.S5Arm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.S5Trail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S5 Trail's row on Home's Strategies card (28 Oct, Boss's ask; [S5Arm]): labelled "failed research gates, Boss's exception", its
 * rules, signal source, record and today's decisions in its name's dialog, and its switch - on paper. Reads [S5Arm.view] (memory
 * only); every change goes through the arm off the main thread. No rupee figure here.
 */
@Composable
internal fun S5Row() {
    val v by S5Arm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { S5Arm.refresh() } } }
    S5RowContent(v, onArm = { on -> scope.launch(Dispatchers.IO) { runCatching { S5Arm.setArmed(on) } } })
}

/** What its name opens: the label first, the rules, the signal source, the record, today's decisions. */
internal fun s5Explanation(v: S5Arm.View): List<String> = listOf(
    S5Trail.NOT_PROVEN + ".",
    S5Trail.RULES + ".",
    "${S5Trail.SIGNAL}.",
    S5Trail.RECORD + ".",
    "Out at 15:15 (the paper book's square-off; the research held to 15:20) or 15:05 on expiry day. Live only through your live gates (Live with your PIN, AI trades go live, its own Trade live switch). Off: it runs in shadow, nothing bought.",
) + v.looks.map { l -> "${l.index} ${l.time}: ${if (l.taken) "taken" else noRupees(l.why)}" } + listOfNotNull(v.model?.let { "The network could not be read ($it): nothing is decided." })

/** [why] without its rupee figures (the row shows none). */
internal fun noRupees(why: String): String = why.replace(Regex("\\s*\\((?:[^()]*₹[^()]*)\\)"), "").replace(Regex("[−+-]?₹[\\d,]+"), "₹…")

@Composable
internal fun S5RowContent(v: S5Arm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(S5Trail.LABEL, s5Explanation(v))
            Text(if (v.armed) "ON · PAPER + ITS LIVE SWITCH · FAILED GATES, BOSS'S EXCEPTION" else "OFF · SHADOW · FAILED GATES, BOSS'S EXCEPTION", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint,
                fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            if (v.open.isNotEmpty()) RowBadge("HOLDING ${v.open.size} · PAPER", p.verdigris, Modifier.padding(top = 2.dp))
            val taken = v.today.size
            Text("Today: $taken taken, ${v.looks.count { !it.taken }} not · ${v.nets.size} closed so far, ${v.wins} won",
                style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            // 30 Oct (Boss): off, it runs in shadow - its would-be trades counted here (no rupee figure on the row).
            if (!v.armed) Text(com.optionslab.app.data.OffShadow.countWords(S5Trail.SOURCE), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "On paper: switch ${S5Trail.LABEL}" },
            checked = v.armed, onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
