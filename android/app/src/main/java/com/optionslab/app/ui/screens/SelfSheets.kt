package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.BossTradeLog
import com.optionslab.app.ira.BotReplayStore
import com.optionslab.app.ira.ChangeTimelineHost
import com.optionslab.app.ira.MonthlyScorecard
import com.optionslab.app.ira.StudyLibrary
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.AutoScorecard
import com.optionslab.ira.BossTrades
import com.optionslab.ira.BotReplay
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.StudyBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The sheets the roadmap's features open over any page (from a card's button or from Jarvis). */
object SelfSheets {
    /** "Today's changes" open (S13). */
    val timeline = MutableStateFlow(false)

    /** TEST ONLY: everything closed. */
    internal fun resetForTest() {
        timeline.value = false
        MonthlyScorecard.close(); StudyLibrary.close(); BotReplayStore.close(); BossTradeLog.dismiss()
    }
}

internal const val TODAY_CHANGES_TITLE = "Today's changes"
internal const val SCORECARD_TITLE = "Automation scorecard"

/**
 * Shown once from the app's frame (over every page): Today's changes, the monthly scorecard, a study page, a bot trade's
 * replay and the "why?" card after a hand trade - each only while it is asked for. Each sheet is a dialog of its own, so it
 * opens above any other sheet (the trade manager's record, say) it was opened from. None in IraGoldAlgo.
 */
@Composable
fun SelfSheetHost(model: AppModel) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val timeline by SelfSheets.timeline.collectAsState(Dispatchers.Main.immediate)
    val scorecard by MonthlyScorecard.open.collectAsState(Dispatchers.Main.immediate)
    val study by StudyLibrary.open.collectAsState(Dispatchers.Main.immediate)
    val replay by BotReplayStore.open.collectAsState(Dispatchers.Main.immediate)
    val prompt by BossTradeLog.prompt.collectAsState(Dispatchers.Main.immediate)
    if (timeline) TodayChangesDialog(model) { SelfSheets.timeline.value = false }
    if (scorecard) ScorecardDialog { MonthlyScorecard.close() }
    study?.let { id -> StudyPageDialog(id) { StudyLibrary.close() } }
    replay?.let { id -> BotReplayDialog(id) { BotReplayStore.close() } }
    prompt?.let { p -> ManualReasonCard(p) }
}

/** A dialog that takes most of the screen, in either orientation, with a title row that wraps beside Close. */
@Composable
internal fun SelfDialog(title: String, tag: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp).testTag(tag)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.title.copy(color = p.ink), maxLines = 2, modifier = Modifier.weight(1f).testTag("$tag-title"))
                BrassButton("Close", modifier = Modifier.testTag("$tag-close"), tone = p.inkSoft, onClick = onClose)
            }
            content()
        }
    }
}

// ---- S13: today's changes ----------------------------------------------------------------------------------------------------

@Composable
internal fun TodayChangesDialog(model: AppModel, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<ChangeTimeline.Item>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { items = withContext(Dispatchers.IO) { runCatching { ChangeTimelineHost.items() }.getOrDefault(emptyList()) } } }
    LaunchedEffect(Unit) { reload() }
    fun undoAll(withPin: Boolean) {
        busy = true
        scope.launch {
            said = withContext(Dispatchers.IO) { runCatching { ChangeTimelineHost.undoAll(withPin) }.getOrElse { "Could not undo just now." } }
            busy = false
            reload()
        }
    }
    SelfDialog(TODAY_CHANGES_TITLE, "today-changes", onClose) {
        TodayChangesContent(items, said, busy, onUndoAll = { undoAll(false) }, onUndoWithPin = { pin = true })
    }
    if (pin) Reauth(model, pinOnly = false, why = "Enter your app PIN to undo today's changes that add live risk as well.",
        onOk = { pin = false; undoAll(true) }, onCancel = { pin = false })
}

