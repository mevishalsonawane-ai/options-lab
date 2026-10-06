package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.WeeklyReview

/** The Ira page's slot: the kept reviews ([com.optionslab.app.ira.IraWeekly]), read off the main thread. */
@Composable
internal fun WeeklyReviewSlot() {
    val kept by com.optionslab.app.ira.IraWeekly.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    LaunchedEffect(Unit) { runCatching { com.optionslab.app.ira.IraWeekly.refresh() } }
    WeeklyReviewCard(kept.orEmpty())
}

/**
 * Jarvis's weekly review ([WeeklyReview]): the newest week's three sentences, the week's eight parts on "The whole review",
 * and the older weeks (up to [WeeklyReview.KEEP_WEEKS]) one tap away. A record of the week, never advice.
 */
@Composable
internal fun WeeklyReviewCard(reviews: List<WeeklyReview.Review>, openAtFirst: Boolean = false) {
    val p = LocalPalette.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(openAtFirst) }
    LedgerCard(title = "Jarvis's weekly review") {
        if (reviews.isEmpty()) {
            Note("No weekly review yet: Jarvis makes one after each week's last session (15:45) and keeps the last ${WeeklyReview.KEEP_WEEKS} weeks.")
        } else {
            val i = index.coerceIn(0, reviews.size - 1)
            val r = reviews[i]
            Text(r.title, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(keepNumbersWhole(r.summary), style = Type.bodySmall.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Step("‹ Older", enabled = i < reviews.size - 1, Modifier.weight(1f)) { index = i + 1; open = false }
                Text("${i + 1} of ${reviews.size}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f))
                Step("Newer ›", enabled = i > 0, Modifier.weight(1f), end = true) { index = i - 1; open = false }
            }
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button) { open = !open }, contentAlignment = Alignment.CenterStart) {
                Text(if (open) "Less" else "The whole review", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp))
            }
            if (open) r.sections.forEach { s ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(s.title, style = Type.body.copy(color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                    s.lines.forEach { l ->
                        Text(keepNumbersWhole(l.trimStart()), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp),
                            modifier = Modifier.padding(start = if (l.startsWith("  ")) 12.dp else 0.dp, top = 2.dp))
                    }
                }
            }
            Note("A record of the week, information only - never advice to trade more or bigger.", Modifier.padding(top = 6.dp))
        }
    }
}

/** "‹ Older" / "Newer ›": a full-size target, faint when there is no week that way. */
@Composable
private fun Step(label: String, enabled: Boolean, modifier: Modifier, end: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(label, style = Type.label.copy(color = if (enabled) p.ink else p.inkFaint, fontSize = 13.sp))
    }
}
