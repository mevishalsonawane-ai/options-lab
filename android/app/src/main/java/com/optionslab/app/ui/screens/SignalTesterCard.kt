package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import com.optionslab.app.data.ExportSource
import com.optionslab.app.data.SignalPaperArm
import com.optionslab.app.data.SignalTesterHost
import com.optionslab.app.data.TelegramCapture
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallEval
import com.optionslab.engine.signals.CallParser
import com.optionslab.engine.signals.ChannelScore
import com.optionslab.engine.signals.ChatScan
import com.optionslab.engine.signals.SignalPaperRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Signal tester on Home's Strategies card (15 Oct 2026; [SignalTesterHost]), compact like every row there: its name (a
 * tap: what it is and its panel - the chats followed, each a row of its own (name, one status line, its "paper-trade its calls"
 * switch; a tap on the name: its scorecard, paper record and calls, OK), the chat picker, Needs review, the paste box, the
 * import, the settings - and OK), one status line, and its switch (off by default). Reads [SignalTesterHost.view] and
 * [SignalPaperArm.view] (memory only); every change goes through the hosts off the main thread.
 */
@Composable
internal fun SignalTesterCard() {
    val v by SignalTesterHost.view.collectAsState(Dispatchers.Main.immediate)
    val pv by SignalPaperArm.view.collectAsState(Dispatchers.Main.immediate)
    val tv by TelegramCapture.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var said by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<ExportSource, List<SignalTesterHost.Seen>>?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SignalTesterHost.on(); SignalPaperArm.refresh(); TelegramCapture.refresh() } } }
    fun io(f: suspend () -> String?) { scope.launch { said = withContext(Dispatchers.IO) { runCatching { f() }.getOrElse { "That did not work: ${it.javaClass.simpleName}." } } } }
    // 16 Oct (Boss: "Telegram login button not working"): the Telegram steps tell their results in the login dialog itself
    // ([TelegramCapture.say]); before, they went to this card's line, hidden behind the open dialog.
    fun tio(f: suspend () -> Unit) { scope.launch { withContext(Dispatchers.IO) { runCatching { f() }.onFailure {
        // The card left the screen meanwhile: not an error to show (the step itself carries on or stops on its own).
        if (it is kotlinx.coroutines.CancellationException) throw it
        TelegramCapture.say("That did not work: ${it.javaClass.simpleName}.") } } } }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            // Read and scanned off the main thread; the picker then lists every chat in the file (titles and counts only).
            val got: Any = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { s -> readAtMost(s, MAX_IMPORT + 1) } ?: return@runCatching "That file could not be read."
                    if (bytes.size > MAX_IMPORT) return@runCatching "That file is over 10 MB: export one chat only (JSON, no media), or a shorter period."
                    val src = ExportSource.of(String(bytes, Charsets.UTF_8))
                    src to SignalTesterHost.scan(src)
                }.getOrElse { (it as? com.optionslab.engine.signals.TelegramExport.Bad)?.message ?: "That file could not be read: ${it.javaClass.simpleName}." }
            }
            @Suppress("UNCHECKED_CAST")
            if (got is Pair<*, *>) picking = got as Pair<ExportSource, List<SignalTesterHost.Seen>> else said = got as String
        }
    }
    SignalTesterCardContent(v, pv, said, tv = tv, a = SignalActions(
        onSwitch = { on -> io { SignalTesterHost.setOn(on) } },
        allow = { n -> io { SignalTesterHost.allow(n) } },
        disallow = { n -> io { SignalTesterHost.disallow(n) } },
        paste = { ch, text, at, approx -> io { SignalTesterHost.paste(ch, text, at, approx) } },
        importFile = { runCatching { importer.launch(arrayOf("application/json", "text/plain", "*/*")) } },
        confirm = { id, d -> io { SignalTesterHost.confirm(id, d) } },
        confirmFollowUp = { id -> io { SignalTesterHost.confirmFollowUp(id) } },
        delay = { s -> io { SignalTesterHost.setDelay(s); null } },
        validity = { m -> io { SignalTesterHost.setValidity(m); null } },
        paper = { ch, on -> io { SignalPaperArm.setOn(ch, on) } },
        paperExit = { e -> io { SignalPaperArm.setExit(e) } },
        paperLimits = { n, cap -> io { SignalPaperArm.setLimits(n, cap) } },
        now = { com.optionslab.app.data.Market.now().toLocalDateTime().withNano(0) },
        tg = TelegramActions(
            keys = { id, hash -> tio { TelegramCapture.saveKeys(id, hash) } }, login = { tio { TelegramCapture.startLogin() } },
            phone = { x -> tio { TelegramCapture.phone(x) } }, code = { x -> tio { TelegramCapture.code(x) } },
            password = { x -> tio { TelegramCapture.password(x) } }, capture = { on -> tio { TelegramCapture.setCapture(on) } },
            window = { f, t -> tio { TelegramCapture.setWindow(f, t) } }, channels = { tio { TelegramCapture.loadChannels() } },
            check = { id -> tio { TelegramCapture.check(id) } }, select = { m -> tio { TelegramCapture.select(m) } },
            history = { id -> tio { TelegramCapture.fetchHistory(id) } }, logOut = { tio { TelegramCapture.logOut() } },
            retry = { tio { TelegramCapture.retryLogin() } }, notListed = { tio { TelegramCapture.notListed() } },
        ),
    ))
    picking?.let { (src, seen) ->
        ChatPickerDialog(seen, v.allow, onCheck = { title ->
            // "Check this chat": that one chat only, on Boss's tap.
            scope.launch {
                val x = withContext(Dispatchers.IO) { runCatching { SignalTesterHost.check(src, title) }.getOrNull() } ?: return@launch
                picking = picking?.let { (s0, l) -> s0 to l.map { if (it.title == title) x else it } }
            }
        }, onOk = { chosen -> picking = null; if (chosen.isNotEmpty()) io { SignalTesterHost.follow(src, chosen) } }) { picking = null }
    }
}

