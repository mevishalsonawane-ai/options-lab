package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.GrammarArm
import com.optionslab.app.data.VariantRunner
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.GrammarPaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The approved forge ideas trading on paper (S2), for the Strategy lab sheet: each hosted idea's paper record against its
 * forward test, what it holds, its last word, and a Stop button (stopping only lowers risk: what it holds is sold, and it is
 * not started again by itself). Ideas stopped (by Boss or by their own record) are listed under it with why. Paper only.
 */
@Composable
fun HostedIdeasSection() {
    if (!GrammarArm.enabled()) return
    // 15 Oct (Boss's cleanup): the strategy lab's paper ideas are hidden once they hold nothing (Home → Strategies → Hidden arms).
    val hidden by com.optionslab.app.data.HiddenArms.state.collectAsState(Dispatchers.Main.immediate)
    if (!com.optionslab.engine.orb.ArmsCleanup.visible(hidden, com.optionslab.engine.orb.ArmsCleanup.FORGE)) return
    val v by GrammarArm.view.collectAsState(Dispatchers.Main.immediate)
    val forge by VariantRunner.forge.collectAsState(Dispatchers.Main.immediate)
    // Read off the main thread; an idea approved a moment ago is taken in here (it trades from the next pass).
    LaunchedEffect(forge) { withContext(Dispatchers.IO) { runCatching { GrammarArm.refresh() } } }
    HostedIdeasContent(v, onStop = { id -> GrammarArm.stop(id) })
}

/** What the hosted ideas' title opens. */
internal const val HOSTED_NOTE = "An idea you start on paper trades by its own rules on the paper account, 1 lot - never a live order. It stops by itself " +
    "when it does worse than its forward test."

/** The section from plain state (tests drive it). [onStop]: stop one idea (a suspend call, run off the main thread). */
@Composable
internal fun HostedIdeasContent(v: GrammarArm.View, onStop: suspend (String) -> String) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val active = v.active
    val stopped = v.ideas.filter { !it.idea.active }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("lab-hosted")) {
        // COMPACT (15 Oct): how a hosted idea trades is in the title's dialog.
        ExplainedName("On paper now (${active.size} of ${GrammarPaper.MAX_HOSTED})", listOf(HOSTED_NOTE),
            style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
        said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp)) }
        if (active.isEmpty()) Note("None on paper yet.", Modifier.padding(top = 6.dp).testTag("lab-hosted-none"))
        active.forEach { iv ->
            val x = iv.idea
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(p.card, RoundedCornerShape(14.dp))
                .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("lab-hosted-" + x.id)) {
                Text(x.name, style = Type.body.copy(color = p.ink, fontSize = 14.sp))
                Text("Paper: ${iv.nets.size} trade${if (iv.nets.size == 1) "" else "s"}, ${GrammarPaper.rs(iv.nets.sum())} · forward test " +
                    "${GrammarPaper.rs(x.expect)} a trade (${x.expectN} trades)", style = Type.label.copy(color = p.ink, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 3.dp))
                iv.open?.let { o ->
                    Text("Holding ${o.symbol} ${o.qty} @ ${"%.2f".format(java.util.Locale.ENGLISH, o.entry)}", style = Type.label.copy(color = p.ink, fontSize = 12.sp),
                        modifier = Modifier.padding(top = 2.dp))
                }
                if (x.status.isNotEmpty()) Text(x.status, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
                iv.verdict?.let { Text(it, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
                BrassButton("Stop", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-hosted-stop-" + x.id), tone = p.inkSoft) {
                    scope.launch(Dispatchers.IO) {
                        val msg = runCatching { onStop(x.id) }.getOrElse { "Could not stop it just now." }
                        withContext(Dispatchers.Main) { said = msg }
                    }
                }
            }
        }
        stopped.takeLast(5).reversed().forEach { iv ->
            Text("${iv.idea.name}: ${iv.idea.stopWhy ?: "stopped"} · ${iv.nets.size} paper trades, ${GrammarPaper.rs(iv.nets.sum())}",
                style = Type.label.copy(color = p.oxblood, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp).testTag("lab-hosted-stopped-" + iv.idea.id))
        }
    }
}
