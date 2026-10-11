package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.SelfGrowth
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.GrowthReport
import com.optionslab.ira.LearningGoals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sheet's title (and the Autopilot card's button). */
internal const val WEEKLY_REPORT_TITLE = "Weekly report"

/** What Jarvis tries to learn next, read from his small file off the main thread the first time. */
@Composable
internal fun rememberLearningGoals(): List<LearningGoals.Goal> {
    val g by SelfGrowth.goals.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SelfGrowth.ensureLoaded() } } }
    return g.goals
}

/**
 * The Autopilot card's way into Jarvis's weekly self-report (inside the card's own column: one item on Home, nothing laid
 * over it). The report is read off the main thread the first time. Not in IraGoldAlgo.
 */
@Composable
internal fun WeeklyReportButton() {
    if (!SelfGrowth.enabled()) return
    val weeks by SelfGrowth.weeks.collectAsState(Dispatchers.Main.immediate)
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SelfGrowth.ensureLoaded() } } }
    BrassButton(WEEKLY_REPORT_TITLE, Modifier.fillMaxWidth().padding(top = 6.dp).testTag("weekly-report-open"), tone = LocalPalette.current.inkSoft) { open = true }
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(LocalPalette.current.paper, RoundedCornerShape(16.dp))) {
            WeeklyGrowthSheet(weeks.lastOrNull(), onClose = { open = false })
        }
    }
}

/**
 * "Weekly report": the week's title, then each part as a card - lessons, Autopilot, specialists, app health, learning goals
 * and next week - each line from the records (the same words as the notification and "how did you grow this week?"). Read
 * only. One scrolling list with the title and Close in a row above it, so it fits upright, on its side and at large font.
 */
@Composable
internal fun WeeklyGrowthSheet(week: GrowthReport.Week?, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val sections = remember(week) { week?.let { GrowthReport.sections(it) }.orEmpty() }
    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(WEEKLY_REPORT_TITLE, style = Type.masthead.copy(color = p.ink, fontSize = 22.sp), maxLines = 2,
                modifier = Modifier.weight(1f).testTag("weekly-title"))
            if (onClose != null) BrassButton("Close", modifier = Modifier.testTag("weekly-close"), tone = p.inkSoft, onClick = onClose)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("weekly-list"),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (week == null) {
                item(key = "none") {
                    Note("No weekly report yet: Jarvis writes the first after Friday's close (or on Saturday morning), from the records only.",
                        Modifier.padding(vertical = 8.dp))
                }
            } else {
                item(key = "week") {
                    Text(GrowthReport.title(week.monday), style = Type.body.copy(color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.testTag("weekly-week"))
                }
                items(sections, key = { "s:" + it.first }) { (head, lines) -> WeeklySection(head, lines) }
                item(key = "foot") {
                    Note("Every number here is from the app's own records on this phone. Nothing in this report changes a setting or a trade.")
                }
            }
        }
    }
}

@Composable
private fun WeeklySection(head: String, lines: List<String>) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(head, style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        lines.forEach { Text(it, style = Type.label.copy(color = p.ink, fontSize = 13.sp)) }
    }
}