private const val MAX_IMPORT = 10 * 1024 * 1024

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

/** What the card's controls do (the screen passes the hosts'; tests pass their own). */
internal class SignalActions(
    val onSwitch: (Boolean) -> Unit = {}, val allow: (String) -> Unit = {}, val disallow: (String) -> Unit = {},
    val paste: (String, String, LocalDateTime, Boolean) -> Unit = { _, _, _, _ -> }, val importFile: () -> Unit = {},
    val confirm: (String, CallParser.Draft?) -> Unit = { _, _ -> }, val confirmFollowUp: (String) -> Unit = {},
    val delay: (Int) -> Unit = {}, val validity: (Int) -> Unit = {},
    val paper: (String, Boolean) -> Unit = { _, _ -> }, val paperExit: (SignalPaperRules.Exit) -> Unit = {},
    val paperLimits: (Int, Double) -> Unit = { _, _ -> }, val now: () -> LocalDateTime = { LocalDateTime.now().withNano(0) },
    val tg: TelegramActions = TelegramActions(),
)

/** The Telegram login's controls (the screen passes [TelegramCapture]'s, off the main thread; tests pass their own). */
internal class TelegramActions(
    val keys: (String, String) -> Unit = { _, _ -> }, val login: () -> Unit = {}, val phone: (String) -> Unit = {}, val code: (String) -> Unit = {},
    val password: (String) -> Unit = {}, val capture: (Boolean) -> Unit = {}, val window: (LocalTime, LocalTime) -> Unit = { _, _ -> },
    val channels: () -> Unit = {}, val check: (Long) -> Unit = {}, val select: (Map<Long, String>) -> Unit = {}, val history: (Long) -> Unit = {},
    val logOut: () -> Unit = {}, val retry: () -> Unit = {},
    /** OK tapped before any channel was listed (16 Oct): nothing changes, and Boss is told why. */
    val notListed: () -> Unit = {},
)

private fun rs(x: Double) = ChannelScore.rs(x)

/** The one status line. */
internal fun signalStatusLine(v: SignalTesterHost.View, pv: SignalPaperArm.View = SignalPaperArm.View()): String =
    (if (v.on) "On" else "Off") + " · ${SignalTesterHost.NOTE}" + if (!v.on) "" else
        " · ${v.allow.size} chat${if (v.allow.size == 1) "" else "s"} · ${v.recs.count { it.kind == SignalTesterHost.CALL }} calls" +
            (if (v.review.isNotEmpty()) " · ${v.review.size} to review" else "") +
            (pv.on.count { it.value }.takeIf { it > 0 }?.let { " · $it on paper" } ?: "")

