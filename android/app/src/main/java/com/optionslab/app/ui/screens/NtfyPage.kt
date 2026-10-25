package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.NtfyArm
import com.optionslab.app.data.NtfyReader
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The page's title (Settings → Automation → ntfy alerts). */
internal const val NTFY_TITLE = "ntfy alerts"

/** What the page says about the topic's safety (tests read it). */
internal const val NTFY_SECURITY = "Your topic works like a password: anyone who knows it can post a message the app would trade. Use a long random " +
    "topic (20 characters or more) and, if your server supports it, an access token. The topic and token are kept encrypted on this " +
    "phone and never shown, logged or backed up."

/**
 * Settings → Automation → ntfy alerts (25 Oct 2026): the topic typed once (kept encrypted, shown masked), the server (ntfy.sh, or
 * your own, https only), an optional access token and prefix, the On switch (off by default), Boss's limits, the strike rule, its
 * own "Trade live" switch (off; on asks the PIN), a Test button (one fetch: nothing traded, nothing marked read), the open trades
 * and the Alerts list (each message's time, words, what was decided and why). Every read and write runs off the main thread.
 * Not in IraGoldAlgo.
 */
@Composable
fun NtfyPage(model: AppModel) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val v by NtfyArm.view.collectAsState(Dispatchers.Main.immediate)
    val st by NtfyReader.status.collectAsState(Dispatchers.Main.immediate)
    val live by com.optionslab.app.data.ArmLive.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf<NtfyReader.Config?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var askLive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { NtfyArm.refresh() }; cfg = runCatching { NtfyReader.config() }.getOrNull() }
    }
    fun io(block: suspend () -> String) = scope.launch {
        said = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Could not save (${it.javaClass.simpleName})." } }
        cfg = withContext(Dispatchers.IO) { runCatching { NtfyReader.config() }.getOrNull() }
    }
    if (askLive) Reauth(model, onOk = {
        askLive = false
        scope.launch { said = withContext(Dispatchers.IO) { com.optionslab.app.data.ArmLive.setLive(LiveMirror.NTFY_ALERTS, true, pinConfirmed = true) } }
    }, onCancel = { askLive = false }, pinOnly = true,
        why = "Enter your app PIN to let ntfy alerts trade live (only in Live with AI trades on; never in the paper-only period).")
    NtfyContent(v, st, cfg, live.on(LiveMirror.NTFY_ALERTS), said,
        onTopic = { t -> io { NtfyReader.setTopic(t) } },
        onServer = { s -> io { NtfyReader.setServer(s) } },
        onToken = { t -> io { NtfyReader.setToken(t) } },
        onPrefix = { x -> io { NtfyReader.setPrefix(x) } },
        onOn = { on -> io { NtfyArm.setOn(on) } },
        onSettings = { s -> io { NtfyArm.setSettings(s) } },
        onLive = { on -> if (on) askLive = true else io { com.optionslab.app.data.ArmLive.setLive(LiveMirror.NTFY_ALERTS, false, pinConfirmed = false) } },
        onTest = { io { NtfyReader.test() } })
}

