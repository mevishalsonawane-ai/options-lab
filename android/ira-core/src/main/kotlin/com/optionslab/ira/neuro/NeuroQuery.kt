package com.optionslab.ira.neuro

import com.optionslab.ira.dhan.DhanUniverse
import java.util.Locale
import kotlin.math.abs

/**
 * Questions put to the [NeuroGraph], for Jarvis ([com.optionslab.ira.NeuroAsk]) and the screens: a node's neighbours, the
 * strongest edges of a type, what usually follows an event, what moves an index most, when zero-to-hero moves happen,
 * how two things are related, what is proven and what is only a hypothesis, and the graph's counts. Every sentence it
 * makes ([say]) gives the rate, the base rate, the sample count and the status - a pattern in past data, never a fact
 * about tomorrow. Read only. Pure.
 */
class NeuroQuery(val g: NeuroGraph) {
    fun node(id: String): Node? = g.byId[id]
    fun label(id: String): String = g.byId[id]?.label ?: id.substringAfter(':')

    /** Edges touching [id], strongest first (learned before structural when [learnedFirst]). */
    fun neighbours(id: String, limit: Int = 10, learnedFirst: Boolean = true): List<Edge> =
        ((g.out[id] ?: emptyList()) + (g.into[id] ?: emptyList()))
            .sortedWith(compareByDescending<Edge> { if (learnedFirst && it.learned) 1 else 0 }.thenByDescending { it.score() }.thenBy { it.from }.thenBy { it.to })
            .take(limit)

    /** The strongest edges of [type]. */
    fun strongest(type: EdgeType, limit: Int = 10, provenOnly: Boolean = false): List<Edge> =
        g.edges.filter { it.type == type && (!provenOnly || it.proven) }.sortedByDescending { it.score() }.take(limit)

    /** What usually followed [e] on [u] (and, for India VIX's events, on the indices): PRECEDES edges, biggest lift first. */
    fun after(u: String, e: Ev, limit: Int = 5): List<Edge> {
        val src = NeuroLearn.ev(if (e.vix) NeuroBuilder.VIX else u, e)
        return (g.out[src] ?: emptyList()).filter { it.type == EdgeType.PRECEDES && (!e.vix || it.to.startsWith("EV:$u:")) }
            .sortedWith(compareByDescending<Edge> { it.proven }.thenByDescending { it.lift }).take(limit)
    }

    /** What moves [u] most: its companies by how they moved on its big days (CONTRIBUTES), then correlation, and who leads it. */
    fun movers(u: String, limit: Int = 5): List<Edge> {
        val into = g.into[NeuroLearn.idx(u)] ?: emptyList()
        val contrib = into.filter { it.type == EdgeType.CONTRIBUTES }
            .sortedWith(compareByDescending<Edge> { it.proven }.thenByDescending { it.weight * it.extra.coerceIn(0.0, 2.0).let { b -> if (b.isNaN()) 1.0 else b } })
        val corr = into.filter { it.type == EdgeType.CORRELATES && contrib.none { c -> c.from == it.from } }.sortedByDescending { abs(it.weight) }
        val leads = into.filter { it.type == EdgeType.LEADS }.sortedByDescending { it.score() }
        return (contrib + corr).take(limit) + leads.take(2)
    }

    /** When zero-to-hero moves happen ([u], or every index): their time-of-day, weekday, expiry and regime edges. */
    fun zeroToHero(u: String? = null, limit: Int = 6): List<Edge> =
        g.edges.filter { it.type == EdgeType.OCCURS_IN && it.from.endsWith(":${Ev.ZERO_TO_HERO.name}") && (u == null || it.from == NeuroLearn.ev(u, Ev.ZERO_TO_HERO)) }
            .sortedWith(compareByDescending<Edge> { it.proven }.thenByDescending { it.lift }).take(limit)

    /** Every edge between [a] and [b] (either way), and a shared sector or index. */
    fun related(a: String, b: String): List<Edge> {
        val direct = g.edges.filter { (it.from == a && it.to == b) || (it.from == b && it.to == a) }
        val shared = (g.out[a] ?: emptyList()).filter { !it.learned }.mapNotNull { ea ->
            (g.out[b] ?: emptyList()).firstOrNull { eb -> !eb.learned && eb.to == ea.to && eb.type == ea.type }?.let { ea }
        }
        return direct.sortedByDescending { it.score() } + shared
    }

    fun proven(limit: Int = 5): List<Edge> = g.edges.filter { it.proven }.sortedByDescending { it.score() }.take(limit)
    fun hypotheses(limit: Int = 5): List<Edge> = g.edges.filter { it.status == Status.HYPOTHESIS }.sortedByDescending { it.score() }.take(limit)

    fun stats(): NeuroGraph.Summary = g.summary()

    /** A node named in [text]: an index, a company (symbol or common name) or a sector; null when none. */
    fun resolve(text: String): String? = names(text).firstOrNull()

    // ---- said --------------------------------------------------------------------------------------------------------------