/** What the name's dialog says first. */
internal fun signalExplanation(): List<String> = listOf(
    "The Signal tester records the option calls of the Telegram chats you follow and scores each chat honestly, in shadow - it never " +
        "places, changes or skips an order by itself.",
    "Paper trading a chat's calls is your choice per chat (its switch, off by default): ${SignalPaperRules.BOSS_CHOICE}. Its live copy (\"Telegram calls\") " +
        "starts off and in shadow - your choice, with the PIN; the shadow scorecard keeps running beside it.",
    "Signal source: the chat's call, its entry read on the option's own premium - NOT the default (the app's own rules signal on the " +
        "underlying); reported apart.",
    "How a call is tested: bought (in shadow) when the premium trades at or above the entry within the window, after your reaction " +
        "delay, with the paper slippage and real charges for 1 lot; out at T1, T2, T3, as given, or with a stop (the chat's, else our −20%); " +
        "square-off 15:15 (15:05 on the index's expiry day).",
    "Messages come from your Telegram login inside the app (read-only: only the channels you select; each post read as it arrives), a " +
        "Telegram Desktop export (pick the chats to follow from its list), Share from Telegram to IraAlgo, or the paste box - no " +
        "notification reading. A channel re-posting a call with ticks, or \"T1 done\" / \"SL hit\" under it, only notes that call (never a " +
        "new trade); the scorecard checks those claims against the real prices. Only calls and follow-ups are used; chatter, adverts and results posts are ignored. Unclear ones go " +
        "to Needs review; your fixes teach the model.",
    "Private: kept encrypted on this phone only, outside the backup and the GitHub uploads; message text only for calls.",
)

@Composable
internal fun SignalTesterCardContent(v: SignalTesterHost.View, pv: SignalPaperArm.View, said: String?, a: SignalActions,
                                     tv: TelegramCapture.View = TelegramCapture.View()) {
    val p = LocalPalette.current
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ExplainedName("Signal tester", signalExplanation(), extra = { SignalPanel(v, pv, said, a, tv) })
            Text(keepNumbersWhole(signalStatusLine(v, pv)), style = Type.bodySmall.copy(color = if (v.on) p.verdigris else p.inkFaint, fontSize = 12.sp))
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Switch the Signal tester" },
            checked = v.on,
            onCheckedChange = { x -> a.onSwitch(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}

/** A followed chat's one status line: its shadow score, and its paper trading when on. */
internal fun chatStatusLine(c: ChannelScore.Card, pv: SignalPaperArm.View): String {
    val paper = if (!pv.isOn(c.channel)) "paper off" else {
        val closed = pv.closedOf(c.channel)
        "paper on: ${pv.openOf(c.channel).size} open, ${closed.size} closed ${rs(closed.sumOf { it.net ?: 0.0 })}"
    }
    return ChannelScore.line(c) + " · " + paper
}

/** The panel inside the name's dialog: the chats followed (a row each), then the tools. */
@Composable
internal fun SignalPanel(v: SignalTesterHost.View, pv: SignalPaperArm.View, said: String?, a: SignalActions, tv: TelegramCapture.View = TelegramCapture.View()) {
    val p = LocalPalette.current
    var channel by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    Column {
        said?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 6.dp)) }
        // The Telegram login, compact: its name (a tap: every step, in its dialog), one status line, its capture switch.
        // 16 Oct: also when the library cannot load on this phone, to say why (a 32-bit phone, a library that failed).
        if (tv.available || tv.why != null) TelegramRow(tv, v, a.tg)
        Text("Chats followed", style = Type.label)
        if (v.cards.isEmpty()) Text("None yet: pick chats from an export, or add one by name.", style = Type.bodySmall)
        v.cards.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Chat ${c.channel}" }
                    .clickable(role = Role.Button) { channel = c.channel }.padding(vertical = 4.dp)) {
                    Text(c.channel, style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(keepNumbersWhole(chatStatusLine(c, pv)), style = Type.bodySmall)
                }
                Switch(modifier = Modifier.semantics { contentDescription = "Paper-trade the calls of ${c.channel}" }, checked = pv.isOn(c.channel),
                    onCheckedChange = { x -> a.paper(c.channel, x) },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card))
            }
        }
        val tools = listOf("chats" to "Chats seen (${v.seen.size})", "review" to "Needs review (${v.review.size}${if (v.pending > 0) ", ${v.pending} with the model" else ""})",
            "allow" to "Add or remove a chat by name", "paste" to "Paste a message", "import" to "Import a Telegram export", "settings" to "Settings")
        tools.forEach { (k, label) ->
            TextButton({ if (k == "import") a.importFile() else dialog = k }, Modifier.heightIn(min = 48.dp)) { Text(keepNumbersWhole(label)) }
        }
    }
    channel?.let { ch -> ExplainDialog(ch, channelLines(v, ch, pv)) { channel = null } }
    when (dialog) {
        "chats" -> ChatPickerDialog(v.seen, v.allow, onOk = { chosen ->
            dialog = null
            val now = v.allow.map { SignalTesterHost.norm(it) }.toSet()
            chosen.filter { SignalTesterHost.norm(it) !in now }.forEach { a.allow(it) }
            v.allow.filter { ch -> chosen.none { SignalTesterHost.norm(it) == SignalTesterHost.norm(ch) } && v.seen.any { SignalTesterHost.norm(it.title) == SignalTesterHost.norm(ch) } }
                .forEach { a.disallow(it) }
        }) { dialog = null }
        "review" -> ReviewDialog(v, a) { dialog = null }
        "allow" -> AllowDialog(v, a) { dialog = null }
        "paste" -> PasteDialog(v, a) { dialog = null }
        "settings" -> SignalSettingsDialog(v, pv, a) { dialog = null }
    }
}