/** The page from plain state (tests drive it). */
@Composable
internal fun NtfyContent(v: NtfyArm.View, st: NtfyReader.Status, cfg: NtfyReader.Config?, liveOn: Boolean, said: String?,
                         onTopic: (String) -> Unit, onServer: (String) -> Unit, onToken: (String) -> Unit, onPrefix: (String) -> Unit,
                         onOn: (Boolean) -> Unit, onSettings: (NtfyRules.Settings) -> Unit, onLive: (Boolean) -> Unit, onTest: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("ntfy-page")) {
        com.optionslab.app.ui.SettingSpot("ntfy.setup") {
            LedgerCard(title = NTFY_TITLE) {
                Note("Reads the messages of your ntfy topic, each one once, and buys the index option a clear message names - on paper unless you " +
                    "turn on Trade live. ${NtfyRules.NOTE.replaceFirstChar { it.uppercase() }}: the app does not know whether it is any good.")
                Text(NTFY_SECURITY, style = Type.bodySmall.copy(color = p.oxblood), modifier = Modifier.padding(top = 6.dp).testTag("ntfy-security"))
                Rule(Modifier.padding(vertical = 8.dp))
                var topic by remember { mutableStateOf("") }
                val set = cfg?.topic != null
                Text("Topic: " + NtfyRules.masked(cfg?.topic), style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.testTag("ntfy-topic-state"))
                if (cfg?.topic?.let { NtfyRules.weakTopic(it) } == true)
                    Text("This topic is short or plain: anyone who guesses it can post an alert. Use a long random one.",
                        style = Type.bodySmall.copy(color = p.oxblood), modifier = Modifier.testTag("ntfy-weak"))
                OutlinedTextField(topic, { topic = it.trim().take(64) }, label = { Text(if (set) "New topic (replaces the saved one)" else "Topic") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("ntfy-topic"))
                Row {
                    TextButton({ onTopic(topic); topic = "" }, enabled = topic.isNotBlank(), modifier = Modifier.testTag("ntfy-topic-save")) { Text("Save topic") }
                    if (set) TextButton({ onTopic("") }, modifier = Modifier.testTag("ntfy-topic-clear")) { Text("Clear topic") }
                    TextButton(onTest, enabled = set, modifier = Modifier.testTag("ntfy-test")) { Text("Test") }
                }
                var server by remember(cfg?.server) { mutableStateOf(cfg?.server ?: NtfyRules.DEFAULT_SERVER) }
                OutlinedTextField(server, { server = it.trim().take(200) }, label = { Text("Server (https)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ntfy-server"))
                TextButton({ onServer(server) }, modifier = Modifier.testTag("ntfy-server-save")) { Text("Save server") }
                var token by remember { mutableStateOf("") }
                Text("Access token: " + if (cfg?.token != null) "set (****)" else "none", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
                OutlinedTextField(token, { token = it.trim().take(200) }, label = { Text("Access token (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("ntfy-token"))
                Row {
                    TextButton({ onToken(token); token = "" }, enabled = token.isNotBlank()) { Text("Save token") }
                    if (cfg?.token != null) TextButton({ onToken("") }) { Text("Clear token") }
                }
                var prefix by remember(cfg?.prefix) { mutableStateOf(cfg?.prefix.orEmpty()) }
                OutlinedTextField(prefix, { prefix = it.take(20) }, label = { Text("Only messages starting with (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ntfy-prefix"))
                TextButton({ onPrefix(prefix) }) { Text("Save prefix") }
                said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(top = 4.dp).testTag("ntfy-said")) }
            }
        }
        com.optionslab.app.ui.SettingSpot("ntfy.switches") {
            LedgerCard(Modifier.padding(top = 10.dp)) {
                SwitchRow("On (paper)", "Off by default. Off buys nothing new; what is held still closes by its own exits.", v.on, "ntfy-on", onOn)
                SwitchRow("Trade live", "Off by default. On asks your PIN, and trades live only in Live with AI trades on.", liveOn, "ntfy-live", onLive)
                SwitchRow("Stock options too", "Your signals for NSE F&O stocks (\"X.NS\") buy the option they name. Off: index options only.",
                    v.settings.stocks, "ntfy-stocks") { onSettings(v.settings.copy(stocks = it)) }
                Text("Reader: ${st.said}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.testTag("ntfy-reader"))
                Rule(Modifier.padding(vertical = 8.dp))
                val s = v.settings
                Text("Limits", style = Type.label.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                Stepper("Lots a trade, at most", s.lots, 1, 5, "ntfy-lots") { onSettings(s.copy(lots = it)) }
                Stepper("Trades a day, at most", s.maxPerDay, 1, 10, "ntfy-max") { onSettings(s.copy(maxPerDay = it)) }
                Stepper("Older than this many minutes: not traded", s.staleMin, 1, 60, "ntfy-stale") { onSettings(s.copy(staleMin = it)) }
                Text("Strike when the message names none", style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp))
                Row {
                    for (r in NtfyParser.StrikeRule.entries) TextButton({ onSettings(s.copy(strikeRule = r)) }, modifier = Modifier.testTag("ntfy-strike-${r.key}")) {
                        Text((if (r == s.strikeRule) "● " else "") + r.label)
                    }
                }
                Note("A stop always: the message's, else 20% under the fill. Target: the message's, else 40% over the fill. Out by 15:15 " +
                    "(15:05 on expiry day). It only buys options; sell, exit, close or book closes what it holds on that index, never a short. " +
                    "The kill switch, the day lock, the account guard and the same-direction cap all apply.")
            }
        }
        if (v.open.isNotEmpty()) LedgerCard(Modifier.padding(top = 10.dp).testTag("ntfy-open"), title = "Open") {
            v.open.forEach { o ->
                Text("${o.symbol} ${o.qty} @ ${NtfyParser.num(o.entry)} · stop ${NtfyParser.num(o.stop)}" + (o.target?.let { " · target ${NtfyParser.num(it)}" } ?: "") +
                    (if (o.legacy) " · moved from the removed Telegram arm" else ""), style = Type.bodySmall.copy(color = p.ink))
            }
        }
        LedgerCard(Modifier.padding(top = 10.dp).testTag("ntfy-alerts"), title = "Alerts") {
            if (v.alerts.isEmpty()) Note("No messages read yet.")
            v.alerts.take(50).forEachIndexed { i, a ->
                if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
                Column(Modifier.testTag("ntfy-alert-$i").semantics { contentDescription = "Alert ${a.state}" }) {
                    Text("%02d:%02d %s".format(java.util.Locale.ENGLISH, a.at.hour, a.at.minute, a.at.toLocalDate()) + " · " + a.state,
                        style = Type.label.copy(color = if (a.state == NtfyArm.TRADED) p.oxblood else p.inkSoft, fontSize = 11.sp))
                    Text(a.text, style = Type.bodySmall.copy(color = p.ink))
                    if (a.why.isNotBlank()) Text(a.why, style = Type.bodySmall.copy(color = p.inkSoft))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.label.copy(color = p.ink, fontSize = 14.sp))
            Text(note, style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp))
        }
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag(tag).semantics { contentDescription = "$title: ntfy alerts" })
    }
}

@Composable
private fun Stepper(title: String, value: Int, min: Int, max: Int, tag: String, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
        TextButton({ onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min, modifier = Modifier.testTag("$tag-minus")) { Text("−") }
        Text("$value", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.testTag("$tag-value"))
        TextButton({ onChange((value + 1).coerceAtMost(max)) }, enabled = value < max, modifier = Modifier.testTag("$tag-plus")) { Text("+") }
    }
}
