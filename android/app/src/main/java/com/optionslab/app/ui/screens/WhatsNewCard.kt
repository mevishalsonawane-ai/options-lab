package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
 * ([onGotIt]) marks them all seen - the caller writes that and stops showing the card. Plain state only: nothing here is
 * written during composition (the "Show all" choice changes on a tap).
 */
@Composable
internal fun WhatsNewCardContent(unseen: List<WhatsNew.Entry>, onGotIt: () -> Unit, onGo: (String) -> Unit) {
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
        BrassButton("Got it", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft, onClick = onGotIt)
        Note("The whole list stays in ${com.optionslab.app.ui.Tab.CABINET.label} → What's new.", Modifier.padding(top = 6.dp))
    }
}

/** One change: its title and day, what changed, where to find it and the question to try; a tap opens its page when it has one. */
@Composable
private fun WhatsNewRow(e: WhatsNew.Entry, onGo: ((String) -> Unit)?) {
    val p = LocalPalette.current
    val go: ((String) -> Unit)? = onGo
    val to: String? = if (go != null) e.to else null
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