/**
 * The chat picker: every chat a source has seen, by TITLE only; Boss ticks the channels to follow (none ticked at first). [onCheck]
 * (while an export is open): "Check this chat" counts that one chat's recent calls, on Boss's tap only, and shows the "looks like a
 * trade-call channel" badge when they are mostly calls. OK follows the ticked ones.
 */
@Composable
internal fun ChatPickerDialog(seen: List<SignalTesterHost.Seen>, followed: List<String>, onCheck: ((String) -> Unit)? = null,
                              onOk: (Set<String>) -> Unit, empty: String = "No chats seen yet: import a Telegram Desktop export.", close: () -> Unit) {
    // Ticked by name (normalised), so a chat followed before stays ticked when the list fills in later; nothing else is ticked.
    var ticked by remember { mutableStateOf(followed.map { SignalTesterHost.norm(it) }.toSet()) }
    fun on(t: String) = SignalTesterHost.norm(t) in ticked
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = close,
        title = { Text("Chats to follow", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(READS_ONLY, style = Type.bodySmall)
                if (seen.isEmpty()) Text(empty, style = Type.bodySmall)
                seen.sortedByDescending { it.stats?.share ?: -1.0 }.forEach { c ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) {
                        val n = SignalTesterHost.norm(c.title)
                        ticked = if (n in ticked) ticked - n else ticked + n
                    }.semantics { contentDescription = "Follow ${c.title}" }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on(c.title), onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                            c.stats?.let { st -> Text(keepNumbersWhole(st.line + if (st.looksLikeCalls) " · ${ChatScan.BADGE}" else ""), style = Type.bodySmall) }
                        }
                        if (onCheck != null && c.stats == null) TextButton({ onCheck(c.title) },
                            Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Check ${c.title}" }) { Text("Check this chat") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onOk(seen.map { it.title }.filter { on(it) }.toSet()) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Follow the ticked chats" }) { Text("OK") } },
        dismissButton = { TextButton(close, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** Said in the picker and the settings (Boss, 15 Oct: "Telegram should only read from the particular channel"). */
internal const val READS_ONLY = "IraAlgo reads only the channels you select. It never reads your private chats or groups, never sends messages, " +
    "never marks anything as read. Nothing is read until you pick a channel."

/** A chat's dialog: its scorecard, its paper record, then its messages, newest first. */
internal fun channelLines(v: SignalTesterHost.View, ch: String, pv: SignalPaperArm.View = SignalPaperArm.View()): List<String> =
    (v.cards.firstOrNull { it.channel == ch }?.let { ChannelScore.details(it) }.orEmpty()) +
        listOfNotNull(if (pv.isOn(ch)) "Paper trading its calls: on - ${SignalPaperRules.BOSS_CHOICE}. ${pv.settings.exit.words}; " +
            "at most ${pv.settings.maxPerDay} a day; paused for the day at a ${rs(-pv.settings.lossCap)} paper loss." else null,
            pv.statusOf(ch)?.let { "Paper: $it" }) +
        monthLines(v, ch, pv) +
        (pv.openOf(ch) + pv.closedOf(ch).takeLast(20).reversed()).map { paperLine(it) } +
        v.callsOf(ch).take(80).map { callLine(it) }

/** [ch]'s paper month against Boss's pass marks (once its paper trading was switched on). */
internal fun monthLines(v: SignalTesterHost.View, ch: String, pv: SignalPaperArm.View, today: java.time.LocalDate = java.time.LocalDate.now()): List<String> {
    val since = pv.sinceOf(ch) ?: return emptyList()
    val marks = pv.monthCheck(ch, v.cards.firstOrNull { it.channel == ch })
    val day = java.time.temporal.ChronoUnit.DAYS.between(since, today) + 1
    return listOf("Month check (paper since $since, day ${minOf(day, SignalPaperRules.MONTH_DAYS)} of ${SignalPaperRules.MONTH_DAYS}): ${SignalPaperRules.monthLine(marks)}") +
        marks.map { m -> (when (m.ok) { true -> "✓ "; false -> "✗ "; null -> "… " }) + "${m.name}: ${m.detail}" }
}

/** One paper trade in words. */
internal fun paperLine(p: SignalPaperArm.Pos): String =
    "Paper ${p.entryTime.toLocalDate()} ${p.entryTime.toLocalTime()} · ${p.symbol} ${p.qty} @ ${CallParser.num(p.entry)} · stop ${CallParser.num(p.stop)}" +
        (if (p.ourStop) " (our stop)" else "") + if (p.open) " · open" else " · out @ ${CallParser.num(p.exit ?: 0.0)} (${p.why}) ${rs(p.net ?: 0.0)}"

/** One message in words: time, what it was, its status and would-be P&L, how it came. */
internal fun callLine(r: SignalTesterHost.Rec): String {
    val head = "${r.at.toLocalDate()} ${r.at.toLocalTime()} · "
    val body = when (r.kind) {
        SignalTesterHost.CALL -> (r.draft?.line() ?: "call") + " · " + callStatus(r)
        SignalTesterHost.FOLLOW -> "follow-up: ${r.follow?.kind?.words ?: "?"}" + (r.follow?.targetNo?.let { if (it == 99) " (all)" else " (T$it)" } ?: "") +
            (r.verdict?.let { " - ${it.words}" } ?: "")
        SignalTesterHost.NOT -> "not a call: ${r.reason ?: "?"}"
        SignalTesterHost.PENDING -> "waiting for the language model"
        else -> "needs review: " + r.unsure.joinToString("; ").ifEmpty { r.reason ?: "unclear" }
    }
    return head + body + " · " + r.sourceWords + (if (r.edited) " · edited later (not scored)" else "") +
        (r.editedAt?.let { " · edited after posting (at ${it.toLocalTime()}; scored as first posted)" } ?: "")
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
private fun SignalSettingsDialog(v: SignalTesterHost.View, pv: SignalPaperArm.View, a: SignalActions, close: () -> Unit) {
    ExplainDialog("Signal tester settings", listOf(
        READS_ONLY,
        "The shadow test: the reaction delay before a call is bought and the window it may trigger in (every chat).",
        "Paper trading (only the chats you switch on): ${SignalPaperRules.BOSS_CHOICE}. 1 lot, MIS; the stop rests in the paper book (the " +
            "chat's, else our −20%); square-off 15:15, or 15:05 on the index's expiry day.",
    ), extra = {
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
        Text("Paper exit rule", style = Type.label)
        SignalPaperRules.Exit.entries.forEach { e ->
            Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.paperExit(e) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = pv.settings.exit == e, onClick = { a.paperExit(e) }); Text(e.words, style = Type.bodySmall)
            }
        }
        Text("Paper trades a day per chat", style = Type.label)
        Row {
            listOf(1, 2, 3, 5).forEach { n ->
                Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.paperLimits(n, pv.settings.lossCap) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = pv.settings.maxPerDay == n, onClick = { a.paperLimits(n, pv.settings.lossCap) }); Text("$n", style = Type.bodySmall)
                }
            }
        }
        Text("Daily paper loss cap per chat", style = Type.label)
        Row {
            listOf(1_000.0, 2_000.0, 3_000.0, 5_000.0).forEach { cap ->
                Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.paperLimits(pv.settings.maxPerDay, cap) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = pv.settings.lossCap == cap, onClick = { a.paperLimits(pv.settings.maxPerDay, cap) })
                    Text(keepNumbersWhole("₹%,.0f".format(java.util.Locale.ENGLISH, cap)), style = Type.bodySmall)
                }
            }
        }
    }, onClose = close)
}

