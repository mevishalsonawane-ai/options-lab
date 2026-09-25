package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.optionslab.app.data.History
import com.optionslab.app.data.Market
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.LinePlot
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.PlotLine
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.portfolio.CurvePoint
import com.optionslab.engine.portfolio.Finding
import com.optionslab.engine.portfolio.Health
import com.optionslab.engine.portfolio.Holding
import com.optionslab.engine.portfolio.Metrics
import com.optionslab.engine.portfolio.PortfolioResult
import java.time.LocalDate
import java.util.Locale

private fun pc(x: Double?) = x?.let { String.format(Locale.ENGLISH, "%+.2f%%", 100 * it) } ?: "—"
private fun n2(x: Double?) = x?.let { String.format(Locale.ENGLISH, "%.2f", it) } ?: "—"

/** Two curves on the same dates (portfolio vs benchmark), benchmark reindexed by date. */
@Composable
private fun Curves(main: List<CurvePoint>, other: List<CurvePoint>) {
    val p = LocalPalette.current
    if (main.size < 2) return
    val at = other.associate { it.date to it.value }
    val xs = main.indices.map { it.toDouble() }
    LinePlot(xs, listOf(PlotLine(main.map { it.value }, p.ink), PlotLine(main.map { at[it.date] }, p.brass, dashed = true)), height = 190.dp)
    Text("${main.first().date} → ${main.last().date}${if (other.isNotEmpty()) " · dashed: benchmark" else ""}",
        style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
}

@Composable
private fun MetricsBlock(m: Metrics) {
    LedgerLine("CAGR", pc(m.cagr))
    LedgerLine("Volatility", pc(m.volatility))
    LedgerLine("Sharpe · Sortino · Calmar", "${n2(m.sharpe)} · ${n2(m.sortino)} · ${n2(m.calmar)}")
    LedgerLine("Max drawdown", pc(m.maxDrawdown))
    LedgerLine("Win rate (days)", pc(m.winRate))
    LedgerLine("Best / worst day", "${pc(m.bestDay)} / ${pc(m.worstDay)}")
    LedgerLine("VaR / CVaR (95%, daily)", "${pc(m.valueAtRisk)} / ${pc(m.cvar)}")
    if (m.benchmarkCagr != null) {
        LedgerLine("Benchmark CAGR", pc(m.benchmarkCagr))
        LedgerLine("Excess CAGR", pc(m.excessCagr))
        LedgerLine("Alpha · Beta", "${pc(m.alpha)} · ${n2(m.beta)}")
        LedgerLine("Up / down capture", "${n2(m.upCapture)} / ${n2(m.downCapture)}")
    }
}

@Composable
private fun HealthBlock(h: Health) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(h.grade ?: "—", style = Type.figureHuge.copy(color = when (h.grade) { "A", "B" -> p.verdigris; "C" -> p.amber; else -> p.oxblood }))
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text("score ${n2(h.score)} / 100", style = Type.figure.copy(color = p.inkSoft))
    }
    h.pillars.forEach { pl -> LedgerLine(pl.label, pl.score?.let { "%.0f".format(it) } ?: "not measured") }
}

