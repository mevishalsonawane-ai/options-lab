package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.JarvisLearning
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.AgentLessons

/** The sheet's title (also the Ira page's card). */
internal const val LEARNED_TITLE = "What Jarvis has learned"

/** Shown in IraAlgo with Jarvis; IraGoldAlgo has no agents to learn from. */
internal fun learnedShown(): Boolean = JarvisLearning.enabled()

/** What Jarvis has learned, read from his small file off the main thread the first time. */
@Composable
internal fun rememberLearnedBook(): AgentLessons.Book {
    val b by JarvisLearning.book.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { JarvisLearning.ensureLoaded() } } }
    return b
}

/** The Ira page's card: how many lessons and how strong, the day's line, and the way into the sheet. */
@Composable
internal fun JarvisLearnedCard(book: AgentLessons.Book, onOpen: () -> Unit) {
    val c = remember(book) { AgentLessons.counts(book) }
    val held = remember(book) { AgentLessons.active(book).size }
    LedgerCard(title = LEARNED_TITLE) {
        Note(if (held == 0) "Nothing yet: each evening after the close Jarvis studies what the bots, the trade manager and the order flow kept, and says a lesson only with its numbers."
            else "$held lessons from the agents: ${c[AgentLessons.Confidence.STRONG]} strong, ${c[AgentLessons.Confidence.BUILDING]} building, ${c[AgentLessons.Confidence.WEAK]} weak.")
        book.line?.let { Text(it, style = Type.label.copy(color = LocalPalette.current.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp)) }
        BrassButton("Open", modifier = Modifier.padding(top = 8.dp), tone = LocalPalette.current.inkSoft, onClick = onOpen)
    }
}

/**
 * "What Jarvis has learned": his growth (lessons by confidence, each topic's level with its evidence), the lessons by topic
 * with a confidence chip, their status and evidence, and the growth timeline. Read only: nothing here changes anything (a
 * change a lesson suggests is put to Boss as a request). One scrolling column, so it fits a phone held either way.
 */
@Composable
internal fun JarvisLearnedSheet(book: AgentLessons.Book, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var picked by rememberSaveable { mutableStateOf("") }
    val topic = remember(picked) { AgentLessons.Topic.entries.firstOrNull { it.name == picked } }
    val active = remember(book) { AgentLessons.ranked(AgentLessons.active(book)) }
    val topics = remember(active) { AgentLessons.Topic.entries.filter { t -> active.any { it.topic == t } } }
    val shown = remember(active, topic) { if (topic == null) active else active.filter { it.topic == topic } }
    val counts = remember(book) { AgentLessons.counts(book) }
    val skills = remember(book) { AgentLessons.skills(book).filter { it.n > 0 } }
    val retired = remember(book) { book.lessons.count { it.status == AgentLessons.Status.RETIRED } }
    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LEARNED_TITLE, style = Type.masthead.copy(color = p.ink, fontSize = 22.sp), modifier = Modifier.weight(1f).testTag("learned-title"),
                maxLines = 2)
            if (onClose != null) BrassButton("Close", modifier = Modifier.testTag("learned-close"), tone = p.inkSoft, onClick = onClose)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") {
                Note("From the agents' own records, studied each evening after the close. A lesson is strong only with 30 or more cases " +
                    "that agree over time. Jarvis only proposes: nothing here changes a setting or a trade.")
            }
            item(key = "growth") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentLessons.Confidence.entries.reversed().forEach { c -> ConfidenceChip(c, "${c.word.replaceFirstChar { it.uppercase() }} · ${counts[c] ?: 0}") }
                    if (retired > 0) LearnedChip("Retired · $retired", p.chip, p.ink)
                }
            }
            if (skills.isNotEmpty()) item(key = "skills") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Skills", style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    skills.forEach { s -> Text("• ${s.words()}", style = Type.label.copy(color = p.ink, fontSize = 13.sp)) }
                }
            }
            if (topics.size > 1) item(key = "topics") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TopicChip("All · ${active.size}", picked.isEmpty()) { picked = "" }
                    topics.forEach { t -> TopicChip("${t.title} · ${active.count { it.topic == t }}", picked == t.name) { picked = if (picked == t.name) "" else t.name } }
                }
            }
            if (shown.isEmpty()) item(key = "none") {
                Note("No lessons yet. Jarvis needs the agents' records to hold some numbers first.", Modifier.padding(vertical = 8.dp))
            }
            items(shown, key = { "l:" + it.id }) { l -> LessonRow(l) }
            if (book.timeline.isNotEmpty()) {
                item(key = "timeline-head") {
                    Text("Growth timeline", style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 8.dp))
                }
                items(book.timeline.reversed(), key = { "m:" + it.key }) { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(AgentLessons.day(m.day), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(end = 10.dp))
                        Text(m.text, style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One lesson: its statement and confidence chip (the chip keeps its room, the words wrap), its status and evidence. */
@Composable
private fun LessonRow(l: AgentLessons.Lesson) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(AgentLessons.statement(l), style = Type.body.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f).padding(end = 8.dp))
            ConfidenceChip(l.confidence, l.confidence.word)
        }
        Text("${l.topic.title} · ${l.status.word}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        Text(AgentLessons.evidenceLine(l), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        // What Autopilot made of it (applied, kept or reverted): he learns from his own changes.
        l.auto?.let { Text("Autopilot: $it", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun ConfidenceChip(c: AgentLessons.Confidence, label: String) {
    val p = LocalPalette.current
    val (bg, fg) = when (c) {
        AgentLessons.Confidence.STRONG -> p.verdigris to Color.White
        AgentLessons.Confidence.BUILDING -> p.brass to p.onPrimary
        AgentLessons.Confidence.WEAK -> p.chip to p.ink
    }
    LearnedChip(label, bg, fg)
}

@Composable
private fun LearnedChip(label: String, bg: Color, fg: Color) {
    val p = LocalPalette.current
    Text(label, style = Type.label.copy(color = fg, fontSize = 12.sp), maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(10.dp)).border(1.dp, p.rule, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** A topic filter chip, 40dp tall at least (an easy tap). */
@Composable
private fun TopicChip(label: String, on: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    Text(label, style = Type.label.copy(color = if (on) Color.White else p.ink, fontSize = 13.sp), maxLines = 1,
        modifier = Modifier.heightIn(min = 40.dp).background(if (on) p.oxblood else p.chip, RoundedCornerShape(16.dp))
            .border(1.dp, p.rule, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp))
}
