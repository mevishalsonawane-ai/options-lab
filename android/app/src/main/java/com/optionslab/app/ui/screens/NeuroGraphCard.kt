package com.optionslab.app.ui.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.NeuroGraphJob
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.InkProgress
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.neuro.NeuroGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The NeuroGraph on the Dhan data page: what Jarvis has learned from the stored market data (nodes, links, how many
 * proven out of sample, when it was last built), the build's progress, and Rebuild graph. Not in IraGoldAlgo.
 */
@Composable
fun NeuroGraphCard() {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val p = LocalPalette.current
    val context = LocalContext.current
    val prog by NeuroGraphJob.progress.collectAsState()
    var summary by remember { mutableStateOf<NeuroGraph.Summary?>(null) }
    LaunchedEffect(prog.running) {
        summary = withContext(Dispatchers.IO) { runCatching { NeuroGraphJob.summary() }.getOrNull() }
    }
    LedgerCard(title = "NeuroGraph") {
        Note("What Jarvis learns from this data: how the indices, their companies, sectors, expiries, weekdays and market events relate. It builds by itself after each download or import (a full rebuild waits for the charger). A link is \"proven\" only if it held on the newest 30% of the data; the rest are hypotheses. It only informs answers and paper research - it never arms or trades.")
        val s = summary
        if (s == null) LedgerLine("Graph", if (prog.running) "building…" else "not built yet")
        else {
            LedgerLine("Nodes", "${s.nodes}")
            LedgerLine("Links", "${s.edges}")
            LedgerLine("Proven out of sample", "${s.proven}")
            LedgerLine("Hypotheses", "${s.hypotheses}")
            if (s.from != null && s.to != null) LedgerLine("Learned from", "${s.days} sessions, ${s.from} to ${s.to}")
            LedgerLine("Last built", java.time.Instant.ofEpochMilli(s.builtAt).atZone(com.optionslab.engine.IST).toLocalDateTime()
                .withSecond(0).withNano(0).toString().replace('T', ' '))
        }
        prog.last?.let { LedgerLine("Last run", it) }
        if (prog.running) {
            Spacer(Modifier.height(6.dp))
            Text(prog.stage, style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
            InkProgress(prog.fraction, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        BrassButton("Rebuild graph", Modifier.fillMaxWidth(), enabled = !prog.running) { NeuroGraphJob.rebuild(context) }
        Note("Ask Jarvis \"graph stats\", \"what moves BankNifty most per the graph?\" or \"graph se batao gap down ke baad kya hota hai\".")
    }
}
