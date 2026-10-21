package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import com.optionslab.engine.orb.BreadthDonchianArm
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
 * R52 Open outside value, Solo 2.0 and (16 Oct) R72 Breadth Donchian - whose dialog also carries its risk line and Boss's
 * comparison with R51 Donchian 20 on the same days. COMPACT (Boss, 15 Oct): each row shows only its name, one short status line (on / off,
 * paper, today's state) and its key numbers (record, P&L, forward-test progress), with its Off switch. A tap on the NAME opens
 * a small dialog with the explanation - its settings, research id and result, its forward-test bar and the "paper only"
 * label - and one OK button. Reads [PassedArmsHost.view] (memory only); every change goes through the host off the main thread.
 */
@Composable
internal fun PassedArmRows() {
    val v by PassedArmsHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { PassedArmsHost.refresh() } } }
    PassedArmsHost.SOURCES.forEach { s ->
        PassedArmRowContent(s, v, onRecordOnly = { scope.launch(Dispatchers.IO) { runCatching { PassedArmsHost.setMode(s, PassedArmsHost.Mode.RECORD) } } }) { on ->
            scope.launch(Dispatchers.IO) { runCatching { PassedArmsHost.setArmed(s, on) } }
        }
    }
}

private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

internal fun passedRules(source: String): String = when (source) {
    DonchianArm.SOURCE -> DonchianArm.RULES
    BreadthDonchianArm.SOURCE -> BreadthDonchianArm.RULES
    OovArm.SOURCE -> OovArm.RULES + ". " + OovArm.STOP_ADDED
    else -> Solo2.RULES + ". " + Solo2.RETRAIN_TODO
}

internal fun passedRecord(source: String): String = when (source) {
    DonchianArm.SOURCE -> DonchianArm.RECORD
    BreadthDonchianArm.SOURCE -> BreadthDonchianArm.RECORD
    OovArm.SOURCE -> OovArm.RECORD
    else -> Solo2.RECORD
}

/** 19 Oct: a passed arm set to record only, on its row (Boss: "Yes, keep only breadth donchain"). */
internal val RECORD_ONLY_ROW = com.optionslab.ira.PassedArmsTalk.RECORD_ONLY_WORDS.replaceFirstChar { it.uppercase() }

/** The short status line: on / record only / off, paper, today's state. */
internal fun passedStatusLine(source: String, v: PassedArmsHost.View): String {
    val t = v.todayOf(source)
    val state = when {
        v.paused[source] != null && !v.recording(source) -> "switched itself off (forward test)"
        // 19 Oct: what it "holds" in record only is a would-be trade, said so.
        t.any { it.open } -> "holding ${t.count { it.open }}" + t.count { it.open && it.shadow != null }.let { n -> if (n > 0) " ($n would-be)" else "" }
        t.isNotEmpty() -> "${t.size} trade${if (t.size == 1) "" else "s"} today, ${rs(v.todayPnl(source))}"
        else -> "no trade today"
    }
    // 16 Oct (R73 review): breaks R51's Supertrend filter skipped today because it could not judge them, said on the row.
    val unjudged = if (source == DonchianArm.SOURCE && v.stOn && v.stUnjudged > 0) " · ${v.stUnjudged} not judged by Supertrend" else ""
    return (if (v.on(source)) "On · paper" else if (v.recording(source)) RECORD_ONLY_ROW else "Off") + " · " + state + unjudged
}

/** The key numbers: its own record, the index reported apart, the forward test's progress. */
internal fun passedNumbersLine(source: String, v: PassedArmsHost.View): String {
    val c = v.closedOf(source)
    val ap = c.filter { it.index == PassedArmsHost.apart(source) }
    val n = if (source == Solo2.SOURCE) "${minOf(c.size, 60)}/60" else "${minOf(c.size, PassedArms.ForwardBar.TRADES)}/${PassedArms.ForwardBar.TRADES}"
    // 19 Oct: the would-be trades (record only, the twin rule, the cap: never bought) counted apart within that record.
    val wb = v.wouldBeOf(source)
    val apartWb = if (wb.isEmpty()) "" else " (${wb.size} would-be, ${rs(wb.sumOf { it.net ?: 0.0 })})"
    return "${c.size} closed$apartWb, ${rs(c.sumOf { it.net ?: 0.0 })} · ${PassedArmsHost.apart(source)} ${rs(ap.sumOf { it.net ?: 0.0 })} · forward test $n"
}

