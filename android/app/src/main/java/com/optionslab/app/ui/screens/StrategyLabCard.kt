package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.VariantRunner
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.DiscoveryStats
import com.optionslab.ira.StrategyForge
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LAB_TITLE = "Strategy lab"
internal const val LAB_SHEET_TITLE = "Strategy lab: variants and new ideas"

/**
 * The Strategy lab section (Home → Automation card → Strategy lab; its own Home card until 11 Oct): each running strategy's
 * shadow variants (the best against the current rules and its test state), the new ideas in forward test, the graveyard
 * (ideas tried / passed / retired) and the proposals waiting. Memory only on screen; the lab's files are read off the main
 * thread the first time. None in GOLD.
 */
@Composable
internal fun StrategyLabSection(model: AppModel) {
    if (!VariantRunner.enabled()) return
    val variants by VariantRunner.variants.collectAsState(Dispatchers.Main.immediate)
    val forge by VariantRunner.forge.collectAsState(Dispatchers.Main.immediate)
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { VariantRunner.ensureLoaded() } } }
    StrategyLabCardContent(variants, forge, onOpen = { open = it ?: "" })
    open?.let { sel -> StrategyLabDialog(model, sel.ifEmpty { null }) { open = null } }
}

/** The card from plain state (tests drive it). [onOpen]: the sheet, at a candidate's id or at the top (null). */
@Composable
internal fun StrategyLabCardContent(variants: VariantLab.Book, forge: StrategyForge.Book, onOpen: (String?) -> Unit) {
    val p = LocalPalette.current
    val rows = remember(variants) {
        VariantLab.STRATEGIES.filter { variants.alive(it.key).isNotEmpty() || variants.runOf(it.key) != VariantLab.Run.OFF }.map { s ->
            val alive = variants.alive(s.key)
            val best = VariantLab.best(variants, s.key)
            s.key to "${s.label} (${variants.runOf(s.key).words}): ${alive.size} variant${if (alive.size == 1) "" else "s"}" +
                (best?.let { " · best ${it.id}, ${it.moved()}: ${VariantLab.state(it)}" } ?: " · none compared yet")
        }
    }
    val c = remember(forge) { StrategyForge.counts(forge) }
    val waitingLive = variants.changes.count { it.status == VariantLab.ChangeStatus.OFFERED }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(LAB_TITLE, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        Note("Variants trade virtually beside each strategy, and new ideas are tested each night. Most ideas fail - that is honest.",
            Modifier.padding(top = 2.dp))
        if (rows.isEmpty()) Note("No strategy is running for the lab to tune yet.", Modifier.padding(top = 6.dp).testTag("lab-none"))
        rows.forEach { (k, line) ->
            Text(line, style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp).testTag("lab-strategy-$k"))
        }
        val fwd = forge.forward
        Text(if (fwd.isEmpty()) "In forward test: none" else "In forward test: ${fwd.size}", style = Type.label.copy(color = p.ink, fontSize = 13.sp),
            modifier = Modifier.padding(top = 8.dp).testTag("lab-forward"))
        fwd.take(3).forEach { r ->
            Text("• ${r.c.name}: ${r.fwdDays} day${if (r.fwdDays == 1) "" else "s"}, ${r.fwd.size} trades, ${DiscoveryStats.rs(r.fwdNet)}",
                style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp).clickable { onOpen(r.c.id) })
        }
        Text("Ideas tried ${c.tried} · passed ${c.passed} · retired ${c.retired} · rejected ${c.rejected}",
            style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp).testTag("lab-graveyard"))
        Text(if (waitingLive + forge.waiting.size == 0) "Nothing waiting for you."
            else "Waiting for you: " + listOfNotNull(waitingLive.takeIf { it > 0 }?.let { "$it live change${if (it == 1) "" else "s"} (PIN)" },
                forge.waiting.size.takeIf { it > 0 }?.let { "$it to start on paper" }).joinToString(" · "),
            style = Type.label.copy(color = if (waitingLive + forge.waiting.size > 0) p.oxblood else p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.padding(top = 4.dp).testTag("lab-waiting"))
        BrassButton("Details", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-details"), tone = p.inkSoft) { onOpen(null) }
    }
}

/** What a tap in the sheet asks for. [UNDO_LIVE]: undo a live change (the PIN unless it puts the defaults back). */
internal enum class LabAction { UNDO, APPLY_LIVE, DECLINE_LIVE, START_PAPER, DECLINE_PAPER, UNDO_LIVE }

