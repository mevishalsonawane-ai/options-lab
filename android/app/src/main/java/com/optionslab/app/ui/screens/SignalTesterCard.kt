package com.optionslab.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.SignalTesterHost
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallEval
import com.optionslab.engine.signals.CallParser
import com.optionslab.engine.signals.ChannelScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Signal tester on Home's Strategies card (15 Oct 2026; [SignalTesterHost]), compact like every row there: its name (a
 * tap: what it is - "shadow — never trades" - and its panel: the channels with their scorecards (a tap on one: its calls),
 * Needs review, the allow-list, the paste box, the import, the settings - and OK), one status line, and its switch (off by
 * default). Reads [SignalTesterHost.view] (memory only); every change goes through the host off the main thread.
 */
@Composable
internal fun SignalTesterCard() {
    val v by SignalTesterHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var said by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SignalTesterHost.on() } } }
    LaunchedEffect(v.listen) { access = withContext(Dispatchers.IO) { SignalTesterHost.accessGranted(ctx) } }
    fun io(f: () -> String?) { scope.launch { said = withContext(Dispatchers.IO) { runCatching { f() }.getOrElse { "That did not work: ${it.javaClass.simpleName}." } } } }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { s -> readAtMost(s, MAX_IMPORT + 1) } ?: return@io "That file could not be read."
            if (bytes.size > MAX_IMPORT) "That file is over 40 MB: export one chat only (JSON, no media)."
            else SignalTesterHost.importExport(String(bytes, Charsets.UTF_8))
        }
    }
    SignalTesterCardContent(v, access, said, SignalActions(
        onSwitch = { on -> io { SignalTesterHost.setOn(on) } },
        onListen = { on -> io { SignalTesterHost.setListen(ctx, on) } },
        openAccess = { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        allow = { n -> io { SignalTesterHost.allow(n) } },
        disallow = { n -> io { SignalTesterHost.disallow(n) } },
        paste = { ch, text, at, approx -> io { SignalTesterHost.paste(ch, text, at, approx) } },
        importFile = { runCatching { importer.launch(arrayOf("application/json", "text/plain", "*/*")) } },
        confirm = { id, d -> io { SignalTesterHost.confirm(id, d) } },
        confirmFollowUp = { id -> io { SignalTesterHost.confirmFollowUp(id) } },
        delay = { s -> io { SignalTesterHost.setDelay(s); null } },
        validity = { m -> io { SignalTesterHost.setValidity(m); null } },
        now = { com.optionslab.app.data.Market.now().toLocalDateTime().withNano(0) },
    ))
}

private const val MAX_IMPORT = 40 * 1024 * 1024

/** Up to [max] bytes of [s] (a longer file stops there: the caller refuses it). */
internal fun readAtMost(s: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < max) {
        val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** What the card's controls do (the screen passes the host's; tests pass their own). */
internal class SignalActions(
    val onSwitch: (Boolean) -> Unit = {}, val onListen: (Boolean) -> Unit = {}, val openAccess: () -> Unit = {},
    val allow: (String) -> Unit = {}, val disallow: (String) -> Unit = {},
    val paste: (String, String, LocalDateTime, Boolean) -> Unit = { _, _, _, _ -> }, val importFile: () -> Unit = {},
    val confirm: (String, CallParser.Draft?) -> Unit = { _, _ -> }, val confirmFollowUp: (String) -> Unit = {},
    val delay: (Int) -> Unit = {}, val validity: (Int) -> Unit = {}, val now: () -> LocalDateTime = { LocalDateTime.now().withNano(0) },
)

private fun rs(x: Double) = ChannelScore.rs(x)

/** The one status line. */
internal fun signalStatusLine(v: SignalTesterHost.View): String =
    (if (v.on) "On" else "Off") + " · ${SignalTesterHost.NOTE}" + if (!v.on) "" else
        " · ${v.allow.size} channel${if (v.allow.size == 1) "" else "s"} · ${v.recs.count { it.kind == SignalTesterHost.CALL }} calls" +
            (if (v.review.isNotEmpty()) " · ${v.review.size} to review" else "")

