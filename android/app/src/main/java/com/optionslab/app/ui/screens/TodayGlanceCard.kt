package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.GlanceSource
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.BigMoveRisk
import com.optionslab.ira.TodayGlance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home's "Today at a glance" card, kept fresh while Home is on screen: read off the main thread ([load], on
 * [Dispatchers.IO]) once at once and then every [everyMs] (60 s), only while the app is in front ([com.optionslab.app.ui.PollWhileStarted]:
 * paused in the background). The card's state is written only when what it shows changed - a read that comes back the same
 * writes nothing, so Compose (and a test) can always settle. Folded or open is remembered ([GlanceSource.expanded]).
 */
@Composable
internal fun TodayGlanceCard(
    onGo: (String) -> Unit,
    load: suspend () -> TodayGlance.Card? = { GlanceSource.read() },
    everyMs: Long = GlanceSource.EVERY_MS,
) {
    var card by remember { mutableStateOf(GlanceSource.last) }
    var open by rememberSaveable { mutableStateOf(GlanceSource.expanded()) }
    com.optionslab.app.ui.PollWhileStarted(everyMs) {
        while (true) {
            val fresh = try { withContext(Dispatchers.IO) { load() } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (fresh != null && fresh != card) card = fresh
            delay(everyMs)
        }
    }
    TodayGlanceContent(card, open, onToggle = { open = !open; GlanceSource.setExpanded(open) }, onGo = onGo)
}

/** The card from plain state ([card] null: not read yet), so tests drive it without the app's records. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TodayGlanceContent(card: TodayGlance.Card?, expanded: Boolean, onToggle: () -> Unit, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var riskNote by rememberSaveable { mutableStateOf(false) }
    LedgerCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today at a glance", style = Type.title.copy(color = p.ink, fontSize = 16.sp))
                Text(card?.market ?: "Reading today…", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            Text(if (expanded) "Hide ▴" else "Show ▾", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(start = 8.dp))
        }
        if (expanded && card != null) GlanceDetails(card, riskNote, { riskNote = !riskNote }, onGo)
    }
}

/** The open card's lines under its heading. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GlanceDetails(card: TodayGlance.Card, riskNote: Boolean, onRisk: () -> Unit, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    Column {
        card.notices.forEach { Text(it, style = Type.bodySmall.copy(color = p.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp)) }
        if (card.cues.isNotEmpty()) {
            GlanceLabel("Before the open")
            card.cues.forEach { Text(it, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
        }
        if (card.risks.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onRisk).padding(vertical = 4.dp)) {
                GlanceLabel("Big-move risk now ⓘ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    card.risks.forEach { b -> GlanceBadge(b) }
                }
                if (riskNote) Note(TodayGlance.RISK_NOTE, Modifier.padding(top = 6.dp))
            }
        }
        if (card.rows.isNotEmpty()) {
            Rule(Modifier.padding(top = 6.dp))
            card.rows.forEachIndexed { i, r ->
                if (i > 0) Rule()
                GlanceRow(r) { onGo(r.to) }
            }
        }
        if (card.verdicts.isNotEmpty()) {
            Rule()
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onGo(TodayGlance.TO_PNL) }.padding(vertical = 6.dp)) {
                GlanceLabel("Live vs backtest")
                card.verdicts.forEach { Text(it, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
            }
        }
        Rule()
        GlanceLabel("Today and tomorrow")
        (card.events.ifEmpty { listOf(TodayGlance.NO_EVENTS) }).forEach {
            Text(it, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        }
        Text(card.asOf, style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun GlanceLabel(text: String) {
    val p = LocalPalette.current
    Text(text, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun glanceColor(level: BigMoveRisk.Level?): Color {
    val p = LocalPalette.current
    return when (level) {
        BigMoveRisk.Level.LOW -> p.verdigris
        BigMoveRisk.Level.NORMAL -> p.inkSoft
        BigMoveRisk.Level.HIGH -> p.amber
        BigMoveRisk.Level.VERY_HIGH -> p.oxblood
        null -> p.inkFaint
    }
}

@Composable
private fun GlanceBadge(b: TodayGlance.Badge) {
    val c = glanceColor(b.level)
    Text("${b.market}: ${b.word}", style = Type.label.copy(color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** One strategy: its name and state on one line (wrapped when they do not fit), the lines under it; a tap opens its page. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GlanceRow(r: TodayGlance.Row, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 7.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.title + " ›", style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.CenterVertically))
            val (bg, fg) = if (r.on) p.verdigris.copy(alpha = 0.14f) to p.verdigris else p.chip to p.inkSoft
            Text(r.state, style = Type.label.copy(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.CenterVertically).background(bg, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
        r.lines.forEach { Text(it, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)) }
    }
}
