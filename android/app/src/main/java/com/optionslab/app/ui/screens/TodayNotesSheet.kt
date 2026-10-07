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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.IraNotes
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.TodayNotes

/** The view's title (also the Ira page's chip and the Settings row). */
internal const val TODAY_NOTES_TITLE = "Today's notes"

/** IraAlgo's Jarvis posts notes by himself; IraGoldAlgo's chat posts none of the automations' notes: not shown there. */
internal fun todayNotesShown(): Boolean = !com.optionslab.app.BuildConfig.GOLD

/** A note collapsed shows this many lines; a tap opens it whole. */
internal const val TODAY_NOTES_LINES = 3

/**
 * Today's notes, newest first, from what [IraNotes] keeps (read only; the list changes as Jarvis posts, and the day as
 * midnight passes - yesterday's notes never stay listed as today's on a page left open overnight).
 */
@Composable
internal fun rememberTodayNotes(): List<TodayNotes.Note> {
    val all by IraNotes.notes.collectAsState()
    var day by remember { mutableStateOf(com.optionslab.app.data.Market.today()) }
    // The day, checked once a minute while the page shows (paused off screen); the state is written only when the day
    // changes - never otherwise, never during composition.
    com.optionslab.app.ui.PollWhileStarted {
        while (true) {
            val now = runCatching { com.optionslab.app.data.Market.today() }.getOrNull()
            if (now != null && now != day) day = now
            kotlinx.coroutines.delay(60_000L)
        }
    }
    return remember(all, day) { TodayNotes.of(all, day) }
}

/**
 * The Ira page's small "Today's notes" chip in its header row, beside the Requests badge (the question box below keeps its
 * room): how many notes Jarvis posted by himself today; a tap opens them.
 */
@Composable
internal fun TodayNotesChip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val n = rememberTodayNotes().size
    Text(keepNumbersWhole(if (n > 0) "$TODAY_NOTES_TITLE · $n" else TODAY_NOTES_TITLE), style = Type.label.copy(color = p.ink, fontSize = 13.sp),
        modifier = modifier.background(p.card, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/**
 * "Today's notes": every note Jarvis posted in the chat by himself today ([notes], newest first) with its time, its
 * category and its words (three lines; a tap opens it whole), filter chips by category, and - for a category picked - a
 * "Turn these off" shortcut per switch behind its notes that only opens that switch in Settings → What Jarvis does by
 * itself ([onTurnOff] gets the row's key): nothing here switches, places, closes or arms anything. Kept today only.
 * [onClose] null: no Close (Settings has its own way back). Plain state only: the filter and what is open change on a
 * tap, never during composition.
 */
@Composable
internal fun TodayNotesSheet(notes: List<TodayNotes.Note>, onTurnOff: (String) -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    // The picked category's name ("" for all), saved with the screen as a plain string.
    var picked by rememberSaveable { mutableStateOf("") }
    val category = remember(picked) { TodayNotes.Category.entries.firstOrNull { it.name == picked } }
    // The notes opened whole, keyed by time and words (a new note arriving on top does not move which one is open).
    var opened by remember { mutableStateOf(emptySet<String>()) }
    val shown = remember(notes, category) { TodayNotes.filter(notes, category) }
    val chips = remember(notes) { TodayNotes.chips(notes) }
    val counts = remember(notes) { TodayNotes.counts(notes).toMap() }
    val switches = remember(notes, category) { if (category == null) emptyList() else IraNotes.switches(notes, category) }
    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(TODAY_NOTES_TITLE, style = Type.masthead.copy(color = p.ink, fontSize = 22.sp), modifier = Modifier.weight(1f))
            if (onClose != null) BrassButton("Close", tone = p.inkSoft, onClick = onClose)
        }
        Note("Today only: what Jarvis said in the chat by himself today, newest first (kept while the app runs). Tap a note to read it whole. Read only: nothing here changes anything.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NotesFilterChip("All · ${notes.size}", picked.isEmpty()) { picked = "" }
            chips.forEach { c ->
                NotesFilterChip("${c.label} · ${counts[c] ?: 0}", picked == c.name) { picked = if (picked == c.name) "" else c.name }
            }
        }
        if (category != null && shown.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (switches.isEmpty()) Note("These have no switch of their own in Settings → Jarvis.")
            else {
                switches.forEach { (key, label) ->
                    BrassButton("Turn these off: $label", tone = p.inkSoft, onClick = { onTurnOff(key) })
                }
                Note("Opens its switch in Settings → Jarvis (What Jarvis does by itself); nothing is switched from here.")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) item(key = "none") {
                Note(if (category == null) "No notes from Jarvis yet today. When he posts one by himself in the chat, it is listed here."
                    else "No ${category.label} notes today.", Modifier.padding(vertical = 8.dp))
            }
            itemsIndexed(shown, key = { i, n -> "${n.at}|${n.text.hashCode()}|${shown.size - i}" }) { _, n ->
                val id = "${n.at}|${n.text.hashCode()}"
                val whole = id in opened
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
                    .border(1.dp, p.rule, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = if (whole) "Show less" else "Read it whole") { opened = if (whole) opened - id else opened + id }
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(keepNumbersWhole(TodayNotes.time(n.at)), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                        Text(n.category.label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
                            modifier = Modifier.padding(start = 8.dp).background(p.chip, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    Text(keepNumbersWhole(n.text), style = Type.body.copy(color = p.ink, fontSize = 14.sp),
                        maxLines = if (whole) Int.MAX_VALUE else TODAY_NOTES_LINES, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** A filter chip: its label with the count; [on] when picked. 40dp tall at least, so it is an easy tap. */
@Composable
private fun NotesFilterChip(label: String, on: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    Text(keepNumbersWhole(label), style = Type.label.copy(color = if (on) Color.White else p.ink, fontSize = 13.sp),
        modifier = Modifier.heightIn(min = 40.dp).background(if (on) p.oxblood else p.chip, RoundedCornerShape(16.dp))
            .border(1.dp, p.rule, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp))
}

/**
 * Settings → App → "Today's notes": the same view as a page. "Turn these off" hands the switch's row key to [onTurnOff]
 * (the caller opens Settings → Jarvis with that row brought into view).
 */
@Composable
internal fun TodayNotesPage(onTurnOff: (String) -> Unit) {
    TodayNotesSheet(rememberTodayNotes(), onTurnOff = onTurnOff, onClose = null)
}
