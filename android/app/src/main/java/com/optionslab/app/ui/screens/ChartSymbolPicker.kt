package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.fmtG
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxInstruments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * One entry of the chart's symbol picker: what is charted ([symbol], [exchange]) and how it is shown. [why]: why it
 * cannot be charted, shown greyed and never tappable (so a pick never ends on a blank chart); null: it can.
 */
internal data class SymbolChoice(val symbol: String, val exchange: String, val title: String, val sub: String = "", val why: String? = null)

/** An option underlying in the picker: an NSE index ([mcx] false) or an MCX commodity. [why]: why it has no options here. */
internal data class OptionRoot(val name: String, val mcx: Boolean, val why: String? = null)

/** One strike of an expiry: its call's and put's trading symbols (null: not listed). */
internal data class StrikeRow(val strike: Double, val ce: String?, val pe: String?)

/**
 * What the chart's symbol picker lists (11 Oct 2026, Boss: "only the BANKNIFTY chart and I am not able to change it"):
 * the indices the candle feed serves, every MCX commodity's near-month future, option underlyings with their expiries
 * and strikes, a search through the app's own instrument lists, and the last symbols charted. Pure: the lists are passed
 * in (read off the main thread by the sheet).
 */
internal object ChartSymbols {
    /** The indices, in the order shown, with their names. Each one has a candle key in [ChartFeed]. */
    val INDICES: List<Pair<String, String>> = listOf(
        "NIFTY" to "Nifty 50", "BANKNIFTY" to "Nifty Bank", "FINNIFTY" to "Nifty Financial Services",
        "MIDCPNIFTY" to "Nifty Midcap Select", "SENSEX" to "BSE Sensex", "INDIAVIX" to "India VIX",
    )

    /** The indices whose options are in the app's option list (NSE's; SENSEX's trade on BSE). */
    val NFO_ROOTS: List<String> = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

    private val ALIASES = mapOf(
        "NIFTY 50" to "NIFTY", "NIFTY50" to "NIFTY", "BANK NIFTY" to "BANKNIFTY", "NIFTY BANK" to "BANKNIFTY",
        "FIN NIFTY" to "FINNIFTY", "NIFTY FIN" to "FINNIFTY", "NIFTY FIN SERVICE" to "FINNIFTY",
        "MIDCAP NIFTY" to "MIDCPNIFTY", "MIDCP NIFTY" to "MIDCPNIFTY", "NIFTY MID SELECT" to "MIDCPNIFTY",
        "INDIA VIX" to "INDIAVIX", "VIX" to "INDIAVIX", "BSE SENSEX" to "SENSEX",
    )

    const val RECENT_MAX = 6
    /** Settings keys: the last symbols charted ("SYM|EX;SYM|EX"), and the last one (reopened after a restart). */
    const val RECENT_KEY = "chart.recent"
    const val LAST_KEY = "chart.last"

    fun indexExchange(symbol: String): String = if (symbol.equals("SENSEX", true)) "BSE" else "NSE"

    /** How a symbol is shown: INDIAVIX as "INDIA VIX"; everything else as charted. */
    fun title(symbol: String): String = if (symbol.equals("INDIAVIX", true)) "INDIA VIX" else symbol

    fun indices(): List<SymbolChoice> = INDICES.map { (s, name) ->
        SymbolChoice(s, indexExchange(s), title(s), name, why = if (ChartFeed.isIndex(s)) null else "No candle feed for $s in this app")
    }

    /**
     * Every MCX commodity as its near-month future ([mcx]: the day's MCX list, null while it is being read; [why]: why it
     * could not be read). A commodity with no live future in the list is greyed with the reason.
     */
    fun commodities(mcx: List<McxContract>?, why: String?, today: LocalDate): List<SymbolChoice> = Mcx.NAMES.map { name ->
        val label = Mcx.commodity(name)?.label ?: name
        val fut = mcx?.let { McxInstruments.futures(it, name, today, 1).firstOrNull() }
        when {
            fut != null -> SymbolChoice(fut.tradingSymbol, Mcx.EXCHANGE, name, "$label · ${fut.label.removePrefix("$name ")}")
            mcx == null -> SymbolChoice(name, Mcx.EXCHANGE, name, label, why = why ?: "Reading MCX's contract list…")
            else -> SymbolChoice(name, Mcx.EXCHANGE, name, label, why = "No $name future in today's MCX list")
        }
    }