/** What the name's dialog says: the label, settings, research id and result, new trades' exits, the forward-test bar, the signal source. */
internal fun passedExplanation(source: String, v: PassedArmsHost.View): List<String> = listOfNotNull(
    PassedArms.PASSED,
    "Research: ${PassedArmsHost.research(source)}. ${passedRecord(source)}.",
    // 16 Oct (R73 review): R51's rules say its Supertrend filter when it is on, with the filter's own research record.
    (if (source == DonchianArm.SOURCE) DonchianArm.rules(v.stOn) else passedRules(source)) + ".",
    if (source == DonchianArm.SOURCE && v.stOn) DonchianArm.ST_RECORD + "." else null,
    // 16 Oct (R72): its risk line, and the point of running it beside Donchian - the two on the same days.
    if (source == BreadthDonchianArm.SOURCE) "Risk: ${BreadthDonchianArm.RISK}." else null,
    if (source == BreadthDonchianArm.SOURCE) v.comparison() + "." else null,
    // 16 Oct: the premium exits NEW trades enter with (the 30-60-100 lock and R66's +5% once +25%, Boss's choices; paper only).
    com.optionslab.ira.PassedArmsTalk.exitsWords(v.lockOn, v.l25On) + ".",
    PassedArms.SIGNAL_INDEX + ".",
    (if (source == Solo2.SOURCE) Solo2.bar() else PassedArms.ForwardBar.BAR) + ".",
    v.forwardLine(source) + ".",
    v.paused[source]?.let { "It switched itself off: $it. Switching it back on is your choice." },
    // 19 Oct: what record only means, while it is.
    if (v.recording(source)) "$RECORD_ONLY_ROW: its signals are still decided on the index; each is kept as a would-be trade, closed by its own " +
        "exits and counted apart. Nothing is bought, never live." else null,
) + (if (source == Solo2.SOURCE) soloDecisions(v) else emptyList())

/**
 * Solo 2.0's own words for today's decisions (15 Oct, Boss: Solo explains itself): each candidate it took or skipped, with
 * the inputs that raised and lowered its score most, then the concepts it does NOT use, labelled as context. Explanation
 * only: the words are made from the numbers it already scored and never change what it trades.
 */
internal fun soloDecisions(v: PassedArmsHost.View): List<String> =
    (if (v.scores.isEmpty()) listOf("Today's decisions: none yet (it decides at each setup's entry minute).")
    else listOf("Today's decisions (its score against its bar; the words say what moved the score most):") + v.scores.map { "• $it." }) + (Solo2.CONTEXT_NOTE + ".")

/**
 * One arm's compact row from plain state ([v]) and its switch callback. 19 Oct: on / record only / off - the switch is on or off,
 * the "Record only" choice beside it ([onRecordOnly]) keeps its breaks as would-be trades with nothing bought; tapped again, off.
 */
