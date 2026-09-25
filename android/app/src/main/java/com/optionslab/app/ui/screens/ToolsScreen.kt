package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.ButterflyBars
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.LinePlot
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.PlotLine
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.SignedBars
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.fmtG
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.Direction
import com.optionslab.engine.options.Payoff
import com.optionslab.engine.options.Side
import com.optionslab.engine.options.StrategyTemplates
import java.util.Locale

private fun f2(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
private fun f1(x: Double) = String.format(Locale.ENGLISH, "%,.1f", x)

/**
 * IraAlgo's option tools on the phone: the chain with Greeks, OI and PCR,
 * max pain, the IV smile, gamma exposure, the expected move, the synthetic
 * future, and the strategy builder with its payoff. Every number comes from
 * the ported analytics (engine/options) on one priced chain: Zerodha's in
 * LIVE mode, Upstox's public candles in SANDBOX.
 */
@Composable
fun ToolsScreen(model: AppModel, view: String, onView: (String) -> Unit, onChart: (String, String) -> Unit) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val snap by model.tools.collectAsState()
    val source by model.toolsSource.collectAsState()
    var underlying by rememberSaveable { mutableStateOf("NIFTY") }
    var picked by remember { mutableStateOf<ChainPick?>(null) }
    val views = listOf("chain" to "Chain", "oi" to "OI · Max pain", "iv" to "IV smile", "gex" to "GEX", "move" to "Expected move",
        "builder" to "Strategy builder", "expiryput" to "Expiry Put")
    // The Expiry Put strategy (formerly the Ticket tab) has its own scrolling page.
    if (view == "expiryput") {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
                ParamTokens("Tool", views.map { it.second to (it.first == view) }) { onView(views[it].first) }
            }
            Box(Modifier.weight(1f)) { TicketScreen(model) }
        }
        return
    }
    LaunchedEffect(underlying, s.live) { model.loadTools(underlying) }
    Page {
        item { PageTitle("Options", "Option chain, analytics, the strategy builder and the Expiry Put strategy") }
        item {
            ParamTokens("Tool", views.map { it.second to (it.first == view) }) { onView(views[it].first) }
            ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
        }
        when (val l = snap) {
            Load.Idle -> item { LedgerCard { FullSpinner("Pricing the chain") } }
            is Load.Busy -> item { LedgerCard { FullSpinner(l.label) } }
            is Load.Failed -> item {
                LedgerCard(accent = p.amber) {
                    Note(l.why)
                    BrassButton("Try again", Modifier.fillMaxWidth()) { model.loadTools(underlying) }
                }
            }
            is Load.Done -> {
                val c = l.value
                item { Header(c, source) { model.loadTools(underlying) } }
                when (view) {
                    "oi" -> item { Column { OiCard(c) } }
                    "iv" -> item { IvCard(c) }
                    "gex" -> item { GexCard(c) }
                    "move" -> item { MoveCard(c) }
                    "builder" -> item { Column { BuilderCard(model, c, s.live) } }
                    else -> item { ChainCard(c) { picked = it } }
                }
            }
        }
    }
    picked?.let { pk -> OptionChartPage(model, pk, onFullChart = { sym -> picked = null; onChart(sym, "NFO") }) { picked = null } }
}