    private fun pct(x: Double) = if (x.isNaN()) "?" else "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun num(x: Double, d: Int = 2) = if (x.isNaN()) "?" else "%.${d}f".format(Locale.ENGLISH, x)

    fun status(e: Edge): String = when (e.status) {
        Status.STRUCTURAL -> "from the index lists as of ${DhanUniverse.CONSTITUENTS_AS_OF}"
        Status.PROVEN -> "proven: it held on the newest 30% of the data" + (if (!e.testValue.isNaN() && e.type != EdgeType.CORRELATES && e.type != EdgeType.LEADS) " (${pct(e.testValue)} there)" else "")
        Status.HYPOTHESIS -> "only a hypothesis: not confirmed on the newest 30%"
    }

    /** One edge as a careful sentence: the rate, the base, the samples, the status. */
    fun say(e: Edge): String {
        val a = label(e.from); val b = label(e.to)
        return when (e.type) {
            EdgeType.CONSTITUENT_OF -> "$a is in $b (${status(e)})"
            EdgeType.IN_SECTOR -> "$a is in the $b sector (a static list as of ${Sectors.AS_OF})"
            EdgeType.PRECEDES -> {
                val whenSaid = if (e.lag == 0) "later the same session" else "within the next ${e.lag} sessions"
                "after ${a.lowercaseFirst()}, ${b.substringAfter(": ")} came $whenSaid in ${pct(e.weight)} of ${e.samples} cases, against ${pct(e.base)} usually (${num(e.lift, 1)}x) - ${status(e)}"
            }
            EdgeType.OCCURS_IN -> if (e.to.startsWith("TOD:"))
                "${pct(e.weight)} of ${a.substringBefore(":")}'s ${e.samples} zero-to-hero days reached the multiple between $b, ${num(e.lift, 1)}x that slot's share of the session - ${status(e)}"
            else "${a.substringAfter(": ")} came on ${pct(e.weight)} of ${context(e.to, b)} (${e.samples} of them) against ${pct(e.base)} overall (${num(e.lift, 1)}x) - ${status(e)}"
            EdgeType.CORRELATES -> "$a and $b's daily returns have a correlation of ${num(e.weight)} over ${e.samples} days" +
                (if (!e.testValue.isNaN()) ", ${num(e.testValue)} on the newest ${e.testSamples}" else "") + " - ${status(e)}"
            EdgeType.LEADS -> "$a's one-minute moves led $b's by ${e.lag} minute${if (e.lag == 1) "" else "s"} (mean daily correlation ${num(e.weight, 3)} against ${num(e.base, 3)} the other way, ${e.samples} days) - ${status(e)}"
            EdgeType.CONTRIBUTES -> "on $b's big days (${Rules.BIG_MOVE_PCT}% or more) $a moved the same way ${pct(e.weight)} of ${e.samples} times, against ${pct(e.base)} on all days" +
                (if (!e.extra.isNaN()) ", about ${num(e.extra, 1)}x the index's move" else "") + " - ${status(e)}"
        }
    }

    private fun String.lowercaseFirst() = if (isEmpty()) this else this[0].lowercase() + substring(1)
    private fun context(id: String, label: String) = when (node(id)?.type) {
        NodeType.DAY, NodeType.EXPIRY -> "${label}s"
        NodeType.REGIME -> "sessions with $label"
        else -> label
    }

    // ---- names --------------------------------------------------------------------------------------------------------------

