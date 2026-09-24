package com.optionslab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.AppSettings
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Arm
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.InkCurve
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.RollingFigure
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Sparkline
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.num
import com.optionslab.app.ui.pct
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.rs1
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.BacktestReport
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Summary
import com.optionslab.engine.fmtG

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParamTokens(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Text(label.uppercase(), style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (text, sel) -> Token(text, sel) { onPick(i) } }
    }
}

@Composable
fun StrategyParams(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, showCapital: Boolean = true) {
    val otms = listOf(0.005, 0.0075, 0.01, 0.0125)
    ParamTokens("Strike distance", otms.map { pct(it) to (it == s.otmPct) }) { i -> update { it.copy(otmPct = otms[i]) } }
    val entries = listOf("10:00", "10:30", "11:00", "12:00", "13:00")
    ParamTokens("Entry (IST)", entries.map { it to (it == s.entry) }) { i -> update { it.copy(entry = entries[i]) } }
    val regimes = listOf("roll", "quoted", "stress")
    ParamTokens("Spread regime", regimes.map { it to (it == s.regime) }) { i -> update { it.copy(regime = regimes[i]) } }
    val lots = listOf("65", "50", "75", "25", "dated")
    ParamTokens("Lot", lots.map { it to (if (it == "dated") s.datedLot else !s.datedLot && it.toInt() == s.pinnedLot) }) { i ->
        update { if (lots[i] == "dated") it.copy(datedLot = true) else it.copy(datedLot = false, pinnedLot = lots[i].toInt()) }
    }
    val wings = listOf<Double?>(null, 0.005, 0.0075, 0.01, 0.025)
    ParamTokens("Structure", wings.map { (if (it == null) "naked" else "wing +${pct(it)}") to (it == s.wingPct) }) { i -> update { it.copy(wingPct = wings[i]) } }
    val holdouts = listOf(0, 20, 30, 40)
    ParamTokens("Sealed holdout", holdouts.map { (if (it == 0) "none" else "last $it") to (it == s.holdout) }) { i -> update { it.copy(holdout = holdouts[i]) } }
    if (showCapital) {
        val caps = listOf(200_000.0, 400_000.0, 1_000_000.0)
        ParamTokens("Capital", caps.map { rs(it) to (it == s.capital) }) { i -> update { it.copy(capital = caps[i]) } }
        val survive = listOf(-0.03, -0.06, -0.13)
        ParamTokens("Survive a day of", survive.map { pct(it, 0) to (it == s.survive) }) { i -> update { it.copy(survive = survive[i]) } }
    }
}

@Composable
fun TrialsScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val bt by model.backtest.collectAsState()
    val arms by model.arms.collectAsState()
    var showParams by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(model::exportBacktest) }

    Page {
        item {
            LedgerCard(title = "The Trial") {
                val structure = if (s.wingPct == null) "naked put" else "put spread, wing ${pct(s.wingPct!!)} wider"
                Text("${pct(s.otmPct)} OTM at ${s.entry} · lot ${if (s.datedLot) "dated" else s.pinnedLot} · ${s.regime} spread · $structure",
                    style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrassButton("Run the trial", Modifier.weight(1f), busy = bt is Load.Busy) { model.runBacktest(force = true) }
                    BrassButton(if (showParams) "Close" else "Adjust", tone = p.inkSoft) { showParams = !showParams }
                }
                AnimatedVisibility(showParams) {
                    Column(Modifier.animateContentSize()) { StrategyParams(s, model::update) }
                }
            }
        }
        when (val b = bt) {
            is Load.Busy -> item { LedgerCard { FullSpinner(b.label, b.progress) } }
            is Load.Failed -> item { LedgerCard { Note(b.why) } }
            is Load.Done -> report(b.value, s.reduceMotion) { export.launch("expiry_put_ledger.csv") }
            Load.Idle -> item { LedgerCard { Note("Run the trial to replay every cached expiry session through the strategy.") } }
        }
        item { ArmsCard(model, arms, s) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.report(r: BacktestReport, calm: Boolean, onExport: () -> Unit) {
    item {
        val p = LocalPalette.current
        val c = r.combined
        LedgerCard(title = "Verdict of the Record") {
            if (c == null) Note("no sessions produced a trade") else {
                Text("sessions: ${r.days.size}  (${r.days.first()} .. ${r.days.last()})", style = Type.figure.copy(fontSize = 12.sp, color = p.inkSoft))
                if (r.holdoutDays.isNotEmpty()) Text("SEALED HOLDOUT: last ${r.holdoutDays.size} sessions, from ${r.holdoutDays.first()}",
                    style = Type.figure.copy(fontSize = 12.sp, color = p.brass))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingFigure(100 * c.winRate, { "%.2f%%".format(it) }, Type.figureHuge.copy(color = if (c.winRate > 0.9) p.verdigris else p.amber), calm = calm)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("win rate", style = Type.italic.copy(color = p.inkSoft))
                        Text("${(c.winRate * c.n).toInt()}/${c.n}", style = Type.figure.copy(color = p.inkSoft))
                    }
                }
                Spacer(Modifier.height(6.dp))
                var acc = 0.0
                val curve = r.all.map { acc += it.netPnl; acc }
                val losses = r.all.mapIndexedNotNull { i, t -> if (!t.won) i else null }
                val sealedDays = r.holdoutDays.toSet()
                val seal = r.all.indexOfFirst { it.session in sealedDays }.takeIf { it >= 0 }
                InkCurve(curve, losses, seal, calm = calm)
                Row(Modifier.fillMaxWidth()) {
                    Text("cumulative net, Rs", style = Type.italic.copy(color = p.inkFaint, fontSize = 13.sp), modifier = Modifier.weight(1f))
                    if (seal != null) Text("┆ sealed", style = Type.italic.copy(color = p.brass, fontSize = 13.sp))
                }
            }
        }
    }
    item {
        LedgerCard(title = "Train · Holdout · Combined") {
            listOfNotNull(r.trainSummary, r.holdoutSummary, r.combined).forEachIndexed { i, sm ->
                if (i > 0) Rule(Modifier.padding(vertical = 6.dp))
                SummaryBlock(sm)
            }
            r.drift?.let { d ->
                val p = LocalPalette.current
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("holdout minus train: ${rs1(d)}/trade", style = Type.figure.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
                    Stamp(if (d > -50) "held up" else "degraded", if (d > -50) p.verdigris else p.oxblood)
                }
            }
        }
    }
    if (r.losers.isNotEmpty()) item {
        val p = LocalPalette.current
        LedgerCard(title = "Losing Sessions (${r.losers.size} of ${r.all.size})", accent = p.oxblood) {
            val sealed = r.holdoutDays.toSet()
            r.losers.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.session}  ${if (t.session in sealed) "holdout" else "train"}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                        Text("K=${fmtG(t.strike)}  settle=${num(t.settlement)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
                    }
                    Text(rs(t.netPnl, true), style = Type.figure.copy(color = p.oxblood))
                }
            }
        }
    }
    item {
        val p = LocalPalette.current
        LedgerCard(title = "Sizing") {
            val plan = r.plan
            if (plan == null) Note(r.planError ?: "no plan") else {
                Text("on ${rs(plan.totalCapital)} surviving a ${pct(plan.survivesMovePct, 0)} day:", style = Type.italic.copy(color = p.inkSoft))
                LedgerLine("Lots", "${plan.lots} at ${rs(plan.capitalPerLot)} each")
                LedgerLine("Expected", "${rs(plan.expectedAnnualRs)}/yr = ${pct(plan.expectedAnnualPct)}", p.verdigris)
                LedgerLine("Worst case", "${rs(plan.worstCaseRs)} (${pct(plan.worstCaseRs / plan.totalCapital, 1)})", p.oxblood)
                Stamp("Unbounded beyond this move", p.oxblood, angle = -3f)
            }
        }
    }
    if (r.skipped.isNotEmpty()) item {
        LedgerCard(title = "Skipped (${r.skipped.size})") {
            r.skipped.take(20).forEach { Note("${it.day}: ${it.why}") }
        }
    }
    item { BrassButton("Export the ledger (CSV)", Modifier.fillMaxWidth(), onClick = onExport) }
}

