package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.OrderReviewHost
import com.optionslab.app.data.VariantRunner
import com.optionslab.app.ui.components.Expandable
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ORDER_REVIEW_TITLE = "Order review"

/**
 * The Order review section (Home → Automation card → Order review, 12 Oct): one line for the latest reviewed day, the warnings
 * (serious flaws, recurring issues that need a fix) always in sight, and behind "Show details" each flaw with its numbers,
 * each strategy's day, what the strategy lab is testing from the flaws (and, once promoted, the before / after on the
 * strategy's own nets) and the proposals the lab cannot test. Memory only on screen; the file is read off the main thread
 * the first time. It never places, changes or cancels an order. None in GOLD or a build without Jarvis.
 */
@Composable
internal fun OrderReviewSection() {
    if (!OrderReviewHost.enabled()) return
    val book by OrderReviewHost.book.collectAsState(Dispatchers.Main.immediate)
    val lab by VariantRunner.variants.collectAsState(Dispatchers.Main.immediate)
    val today = rememberToday()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { OrderReviewHost.ensureLoaded() } } }
    val view = remember(book, lab, today) { OrderReviewHost.view(book, lab, today) }
    OrderReviewContent(view)
}

/** The section from plain state (tests drive it). */
@Composable
internal fun OrderReviewContent(v: OrderReviewHost.View) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("order-review")) {
        Text(ORDER_REVIEW_TITLE, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        Text(v.line, style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp).testTag("order-review-line"))
        // Warnings stay in sight, never folded.
        v.warnings.forEachIndexed { i, w ->
            Text(w, style = Type.label.copy(color = p.oxblood, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp).testTag("order-review-warning-$i"))
        }
        Note("Every order and trade is studied once a day after the close. Parameter ideas go to the strategy lab (paper first); " +
            "nothing is changed by the review itself, and it never places, changes or cancels an order.", Modifier.padding(top = 4.dp))
        Expandable("order-review", Modifier.padding(top = 2.dp)) {
            OrderReviewList("Flaws", v.flaws, "order-review-flaws", "No flaws found.")
            OrderReviewList("Per strategy", v.summaries, "order-review-summaries", "Nothing reviewed yet.")
            OrderReviewList("Tested in the strategy lab", v.lab, "order-review-lab", "Nothing asked of the lab yet (a flaw must recur first).")
            if (v.fixes.isNotEmpty()) OrderReviewList("Needs a fix", v.fixes, "order-review-fixes", "")
            if (v.proposals.isNotEmpty()) OrderReviewList("Proposals (the lab cannot test these)", v.proposals, "order-review-proposals", "")
            v.notes.forEach { Note(it, Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun OrderReviewList(title: String, lines: List<String>, tag: String, empty: String) {
    val p = LocalPalette.current
    Text(title, style = Type.label.copy(color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp).testTag(tag))
    if (lines.isEmpty() && empty.isNotEmpty()) Text(empty, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
    lines.forEach { l -> Text("• $l", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
}