@Composable
internal fun PassedArmRowContent(source: String, v: PassedArmsHost.View, onRecordOnly: () -> Unit = {}, onArm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val label = PassedArmsHost.label(source)
    val on = v.on(source)
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // ONE status line (15 Oct, Boss); its record and forward-test numbers are in the name's dialog with its explanation.
            ExplainedName(label, listOf(passedNumbersLine(source, v) + ".") + passedExplanation(source, v))
            Text(keepNumbersWhole(passedStatusLine(source, v)), style = Type.bodySmall.copy(color = if (on) p.verdigris else p.inkFaint, fontSize = 12.sp))
        }
        // 19 Oct (Boss: "Yes, keep only breadth donchain"): the third state. Picked: shown ticked; tapped again: off. The switch on leaves it.
        val rec = v.recording(source)
        Text(if (rec) "✓ Record only" else "Record only",
            modifier = Modifier.semantics { contentDescription = "Record only, nothing bought: $label" }
                .toggleable(value = rec, role = Role.Checkbox) { x -> if (x) onRecordOnly() else onArm(false) }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp),
            style = Type.bodySmall.copy(color = if (rec) p.verdigris else p.inkFaint, fontSize = 12.sp, fontWeight = if (rec) FontWeight.SemiBold else FontWeight.Normal))
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch $label" },
            checked = on,
            onCheckedChange = { x -> onArm(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/**
 * The shadow Watchlist on Home's Strategies card (15 Oct; [WatchlistHost]), compact: its name (a tap: the explanation - "shadow,
 * not traded; information only, not a trade signal", the written selection rule, each rule with its research and C4 marked
 * NON-DEFAULT, what was left out - and OK), one status line, one numbers line per rule, and its switch.
 */
@Composable
internal fun WatchlistCard() {
    val v by WatchlistHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { WatchlistHost.on() } } }
    WatchlistCardContent(v) { on -> scope.launch(Dispatchers.IO) { runCatching { WatchlistHost.setOn(on) } } }
}

/** One record in words: rule, day and time, contract, entry and exit, the would-be P&L (Jarvis and the diagnostics). */
internal fun watchRecordLine(r: WatchlistHost.Rec): String {
    val s = r.s
    val head = "${s.rule.label} · ${s.entry.toLocalDate()} ${s.entry.toLocalTime()} · ${s.underlying} ${r.expiry ?: "?"} ${s.strike} ${s.right}"
    val p = r.p
    return when {
        p != null -> head + " · in %.2f @ %s, out %.2f @ %s (%s) · would-be %s".format(Locale.ENGLISH, p.entryFill, p.entryAt.toLocalTime(), p.exitFill,
            p.exitAt.toLocalTime(), p.why, rs(p.net))
        r.state == WatchlistHost.NOFILL -> "$head · would not have filled"
        r.state == WatchlistHost.UNPRICED -> "$head · not priced" + (r.note?.let { " ($it)" } ?: "")
        else -> "$head · open (shadow)"
    }
}

internal fun watchExplanation(v: WatchlistHost.View): List<String> = listOf(Watchlist.NOTE.replaceFirstChar { it.uppercase() } + ".", Watchlist.SELECTION) +
    Watchlist.Rule.entries.map { r -> "${r.label} (${r.research}, ${r.tag}): ${r.words}. ${r.record}." } + listOf(Watchlist.LEFT_OUT) +
    v.recs.takeLast(5).reversed().map { "Latest: " + watchRecordLine(it) }

/** One rule's numbers, short: trades, win rate, net, p (from 40). */
internal fun watchNumbers(c: Watchlist.Card): String = "${c.rule.label}${if (c.rule.nonDefault) " (non-default)" else ""}: ${c.trades}" +
    (c.winRate?.let { ", %.0f%% won".format(Locale.ENGLISH, it * 100) } ?: "") + ", ${rs(c.net)}" + (c.p?.let { ", p %.3f".format(Locale.ENGLISH, it) } ?: "")

@Composable
internal fun WatchlistCardContent(v: WatchlistHost.View, onSwitch: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // ONE status line (15 Oct, Boss); each rule's numbers are in the name's dialog.
            ExplainedName("Watchlist (shadow)", v.cards.map { watchNumbers(it) } + watchExplanation(v))
            Text(if (v.on) "On · shadow, not traded · ${v.recs.count { !it.final }} open" else "Off",
                style = Type.bodySmall.copy(color = if (v.on) p.verdigris else p.inkFaint, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Shadow only: switch the Watchlist" },
            checked = v.on,
            onCheckedChange = { x -> onSwitch(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
