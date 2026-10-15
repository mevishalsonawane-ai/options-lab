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
import com.optionslab.app.data.PassedArmsHost
import com.optionslab.app.data.WatchlistHost
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.Watchlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The paper arms that passed research on Home's Strategies card (15 Oct, Boss: "Both"; [PassedArmsHost]): R51 Donchian 20,
 * R52 Open outside value and Solo 2.0, one row each - labelled "Passed research (R51/R52/R57) — paper only", its settings
 * and signal source, today's state and trades with the running P&L, its record (the index the research said to report
 * apart, apart), its forward-test bar and its Off switch (one tap; the change log's Undo does the same). Reads
 * [PassedArmsHost.view] (memory only); the book is read and every change goes through the host off the main thread.
 */
@Composable
internal fun PassedArmRows() {
    val v by PassedArmsHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { PassedArmsHost.refresh() } } }
    PassedArmsHost.SOURCES.forEach { s ->
        PassedArmRowContent(s, v) { on -> scope.launch(Dispatchers.IO) { runCatching { PassedArmsHost.setArmed(s, on) } } }
    }
}

private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

internal fun passedRules(source: String): String = when (source) {
    DonchianArm.SOURCE -> DonchianArm.RULES
    OovArm.SOURCE -> OovArm.RULES
    else -> Solo2.RULES
}

internal fun passedRecord(source: String): String = when (source) {
    DonchianArm.SOURCE -> DonchianArm.RECORD
    OovArm.SOURCE -> OovArm.RECORD
    else -> Solo2.RECORD
}

/** Its own record: closed trades, net, and the index reported apart. */
internal fun passedRecordLine(source: String, v: PassedArmsHost.View): String {
    val c = v.closedOf(source)
    val ap = c.filter { it.index == PassedArmsHost.apart(source) }
    val t = v.todayOf(source)
    return "Today: ${if (t.isEmpty()) "no trade" else "${t.size} trade${if (t.size == 1) "" else "s"}, ${rs(v.todayPnl(source))}"} · " +
        "its own record: ${c.size} closed, ${rs(c.sumOf { it.net ?: 0.0 })} after charges · ${PassedArmsHost.apart(source)} apart: ${ap.size}, ${rs(ap.sumOf { it.net ?: 0.0 })}"
}

/** One arm's row from plain state ([v]) and its switch callback. */
@Composable
internal fun PassedArmRowContent(source: String, v: PassedArmsHost.View, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val label = PassedArmsHost.label(source)
    val on = v.on(source)
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(PassedArms.PASSED, style = Type.label.copy(color = p.verdigris, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp).background(p.verdigris.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(keepNumbersWhole(passedRecord(source)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            Text(keepNumbersWhole(passedRules(source)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            if (source == OovArm.SOURCE) Text(keepNumbersWhole(OovArm.STOP_ADDED), style = Type.bodySmall.copy(color = p.amber, fontSize = 11.sp))
            Text(keepNumbersWhole(PassedArms.SIGNAL_INDEX), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            Text(if (on) "ON · PAPER ONLY" else "OFF", style = Type.label.copy(color = if (on) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            v.paused[source]?.let { Text(keepNumbersWhole("Switched itself off on its forward-test bar: $it. Switching it back on is your choice."),
                style = Type.bodySmall.copy(color = p.amber, fontSize = 12.sp)) }
            Text(keepNumbersWhole(passedRecordLine(source, v)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            v.todayOf(source).forEach { o -> Text(keepNumbersWhole(PassedArmsHost.tradeLine(o, v.marks)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)) }
            if (source == Solo2.SOURCE) {
                v.scores.forEach { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp)) }
                Text(if (v.modelLoaded) "Model: R57's A2, loaded" else "Model: not loaded yet", style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
                Text(keepNumbersWhole(Solo2.RETRAIN_TODO + "."), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            }
            Text(keepNumbersWhole(v.forwardLine(source) + "."), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            Text(keepNumbersWhole((if (source == Solo2.SOURCE) Solo2.bar() else PassedArms.ForwardBar.BAR) + "."), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            if (on) v.status.filterKeys { it.startsWith("$source|") }.toSortedMap().forEach { (k, s) ->
                Text(keepNumbersWhole("${k.substringAfter('|')}: $s"), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch $label" },
            checked = on,
            onCheckedChange = { x -> onArm(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/**
 * The shadow Watchlist's card on Home's Strategies card (15 Oct; [WatchlistHost]): "shadow, not traded; information only, not
 * a trade signal", the written selection rule, each rule (C4 marked NON-DEFAULT: an option-chain signal) with its research
 * and its scorecard, the latest records (contract, entry, exit, would-be P&L), and its switch.
 */
@Composable
internal fun WatchlistCard() {
    val v by WatchlistHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { WatchlistHost.on() } } }
    WatchlistCardContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { WatchlistHost.setOn(on) } } }
}

/** One record in words: rule, day and time, contract, entry and exit, the would-be P&L. */
internal fun watchRecordLine(r: WatchlistHost.Rec): String {
    val s = r.s
    val c = "${s.underlying} ${r.expiry ?: "?"} ${s.strike} ${s.right}"
    val head = "${s.rule.label} · ${s.entry.toLocalDate()} ${s.entry.toLocalTime()} · $c"
    val p = r.p
    return when {
        p != null -> head + " · in %.2f @ %s, out %.2f @ %s (%s) · would-be %s".format(Locale.ENGLISH, p.entryFill, p.entryAt.toLocalTime(), p.exitFill,
            p.exitAt.toLocalTime(), p.why, rs(p.net))
        r.state == WatchlistHost.NOFILL -> "$head · would not have filled"
        r.state == WatchlistHost.UNPRICED -> "$head · not priced" + (r.note?.let { " ($it)" } ?: "")
        else -> "$head · open (shadow)"
    }
}

@Composable
internal fun WatchlistCardContent(v: WatchlistHost.View, onSwitch: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Watchlist (shadow)", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(Watchlist.NOTE, style = Type.label.copy(color = p.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(keepNumbersWhole(Watchlist.SELECTION), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            Watchlist.Rule.entries.forEach { r ->
                Text(r.label, style = Type.bodySmall.copy(color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
                Text(r.tag, style = Type.label.copy(color = if (r.nonDefault) p.amber else p.inkFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Text(keepNumbersWhole(r.words + ". " + r.research + ": " + r.record + "."), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp))
                v.cards.firstOrNull { it.rule == r }?.let { Text(keepNumbersWhole(Watchlist.line(it)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)) }
            }
            val recent = v.recs.takeLast(5).reversed()
            if (recent.isNotEmpty()) Text("Latest", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            recent.forEach { Text(keepNumbersWhole(watchRecordLine(it)), style = Type.bodySmall.copy(color = p.ink, fontSize = 11.sp)) }
            Text(keepNumbersWhole(Watchlist.LEFT_OUT), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
            Text(if (v.on) "ON · SHADOW ONLY" else "OFF", style = Type.label.copy(color = if (v.on) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Shadow only: switch the Watchlist" },
            checked = v.on,
            onCheckedChange = { x -> onSwitch(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
