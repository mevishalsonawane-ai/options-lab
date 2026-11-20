package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.launch

/** The button's words (the tests find it by them). */
internal const val SHARE_STATE_LABEL = "Share full settings and state"

/**
 * 20 Nov 2026 (Boss: "Can we create a button where i could share u all the setting details always which could help you analysis"):
 * one tap builds ONE plain-text file of every setting, each arm's live gates, every stop and limit, the full diagnostics and today's
 * decisions ([com.optionslab.app.data.SettingsShare]) and opens the share sheet on it - never clipboard text. The file is built on a
 * worker (the button says "Preparing the file…" meanwhile; a second tap waits), never on the screen's thread, and it only reads.
 * At the top of Settings and beside Copy diagnostics. None in IraGoldAlgo.
 */
@Composable
internal fun ShareStateButton(modifier: Modifier = Modifier, note: Boolean = true, gold: Boolean = com.optionslab.app.BuildConfig.GOLD) {
    if (gold) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        BrassButton(if (busy) "Preparing the file…" else SHARE_STATE_LABEL, Modifier.fillMaxWidth().testTag("share-state"), enabled = !busy) {
            if (busy) return@BrassButton
            busy = true; said = null
            scope.launch {
                said = runCatching { com.optionslab.app.data.SettingsShare.share(ctx) }.getOrElse { "Could not prepare the file just now." }
                busy = false
            }
        }
        if (busy) Note("Gathering every setting, each arm's live gates and today's diary: a few seconds.", Modifier.padding(top = 4.dp))
        else if (note && said == null) Note("One text file for whoever helps you: every setting, each arm's live gates, the stops and today's " +
            "decisions. Your P&L is in it; keys, tokens, your PIN, ids and addresses are not.", Modifier.padding(top = 4.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