/** The Telegram login's row: name (a tap: its dialog), one status line, the capture switch. */
@Composable
internal fun TelegramRow(tv: TelegramCapture.View, v: SignalTesterHost.View, a: TelegramActions) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Telegram login" }.clickable(role = Role.Button) { open = true }
            .padding(vertical = 4.dp)) {
            Text("Telegram login", style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Text(keepNumbersWhole(tv.line), style = Type.bodySmall)
        }
        Switch(modifier = Modifier.semantics { contentDescription = "Capture the selected Telegram channels" }, checked = tv.capture,
            enabled = tv.loggedIn, onCheckedChange = { x -> a.capture(x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card))
    }
    if (open) TelegramDialog(tv, v, a) { open = false }
}

/** The capture windows offered (IST). */
internal val TG_WINDOWS: List<Pair<LocalTime, LocalTime>> = listOf(LocalTime.of(9, 0) to LocalTime.of(15, 35), LocalTime.of(8, 30) to LocalTime.of(15, 40))

/**
 * Every Telegram step in one dialog with a single OK: the api keys (once), the login (phone, code, 2-step password), the channels
 * (titles only, nothing selected at first), each selected channel's history, the capture window, and "Log out of Telegram".
 */
@Composable
internal fun TelegramDialog(tv: TelegramCapture.View, v: SignalTesterHost.View, a: TelegramActions, close: () -> Unit) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val selected = v.tg
    val masked = androidx.compose.ui.text.input.PasswordVisualTransformation()
    ExplainDialog("Telegram login", listOf(
        READS_ONLY,
        "Read-only: IraAlgo never sends, joins, leaves, reacts or marks anything as read. Your api_id and api_hash (from my.telegram.org → " +
            "API development tools) and the login stay encrypted on this phone only; your phone number, code and password go only to Telegram.",
        "Status: ${tv.line}." + (tv.hint?.let { " The code / password hint: $it." } ?: ""),
    ), extra = {
        // 16 Oct (Boss: "Telegram login button not working"): every step's result, here, next to its button; a visible busy state
        // while Telegram starts, and what to check (with "Try again") when it does not answer.
        if (tv.busy) Row(Modifier.padding(bottom = 6.dp).semantics { contentDescription = "Telegram is starting" }, verticalAlignment = Alignment.CenterVertically) {
            com.optionslab.app.ui.components.CompassSpinner(Modifier.size(16.dp))
            Text("Starting Telegram…", style = Type.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
        // While starting, the spinner's own words stand in for the "Starting Telegram…" said (what came before it stays).
        (if (tv.busy) tv.said?.removeSuffix("Starting Telegram…")?.trim()?.ifEmpty { null } else tv.said)?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 6.dp)) }
        val auth = tv.auth
        when {
            !tv.available -> Unit
            !tv.keys -> {
                OutlinedTextField(apiId, { apiId = it.filter { c -> c.isDigit() }.take(12) }, singleLine = true, label = { Text("api_id") },
                    modifier = Modifier.semantics { contentDescription = "api_id" })
                OutlinedTextField(apiHash, { apiHash = it.trim().take(40) }, singleLine = true, label = { Text("api_hash") }, visualTransformation = masked,
                    modifier = Modifier.semantics { contentDescription = "api_hash" })
                TextButton({ a.keys(apiId, apiHash); apiHash = "" }, Modifier.heightIn(min = 48.dp)) { Text("Save keys") }
            }
            auth == com.optionslab.engine.signals.TdSession.Auth.NEED_PHONE || auth == com.optionslab.engine.signals.TdSession.Auth.NEED_CODE ||
                auth == com.optionslab.engine.signals.TdSession.Auth.NEED_PASSWORD -> {
                val (label, send) = when (auth) {
                    com.optionslab.engine.signals.TdSession.Auth.NEED_PHONE -> "Phone number (+91…)" to a.phone
                    com.optionslab.engine.signals.TdSession.Auth.NEED_CODE -> "Login code" to a.code
                    else -> "2-step verification password" to a.password
                }
                OutlinedTextField(entry, { entry = it.take(64) }, singleLine = true, label = { Text(label) },
                    visualTransformation = if (auth == com.optionslab.engine.signals.TdSession.Auth.NEED_PASSWORD) masked
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.semantics { contentDescription = label })
                TextButton({ if (entry.isNotBlank()) { send(entry); entry = "" } }, Modifier.heightIn(min = 48.dp)) { Text("Send") }
            }
            tv.stuck -> TextButton(a.retry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            // Starting: the spinner above, no second "Log in" to tap meanwhile.
            !tv.loggedIn -> if (!tv.busy) TextButton(a.login, Modifier.heightIn(min = 48.dp)) { Text("Log in to Telegram") }
            else -> {
                TextButton({ a.channels(); picking = true }, Modifier.heightIn(min = 48.dp)) { Text("Choose channels (${selected.size} selected)") }
                // 16 Oct: what is selected now, in words (the diagnostics read the same store).
                Text(keepNumbersWhole(if (selected.isEmpty()) "No channel selected yet: nothing is read until you tick one and tap OK."
                    else "Selected (read on arrival): " + selected.values.joinToString(", ") + "."), style = Type.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp).semantics { contentDescription = "Selected channels" })
                selected.forEach { (id, title) ->
                    TextButton({ a.history(id) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "History of $title" }) {
                        Text(keepNumbersWhole("Read $title's history since 20 Nov 2024"))
                    }
                }
                Text("Capture window (IST, trading days)", style = Type.label)
                TG_WINDOWS.forEach { (f, t) ->
                    Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.window(f, t) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tv.from == f && tv.to == t, onClick = { a.window(f, t) }); Text(keepNumbersWhole("$f-$t"), style = Type.bodySmall)
                    }
                }
            }
        }
        if (tv.keys || tv.loggedIn) TextButton(a.logOut, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Log out of Telegram" }) {
            Text("Log out of Telegram")
        }
    }, onClose = close)
    if (picking) {
        // Titles only; nothing ticked but what Boss selected before. "Check this chat" counts one chat's recent posts, on his tap.
        // 16 Oct (Boss: channels chosen but "0 channels selected"): rows by id - the list as it comes in, plus every channel
        // selected before that is not in it (still loading, archived, renamed) - so OK never drops a selected channel, and OK
        // before anything is listed says so in plain words instead of silently changing nothing.
        val rows = telegramRows(tv.channels, selected)
        val seen = rows.values.map { t -> v.seen.firstOrNull { SignalTesterHost.norm(it.title) == SignalTesterHost.norm(t) && it.stats != null }
            ?: SignalTesterHost.Seen(t, null, SignalTesterHost.TELEGRAM) }
        // Ticked by the row's title (a renamed channel's new one), so a selected channel is never shown unticked and dropped.
        ChatPickerDialog(seen, rows.filterKeys { it in selected }.values.toList(), onCheck = { title -> rows.entries.firstOrNull { it.value == title }?.let { a.check(it.key) } },
            onOk = { chosen -> picking = false; val m = telegramChosen(rows, chosen); if (m == null) a.notListed() else a.select(m) },
            empty = "Your channel list is still coming in from Telegram (a few seconds). Wait for it here, or close and tap Choose channels again.") { picking = false }
    }
}

/** The picker's rows: the channels listed now, and the ones selected before that are not listed (16 Oct). */
internal fun telegramRows(listed: List<com.optionslab.engine.signals.TdSession.Channel>, selected: Map<Long, String>): Map<Long, String> {
    val now = listed.associate { it.id to it.title }
    return now + selected.filterKeys { it !in now }
}

/** The ticked rows by id; null when there was nothing to pick from (the list not in yet: nothing changes, Boss is told). */
internal fun telegramChosen(rows: Map<Long, String>, chosen: Set<String>): Map<Long, String>? =
    if (rows.isEmpty()) null else rows.filterValues { it in chosen }
