package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.DriftReview
import com.optionslab.ira.Findings
import com.optionslab.ira.MarketBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home → Strategies card → Brain (10 Oct, smart workers): each strategy's health against its backtest ("on track",
 * "drifting", "parked", "too few trades"), the market's regime and the event windows open now, the bots' consensus per
 * index, and a sheet with the findings feed and Boss's choices (what pauses new entries, the reactions, the safety net, a
 * drifting paper strategy). Memory only on screen; settings are written off the main thread.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BrainSection() {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val p = LocalPalette.current
    val brain by SmartWorkers.brain.collectAsState(Dispatchers.Main.immediate)
    val reviews by SmartWorkers.reviews.collectAsState(Dispatchers.Main.immediate)
    val settings by SmartWorkers.settings.collectAsState(Dispatchers.Main.immediate)
    var sheet by remember { mutableStateOf(false) }
    var consensus by remember { mutableStateOf<List<String>>(emptyList()) }
    // The settings and the reviews read once, off the main thread; the consensus every 15 s while shown (memory only).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SmartWorkers.ensureLoaded(); SmartWorkers.refreshReviews() } } }
    LaunchedEffect(Unit) {
        while (true) {
            consensus = withContext(Dispatchers.Default) { runCatching { SmartWorkers.consensusLines().take(2) }.getOrDefault(emptyList()) }
            kotlinx.coroutines.delay(15_000L)
        }
    }
    Rule()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Brain", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        val chips = SmartWorkers.chips(reviews, settings)
        if (chips.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (name, chip) ->
                val tone = when (chip) { "on track" -> p.verdigris; "drifting" -> p.amber; "parked" -> p.oxblood; else -> p.inkSoft }
                Text("$name: $chip", maxLines = 1, softWrap = false,
                    style = Type.label.copy(color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { contentDescription = "$name health: $chip" }
                        .background(tone.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        val now = System.currentTimeMillis()
        val regime = brain.regimes.values.joinToString(" · ") { "${it.name} ${it.state.words}" }
        Note("Market: " + regime.ifEmpty { "regime not known yet" } +
            (brain.activeWindows(now).firstOrNull()?.let { " · ${it.name} window now" } ?: ""), Modifier.padding(top = 4.dp))
        consensus.forEach { Note("Bots on $it", Modifier.padding(top = 2.dp)) }
        if (brain.health.words().isNotEmpty()) Note("Data: " + brain.health.words().joinToString("; "), Modifier.padding(top = 2.dp))
        BrassButton("Findings and pauses", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { sheet = true }
    }
    if (sheet) BrainSheet { sheet = false }
}

/** The findings feed and Boss's choices for the smart workers. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BrainSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val settings by SmartWorkers.settings.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var feed by remember { mutableStateOf<List<String>>(emptyList()) }
    var effects by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        feed = withContext(Dispatchers.Default) { runCatching { SmartWorkers.feed(30) }.getOrDefault(emptyList()) }
        effects = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.FlowGate.pauseEffectLines() }.getOrDefault(emptyList()) }
    }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Findings and pauses", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                BrainHeading("What the bots found (newest first)")
                if (feed.isEmpty()) Note("Nothing standing just now.") else feed.forEach { Note(it, Modifier.padding(vertical = 2.dp)) }
                BrainHeading("Pause new entries during")
                Note("Pauses only ever skip a NEW entry, paper and live alike; exits always go. SHADOW changes nothing and logs, beside " +
                    "each signal, what a pause would have done, to judge later.")
                MarketBrain.Cause.entries.forEach { c ->
                    BrainChoice(c.words.replaceFirstChar { it.uppercase() }, MarketBrain.Mode.entries.map { it.name }, settings.pause.mode(c).name) { m ->
                        save { SmartWorkers.setPause(c, MarketBrain.Mode.valueOf(m)) }
                    }
                }
                BrainHeading("Reactions between bots (every strategy)")
                Findings.Reaction.entries.forEach { r ->
                    BrainChoice(r.words.replaceFirstChar { it.uppercase() }, Findings.Mode.entries.map { it.name }, settings.reactions.mode("*", r).name) { m ->
                        save { SmartWorkers.setReaction(null, r, Findings.Mode.valueOf(m)) }
                    }
                }
                BrainHeading("Safety-net check with nothing held")
                BrainChoice("Every (seconds); while anything is held it is always every 15 s", listOf("15", "30", "45", "60"), settings.netFlatSec.toString()) { v ->
                    save { SmartWorkers.setNetFlatSec(v.toInt()) }
                }
                BrainHeading("A paper strategy drifting from its backtest")
                BrainChoice("A live one always parks itself to paper and asks you", DriftReview.PaperPolicy.entries.map { it.name }, settings.paperDrift.name) { v ->
                    save { SmartWorkers.setPaperDrift(DriftReview.PaperPolicy.valueOf(v)) }
                }
                if (effects.isNotEmpty()) {
                    BrainHeading("What the shadow pauses would have done")
                    effects.forEach { Note(it, Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BrainHeading(text: String) {
    val p = LocalPalette.current
    Text(text.uppercase(java.util.Locale.ENGLISH), style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

/** One choice: its [label] and a chip per option ([current] lit); a tap on another calls [onPick]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BrainChoice(label: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val on = o == current
                val fill: Color = if (on) p.brass else p.chip
                Text(o, maxLines = 1, softWrap = false,
                    style = Type.label.copy(color = if (on) p.onPrimary else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { contentDescription = "$label: $o" }
                        .background(fill, RoundedCornerShape(50))
                        .clickable { if (!on) onPick(o) }.heightIn(min = 36.dp)
                        .padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
    }
}