    /** The option underlyings: NSE's index options ([nfo] null while read), SENSEX (greyed: BSE's), then MCX's with options. */
    fun optionRoots(nfo: List<Upstox.Contract>?, nfoWhy: String?, mcx: List<McxContract>?, today: LocalDate): List<OptionRoot> {
        val nse = NFO_ROOTS.map { u ->
            OptionRoot(u, false, why = when {
                nfo == null -> nfoWhy ?: "Reading the option list…"
                nfo.none { it.underlying == u && !it.isExpired(today) } -> "No $u options in the option list on the phone"
                else -> null
            })
        }
        val commodities = mcx?.let { McxInstruments.chainNames(it, today) }.orEmpty().map { OptionRoot(it, true) }
        return nse + commodities + OptionRoot("SENSEX", false, why = "SENSEX options trade on BSE; the app's option list is NSE's")
    }

    /** [root]'s expiries still running, nearest first. */
    fun expiries(root: OptionRoot, nfo: List<Upstox.Contract>?, mcx: List<McxContract>?, today: LocalDate): List<LocalDate> =
        (if (root.mcx) mcx.orEmpty().filter { it.isOption && it.name == root.name && !it.expiry.isBefore(today) }.map { it.expiry }
        else nfo.orEmpty().filter { it.underlying == root.name && !it.isExpired(today) }.map { it.expiry }).distinct().sorted()

    /** [root]'s strikes on [expiry], low to high, each with its CE and PE. */
    fun strikes(root: OptionRoot, expiry: LocalDate, nfo: List<Upstox.Contract>?, mcx: List<McxContract>?): List<StrikeRow> {
        val legs: List<Triple<Double, Right, String>> =
            if (root.mcx) mcx.orEmpty().filter { it.isOption && it.name == root.name && it.expiry == expiry }.mapNotNull { c -> c.right?.let { Triple(c.strike, it, c.tradingSymbol) } }
            else nfo.orEmpty().filter { it.underlying == root.name && it.expiry == expiry }.map { Triple(it.strike, it.right, it.tradingSymbol) }
        return legs.groupBy { it.first }.map { (k, l) ->
            StrikeRow(k, l.firstOrNull { it.second == Right.CE }?.third, l.firstOrNull { it.second == Right.PE }?.third)
        }.sortedBy { it.strike }
    }

    /** The [n] strikes nearest [spot] (the middle ones while the price is not known), low to high. */
    fun near(rows: List<StrikeRow>, spot: Double?, n: Int = 21): List<StrikeRow> {
        if (rows.size <= n) return rows
        if (spot == null) { val from = (rows.size - n) / 2; return rows.subList(from, from + n) }
        return rows.sortedBy { abs(it.strike - spot) }.take(n).sortedBy { it.strike }
    }

    /** What the strikes are centred on: the index, or the MCX future [root]'s options on [expiry] turn into. */
    fun spotSymbol(root: OptionRoot, expiry: LocalDate?, mcx: List<McxContract>?): String? {
        if (!root.mcx) return root.name
        val all = mcx.orEmpty()
        val opt = all.firstOrNull { it.isOption && it.name == root.name && (expiry == null || it.expiry == expiry) } ?: return null
        return McxInstruments.underlyingFuture(all, opt)?.tradingSymbol
    }

    /**
     * What a typed [text] can chart, best first: an index by name or alias ("bank nifty", "vix"), a commodity's name as
     * its near and next futures, a trading symbol typed in full from the lists on the phone, then the feed's own search
     * ([search]: indices, NSE options with every word typed, MCX contracts). An expired contract typed in full is greyed
     * with why. Never the network itself ([search] reads the lists on the phone).
     */
    fun resolve(text: String, nfo: List<Upstox.Contract>?, mcx: List<McxContract>?, today: LocalDate, search: (String) -> List<ChartFeed.Match>): List<SymbolChoice> {
        val q = text.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val flat = q.replace(" ", "")
        val out = LinkedHashMap<String, SymbolChoice>()
        fun add(c: SymbolChoice) { val k = c.symbol.uppercase(Locale.ROOT); if (k !in out) out[k] = c }
        val idx = indices()
        ALIASES[q]?.let { a -> idx.firstOrNull { it.symbol == a }?.let(::add) }
        idx.filter { it.symbol.contains(flat) || it.title.replace(" ", "").contains(flat) }.forEach(::add)
        val list = mcx.orEmpty()
        Mcx.NAMES.filter { it == flat || (flat.length >= 3 && it.startsWith(flat)) }.forEach { n ->
            McxInstruments.futures(list, n, today, 2).forEach { f -> add(SymbolChoice(f.tradingSymbol, Mcx.EXCHANGE, f.tradingSymbol, f.label)) }
        }
        list.firstOrNull { it.tradingSymbol.equals(flat, true) }?.let {
            add(SymbolChoice(it.tradingSymbol, Mcx.EXCHANGE, it.tradingSymbol, it.label,
                why = if (it.expiry.isBefore(today)) expired(it.expiry) else null))
        }
        nfo.orEmpty().firstOrNull { it.tradingSymbol.equals(flat, true) }?.let {
            add(SymbolChoice(it.tradingSymbol, "NFO", it.tradingSymbol, "${it.underlying} option · lot ${it.lotSize}",
                why = if (it.isExpired(today)) expired(it.expiry) else null))
        }
        runCatching { search(text.trim()) }.getOrDefault(emptyList()).forEach { m -> add(SymbolChoice(m.symbol, m.exchange, title(m.symbol), m.name)) }
        return out.values.take(40)
    }

