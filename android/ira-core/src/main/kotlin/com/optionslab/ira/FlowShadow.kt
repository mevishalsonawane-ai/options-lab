package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * The order flow's evidence (Boss, 9 Oct 2026): at every strategy signal - paper or live, taken or not - the flow's read at
 * that moment is logged beside it ([signal]), then what became of it: taken, skipped by the flow, refused for another reason
 * ([verdict]); a paper entry's order ([order]) and later its result ([result]); and the underlying future's move in the
 * trade's direction 15 and 30 minutes on ([move]), for the signals not taken too. [summary] sets the trades taken WITH the
 * flow against those taken AGAINST it, per strategy, so a few weeks of it say whether the flow helps.
 *
 * Each strategy has its own [OrderFlow.Mode]: SHADOW by default (logged, nothing changes), CONFIRM (an entry the flow does
 * not agree with is skipped) only where Boss sets it, OFF. The lines are plain text, one event a line, appended to a file on
 * the phone (market data and the strategies' own signals; nothing about the account, no token, key or URL). Pure.
 */
object FlowShadow {
    /** A strategy the flow can confirm: its key, its name on screen, and whether it can ever place a Zerodha order. */
    data class Strategy(val key: String, val label: String, val liveCapable: Boolean, val group: String)

    val STRATEGIES: List<Strategy> = listOf(
        Strategy("orb", "ORB", true, "BankNifty arms"),
        Strategy("orb_fresh", "ORB Fresh", true, "BankNifty arms"),
        Strategy("orb_sweep", "ORB Sweep", false, "BankNifty arms"),
        Strategy("range_fade", "Range Fade", false, "BankNifty arms"),
        Strategy("liquidity:BANKNIFTY", "Liquidity 15+5 BankNifty", true, "Liquidity 15+5"),
        Strategy("liquidity:FINNIFTY", "Liquidity 15+5 FinNifty", true, "Liquidity 15+5"),
        Strategy("liquidity:MIDCPNIFTY", "Liquidity 15+5 Midcap Nifty", true, "Liquidity 15+5"),
        Strategy("night_r3", "Night (R3)", false, "Paper-only bots"),
        Strategy("hero", "Hero (expiry)", false, "Paper-only bots"),
        Strategy("solo", "Jarvis Solo", false, "Paper-only bots"),
        Strategy("vix_div", "VIX divergence", false, "Paper-only bots"),
        Strategy("pine", "Pine auto-trade arms", true, "Your scripts"),
        Strategy("saved", "Saved strategies (one-leg CE/PE)", true, "Your scripts"),
        Strategy("jarvis", "Jarvis's own trades", true, "Jarvis"),
        Strategy("mcx_ng_eve", "MCX natural gas evening", false, "MCX (commodities)"),
        Strategy("mcx_silverm_am", "MCX silver morning call", false, "MCX (commodities)"),
        Strategy("mcx_trend12", "MCX 12-month trend", false, "MCX (commodities)"),
        Strategy("mcx_us_silver", "MCX US-night silver", false, "MCX (commodities)"),
    )

    private val BY_KEY = STRATEGIES.associateBy { it.key }

    fun strategy(key: String): Strategy? = BY_KEY[key]

    /** Liquidity 15+5's key for the index its book trades. */
    fun liquidityKey(underlying: String): String = "liquidity:$underlying"

    /** The default for every strategy: logged beside each signal, nothing changed. */
    val DEFAULT = OrderFlow.Mode.SHADOW

    /** The modes and thresholds as kept in the settings: "key=MODE:55;key=MODE:60". Unknown or bad parts are left out. */
    data class Setting(val mode: OrderFlow.Mode, val threshold: Int = OrderFlow.THRESHOLD)

    fun encode(m: Map<String, Setting>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k=${v.mode.name}:${v.threshold}" }

    fun decode(s: String?): Map<String, Setting> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, Setting>()
        for (part in s.split(';')) {
            val k = part.substringBefore('=', "").trim()
            val v = part.substringAfter('=', "")
            if (k.isEmpty() || k !in BY_KEY) continue
            val mode = runCatching { OrderFlow.Mode.valueOf(v.substringBefore(':')) }.getOrNull() ?: continue
            val th = v.substringAfter(':', "").toIntOrNull()?.coerceIn(OrderFlow.MIN_THRESHOLD, OrderFlow.MAX_THRESHOLD) ?: OrderFlow.THRESHOLD
            out[k] = Setting(mode, th)
        }
        return out
    }

    fun setting(m: Map<String, Setting>, key: String): Setting = m[key] ?: Setting(DEFAULT)

    // ---- the lines ------------------------------------------------------------------------------------------------------

    /** A signal's id: its strategy, its own time (a bar, a minute) and what it buys. Unique per signal, kept across a restart. */
    fun id(key: String, at: String, what: String = ""): String = clean("$key@$at" + if (what.isEmpty()) "" else "@$what")

    /** Field text without the separator or a line break. */
    fun clean(s: String): String = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(160)

    private fun d(x: Double?) = if (x == null || x.isNaN()) "" else "%.4f".format(Locale.ENGLISH, x)

    /** The signal and the flow at it ([r] null: no read). */
    fun signal(id: String, epochSec: Long, key: String, underlying: String, side: Int, live: Boolean, mode: OrderFlow.Mode, threshold: Int,
               a: OrderFlow.Agreement, skipped: Boolean, r: OrderFlow.Read?, book: String = "", ctx: Context? = null): String = listOf(
        "S", clean(id), epochSec.toString(), clean(key), clean(underlying), side.toString(), if (live) "1" else "0", mode.name,
        threshold.toString(), a.name, if (skipped) "1" else "0", r?.buyers?.toString() ?: "", if (r?.warm == true) "1" else "0",
        d(r?.ofi60), d(r?.cvd60), d(r?.depth), d(r?.queue), d(r?.ratio), d(r?.optionNet), r?.buildUp?.name ?: "",
        d(r?.z?.get("ofi")), d(r?.z?.get("cvd60")), d(r?.z?.get("depth60")), d(r?.z?.get("ratio")), d(r?.z?.get("options")), d(r?.mid),
        // The 10 / 60 / 300 s values (HUNT R7), and the traded option's 5-level book at the signal.
        d(r?.ofi10), d(r?.ofi300), d(r?.cvd10), d(r?.cvd300), d(r?.depth10), d(r?.depth60), d(r?.depth300), d(r?.qcvd60), clean(book),
        // The trap guard at the signal: its flags, the buyers' share before it, the pulls and the cancel-to-trade ratio.
        r?.flags?.joinToString(",") { it.name } ?: "", r?.rawBuyers?.toString() ?: "", r?.pulledBid60?.toString() ?: "",
        r?.pulledAsk60?.toString() ?: "", d(r?.cancelToTrade),
    ).plus(context(ctx)).joinToString("|")

    /**
     * The auction and the gamma regime at a signal (9 Oct 2026; logged only, never a rule - research tests whether each one
     * helped): [price] (the future's), the auction's [auction] snapshot, the read [r] (its absorption note), the gamma
     * regime [gamma] under [convention], and the index's [basis] (future − index) to place the future against the index's
     * zero-gamma level.
     */
    data class Context(
        val price: Double?, val auction: Auction.Snapshot?, val r: OrderFlow.Read?,
        val gamma: GammaRegime.Result?, val convention: GammaRegime.Convention = GammaRegime.Convention.STANDARD, val basis: Double? = null,
    )

    /** The context's field names, in the order logged after the trap guard's (field [CONTEXT_FROM] on). */
    val CONTEXT_FIELDS = listOf(
        "prior_poc_dist", "prior_vah_dist", "prior_val_dist", "regime", "delta15", "divergence", "absorption",
        "gex_sign", "gex_convention", "zero_gamma_dist", "vwap_sd", "vwap_side", "ib_range", "open_type", "day_type",
    )

    /** The first context field's index in an S line. */
    const val CONTEXT_FROM = 40

    /** The context's fields (empty strings where not known; all empty with no context). */
    fun context(c: Context?): List<String> {
        if (c == null) return CONTEXT_FIELDS.map { "" }
        val px = c.price?.takeIf { it > 0 } ?: c.r?.mid?.takeIf { it > 0 } ?: c.auction?.last?.takeIf { it > 0 }
        val prior = c.auction?.prior
        fun dist(level: Double?) = if (px == null || level == null) "" else d(px - level)
        val g = c.gamma
        val zgDist = if (g?.zeroGamma == null || px == null) "" else d(px - (c.basis ?: (g.forward - g.spot)) - g.zeroGamma)
        val v = c.auction?.vwap
        val tpo = c.auction?.tpo
        return listOf(
            dist(prior?.poc), dist(prior?.vah), dist(prior?.vaLow), c.auction?.regime?.name ?: "",
            c.auction?.delta15?.toString() ?: "", c.auction?.divergence?.name ?: "", clean(OrderFlow.absorption(c.r) ?: ""),
            g?.let { if (it.net(c.convention) >= 0) "1" else "-1" } ?: "", if (g != null) c.convention.name else "", zgDist,
            if (v != null && px != null) d(v.sdUnits(px)) else "", if (v != null && px != null) (if (px >= v.vwap) "above" else "below") else "",
            d(tpo?.ibRange), tpo?.openType?.name ?: "", tpo?.dayType?.name ?: "",
        )
    }

    /** The verdict of an entry the flow skipped (CONFIRM). */
    const val SKIPPED = "skipped_by_flow"

    /** What became of signal [id] ("entered", "skipped_by_flow", a refusal). */
    fun verdict(id: String, what: String): String = "V|${clean(id)}|${clean(what)}"

    /** Signal [id] entered on paper with order [orderId] (its result is read from the paper book later). */
    fun order(id: String, orderId: String): String = "O|${clean(id)}|${clean(orderId)}"

    /** Signal [id]'s trade closed with [net] rupees (after charges when known). */
    fun result(id: String, net: Double): String = "R|${clean(id)}|${d(net)}"

    /** The underlying future's move in the trade's direction 15 and 30 minutes after signal [id] (points; null not read). */
    fun move(id: String, m15: Double?, m30: Double?): String = "M|${clean(id)}|${d(m15)}|${d(m30)}"

    /** One signal as read back from the lines. */
    data class Signal(
        val id: String, val epochSec: Long, val key: String, val underlying: String, val side: Int, val live: Boolean,
        val mode: OrderFlow.Mode, val agreement: OrderFlow.Agreement, var skipped: Boolean, val buyers: Int?,
        var verdict: String? = null, var orderId: String? = null, var net: Double? = null, var m15: Double? = null, var m30: Double? = null,
        /** The trap guard's flags at the signal ([TrapGuard.Trap] names). */
        var flags: List<String> = emptyList(),
        /** The auction and gamma context at the signal ([CONTEXT_FIELDS] to their logged text; empty for older lines). */
        var context: Map<String, String> = emptyMap(),
    ) {
        val taken: Boolean get() = verdict?.startsWith("entered") == true || orderId != null
    }

    /** Every signal in [lines], oldest first (a later line for an id fills in that signal; unreadable lines are skipped). */
    fun parse(lines: Sequence<String>): List<Signal> {
        val out = LinkedHashMap<String, Signal>()
        for (l in lines) {
            val f = l.split('|')
            when (f.getOrNull(0)) {
                "S" -> runCatching {
                    if (f.size < 12 || f[1] in out) return@runCatching
                    out[f[1]] = Signal(f[1], f[2].toLong(), f[3], f[4], f[5].toInt(), f[6] == "1", OrderFlow.Mode.valueOf(f[7]),
                        OrderFlow.Agreement.valueOf(f[9]), f[10] == "1", f[11].toIntOrNull(),
                        flags = f.getOrNull(35)?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                        context = CONTEXT_FIELDS.withIndex().mapNotNull { (i, k) -> f.getOrNull(CONTEXT_FROM + i)?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap())
                }
                "V" -> out[f.getOrNull(1)]?.let { s ->
                    f.getOrNull(2)?.let { v -> if (s.verdict == null || !s.taken) s.verdict = v; if (v == SKIPPED) s.skipped = true }
                }
                "O" -> out[f.getOrNull(1)]?.let { it.orderId = f.getOrNull(2) }
                "R" -> out[f.getOrNull(1)]?.let { it.net = f.getOrNull(2)?.toDoubleOrNull() }
                "M" -> out[f.getOrNull(1)]?.let { it.m15 = f.getOrNull(2)?.toDoubleOrNull(); it.m30 = f.getOrNull(3)?.toDoubleOrNull() }
            }
        }
        return out.values.toList()
    }

    // ---- the record -----------------------------------------------------------------------------------------------------

    /** Closed paper trades: how many, how many won, their net; and the 30-minute moves of the signals (taken or not). */
    data class Tally(val trades: Int = 0, val wins: Int = 0, val net: Double = 0.0, val moves: Int = 0, val moveSum: Double = 0.0) {
        val winPct: Int? get() = if (trades == 0) null else Math.round(wins * 100.0 / trades).toInt()
        val avgMove: Double? get() = if (moves == 0) null else moveSum / moves
        operator fun plus(s: Signal): Tally {
            val closed = s.net != null && !s.live
            return Tally(trades + if (closed) 1 else 0, wins + if (closed && s.net!! > 0) 1 else 0, net + if (closed) s.net!! else 0.0,
                moves + if (s.m30 != null) 1 else 0, moveSum + (s.m30 ?: 0.0))
        }
    }

    /** One strategy's record: signals, skipped by the flow, and the trades WITH the flow against those AGAINST it. */
    data class Record(val key: String, val signals: Int, val skipped: Int, val with: Tally, val against: Tally, val unknown: Tally)

    fun summary(signals: List<Signal>): List<Record> = signals.groupBy { it.key }.map { (k, list) ->
        var w = Tally(); var a = Tally(); var u = Tally()
        for (s in list) when (s.agreement) {
            OrderFlow.Agreement.AGREES -> w += s
            OrderFlow.Agreement.DISAGREES -> a += s
            OrderFlow.Agreement.UNKNOWN -> u += s
        }
        Record(k, list.size, list.count { it.skipped }, w, a, u)
    }.sortedBy { r -> STRATEGIES.indexOfFirst { it.key == r.key }.let { if (it < 0) Int.MAX_VALUE else it } }

    private fun rs(x: Double) = (if (x < 0) "−Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))

    /** "3 trades, 67% won, +Rs 1,240" or "no closed paper trades". */
    fun tallyWords(t: Tally): String = if (t.trades == 0) "no closed paper trades" else
        "${t.trades} trade${if (t.trades == 1) "" else "s"}, ${t.winPct}% won, ${rs(t.net)}"

    /** The row's line: "With flow: 3 trades, 67% won, +Rs 1,240 · against: 2 trades, 0% won, −Rs 800 · 1 skipped". */
    fun recordLine(r: Record?): String {
        if (r == null || r.signals == 0) return "No signals logged yet."
        return "With flow: ${tallyWords(r.with)} · against: ${tallyWords(r.against)}" +
            (if (r.skipped > 0) " · ${r.skipped} skipped by the flow" else "") + " · ${r.signals} signal${if (r.signals == 1) "" else "s"} logged"
    }

    // ---- Jarvis: "how is order flow helping?" ----------------------------------------------------------------------------

    private val FLOW = Regex(" (order ?flow|orderflow|flow filter|flow confirm) ")
    private val HELP = Regex(" (helping|helped|help|working|worked|record|results?|paying|useful|worth|evidence|proof|proven) ")

    fun helpAsked(text: String): Boolean {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return FLOW.containsMatchIn(t) && HELP.containsMatchIn(t)
    }

    /** Jarvis's answer from the record [records]: overall, then the strategies with closed trades on both sides. */
    fun helpAnswer(records: List<Record>): String {
        if (records.isEmpty() || records.all { it.signals == 0 })
            return "No signals logged with the order flow yet, Boss. Every strategy logs the flow beside each signal from now on; " +
                "give it a few weeks of trades before reading anything into it."
        val all = records.fold(Tally() to Tally()) { (w, a), r ->
            Tally(w.trades + r.with.trades, w.wins + r.with.wins, w.net + r.with.net, w.moves + r.with.moves, w.moveSum + r.with.moveSum) to
                Tally(a.trades + r.against.trades, a.wins + r.against.wins, a.net + r.against.net, a.moves + r.against.moves, a.moveSum + r.against.moveSum)
        }
        val sig = records.sumOf { it.signals }
        val skipped = records.sumOf { it.skipped }
        val head = "$sig signal${if (sig == 1) "" else "s"} logged with the flow" + (if (skipped > 0) ", $skipped skipped by it" else "") + ". " +
            "Paper trades taken with the flow: ${tallyWords(all.first)}; against it: ${tallyWords(all.second)}."
        val moves = listOfNotNull(all.first.avgMove?.let { "after signals with the flow the future moved %+.1f points in the trade's way in 30 minutes".format(Locale.ENGLISH, it) },
            all.second.avgMove?.let { "against it %+.1f".format(Locale.ENGLISH, it) })
        val per = records.filter { it.with.trades > 0 && it.against.trades > 0 }.take(3).joinToString("; ") { r ->
            "${strategy(r.key)?.label ?: r.key}: with ${rs(r.with.net)} over ${r.with.trades}, against ${rs(r.against.net)} over ${r.against.trades}"
        }
        val enough = all.first.trades >= 20 && all.second.trades >= 20
        return head + (if (moves.isNotEmpty()) " " + moves.joinToString(", ").replaceFirstChar { it.uppercase() } + "." else "") +
            (if (per.isNotEmpty()) " $per." else "") +
            if (enough) " That is enough trades to start comparing, still on paper." else " Too few trades to say it helps yet; it stays a paper test."
    }
}
