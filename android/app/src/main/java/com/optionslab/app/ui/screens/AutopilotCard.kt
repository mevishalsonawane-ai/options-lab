package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.Autopilot
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.AutoChange
import com.optionslab.ira.AutoGate
import com.optionslab.ira.Experiments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The card's and the sheet's title. */
internal const val AUTOPILOT_TITLE = "Autopilot"
internal const val AUTOPILOT_LOG_TITLE = "Autopilot change log"

/**
 * The Autopilot section (Home → Automation card → Autopilot; its own Home card until 11 Oct): Autopilot learning's level,
 * today's changes, how many are on probation and how many reverted themselves, and the change log with Undo. Memory only on
 * screen; the log is read off the main thread the first time; every Undo and Apply runs off the main thread, and one that
 * raises live risk asks for the PIN. None in GOLD.
 */
@Composable
internal fun AutopilotSection(model: AppModel) {
    if (!Autopilot.enabled()) return
    val book by Autopilot.book.collectAsState(Dispatchers.Main.immediate)
    val mode by Autopilot.mode.collectAsState(Dispatchers.Main.immediate)
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Autopilot.ensureLoaded() } } }
    val today = rememberMarketDay()
    Column(Modifier.fillMaxWidth()) {
        AutopilotCardContent(book, mode, today, onOpen = { open = true })
        // Jarvis's weekly self-report (11 Oct): its sheet from here.
        WeeklyReportButton()
        // S13-S15: every agent's changes today (undo everything today), last month's scorecard, the learning freeze.
        AutopilotSelfButtons()
    }
    if (open) AutopilotLogDialog(model) { open = false }
}

/**
 * Today's market day, following the clock (a minute's poll while the app is in front): computed once, the card's "Today"
 * stayed on the day before after midnight with Home left open (review G5-10). Memory only.
 */
@Composable
internal fun rememberMarketDay(): LocalDate {
    fun now(): LocalDate = runCatching { com.optionslab.app.data.Market.today() }.getOrElse { LocalDate.now() }
    var today by remember { mutableStateOf(now()) }
    com.optionslab.app.ui.PollWhileStarted { while (true) { today = now(); kotlinx.coroutines.delay(60_000) } }
    return today
}

