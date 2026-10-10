package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.optionslab.app.data.VixDivArm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.VixDivRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "VIX divergence (not proven)"'s row on Home's Strategies card (9 Oct, research R1 idea N13; [VixDivArm]): labelled
 * "Not proven — paper only", its research numbers above its rules, where its paper test stands, what it holds, each
 * index's last decision, and its switch - off until Boss turns it on, and on means paper only, always. Reads
 * [VixDivArm.view] (memory only); the book is read and the switch goes through [VixDivArm.setArmed] off the main thread.
 */
@Composable
internal fun VixDivRow() {
    val v by VixDivArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { VixDivArm.refresh() } } }
    VixDivRowContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { VixDivArm.setArmed(on) } } }
}

/** Where a paper-only arm's paper test stands ([com.optionslab.ira.Vetting]), and how many trades its research asks for. */
internal fun paperTestLine(name: String, nets: List<Double>, wanted: Int): String =
    com.optionslab.ira.Vetting.judge(name, nets).text() + " The research asks for about $wanted paper trades before any money."

/** The row from plain state ([v]) and the switch's callback. */
@Composable
internal fun VixDivRowContent(v: VixDivArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(VixDivRules.LABEL, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(VixDivRules.NOT_PROVEN, style = Type.label.copy(color = p.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            // The research numbers first, then the rules.
            Text(keepNumbersWhole(VixDivRules.RECORD), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            Text(keepNumbersWhole(VixDivRules.RULES), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            Text(if (v.armed) "ON · PAPER ONLY" else "OFF", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            Text(keepNumbersWhole(paperTestLine(VixDivRules.LABEL, v.nets, VixDivRules.PAPER_TRADES_WANTED)),
                style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            v.open.forEach { o ->
                Text(keepNumbersWhole("${o.symbol} · ${o.qty} @ %.2f · paper".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            }
            if (v.armed) v.status.toSortedMap().forEach { (idx, s) ->
                Text(keepNumbersWhole("$idx: $s"), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch ${VixDivRules.LABEL}" },
            checked = v.armed,
            onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
