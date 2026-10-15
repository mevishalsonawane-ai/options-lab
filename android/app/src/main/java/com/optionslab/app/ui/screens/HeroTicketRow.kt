package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.HeroTicketArm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.HeroTicketRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Hero ₹1,000 ticket"'s row on Home's Strategies card (15 Oct, Boss's choice; [HeroTicketArm]): labelled "Boss's choice - did
 * not pass research (R50): about -Rs 525 per Rs 1,000 on average; jackpots are rare", its own measured record, its rules and
 * signal source, today's tickets with the running P&L, its exit (trail only / 2× / 3×, kept) and its switch - on paper only,
 * always; one tap switches it off. Reads [HeroTicketArm.view] (memory only); the book is read and every change goes through
 * the arm off the main thread.
 */
@Composable
internal fun HeroTicketRow() {
    val v by HeroTicketArm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { HeroTicketArm.refresh() } } }
    HeroTicketRowContent(v,
        onArm = { on -> scope.launch(Dispatchers.IO) { runCatching { HeroTicketArm.setArmed(on) } } },
        onTarget = { t -> scope.launch(Dispatchers.IO) { runCatching { HeroTicketArm.setTarget(t) } } },
        onStart = { s -> scope.launch(Dispatchers.IO) { runCatching { HeroTicketArm.setTrailStart(s) } } })
}

private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

/** The running P&L line: today's tickets (sold ones after charges, held ones at their last price) and its own record. */
internal fun heroTicketPnlLine(v: HeroTicketArm.View): String =
    "Today: ${if (v.today.isEmpty()) "no ticket" else "${v.today.size} ticket${if (v.today.size == 1) "" else "s"}, ${rs(v.todayPnl)}"} · " +
        "its own record: ${v.nets.size} closed, ${rs(v.nets.sum())} after charges"

/** What the ticket's name opens: Boss's label (first), its measured record, rules, signal source, the trail note, its record apart. */
internal fun heroTicketExplanation(v: HeroTicketArm.View): List<String> = listOf(
    HeroTicketRules.RECORD + ".",
    HeroTicketRules.MEASURED,
    HeroTicketRules.RULES,
    HeroTicketRules.SIGNAL_SOURCE,
    HeroTicketRules.TRAIL_START_NOTE + ".",
    HeroTicketRules.SEPARATE + ".",
) + v.status.toSortedMap().map { (idx, s) -> "$idx: $s" }

/** The row from plain state ([v]) and its callbacks. COMPACT (15 Oct): name (tap: the explanation), state, P&L, tickets, exit choices. */
@Composable
internal fun HeroTicketRowContent(v: HeroTicketArm.View, onArm: (Boolean) -> Unit, onTarget: (HeroTicketRules.Target) -> Unit,
                                  onStart: (HeroTicketRules.TrailStart) -> Unit = {}) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // COMPACT (15 Oct, Boss): its tickets and its exit / trail-start choices are in the name's dialog with its rules.
            ExplainedName(HeroTicketRules.LABEL, heroTicketExplanation(v), extra = { HeroTicketControls(v, onTarget, onStart) })
            Text(if (v.armed) "ON · PAPER ONLY" else "OFF", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            Text(keepNumbersWhole(heroTicketPnlLine(v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch ${HeroTicketRules.LABEL}" },
            checked = v.armed,
            onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** The ticket's own tickets today and its exit and trail-start choices (its name's dialog; a choice applies to new tickets). */
@Composable
private fun HeroTicketControls(v: HeroTicketArm.View, onTarget: (HeroTicketRules.Target) -> Unit, onStart: (HeroTicketRules.TrailStart) -> Unit) {
    val p = LocalPalette.current
    Column {
        v.today.forEach { o ->
            val line = if (o.open) "${o.symbol} · ${o.qty} @ %.2f".format(Locale.ENGLISH, o.entry) +
                (v.marks[o.symbol]?.let { m -> " · now %.2f".format(Locale.ENGLISH, m) } ?: "") + " · paper"
                else "${o.symbol} · ${o.qty} @ %.2f → %.2f (%s) · %s".format(Locale.ENGLISH, o.entry, o.exit ?: 0.0, o.why ?: "", rs(o.net ?: 0.0))
            Text(keepNumbersWhole(line), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
        // Its exit for new tickets, kept: the trail alone (the default) or a 2× / 3× target beside it.
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Exit:", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            HeroTicketRules.Target.entries.forEach { t ->
                val on = v.target == t
                val word = when (t) { HeroTicketRules.Target.TRAIL_ONLY -> "Trail only"; HeroTicketRules.Target.X2 -> "2×"; HeroTicketRules.Target.X3 -> "3×" }
                Text(word, style = Type.label.copy(color = if (on) p.card else p.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .semantics { contentDescription = "${HeroTicketRules.LABEL} exit: ${t.words}"; selected = on }
                        .background(if (on) p.verdigris else p.ink.copy(alpha = 0.06f), RoundedCornerShape(50))
                        .clickable(role = Role.RadioButton) { if (!on) onTarget(t) }
                        .padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
        // Where the 25% trail starts for new tickets: at the fill (R50, also a 25% stop), after 2× or after 3×.
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Trail starts:", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            HeroTicketRules.TrailStart.entries.forEach { s ->
                val on = v.trailStart == s
                val word = when (s) { HeroTicketRules.TrailStart.AT_FILL -> "At fill"; HeroTicketRules.TrailStart.AFTER_2X -> "After 2×"; HeroTicketRules.TrailStart.AFTER_3X -> "After 3×" }
                Text(word, style = Type.label.copy(color = if (on) p.card else p.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .semantics { contentDescription = "${HeroTicketRules.LABEL} trail start: ${s.words}"; selected = on }
                        .background(if (on) p.verdigris else p.ink.copy(alpha = 0.06f), RoundedCornerShape(50))
                        .clickable(role = Role.RadioButton) { if (!on) onStart(s) }
                        .padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
    }
}