/** The card from plain state (tests drive it without an [AppModel]). */
@Composable
internal fun AutopilotCardContent(book: AutoChange.Book, mode: AutoChange.Mode, today: LocalDate, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val s = remember(book, today) { AutoChange.summary(book, today) }
    val todays = remember(book, today) { book.entries.filter { it.day == today && (it.applied || it.status == AutoChange.Status.OFFERED) }.takeLast(3).reversed() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(AUTOPILOT_TITLE, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        Note("Autopilot learning: ${mode.words}" + when (mode) {
            AutoChange.Mode.OFF -> " - nothing changes by itself."
            AutoChange.Mode.SHADOW_ONLY -> " - shadow changes by itself, the rest asked."
            AutoChange.Mode.SAFE -> " - shadow and paper by itself, live only to lower risk."
        }, Modifier.padding(top = 2.dp))
        Text(if (s.today == 0) "No changes today." else "Today: ${s.today} change${if (s.today == 1) "" else "s"} " +
            "(${s.todayPaper} paper, ${s.todayShadow} shadow, ${s.todayLive} live risk-lowering)",
            style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp).testTag("autopilot-today"))
        todays.forEach { e -> Note("• ${e.change.what}: ${e.change.from} → ${e.change.to}" + if (e.status == AutoChange.Status.OFFERED) " (asked)" else "",
            Modifier.padding(top = 2.dp)) }
        Text("On probation ${s.probation} · kept ${s.kept} · reverted ${s.reverted}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.padding(top = 4.dp).testTag("autopilot-counts"))
        BrassButton("Change log", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft, onClick = onOpen)
    }
}

/** What a tap on the log asks for: Undo, Apply (an asked change) or Revert (a revert asked), each by its entry. */
internal enum class LogAction { UNDO, APPLY, REVERT }

/**
 * Does a tap on [a] for [c] ask for the PIN before anything is done? A Revert always does: a revert is left to Boss only
 * because its agent refused it without the PIN (un-muting where the manager acts on live, a live raise), whatever the change's
 * own scope (review G5-1). An Undo the agent holds for the PIN by what it holds now is caught by [Autopilot.undo]'s answer.
 */
internal fun asksPinFirst(a: LogAction, c: AutoChange.Change): Boolean = when (a) {
    LogAction.UNDO -> AutoChange.undoNeedsPin(c)
    LogAction.APPLY -> c.scope == AutoChange.Scope.LIVE && AutoChange.riskOf(c) == AutoChange.Risk.RAISE
    LogAction.REVERT -> true
}

/** The change log with Boss's hand on it: Undo, Apply and Revert, the PIN first when it raises live risk. */
@Composable
internal fun AutopilotLogDialog(model: AppModel, onClose: () -> Unit) {
    val book by Autopilot.book.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<Pair<LogAction, String>?>(null) }
    val scope = rememberCoroutineScope()
    // The undos the agents hold for the PIN by what they hold now (an un-mute where the manager acts on live, the order flow's
    // CONFIRM in Live): worked out off the main thread, so the button says "PIN" as Autopilot.undo will ask (review G5-2).
    val undoPin by produceState(emptySet<String>(), book) {
        value = withContext(Dispatchers.IO) {
            runCatching { book.entries.filter { it.applied && it.status != AutoChange.Status.REVERT_ASKED && Autopilot.undoNeedsPin(it.id) }.map { it.id }.toSet() }
                .getOrDefault(emptySet())
        }
    }
    fun doIt(a: LogAction, id: String, f: () -> String) {
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { f() }.getOrElse { "Could not do that just now." }
            // An agent that holds it for the PIN after all: the PIN prompt, never a dead end.
            withContext(Dispatchers.Main) { if (msg == Autopilot.PIN_UNDO || msg == Autopilot.PIN_REVERT) pinFor = a to id else said = msg }
        }
    }
    fun tap(a: LogAction, id: String) {
        val e = AutoChange.entry(book, id) ?: return
        if (asksPinFirst(a, e.change) || (a == LogAction.UNDO && id in undoPin)) { pinFor = a to id; return }
        doIt(a, id) {
            when (a) {
                LogAction.UNDO -> Autopilot.undo(id)
                LogAction.APPLY -> Autopilot.acceptAsked(id, pinConfirmed = false)
                LogAction.REVERT -> Autopilot.revertWithPin(id, pinConfirmed = false)
            }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(LocalPalette.current.paper, RoundedCornerShape(16.dp))) {
            AutopilotLogSheet(book, said, onAction = { a, id -> tap(a, id) }, onClose = onClose, undoPin = undoPin)
        }
    }
    pinFor?.let { (a, id) ->
        val what = AutoChange.entry(book, id)?.change?.what ?: "this change"
        Reauth(model, pinOnly = false,
            why = when (a) {
                LogAction.UNDO -> "Enter your app PIN to undo Autopilot's change to $what: putting it back adds live risk."
                LogAction.APPLY -> "Enter your app PIN to apply the change to $what: it adds live risk."
                LogAction.REVERT -> "Enter your app PIN to revert Autopilot's change to $what: it adds live risk."
            },
            onOk = {
                pinFor = null
                doIt(a, id) {
                    when (a) {
                        LogAction.UNDO -> Autopilot.undo(id, pinConfirmed = true)
                        LogAction.APPLY -> Autopilot.acceptAsked(id, pinConfirmed = true)
                        LogAction.REVERT -> Autopilot.revertWithPin(id, pinConfirmed = true)
                    }
                }
            },
            onCancel = { pinFor = null })
    }
}

