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
import com.optionslab.app.data.Ft3Arm
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.Ft3Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "FT3 near-ATM morning"'s row on Home's Strategies card (21 Oct, Boss's choice; [Ft3Arm]): labelled "Boss's choice - a forward
 * test, did not pass research (R98e: SPA p 0.96)", its rules, signal source, replayed record and how it is judged, how it goes
 * live (the standard way), today's tickets with the running P&L and its switch - on paper; one tap switches it off. Reads
 * [Ft3Arm.view] (memory only); the book is read and every change goes through the arm off the main thread.
 */
@Composable
internal fun Ft3Row() {
    val v by Ft3Arm.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { Ft3Arm.refresh() } } }
    Ft3RowContent(v, onArm = { on -> scope.launch(Dispatchers.IO) { runCatching { Ft3Arm.setArmed(on) } } })
}

private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

/** How its live path works, in a line (the standard one every paper arm has). */
internal const val FT3_LIVE_WORDS = "Live: the standard way - only in Live (your PIN) with \"AI trades go live\" on, its own Trade live " +
    "switch on and its Shadow off (your PIN); until then Shadow only records it. Live buys at most your lot cap (Bot settings; " +
    "all its lots with the cap off), so its record is read at its own size, and rests a -80% disaster stop at Zerodha."

/** The running P&L line: today's tickets (sold ones after charges, held ones at their last price) and the forward test so far. */
internal fun ft3PnlLine(v: Ft3Arm.View): String =
    "Today: ${if (v.today.isEmpty()) "no ticket" else "${v.today.size} ticket${if (v.today.size == 1) "" else "s"}, ${rs(v.todayPnl)}"} · " +
        "forward test: ${v.nets.size} closed, ${v.won} won, ${rs(v.nets.sum())} after charges"

/** What its name opens: Boss's label (first), its rules, signal source, replayed record, how it is judged, its live path, today. */
internal fun ft3Explanation(v: Ft3Arm.View): List<String> = listOf(
    Ft3Rules.RECORD + ".",
    Ft3Rules.RULES + ".",
    Ft3Rules.SIGNAL_SOURCE + ".",
    Ft3Rules.MEASURED + ".",
    Ft3Rules.JUDGED + ".",
    FT3_LIVE_WORDS,
    Ft3Rules.SEPARATE + ".",
) + v.status.toSortedMap().map { (idx, s) -> "$idx: $s" }

/** The row from plain state ([v]) and its callback. COMPACT: name (tap: the explanation and today's tickets), state, P&L. */
@Composable
internal fun Ft3RowContent(v: Ft3Arm.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(Ft3Rules.LABEL, ft3Explanation(v), extra = { Ft3Tickets(v) })
            Text(if (v.armed) "ON · PAPER · LIVE THE STANDARD WAY" else "OFF", style = Type.label.copy(color = if (v.armed) p.verdigris else p.inkFaint,
                fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            if (v.open.isNotEmpty()) RowBadge("HOLDING ${v.open.size} · PAPER", p.verdigris, Modifier.padding(top = 2.dp))
            Text(keepNumbersWhole(ft3PnlLine(v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "On paper: switch ${Ft3Rules.LABEL}" },
            checked = v.armed,
            onCheckedChange = { on -> onArm(on) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** Today's tickets (its name's dialog). */
@Composable
private fun Ft3Tickets(v: Ft3Arm.View) {
    val p = LocalPalette.current
    Column {
        v.today.forEach { o ->
            val line = if (o.open) "${o.symbol} · ${o.qty} @ %.2f".format(Locale.ENGLISH, o.entry) +
                (v.marks[o.symbol]?.let { m -> " · now %.2f".format(Locale.ENGLISH, m) } ?: "") + " · ${o.mark} mark · paper"
                else "${o.symbol} · ${o.qty} @ %.2f → %.2f (%s) · %s".format(Locale.ENGLISH, o.entry, o.exit ?: 0.0, o.why ?: "", rs(o.net ?: 0.0))
            Text(keepNumbersWhole(line), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
        }
    }
}
