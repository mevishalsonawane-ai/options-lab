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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.TrendWatchHost
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.trend.TrendTurn
import com.optionslab.engine.trend.TrendWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Trend watch on Home's Strategies card (16 Oct, Boss: "if the trend changes, sell the orders"; [TrendWatchHost]), compact:
 * its name (a tap: what it does, the research, what is watched now, today's turns, and only its own switches - live positions,
 * alerts and each source's paper exit - then OK), one status line, and its switch.
 */
@Composable
internal fun TrendWatchRow() {
    val v by TrendWatchHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { TrendWatchHost.switches() } } }
    fun io(f: () -> Unit) { scope.launch(Dispatchers.IO) { runCatching { f() } } }
    TrendWatchRowContent(v, onSwitch = { on -> io { TrendWatchHost.setOn(on) } }, onLive = { on -> io { TrendWatchHost.setLive(on) } },
        onAlerts = { on -> io { TrendWatchHost.setAlerts(on) } }, onOthers = { on -> io { TrendWatchHost.setOthers(on) } }, onAct = { s, on -> io { TrendWatchHost.setAct(s, on) } })
}

/** The dialog's paragraphs: what it does and what research found, then what is watched and today's turns. */
internal fun trendExplanation(v: TrendWatchHost.View): List<String> = listOf(
    "Every open position and pending order, paper and (with its switch) live, is judged on its underlying's completed bars - " +
        "the index, the stock or the MCX future, never the option's own price - about once a minute, in Jarvis's background round (NSE hours, 09:20-15:40: MCX evening positions are not watched).",
    "Research R63 tested four turns (${TrendTurn.Rule.entries.joinToString("; ") { "${it.key} ${it.label}" }}) on six strategies " +
        "from 20 Nov 2024. ${TrendWatch.RECORD}.",
    "So only the Hero ₹1,000 ticket's paper legs are sold on a turn (T3), through the arm's own exit; each sale is in the change " +
        "log, one tap undoes it. By default only the Hero ticket is watched (your choice, 16 Oct). With \"Other strategies and " +
        "orders too\" on, everything else gets a note (\"would sell at …\"), with ${TrendWatch.SHADOW_RULE.key} " +
        "(${TrendWatch.SHADOW_RULE.label}). Pending orders: \"would cancel\", never cancelled. Live: notes only, never an order.",
) + (if (v.watched.isEmpty()) listOf("Nothing is watched right now.")
    else v.watched.take(12).map { "Watched: ${it.source.label} ${it.symbol}${if (it.live) " (live)" else ""} · ${it.rule} · ${it.state}" }) +
    v.recent.takeLast(6).reversed().map { "Today: " + it.line }

@Composable
internal fun TrendWatchRowContent(v: TrendWatchHost.View, onSwitch: (Boolean) -> Unit, onLive: (Boolean) -> Unit, onAlerts: (Boolean) -> Unit,
                                  onAct: (TrendWatch.Source, Boolean) -> Unit, onOthers: (Boolean) -> Unit = {}) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName(TrendWatch.LABEL, trendExplanation(v), extra = {
                Column {
                    // 16 Oct (Boss): the Hero ticket alone by default; the notes for everything else are one switch away.
                    SmallSwitch("Other strategies and orders too (notes only)", v.sw.others, onOthers)
                    SmallSwitch("Live positions too (notes and alerts only)", v.sw.live, onLive, enabled = v.sw.others)
                    SmallSwitch("A quiet alert on each turn", v.sw.alerts, onAlerts)
                    Text("Sell on paper when the trend turns:", style = Type.bodySmall.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                    for (s in TrendWatch.Source.SWITCHABLE) {
                        val can = TrendWatch.canAct(s)
                        SmallSwitch(s.label + if (can) " (passed research)" else " (failed research: notes only)", can && v.sw.acts(s), { on -> onAct(s, on) }, enabled = can)
                    }
                }
            })
            Text(v.status, style = Type.bodySmall.copy(color = if (v.sw.on) p.verdigris else p.inkFaint, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Switch the Trend watch" },
            checked = v.sw.on,
            onCheckedChange = { x -> onSwitch(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** One labelled switch inside the dialog (48 dp, described by its label). */
@Composable
private fun SmallSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.bodySmall.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}
