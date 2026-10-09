package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.first

private fun f2(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
private fun f1(x: Double) = String.format(Locale.ENGLISH, "%,.1f", x)

/** The MCX chains the Options tab offers (research/MCX_GUIDE.md 1b: the liquid near-month books). */
internal val MCX_CHAINS = com.optionslab.engine.mcx.Mcx.CHAIN_FIRST

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
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val snap by model.tools.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val source by model.toolsSource.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var underlying by rememberSaveable { mutableStateOf("NIFTY") }
    var picked by remember { mutableStateOf<ChainPick?>(null) }
    val views = listOf("chain" to "Chain", "oi" to "OI · Max pain", "straddle" to "Straddle", "iv" to "IV smile", "gex" to "GEX",
        "move" to "Expected move", "builder" to "Strategy builder", "expiryput" to "Expiry Put", "commodities" to "Commodities")
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
    // MCX (9 Oct): every commodity's near and next future; its option chain opens here with that underlying.
    if (view == "commodities") {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
                ParamTokens("Tool", views.map { it.second to (it.first == view) }) { onView(views[it].first) }
            }
            Box(Modifier.weight(1f)) { CommoditiesScreen(model, onChart, onChain = { name -> underlying = name; onView("chain") }) }
        }
        return
    }
    LaunchedEffect(underlying, s.live) { model.loadTools(underlying) }
    // Live mode with the Zerodha stream up: the chain re-prices every 5 s from the ticks (no quote calls).
    val streamStatus by com.optionslab.app.data.KiteStream.status.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val streaming = s.live && streamStatus == com.optionslab.app.data.KiteStream.Status.LIVE
    // Battery (round 6): only while the app is in front - a plain LaunchedEffect kept pricing the chain (two Zerodha
    // calls every 5 s) with the screen off and the Options tab last open; it resumes on return.
    // Round 2: re-priced on the stream's next price (at most once a second: the chain's analytics are re-run each time),
    // 5 s at the latest as before. The prices come from the ticks in memory; no quote call is added.
    com.optionslab.app.ui.PollWhileStarted(underlying, streaming) {
        val version = com.optionslab.app.data.KiteStream.version
        while (streaming) {
            val seen = version.value
            kotlinx.coroutines.withTimeoutOrNull(5_000) { version.first { it != seen } }
            model.loadTools(underlying, quiet = true)
            kotlinx.coroutines.delay(1_000)
        }
    }
    Page {
        item { PageTitle("Options", "Option chain, analytics, the strategy builder and the Expiry Put strategy") }
        item {
            ParamTokens("Tool", views.map { it.second to (it.first == view) }) { onView(views[it].first) }
            ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
            // MCX (9 Oct): the liquid near-month option books; the chain's underlying is the future its options turn into.
            ParamTokens("MCX", MCX_CHAINS.map { it to (it == underlying) }) { underlying = MCX_CHAINS[it] }
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
                    "straddle" -> item { StraddleCard(model, c, streaming) }
                    "iv" -> item { IvCard(c) }
                    "gex" -> item { GexCard(c) }
                    "move" -> item { MoveCard(c) }
                    "builder" -> item { Column { BuilderCard(model, c, s.live) } }
                    else -> item { ChainCard(c) { picked = it } }
                }
            }
        }
    }
    picked?.let { pk -> OptionChartPage(model, pk, onFullChart = { sym -> picked = null; onChart(sym, if (com.optionslab.engine.mcx.Mcx.isMcxName(pk.underlying)) "MCX" else "NFO") }) { picked = null } }
}

