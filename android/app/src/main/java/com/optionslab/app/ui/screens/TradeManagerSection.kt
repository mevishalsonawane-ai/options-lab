package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ManagerSpecialists
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.ManagerTeam
import com.optionslab.ira.SpecialistBook
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home → Strategies card → Trade manager (10 Oct): the record per strategy (paper and live apart: trades, early exits,
 * extensions, lock exits, net against the original rules) and a sheet with each wired strategy's mode and every managed
 * trade's decisions. Memory only on screen; a mode is written off the main thread; LIVE to ACT asks for the PIN.
 */
@Composable
internal fun TradeManagerSection(model: AppModel) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val records by TradeManagerHost.records.collectAsState(Dispatchers.Main.immediate)
    val policy by TradeManagerHost.policy.collectAsState(Dispatchers.Main.immediate)
    var sheet by remember { mutableStateOf(false) }
    var team by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { TradeManagerHost.ensureLoaded() } } }
    Rule()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val p = LocalPalette.current
        Text("Trade manager", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        TradeManager.recordLines(records, policy).forEach { Note(it, Modifier.padding(top = 2.dp)) }
        records.filter { !it.closed }.forEach { r -> Note("${r.trade.label}: " + TradeManager.cardLine(r), Modifier.padding(top = 2.dp)) }
        BrassButton("Trade manager record", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { sheet = true }
        BrassButton("Specialists", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { team = true }
    }
    if (sheet) TradeManagerSheet(model) { sheet = false }
    if (team) SpecialistsSheet(model) { team = false }
}

/**
 * Trade manager → Specialists: per strategy, each specialist's latest vote and reason on every open trade with the Chair's
 * decision and its top three reasons, then each specialist's weight, mute switch and report card, last night's line, and -
 * where the manager acts on live trades - the tuned weights to accept with the PIN. Muting needs no PIN; un-muting where the
 * manager acts on live trades asks for it. Memory only on screen; every change is written off the main thread.
 */
@Composable
internal fun SpecialistsSheet(model: AppModel, onClose: () -> Unit) {
    val book by ManagerSpecialists.book.collectAsState(Dispatchers.Main.immediate)
    val views by ManagerSpecialists.views.collectAsState(Dispatchers.Main.immediate)
    val records by TradeManagerHost.records.collectAsState(Dispatchers.Main.immediate)
    val policy by TradeManagerHost.policy.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val scope = rememberCoroutineScope()
    fun doIt(f: () -> String) {
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { f() }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = msg }
        }
    }
    SpecialistsContent(book, views, records, policy, said,
        onMute = { family, id -> doIt { ManagerSpecialists.mute(family, id) } },
        onUnmute = { family, id ->
            if (policy.mode(family, TradeManager.Account.LIVE) == TradeManager.Mode.ACT) {
                pinFor = family to id
            } else {
                doIt { ManagerSpecialists.unmute(family, id) }
            }
        },
        onAccept = { family -> pinFor = family to null },
        onClose = onClose)
    pinFor?.let { (family, id) ->
        val name = TradeManager.familyName(family)
        Reauth(model, pinOnly = false,
            why = if (id != null) "Enter your app PIN to un-mute the ${ManagerTeam.nameOf(id)} specialist on $name, whose live trades the trade manager acts on."
                else "Enter your app PIN to use the tuned specialists' weights on $name's live trades.",
            onOk = {
                pinFor = null
                doIt { if (id != null) ManagerSpecialists.unmute(family, id, pinConfirmed = true) else ManagerSpecialists.acceptSuggestion(family, pinConfirmed = true) }
            },
            onCancel = { pinFor = null })
    }
}