    private fun expired(d: LocalDate) = "Expired on $d; the candle feed keeps no expired contracts"

    /** [list] with [s] first, once, at most [RECENT_MAX]. */
    fun pushRecent(list: List<Pair<String, String>>, s: Pair<String, String>): List<Pair<String, String>> =
        (listOf(s) + list.filter { !it.first.equals(s.first, true) }).take(RECENT_MAX)

    fun encode(list: List<Pair<String, String>>): String = list.joinToString(";") { "${it.first}|${it.second}" }

    fun decode(kept: String?): List<Pair<String, String>> = kept.orEmpty().split(';').mapNotNull { one ->
        val parts = one.split('|')
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
    }.take(RECENT_MAX)

    /** The symbol charted last (kept in the settings), or null. */
    fun lastAsk(): Pair<String, String>? =
        runCatching { decode(com.optionslab.app.security.SecurePrefs.getString(LAST_KEY)).firstOrNull() }.getOrNull()
}

private val EXPIRY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

/**
 * The Chart tab's symbol picker (native, so it works whatever the web chart does): search, recent, indices,
 * commodities (MCX near-month futures) and options (underlying → expiry → strike → CE / PE, or the priced chain
 * through [onChain], the chart's own OPT popup). The instrument lists are read off the main thread when it opens.
 * [onPick] (symbol, exchange) charts one; greyed entries say why they cannot be charted and do nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartSymbolSheet(
    current: String,
    source: ChartSource,
    recents: List<Pair<String, String>>,
    onPick: (String, String) -> Unit,
    onChain: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val today = remember { com.optionslab.app.data.Market.today() }
    var nfo by remember { mutableStateOf<List<Upstox.Contract>?>(null) }
    var nfoWhy by remember { mutableStateOf<String?>(null) }
    var mcx by remember { mutableStateOf<List<McxContract>?>(null) }
    var mcxWhy by remember { mutableStateOf<String?>(null) }
    // The lists on the phone first, then today's where the phone has none or an older one (network, on IO).
    LaunchedEffect(source) { withContext(Dispatchers.Main.immediate) {
        mcx = withContext(Dispatchers.IO) { runCatching { source.mcx() }.getOrNull()?.takeIf { it.isNotEmpty() } }
        val fresh = withContext(Dispatchers.IO) { runCatching { source.loadMcx() } }
        val got = fresh.getOrNull()?.takeIf { it.isNotEmpty() }
        if (got != null) { mcx = got; mcxWhy = null }
        else if (mcx == null) mcxWhy = "MCX's contract list could not be read" + (fresh.exceptionOrNull()?.message?.let { ": $it" } ?: "; try again later")
        Unit
    } }
    LaunchedEffect(source) { withContext(Dispatchers.Main.immediate) {
        val saved = withContext(Dispatchers.IO) { runCatching { source.cachedContracts() }.getOrNull() }
        if (!saved.isNullOrEmpty()) { nfo = saved; return@withContext }
        val fresh = withContext(Dispatchers.IO) { runCatching { source.contracts() } }
        nfo = fresh.getOrNull()
        if (nfo == null) nfoWhy = "The option list could not be read" + (fresh.exceptionOrNull()?.message?.let { ": $it" } ?: "")
        Unit
    } }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SymbolChoice>?>(null) }
    LaunchedEffect(query, nfo, mcx) { withContext(Dispatchers.Main.immediate) {
        val q = query
        if (q.isBlank()) results = null
        else {
            val n = nfo; val m = mcx
            results = withContext(Dispatchers.IO) { ChartSymbols.resolve(q, n, m, today) { source.search(it) } }
        }
        Unit
    } }

    var root by remember { mutableStateOf<OptionRoot?>(null) }
    var expiry by remember { mutableStateOf<LocalDate?>(null) }
    var spot by remember { mutableStateOf<Double?>(null) }
    // The strikes are centred on the underlying's last price (one small candle read, off the main thread).
    LaunchedEffect(root, expiry, mcx) { withContext(Dispatchers.Main.immediate) {
        spot = null
        val r = root ?: return@withContext
        val sym = ChartSymbols.spotSymbol(r, expiry, mcx) ?: return@withContext
        val t = System.currentTimeMillis() / 1000
        spot = withContext(Dispatchers.IO) { runCatching { source.bars(sym, "1m", t - 3 * 86400, t).lastOrNull()?.close }.getOrNull() }
        Unit
    } }

    // The option roots, a root's expiries and an expiry's strikes filter the whole NFO list (tens of thousands of contracts):
    // worked out off the main thread, each tagged with what it was worked out for so a stale list is never shown.
    val roots by produceState<List<OptionRoot>?>(null, nfo, nfoWhy, mcx) {
        withContext(Dispatchers.Main.immediate) {
            val n = nfo; val w = nfoWhy; val m = mcx
            value = withContext(Dispatchers.Default) { ChartSymbols.optionRoots(n, w, m, today) }
        }
    }
    val rootExpiries by produceState<Pair<OptionRoot, List<LocalDate>>?>(null, root, nfo, mcx) {
        withContext(Dispatchers.Main.immediate) {
            val r = root ?: return@withContext
            val n = nfo; val m = mcx
            value = r to withContext(Dispatchers.Default) { ChartSymbols.expiries(r, n, m, today) }
        }
    }
    val expiryStrikes by produceState<Pair<Pair<OptionRoot, LocalDate>, List<StrikeRow>>?>(null, root, expiry, nfo, mcx) {
        withContext(Dispatchers.Main.immediate) {
            val r = root ?: return@withContext
            val e = expiry ?: return@withContext
            val n = nfo; val m = mcx
            value = (r to e) to withContext(Dispatchers.Default) { ChartSymbols.strikes(r, e, n, m) }
        }
    }

    fun pick(c: SymbolChoice) { if (c.why == null) onPick(c.symbol, c.exchange) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chart a symbol", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            androidx.compose.material3.OutlinedTextField(query, { query = it.take(40) }, singleLine = true,
                label = { Text("Search: index, MCX or option") },
                modifier = Modifier.fillMaxWidth().testTag("pick:search"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results?.firstOrNull { it.why == null }?.let { pick(it) } }))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                val found = results
                if (query.isNotBlank()) {
                    PickHeader("Results")
                    when {
                        found == null -> Text("Searching…", style = Type.bodySmall.copy(color = p.inkSoft))
                        found.isEmpty() -> Text("No index, MCX contract or option matches \"${query.trim()}\".", style = Type.bodySmall.copy(color = p.inkSoft))
                        else -> found.forEach { c -> PickRow(c, c.symbol.equals(current, true), "pick:result:${c.symbol}") { pick(c) } }
                    }
                } else {
                    if (recents.isNotEmpty()) {
                        PickHeader("Recent")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recents.forEach { (s, e) -> PickChip(ChartSymbols.title(s), e, null, s.equals(current, true), "pick:recent:$s") { onPick(s, e) } }
                        }
                    }
                    PickHeader("Indices")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartSymbols.indices().forEach { c -> PickChip(c.title, c.sub, c.why, c.symbol.equals(current, true), "pick:index:${c.symbol}") { pick(c) } }
                    }
                    PickHeader("Commodities (MCX)")
                    Text("The near-month future of each. Search a contract for another month.", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp),
                        modifier = Modifier.padding(bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        remember(mcx, mcxWhy) { ChartSymbols.commodities(mcx, mcxWhy, today) }.forEach { c -> PickChip(c.title, c.sub, c.why, c.symbol.equals(current, true), "pick:mcx:${c.title}") { pick(c) } }
                    }
                    PickHeader("Options")
                    Text("Pick an underlying, then the expiry, then a strike's CE or PE.", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp),
                        modifier = Modifier.padding(bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        roots.orEmpty().forEach { r ->
                            PickChip(r.name, if (r.mcx) "MCX" else "NSE", r.why, root?.name == r.name, "pick:root:${r.name}") { root = r; expiry = null }
                        }
                    }
                    root?.let { r ->
                        val worked = rootExpiries?.takeIf { it.first == r }?.second
                        val exps = worked.orEmpty()
                        PickHeader("${r.name} expiry")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            exps.take(8).forEach { e ->
                                PickChip(e.format(EXPIRY_DAY), null, null, expiry == e, "pick:expiry:$e") { expiry = e }
                            }
                            // The chart's own priced chain (nearest expiry): tap a price there to chart it.
                            if (onChain != null) PickChip("Priced chain ›", "nearest expiry", null, false, "pick:chain:${r.name}") { onChain(r.name) }
                        }
                        if (worked != null && exps.isEmpty()) Text("No ${r.name} expiry in the list on the phone.", style = Type.bodySmall.copy(color = p.inkSoft))
                        expiry?.let { e ->
                            val all = expiryStrikes?.takeIf { it.first == (r to e) }?.second.orEmpty()
                            val rows = ChartSymbols.near(all, spot)
                            PickHeader("${r.name} ${e.format(EXPIRY_DAY)} strikes" + (spot?.let { " · last ${fmtG(Math.round(it * 100) / 100.0)}" } ?: ""))
                            val ex = if (r.mcx) Mcx.EXCHANGE else "NFO"
                            rows.forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(fmtG(row.strike), style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
                                    PickLeg("CE", row.ce, current, "pick:CE:${fmtG(row.strike)}") { s -> onPick(s, ex) }
                                    PickLeg("PE", row.pe, current, "pick:PE:${fmtG(row.strike)}") { s -> onPick(s, ex) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickHeader(text: String) {
    val p = LocalPalette.current
    Text(text, style = Type.label.copy(color = p.inkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

/** A chip: [title] over [sub]; greyed with [why] under it (not tappable) when it cannot be charted. */
@Composable
private fun PickChip(title: String, sub: String?, why: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val enabled = why == null
    val bg = when { selected -> p.brass; enabled -> p.chip; else -> p.paperDeep }
    val fg = when { selected -> p.onPrimary; enabled -> p.ink; else -> p.inkFaint }
    Column(Modifier.testTag(tag).widthIn(min = 48.dp, max = 280.dp).heightIn(min = 48.dp).background(bg, RoundedCornerShape(12.dp))
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) { disabled() })
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = Type.label.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        if (!sub.isNullOrBlank()) Text(sub, style = Type.bodySmall.copy(color = if (selected) fg else p.inkSoft, fontSize = 11.sp))
        if (why != null) Text(why, style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp))
    }
}