@Composable
internal fun Header(c: ChainSnapshot, source: String, onRefresh: () -> Unit) {
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
fun ChainCard(c: ChainSnapshot, onPick: (ChainPick) -> Unit) {
    val p = LocalPalette.current
    // Tablets and a sideways phone have room for the open interest on each side.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    fun oi(v: Long?) = v?.let { if (it >= 100_000) f1(it / 100_000.0) + "L" else "%,d".format(it) } ?: "—"
    LedgerCard(title = "Option chain") {
        Row {
            val heads = listOf("CE Δ", "CE IV", "CE LTP", "STRIKE", "PE LTP", "PE IV", "PE Δ")
            (if (wide) listOf("CE OI") + heads + "PE OI" else heads).forEach { h ->
                // One line each, shrunk to fit: a header never breaks inside a word ("STRI / KE" at large fonts).
                com.optionslab.app.ui.components.FitText(h, style = Type.label.copy(color = p.inkSoft, fontSize = 9.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.weight(if (h == "STRIKE") 1.25f else 1f), minSize = 5.sp)
            }
        }
        Rule(Modifier.padding(vertical = 4.dp))
        val fit = androidx.compose.runtime.remember(c) { androidx.compose.runtime.mutableFloatStateOf(1f) }
        // The underlying's price sits between the strike just below it and the one just above: a line there.
        val spotAt = spotLineIndex(c.rows.map { it.strike }, c.spot)
        c.rows.forEachIndexed { i, r ->
            if (i == spotAt) SpotLine(c.underlying, c.spot)
            val atm = r.strike == c.atm
            val itmCall = r.strike < c.spot
            // In the money: calls below spot, puts above it, shaded on their own side of the table.
            val ceItm = Modifier.background(if (itmCall) p.amber.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
            val peItm = Modifier.background(if (!itmCall && r.strike != c.spot) p.amber.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
            val ce = c.ceGreeks.getOrNull(i); val pe = c.peGreeks.getOrNull(i)
            val cell = Type.figure.copy(fontSize = 11.sp)
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val ceTone = if (itmCall) p.ink else p.inkSoft
                val peTone = if (!itmCall) p.ink else p.inkSoft
                if (wide) NumCell(oi(r.ce?.oi), cell.copy(color = ceTone), Modifier.weight(1f).then(ceItm), fit)
                NumCell(ce?.let { f2(it.delta) } ?: "—", cell.copy(color = ceTone), Modifier.weight(1f).then(ceItm), fit)
                NumCell(ce?.let { f1(it.ivPct) } ?: "—", cell.copy(color = ceTone), Modifier.weight(1f).then(ceItm), fit)
                val pickCe = { onPick(ChainPick(c.underlying, c.expiry, r.strike, com.optionslab.engine.Right.CE, r.ce?.ltp, ce?.delta, ce?.ivPct, c.lotSize)) }
                val pickPe = { onPick(ChainPick(c.underlying, c.expiry, r.strike, com.optionslab.engine.Right.PE, r.pe?.ltp, pe?.delta, pe?.ivPct, c.lotSize)) }
                NumCell(r.ce?.let { f2(it.ltp) } ?: "—", cell.copy(color = p.verdigris, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), Modifier.weight(1f).background(p.verdigris.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(enabled = r.ce != null, onClick = pickCe).padding(vertical = 5.dp), fit)
                NumCell(fmtG(r.strike), cell.copy(color = if (atm) p.gold else p.ink, fontSize = if (atm) 13.sp else 12.sp,
                    fontWeight = if (atm) androidx.compose.ui.text.font.FontWeight.Bold else null),
                    Modifier.weight(1.25f).then(if (atm) Modifier.border(1.dp, p.gold, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(vertical = 4.dp) else Modifier), fit)
                NumCell(r.pe?.let { f2(it.ltp) } ?: "—", cell.copy(color = p.oxblood, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), Modifier.weight(1f).background(p.oxblood.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(enabled = r.pe != null, onClick = pickPe).padding(vertical = 5.dp), fit)
                NumCell(pe?.let { f1(it.ivPct) } ?: "—", cell.copy(color = peTone), Modifier.weight(1f).then(peItm), fit)
                NumCell(pe?.let { f2(it.delta) } ?: "—", cell.copy(color = peTone), Modifier.weight(1f).then(peItm), fit)
                if (wide) NumCell(oi(r.pe?.oi), cell.copy(color = peTone), Modifier.weight(1f).then(peItm), fit)
            }
        }
        if (spotAt == c.rows.size) SpotLine(c.underlying, c.spot)
        c.synthetic?.let {
            Spacer(Modifier.height(8.dp))
            LedgerLine("Synthetic future (K + C − P)", "${f2(it.price)} · basis ${f2(it.basis)}")
        }
        Note("The line with the price is ${c.underlying} now, between the strikes below and above it; the boxed strike is ATM. " +
            "Shaded cells are in the money (calls below the price, puts above it). " +
            "Tap a call (CE) or put (PE) price to open its chart. Black-76 Greeks off the parity forward; Δ per 1 of underlying, IV in percent.")
    }
}

/** Where the spot line goes: before the first strike at or above [spot] (strikes ascending); [strikes].size when all are below. */
internal fun spotLineIndex(strikes: List<Double>, spot: Double): Int =
    strikes.indexOfFirst { it >= spot }.let { if (it < 0) strikes.size else it }

/** The underlying's current price as a gold rule across the chain, with the price in a pill at its centre. */
@Composable
private fun SpotLine(underlying: String, spot: Double) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(2.dp).background(p.gold))
        Text("$underlying ${f2(spot)}", maxLines = 1, softWrap = false,
            style = Type.figure.copy(color = p.onPrimary, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            modifier = Modifier.background(p.gold, androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp))
        Box(Modifier.weight(1f).height(2.dp).background(p.gold))
    }
}

/**
 * A chain cell: one centred line (at a large font on a small phone "192.05" wrapped into "192.0 / 5"). All cells of a
 * table share [fit]: when any cell's number does not fit its column, every cell shrinks by the same step, so the
 * table keeps one type size.
 */
@Composable
private fun NumCell(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, fit: androidx.compose.runtime.MutableFloatState) =
    Text(text, modifier.padding(horizontal = 1.dp), style = style.copy(textAlign = TextAlign.Center, fontSize = style.fontSize * fit.floatValue),
        maxLines = 1, softWrap = false,
        onTextLayout = { r -> if (r.didOverflowWidth && fit.floatValue > 0.5f) fit.floatValue = (fit.floatValue * 0.92f).coerceAtLeast(0.5f) })

@Composable
internal fun OiCard(c: ChainSnapshot) {
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
    OiChangeCard(c)
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
internal fun IvCard(c: ChainSnapshot) {
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
internal fun GexCard(c: ChainSnapshot) {
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
internal fun MoveCard(c: ChainSnapshot) {
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


/** OI added or shed today, per strike: from the day's first reading of each contract. */
@Composable
internal fun OiChangeCard(c: ChainSnapshot) {
    val p = LocalPalette.current
    val ch = com.optionslab.engine.options.ChainAnalytics.oiChange(c.rows)
    LedgerCard(title = "OI change today") {
        if (ch.none { it.ceOiChange != 0.0 || it.peOiChange != 0.0 }) {
            Note("No change yet: the baseline is the first reading of the day, so this fills in as the session goes on.")
            return@LedgerCard
        }
        LinePlot(ch.map { it.strike }, listOf(PlotLine(ch.map { it.ceOiChange }, p.verdigris), PlotLine(ch.map { it.peOiChange }, p.oxblood)), markX = c.spot)
        Text("Calls green, puts red; above zero is OI added. The rule is spot.", style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        LedgerLine("Call OI added", "%,+.0f".format(ch.sumOf { it.ceOiChange }), p.verdigris)
        LedgerLine("Put OI added", "%,+.0f".format(ch.sumOf { it.peOiChange }), p.oxblood)
        ch.maxByOrNull { it.ceOiChange }?.takeIf { it.ceOiChange > 0 }?.let { LedgerLine("Most calls written", fmtG(it.strike), p.verdigris) }
        ch.maxByOrNull { it.peOiChange }?.takeIf { it.peOiChange > 0 }?.let { LedgerLine("Most puts written", fmtG(it.strike), p.oxblood) }
    }
}

/**
 * The straddle / strangle tracker: a call and a put of the chain's expiry as one
 * combined premium through the day, and the pair's P&L when both legs are held.
 */
@Composable
private fun StraddleCard(model: AppModel, c: ChainSnapshot, streaming: Boolean) {
    val p = LocalPalette.current
    val strikes = c.rows.map { it.strike }
    val atmIndex = strikes.indexOf(c.atm).takeIf { it >= 0 } ?: (strikes.size / 2)
    val near = (-3..3).mapNotNull { strikes.getOrNull(atmIndex + it) }
    var ceK by remember(c.underlying, c.expiry) { mutableStateOf(c.atm ?: strikes.getOrNull(atmIndex) ?: 0.0) }
    var peK by remember(c.underlying, c.expiry) { mutableStateOf(c.atm ?: strikes.getOrNull(atmIndex) ?: 0.0) }
    var view by remember { mutableStateOf<AppModel.StraddleView?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(c.underlying, c.expiry, ceK, peK) { view = null }
    // Battery (round 2): polled only while the app is in front - the pair was read every 3 s from a phone in a pocket.
    com.optionslab.app.ui.PollWhileStarted(c.underlying, c.expiry, ceK, peK, streaming) {
        while (true) {
            runCatching { model.straddle(c.underlying, c.expiry, ceK, peK) }
                .onSuccess { view = it; err = null }.onFailure { err = it.message ?: "could not load the pair" }
            kotlinx.coroutines.delay(if (streaming) 3_000 else 30_000)
        }
    }
    com.optionslab.app.ui.components.AlertOn(err)
    LedgerCard(title = if (ceK == peK) "Straddle ${fmtG(ceK)}" else "Strangle ${fmtG(peK)} PE · ${fmtG(ceK)} CE") {
        ParamTokens("Call strike", near.map { fmtG(it) to (it == ceK) }) { ceK = near[it] }
        ParamTokens("Put strike", near.map { fmtG(it) to (it == peK) }) { peK = near[it] }
        val v = view
        if (v == null) { FullSpinner("Loading the pair"); return@LedgerCard }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
            Stat("Combined", f2(v.now))
            Stat("CE", f2(v.ceNow))
            Stat("PE", f2(v.peNow))
            Stat("From open", (if (v.now - v.open >= 0) "+" else "") + f2(v.now - v.open))
        }
        if (v.minutes.size >= 2) {
            LinePlot(v.minutes.map { it.toDouble() }, listOf(PlotLine(v.combined, p.ink)), Modifier.padding(top = 8.dp))
            Text("Combined premium through the day" + if (v.streamed) " · live from Zerodha" else " · 1-minute closes", style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
        }
        LedgerLine("Day's high · low", "${f2(v.high)} · ${f2(v.low)}")
        LedgerLine("Opened at", f2(v.open))
        v.heldPnl?.let { LedgerLine("Your pair's P&L (${v.heldWhere})", "%+,.0f".format(it), if (it >= 0) p.verdigris else p.oxblood) }
        Note("A seller wants the combined premium to fall; a buyer wants it to rise. Both legs of the same expiry (${c.expiry}).")
    }
}
