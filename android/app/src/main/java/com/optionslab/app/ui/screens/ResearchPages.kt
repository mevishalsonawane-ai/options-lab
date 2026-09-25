package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.Store
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.SignalResult
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.num
import com.optionslab.app.ui.pct
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Costs
import com.optionslab.engine.Ic
import com.optionslab.engine.Lots
import com.optionslab.engine.Sizing
import com.optionslab.engine.fmtG
import com.optionslab.engine.minuteText
import java.time.LocalDate

// ---- the IC table (the `ic` command) -------------------------------------------

@Composable
fun IcPage(model: AppModel) {
    val p = LocalPalette.current
    val st by model.ic.collectAsState()
    var underlying by remember { mutableStateOf("NIFTY") }
    var regime by remember { mutableStateOf("quoted") }
    var complete by remember { mutableStateOf(false) }
    Page {
        item { PageTitle("The IC Table", "M2: measurement before any rule") }
        item {
            LedgerCard {
                Note("Signed option order flow at 1, 5, 15 and 30 minutes, each with its null band, its partial IC after controls, and its edge over the round-trip cost. A raw IC is never shown without its partial - that omission is how PCR and max pain look real.")
                ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
                ParamTokens("Regime", Costs.REGIMES.map { it to (it == regime) }) { regime = Costs.REGIMES[it] }
                ParamTokens("Sessions", listOf("all" to !complete, "same-day chains only" to complete)) { complete = it == 1 }
                Spacer(Modifier.height(10.dp))
                BrassButton("Measure", Modifier.fillMaxWidth(), busy = st is Load.Busy) { model.runIc(underlying, regime, complete) }
            }
        }
        when (val s = st) {
            is Load.Busy -> item { LedgerCard { FullSpinner(s.label, s.progress) } }
            is Load.Failed -> item { LedgerCard { Note(s.why) } }
            is Load.Done -> {
                val r = s.value
                item {
                    LedgerCard(title = "${r.underlying}: ${r.sessions.size} sessions") {
                        Text("${r.sessions.first()} .. ${r.sessions.last()}", style = Type.figure.copy(fontSize = 12.sp, color = p.inkSoft))
                        LedgerLine("Median active premium", "Rs %.2f".format(r.medianPremium))
                        LedgerLine("Lot / regime", "${r.lot} / ${r.regime}")
                        LedgerLine("Round-trip break-even", "${num(r.breakeven, 2)} pts")
                        LedgerLine("2x cost bar", "${num(2 * r.breakeven, 2)} pts")
                    }
                }
                r.rows.forEach { row -> item { IcRowCard(row) } }
                item {
                    LedgerCard(accent = if (r.cleared.isEmpty()) p.brass else p.verdigris) {
                        if (r.cleared.isEmpty()) {
                            Text("Nothing clears the 2x cost bar while beating its null band.", style = Type.body.copy(color = p.ink))
                            Note("That is a result, not a failure - it is the measurement that should precede writing any rule.")
                        } else r.cleared.forEach { Text("CLEARS 2x COST: ${it.feature} h=${it.horizon} edge_over_cost=${"%.2f".format(it.edgeOverCost)}x", style = Type.figure.copy(color = p.verdigris)) }
                    }
                }
            }
            Load.Idle -> Unit
        }
    }
}

@Composable
private fun IcRowCard(r: Ic.IcRow) {
    val p = LocalPalette.current
    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.feature}  h=${r.horizon}", style = Type.title.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            if (!r.monotone) Stamp("Non-monotone", p.amber, animate = false)
            else if (r.clearsCost && r.beatsNull) Stamp("Clears", p.verdigris, animate = false)
        }
        LedgerLine("IC / partial", "%+.4f / %+.4f".format(r.ic, r.partialIc))
        LedgerLine("Daily mean [95%]", "%+.4f [%+.4f, %+.4f]".format(r.dailyIcMean, r.dailyIcLo, r.dailyIcHi))
        LedgerLine("iid band", "[%+.4f, %+.4f]".format(r.icIidLo, r.icIidHi))
        LedgerLine("Null 95th pct", "%.4f".format(r.nullIcHi), if (r.beatsNull) p.verdigris else p.inkSoft)
        LedgerLine("Decile spread / leg", "%+.2f / %+.2f pts".format(r.decileSpreadPts, r.singleLegEdgePts))
        LedgerLine("Edge over cost", "%.2fx".format(r.edgeOverCost), if (r.clearsCost) p.verdigris else p.oxblood)
        Text("n=${r.nObs}, sessions ${r.nSessionsMeasured}/${r.nSessions}", style = Type.figure.copy(fontSize = 11.sp, color = p.inkFaint))
    }
}