@Composable
private fun Findings(title: String, list: List<Finding>) {
    val p = LocalPalette.current
    if (list.isEmpty()) return
    Text(title.uppercase(), style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
    list.forEach { f ->
        Text("• ${f.title}", style = Type.body.copy(color = p.ink, fontSize = 15.sp))
        Text(f.detail, style = Type.bodySmall.copy(color = p.inkSoft))
    }
}

@Composable
private fun PortfolioReport(r: PortfolioResult, sources: Set<String>) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LedgerCard(title = "Equity curve") {
            Curves(r.equity, r.benchmarkEquity)
            Note("Prices: ${sources.joinToString(" + ").ifEmpty { "—" }}")
        }
        LedgerCard(title = "Metrics") { MetricsBlock(r.metrics) }
        LedgerCard(title = "Holdings") {
            r.items.forEachIndexed { i, it ->
                if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
                Text(it.symbol, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
                LedgerLine("Weight target → final", "${pc(it.weightTarget).removePrefix("+")} → ${pc(it.weightFinal).removePrefix("+")}")
                LedgerLine("Net P&L · contribution", "${rs(it.netPnl, true)} · ${pc(it.contributionPct)}", if (it.netPnl >= 0) p.verdigris else p.oxblood)
            }
        }
        LedgerCard(title = "Health") { HealthBlock(r.health) }
        r.monteCarlo.cagr?.let { b ->
            LedgerCard(title = "Monte Carlo (${r.monteCarlo.paths} paths)") {
                LedgerLine("CAGR 5% · median · 95%", "${pc(b.p05)} · ${pc(b.median)} · ${pc(b.p95)}")
                r.monteCarlo.maxDrawdown?.let { LedgerLine("Drawdown median · 5% worst", "${pc(it.median)} · ${pc(it.p05)}") }
                LedgerLine("Chance of a loss", pc(r.monteCarlo.probabilityOfLoss).removePrefix("+"))
            }
        }
        LedgerCard(title = "Insights") {
            r.insights.headline?.let { Text(it.title, style = Type.title.copy(color = p.ink, fontSize = 15.sp)); Text(it.detail, style = Type.bodySmall) }
            Findings("Strengths", r.insights.strengths)
            Findings("Weaknesses", r.insights.weaknesses)
            Findings("Opportunities", r.insights.opportunities)
            Findings("Threats", r.insights.threats)
        }
        LedgerCard(title = "Costs") {
            LedgerLine("Model", r.costs.model)
            LedgerLine("Cost drag", pc(r.costs.drag))
            LedgerLine("Rebalances", "${r.rebalancing.count} (${r.rebalancing.rule})")
        }
    }
}