@Composable
private fun Header(c: ChainSnapshot, source: String, onRefresh: () -> Unit) {
    val p = LocalPalette.current
    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${c.underlying} · ${c.expiry}", style = Type.title.copy(color = p.ink, fontSize = 15.sp))
                Text("spot ${f2(c.spot)} · fwd ${f2(c.forward)} · ${f1(c.dteDays)} days", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
                Text(source, style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
            }
            BrassButton("Refresh", tone = p.inkSoft, onClick = onRefresh)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
            Stat("ATM", c.atm?.let { fmtG(it) } ?: "—")
            Stat("ATM IV", c.atmIv?.let { "${f2(it)}%" } ?: "—")
            Stat("PCR", f2(c.pcr.pcrOi))
            Stat("Max pain", c.maxPain?.let { fmtG(it.maxPainStrike) } ?: "—")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val p = LocalPalette.current
    Column {
        Text(label.uppercase(), style = Type.label.copy(color = p.inkSoft, fontSize = 9.sp))
        Text(value, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
    }
}

@Composable
private fun ChainCard(c: ChainSnapshot, onPick: (ChainPick) -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "Option chain") {
        Row {
            listOf("CE Δ", "CE IV", "CE LTP", "STRIKE", "PE LTP", "PE IV", "PE Δ").forEachIndexed { i, h ->
                Text(h, style = Type.label.copy(color = p.inkSoft, fontSize = 9.sp), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(if (i == 3) 1.25f else 1f))
            }
        }
        Rule(Modifier.padding(vertical = 4.dp))
        c.rows.forEachIndexed { i, r ->
            val atm = r.strike == c.atm
            val itmCall = r.strike < c.spot
            val ce = c.ceGreeks.getOrNull(i); val pe = c.peGreeks.getOrNull(i)
            val cell = Type.figure.copy(fontSize = 11.sp)
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val ceTone = if (itmCall) p.ink else p.inkSoft
                val peTone = if (!itmCall) p.ink else p.inkSoft
                Text(ce?.let { f2(it.delta) } ?: "—", style = cell.copy(color = ceTone), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(ce?.let { f1(it.ivPct) } ?: "—", style = cell.copy(color = ceTone), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                val pickCe = { onPick(ChainPick(c.underlying, c.expiry, r.strike, com.optionslab.engine.Right.CE, r.ce?.ltp, ce?.delta, ce?.ivPct, c.lotSize)) }
                val pickPe = { onPick(ChainPick(c.underlying, c.expiry, r.strike, com.optionslab.engine.Right.PE, r.pe?.ltp, pe?.delta, pe?.ivPct, c.lotSize)) }
                Text(r.ce?.let { f2(it.ltp) } ?: "—", style = cell.copy(color = p.verdigris, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(p.verdigris.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(enabled = r.ce != null, onClick = pickCe).padding(vertical = 5.dp))
                Text(fmtG(r.strike), style = cell.copy(color = if (atm) p.gold else p.ink, fontSize = if (atm) 13.sp else 12.sp),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1.25f))
                Text(r.pe?.let { f2(it.ltp) } ?: "—", style = cell.copy(color = p.oxblood, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(p.oxblood.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(enabled = r.pe != null, onClick = pickPe).padding(vertical = 5.dp))
                Text(pe?.let { f1(it.ivPct) } ?: "—", style = cell.copy(color = peTone), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(pe?.let { f2(it.delta) } ?: "—", style = cell.copy(color = peTone), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        c.synthetic?.let {
            Spacer(Modifier.height(8.dp))
            LedgerLine("Synthetic future (K + C − P)", "${f2(it.price)} · basis ${f2(it.basis)}")
        }
        Note("Tap a call (CE) or put (PE) price to open its chart. Black-76 Greeks off the parity forward; Δ per 1 of underlying, IV in percent.")
    }
}

@Composable
private fun OiCard(c: ChainSnapshot) {
    val p = LocalPalette.current
    val xs = c.rows.map { it.strike }
    LedgerCard(title = "Open interest") {
        ButterflyBars(xs, c.rows.map { (it.ce?.oi ?: 0L).toDouble() }, c.rows.map { (it.pe?.oi ?: 0L).toDouble() }, p.verdigris, p.oxblood, markX = c.spot)
        Text("Calls above the line, puts below; the dashed rule is spot.", style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        LedgerLine("Call OI", "%,d".format(c.pcr.totalCeOi))
        LedgerLine("Put OI", "%,d".format(c.pcr.totalPeOi))
        LedgerLine("PCR (OI)", f2(c.pcr.pcrOi))
        LedgerLine("PCR (volume)", f2(c.pcr.pcrVolume))
        val ceWall = c.rows.maxByOrNull { it.ce?.oi ?: 0L }; val peWall = c.rows.maxByOrNull { it.pe?.oi ?: 0L }
        ceWall?.let { LedgerLine("Call wall (resistance)", fmtG(it.strike), p.verdigris) }
        peWall?.let { LedgerLine("Put wall (support)", fmtG(it.strike), p.oxblood) }
    }
    Spacer(Modifier.height(14.dp))
    c.maxPain?.let { mp ->
        LedgerCard(title = "Max pain") {
            LinePlot(mp.painData.map { it.strike }, listOf(PlotLine(mp.painData.map { it.totalPainCr }, p.ink)), markX = mp.maxPainStrike)
            LedgerLine("Max pain strike", fmtG(mp.maxPainStrike), p.gold)
            LedgerLine("Distance from spot", "${f1(mp.maxPainStrike - c.spot)} pts")
            Note("The settlement at which option writers, in total, would pay out least (Rs crore on the curve).")
        }
    }
}

@Composable
private fun IvCard(c: ChainSnapshot) {
    val p = LocalPalette.current
    val sm = c.ivSmile
    LedgerCard(title = "IV smile") {
        if (sm == null) Note("Not enough time to expiry to solve implied vols.") else {
            val xs = sm.chain.map { it.strike }
            LinePlot(xs, listOf(PlotLine(sm.chain.map { it.ceIv }, p.verdigris), PlotLine(sm.chain.map { it.peIv }, p.oxblood)), markX = c.spot)
            LedgerLine("ATM IV", sm.atmIv?.let { "${f2(it)}%" } ?: "—")
            LedgerLine("Skew (put − call wing)", sm.skew?.let { "${f2(it)} pts" } ?: "—")
            Note("Calls in verdigris, puts in oxblood. A put wing above the calls is the usual index skew: crash insurance costs more.")
        }
    }
}

@Composable
private fun GexCard(c: ChainSnapshot) {
    val p = LocalPalette.current
    val g = c.gex
    LedgerCard(title = "Gamma exposure") {
        if (g == null) Note("Not enough time to expiry to compute gamma.") else {
            SignedBars(g.chain.map { it.strike }, g.chain.map { it.netGex }, markX = c.spot)
            LedgerLine("Net GEX", "%,.0f".format(g.totalNetGex), if (g.totalNetGex >= 0) p.verdigris else p.oxblood)
            LedgerLine("Call GEX / put GEX", "%,.0f / %,.0f".format(g.totalCeGex, g.totalPeGex))
            val flip = g.chain.zipWithNext().firstOrNull { (a, b) -> a.netGex * b.netGex < 0 }
            flip?.let { LedgerLine("Sign flips between", "${fmtG(it.first.strike)} and ${fmtG(it.second.strike)}") }
            Note("GEX = gamma × OI × lot, calls minus puts. Positive: dealers long gamma, moves tend to be damped. Negative: moves tend to run.")
        }
    }
}

@Composable
private fun MoveCard(c: ChainSnapshot) {
    val p = LocalPalette.current
    val gd = c.gammaDensity
    LedgerCard(title = "Expected move") {
        if (gd == null) Note("Not enough time to expiry.") else {
            LedgerLine("ATM IV", "${f2(gd.atmIv)}%")
            Text("TODAY", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 6.dp))
            LedgerLine("1σ range", "${f1(gd.intradayBand.oneSigmaLow)} – ${f1(gd.intradayBand.oneSigmaHigh)}  (±${f1(gd.intradayBand.sigmaMove)})")
            LedgerLine("2σ range", "${f1(gd.intradayBand.twoSigmaLow)} – ${f1(gd.intradayBand.twoSigmaHigh)}")
            Text("TO EXPIRY", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.padding(top = 6.dp))
            LedgerLine("1σ range", "${f1(gd.expiryBand.oneSigmaLow)} – ${f1(gd.expiryBand.oneSigmaHigh)}  (±${f1(gd.expiryBand.sigmaMove)})")
            LedgerLine("2σ range", "${f1(gd.expiryBand.twoSigmaLow)} – ${f1(gd.expiryBand.twoSigmaHigh)}")
            gd.peakExpiryStrike?.let { LedgerLine("Gamma peak to expiry", fmtG(it), p.gold) }
            LinePlot(gd.chain.map { it.strike }, listOf(PlotLine(gd.chain.map { it.densityExpiry }, p.ink), PlotLine(gd.chain.map { it.densityIntraday }, p.brass, dashed = true)),
                markX = c.spot, height = 140.dp)
            Note("Lognormal bands from the ATM implied vol. Solid: gamma density to expiry; dashed: today.")
        }
    }
}

@Composable
private fun BuilderCard(model: AppModel, c: ChainSnapshot, live: Boolean) {
    val p = LocalPalette.current
    var dir by remember { mutableStateOf<Direction?>(null) }
    var pick by remember(c.underlying) { mutableStateOf(StrategyTemplates.ALL.first().id) }
    var mult by remember { mutableStateOf(1) }
    val atm = c.atm
    LedgerCard(title = "Strategy builder") {
        val dirs = listOf(null to "All", Direction.BULLISH to "Bullish", Direction.BEARISH to "Bearish", Direction.NON_DIRECTIONAL to "Neutral")
        ParamTokens("Outlook", dirs.map { it.second to (it.first == dir) }) { dir = dirs[it].first }
        val list = StrategyTemplates.byDirection(dir).filter { t -> t.legs.all { it.expiryOffset == 0 } }
        Column(Modifier.padding(top = 6.dp)) {
            list.forEach { t ->
                Text((if (t.id == pick) "◆ " else "◇ ") + t.name,
                    style = Type.body.copy(color = if (t.id == pick) p.gold else p.ink, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth().clickable { pick = t.id }.padding(vertical = 3.dp))
            }
        }
        val mults = listOf(1, 2, 3, 5)
        ParamTokens("Lots per leg ×", mults.map { "$it" to (it == mult) }) { mult = mults[it] }
    }
    Spacer(Modifier.height(14.dp))
    val t = StrategyTemplates.byId(pick) ?: return
    if (atm == null) { LedgerCard { Note("No ATM strike on this chain.") }; return }
    val r = StrategyTemplates.resolve(t, c.rows, atm, c.expiryCode, listOf(c.expiryCode), mult)
    LedgerCard(title = t.name) {
        Note(t.description)
        com.optionslab.app.ui.components.AlertOn(r.errors.takeIf { !r.ok && it.isNotEmpty() }?.joinToString(" "))

        r.legs.forEach { l ->
            LedgerLine("${l.leg.side} ${l.lots}× ${fmtG(l.strike)} ${l.leg.optionType}", f2(l.price), if (l.leg.side == Side.SELL) p.oxblood else p.verdigris)
        }
        if (r.ok) {
            val ivOf = { l: com.optionslab.engine.options.ResolvedTemplateLeg ->
                val i = c.rows.indexOfFirst { it.strike == l.strike }
                (if (l.leg.optionType.isCall) c.ceGreeks.getOrNull(i) else c.peGreeks.getOrNull(i))?.ivPct ?: (c.atmIv ?: 0.0)
            }
            val legs = StrategyTemplates.toStrategyLegs(r, c.underlying, c.lotSize, ivOf)
            val now = com.optionslab.app.data.Market.now().toInstant()
            val iv = c.atmIv ?: 12.0
            val result = remember(legs, c) {
                runCatching {
                    val range = Payoff.payoffPriceRange(c.spot, legs, iv, c.tYears)
                    Payoff.computePayoff(legs, c.spot, Payoff.nearestLegDays(legs, now), 0.0, range, fallbackIv = iv, now = now)
                }.getOrNull()
            }
            if (result == null) Note("The payoff could not be computed for these legs.") else {
                LinePlot(result.samples.map { it.underlying },
                    listOf(PlotLine(result.samples.map { it.expiry }, p.ink), PlotLine(result.samples.map { it.tplus0 }, p.brass, dashed = true)),
                    markX = c.spot, fillSign = true, height = 200.dp)
                Text("Solid: at expiry. Dashed: today. Brass rule: spot.", style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
                fun money(x: Double) = if (x.isInfinite()) (if (x > 0) "unlimited" else "unlimited loss") else rs(x, true)
                LedgerLine("Max profit", money(result.maxProfit), p.verdigris)
                LedgerLine("Max loss", money(result.maxLoss), p.oxblood)
                LedgerLine("Breakevens", result.breakevens.joinToString(" · ") { f1(it) }.ifEmpty { "none" })
                val net = Payoff.netCredit(legs)
                LedgerLine(if (net >= 0) "Net credit" else "Net debit", rs(kotlin.math.abs(net)))
                Payoff.probabilityOfProfit(result.samples, c.spot, iv, c.tYears)?.let { LedgerLine("Probability of profit", "%.1f%%".format(100 * it)) }
                Spacer(Modifier.height(8.dp))
                if (live) BrassButton("Review as Zerodha orders", Modifier.fillMaxWidth(), tone = p.oxblood) {
                    model.planBasket("${t.name} · ${c.underlying} ${c.expiry}", c.underlying, c.expiry, legs)
                } else BrassButton("Paper-trade these legs", Modifier.fillMaxWidth(), tone = p.verdigris) {
                    model.paperBasket(c.underlying, c.expiry, legs)
                }
                if (live) Note("Opens the usual review on the Trade tab: wings are bought before any leg is sold, each at the best bid/offer, then hold-to-send and your PIN.")
            }
        }
    }
}
