package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.HiddenArms
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.ArmsCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The Hidden arms list's title. */
internal const val HIDDEN_ARMS = "Hidden arms"

/** What the list says first. */
internal const val HIDDEN_NOTE = "Switched off and hidden on your choice (15 Oct). Their code, settings and trades are kept. " +
    "Restore puts one back on its row in one tap - back on PAPER only if it was on (Live still takes your PIN on its row)."

/**
 * The "Hidden arms" list (Boss's 15 Oct cleanup, [HiddenArms]) on Home's Strategies card and the Strategies page: one line with
 * the count; a tap opens the list - each hidden arm with a Restore button (one tap, logged in Autopilot's change timeline), the
 * arms still holding a position (switched off after their exit) and the NIFTY note - and OK closes it. Reads memory only; a
 * restore runs off the main thread.
 */
@Composable
internal fun HiddenArmsEntry() {
    val s by HiddenArms.state.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    HiddenArmsContent(s, said) { key -> scope.launch(Dispatchers.IO) { said = runCatching { HiddenArms.restore(key) }.getOrNull() } }
}

/** The list from plain state ([s]), the last restore's words ([said]) and the restore callback (an arm's key). */
@Composable
internal fun HiddenArmsContent(s: ArmsCleanup.State, said: String? = null, onRestore: (String) -> Unit) {
    if (!s.done) return
    val p = LocalPalette.current
    val listed = ArmsCleanup.listed(s)
    val waiting = ArmsCleanup.HIDDEN.filter { it.key in s.waiting }
    var open by remember { mutableStateOf(false) }
    Rule()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "$HIDDEN_ARMS: ${listed.size}" }
        .clickable(role = Role.Button) { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$HIDDEN_ARMS (${listed.size})", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(if (waiting.isEmpty()) "Switched off on your choice · tap to restore one"
                else "${waiting.size} switching off after ${if (waiting.size == 1) "its" else "their"} exit",
                style = Type.bodySmall.copy(color = if (waiting.isEmpty()) p.inkSoft else p.amber, fontSize = 12.sp))
        }
        Text("›", style = Type.title.copy(color = p.inkFaint))
    }
    if (open) com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(HIDDEN_ARMS, style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(HIDDEN_NOTE, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(bottom = 6.dp))
                if (listed.isEmpty()) Text("Nothing is hidden now.", style = Type.bodySmall)
                listed.forEach { h ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.label, style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                            Text(if (h.key in s.wasOn) "was on · back on paper" else "was off", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                        }
                        TextButton({ onRestore(h.key) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Restore ${h.label}" }) { Text("Restore") }
                    }
                }
                waiting.forEach { h ->
                    Text(keepNumbersWhole("${h.label}: ${ArmsCleanup.WAITING}."), style = Type.bodySmall.copy(color = p.amber), modifier = Modifier.padding(top = 6.dp))
                }
                Text(keepNumbersWhole(ArmsCleanup.NIFTY_NOTE + "."), style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
                said?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton({ open = false }, Modifier.heightIn(min = 48.dp)) { Text("OK") } },
    )
}