// ---- Signal Lab: the UT Bot + LinReg ports ---------------------------------------

@Composable
fun SignalPage(model: AppModel) {
    val p = LocalPalette.current
    val st by model.signal.collectAsState()
    var underlying by remember { mutableStateOf("NIFTY") }
    var indicator by remember { mutableStateOf("utbot") }
    var key by remember { mutableFloatStateOf(2f) }
    var atr by remember { mutableIntStateOf(1) }
    var length by remember { mutableIntStateOf(100) }
    var days by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(underlying) {
        days = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Store.barDays(underlying) }
        day = days.lastOrNull()
    }
    Page {
        item { PageTitle("Signal Lab", "Buy signal → ATM call; sell → ATM put; next-bar entry; costs always") }
        item {
            LedgerCard {
                ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
                ParamTokens("Indicator", listOf("UT Bot" to (indicator == "utbot"), "LinReg flip" to (indicator == "linreg"))) { indicator = if (it == 0) "utbot" else "linreg" }
                ParamTokens("Session", days.takeLast(8).map { it.toString().substring(5) to (it == day) }) { day = days.takeLast(8)[it] }
                if (indicator == "utbot") {
                    Text("Key value a = ${"%.1f".format(key)}   ·   ATR period c = $atr", style = Type.figure.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
                    Slider(key, { key = (it * 2).toInt() / 2f }, valueRange = 0.5f..5f)
                    Slider(atr.toFloat(), { atr = it.toInt().coerceAtLeast(1) }, valueRange = 1f..20f)
                } else {
                    Text("Window length = $length bars", style = Type.figure.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
                    Slider(length.toFloat(), { length = it.toInt() }, valueRange = 20f..200f)
                }
                BrassButton("Replay the session", Modifier.fillMaxWidth(), enabled = day != null, busy = st is Load.Busy) {
                    day?.let { model.runSignal(underlying, it, indicator, key.toDouble(), atr, length, if (underlying == "NIFTY") 65 else 30) }
                }
                Note("Run settings from the PC port: a = 2, ATR period 1, Heikin Ashi off. ATR(1) is the bar's own true range, so this is far twitchier than the script's default ATR(10).")
            }
        }
        when (val s = st) {
            is Load.Busy -> item { LedgerCard { FullSpinner(s.label) } }
            is Load.Failed -> item { LedgerCard { Note(s.why) } }
            is Load.Done -> signalResult(s.value)
            Load.Idle -> Unit
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.signalResult(r: SignalResult) {
    item {
        val p = LocalPalette.current
        LedgerCard(title = "${r.underlying} ${r.day}") {
            SignalChart(r)
            val net = r.trades.sumOf { it.netPnl }
            LedgerLine("Chain", r.expiry?.let { "expiry $it" } ?: "none")
            LedgerLine("Signals", "${r.buys.count { it }} buy · ${r.sells.count { it }} sell")
            LedgerLine("Trades", "${r.trades.size}, won ${r.trades.count { it.netPnl > 0 }}")
            LedgerLine("Net", rs(net, true), if (net >= 0) p.verdigris else p.oxblood)
            Note("A flat option price must produce a LOSS: costs are charged on every trade and cannot be switched off.")
        }
    }
    item {
        val p = LocalPalette.current
        LedgerCard(title = "Trades") {
            if (r.trades.isEmpty()) Note("No fillable trades.")
            r.trades.forEachIndexed { i, t ->
                if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${minuteText(t.entryMinute)}→${minuteText(t.exitMinute)}  ${t.right} ${fmtG(t.strike)}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                        Text("${"%.2f".format(t.entryPx)} → ${"%.2f".format(t.exitPx)} · ${t.exitReason.replace('_', ' ')}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                    }
                    Text(rs(t.netPnl, true), style = Type.figure.copy(color = if (t.netPnl >= 0) p.verdigris else p.oxblood))
                }
            }
        }
    }
}

@Composable
private fun SignalChart(r: SignalResult) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val c = r.bars.close
        if (c.size < 2) return@Canvas
        val lo = c.min(); val hi = c.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = size.width * i / (c.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
        val path = Path().apply { c.indices.forEach { i -> if (i == 0) moveTo(x(i), y(c[i])) else lineTo(x(i), y(c[i])) } }
        drawPath(path, p.ink, style = Stroke(1.4.dp.toPx()))
        for (i in c.indices) {
            if (r.buys[i]) drawCircle(p.verdigris, 3.5.dp.toPx(), Offset(x(i), y(c[i]) + 8.dp.toPx()))
            if (r.sells[i]) drawCircle(p.oxblood, 3.5.dp.toPx(), Offset(x(i), y(c[i]) - 8.dp.toPx()))
        }
    }
}

// ---- Sizing & the tail ------------------------------------------------------------

@Composable
fun SizingPage(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    var capital by remember { mutableFloatStateOf(s.capital.toFloat()) }
    var survive by remember { mutableFloatStateOf((s.survive * 100).toFloat()) }
    val plan = runCatching { Sizing.planPosition(capital.toDouble(), survive / 100.0) }
    Page {
        item { PageTitle("Sizing & Tail", "Sizing does not shrink the loss; it decides whether it is terminal") }
        item {
            LedgerCard {
                Text("Capital ${rs(capital.toDouble())}", style = Type.figure.copy(color = p.ink))
                Slider(capital, { capital = (it / 10_000).toInt() * 10_000f }, valueRange = 100_000f..2_000_000f)
                Text("Survive a ${"%.0f".format(survive)}% expiry day", style = Type.figure.copy(color = p.ink))
                Slider(survive, { survive = it.toInt().toFloat() }, valueRange = -20f..-1f)
                Rule(Modifier.padding(vertical = 6.dp))
                plan.onSuccess { pl ->
                    LedgerLine("Lots", "${pl.lots} (depth ceiling ${Sizing.MAX_LOTS_ON_DEPTH})")
                    LedgerLine("Capital per lot", rs(pl.capitalPerLot))
                    LedgerLine("Expected", "${rs(pl.expectedAnnualRs)}/yr = ${pct(pl.expectedAnnualPct)}", p.verdigris)
                    LedgerLine("Worst case", rs(pl.worstCaseRs), p.oxblood)
                    Stamp("Unbounded beyond this move", p.oxblood, angle = -3f)
                }.onFailure { Note(it.message ?: "") }
                Spacer(Modifier.height(8.dp))
                BrassButton("Use for the trial", Modifier.fillMaxWidth(), tone = p.inkSoft) {
                    model.update { it.copy(capital = capital.toDouble(), survive = survive / 100.0) }
                    model.say("Sizing saved for the trial.")
                }
            }
        }
        item {
            LedgerCard(title = "The Tail, per lot of ${Sizing.LOT_SIZE}") {
                Note("Strike 0.75% below a 24,000 forward, median credit Rs ${Sizing.MEDIAN_CREDIT_RS.toInt()}. Pure payoff arithmetic: the sample contains no such day, so no backtest can price it.")
                Spacer(Modifier.height(6.dp))
                for (m in listOf(-0.01, -0.02, -0.03, -0.06, -0.13, -0.20)) {
                    val loss = Sizing.lossAtMove(m)
                    LedgerLine("index ${pct(m, 0)}", "${rs(loss)} = ${"%.0f".format(loss / Sizing.MEDIAN_CREDIT_RS)} wins", if (loss > 0) p.oxblood else p.verdigris)
                }
                Note("Exchange margin is only about ${rs(Sizing.EXCHANGE_MARGIN_RS)}/lot. Margin is NOT the binding constraint - survival is. -13% is a 2020-03-23 repeat.")
            }
        }
    }
}

// ---- Cost calculator ---------------------------------------------------------------

@Composable
fun CostsPage(model: AppModel) {
    val p = LocalPalette.current
    var premium by remember { mutableFloatStateOf(4.85f) }
    var lot by remember { mutableIntStateOf(65) }
    var regime by remember { mutableStateOf("quoted") }
    val (sell, buy, rt) = model.costs(premium.toDouble(), lot, 1, regime)
    Page {
        item { PageTitle("Cost Calculator", "There is deliberately no zero-cost regime") }
        item {
            LedgerCard {
                Text("Premium Rs ${"%.2f".format(premium)}", style = Type.figure.copy(color = p.ink))
                Slider(premium, { premium = (it * 20).toInt() / 20f }, valueRange = 0.5f..400f)
                ParamTokens("Lot", listOf(65, 75, 50, 25, 30, 15).map { "$it" to (it == lot) }) { lot = listOf(65, 75, 50, 25, 30, 15)[it] }
                ParamTokens("Regime", Costs.REGIMES.map { it to (it == regime) }) { regime = Costs.REGIMES[it] }
                LedgerLine("Spread per unit", "Rs %.3f (law 0.162 + 0.00292 x premium)".format(Costs.spreadPerUnit(premium.toDouble(), regime)))
                LedgerLine("Flat 0.9% would say", "Rs %.3f".format(0.009 * premium))
            }
        }
        for ((title, c) in listOf("Sell and hold to settlement" to sell, "Buy and hold to settlement" to buy, "Full round trip" to rt)) item {
            LedgerCard(title = title) {
                c.items().forEach { (k, v) -> LedgerLine(k, "Rs %.2f".format(v)) }
                Rule(Modifier.padding(vertical = 4.dp))
                LedgerLine("Total", "Rs %.2f (%s of premium)".format(c.total, pct(c.fractionOfPremium)), p.oxblood)
            }
        }
        item {
            LedgerCard {
                LedgerLine("Round-trip break-even at delta 0.5", "${num(Costs.breakevenIndexPoints(premium.toDouble(), lot, 1, regime, 0.5), 2)} index pts")
                Note("Brokerage is a flat Rs 20 an order, so the explicit fraction depends on the premium. The spread is 70-89% of the total and is an assumption, not data.")
            }
        }
    }
}

// ---- Lot history ---------------------------------------------------------------

@Composable
fun LotsPage() {
    val p = LocalPalette.current
    val today = LocalDate.now()
    Page {
        item { PageTitle("Lot History", "Read from NSE bhavcopy NewBrdLotQty, not assumed") }
        for ((u, rows) in Lots.LOT_HISTORY) item {
            LedgerCard(title = u) {
                rows.forEach { (d, lot) -> LedgerLine("from $d", "$lot") }
                Rule(Modifier.padding(vertical = 4.dp))
                LedgerLine("Today", "${Lots.lotSizeOn(u, today)}", p.brass)
                Note("Coverage starts ${Lots.coverageStart(u)}. Earlier dates RAISE rather than extrapolate; the chain's own open interest names the lot first.")
            }
        }
        item {
            LedgerCard {
                Note("On 2025-01-30 every NIFTY contract expiring that day carried lot 25 while the rest of the book was at 75 - the date table's answer is three times too large. That is why a dated backtest asks the chain first.")
            }
        }
    }
}

// ---- Research notes -------------------------------------------------------------

@Composable
fun NotesPage() {
    val p = LocalPalette.current
    val notes = listOf(
        "The one result" to "On a NIFTY expiry day at 11:00 IST, sell one put at the nearest listed strike to F x 0.9925, F the put-call-parity forward. Hold to cash settlement. Never buy it back. 170 sessions, 2023-01-05 to 2026-04-13.",
        "What this is not" to "The loss is unbounded. A -6% expiry costs about Rs 82,000 per lot. Capacity is about 2 lots: median near-ATM top-of-book depth is 2.0 lots. It pays roughly Rs 41,000 a year, full stop.",
        "Everything directional was closed" to "Six signal families across five horizons reached a hit-rate ceiling of 51.2% against the 59-74% required at a 1-minute hold. Delaying entry by one minute flips the best 1-minute edge from +0.290 to -0.220 index points.",
        "Multi-day does not rescue it" to "From five days out, a coin that always says \"long\" clears the cost bar with zero skill, because what clears it is drift. Buy-and-hold returned 11.25%/yr on a sealed 2017-2026 holdout and beat every active rule tested.",
        "The hedge" to "A 0.75%-wide wing helped on 0 of 170 sessions and cost half the edge. Its entire value is insurance against the event that has not happened yet - a decision about ruin, not expectancy.",
        "Not deployed" to "Unmeasured before any capital moves: real broker margin, the true intraday bid-ask spread, and whether a broker force-closes a naked short near expiry.",
        "Invariants" to "No zero-cost path exists. The spread has a tick floor. Contract identity is (underlying, expiry, strike, right), never the broker's recycled token. Quantities are normalised to contracts at ingest. A raw IC is never printed without its partial.",
    )
    Page {
        item { PageTitle("Research Notes", "Prefer the option that makes a wrong number impossible over the one that makes it unlikely") }
        notes.forEach { (t, body) ->
            item {
                LedgerCard(title = t) { Text(body, style = Type.body.copy(color = p.ink)) }
            }
        }
    }
}