/** The sheet from plain state (tests drive it): newest first, each change with its time, agent, what, why and undo state. */
@Composable
internal fun TodayChangesContent(changes: List<ChangeTimeline.Item>?, said: String?, busy: Boolean, onUndoAll: () -> Unit, onUndoWithPin: () -> Unit) {
    val p = LocalPalette.current
    val newest = remember(changes) { changes.orEmpty().sortedByDescending { it.atMs } }
    val plan = remember(changes) { ChangeTimeline.undoPlan(changes.orEmpty()) }
    said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp).testTag("today-changes-said")) }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item(key = "intro") {
            Note("Every change the agents made by themselves today: Autopilot, the specialists' own tuning, the strategy lab, the health tuner " +
                "and the other agents. \"Undo everything today\" puts each back through its own undo, newest first; one that would add live " +
                "risk is left for your PIN.")
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth()) {
                BrassButton(if (busy) "Undoing…" else "Undo everything today (${plan.now.size})", Modifier.fillMaxWidth().padding(top = 4.dp).testTag("undo-all"),
                    enabled = !busy && plan.now.isNotEmpty(), tone = p.oxblood, onClick = onUndoAll)
                if (plan.needPin.isNotEmpty()) {
                    Note("Needs your PIN (adds live risk): " + plan.needPin.joinToString("; ") { it.what }, Modifier.padding(top = 4.dp).testTag("needs-pin"))
                    BrassButton("Undo those too with my PIN (${plan.needPin.size})", Modifier.fillMaxWidth().padding(top = 4.dp).testTag("undo-all-pin"),
                        enabled = !busy, tone = p.oxblood, onClick = onUndoWithPin)
                }
            }
        }
        if (changes == null) item(key = "reading") { Note("Reading the agents' books…") }
        else if (newest.isEmpty()) item(key = "none") { Note("Nothing changed by itself today.", Modifier.testTag("today-changes-none")) }
        items(newest, key = { it.id }) { i -> ChangeRow(i) }
    }
}

@Composable
private fun ChangeRow(i: ChangeTimeline.Item) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("change-" + i.id)) {
        Text(i.what, style = Type.body.copy(color = p.ink, fontSize = 14.sp))
        Text("${com.optionslab.app.data.SmartWorkers.hhmm(i.atMs)} · ${i.agent}" + (if (i.scope.isNotEmpty()) " · ${i.scope}" else ""),
            style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        Text("Why: ${i.why}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        Text(i.undo.words.replaceFirstChar { it.uppercase() }, style = Type.label.copy(color = when (i.undo) {
            ChangeTimeline.UndoState.NEEDS_PIN -> p.oxblood
            ChangeTimeline.UndoState.UNDOABLE -> p.verdigris
            else -> p.inkSoft
        }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp))
    }
}

// ---- S15: the monthly scorecard ----------------------------------------------------------------------------------------------

@Composable
internal fun ScorecardDialog(onClose: () -> Unit) {
    var card by remember { mutableStateOf<AutoScorecard.Card?>(null) }
    LaunchedEffect(Unit) { card = withContext(Dispatchers.IO) { runCatching { MonthlyScorecard.lastMonth() }.getOrNull() } }
    SelfDialog(SCORECARD_TITLE, "scorecard", onClose) { ScorecardContent(card) }
}

@Composable
internal fun ScorecardContent(card: AutoScorecard.Card?) {
    val p = LocalPalette.current
    val lines = remember(card) { card?.let { AutoScorecard.lines(it) }.orEmpty() }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item(key = "head") {
            if (card == null) Note("Reading the agents' books…")
            else {
                Text(AutoScorecard.monthWords(card.month), style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Note(AutoScorecard.say(card), Modifier.padding(top = 4.dp).testTag("scorecard-say"))
                Note("Each change's effect is measured on the trades that settled after it, against the same trades with no change.", Modifier.padding(top = 4.dp))
            }
        }
        items(lines.withIndex().toList(), key = { "l${it.index}" }) { (_, l) -> Note("• $l") }
    }
}

// ---- S19: a study page -------------------------------------------------------------------------------------------------------

@Composable
internal fun StudyPageDialog(id: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var page by remember(id) { mutableStateOf<StudyBook.Page?>(null) }
    LaunchedEffect(id) { page = withContext(Dispatchers.IO) { StudyLibrary.pages(ctx).firstOrNull { it.id == id } } }
    SelfDialog(page?.title ?: "Study", "study-page", onClose) { StudyPageBody(page) }
}

@Composable
internal fun StudyPageBody(page: StudyBook.Page?) {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        if (page == null) item(key = "reading") { Note("Opening the page…") }
        else {
            item(key = "group") { Text(page.group.uppercase(), style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp)) }
            items(page.paragraphs.withIndex().toList(), key = { "p${it.index}" }) { (_, para) ->
                Text(para, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.testTag("study-para"))
            }
            item(key = "source") { Note("The numbers are the app's own research (research/HUNT_* and the strategy notes), summarised.") }
        }
    }
}

// ---- S21: a bot trade's replay -----------------------------------------------------------------------------------------------

