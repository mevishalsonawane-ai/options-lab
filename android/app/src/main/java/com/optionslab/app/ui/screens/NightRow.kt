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
    // 15 Oct (Boss's cleanup): hidden once it holds nothing; restored from the Hidden arms list.
    val hidden by com.optionslab.app.data.HiddenArms.state.collectAsState(Dispatchers.Main.immediate)
    if (!com.optionslab.engine.orb.ArmsCleanup.visible(hidden, com.optionslab.engine.orb.ArmsCleanup.NIGHT)) return
    val v by NightArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { NightArm.refresh() } } }
    NightRowContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { NightArm.setArmed(on) } } }
}

/** What Night (R3)'s name opens: its rules, research and label, and each index's last decision. */
internal fun nightExplanation(v: NightArm.View): List<String> = listOf(
    NightRules.RULES + ".",
    "Research: pre-holdout Rs ${NightRules.PRE_PER_DAY}/day, holdout Rs ${NightRules.HOLD_PER_DAY}/day · not proven.",
    "Paper first: at Zerodha only by its own \"Trade live\" switch (beside the Live switch), in Live with AI trades on.",
) + v.status.toSortedMap().map { (idx, s) -> "$idx: $s" }

/** The row from plain state ([v]) and the switch's callback. COMPACT (15 Oct): name (tap: the explanation), badge, holdings. */
@Composable
internal fun NightRowContent(v: NightArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplainedName(NightRules.LABEL, nightExplanation(v))
                Spacer(Modifier.width(8.dp))
                val (label, color) = when {
                    v.open.isNotEmpty() -> "HOLDING OVERNIGHT · PAPER" to p.verdigris
                    v.armed -> "ARMED · PAPER ONLY" to p.amber
                    else -> "OFF" to p.inkFaint
                }
                RowBadge(label, color)
            }
            if (v.open.isEmpty()) Text(if (v.armed) "No position · decides at 15:20" else "Off", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            v.open.forEach { o ->
                Text(keepNumbersWhole("${o.symbol} · ${o.qty} @ %.2f · sells 09:16".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
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