/** The sheet with Boss's hand on it: Undo (paper), Apply with the PIN (live), start on paper or decline (forge). */
@Composable
internal fun StrategyLabDialog(model: AppModel, selected: String?, onClose: () -> Unit) {
    val variants by VariantRunner.variants.collectAsState(Dispatchers.Main.immediate)
    val forge by VariantRunner.forge.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    var undoPinFor by remember { mutableStateOf<String?>(null) }
    val today = remember { runCatching { com.optionslab.app.data.Market.today() }.getOrElse { java.time.LocalDate.now() } }
    val scope = rememberCoroutineScope()
    fun doIt(f: () -> String) {
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { f() }.getOrElse { "Could not do that just now." }
            withContext(Dispatchers.Main) { said = msg }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(LocalPalette.current.paper, RoundedCornerShape(16.dp))) {
            StrategyLabSheet(variants, forge, selected, said, today = today, onAction = { a, id ->
                when (a) {
                    LabAction.UNDO -> doIt { VariantRunner.undo(id) }
                    // A live undo that puts the defaults back lowers risk: no PIN. Any other live undo asks for it.
                    LabAction.UNDO_LIVE -> if (VariantRunner.undoLiveNeedsPin(id)) undoPinFor = id else doIt { VariantRunner.undoLive(id, pinConfirmed = false) }
                    LabAction.APPLY_LIVE -> pinFor = id
                    LabAction.DECLINE_LIVE -> doIt { VariantRunner.declineLive(id) }
                    LabAction.START_PAPER -> doIt { VariantRunner.approvePaper(id) }
                    LabAction.DECLINE_PAPER -> doIt { VariantRunner.declinePaper(id) }
                }
            }, onClose = onClose)
        }
    }
    pinFor?.let { id ->
        Reauth(model, pinOnly = false, why = "Enter your app PIN to change a live strategy's rules to the variant that beat them.",
            onOk = { pinFor = null; doIt { VariantRunner.acceptLive(id, pinConfirmed = true) } }, onCancel = { pinFor = null })
    }
    undoPinFor?.let { id ->
        Reauth(model, pinOnly = false, why = "Enter your app PIN to change a live strategy's rules back to the ones before this change.",
            onOk = { undoPinFor = null; doIt { VariantRunner.undoLive(id, pinConfirmed = true) } }, onCancel = { undoPinFor = null })
    }
}

/**
 * The lab's details from plain state (tests drive it): the changes (Undo on paper; Apply with the PIN on live), each
 * strategy's variants with their test state, then each idea - its rules in plain English, its backtest table, its forward
 * record and why it stands where it does (why it was retired). One lazy column: it fits a phone held either way and at
 * large font (the title wraps beside Close; every button has its own row).
 */