/** The Specialists sheet from plain state (tests drive it without an [AppModel]). */
@Composable
internal fun SpecialistsContent(
    book: ManagerSpecialists.Book,
    views: Map<String, ManagerSpecialists.View>,
    records: List<TradeManager.Record>,
    policy: TradeManager.Policy,
    said: String?,
    onMute: (String, String) -> Unit,
    onUnmute: (String, String) -> Unit,
    onAccept: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    var family by remember { mutableStateOf(TradeManager.FAMILIES.first().key) }
    val cards = remember(records) { SpecialistBook.cards(records) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Specialists", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Note("Sixteen specialists each read one family of data and vote; the chair weighs the votes. News, traps and bad data can " +
                "still order an exit alone. Muting a specialist needs no PIN: its votes stop counting and the manager moves back toward " +
                "the original rules (its record goes on, and a hard veto stays). Weights tune themselves slowly on paper and shadow; " +
                "live trades keep frozen weights until you accept a change with your PIN.")
            said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                BrainChoice("Strategy", TradeManager.FAMILIES.map { it.name }, TradeManager.familyName(family)) { picked ->
                    TradeManager.FAMILIES.firstOrNull { it.name == picked }?.let { family = it.key }
                }
                val liveActs = policy.mode(family, TradeManager.Account.LIVE) == TradeManager.Mode.ACT
                val tuning = book.tuned[family]
                val cfg = ManagerSpecialists.configFor(family, frozen = false)
                Text("OPEN TRADES (LATEST LOOK)", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                val open = views.values.filter { it.family == family }.sortedBy { it.label }
                if (open.isEmpty()) Note("No open trade of ${TradeManager.familyName(family)} is being managed now.")
                open.forEach { v ->
                    Text(v.label + if (v.frozen) " · live (frozen weights)" else "",
                        style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    Note(v.chair)
                    v.top.forEachIndexed { i, r -> Note("${i + 1}. $r", Modifier.padding(start = 8.dp)) }
                    val vcfg = ManagerSpecialists.configFor(family, v.frozen)
                    v.votes.forEach { vote ->
                        Note(ManagerTeam.voteLine(vote, vcfg.spec(vote.specialist).weight, !vcfg.counts(vote.specialist)), Modifier.padding(start = 8.dp, top = 2.dp))
                    }
                }
                Text("SPECIALISTS (WEIGHT, MUTE, REPORT CARD)", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                book.lines.firstOrNull { it.startsWith(TradeManager.familyName(family) + ":") }?.let { Note("Last night: $it", Modifier.padding(bottom = 4.dp)) }
                ManagerTeam.SPECIALISTS.forEach { sp ->
                    val mutedWhy = tuning?.muted?.get(sp.id)
                    val card = cards.firstOrNull { it.family == family && it.specialist == sp.id }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sp.name + if (sp.id in ManagerTeam.VETOES) " (hard veto)" else "",
                                style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                            Note("Weight ${String.format(java.util.Locale.ENGLISH, "%.2f", cfg.spec(sp.id).weight)}" +
                                (if (cfg.spec(sp.id).gate) " · extension gate" else "") + (mutedWhy?.let { " · muted: $it" } ?: ""))
                            Note(card?.let { SpecialistBook.cardLine(it) } ?: "No decision voted yet.")
                        }
                        if (mutedWhy == null) TextButton({ onMute(family, sp.id) }) { Text("Mute") }
                        else TextButton({ onUnmute(family, sp.id) }) { Text(if (liveActs) "Un-mute (PIN)" else "Un-mute") }
                    }
                }
                if (liveActs) {
                    val sug = ManagerSpecialists.suggestion(family)
                    Text("LIVE (FROZEN WEIGHTS)", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                    if (sug.isEmpty()) Note("The live trades use the same weights as the tuned ones.")
                    else {
                        Note("Tuning suggests for live: " + sug.joinToString("; "))
                        BrassButton("Accept for live (PIN)", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { onAccept(family) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TradeManagerSheet(model: AppModel, onClose: () -> Unit) {
    val policy by TradeManagerHost.policy.collectAsState(Dispatchers.Main.immediate)
    val records by TradeManagerHost.records.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun choose(family: String, account: TradeManager.Account, m: TradeManager.Mode) {
        if (TradeManager.needsPin(m, account) && TradeManager.familyOf(family)?.canAct == true) { pin = family; return }
        scope.launch(Dispatchers.IO) {
            val msg = runCatching { TradeManagerHost.setMode(family, account, m) }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = msg }
        }
    }
    TradeManagerContent(policy, records, said, onChoose = { f, a, m -> choose(f, a, m) }, onClose = onClose)
    pin?.let { family ->
        Reauth(model, pinOnly = false, why = "Enter your app PIN to let the trade manager act on ${TradeManager.familyName(family)}'s LIVE trades. " +
            TradeManager.UNPROVEN,
            onOk = {
                pin = null
                scope.launch(Dispatchers.IO) {
                    val msg = runCatching { TradeManagerHost.setMode(family, TradeManager.Account.LIVE, TradeManager.Mode.ACT, pinConfirmed = true) }
                        .getOrElse { "Could not save that." }
                    withContext(Dispatchers.Main) { said = msg }
                }
            },
            onCancel = { pin = null })
    }
}

/** The sheet from plain state (tests drive it without an [AppModel]). */
@Composable
internal fun TradeManagerContent(
    policy: TradeManager.Policy,
    records: List<TradeManager.Record>,
    said: String?,
    onChoose: (String, TradeManager.Account, TradeManager.Mode) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trade manager record", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Note("After a fill it keeps watching the trade with the live readings. It may exit early when the picture turns against " +
                "the trade, and may move the target out only together with a lock at breakeven plus charges or better, raised on each " +
                "move and trailing new highs at half the open profit. It never adds lots, never widens a stop and never moves a " +
                "square-off. SHADOW records what it would do beside what the original rules did; nothing changes. Live to ACT asks " +
                "for your PIN. Solo and Pine act on paper by default; every other strategy records only until you switch it on. " +
                "A strategy with 30 settled trades and the manager ahead says \"ready to turn on?\" - a hint only, nothing switches by itself.")
            said?.let { Text(it, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 4.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (f in TradeManager.FAMILIES) {
                    val accounts = if (f.paperOnly) listOf(TradeManager.Account.PAPER) else TradeManager.Account.entries
                    // An arm whose exits do not take the manager's word yet: OFF or SHADOW only.
                    val options = if (f.canAct) TradeManager.Mode.entries else listOf(TradeManager.Mode.OFF, TradeManager.Mode.SHADOW)
                    for (a in accounts) {
                        val label = "${f.name} ${a.name.lowercase(java.util.Locale.ENGLISH)}" +
                            (if (f.paperOnly) " (paper only)" else "") + (if (f.canAct) "" else " - records only for now")
                        BrainChoice(label, options.map { it.name }, policy.mode(f.key, a).name) { m ->
                            onChoose(f.key, a, TradeManager.Mode.valueOf(m))
                        }
                    }
                }
                Text("RECORD (PER STRATEGY, PAPER AND LIVE APART)", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                TradeManager.recordLines(records, policy).forEach { Note(it, Modifier.padding(vertical = 2.dp)) }
                Text("TRADES (NEWEST FIRST)", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                if (records.isEmpty()) Note("No trade managed yet.")
                records.reversed().take(40).forEach { r ->
                    Text("${r.trade.label} · ${r.trade.instrument} · ${r.trade.account.name.lowercase(java.util.Locale.ENGLISH)}",
                        style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    Note(TradeManager.cardLine(r))
                    r.notes.takeLast(6).forEach { n ->
                        Note("${com.optionslab.app.data.SmartWorkers.hhmm(n.atMs)} ${if (n.acted) "" else "(shadow) "}${n.kind.lowercase(java.util.Locale.ENGLISH).replace('_', ' ')}: ${n.words}",
                            Modifier.padding(start = 8.dp))
                    }
                    r.original?.let { o -> Note("Original rules: ${o.why.lowercase(java.util.Locale.ENGLISH)} at ${TradeManager.num(o.price)}", Modifier.padding(start = 8.dp)) }
                }
            }
        }
    }
}
