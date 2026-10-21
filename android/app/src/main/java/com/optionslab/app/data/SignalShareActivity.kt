package com.optionslab.app.data

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Share to IraAlgo (15 Oct 2026; the Signal tester, [SignalTesterHost]): Boss shares a message from Telegram (text only) and
 * picks which allow-listed channel it came from; it is recorded as "shared (time approximate)" at the moment of sharing.
 * SHADOW ONLY: nothing is traded. Nothing is recorded without Boss's OK in this dialog, so another app cannot slip a message
 * in; the dialog shows only the channel names Boss typed himself, and no account figure. The tester off or no channel listed:
 * it says so and records nothing.
 */
class SignalShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val at = Market.now().toLocalDateTime().withNano(0)
        val text = sharedText(intent)
        setContent { IraAlgoTheme("system") { ShareDialog(text, at) { finish() } } }
    }

    companion object {
        /** The plain text of a SEND intent (at most 4,000 characters), or null. */
        fun sharedText(i: Intent?): String? =
            if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) i.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(4000)?.ifEmpty { null } else null
    }
}

/** The share dialog: the message, the channel picked from the allow-list, OK records it, Cancel drops it. */
@Composable
internal fun ShareDialog(text: String?, at: LocalDateTime, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var allow by remember { mutableStateOf<List<String>?>(null) }
    var on by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (o, a) = withContext(Dispatchers.IO) { runCatching { SignalTesterHost.on() to SignalTesterHost.view.value.allow }.getOrDefault(false to emptyList()) }
        on = o; allow = a; pick = a.firstOrNull()
    }
    val list = allow
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = done,
        title = { Text("Signal tester (shadow)", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val note = said
                when {
                    note != null -> Text(note, style = Type.bodySmall)
                    text == null -> Text("Share a message's text from Telegram to record it.", style = Type.bodySmall)
                    list == null -> Text("Reading the allow-list…", style = Type.bodySmall)
                    !on || list.isEmpty() -> Text("Switch the Signal tester on and add the channel on Home → Strategies → Signal tester first. Nothing was recorded.",
                        style = Type.bodySmall)
                    else -> {
                        Text("Shadow — never trades. Recorded at ${at.toLocalTime()} (time approximate).", style = Type.bodySmall)
                        Text(text.take(300), style = Type.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                        Text("From which channel?", style = Type.bodySmall)
                        list.forEach { ch ->
                            Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { pick = ch }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = pick == ch, onClick = { pick = ch })
                                Text(ch, style = Type.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.optionslab.app.ui.components.TextButton({
                val ch = pick
                if (said != null || text == null || ch == null || !on) done()
                else scope.launch {
                    said = withContext(Dispatchers.IO) { runCatching { SignalTesterHost.shareNow(ch, text, at) }.getOrElse { "Not recorded." } }
                }
            }, Modifier.heightIn(min = 48.dp)) { Text("OK") }
        },
        dismissButton = { com.optionslab.app.ui.components.TextButton(done, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