    companion object {
        /** Common names of companies, as said (lower case, spaces) -> symbol. */
        val ALIASES: Map<String, String> = mapOf(
            "hdfc bank" to "HDFCBANK", "icici bank" to "ICICIBANK", "icici" to "ICICIBANK", "sbi" to "SBIN", "state bank" to "SBIN",
            "kotak" to "KOTAKBANK", "kotak bank" to "KOTAKBANK", "axis bank" to "AXISBANK", "axis" to "AXISBANK", "indusind" to "INDUSINDBK",
            "indusind bank" to "INDUSINDBK", "bank of baroda" to "BANKBARODA", "au bank" to "AUBANK", "federal bank" to "FEDERALBNK",
            "idfc first" to "IDFCFIRSTB", "canara bank" to "CANBK", "yes bank" to "YESBANK", "reliance" to "RELIANCE", "infosys" to "INFY",
            "tcs" to "TCS", "airtel" to "BHARTIARTL", "bharti airtel" to "BHARTIARTL", "larsen" to "LT", "l&t" to "LT", "bajaj finance" to "BAJFINANCE",
            "tata motors" to "TATAMOTORS", "tata steel" to "TATASTEEL", "maruti" to "MARUTI", "hindustan unilever" to "HINDUNILVR",
            "sun pharma" to "SUNPHARMA", "wipro" to "WIPRO", "hcl" to "HCLTECH", "tech mahindra" to "TECHM", "mahindra" to "M&M",
        )

        private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
            .replace(Regex("[^a-z0-9& -]"), " ").replace(Regex("\\s+"), " ").trim() + " "

        private val INDEX_WORDS: List<Pair<Regex, String>> = listOf(
            " (nifty next 50|nifty next fifty|niftynxt50|junior nifty) " to "NIFTYNXT50",
            " (midcap nifty|midcpnifty|midcap select|mid cap nifty) " to "MIDCPNIFTY",
            " (bank nifty|banknifty|nifty bank|bnf) " to "BANKNIFTY",
            " (fin nifty|finnifty|nifty fin) " to "FINNIFTY",
            " (bankex) " to "BANKEX", " (sensex) " to "SENSEX", " (india vix|vix) " to "INDIAVIX",
            " (nifty|nifty 50|nifty fifty) " to "NIFTY",
        ).map { (p, n) -> Regex(p) to n }

        /** Every node named in [text], in the order said: indices, companies, sectors. */
        fun names(text: String): List<String> {
            var t = norm(text)
            val found = ArrayList<Pair<Int, String>>()
            for ((rx, u) in INDEX_WORDS) {
                rx.find(t)?.let { m -> found += m.range.first to NeuroLearn.idx(u); t = t.replaceRange(m.range, " ".repeat(m.value.length)) }
            }
            for ((name, sym) in ALIASES.entries.sortedByDescending { it.key.length }) {
                val i = t.indexOf(" $name ")
                if (i >= 0) { found += i to NeuroLearn.stk(sym); t = t.replaceRange(i, i + name.length + 2, " ".repeat(name.length + 2)) }
            }
            for (sym in DhanUniverse.allConstituents()) {
                val w = " ${sym.lowercase(Locale.ENGLISH)} "
                val i = t.indexOf(w)
                if (i >= 0) found += i to NeuroLearn.stk(sym)
            }
            for (s in Sectors.SECTOR_OF.values.distinct()) {
                val i = t.indexOf(" ${s.lowercase(Locale.ENGLISH)} ")
                if (i >= 0) found += i to NeuroLearn.sec(s)
            }
            return found.sortedBy { it.first }.map { it.second }.distinct()
        }

        /** The event named in [text], or null. */
        fun event(text: String): Ev? {
            val t = norm(text).replace("-", " ")
            return when {
                Regex(" (zero to hero|0 to hero|zerotohero) ").containsMatchIn(t) -> Ev.ZERO_TO_HERO
                Regex(" (hero to zero|herotozero) ").containsMatchIn(t) -> Ev.HERO_TO_ZERO
                Regex(" gap ?up ").containsMatchIn(t) -> Ev.GAP_UP
                Regex(" gap ?down ").containsMatchIn(t) -> Ev.GAP_DOWN
                Regex(" (vix spike|vix (jumps|jumped|spikes|spiked)|vix up) ").containsMatchIn(t) -> Ev.VIX_SPIKE
                Regex(" (vix drop|vix (falls|fell|drops|dropped|crush)|vix down) ").containsMatchIn(t) -> Ev.VIX_DROP
                Regex(" (iv spike|iv (jumps|jumped|spikes)) ").containsMatchIn(t) -> Ev.IV_SPIKE
                Regex(" (oi build ?up|open interest build ?up|oi buildup) ").containsMatchIn(t) -> Ev.OI_BUILDUP
                Regex(" (oi unwind|oi unwinding|open interest unwind) ").containsMatchIn(t) -> Ev.OI_UNWIND
                Regex(" (straddle expansion|straddle expand|straddle expands) ").containsMatchIn(t) -> Ev.STRADDLE_EXPANSION
                Regex(" (big range|wide range|range day) ").containsMatchIn(t) -> Ev.BIG_RANGE
                Regex(" (big up|up day|big rally|strong up|big green) ").containsMatchIn(t) -> Ev.BIG_UP
                Regex(" (big down|down day|big fall|big red|sell ?off) ").containsMatchIn(t) -> Ev.BIG_DOWN
                else -> null
            }
        }
    }
}

/**
 * A proven PRECEDES or OCCURS_IN relation offered to the Strategy Lab as something to research ON PAPER: [text] says it
 * with its rate, base rate, samples and status. A hint arms nothing, trades nothing and changes no risk; it is only
 * where a paper backtest might start.
 */
data class ResearchHint(val text: String, val edge: Edge) {
    val paperOnly: Boolean get() = true
}

object ResearchHints {
    /** The proven sequences and timings in [g], strongest first. Hypotheses are never offered. */
    fun from(g: NeuroGraph, limit: Int = 10): List<ResearchHint> {
        val q = NeuroQuery(g)
        return g.edges.filter { it.proven && (it.type == EdgeType.PRECEDES || it.type == EdgeType.OCCURS_IN) }
            .sortedByDescending { it.score() }.take(limit)
            .map { e -> ResearchHint(q.say(e).replaceFirstChar { it.uppercase() } + ". Worth a paper backtest only; it arms nothing.", e) }
    }
}
