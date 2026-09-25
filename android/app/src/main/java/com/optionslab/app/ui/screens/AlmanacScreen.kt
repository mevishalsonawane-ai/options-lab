package com.optionslab.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.num
import com.optionslab.app.ui.pct
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.InkProgress
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.RollingFigure
import com.optionslab.app.ui.components.Sparkline
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.StatusDot
import com.optionslab.app.ui.components.VerdictDial
import com.optionslab.app.work.Jobs
import com.optionslab.engine.fmtG
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlmanacScreen(model: AppModel, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val quotes by model.quotes.collectAsState()
    val note by model.quoteNote.collectAsState()
    val ledger by model.ledger.collectAsState()
    val mark by model.openMark.collectAsState()
    val bt by model.backtest.collectAsState()
    val job by model.jobState.collectAsState()
    val alarms by model.alarms.collectAsState()
    val positions by model.livePositions.collectAsState()
    val broker by model.broker.collectAsState()

    // Quotes poll only while the Almanac is on screen AND the app is in the foreground.
    com.optionslab.app.ui.PollWhileStarted {
        model.startQuotes()
        try { kotlinx.coroutines.awaitCancellation() } finally { model.stopQuotes() }
    }
    LaunchedEffect(Unit) { if (bt is Load.Idle) model.runBacktest() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Page {
        if (!broker.configured) item {
            LedgerCard(title = "Link your Zerodha account") {
                Note("Trading, live or paper, opens once your Zerodha account is linked. It takes two minutes: create a Kite Connect app, then paste its API key and secret here.")
                Spacer(Modifier.height(10.dp))
                BrassButton("Link Zerodha", Modifier.fillMaxWidth()) { onGo("broker") }
            }
        }
        item {
            // The tape: every index, scrolling like a ticker.
            val tape = if (quotes.isEmpty()) "NIFTY ·  BANKNIFTY ·  INDIA VIX ·  awaiting the first print" else
                quotes.values.joinToString("      ◆      ") { q -> "${q.symbol} ${num(q.last, 2)}  ${if (q.change >= 0) "▲" else "▼"} ${num(kotlin.math.abs(q.change), 2)} (${pct(q.changePct)})" }
            Text(tape, style = Type.figure.copy(color = p.inkSoft, fontSize = 13.sp), maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sym in listOf("NIFTY", "BANKNIFTY", "INDIAVIX")) {
                    val q = quotes[sym]
                    LedgerCard(Modifier.weight(1f)) {
                        Text(if (sym == "INDIAVIX") "VIX" else sym, style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp))
                        if (q == null) Text("—", style = Type.figureLarge.copy(color = p.inkFaint, fontSize = 20.sp))
                        else {
                            RollingFigure(q.last, { num(it, if (sym == "INDIAVIX") 2 else 0) }, Type.figureLarge.copy(color = p.ink, fontSize = 19.sp), calm = s.reduceMotion)
                            Text("${if (q.change >= 0) "+" else ""}${pct(q.changePct)}", style = Type.figure.copy(fontSize = 12.sp, color = if (q.change >= 0) p.verdigris else p.oxblood))
                            Spacer(Modifier.height(4.dp))
                            Sparkline(q.spark, if (q.change >= 0) p.verdigris else p.oxblood, Modifier.fillMaxWidth().height(26.dp))
                        }
                    }
                }
            }
            note?.let { Note(it, Modifier.padding(top = 6.dp, start = 4.dp)) }
        }

        item {
            val expiries = Market.upcomingExpiries("NIFTY")
            val today = Market.today()
            LedgerCard(title = "The Calendar") {
                if (expiries.isEmpty()) {
                    Note("The expiry calendar comes from the instrument master. Run a harvest once (More → Data) and it fills in.")
                } else {
                    val next = expiries.first()
                    val days = ChronoUnit.DAYS.between(today, next)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (days == 0L) "EXPIRY DAY" else "NEXT NIFTY EXPIRY", style = Type.label.copy(color = p.inkSoft))
                            Text(next.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), style = Type.title.copy(color = p.ink))
                        }
                        if (days == 0L) Stamp("Today", p.oxblood) else {
                            Text("$days", style = Type.figureHuge.copy(color = p.brass))
                            Text(" day${if (days == 1L) "" else "s"}", style = Type.italic.copy(color = p.inkSoft))
                        }
                    }
                    if (days == 0L) Note("Entry at ${s.entry} IST: sell the put nearest ${pct(s.otmPct)} below the parity forward. Hold to cash settlement. Never buy it back.")
                    if (expiries.size > 1) Note("Then " + expiries.drop(1).take(3).joinToString(" · ") { it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) })
                }
            }
        }

        val open = ledger.firstOrNull { it.row.status == "open" }
        if (open != null) item {
            val tk = open.row.ticket
            val spot = quotes[tk.underlying]?.last
            LedgerCard(title = "Open Paper Ticket", accent = p.oxblood, onClick = { onGo("ticket") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SELL ${tk.underlying} ${fmtG(tk.strike)} PE", style = Type.title.copy(color = p.ink))
                        Text("x${tk.lots} lot (${tk.lotSize}) · credit ${"%.2f".format(tk.credit)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 13.sp))
                    }
                    Stamp("Paper", p.oxblood, animate = false)
                }
                Spacer(Modifier.height(8.dp))
                if (mark != null) {
                    Text("LIVE MARK", style = Type.label.copy(color = p.inkSoft))
                    RollingFigure(mark!!, { rs(it, true) }, Type.figureLarge.copy(color = if (mark!! >= 0) p.verdigris else p.oxblood), calm = s.reduceMotion)
                }
                LedgerLine("Breakeven", num(tk.breakeven))
                if (spot != null) {
                    val cushion = spot - tk.breakeven
                    LedgerLine("Cushion", "${num(cushion)} pts", if (cushion > 0) p.verdigris else p.oxblood)
                    InkProgress(((spot - tk.breakeven) / (tk.forward - tk.breakeven)).toFloat(), Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }

        if (s.live) item {
            LedgerCard(title = "Zerodha Positions · Live", accent = p.oxblood, onClick = { onGo("broker") }) {
                if (positions.isEmpty()) Note("No open positions, or not logged in today.")
                positions.filter { it.qty != 0 }.forEach { ps ->
                    LedgerLine("${ps.symbol} ×${ps.qty}", "${num(ps.last, 2)}  ${rs(ps.pnl, true)}", if (ps.pnl >= 0) p.verdigris else p.oxblood)
                }
                if (positions.isNotEmpty()) LedgerLine("Day P&L", rs(positions.sumOf { it.pnl }, true), if (positions.sumOf { it.pnl } >= 0) p.verdigris else p.oxblood)
            }
        }

        item {
            LedgerCard(title = "Live Watch") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (job.running) p.verdigris else p.inkFaint, pulsing = job.running)
                    Spacer(Modifier.width(8.dp))
                    Text(if (job.running) job.stage.ifEmpty { "Watching" } else "Idle", style = Type.body.copy(color = p.ink), modifier = Modifier.weight(1f))
                    if (job.running) BrassButton("Stop", tone = p.oxblood) { model.stopLive() }
                    else BrassButton("Watch now") { model.startJob(Jobs.Kind.LIVE) }
                }
                Note("A notification that updates itself every minute: index levels, your ticket's live mark and cushion, risk alerts and ${alarms.count { it.enabled }} price alarm(s). Starts itself at 09:14 on market days.")
            }
        }

        item {
            LedgerCard(title = "The One Result", onClick = { onGo("trials") }) {
                when (val b = bt) {
                    is Load.Busy -> FullSpinner(b.label, b.progress)
                    is Load.Failed -> Note(b.why)
                    is Load.Done -> {
                        val c = b.value.combined
                        if (c == null) Note("no sessions produced a trade") else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                RollingFigure(100 * c.winRate, { "%.2f%%".format(it) }, Type.figureHuge.copy(color = p.verdigris), calm = s.reduceMotion)
                                Spacer(Modifier.width(8.dp))
                                Text("won, ${c.n} expiries", style = Type.italic.copy(color = p.inkSoft))
                            }
                            LedgerLine("Mean per trade", rs(c.mean, true), if (c.mean >= 0) p.verdigris else p.oxblood)
                            LedgerLine("Worst trade", rs(c.worst, true), p.oxblood)
                            LedgerLine("Total", rs(c.total, true))
                            AnimatedVisibility(shown, enter = fadeIn(tween(200)) + expandVertically(tween(200))) {
                                Note("What this pays, honestly: 5% to 12% a year on properly capitalised money. The loss is UNBOUNDED; the record is a property of a 2023-2026 bull market.")
                            }
                        }
                    }
                    Load.Idle -> Note("Preparing…")
                }
            }
        }

        item {
            val verdict = SecurePrefs.getString("health.verdict")
            LedgerCard(title = "Strategy Health", onClick = { onGo("health") }) {
                if (verdict == null) Note("Not yet checked. Open Health to run the kill conditions.") else {
                    VerdictDial(when (verdict) { "PASS" -> 0; "WARN" -> 1; else -> 2 }, calm = s.reduceMotion)
                    Text(verdict, style = Type.title.copy(color = when (verdict) { "PASS" -> p.verdigris; "WARN" -> p.amber; else -> p.oxblood }),
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Note("The verdict is the WORST check, never an average.", Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }

        item {
            LedgerCard(title = "Alarms", onClick = { onGo("alarms") }) {
                val on = alarms.filter { it.enabled }
                if (on.isEmpty()) Note("No price alarms set. Tap to add one - \"NIFTY falls below 24,000\".")
                else on.take(4).forEach { a -> LedgerLine(a.symbol, "${if (a.above) "≥" else "≤"} ${num(a.level, 2)}") }
            }
        }
    }
}
