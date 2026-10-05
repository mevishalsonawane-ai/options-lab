package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ui.components.Flourish
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.Requests
import kotlinx.coroutines.delay

/**
 * The Requests panel (Boss, 5 Oct): only what waits for his yes - Jarvis's trade ideas, "shall I stop / exit / close",
 * guard offers, plans, strategies - apart from the chat. Each says what it would do, why, Paper or Zerodha, when it was
 * asked and when it lapses, with Yes and No: Yes is the hub's own confirm (fingerprint, PIN and live gates and all), No
 * its decline. Behind the app's lock like every screen; nothing actionable in IraGoldAlgo.
 */
@Composable
fun RequestsPanel(onClose: () -> Unit) {
    val p = LocalPalette.current
    val waiting by iraSlice { s -> IraHub.requestsOf(s) }
    val recent by IraHub.recentRequests().collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The countdowns tick each second while the panel is on screen.
    com.optionslab.app.ui.PollWhileStarted { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val gold = com.optionslab.app.BuildConfig.GOLD
    val shown = Requests.shown(waiting, now, gold)
    LazyColumn(Modifier.fillMaxSize().background(p.paper), contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹  Back", style = Type.label.copy(color = p.inkSoft, fontSize = 14.sp),
                    modifier = Modifier.clickable { onClose() }.padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
                Flourish(Requests.badge(shown.size))
            }
        }
        if (gold) item { LedgerCard { Note(Requests.GOLD) } }
        else if (shown.isEmpty()) item { LedgerCard { Text(Requests.EMPTY, style = Type.body.copy(color = p.ink)) } }
        else item { Note("Yes goes through the same checks as ever: real money still needs your fingerprint or PIN. A request lapses by itself, and nothing is done.") }
        items(shown, key = { "w${it.kind.name}${it.id}" }) { v -> RequestCard(v, now) }
        if (!gold && recent.isNotEmpty()) {
            item { Flourish("Recent", Modifier.padding(top = 8.dp)) }
            items(recent, key = { "r${it.view.kind.name}${it.view.id}" }) { r -> RecentRow(r) }
        }
    }
}

@Composable
private fun RequestCard(v: Requests.RequestView, now: Long) {
    val p = LocalPalette.current
    val live = v.venue == Requests.Venue.ZERODHA
    LedgerCard(accent = if (live) p.oxblood else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.kind.label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
            Spacer(Modifier.width(8.dp))
            Text(v.venue.label, style = Type.label.copy(color = if (live) p.oxblood else if (v.venue == Requests.Venue.PAPER) p.verdigris else p.inkSoft,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(v.what.replaceFirstChar { it.uppercase() }, style = Type.title.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.padding(top = 4.dp))
        val figures = listOfNotNull(v.symbol?.let { "Symbol: $it" }, v.qty?.let { "Qty: $it" }, v.price?.let { "Price: $it" })
        if (figures.isNotEmpty()) Note(figures.joinToString(" · "))
        v.why?.let { Note("Why: $it") }
        Note(Requests.askedText(v.askedAt, now) + " · " + Requests.lapseText(v.lapsesAt, now))
        // The very buttons the chat shows under the request: the hub's confirm and decline, never a path of their own.
        if (v.kind == Requests.Kind.STRATEGY) ProposalActions(v.id) else ActionConfirm(v.id, yes = "Yes, approve", no = "No, reject")
    }
}

@Composable
private fun RecentRow(r: Requests.Recent) {
    val p = LocalPalette.current
    val at = runCatching {
        java.time.Instant.ofEpochMilli(r.at).atZone(com.optionslab.engine.IST).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH))
    }.getOrDefault("")
    val tone = when (r.outcome) {
        Requests.Outcome.APPROVED -> p.verdigris
        Requests.Outcome.FAILED -> p.oxblood
        else -> p.inkSoft
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.outcome.label, style = Type.label.copy(color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(72.dp))
        Text("${r.view.title} · ${r.view.venue.label} · $at", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
    }
}
