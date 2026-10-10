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
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
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
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { TradeManagerHost.ensureLoaded() } } }
    Rule()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val p = LocalPalette.current
        Text("Trade manager", style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        TradeManager.recordLines(records, policy).forEach { Note(it, Modifier.padding(top = 2.dp)) }
        records.filter { !it.closed }.forEach { r -> Note("${r.trade.label}: " + TradeManager.cardLine(r), Modifier.padding(top = 2.dp)) }
        BrassButton("Trade manager record", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { sheet = true }
    }
    if (sheet) TradeManagerSheet(model) { sheet = false }
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