/** What the name's dialog says first. */
internal fun signalExplanation(): List<String> = listOf(
    "Shadow — never trades. It records the option calls of the Telegram channels you allow-list and scores each channel honestly; " +
        "it never places, changes or skips an order, paper or live. Trading on any of it would be a separate decision, after research and your PIN rules.",
    "Signal source: the channel's call, its entry read on the option's own premium - NOT the default (the app's own rules signal on the " +
        "underlying); reported apart, shadow only.",
    "How a call is tested: bought (on paper, in shadow) when the premium trades at or above the entry within the window, after your reaction " +
        "delay, with the paper slippage and real charges for 1 lot; out at T1, T2, T3, as given, or with a stop (the channel's, else our −20%); " +
        "square-off 15:15 (15:05 on the index's expiry day).",
    "Capture: Telegram notifications (only the channels on your list, only after you turn it on and grant access), Share from Telegram to " +
        "IraAlgo, the paste box, or a Telegram Desktop export (result.json). Unclear messages go to Needs review; your fixes teach the model.",
    "Private: kept encrypted on this phone only, outside the backup and the GitHub uploads; message text only for calls.",
)

@Composable
internal fun SignalTesterCardContent(v: SignalTesterHost.View, access: Boolean, said: String?, a: SignalActions) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName("Signal tester (shadow)", signalExplanation(), extra = { SignalPanel(v, access, said, a) })
            Text(keepNumbersWhole(signalStatusLine(v)), style = Type.bodySmall.copy(color = if (v.on) p.verdigris else p.inkFaint, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Shadow only: switch the Signal tester" },
            checked = v.on,
            onCheckedChange = { x -> a.onSwitch(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** The panel inside the name's dialog: channels, then the tools. */
@Composable
internal fun SignalPanel(v: SignalTesterHost.View, access: Boolean, said: String?, a: SignalActions) {
    var channel by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    Column {
        said?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 6.dp)) }
        Text("Channels", style = Type.label)
        if (v.cards.isEmpty()) Text("None yet: add a channel to the allow-list.", style = Type.bodySmall)
        v.cards.forEach { c ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Channel ${c.channel}" }
                .clickable(role = Role.Button) { channel = c.channel }.padding(vertical = 4.dp)) {
                Text(c.channel, style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                Text(keepNumbersWhole(ChannelScore.line(c)), style = Type.bodySmall)
            }
        }
        val tools = listOf("review" to "Needs review (${v.review.size}${if (v.pending > 0) ", ${v.pending} with the model" else ""})",
            "allow" to "Allow-list", "paste" to "Paste a message", "import" to "Import a Telegram export", "settings" to "Settings")
        tools.forEach { (k, label) ->
            TextButton({ if (k == "import") a.importFile() else dialog = k }, Modifier.heightIn(min = 48.dp)) { Text(keepNumbersWhole(label)) }
        }
    }
    channel?.let { ch -> ExplainDialog(ch, channelLines(v, ch)) { channel = null } }
    when (dialog) {
        "review" -> ReviewDialog(v, a) { dialog = null }
        "allow" -> AllowDialog(v, a) { dialog = null }
        "paste" -> PasteDialog(v, a) { dialog = null }
        "settings" -> SignalSettingsDialog(v, access, a) { dialog = null }
    }
}

/** A channel's dialog: its scorecard, then its messages, newest first. */
internal fun channelLines(v: SignalTesterHost.View, ch: String): List<String> =
    (v.cards.firstOrNull { it.channel == ch }?.let { ChannelScore.details(it) }.orEmpty()) +
        v.callsOf(ch).take(80).map { callLine(it) }

/** One message in words: time, what it was, its status and would-be P&L, how it came. */
internal fun callLine(r: SignalTesterHost.Rec): String {
    val head = "${r.at.toLocalDate()} ${r.at.toLocalTime()} · "
    val body = when (r.kind) {
        SignalTesterHost.CALL -> (r.draft?.line() ?: "call") + " · " + callStatus(r)
        SignalTesterHost.FOLLOW -> "follow-up: ${r.follow?.kind?.words ?: "?"}" + (r.follow?.targetNo?.let { if (it == 99) " (all)" else " (T$it)" } ?: "") +
            when (r.claim) { true -> " - true by the prices"; false -> " - NOT true by the prices"; null -> "" }
        SignalTesterHost.NOT -> "not a call: ${r.reason ?: "?"}"
        SignalTesterHost.PENDING -> "waiting for the language model"
        else -> "needs review: " + r.unsure.joinToString("; ").ifEmpty { r.reason ?: "unclear" }
    }
    return head + body + " · " + r.sourceWords + if (r.edited) " · edited later (not scored)" else ""
}

private fun callStatus(r: SignalTesterHost.Rec): String {
    r.unpriced?.let { return "not priced: $it" }
    val res = r.result ?: return "waiting to be priced"
    val leg = res.legs[ChannelScore.MAIN]
    return res.status.words + (res.fillAt?.let { " · in ${CallParser.num(res.fillRaw ?: 0.0)} at ${it.toLocalTime()}" } ?: "") +
        (leg?.let { " · with a stop ${rs(it.net)} (${it.why})" } ?: "") + (if (res.pre?.movedBefore == true) " · moved before the call" else "") +
        (res.note?.let { " · $it" } ?: "")
}

@Composable
private fun ReviewDialog(v: SignalTesterHost.View, a: SignalActions, close: () -> Unit) {
    var fix by remember { mutableStateOf<SignalTesterHost.Rec?>(null) }
    ExplainDialog("Needs review", if (v.review.isEmpty()) listOf("Nothing to review.") else listOf("Tap a message to fix or confirm it."), extra = {
        v.review.reversed().take(60).forEach { r ->
            Text(keepNumbersWhole(callLine(r)), style = Type.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "Review ${r.id}" }.clickable(role = Role.Button) { fix = r }.padding(vertical = 4.dp))
        }
    }, onClose = close)
    fix?.let { r -> FixDialog(r, a) { fix = null } }
}

