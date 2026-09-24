package com.optionslab.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

private data class Drawer(val key: String, val title: String, val blurb: String)

private val DRAWERS = listOf(
    Drawer("alarms", "Alarms", "Price alarms on NIFTY, BANKNIFTY and VIX"),
    Drawer("signal", "Signal Lab", "UT Bot and LinReg, replayed on a harvested day"),
    Drawer("ic", "The IC Table", "Is this data predictable at all, net of cost?"),
    Drawer("sizing", "Sizing & Tail", "What a bad day costs, computed not sampled"),
    Drawer("costs", "Cost Calculator", "Itemised charges and the tick-floored spread"),
    Drawer("lots", "Lot History", "NSE lot sizes, verified from bhavcopy"),
    Drawer("data", "Data & Harvest", "The record, the nightly harvest, provenance"),
    Drawer("security", "Security", "Lock, biometrics, integrity of this device"),
    Drawer("schedule", "Schedules & Notices", "The day's clock and what it tells you"),
    Drawer("notes", "Research Notes", "What was tested, and what was closed"),
)

@Composable
fun CabinetScreen(model: AppModel, page: String?, onPage: (String?) -> Unit) {
    AnimatedContent(
        targetState = page,
        transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.97f)) togetherWith fadeOut(tween(200)) },
        label = "drawer",
    ) { pg ->
        when (pg) {
            null -> Drawers(onPage)
            "alarms" -> AlarmsPage(model)
            "signal" -> SignalPage(model)
            "ic" -> IcPage(model)
            "sizing" -> SizingPage(model)
            "costs" -> CostsPage(model)
            "lots" -> LotsPage()
            "data" -> DataPage(model)
            "security" -> SecurityPage(model)
            "schedule" -> SchedulePage(model)
            "notes" -> NotesPage()
            else -> Drawers(onPage)
        }
    }
}

@Composable
private fun Drawers(onPage: (String) -> Unit) {
    val p = LocalPalette.current
    Page {
        item { Note("A cabinet of instruments. Everything the PC harness can do lives in one of these drawers.") }
        DRAWERS.chunked(2).forEach { pair ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { d ->
                        LedgerCard(Modifier.weight(1f).height(118.dp), onClick = { onPage(d.key) }) {
                            Text(d.title.uppercase(), style = Type.title.copy(color = p.ink, fontSize = 13.sp))
                            Spacer(Modifier.height(6.dp))
                            Text(d.blurb, style = Type.italic.copy(color = p.inkSoft, fontSize = 14.sp))
                        }
                    }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
fun PageTitle(text: String, sub: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(text.uppercase(), style = Type.masthead.copy(color = p.ink, fontSize = 18.sp))
        if (sub != null) Text(sub, style = Type.italic.copy(color = p.inkSoft))
    }
}
