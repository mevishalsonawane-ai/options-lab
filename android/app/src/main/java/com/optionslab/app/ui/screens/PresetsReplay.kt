package com.optionslab.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.AlertOn
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Sparkline
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.pct
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.Alerts
import com.optionslab.engine.ReplayAccount
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.minuteText
import com.optionslab.engine.sandbox.SandboxCosts
import com.optionslab.engine.strategy.Presets
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.math.abs

private val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY")

private fun hm(t: String): LocalTime? = runCatching { LocalTime.parse(t.trim().padStart(5, '0')) }.getOrNull()

/**
 * Ready-made option sellers' baskets. Pick one, set the size, times and the
 * basket stop / target, see how it did over every harvested session, then add
 * it to Strategies (paper, not armed).
 */
@Composable
fun PresetsCard(model: AppModel) {
    val p = LocalPalette.current
    var id by remember { mutableStateOf(Presets.ALL.first().id) }
    var u by remember { mutableStateOf("NIFTY") }
    var lots by remember { mutableStateOf("1") }
    var entry by remember { mutableStateOf("09:20") }
    var exit by remember { mutableStateOf("15:15") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val result by model.preset.collectAsState()
    val preset = Presets.byId(id)!!

    val e = hm(entry); val x = hm(exit)
    val n = lots.toIntOrNull()
    val sl = stop.toDoubleOrNull(); val tg = target.toDoubleOrNull()
    val problem = when {
        n == null || n !in 1..50 -> "Lots must be 1 to 50"
        e == null || x == null -> "Times are HH:MM, like 09:20"
        e.isBefore(LocalTime.of(9, 15)) || x.isAfter(LocalTime.of(15, 25)) -> "Entry and exit must be between 09:15 and 15:25"
        !e.isBefore(x) -> "The exit must come after the entry"
        stop.isNotBlank() && (sl == null || sl <= 0) -> "The basket stop is a positive rupee amount"
        target.isNotBlank() && (tg == null || tg <= 0) -> "The basket target is a positive rupee amount"
        else -> null
    }

    LedgerCard(title = "Presets") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Presets.ALL.forEach { pr -> Token(pr.name, pr.id == id) { id = pr.id; model.preset.value = Load.Idle } }
        }
        Text(preset.blurb, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UNDERLYINGS.forEach { k -> Token(k, k == u) { u = k; model.preset.value = Load.Idle } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { PriceField(lots, { lots = it.filter(Char::isDigit) }, "Lots") }
            Column(Modifier.weight(1f)) { TimeField(entry, { entry = it }, "Entry") }
            Column(Modifier.weight(1f)) { TimeField(exit, { exit = it }, "Exit") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { PriceField(stop, { stop = it }, "Basket stop ₹") }
            Column(Modifier.weight(1f)) { PriceField(target, { target = it }, "Basket target ₹") }
        }
        val busy = result is Load.Busy
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton("Backtest", Modifier.weight(1f), busy = busy, enabled = !busy) {
                if (problem != null) Alerts.error(problem) else model.runPreset(id, u, n!!, e!!, x!!, sl, tg)
            }
            BrassButton("Add to Strategies", Modifier.weight(1f), tone = p.inkSoft) {
                if (problem != null) Alerts.error(problem) else model.addPreset(id, u, n!!, e!!, x!!, sl, tg)
            }
        }
        when (val r = result) {
            is Load.Busy -> Text(r.label, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
            is Load.Failed -> AlertOn(r.why, throttle = false)
            is Load.Done -> PresetResult(r.value)
            Load.Idle -> Note("Backtest replays it over every harvested session: every leg entered at the entry minute's close, the basket marked each minute, out on the stop, the target or the exit time, charges paid. No slippage beyond that.")
        }
    }
}

@Composable
private fun TimeField(v: String, set: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(v, { set(it.filter { c -> c.isDigit() || c == ':' }.take(5)) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

@Composable
private fun PresetResult(r: Presets.Result) {
    val p = LocalPalette.current
    val tone = { x: Double -> if (x >= 0) p.verdigris else p.oxblood }
    Column(Modifier.padding(top = 10.dp)) {
        var cum = 0.0
        val curve = listOf(0.0) + r.days.sortedBy { it.day }.map { cum += it.net; cum }
        Sparkline(curve, tone(r.net), Modifier.fillMaxWidth().height(56.dp))
        LedgerLine("Sessions", "${r.days.size}" + if (r.skipped > 0) " (${r.skipped} could not be priced)" else "")
        LedgerLine("Net after charges", rs(r.net, sign = true), tone(r.net))
        LedgerLine("Win rate", pct(r.winRate, 0))
        LedgerLine("Average day", rs(r.average, sign = true), tone(r.average))
        LedgerLine("Best / worst day", "${rs(r.best, sign = true)} / ${rs(r.worst, sign = true)}")
        LedgerLine("Max drawdown", rs(r.maxDrawdown, sign = true), p.oxblood)
        LedgerLine("Profit factor", r.profitFactor?.let { String.format(java.util.Locale.ENGLISH, "%.2f", it) } ?: "no losing day")
        val exits = r.days.groupingBy { it.reason }.eachCount()
        LedgerLine("Exits", listOf("Stop", "Target", "Time").joinToString(" · ") { "$it ${exits[it] ?: 0}" })
        Text("Last sessions", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
        r.days.sortedByDescending { it.day }.take(8).forEach { d ->
            LedgerLine("${d.day} · ${d.reason} ${minuteText(d.exitMinute)}", rs(d.net, sign = true), tone(d.net))
        }
    }
}

// ---- replay ------------------------------------------------------------------------------------

/**
 * Trade a past session candle by candle: pick a harvested day, step or play
 * the one-minute bars, buy or sell the index or the ATM call / put at the
 * current close, and watch the pretend P&L. The future stays hidden.
 */
@Composable
fun ReplayLab(model: AppModel) {
    val p = LocalPalette.current
    var u by remember { mutableStateOf("NIFTY") }
    val days = remember(u) { model.replayDays(u).sortedDescending() }
    var di by remember(u) { mutableIntStateOf(0) }
    val loaded by model.replay.collectAsState()
    var what by remember { mutableStateOf("Index") }
    var cursor by remember { mutableIntStateOf(5) }
    var playing by remember { mutableStateOf(false) }
    var lotsText by remember { mutableStateOf("1") }
    val acct = remember { ReplayAccount() }
    var rev by remember { mutableIntStateOf(0) }

    val session = (loaded as? Load.Done)?.value
    val series: Series? = remember(session, what) {
        session?.let { s ->
            val ix = s.index ?: return@let null
            if (what == "Index") ix else {
                val expiry = s.options.mapNotNull { it.expiry }.filter { !it.isBefore(s.day) }.minOrNull()
                val chain = s.options.filter { it.expiry == expiry && it.right == if (what == "ATM CE") Right.CE else Right.PE }
                val spot = ix.close[minOf(5, ix.size - 1)]
                chain.minByOrNull { abs(it.strike - spot) }
            }
        }
    }
    // A new day or instrument starts a fresh account at 09:20.
    LaunchedEffect(series) { acct.reset(); rev++; playing = false; cursor = series?.let { minOf(5, it.size - 1) } ?: 0 }
    LaunchedEffect(playing, series) {
        while (playing && series != null) {
            delay(400)
            if (cursor >= series.size - 1) playing = false else cursor++
        }
    }

    val lot = session?.let { s -> s.lotHint ?: runCatching { com.optionslab.engine.Lots.lotSizeOn(u, s.day) }.getOrNull() } ?: 0
    val lots = lotsText.toIntOrNull()

    Page {
        item { PageTitle("Replay", "Trade a past session candle by candle") }
        item {
            LedgerCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { UNDERLYINGS.forEach { k -> Token(k, k == u) { u = k } } }
                if (days.isEmpty()) Note("No harvested sessions for $u yet.") else {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrassButton("‹", tone = p.inkSoft, enabled = di < days.size - 1) { di++ }
                        Text(days[di].format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.ENGLISH)),
                            style = Type.title.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        BrassButton("›", tone = p.inkSoft, enabled = di > 0) { di-- }
                    }
                    BrassButton("Load this session", Modifier.fillMaxWidth().padding(top = 8.dp), busy = loaded is Load.Busy) { model.loadReplay(u, days[di]) }
                }
                (loaded as? Load.Failed)?.let { AlertOn(it.why, throttle = false) }
            }
        }
        if (session != null) item {
            LedgerCard(title = "$u · ${session.day}") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Index", "ATM CE", "ATM PE").forEach { k -> Token(k, k == what) { what = k } } }
                val s = series
                if (s == null || s.size == 0) Note("That instrument has no bars on this day.") else {
                    rev.let { }   // read so trades redraw the figures
                    val c = cursor.coerceIn(0, s.size - 1)
                    val px = s.close[c]
                    val name = if (what == "Index") u else "$u ${com.optionslab.engine.fmtG(s.strike)} ${s.right}"
                    Text("$name · ${minuteText(s.minutes[c])} · ${c + 1}/${s.size}", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
                    Text(String.format(java.util.Locale.ENGLISH, "%,.2f", px), style = Type.title.copy(fontSize = 22.sp))
                    fun ohlc(a: DoubleArray?) = a?.get(c)?.let { String.format(java.util.Locale.ENGLISH, "%,.2f", it) } ?: "—"
                    Text("O ${ohlc(s.open)}  H ${ohlc(s.high)}  L ${ohlc(s.low)}  C ${ohlc(s.close)}", style = Type.figure.copy(fontSize = 11.sp, color = p.inkSoft))
                    Sparkline(s.close.take(c + 1), p.ink, Modifier.fillMaxWidth().height(90.dp).padding(vertical = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrassButton(if (playing) "Pause" else "Play", Modifier.weight(1f), tone = p.inkSoft) { playing = !playing }
                        BrassButton("+1", Modifier.weight(1f), tone = p.inkSoft, enabled = c < s.size - 1) { cursor = minOf(s.size - 1, c + 1) }
                        BrassButton("+5", Modifier.weight(1f), tone = p.inkSoft, enabled = c < s.size - 1) { cursor = minOf(s.size - 1, c + 5) }
                        BrassButton("+30", Modifier.weight(1f), tone = p.inkSoft, enabled = c < s.size - 1) { cursor = minOf(s.size - 1, c + 30) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { PriceField(lotsText, { lotsText = it.filter(Char::isDigit) }, "Lots (lot $lot)") }
                        fun trade(side: Int) {
                            val n = lots
                            when {
                                n == null || n !in 1..50 -> Alerts.error("Lots must be 1 to 50")
                                lot <= 0 -> Alerts.error("The lot size for this day is not known")
                                else -> {
                                    val qty = n * lot
                                    val cost = if (what == "Index") 0.0 else SandboxCosts.charge(if (side > 0) "BUY" else "SELL", BigDecimal(px), qty).toDouble()
                                    acct.trade(s.minutes[c], side, qty, px, cost); rev++
                                    Alerts.success("${if (side > 0) "Bought" else "Sold"} $qty $name at ${String.format(java.util.Locale.ENGLISH, "%,.2f", px)}")
                                }
                            }
                        }
                        BrassButton("Buy", Modifier.weight(1f).padding(top = 6.dp), tone = p.verdigris) { trade(1) }
                        BrassButton("Sell", Modifier.weight(1f).padding(top = 6.dp), tone = p.oxblood) { trade(-1) }
                    }
                    val net = acct.net(px)
                    LedgerLine("Position", if (acct.position == 0) "flat" else "${acct.position} @ ${String.format(java.util.Locale.ENGLISH, "%,.2f", acct.average)}")
                    LedgerLine("Open P&L", rs(acct.unrealised(px), sign = true), if (acct.unrealised(px) >= 0) p.verdigris else p.oxblood)
                    LedgerLine("Booked", rs(acct.realised, sign = true))
                    if (acct.charges > 0) LedgerLine("Charges", rs(acct.charges))
                    LedgerLine("Net", rs(net, sign = true), if (net >= 0) p.verdigris else p.oxblood)
                    if (acct.fills.isNotEmpty()) {
                        Text("Trades", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
                        acct.fills.takeLast(10).reversed().forEach { f ->
                            LedgerLine("${minuteText(f.minute)} ${if (f.side > 0) "BUY" else "SELL"} ${f.qty}", String.format(java.util.Locale.ENGLISH, "%,.2f", f.price))
                        }
                    }
                    BrassButton("Start over", Modifier.fillMaxWidth().padding(top = 8.dp), tone = p.inkSoft) {
                        acct.reset(); rev++; playing = false; cursor = minOf(5, s.size - 1)
                    }
                    Note(if (what == "Index") "Index trades are points × lot, no charges: a stand-in for the future." else "Option trades pay the paper account's charges.")
                }
            }
        }
    }
}