/** IraAlgo's Portfolio Backtester and Analyzer. */
@Composable
fun PortfolioLab(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val res by model.portfolio.collectAsState()
    val an by model.analyzer.collectAsState()
    val rows = remember { mutableStateListOf("NIFTYBEES" to "50", "GOLDBEES" to "30", "ITC" to "20") }
    var years by remember { mutableStateOf(5) }
    var bench by remember { mutableStateOf<String?>("NIFTY") }
    var rebalance by remember { mutableStateOf("never") }
    var capital by remember { mutableStateOf(100_000.0) }
    Page {
        item { PageTitle("Portfolio", "Backtest a basket of NSE holdings, or analyse the ones you own") }
        if (s.live) item {
            LedgerCard(title = "Your holdings") {
                Note("Your Zerodha delivery holdings at today's weights, run through the backtester over the last year against NIFTY.")
                BrassButton("Analyse my holdings", Modifier.fillMaxWidth()) { model.analyzeHoldings() }
                when (val a = an) {
                    is Load.Busy -> FullSpinner(a.label)
                    is Load.Failed -> com.optionslab.app.ui.components.AlertOn(a.why)
                    is Load.Done -> {
                        val sm = a.value.result.summary
                        LedgerLine("Holdings", "${sm.count}")
                        LedgerLine("Current value", rs(sm.current))
                        LedgerLine("P&L", "${rs(sm.pnl, true)} (${pc(sm.pnlPct)})", if (sm.pnl >= 0) p.verdigris else p.oxblood)
                        a.value.result.analysisError?.let { Note(it) }
                        if (a.value.result.skipped.isNotEmpty()) Note("Not analysed (not NSE/BSE): ${a.value.result.skipped.joinToString()}")
                    }
                    Load.Idle -> Unit
                }
            }
        }
        (an as? Load.Done<AppModel.AnalyzerView>)?.value?.let { v -> v.result.analysis?.let { r -> item { PortfolioReport(r, v.sources) } } }
        item {
            LedgerCard(title = "Backtest a basket") {
                rows.forEachIndexed { i, (sym, w) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sym, { v -> rows[i] = v.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == '&' }.take(20) to w },
                            label = { Text("NSE symbol") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(w, { v -> rows[i] = sym to v.filter { it.isDigit() || it == '.' }.take(6) },
                            label = { Text("Weight %") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        TextButton({ rows.removeAt(i) }) { Text("✕", style = Type.label.copy(color = p.oxblood)) }
                    }
                }
                if (rows.size < 20) TextButton({ rows.add("" to "") }) { Text("+ Add holding", style = Type.label.copy(color = p.brass)) }
                val yrs = listOf(1, 3, 5, 10)
                ParamTokens("Period", yrs.map { "$it y" to (it == years) }) { years = yrs[it] }
                val rules = listOf("never", "monthly", "quarterly", "yearly")
                ParamTokens("Rebalance", rules.map { it to (it == rebalance) }) { rebalance = rules[it] }
                val bs = listOf<String?>(null) + History.BENCHMARKS
                ParamTokens("Benchmark", bs.map { (it ?: "none") to (it == bench) }) { bench = bs[it] }
                val caps = listOf(100_000.0, 1_000_000.0, 10_000_000.0)
                ParamTokens("Capital", caps.map { rs(it) to (it == capital) }) { capital = caps[it] }
                Spacer(Modifier.height(8.dp))
                BrassButton("Run the backtest", Modifier.fillMaxWidth(), busy = res is Load.Busy) {
                    val hs = rows.filter { it.first.isNotBlank() }.map { Holding(it.first, "NSE", it.second.toDoubleOrNull() ?: 0.0) }
                    val end = Market.today()
                    model.runPortfolio(hs, end.minusYears(years.toLong()), end, bench, rebalance, capital)
                }
                Note("Indian equity delivery costs (STT, exchange, SEBI, stamp, GST) are charged on every trade, as IraAlgo does.")
            }
        }
        when (val r = res) {
            is Load.Busy -> item { LedgerCard { FullSpinner(r.label) } }
            is Load.Failed -> item { LedgerCard(accent = p.amber) { Note(r.why) } }
            is Load.Done -> item { PortfolioReport(r.value.result, r.value.sources) }
            Load.Idle -> Unit
        }
    }
}

/** IraAlgo's SIP Backtester. */
@Composable
fun SipLab(model: AppModel) {
    val p = LocalPalette.current
    val res by model.sip.collectAsState()
    var symbol by remember { mutableStateOf("NIFTYBEES") }
    var amount by remember { mutableStateOf("10000") }
    var years by remember { mutableStateOf(5) }
    var freq by remember { mutableStateOf("monthly") }
    var day by remember { mutableStateOf(5) }
    var stepUp by remember { mutableStateOf(0.0) }
    var bench by remember { mutableStateOf<String?>("NIFTY") }
    Page {
        item { PageTitle("SIP", "What a systematic investment would have done") }
        item {
            LedgerCard(title = "The plan") {
                OutlinedTextField(symbol, { symbol = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '&' }.take(20) },
                    label = { Text("NSE symbol") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(8) }, label = { Text("Installment (Rs)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                val fs = listOf("weekly", "fortnightly", "monthly", "quarterly")
                ParamTokens("Frequency", fs.map { it to (it == freq) }) { freq = fs[it] }
                if (freq == "monthly" || freq == "quarterly") {
                    val days = listOf(1, 5, 10, 15, 20, 25)
                    ParamTokens("Day of month", days.map { "$it" to (it == day) }) { day = days[it] }
                }
                val ups = listOf(0.0, 5.0, 10.0, 15.0)
                ParamTokens("Yearly step-up", ups.map { "${it.toInt()}%" to (it == stepUp) }) { stepUp = ups[it] }
                val yrs = listOf(3, 5, 10, 15)
                ParamTokens("Period", yrs.map { "$it y" to (it == years) }) { years = yrs[it] }
                val bs = listOf<String?>(null) + History.BENCHMARKS
                ParamTokens("Compare with", bs.map { (it ?: "none") to (it == bench) }) { bench = bs[it] }
                Spacer(Modifier.height(8.dp))
                BrassButton("Run the SIP", Modifier.fillMaxWidth(), busy = res is Load.Busy, enabled = symbol.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0) {
                    val end = Market.today()
                    model.runSip(symbol, "NSE", end.minusYears(years.toLong()), end, amount.toDouble(), freq, day, stepUp, bench)
                }
            }
        }
        when (val r = res) {
            is Load.Busy -> item { LedgerCard { FullSpinner(r.label) } }
            is Load.Failed -> item { LedgerCard(accent = p.amber) { Note(r.why) } }
            is Load.Done -> {
                val v = r.value.result
                val h = v.headline
                item {
                    LedgerCard(title = "Outcome") {
                        LedgerLine("Invested", h.invested?.let { rs(it) } ?: "—")
                        LedgerLine("Value now", h.finalValue?.let { rs(it) } ?: "—", p.verdigris)
                        LedgerLine("Gain", h.gain?.let { rs(it, true) } ?: "—", if ((h.gain ?: 0.0) >= 0) p.verdigris else p.oxblood)
                        LedgerLine("XIRR", pc(h.xirr))
                        LedgerLine("Multiple", h.multiple?.let { "%.2f×".format(it) } ?: "—")
                        LedgerLine("Installments", "${h.installments}")
                        LedgerLine("Average cost vs average price", "${n2(h.averageCost)} vs ${n2(h.averagePrice)} (${pc(h.costAdvantage)})")
                        LedgerLine("Charges", h.charges?.let { rs(it) } ?: "—")
                    }
                }
                item {
                    LedgerCard(title = "Value vs invested") {
                        val inv = v.curves.invested.associate { it.date to it.value }
                        val xs = v.curves.value.indices.map { it.toDouble() }
                        LinePlot(xs, listOf(PlotLine(v.curves.value.map { it.value }, p.verdigris), PlotLine(v.curves.value.map { inv[it.date] }, p.inkSoft, dashed = true)), height = 190.dp)
                        Note("Prices: ${r.value.sources.joinToString(" + ")}. Dashed: money put in.")
                    }
                }
                v.lumpsum?.let { l ->
                    item {
                        LedgerCard(title = "Against a lump sum") {
                            LedgerLine("Lump sum on ${l.entryDate}", l.finalValue?.let { rs(it) } ?: "—")
                            LedgerLine("SIP", l.sipFinalValue?.let { rs(it) } ?: "—")
                            LedgerLine("Winner", if (l.sipWon) "SIP" else "Lump sum", if (l.sipWon) p.verdigris else p.amber)
                        }
                    }
                }
                v.benchmark?.let { b ->
                    item {
                        LedgerCard(title = "Same plan in ${b.symbol}") {
                            if (b.error != null) Note(b.error!!) else {
                                LedgerLine("XIRR", pc(b.xirr))
                                LedgerLine("Excess XIRR", pc(b.excessXirr), if ((b.excessXirr ?: 0.0) >= 0) p.verdigris else p.oxblood)
                            }
                        }
                    }
                }
                item {
                    LedgerCard(title = "Year by year") {
                        v.yearly.forEach { y -> LedgerLine("${y.year}", "${y.value?.let { rs(it) } ?: "—"} · ${pc(y.change)}") }
                        LedgerLine("Deepest drawdown", pc(v.drawdown.maxDrawdown))
                        LedgerLine("Time below invested", pc(v.underwater.shareBelow).removePrefix("+"))
                        v.warnings.forEach { Note(it) }
                    }
                }
            }
            Load.Idle -> Unit
        }
    }
}
