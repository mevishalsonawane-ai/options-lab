package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.Market
import com.optionslab.app.data.Strategies
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.StatusDot
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.strategy.LegDef
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import com.optionslab.engine.strategy.Product
import com.optionslab.engine.strategy.RunMode
import com.optionslab.engine.strategy.RunStatus
import com.optionslab.engine.strategy.SchedulerConfig
import com.optionslab.engine.strategy.Segment
import com.optionslab.engine.strategy.StrategyDef
import com.optionslab.engine.strategy.StrategyType
import com.optionslab.engine.strategy.StrikeMode
import com.optionslab.engine.strategy.Trail
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
private fun p2(x: Double?) = x?.let { String.format(Locale.ENGLISH, "%,.2f", it) } ?: "—"

/**
 * IraAlgo's Strategy Module: build a basket of option legs with per-leg stop,
 * target and trail and basket stop/target/lock, run it on the paper account
 * or on Zerodha, and let the phone manage the exits.
 */
@Composable
fun StrategiesScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val list by model.strategies.collectAsState()
    val log by model.strategyLog.collectAsState()
    var editing by remember { mutableStateOf<StrategyDef?>(null) }
    var confirmLive by remember { mutableStateOf<Long?>(null) }
    var reauthFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        model.refreshStrategies()
        while (true) {
            delay(if (Market.isOpen()) 15_000 else 60_000)
            model.refreshStrategies(tick = list.any { it.running })
        }
    }

    Page {
        item { PageTitle("Strategies", "Baskets with stops, targets and trails, managed by the phone") }
        item {
            LedgerCard {
                Note("Paper runs trade the sandbox account. Live runs trade Zerodha: you start them yourself with your PIN or fingerprint; after that the strategy's own exits (stops, targets, lock profit, the daily loss limit, the exit time) go out without asking, and only ever close what the run holds.")
                BrassButton("New strategy", Modifier.fillMaxWidth().padding(top = 8.dp)) { editing = blankStrategy() }
            }
        }
        if (list.isEmpty()) item { LedgerCard { Note("No strategies yet.") } }
        list.forEach { e -> item(key = e.def.id) { StrategyCard(model, e, s.live && s.allowRealOrders, onEdit = { editing = e.def }, onLive = { confirmLive = e.def.id }) } }
        if (log.isNotEmpty()) item {
            LedgerCard(title = "Audit log") {
                val fmt = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
                log.take(40).forEach { l ->
                    val tone = when (l.severity) { "critical" -> p.oxblood; "warn" -> p.amber; else -> p.inkSoft }
                    Text("${java.time.Instant.ofEpochMilli(l.at).atZone(com.optionslab.engine.IST).format(fmt)} · ${l.strategy}",
                        style = Type.figure.copy(color = p.inkFaint, fontSize = 10.sp))
                    Text(l.message, style = Type.bodySmall.copy(color = tone), modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }

    editing?.let { d -> StrategyEditor(model, d) { editing = null } }
    confirmLive?.let { id ->
        val d = list.firstOrNull { it.def.id == id }?.def
        AlertDialog(
            onDismissRequest = { confirmLive = null }, properties = secure,
            title = { Text("Start ${d?.name} on Zerodha?", style = Type.title) },
            text = {
                Column {
                    Text("Every leg is sent as a MARKET order now, BUY legs first. The strategy then manages its own exits.", style = Type.bodySmall)
                    d?.legs?.forEach { l -> Text("• ${if (l.position == Position.B) "BUY" else "SELL"} ${l.lots} lot ${l.atmOffset ?: ""} ${l.optionType?.wire ?: ""} ${l.expiry ?: ""}", style = Type.bodySmall) }
                }
            },
            confirmButton = { TextButton({ reauthFor = id; confirmLive = null }) { Text("Confirm with PIN") } },
            dismissButton = { TextButton({ confirmLive = null }) { Text("Cancel") } },
        )
    }
    reauthFor?.let { id -> Reauth(model, onOk = { reauthFor = null; model.startStrategy(id, live = true) }, onCancel = { reauthFor = null }) }
}

@Composable
private fun StrategyCard(model: AppModel, e: Strategies.Entry, liveAllowed: Boolean, onEdit: () -> Unit, onLive: () -> Unit) {
    val p = LocalPalette.current
    val d = e.def
    val r = e.run
    var deleting by remember { mutableStateOf(false) }
    LedgerCard(title = d.name, accent = if (e.running) (if (r?.mode == RunMode.LIVE) p.oxblood else p.verdigris) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (e.running) p.verdigris else p.inkFaint, pulsing = e.running)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text("${d.underlying} · ${d.strategyType.wire} · ${d.legs.size} legs · ${d.product.wire}" +
                (d.entryTime?.let { " · $it–${d.exitTime}" } ?: ""), style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
            if (e.running && r != null) Stamp(if (r.mode == RunMode.LIVE) "Live" else "Paper", if (r.mode == RunMode.LIVE) p.oxblood else p.verdigris, animate = false)
        }
        if (r != null) {
            LedgerLine("Run", "${r.status.name.lowercase()}${r.stopReason?.let { " · $it" } ?: ""}")
            LedgerLine("P&L", "${rs(r.pnlTotal, true)}  (realised ${rs(r.pnlRealized, true)})", if (r.pnlTotal >= 0) p.verdigris else p.oxblood)
            if (r.lockArmed) LedgerLine("Lock floor", r.lockFloor?.let { rs(it, true) } ?: "armed")
            r.startError?.let { Text("✕ $it", style = Type.bodySmall.copy(color = p.oxblood)) }
            r.legs.values.forEach { l ->
                Rule(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${if (l.position == "B") "BUY" else "SELL"} ${l.symbol} ×${l.qty}", style = Type.figure.copy(color = if (l.position == "S") p.oxblood else p.verdigris, fontSize = 12.sp))
                        Text("${l.status} · in ${p2(l.entryAvg.takeIf { it > 0 })} · ltp ${p2(l.ltp)} · SL ${p2(l.effectiveSl)} · TG ${p2(l.effectiveTarget)}",
                            style = Type.figure.copy(color = p.inkSoft, fontSize = 10.sp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rs(l.mtm + l.realizedPnl, true), style = Type.figure.copy(color = if (l.mtm + l.realizedPnl >= 0) p.verdigris else p.oxblood, fontSize = 12.sp))
                        if (e.running && l.status == "open") TextButton({ model.closeStrategyLeg(d.id, l.legId) }) { Text("Exit", style = Type.label.copy(color = p.oxblood)) }
                    }
                }
            }
        }
        d.scheduler?.takeIf { it.enabled }?.let { sc ->
            LedgerLine("Schedule", "${sc.days.joinToString(",") { it.name.take(3).lowercase() }} ${sc.startTime ?: ""}→${sc.autoStopTime ?: ""} · ${sc.defaultMode.wire}")
        }
        Spacer(Modifier.height(8.dp))
        if (e.running) BrassButton("Stop and close all", Modifier.fillMaxWidth(), tone = p.oxblood) { model.stopStrategy(d.id) }
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrassButton("Start paper", Modifier.weight(1f), tone = p.verdigris) { model.startStrategy(d.id, live = false) }
                BrassButton("Start live", Modifier.weight(1f), tone = p.oxblood, enabled = liveAllowed && d.liveEnabled, onClick = onLive)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onEdit) { Text("Edit", style = Type.label.copy(color = p.inkSoft)) }
                TextButton({ model.setStrategyLive(d.id, !d.liveEnabled) }) {
                    Text(if (d.liveEnabled) "Disable live" else "Enable live", style = Type.label.copy(color = if (d.liveEnabled) p.oxblood else p.inkSoft))
                }
                TextButton({ deleting = true }) { Text("Delete", style = Type.label.copy(color = p.oxblood)) }
            }
            if (!liveAllowed) Note("Live runs need LIVE mode and real orders on (Cabinet → Zerodha), and \"Enable live\" on this strategy.")
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, properties = secure,
        title = { Text("Delete ${d.name}?", style = Type.title) },
        confirmButton = { TextButton({ deleting = false; model.deleteStrategy(d.id) }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep") } },
    )
}

private fun blankStrategy() = StrategyDef(
    name = "Short straddle", underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
    entryTime = LocalTime.of(9, 20), exitTime = LocalTime.of(15, 15),
    legs = listOf(
        LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 30.0),
        LegDef(2, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 30.0),
    ),
)

/** Mutable form state for one leg. */
private class LegForm(l: LegDef) {
    var buy by mutableStateOf(l.position == Position.B)
    var ce by mutableStateOf(l.optionType == OptionType.CE)
    var offset by mutableStateOf(l.atmOffset ?: "ATM")
    var lots by mutableStateOf(l.lots)
    var expiry by mutableStateOf(l.expiry ?: "weekly")
    var sl by mutableStateOf(l.slPts?.toString() ?: "")
    var target by mutableStateOf(l.targetPts?.toString() ?: "")
    var trailX by mutableStateOf(l.trail?.x?.toString() ?: "")
    var trailY by mutableStateOf(l.trail?.y?.toString() ?: "")

    fun toLeg(id: Int) = LegDef(
        id, Segment.OPTIONS, if (buy) Position.B else Position.S, lots, if (ce) OptionType.CE else OptionType.PE, StrikeMode.ATM, offset,
        expiry = expiry, slPts = sl.toDoubleOrNull(), targetPts = target.toDoubleOrNull(),
        trail = trailX.toDoubleOrNull()?.takeIf { it > 0 }?.let { Trail(it, trailY.toDoubleOrNull() ?: 0.0) },
    )
}

@Composable
private fun StrategyEditor(model: AppModel, d: StrategyDef, onClose: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(d.name) }
    var underlying by remember { mutableStateOf(d.underlying) }
    var intraday by remember { mutableStateOf(d.strategyType == StrategyType.INTRADAY) }
    var entry by remember { mutableStateOf(d.entryTime?.toString() ?: "09:20") }
    var exit by remember { mutableStateOf(d.exitTime?.toString() ?: "15:15") }
    var mis by remember { mutableStateOf(d.product == Product.MIS) }
    var overallSl by remember { mutableStateOf(d.overallSlMtm?.toString() ?: "") }
    var overallTg by remember { mutableStateOf(d.overallTargetMtm?.toString() ?: "") }
    var dailyLoss by remember { mutableStateOf(d.dailyLossLimitInr?.toString() ?: "") }
    var trailToEntry by remember { mutableStateOf(d.trailSlToEntry) }
    var sched by remember { mutableStateOf(d.scheduler?.enabled == true) }
    var schedLive by remember { mutableStateOf(d.scheduler?.defaultMode == RunMode.LIVE) }
    val days = remember { mutableStateListOf<DayOfWeek>().apply { addAll(d.scheduler?.days ?: listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) } }
    val legs = remember { mutableStateListOf<LegForm>().apply { d.legs.forEach { add(LegForm(it)) } } }
    var err by remember { mutableStateOf<String?>(null) }
    fun time(t: String) = runCatching { LocalTime.parse(t) }.getOrNull()

    AlertDialog(
        onDismissRequest = onClose, properties = secure,
        title = { Text(if (d.id == 0L) "New strategy" else "Edit ${d.name}", style = Type.title) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(520.dp)) {
                item {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
                    ParamTokens("Type", listOf("Intraday" to intraday, "Positional" to !intraday)) { intraday = it == 0 }
                    ParamTokens("Product", listOf("NRML" to !mis, "MIS" to mis)) { mis = it == 1 }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(entry, { entry = it.take(5) }, label = { Text("Entry HH:MM") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(exit, { exit = it.take(5) }, label = { Text("Exit HH:MM") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                legs.forEachIndexed { i, l ->
                    item {
                        Rule(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LEG ${i + 1}", style = Type.label.copy(color = p.brass), modifier = Modifier.weight(1f))
                            if (legs.size > 1) TextButton({ legs.removeAt(i) }) { Text("Remove", style = Type.label.copy(color = p.oxblood)) }
                        }
                        ParamTokens("Side", listOf("SELL" to !l.buy, "BUY" to l.buy)) { l.buy = it == 1 }
                        ParamTokens("Option", listOf("CE" to l.ce, "PE" to !l.ce)) { l.ce = it == 0 }
                        val offs = listOf("ITM2", "ITM1", "ATM", "OTM1", "OTM2", "OTM3", "OTM4", "OTM5")
                        ParamTokens("Strike", offs.map { it to (it == l.offset) }) { l.offset = offs[it] }
                        val exps = listOf("weekly", "next_week", "monthly")
                        ParamTokens("Expiry", exps.map { it.replace('_', ' ') to (it == l.expiry) }) { l.expiry = exps[it] }
                        val lotsOpt = listOf(1, 2, 3, 5)
                        ParamTokens("Lots", lotsOpt.map { "$it" to (it == l.lots) }) { l.lots = lotsOpt[it] }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumField("SL pts", l.sl, Modifier.weight(1f)) { l.sl = it }
                            NumField("Target pts", l.target, Modifier.weight(1f)) { l.target = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumField("Trail every", l.trailX, Modifier.weight(1f)) { l.trailX = it }
                            NumField("…move SL by", l.trailY, Modifier.weight(1f)) { l.trailY = it }
                        }
                    }
                }
                item {
                    if (legs.size < StrategyDef.MAX_LEGS) TextButton({ legs.add(LegForm(LegDef(legs.size + 1, Segment.OPTIONS, Position.B, 1, OptionType.PE, StrikeMode.ATM, "OTM2", expiry = "weekly"))) }) {
                        Text("+ Add leg", style = Type.label.copy(color = p.brass))
                    }
                    Rule(Modifier.padding(vertical = 8.dp))
                    Text("BASKET", style = Type.label.copy(color = p.brass))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("Overall SL Rs", overallSl, Modifier.weight(1f)) { overallSl = it }
                        NumField("Overall target Rs", overallTg, Modifier.weight(1f)) { overallTg = it }
                    }
                    NumField("Daily loss limit Rs", dailyLoss, Modifier.fillMaxWidth()) { dailyLoss = it }
                    ToggleRow("Trail SL to entry", "When one leg's stop fires, move the others' stops to their entry prices.", trailToEntry) { trailToEntry = it }
                    Rule(Modifier.padding(vertical = 8.dp))
                    ToggleRow("Schedule", "Start at the entry time and square off at the exit time on the chosen days.", sched) { sched = it }
                    if (sched) {
                        val all = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                        ParamTokens("Days", all.map { it.name.take(3) to (it in days) }) { i -> if (all[i] in days) days.remove(all[i]) else days.add(all[i]) }
                        ParamTokens("Scheduled start", listOf("Paper" to !schedLive, "Live (asks you)" to schedLive)) { schedLive = it == 1 }
                    }
                    err?.let { Text(it, style = Type.bodySmall.copy(color = p.oxblood), modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        },
        confirmButton = {
            TextButton({
                val def = d.copy(
                    name = name.trim(), underlying = underlying, underlyingExchange = "NSE_INDEX",
                    strategyType = if (intraday) StrategyType.INTRADAY else StrategyType.POSITIONAL,
                    entryTime = time(entry), exitTime = time(exit), product = if (mis) Product.MIS else Product.NRML,
                    legs = legs.mapIndexed { i, l -> l.toLeg(i + 1) },
                    overallSlMtm = overallSl.toDoubleOrNull(), overallTargetMtm = overallTg.toDoubleOrNull(),
                    dailyLossLimitInr = dailyLoss.toDoubleOrNull(), trailSlToEntry = trailToEntry,
                    scheduler = if (sched) SchedulerConfig(true, days.sortedBy { it.value }, time(entry), time(exit),
                        if (schedLive) RunMode.LIVE else RunMode.SANDBOX) else null,
                )
                model.saveStrategy(def) { e -> if (e == null) onClose() else err = e }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(9)) }, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