/** A search result on its own line: the symbol, its exchange and what it is; greyed with why when it cannot be charted. */
@Composable
private fun PickRow(c: SymbolChoice, selected: Boolean, tag: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val enabled = c.why == null
    Column(Modifier.testTag(tag).fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 48.dp)
        .background(if (selected) p.brass else if (enabled) p.chip else p.paperDeep, RoundedCornerShape(12.dp))
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) { disabled() })
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        val fg = if (selected) p.onPrimary else if (enabled) p.ink else p.inkFaint
        Text(c.title, style = Type.label.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        Text(listOf(c.exchange, c.sub).filter { it.isNotBlank() }.joinToString(" · "), style = Type.bodySmall.copy(color = if (selected) fg else p.inkSoft, fontSize = 11.sp))
        c.why?.let { Text(it, style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp)) }
    }
}

/** A strike's CE or PE: charts that option; a dash when it is not listed. */
@Composable
private fun PickLeg(label: String, symbol: String?, current: String, tag: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    if (symbol == null) {
        Text("—", style = Type.label.copy(color = p.inkFaint), modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 12.dp))
        return
    }
    val selected = symbol.equals(current, true)
    androidx.compose.foundation.layout.Box(Modifier.testTag(tag).widthIn(min = 64.dp).heightIn(min = 48.dp)
        .background(if (selected) p.brass else p.chip, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClickLabel = "Chart $symbol") { onPick(symbol) }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, softWrap = false, style = Type.label.copy(color = if (selected) p.onPrimary else p.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}
