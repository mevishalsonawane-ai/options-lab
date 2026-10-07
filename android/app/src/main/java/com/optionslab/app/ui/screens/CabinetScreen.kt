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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    "Assistant" to listOf(
        Drawer("ira", "Ira", "Ask about today's market: trend, levels, patterns"),
    ),
) + (if (com.optionslab.app.BuildConfig.JARVIS) listOf(
    "Jarvis" to listOf(
        Drawer("jarvis", "Voice and AI model", "Listening, voice style, your voice print, spoken replies, the on-device model"),
    ),
) else emptyList()) + listOf(
    "Account" to listOf(
        Drawer("broker", "Zerodha", "Login, mode, order limits, manual order"),
        Drawer("alarms", "Alerts", "Price alarms and P&L alerts"),
        Drawer("security", "Security", "PIN, biometrics, device checks, widget"),
        Drawer("schedule", "Schedules", "Daily jobs, notifications, market holidays"),
    ),
    "Bot" to listOf(
        Drawer("risk", "Bot settings", "Kill switch, daily loss, drawdown, position and order limits"),
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
) + (if (!com.optionslab.app.BuildConfig.GOLD) listOf(
    // What changed in recent updates and where to find it (WhatsNewPage); Home's card shows the unseen ones.
    "App" to listOf(
        Drawer("whatsnew", "What's new", "Recent changes to the app and where to find them"),
        // Every question Jarvis answers, by topic (AskGuidePage); a tap asks it in the chat. The Ira page has it by the question box.
        Drawer("askguide", ASK_GUIDE_TITLE, "Every question Jarvis answers, by topic; tap one to ask it"),
    ) + (if (todayNotesShown()) listOf(
        // What Jarvis said in the chat by himself today, by category (TodayNotesPage). The Ira page has it in the chat's header.
        Drawer("todaynotes", TODAY_NOTES_TITLE, "What Jarvis said by himself today, newest first, by category"),
    ) else emptyList()),
) else emptyList())

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
            Text("‹  ${com.optionslab.app.ui.Tab.CABINET.label}", style = Type.label.copy(color = LocalPalette.current.ink, fontSize = 15.sp),
                modifier = Modifier.clickable { onPage(null) }.padding(horizontal = 16.dp, vertical = 10.dp))
            Box(Modifier.weight(1f)) { DrawerPage(model, pg, onPage) }
        } else Drawers(onPage)
    }
}

@Composable
private fun DrawerPage(model: AppModel, pg: String, onPage: (String?) -> Unit) {
    run {
        when (pg) {
            "ira" -> IraPage(androidx.compose.runtime.remember(model) { iraOrderPathsFor(model) })
            "jarvis" -> JarvisSettingsPage()
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
            "whatsnew" -> WhatsNewPage()
            // A question asked from the guide: the chat opens with it (the guide closes as the page changes).
            "askguide" -> AskGuidePage(onAsked = { onPage("ira-chat") })
            // "Turn these off": Settings → Jarvis with that switch's row brought into view (navigation only).
            "todaynotes" -> TodayNotesPage(onTurnOff = { key -> com.optionslab.app.ui.SettingFocus.ask(key); onPage("jarvis") })
            "ira-chat" -> IraPage(androidx.compose.runtime.remember(model) { iraOrderPathsFor(model) }, startInChat = true)
            else -> Drawers(onPage)
        }
    }
}

/** The pages this build's Settings shows (the drawers' keys): the search lists only settings on them. */
private val SHOWN_PAGES: Set<String> by lazy { GROUPS.flatMap { (_, items) -> items.map { it.key } }.toSet() }

@Composable
private fun Drawers(onPage: (String) -> Unit) {
    val p = LocalPalette.current
    // Settings search (07 Oct): typing lists the matching settings across every page; a tap opens that page with the row
    // highlighted. Navigation only: nothing is switched from the list.
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(query) { if (query.isBlank()) emptyList() else settingsFound(query, gold = false, pages = SHOWN_PAGES) }
    Page {
        item { SettingsSearchField(query) { query = it } }
        if (query.isNotBlank()) {
            item { SettingsResults(found, query) { e -> openSetting(e, onPage) } }
        } else GROUPS.forEach { (group, items) ->
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