@Composable
private fun SummaryBlock(sm: Summary) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(sm.label.uppercase(), style = Type.label.copy(color = p.brass), modifier = Modifier.weight(1f))
        Text("n=${sm.n}  win ${pct(sm.winRate)}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
    }
    LedgerLine("Mean / median", "${rs1(sm.mean)} / ${rs1(sm.median)}")
    LedgerLine("Worst", rs1(sm.worst), if (sm.worst < 0) p.oxblood else p.verdigris)
    LedgerLine("Total", rs(sm.total, true))
    LedgerLine("Credit / cost", "${rs(sm.creditPerLot)}/lot · ${num(sm.medianCost)} (${pct(sm.costShare, 1)})")
}

@Composable
private fun ArmsCard(model: AppModel, arms: Load<List<com.optionslab.app.ui.ArmResult>>, s: AppSettings) {
    val p = LocalPalette.current
    LedgerCard(title = "The Arms") {
        Note("Every variant this project measured, replayed side by side over one pass of the record. Tap an arm to adopt its settings.")
        Spacer(Modifier.height(8.dp))
        BrassButton("Compare all arms", Modifier.fillMaxWidth(), busy = arms is Load.Busy) {
            model.runArms(Arm("Your settings", "the trial above", s.params()))
        }
        when (arms) {
            is Load.Busy -> FullSpinner(arms.label, arms.progress)
            is Load.Failed -> Note(arms.why)
            is Load.Done -> arms.value.forEach { a ->
                Rule(Modifier.padding(vertical = 8.dp))
                Column(Modifier.fillMaxWidth().clickable { model.update { st -> adopt(st, a.arm.params) }; model.say("Adopted \"${a.arm.name}\". Run the trial to see it in full.") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.arm.name, style = Type.title.copy(color = p.ink, fontSize = 14.sp))
                            Text(a.arm.note, style = Type.italic.copy(color = p.inkSoft, fontSize = 13.sp))
                        }
                        Sparkline(a.curve, p.brass, Modifier.width(80.dp).height(28.dp))
                    }
                    val sm = a.summary
                    if (sm == null) Note("no trades") else Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("win ${pct(sm.winRate)}", style = Type.figure.copy(fontSize = 12.sp, color = p.verdigris))
                        Text("mean ${rs1(sm.mean)}", style = Type.figure.copy(fontSize = 12.sp, color = p.ink))
                        Text("worst ${rs(sm.worst, true)}", style = Type.figure.copy(fontSize = 12.sp, color = p.oxblood))
                        Text("cost ${pct(sm.costShare, 1)}", style = Type.figure.copy(fontSize = 12.sp, color = p.inkSoft))
                    }
                }
            }
            Load.Idle -> Unit
        }
    }
}

private fun adopt(s: AppSettings, p: ExpiryPut.Params): AppSettings = s.copy(
    otmPct = p.otmPct, entry = com.optionslab.engine.minuteText(p.entryMinute), regime = p.regime, wingPct = p.wingPct,
    datedLot = p.lot is ExpiryPut.LotChoice.Dated,
    pinnedLot = (p.lot as? ExpiryPut.LotChoice.Pinned)?.lot ?: s.pinnedLot,
)