/**
 * The change log from plain state (tests drive it): newest first, each change with who made it, what moved (from → to), its
 * scope and risk, its status, the reason with the numbers, and its buttons. One scrolling column: it fits a phone held either
 * way and at large font (the title wraps beside Close; each button has its own row).
 */
@Composable
internal fun AutopilotLogSheet(book: AutoChange.Book, said: String?, onAction: (LogAction, String) -> Unit, onClose: (() -> Unit)?,
                               undoPin: Set<String> = emptySet()) {
    val p = LocalPalette.current
    val entries = remember(book) { book.entries.filter { it.status != AutoChange.Status.DECLINED }.reversed() }
    val experiments = remember(book) { book.experiments.sortedWith(compareBy<Experiments.Experiment> { if (it.status == Experiments.Status.RUNNING) 0 else 1 }.thenByDescending { it.startedOn }) }
    val header = remember(book) { AutoGate.header(book) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(AUTOPILOT_LOG_TITLE, style = Type.title.copy(color = p.ink), maxLines = 2, modifier = Modifier.weight(1f).testTag("autopilot-log-title"))
            if (onClose != null) BrassButton("Close", modifier = Modifier.testTag("autopilot-log-close"), tone = p.inkSoft, onClick = onClose)
        }
        Text(header, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp).testTag("autopilot-log-trials"))
        said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
            item(key = "intro") {
                Note("Changes the agents made by themselves, and those asked. Shadow and paper change by themselves; live only ever to lower " +
                    "risk. Each change is first replayed on stored trades (pre-flight) and then judged by a sequential test that keeps or " +
                    "reverts it as soon as the evidence is clear - at the latest after ${AutoChange.PROBATION_N} trades or ${AutoChange.PROBATION_DAYS} " +
                    "trading days. A lesson still building is tried as a paper experiment first. An undo that adds live risk asks for your PIN.")
            }
            if (experiments.isNotEmpty()) items(experiments, key = { "x:" + it.id }) { x -> ExperimentRow(x) }
            if (entries.isEmpty()) item(key = "none") { Note("No changes yet.", Modifier.padding(vertical = 8.dp)) }
            items(entries, key = { "e:" + it.id }) { e -> LogRow(e, e.id in undoPin, onAction) }
        }
    }
}

