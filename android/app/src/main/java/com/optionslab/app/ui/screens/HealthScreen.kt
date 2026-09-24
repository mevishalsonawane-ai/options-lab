package com.optionslab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.VerdictDial
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Monitor

@Composable
fun HealthScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val h by model.health.collectAsState()
    var source by remember { mutableStateOf((h as? Load.Done<com.optionslab.app.ui.HealthResult>)?.value?.source ?: "backtest") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { source = "imported"; model.importLedger(it) } }

    LaunchedEffect(Unit) { if (h is Load.Idle) model.runHealth(source) }

    Page {
        item {
            LedgerCard(title = "Kill Conditions") {
                Note("Not a learner. It adapts nothing. It asks whether the conditions this strategy depends on are still true - now, not on average since 2023.")
                val sources = listOf("backtest" to "Backtest", "paper" to "Paper ledger", "imported" to "Imported CSV")
                ParamTokens("Check", sources.map { it.second to (it.first == source) }) { i ->
                    if (sources[i].first == "imported") pick.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                    else { source = sources[i].first; model.runHealth(source) }
                }
                val windows = listOf(10, 30, 60, 0)
                ParamTokens("Window", windows.map { (if (it == 0) "all" else "last $it") to (it == s.healthLast) }) { i ->
                    model.update { it.copy(healthLast = windows[i]) }; model.runHealth(source)
                }
            }
        }
        when (val r = h) {
            is Load.Busy -> item { LedgerCard { FullSpinner(r.label) } }
            is Load.Failed -> item { LedgerCard(accent = p.amber) { Note(r.why) } }
            is Load.Done -> {
                val res = r.value
                item {
                    LedgerCard(title = "Verdict") {
                        VerdictDial(res.verdict.rank, calm = s.reduceMotion)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(res.verdict.mark, style = Type.masthead.copy(color = color(res.verdict)))
                                Text("last ${res.window.size} of ${res.total} sessions (${res.window.first().session} .. ${res.window.last().session})",
                                    style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
                            }
                            Stamp(if (res.verdict == Monitor.Status.PASS) "Holds" else "Look closer", color(res.verdict))
                        }
                        Spacer(Modifier.height(6.dp))
                        Note(if (res.verdict == Monitor.Status.PASS) "Every precondition still holds."
                        else "The verdict is the WORST check, never an average: one broken precondition is enough to end the strategy.")
                    }
                }
                res.checks.forEach { c -> item { CheckCard(c) } }
            }
            Load.Idle -> Unit
        }
        item {
            BrassButton("Check again", Modifier.fillMaxWidth()) { model.runHealth(source) }
        }
    }
}

@Composable
private fun color(s: Monitor.Status) = LocalPalette.current.let { p ->
    when (s) { Monitor.Status.PASS -> p.verdigris; Monitor.Status.WARN -> p.amber; Monitor.Status.FAIL -> p.oxblood }
}

@Composable
private fun CheckCard(c: Monitor.Check) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(c.status != Monitor.Status.PASS) }
    LedgerCard(accent = color(c.status), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name.uppercase(), style = Type.title.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            Stamp(c.status.mark, color(c.status), animate = false)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.measured, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
        Text("vs ${c.threshold}", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
        AnimatedVisibility(open) {
            Note(c.why, Modifier.padding(top = 6.dp).clickable { open = false })
        }
    }
}
