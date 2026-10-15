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
    // 15 Oct (Boss's cleanup): hidden once it holds nothing; restored from the Hidden arms list.
    val hidden by com.optionslab.app.data.HiddenArms.state.collectAsState(Dispatchers.Main.immediate)
    if (!com.optionslab.engine.orb.ArmsCleanup.visible(hidden, com.optionslab.engine.orb.ArmsCleanup.VIX_DIV)) return
    val v by VixDivArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { VixDivArm.refresh() } } }
    VixDivRowContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { VixDivArm.setArmed(on) } } }
}

/** Where a paper-only arm's paper test stands ([com.optionslab.ira.Vetting]), and how many trades its research asks for. */
internal fun paperTestLine(name: String, trades: List<com.optionslab.ira.Vetting.Trade>, wanted: Int,
                            today: java.time.LocalDate? = runCatching { com.optionslab.app.data.Market.today() }.getOrNull()): String =
    com.optionslab.ira.Vetting.judge(name, trades, today).text() + " The research asks for about $wanted paper trades before any money."

/** What the VIX divergence arm's name opens: its label, research (first), rules, paper test and each index's last decision. */
internal fun vixDivExplanation(v: VixDivArm.View): List<String> = listOf(
    VixDivRules.NOT_PROVEN + ".",
    VixDivRules.RECORD,
    VixDivRules.RULES,
    paperTestLine(VixDivRules.LABEL, v.trades, VixDivRules.PAPER_TRADES_WANTED),
    "Paper only: it never trades on Zerodha.",
) + v.status.toSortedMap().map { (idx, s) -> "$idx: $s" }

/** Its key numbers: its paper trades and their net after charges. */
internal fun vixDivNumbers(v: VixDivArm.View): String =
    "Paper: ${v.trades.size} trade${if (v.trades.size == 1) "" else "s"}, " + (if (v.trades.sumOf { it.net } < 0) "−₹" else "+₹") +
        "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(v.trades.sumOf { it.net }))

/** The row from plain state ([v]) and the switch's callback. COMPACT (15 Oct): name (tap: the explanation), badge, numbers. */
@Composable
internal fun VixDivRowContent(v: VixDivArm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(VixDivRules.LABEL, vixDivExplanation(v))
            Text(if (v.armed) "ON · PAPER ONLY" else "OFF", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold))
            Text(keepNumbersWhole(vixDivNumbers(v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            v.open.forEach { o ->
                Text(keepNumbersWhole("${o.symbol} · ${o.qty} @ %.2f · paper".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
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
