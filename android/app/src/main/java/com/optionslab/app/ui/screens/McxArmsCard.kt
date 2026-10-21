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
import com.optionslab.app.data.McxPaperArms
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.mcx.McxArmRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** The heading of the MCX paper bots' group on Home's Strategies card. */
internal const val MCX_GROUP = "MCX (commodities)"

/** Where the MCX paper bots live (9 Oct, Boss: "it should be on home screen"); the Commodities page says so in one line. */
internal const val MCX_ARMS_WHERE = "MCX paper bots are on Home → MCX card."

/**
 * The MCX paper bots on Home's Strategies card (9 Oct, research M3 / M4 / M2; [McxPaperArms]), in their own
 * "MCX (commodities)" group under the NSE arms: one row each, labelled "Not proven — paper only" with its research numbers
 * above its rules, and its switch - off until Boss turns it on, and on means paper only, always. Reads [McxPaperArms.view]
 * (the one place they are shown; the Commodities page only points here); a switch goes through [McxPaperArms.setArmed]
 * off the main thread.
 */
@Composable
internal fun McxArmRows(divider: Boolean = true) {
    val v by McxPaperArms.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { McxPaperArms.refresh() } } }
    McxArmsGroup(v, divider) { arm, on -> scope.launch(Dispatchers.IO) { runCatching { McxPaperArms.setArmed(arm, on) } } }
    // 21 Oct: NR7 crude, record only (no orders, ever; [com.optionslab.app.data.McxNr7Record]).
    val n by com.optionslab.app.data.McxNr7Record.view.collectAsState(Dispatchers.Main.immediate)
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.McxNr7Record.refresh() } } }
    McxNr7Content(n) { on -> scope.launch(Dispatchers.IO) { runCatching { com.optionslab.app.data.McxNr7Record.setOn(on) } } }
}

/** What the NR7 record's state line says. */
internal fun nr7State(v: com.optionslab.app.data.McxNr7Record.View): String =
    if (v.on) "RECORDING · NO ORDERS" else "OFF · RECORD ONLY (NO ORDERS)"

/** Its key numbers: the closed would-be trades and their net after charges and spreads. */
internal fun nr7Numbers(v: com.optionslab.app.data.McxNr7Record.View): String {
    val n = v.closed.size
    val net = v.closedNet
    return "Would-be: $n trade${if (n == 1) "" else "s"}, ${if (net < 0) "−₹" else "+₹"}${"%,.0f".format(Locale.ENGLISH, kotlin.math.abs(net))} · never traded"
}

/** The NR7 record's row from plain state ([v]) and its switch's callback. */
@Composable
internal fun McxNr7Content(v: com.optionslab.app.data.McxNr7Record.View, onSwitch: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(com.optionslab.engine.mcx.McxNr7Rules.LABEL, listOfNotNull(
                "Record only: it never places an order, on paper or live. Not proven: it failed one research gate.",
                com.optionslab.engine.mcx.McxNr7Rules.RECORD,
                com.optionslab.engine.mcx.McxNr7Rules.RULES,
            ) + v.status.entries.sortedBy { it.key }.map { (u, s) -> "$u: $s" } + v.closed.takeLast(5).reversed().map { t ->
                "%s %s %s: %s".format(Locale.ENGLISH, t.entryTime.toLocalDate(), t.symbol, t.why ?: "",
                    (if ((t.net ?: 0.0) < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(t.net ?: 0.0)))
            })
            Text(nr7State(v), style = Type.label.copy(color = if (v.on) p.verdigris else p.inkFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Text(keepNumbersWhole(nr7Numbers(v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            v.open.forEach { o ->
                Text(keepNumbersWhole("Would hold ${o.symbol} · ${o.qty} @ %.2f · record only".format(Locale.ENGLISH, o.fill)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Record only, no orders: switch ${com.optionslab.engine.mcx.McxNr7Rules.LABEL}" },
            checked = v.on,
            onCheckedChange = onSwitch,
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** The group from plain state ([v]) and the switches' callback (arm, on); it sits in the Strategies card's column. */
@Composable
internal fun McxArmsGroup(v: McxPaperArms.View, divider: Boolean = true, onArm: (String, Boolean) -> Unit) {
    val p = LocalPalette.current
    if (divider) Rule()
    // COMPACT (15 Oct): the group's note is in its name's dialog.
    ExplainedName(MCX_GROUP, listOf(MCX_NOTE), Modifier.padding(top = if (divider) 4.dp else 0.dp), style = Type.title.copy(color = p.ink, fontSize = 15.sp))
    // 21 Oct (Boss's request): the SILVERM morning call and the 12-month trend are hidden ([com.optionslab.app.data.HiddenArms]);
    // a hidden arm's row shows only while it still holds a position (it is restored from Home → Strategies → Hidden arms).
    val hidden by com.optionslab.app.data.HiddenArms.state.collectAsState(Dispatchers.Main.immediate)
    McxPaperArms.ARMS.filter { com.optionslab.engine.orb.ArmsCleanup.visible(hidden, it) }.forEach { arm -> McxArmRow(arm, v, onArm) }
}

/** What the MCX group's name opens. */
internal const val MCX_NOTE = "Research ideas on paper only: none is proven. 1 lot each, never sent to Zerodha, off until you switch one on."

/** What an MCX bot's name opens: its label, research (first), rules, paper test, its last decision and why it was switched off. */
internal fun mcxExplanation(arm: String, v: McxPaperArms.View): List<String> = listOfNotNull(
    McxArmRules.NOT_PROVEN + ".",
    McxPaperArms.record(arm),
    McxPaperArms.rules(arm),
    com.optionslab.ira.Vetting.judge(McxPaperArms.label(arm), v.trades[arm].orEmpty(), runCatching { com.optionslab.app.data.Market.today() }.getOrNull()).text(),
    v.status[arm]?.takeIf { v.isArmed(arm) },
    com.optionslab.engine.mcx.McxMorningPark.ROW.takeIf { !v.isArmed(arm) && arm in v.parked },
)

/** Its key numbers: its closed paper trades and their net after charges. */
internal fun mcxNumbers(arm: String, v: McxPaperArms.View): String {
    val t = v.trades[arm].orEmpty()
    val net = t.sumOf { it.net }
    return "Paper: ${t.size} trade${if (t.size == 1) "" else "s"}, ${if (net < 0) "−₹" else "+₹"}${"%,.0f".format(Locale.ENGLISH, kotlin.math.abs(net))}"
}

@Composable
private fun McxArmRow(arm: String, v: McxPaperArms.View, onArm: (String, Boolean) -> Unit) {
    val p = LocalPalette.current
    val on = v.isArmed(arm)
    val open = v.open.filter { it.arm == arm }
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // COMPACT (15 Oct): name (tap: its label, research, rules, paper test and notes), state, key numbers, holdings.
            ExplainedName(McxPaperArms.label(arm), mcxExplanation(arm, v))
            Text(if (on) "ON · PAPER ONLY" else if (arm in v.parked) "OFF · switched off on your OK (14 Oct)" else "OFF",
                style = Type.label.copy(color = if (on) p.verdigris else if (arm in v.parked) p.amber else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold))
            Text(keepNumbersWhole(mcxNumbers(arm, v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            open.forEach { o ->
                Text(keepNumbersWhole("${if (o.side > 0) "Long" else "Short"} ${o.symbol} · ${o.qty} @ %.2f · paper".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch ${McxPaperArms.label(arm)}" },
            checked = on,
            onCheckedChange = { x -> onArm(arm, x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
