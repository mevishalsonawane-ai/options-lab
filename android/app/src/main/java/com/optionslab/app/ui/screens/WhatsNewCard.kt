package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.WhatsNew

/**
 * Home's "What's new" card: the changes not seen yet ([unseen], newest first), the first [WhatsNew.COLLAPSED] until "Show all",
 * each with where to find it and a question to try with Jarvis; a change with a page to open is a tap away ([onGo]). "Got it"
 * ([onGotIt], in the header row, so it is always in view) marks them all seen - the caller writes that and stops showing
 * the card. Plain state only: nothing here is written during composition (the "Show all" choice changes on a tap).
 */
@Composable
internal fun WhatsNewCardContent(unseen: List<WhatsNew.Entry>, onGotIt: () -> Unit, onGo: ((String) -> Unit)?) {
    val p = LocalPalette.current
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = WhatsNew.collapsed(unseen, all)
    val more = WhatsNew.hidden(unseen, all)
    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What's new", style = Type.title.copy(color = p.ink, fontSize = 16.sp))
                Text(keepNumbersWhole(if (unseen.size == 1) "1 change since you last looked" else "${unseen.size} changes since you last looked"),
                    style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            BrassButton("Got it", Modifier.padding(start = 8.dp), tone = p.inkSoft, onClick = onGotIt)
        }
        shown.forEach { e ->
            Rule(Modifier.padding(top = 6.dp))
            WhatsNewRow(e, onGo)
        }
        if (more > 0) {
            Rule()
            Text(keepNumbersWhole("Show all ($more more) ▾"), style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { all = true }.padding(vertical = 14.dp))
        }
        Note("The whole list stays in ${com.optionslab.app.ui.Tab.CABINET.label} → What's new.", Modifier.padding(top = 6.dp))
    }
}

/**
 * The Ira page with "What's new" above it (Home opens on the Ira page in Jarvis): the same unseen list as the Dashboard's
 * card ([com.optionslab.app.data.WhatsNewStore.shown]), and the same "Got it", so dismissing either hides both. Compact, so
 * the globe and the question box keep the screen: one row ("What's new · N changes", Show, Got it); "Show" opens the list
 * under it, at most [LIST_SHARE] of the height there is, scrolling within it. With nothing unseen the page is exactly as
 * before. [onGo]: a change's page (null: the rows open nothing); a change that points at this page ("ira") opens nothing here.
 */
@Composable
internal fun WhatsNewOverPage(onGo: ((String) -> Unit)?, content: @Composable () -> Unit) {
    val news by remember { com.optionslab.app.data.WhatsNewStore.shown() }.collectAsState()
    // Each time the app comes back to the front: a settings read that failed before is tried again (a flow, not Compose state).
    com.optionslab.app.ui.PollWhileStarted { com.optionslab.app.data.WhatsNewStore.shown() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listMax = maxHeight * LIST_SHARE
        Column(Modifier.fillMaxSize()) {
            if (news.isNotEmpty()) WhatsNewHeader(news, onGotIt = { com.optionslab.app.data.WhatsNewStore.markAllSeen() }, onGo = onGo,
                listMax = listMax, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** The open list over the Ira page takes at most this share of the height there is. */
private const val LIST_SHARE = 0.35f

/**
 * The Ira page's compact "What's new": one row with the count, "Show"/"Hide" and "Got it" (always in view); open, the
 * changes under it, at most [listMax] tall and scrolling. A change that points at the Ira page itself ([here]) is no link.
 * Plain state only: "Show" changes on a tap.
 */
@Composable
internal fun WhatsNewHeader(unseen: List<WhatsNew.Entry>, onGotIt: () -> Unit, onGo: ((String) -> Unit)?, listMax: Dp,
                            modifier: Modifier = Modifier, here: String = "ira") {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(p.card, RoundedCornerShape(12.dp)).border(1.dp, p.rule, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(keepNumbersWhole(if (unseen.size == 1) "What's new · 1 change" else "What's new · ${unseen.size} changes"),
                style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 10.dp, vertical = 14.dp))
            BrassButton("Got it", tone = p.inkSoft, onClick = onGotIt)
        }
        if (open) Column(Modifier.fillMaxWidth().heightIn(max = listMax).verticalScroll(rememberScrollState())) {
            unseen.forEach { e ->
                Rule()
                WhatsNewRow(e, onGo, here)
            }
            Note("The whole list stays in ${com.optionslab.app.ui.Tab.CABINET.label} → What's new.", Modifier.padding(vertical = 6.dp))
        }
    }
}

/** One change: its title and day, what changed, where to find it and the question to try; a tap opens its page when it has one (not [here]). */
@Composable
private fun WhatsNewRow(e: WhatsNew.Entry, onGo: ((String) -> Unit)?, here: String? = null) {
    val p = LocalPalette.current
    val go: ((String) -> Unit)? = onGo
    // A change on the page already open ([here]) is no link (it would open a second copy of it).
    val to: String? = if (go != null) e.to?.takeIf { it != here } else null
    val tap: Modifier = if (to != null && go != null) Modifier.clickable(role = Role.Button) { go(to) } else Modifier
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(tap).padding(vertical = 7.dp)) {
        Text(keepNumbersWhole(e.title + if (to != null) " ›" else ""), style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        Text(keepNumbersWhole(WhatsNew.day(e.date)), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
        Text(keepNumbersWhole(e.what), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        Text(keepNumbersWhole(WhatsNew.whereLine(e)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        WhatsNew.askLine(e)?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Settings → What's new: every change this build shows, newest first, at any time ([entries]; the app passes this build's). */
@Composable
internal fun WhatsNewPage(entries: List<WhatsNew.Entry> = com.optionslab.app.data.WhatsNewStore.entries()) {
    Page {
        item { PageTitle("What's new", "What changed in recent updates, and where to find it") }
        if (entries.isEmpty()) item { LedgerCard { Note("Nothing new in this build yet.") } }
        WhatsNew.newestFirst(entries).forEach { e -> item(key = e.id) { LedgerCard { WhatsNewRow(e, null) } } }
    }
}