@Composable
internal fun StrategyLabSheet(variants: VariantLab.Book, forge: StrategyForge.Book, selected: String?, said: String?,
                              onAction: (LabAction, String) -> Unit, onClose: (() -> Unit)?,
                              today: java.time.LocalDate = java.time.LocalDate.now()) {
    val p = LocalPalette.current
    val changes = remember(variants) { variants.changes.filter { it.status != VariantLab.ChangeStatus.DECLINED }.reversed() }
    val ideas = remember(forge, selected) {
        val order = listOf(StrategyForge.Stage.PROPOSED, StrategyForge.Stage.FORWARD, StrategyForge.Stage.PAPER, StrategyForge.Stage.RETIRED,
            StrategyForge.Stage.DECLINED, StrategyForge.Stage.REJECTED)
        val all = forge.records.sortedWith(compareBy<StrategyForge.Record> { order.indexOf(it.stage) }.thenByDescending { it.c.n })
        val top = all.filter { it.stage != StrategyForge.Stage.REJECTED } + all.filter { it.stage == StrategyForge.Stage.REJECTED }.take(12)
        top.sortedByDescending { it.c.id == selected }
    }
    val c = remember(forge) { StrategyForge.counts(forge) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(LAB_SHEET_TITLE, style = Type.title.copy(color = p.ink), maxLines = 3, modifier = Modifier.weight(1f).testTag("lab-sheet-title"))
            if (onClose != null) BrassButton("Close", modifier = Modifier.testTag("lab-sheet-close"), tone = p.inkSoft, onClick = onClose)
        }
        said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
            item(key = "intro") {
                Note("Variants and new ideas trade only virtually on the day's own data, after paper costs and half a spread more. A variant must beat " +
                    "the current rules for ${VariantLab.MIN_N} days and pass the false-discovery check across all ${variants.tried} tried. Each variant is " +
                    "the arm's own rules with other values. Paper changes reach the arm's paper trades by themselves from the next session, with Undo; " +
                    "live ones need your PIN, also from the next session. A variant that loses too much goes back to the defaults by itself. " +
                    "Liquidity 15+5 tunes its exits only. New ideas face walk-forward, a random twin and a count of all " +
                    "${c.tried} ever tried, then ${StrategyForge.FWD_MIN_DAYS} virtual days before you are asked - paper only.")
            }
            item(key = "graveyard") {
                Text("Graveyard: ${c.tried} ideas tried · ${c.passed} passed the backtest · ${c.retired} retired after forward · ${c.rejected} rejected · " +
                    "${variants.tried} variants tried, ${variants.retiredCount} retired",
                    style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.testTag("lab-sheet-graveyard"))
            }
            if (changes.isNotEmpty()) item(key = "changes-h") { Text("Changes", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold)) }
            items(changes, key = { "c:" + it.id }) { ch -> ChangeRow(ch, variants, onAction) }
            val shown = VariantLab.STRATEGIES.filter { s -> variants.variants.any { v -> v.strategy == s.key } || variants.tunes.any { it.strategy == s.key } }
            if (shown.isNotEmpty()) item(key = "variants-h") { Text("Variants", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold)) }
            items(shown, key = { "s:" + it.key }) { s -> StrategyRow(s, variants, today, onAction) }
            item(key = "ideas-h") { Text("New ideas", style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold)) }
            if (ideas.isEmpty()) item(key = "ideas-none") { Note("No idea tried yet: the forge runs at night, while the phone charges or after 18:00.") }
            items(ideas, key = { "i:" + it.c.id }) { r -> IdeaRow(r, onAction) }
            item(key = "hosted") { HostedIdeasSection() }
        }
    }
}

@Composable
private fun LabBox(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
        .border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
}

