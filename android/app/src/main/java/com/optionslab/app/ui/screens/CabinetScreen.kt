package com.optionslab.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

private val GROUPS = listOf(
    "Account" to listOf(
        Drawer("broker", "Zerodha", "Login, mode, order limits, manual order"),
        Drawer("risk", "Risk limits", "Kill switch, daily loss, drawdown, position and order limits"),
        Drawer("alarms", "Alerts", "Price alarms and P&L alerts"),
        Drawer("security", "Security", "PIN, biometrics, device checks, widget"),
        Drawer("schedule", "Schedules", "Daily jobs, notifications, market holidays"),
    ),
    "Research" to listOf(
        Drawer("signal", "Signal lab", "UT Bot and LinReg on a harvested day"),
        Drawer("ic", "IC table", "Is the data predictable, net of cost?"),
        Drawer("sizing", "Sizing and tail risk", "What a bad day costs"),
        Drawer("costs", "Cost calculator", "Itemised charges and spread"),
        Drawer("lots", "Lot sizes", "NSE lot history, from bhavcopy"),
        Drawer("notes", "Research notes", "What was tested, and what was closed"),
    ),
    "Data" to listOf(
        Drawer("data", "Data and harvest", "The record, nightly harvest, provenance"),
    ),
)

@Composable
fun CabinetScreen(model: AppModel, page: String?, onPage: (String?) -> Unit) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // Opening a page pushes it in from the right; going back slides it away, as in any settings list.
            val dir = if (targetState != null) 1 else -1
            (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it * dir / 4 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(200)) { -it * dir / 8 } + fadeOut(tween(140)))
        },
        label = "drawer",
    ) { pg ->
        if (pg != null) Column {
            Text("‹  More", style = Type.label.copy(color = LocalPalette.current.ink, fontSize = 15.sp),
                modifier = Modifier.clickable { onPage(null) }.padding(horizontal = 16.dp, vertical = 10.dp))
            Box(Modifier.weight(1f)) { DrawerPage(model, pg, onPage) }
        } else Drawers(onPage)
    }
}

@Composable
private fun DrawerPage(model: AppModel, pg: String, onPage: (String?) -> Unit) {
    run {
        when (pg) {
            "broker" -> BrokerPage(model)
            "alarms" -> AlarmsPage(model)
            "risk" -> RiskPage(model)
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
        GROUPS.forEach { (group, items) ->
            item {
                Text(group, style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            item {
                LedgerCard {
                    items.forEachIndexed { i, d ->
                        if (i > 0) com.optionslab.app.ui.components.Rule()
                        Row(
                            Modifier.fillMaxWidth().clickable { onPage(d.key) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, style = Type.body.copy(color = p.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                                Text(d.blurb, style = Type.bodySmall.copy(color = p.inkSoft))
                            }
                            Text("›", style = Type.title.copy(color = p.inkFaint, fontSize = 22.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageTitle(text: String, sub: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = Type.masthead.copy(color = p.ink, fontSize = 24.sp))
        if (sub != null) Text(sub, style = Type.bodySmall.copy(color = p.inkSoft))
    }
}
