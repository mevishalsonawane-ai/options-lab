package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.optionslab.app.data.NightArm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.NightRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Night (R3)'s row on Home's Strategies card (08 Oct, research X2 rule R3): its switch (paper only, always), what it holds
 * overnight, its last decision for each index and its research numbers (not proven). Reads [NightArm.view]; the switch
 * goes through [NightArm.setArmed] off the main thread.
 */
@Composable
internal fun NightRow() {
    val v by NightArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { NightArm.refresh() } } }
    NightRowContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { NightArm.setArmed(on) } } }
}

/** The row from plain state ([v]) and the switch's callback. */
@Composable
internal fun NightRowContent(v: NightArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NightRules.LABEL, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(8.dp))
                val (label, color) = when {
                    v.open.isNotEmpty() -> "HOLDING OVERNIGHT · PAPER" to p.verdigris
                    v.armed -> "ARMED · PAPER ONLY · NOT PROVEN" to p.amber
                    else -> "OFF" to p.inkFaint
                }
                Text(label, style = Type.label.copy(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            }
            Text(keepNumbersWhole(NightRules.RULES), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            Text(keepNumbersWhole("Research: pre-holdout Rs ${NightRules.PRE_PER_DAY}/day, holdout Rs ${NightRules.HOLD_PER_DAY}/day · not proven"),
                style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            v.open.forEach { o ->
                Text(keepNumbersWhole("${o.symbol} · ${o.qty} @ %.2f · sells 09:16".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            }
            if (v.armed) v.status.toSortedMap().forEach { (idx, s) ->
                Text(keepNumbersWhole("$idx: $s"), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Arm ${NightRules.LABEL}" },
            checked = v.armed,
            onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