@Composable
private fun ChangeRow(ch: VariantLab.Change, variants: VariantLab.Book, onAction: (LabAction, String) -> Unit) {
    val p = LocalPalette.current
    val label = VariantLab.strategy(ch.strategy)?.label ?: ch.strategy
    val v = variants.variants.firstOrNull { it.id == ch.variant }
    LabBox {
        Text("$label: ${v?.moved() ?: ch.variant}", style = Type.body.copy(color = p.ink, fontSize = 14.sp))
        Text("${DiscoveryStats.day(ch.day)} · ${ch.kind.words} · ${ch.status.words}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.padding(top = 4.dp))
        Text("Why: ${ch.why}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        ch.effective?.let { Text("Traded from ${DiscoveryStats.day(it)}", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp)) }
        if (ch.kind == VariantLab.ChangeKind.AUTO_PAPER && ch.status == VariantLab.ChangeStatus.APPLIED)
            BrassButton("Undo", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-undo-" + ch.id), tone = p.inkSoft) { onAction(LabAction.UNDO, ch.id) }
        if (ch.kind == VariantLab.ChangeKind.LIVE_PROPOSAL && ch.status == VariantLab.ChangeStatus.ACCEPTED)
            BrassButton(if (VariantLab.restoresDefault(variants, ch.id)) "Undo" else "Undo (PIN)", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-undo-" + ch.id),
                tone = p.inkSoft) { onAction(LabAction.UNDO_LIVE, ch.id) }
        if (ch.status == VariantLab.ChangeStatus.OFFERED) {
            BrassButton("Apply (PIN)", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-apply-" + ch.id), tone = p.inkSoft) { onAction(LabAction.APPLY_LIVE, ch.id) }
            BrassButton("Decline", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { onAction(LabAction.DECLINE_LIVE, ch.id) }
        }
    }
}

@Composable
private fun StrategyRow(s: VariantLab.Strategy, variants: VariantLab.Book, today: java.time.LocalDate, onAction: (LabAction, String) -> Unit) {
    val p = LocalPalette.current
    val mine = variants.variants.filter { it.strategy == s.key }.sortedWith(compareBy<VariantLab.Variant> { !it.alive }.thenByDescending { it.n })
    LabBox {
        Text("${s.label} (${variants.runOf(s.key).words})", style = Type.body.copy(color = p.ink, fontSize = 14.sp))
        // What the arm trades (S1): per account, "running: default" or "running: variant V3 since 14 Oct", with its Undo.
        for (live in listOf(false, true)) {
            val acct = if (live) "live" else "paper"
            val words = VariantLab.runningWords(variants, s.key, live, today)
            Text("${if (live) "Live" else "Paper"} · $words", style = Type.label.copy(color = p.ink, fontSize = 12.sp),
                modifier = Modifier.padding(top = 3.dp).testTag("lab-running-${s.key}-$acct"))
            val r = VariantLab.running(variants, s.key, live, today)
            val ch = r.change ?: r.next?.let { variants.change(it.change) }
            val action = when {
                ch == null -> null
                !live && ch.kind == VariantLab.ChangeKind.AUTO_PAPER && ch.status == VariantLab.ChangeStatus.APPLIED -> LabAction.UNDO
                live && ch.kind == VariantLab.ChangeKind.LIVE_PROPOSAL && ch.status == VariantLab.ChangeStatus.ACCEPTED -> LabAction.UNDO_LIVE
                else -> null
            }
            if (ch != null && action != null) BrassButton(if (action == LabAction.UNDO_LIVE && !VariantLab.restoresDefault(variants, ch.id)) "Undo (PIN)" else "Undo",
                Modifier.fillMaxWidth().padding(top = 4.dp).testTag("lab-undo-running-${s.key}-$acct"), tone = p.inkSoft) { onAction(action, ch.id) }
        }
        Text("Current: " + s.params.joinToString(", ") { pr -> "${pr.words} ${pr.say(variants.currentOf(s.key).getValue(pr.key))}" },
            style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
        mine.take(8).forEach { v ->
            Text("${v.id} · ${v.moved()} · ${v.status.words}" + (if (v.n > 0) " · ${VariantLab.state(v)}" else "") + (if (v.why.isNotEmpty() && !v.alive) " · ${v.why}" else ""),
                style = Type.label.copy(color = if (v.status == VariantLab.Status.RETIRED || v.status == VariantLab.Status.PRECHECK_FAILED) p.inkSoft else p.ink, fontSize = 12.sp),
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun IdeaRow(r: StrategyForge.Record, onAction: (LabAction, String) -> Unit) {
    val p = LocalPalette.current
    LabBox {
        Text("${r.c.name} · ${r.stage.words}", style = Type.body.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.testTag("lab-idea-" + r.c.id))
        Text("Rules: " + r.c.rules.words(r.c.underlying), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 3.dp))
        r.bt?.let { bt ->
            Text("Backtest" + (if (bt.approx) " (index-only approximation)" else "") + (bt.from?.let { " · $it to ${bt.to}" } ?: ""),
                style = Type.label.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
            TableRow("Trades / days", "${bt.trades} / ${bt.days}")
            TableRow("Net after costs", DiscoveryStats.rs(bt.net))
            TableRow("Won", "${bt.winRate.toInt()}%")
            TableRow("Folds", bt.folds.joinToString(" / ") { DiscoveryStats.rs(it) })
            TableRow("Random twin p", VariantLab.fmtP(bt.twinP))
            TableRow("Deepest fall", DiscoveryStats.rs(-bt.maxDD))
            bt.gates.forEach { (g, ok) -> TableRow(g, if (ok) "passed" else "failed") }
        }
        if (r.fwdDays > 0) Text("Forward: ${r.fwd.size} trades over ${r.fwdDays} days, ${DiscoveryStats.rs(r.fwdNet)}",
            style = Type.label.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
        if (r.why.isNotEmpty()) Text((if (r.stage == StrategyForge.Stage.RETIRED) "Why retired: " else "Why: ") + r.why,
            style = Type.label.copy(color = if (r.stage == StrategyForge.Stage.RETIRED) p.oxblood else p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        if (r.stage == StrategyForge.Stage.PROPOSED) {
            BrassButton("Start on paper", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("lab-start-" + r.c.id), tone = p.inkSoft) { onAction(LabAction.START_PAPER, r.c.id) }
            BrassButton("Decline", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { onAction(LabAction.DECLINE_PAPER, r.c.id) }
        }
    }
}

/** One row of a table: the label takes what it needs up to half, the value the rest (both wrap; nothing overlaps). */
@Composable
private fun TableRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
        Text(value, style = Type.label.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}