/** One paper experiment: "experiment" with what it tries, its status, its pairs and the variant's lead, and its sequential test. */
@Composable
private fun ExperimentRow(x: Experiments.Experiment) {
    val p = LocalPalette.current
    val c = x.change
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("experiment-" + x.id)) {
        Text("${c.what}: ${c.from} → ${c.to}", style = Type.body.copy(color = p.ink, fontSize = 14.sp))
        Text("${com.optionslab.ira.AgentLessons.day(x.startedOn)} · experiment · paper only · ${x.status.words}",
            style = Type.label.copy(color = if (x.status == Experiments.Status.LOST) p.oxblood else p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        Text("${x.pairs} pair${if (x.pairs == 1) "" else "s"} on paper trades (each one both ways), the variant ${com.optionslab.ira.AgentLessons.rs(x.lead)} " +
            (if (x.lead >= 0) "ahead" else "behind"), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        if (x.test.isNotEmpty()) Text("Test: ${x.test}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        if (x.note.isNotEmpty()) Text(x.note, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LogRow(e: AutoChange.Entry, agentPin: Boolean, onAction: (LogAction, String) -> Unit) {
    val p = LocalPalette.current
    val c = e.change
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("${c.what}: ${c.from} → ${c.to}", style = Type.body.copy(color = p.ink, fontSize = 14.sp))
        Text("${com.optionslab.ira.AgentLessons.day(e.day)} · ${c.kind.agent} · ${c.scope.words} · ${AutoChange.riskOf(c).words} · ${e.status.words}" +
            (if (e.by != "autopilot") " · by ${e.by}" else ""),
            style = Type.label.copy(color = if (e.status == AutoChange.Status.REVERTED) p.oxblood else p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        Text("Why: ${c.reason}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        if (e.note.isNotEmpty()) Text(e.note, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        if (e.afterN > 0) Text("Since: ${e.afterN} cases, ${com.optionslab.ira.AgentLessons.rs(e.effect)} against what would have happened",
            style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        e.q?.let { q -> Text("Lesson: ${com.optionslab.ira.AgentLessons.qWords(q)}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
        if (e.test.isNotEmpty()) Text("Test: ${e.test}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        if (e.preflight.isNotEmpty()) Text(e.preflight.replaceFirstChar { it.uppercase() },
            style = Type.label.copy(color = if (e.status == AutoChange.Status.SKIPPED) p.oxblood else p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        val pin = c.scope == AutoChange.Scope.LIVE
        if (e.applied && e.status != AutoChange.Status.REVERT_ASKED)
            BrassButton(if (AutoChange.undoNeedsPin(c) || agentPin) "Undo (PIN)" else "Undo", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("undo-" + e.id),
                tone = p.inkSoft) { onAction(LogAction.UNDO, e.id) }
        if (e.status == AutoChange.Status.OFFERED)
            BrassButton(if (pin && AutoChange.riskOf(c) == AutoChange.Risk.RAISE) "Apply (PIN)" else "Apply", Modifier.fillMaxWidth().padding(top = 6.dp),
                tone = p.inkSoft) { onAction(LogAction.APPLY, e.id) }
        if (e.status == AutoChange.Status.REVERT_ASKED)
            BrassButton("Revert (PIN)", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { onAction(LogAction.REVERT, e.id) }
    }
}

/**
 * Settings → Bot settings → Autopilot learning: OFF, SAFE (the default) or SHADOW-ONLY. OFF and SHADOW-ONLY need nothing;
 * SAFE asks for the PIN while the live account is on. Never by voice upward, never from a backup.
 */
@Composable
internal fun AutopilotSettingCard(model: AppModel) {
    if (!Autopilot.enabled()) return
    val mode by Autopilot.mode.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Autopilot.ensureLoaded() } } }
    fun set(m: AutoChange.Mode, confirmed: Boolean) {
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { Autopilot.setMode(m, confirmed) }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = msg }
        }
    }
    LedgerCard(title = "Autopilot learning") {
        AutopilotModeContent(mode, said) { m ->
            scope.launch {
                val needs = withContext(Dispatchers.IO) { runCatching { Autopilot.needsPin(m) }.getOrDefault(true) }
                if (needs) { pin = true } else { set(m, false) }
            }
        }
    }
    if (pin) Reauth(model, pinOnly = false, why = "Enter your app PIN to set Autopilot learning to SAFE while the live account is on: it may then lower live risk by itself.",
        onOk = { pin = false; set(AutoChange.Mode.SAFE, true) }, onCancel = { pin = false })
}

/** The setting's rows from plain state (tests drive it). */
@Composable
internal fun AutopilotModeContent(mode: AutoChange.Mode, said: String?, onPick: (AutoChange.Mode) -> Unit) {
    val order = listOf(AutoChange.Mode.OFF, AutoChange.Mode.SAFE, AutoChange.Mode.SHADOW_ONLY)
    Note("The agents make the changes their lessons point to, within strict bounds, each logged and undoable. SAFE: shadow and paper " +
        "by themselves, live only to lower risk, anything that adds risk asked. SHADOW-ONLY: shadow by themselves, the rest asked. " +
        "Never the PIN, Live, AI-live, the guard, the locks, sizes or orders. \"Turn off autopilot\" by voice always works; turning it up is only here.")
    ParamTokens("Autopilot learning", order.map { (if (it == AutoChange.Mode.SHADOW_ONLY) "SHADOW-ONLY" else it.name) to (it == mode) }) { i -> onPick(order[i]) }
    said?.let { Note(it, Modifier.padding(top = 4.dp)) }
}