@Composable
internal fun BotReplayDialog(tradeId: String, onClose: () -> Unit) {
    var replay by remember(tradeId) { mutableStateOf<BotReplay.Replay?>(null) }
    var failed by remember(tradeId) { mutableStateOf(false) }
    LaunchedEffect(tradeId) {
        val r = withContext(Dispatchers.IO) { runCatching { BotReplayStore.load(tradeId) }.getOrNull() }
        if (r == null) failed = true else replay = r
    }
    SelfDialog(keepNumbersWhole(replay?.title ?: "Replay"), "bot-replay", onClose) {
        BotReplayContent(replay, if (failed) "No replay for this trade: its record is gone." else null)
    }
}

/** The replay from plain state (tests drive it): the chart, the counterfactual, the flow at entry, the timeline. */
@Composable
internal fun BotReplayContent(r: BotReplay.Replay?, error: String?) {
    val p = LocalPalette.current
    val small = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
    val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        if (r == null) { item(key = "wait") { Text(error ?: "Reading the trade's record…", style = soft) }; return@LazyColumn }
        item(key = "chart") {
            if (r.bars.isEmpty()) Text("No minute candles kept for this trade: the timeline below is the whole replay.", style = soft)
            else {
                BotReplayChart(r, Modifier.fillMaxWidth().height(200.dp))
                Text(BotReplay.legend(r), style = soft.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
            }
        }
        BotReplay.rangeLine(r)?.let { l -> item(key = "range") { Text(keepNumbersWhole(l), style = small) } }
        item(key = "why") { Text(keepNumbersWhole("Why it exited: ${r.exitWhy}"), style = small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("replay-why")) }
        r.counterfactual?.let { c -> item(key = "cf") { Text(keepNumbersWhole(c), style = small, modifier = Modifier.testTag("replay-counterfactual")) } }
        r.flowAtEntry?.let { f -> item(key = "flow") { Text(f, style = small) } }
        item(key = "tl") { Text("WHAT THE TRADE MANAGER SAID", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 8.dp)) }
        items(r.timeline.withIndex().toList(), key = { "t${it.index}" }) { (_, line) -> Text(keepNumbersWhole(line), style = soft) }
        item(key = "foot") { Text("A replay of a closed trade: it reads the records and changes nothing.", style = soft, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** The option's candles over the hold, the entry ●, the exit ■ and the original rules' exit ◆ (the Liquidity replay's style). */
@Composable
private fun BotReplayChart(r: BotReplay.Replay, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.background(p.paperDeep, RoundedCornerShape(8.dp))
        .semantics { contentDescription = "Trade replay chart: the option's minutes, the entry, the exit and the original rules' exit" }) {
        val l = BotReplay.layout(r, size.width.toInt(), size.height.toInt(), 6.dp.toPx().toInt()) ?: return@Canvas
        drawRect(p.gold.copy(alpha = 0.12f), Offset(l.holdFrom, 0f), Size(max(1f, l.holdTo - l.holdFrom), size.height))
        for (k in l.candles) {
            val c = (if (k.up) p.verdigris else p.oxblood).copy(alpha = 0.8f)
            drawLine(c, Offset(k.x, k.high), Offset(k.x, k.low), 1f)
            drawRect(c, Offset(k.x - l.candleWidth / 2, k.top), Size(l.candleWidth, max(1f, k.bottom - k.top)))
        }
        val rad = 4.dp.toPx()
        l.entry?.let { drawCircle(p.ink, rad, Offset(it.x, it.y)) }
        l.exit?.let { drawRect(p.ink, Offset(it.x - rad, it.y - rad), Size(rad * 2, rad * 2)) }
        l.original?.let { d -> drawPath(Path().apply { moveTo(d.x, d.y - rad); lineTo(d.x + rad, d.y); lineTo(d.x, d.y + rad); lineTo(d.x - rad, d.y); close() }, p.brass) }
    }
}

/**
 * The order review's soft word before a hand entry (S10): when Boss's own record says such a trade loses ("Heads up: your
 * after-14:00 gut trades lost 9 of 11"). Read off the main thread; words only - the order is never blocked or changed.
 */
@Composable
internal fun BossRuleNote(entry: Boolean) {
    if (!entry || !BossTradeLog.enabled()) return
    val p = LocalPalette.current
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { note = withContext(Dispatchers.IO) { runCatching { BossTradeLog.warning() }.getOrNull() } }
    note?.let {
        com.optionslab.app.ui.components.LedgerCard(title = "Your own record", accent = p.amber, modifier = Modifier.padding(bottom = 10.dp).testTag("boss-rule-note")) { Note(it) }
    }
}

// ---- S20: today's lesson (the Ira page) --------------------------------------------------------------------------------------

/** The Ira page's "Today's lesson" card: shown only on the lesson's own day, and only when there is one. */
@Composable
internal fun TodayLessonSlot() {
    val lesson by com.optionslab.app.ira.SelfJobs.lesson.collectAsState(Dispatchers.Main.immediate)
    val today = remember { runCatching { com.optionslab.app.data.Market.today() }.getOrElse { java.time.LocalDate.now() } }
    lesson?.takeIf { it.day == today }?.let { TodayLessonCard(it) }
}

@Composable
internal fun TodayLessonCard(l: com.optionslab.ira.DailyLesson.Lesson) {
    val p = LocalPalette.current
    com.optionslab.app.ui.components.LedgerCard(title = "Today's lesson", modifier = Modifier.testTag("today-lesson")) {
        Text(keepNumbersWhole(l.title), style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
        Note(keepNumbersWhole(l.text), Modifier.padding(top = 4.dp))
        Text("From ${l.kind.words}, today's own records.", style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

// ---- the Autopilot card's buttons (Home) -------------------------------------------------------------------------------------

/** Home → Autopilot: Today's changes (S13) and the monthly scorecard (S15), and a line while learning is frozen (S14). */
@Composable
internal fun AutopilotSelfButtons() {
    val p = LocalPalette.current
    val freeze by com.optionslab.app.ira.LearningFreeze.state.collectAsState(Dispatchers.Main.immediate)
    if (freeze.frozen) Text("Learning frozen: nothing is applied until you unfreeze it (Settings → Bot settings).",
        style = Type.label.copy(color = p.oxblood, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp).testTag("freeze-line"))
    BrassButton(TODAY_CHANGES_TITLE, Modifier.fillMaxWidth().padding(top = 6.dp).testTag("open-today-changes"), tone = p.inkSoft) { SelfSheets.timeline.value = true }
    BrassButton("Monthly scorecard", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("open-scorecard"), tone = p.inkSoft) { MonthlyScorecard.show() }
}

// ---- S10: the "why?" card after a hand trade --------------------------------------------------------------------------------

/**
 * After a hand order fills: why? One tap on a chip (and a note if Boss likes), or ✕. A small dialog that never holds the
 * order (it is already filled) and goes away by itself after a few minutes.
 */
@Composable
internal fun ManualReasonCard(prompt: BossTradeLog.Prompt) {
    LaunchedEffect(prompt) { kotlinx.coroutines.delay(BossTradeLog.PROMPT_MS); if (BossTradeLog.prompt.value == prompt) BossTradeLog.dismiss() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { BossTradeLog.dismiss() }, properties = androidx.compose.ui.window.DialogProperties(
        securePolicy = com.optionslab.app.security.Capture.policy)) {
        ManualReasonContent(prompt.symbol, prompt.exit,
            onEntry = { why, note -> BossTradeLog.answer(prompt, why = why, note = note) },
            onExit = { why, note -> BossTradeLog.answer(prompt, exitWhy = why, note = note) },
            onDismiss = { BossTradeLog.dismiss() })
    }
}

/** The card from plain state (tests drive it): the chips for an entry or an exit, a note, ✕. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ManualReasonContent(symbol: String, exit: Boolean, onEntry: (BossTrades.Why, String) -> Unit, onExit: (BossTrades.ExitWhy, String) -> Unit,
                                 onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(p.paper, RoundedCornerShape(16.dp)).padding(14.dp).testTag("manual-reason")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (exit) "Why did you close $symbol?" else "Why this trade?", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            com.optionslab.app.ui.components.TextButton(onDismiss, modifier = Modifier.testTag("manual-reason-dismiss")) { Text("✕", style = Type.label.copy(color = p.inkSoft)) }
        }
        Note("Optional - one tap. Jarvis learns your own rules from it; nothing is changed or held.", Modifier.padding(top = 2.dp))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (exit) BossTrades.ExitWhy.entries.forEach { w ->
                com.optionslab.app.ui.components.Token(w.words, false, Modifier.testTag("exit-" + w.name)) { onExit(w, note) }
            } else BossTrades.Why.entries.forEach { w ->
                com.optionslab.app.ui.components.Token(w.words, false, Modifier.testTag("why-" + w.name)) { onEntry(w, note) }
            }
        }
        androidx.compose.material3.OutlinedTextField(note, { note = it.take(140) }, label = { Text("Note (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}