/** One message's fields, as guessed, for Boss to fix: OK confirms the call; "Not a call" marks it so; a follow-up can be confirmed. */
@Composable
internal fun FixDialog(r: SignalTesterHost.Rec, a: SignalActions, close: () -> Unit) {
    val d = r.draft ?: r.follow?.ref ?: CallParser.Draft()
    var und by remember { mutableStateOf(d.underlying.orEmpty()) }
    var strike by remember { mutableStateOf(d.strike?.let { CallParser.num(it) }.orEmpty()) }
    var right by remember { mutableStateOf(d.right) }
    var entry by remember { mutableStateOf(d.trigger?.let { CallParser.num(it) }.orEmpty()) }
    var stop by remember { mutableStateOf(d.stopLevel?.let { CallParser.num(it) }.orEmpty()) }
    var targets by remember { mutableStateOf(d.targets.joinToString(" / ") { CallParser.num(it) }) }
    var err by remember { mutableStateOf<String?>(null) }
    fun num(s: String) = s.trim().replace(",", "").toDoubleOrNull()
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = close,
        title = { Text("Fix or confirm", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(r.text ?: "(no text kept)", style = Type.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                if (r.unsure.isNotEmpty()) Text("Unsure: " + r.unsure.joinToString("; "), style = Type.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                OutlinedTextField(und, { und = it.take(20) }, singleLine = true, label = { Text("Index or stock") })
                OutlinedTextField(strike, { strike = it.take(10) }, singleLine = true, label = { Text("Strike") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(Right.CE, Right.PE).forEach { x ->
                        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { right = x }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = right == x, onClick = { right = x }); Text(x.name)
                        }
                    }
                }
                OutlinedTextField(entry, { entry = it.take(10) }, singleLine = true, label = { Text("Entry (above)") })
                OutlinedTextField(stop, { stop = it.take(10) }, singleLine = true, label = { Text("Stop (blank: none given)") })
                OutlinedTextField(targets, { targets = it.take(60) }, singleLine = true, label = { Text("Targets (310 / 345 / 400)") })
                err?.let { Text(it, style = Type.bodySmall.copy(color = LocalPalette.current.oxblood)) }
                if (r.follow != null) TextButton({ a.confirmFollowUp(r.id); close() }, Modifier.heightIn(min = 48.dp)) { Text("It is a follow-up: ${r.follow.kind.words}") }
            }
        },
        confirmButton = {
            TextButton({
                val k = num(strike)
                val e = num(entry)
                val tg = targets.split('/', ',', '-', ' ').mapNotNull { num(it) }
                when {
                    und.isBlank() || k == null || right == null -> err = "Fill in the index or stock, the strike and CE / PE."
                    e == null -> err = "Fill in the entry."
                    else -> {
                        a.confirm(r.id, CallParser.Draft(und.trim().uppercase().replace(" ", ""), k, right, d.expiry, CallParser.Trig.ABOVE, e, null,
                            num(stop), null, tg))
                        close()
                    }
                }
            }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Confirm this call" }) { Text("OK") }
        },
        dismissButton = { TextButton({ a.confirm(r.id, null); close() }, Modifier.heightIn(min = 48.dp)) { Text("Not a call") } },
    )
}

@Composable
private fun AllowDialog(v: SignalTesterHost.View, a: SignalActions, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    ExplainDialog("Allow-list", listOf("Only these Telegram chats are read; every other chat and app is ignored and never stored. " +
        "Type the name exactly as Telegram shows it."), extra = {
        v.allow.forEach { ch ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ch, style = Type.bodySmall, modifier = Modifier.weight(1f))
                TextButton({ a.disallow(ch) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove $ch" }) { Text("Remove") }
            }
        }
        OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Channel name") })
        TextButton({ if (name.isNotBlank()) { a.allow(name.trim()); name = "" } }, Modifier.heightIn(min = 48.dp)) { Text("Add") }
    }, onClose = close)
}

@Composable
private fun PasteDialog(v: SignalTesterHost.View, a: SignalActions, close: () -> Unit) {
    val start = remember { a.now() }
    var ch by remember { mutableStateOf(v.allow.firstOrNull()) }
    var text by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(start.toLocalTime().toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = close,
        title = { Text("Paste a message", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (v.allow.isEmpty()) Text("Add the channel to the allow-list first.", style = Type.bodySmall)
                v.allow.forEach { c ->
                    Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { ch = c }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ch == c, onClick = { ch = c }); Text(c, style = Type.bodySmall)
                    }
                }
                OutlinedTextField(text, { text = it.take(4000) }, label = { Text("Message") }, minLines = 3)
                OutlinedTextField(time, { time = it.take(8) }, singleLine = true, label = { Text("Sent at (today, HH:MM:SS)") })
                err?.let { Text(it, style = Type.bodySmall.copy(color = LocalPalette.current.oxblood)) }
            }
        },
        confirmButton = {
            TextButton({
                val c = ch
                val t = runCatching { LocalTime.parse(time.trim()) }.getOrNull()
                when {
                    c == null -> err = "Pick the channel."
                    text.isBlank() -> err = "Paste the message."
                    t == null -> err = "Write the time as 09:28 or 09:28:04."
                    else -> { a.paste(c, text, start.toLocalDate().atTime(t), time.trim() == start.toLocalTime().toString()); close() }
                }
            }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Record this message" }) { Text("OK") }
        },
        dismissButton = { TextButton(close, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@Composable
private fun SignalSettingsDialog(v: SignalTesterHost.View, access: Boolean, a: SignalActions, close: () -> Unit) {
    val p = LocalPalette.current
    ExplainDialog("Signal tester settings", listOf(
        "Read Telegram notifications: only Telegram's own notifications, only from the channels on your allow-list; everything else is " +
            "ignored at once and never stored. Off by default. Android asks you to grant IraAlgo notification access - it is your tap there.",
    ), extra = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Read Telegram notifications", style = Type.bodySmall, modifier = Modifier.weight(1f))
            Switch(modifier = Modifier.semantics { contentDescription = "Read Telegram notifications" }, checked = v.listen,
                onCheckedChange = { x -> a.onListen(x); if (x && !access) a.openAccess() },
                colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card))
        }
        Text(if (access) "Notification access: granted" else "Notification access: not granted", style = Type.bodySmall)
        TextButton(a.openAccess, Modifier.heightIn(min = 48.dp)) { Text("Open notification access") }
        Text("Reaction delay", style = Type.label)
        Row {
            CallEval.Settings.DELAYS.forEach { s ->
                Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.delay(s) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = v.delaySec == s, onClick = { a.delay(s) }); Text("$s s", style = Type.bodySmall)
                }
            }
        }
        Text("Entry window", style = Type.label)
        Row {
            listOf(5, 10, 15, 30).forEach { m ->
                Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.validity(m) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = v.validityMin == m, onClick = { a.validity(m) }); Text("$m min", style = Type.bodySmall)
                }
            }
        }
    }, onClose = close)
}
