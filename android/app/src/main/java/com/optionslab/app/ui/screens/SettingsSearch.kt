package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.SettingsIndex

/** The search box's label (tests find it by this). */
internal const val SETTINGS_SEARCH_LABEL = "Search settings"

/** At most this many results are listed (a word as wide as "voice" finds dozens). */
internal const val SETTINGS_SEARCH_MAX = 40

/**
 * The settings matching [query] in this build: IraGoldAlgo's ([gold]) or, in IraAlgo, those on the pages its Settings
 * shows ([pages]: the drawers' keys; Jarvis's page only with Jarvis). Pure: read once per query by the caller.
 */
internal fun settingsFound(query: String, gold: Boolean, pages: Set<String>?): List<SettingsIndex.Entry> =
    SettingsIndex.search(query, gold, pages).take(SETTINGS_SEARCH_MAX)

/**
 * A found setting tapped: its row is asked for ([com.optionslab.app.ui.SettingFocus]: brought into view and pulsed once its
 * page shows it), then [open] shows its page. NAVIGATION ONLY: nothing is switched, set or confirmed here - the row keeps
 * its own PIN, fingerprint or confirmation exactly as before.
 */
internal fun openSetting(e: SettingsIndex.Entry, open: (String) -> Unit) {
    if (!e.isPage) com.optionslab.app.ui.SettingFocus.ask(e.key)
    open(e.page)
}

/** The search box at the top of Settings. */
@Composable
internal fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { onQuery(it.take(60)) }, label = { Text(SETTINGS_SEARCH_LABEL) }, singleLine = true,
            modifier = Modifier.weight(1f))
        if (query.isNotEmpty()) Text("Clear", style = Type.label.copy(color = p.inkSoft, fontSize = 14.sp),
            modifier = Modifier.heightIn(min = 48.dp).clickable { onQuery("") }.padding(start = 12.dp, top = 14.dp, bottom = 14.dp))
    }
}

/**
 * What the search found: each setting's name, its path ("Settings → Security → Unlocking → Fingerprint") and what it does; a
 * tap only navigates there ([onOpen]). Nothing found: said so.
 */
@Composable
internal fun SettingsResults(found: List<SettingsIndex.Entry>, query: String, onOpen: (SettingsIndex.Entry) -> Unit) {
    val p = LocalPalette.current
    if (found.isEmpty()) {
        Note("No setting matches \"${query.trim()}\". Try another word: \"sound\", \"backup\", \"lots\", \"fingerprint\".")
        return
    }
    LedgerCard {
        found.forEachIndexed { i, e ->
            if (i > 0) Rule()
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(e) }.padding(vertical = 10.dp)) {
                Text(keepNumbersWhole(e.title), style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Text(keepNumbersWhole(e.pathText), style = Type.bodySmall.copy(color = p.brass))
                Text(keepNumbersWhole(e.description), style = Type.bodySmall.copy(color = p.inkSoft), maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
