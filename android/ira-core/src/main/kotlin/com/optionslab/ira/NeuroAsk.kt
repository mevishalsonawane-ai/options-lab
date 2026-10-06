package com.optionslab.ira

import com.optionslab.ira.neuro.EdgeType
import com.optionslab.ira.neuro.Ev
import com.optionslab.ira.neuro.NeuroGraph
import com.optionslab.ira.neuro.NeuroLearn
import com.optionslab.ira.neuro.NeuroQuery
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/**
 * Jarvis asked about the NeuroGraph - what he has learned from the Dhan market data kept on this phone (built by itself
 * after each download or data-pack import): "what does the graph say happens after a gap up in Nifty?", "according to
 * the graph what moves BankNifty most?", "when do zero to hero moves happen per the graph?", "how related are HDFC Bank
 * and BankNifty in the graph?", "what's proven in the graph?", "graph stats", "what have you learned from the market
 * data?", "graph se batao", "data se kya seekha", "graph mein kya pakka hai".
 *
 * The graph must be NAMED (graph, neurograph, knowledge graph) or learning from the data asked, so the market questions
 * other readers answer (the gap record, the day after, the leading company) keep theirs. Every answer gives rates against
 * base rates with their sample counts, and says what is proven out of sample and what is only a hypothesis - never a
 * forecast, never advice; nothing acts. Pure: the app hands it the graph.
 */
object NeuroAsk {
    enum class Kind { OVERVIEW, STATS, PROVEN, AFTER, MOVERS, ZERO_TO_HERO, RELATED, ABOUT }

    /** What was asked: the kind, the index named (app name, null when none), the nodes named and the event named. */
    data class Q(val kind: Kind, val index: String?, val names: List<String>, val event: Ev?)

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9& -]"), " ").replace("-", " ").replace(rx("\\s+"), " ").trim() + " "

    /** The graph named. A bare "graph" only in a phrase that asks it (not a chart: "show me the nifty graph" is not this). */
    private val GRAPH = Regex(" (neuro ?graph|knowledge graph|graph se|graph mein|graph me|graph main|graph ke hisaab se|graph ke hisab se|" +
        "according to (the |your )?graph|per (the |your )?graph|(the|your) graph (say|says|said|show|shows|know|knows|think|thinks)|" +
        "in (the|your) graph|from (the|your) graph|graph stats|graph statistics|graph status|graph kitna|graph kaisa|graph ready|graph built|" +
        "graph bana|graph banaya|graph bataye|graph batao|is your graph|is the graph|how big is (the |your )?graph|(the|your) learned graph|graph of what you learned) ")
    /** Learning from the market data asked (not what Jarvis learned about Boss). */
    private val LEARNED = Regex(" (what (have you|did you|you have|youve) (learned|learnt|learn) from (the |your )?(market |dhan |downloaded |stored |imported |historical )?(data|candles|history)|" +
        "(market |dhan |downloaded )?data se (kya|kya kya) (seekha|sikha|seekhe|sikhe|samjha)|data (mein|me) se (kya|kya kya) (seekha|sikha)|" +
        "what did the data teach you|what has the data taught you) ")
    /** Not this: a chart to show, a change asked, Boss's own book. */
    private val NOT = Regex(" (chart|candle chart|dikhao|show me the (nifty|banknifty|sensex|price) graph|plot|draw|open the|delete|remove|wipe|erase|hatao|" +
        "my (p&l|pnl|position|positions|trades|portfolio|book)|mera (p&l|pnl)|about me|mere baare) ")

    private val STATS = Regex(" (stats|statistics|status|how big|kitna bada|size|how many (nodes|edges|links)|when was .*built|last built|kab bana|ready|built|bana kya|bana hai) ")
    private val PROVEN = Regex(" (proven|prove|hypothesis|hypotheses|pakka|pakki|confirmed|sabit|sure about|guess) ")
    private val ZTH = Regex(" (zero to hero|0 to hero|zerotohero) ")
    private val AFTER = Regex(" ((happens|happen|comes|come|follows|follow|usually happens) after|(usually )?(follows|follow)|after (a|an|the)|ke baad|baad (mein|me) kya) ")
    private val MOVERS = Regex(" ((what|who|which (stock|stocks|company|companies|bank|banks)) (moves|move|drives|drive|influences|leads|lead)|moves .* most|" +
        "hilata|hilate|chalata|chalate|drives|influence|influences|kaun (hilata|chalata|move karta)|sabse zyada (kaun|kya)|biggest (driver|influence)) ")
    private val RELATED = Regex(" (how related|related are|relation between|relationship between|connected|connection between|correlat|rishta|link between|linked) ")
    private val ABOUT = Regex(" (know about|about|ke baare|ke bare|tell me about|batao) ")

    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        val graph = GRAPH.containsMatchIn(t)
        val learned = LEARNED.containsMatchIn(t)
        if (!graph && !learned) return null
        val names = NeuroQuery.names(text)
        val index = names.firstOrNull { it.startsWith("IDX:") }?.removePrefix("IDX:")
        val event = NeuroQuery.event(text)
        val kind = when {
            ZTH.containsMatchIn(t) && !AFTER.containsMatchIn(t) -> Kind.ZERO_TO_HERO
            event != null && AFTER.containsMatchIn(t) -> Kind.AFTER
            RELATED.containsMatchIn(t) && names.size >= 2 -> Kind.RELATED
            MOVERS.containsMatchIn(t) && index != null -> Kind.MOVERS
            PROVEN.containsMatchIn(t) -> Kind.PROVEN
            STATS.containsMatchIn(t) -> Kind.STATS
            names.isNotEmpty() && (ABOUT.containsMatchIn(t) || graph) -> Kind.ABOUT
            else -> Kind.OVERVIEW
        }
        return Q(kind, index, names, event)
    }

    // ---- the answers ----------------------------------------------------------------------------------------------------

    const val NONE = "I haven't built the NeuroGraph yet, Boss. It builds by itself after a Dhan download or a data-pack import " +
        "(More, Dhan data, Rebuild graph), from the market data kept on this phone."
    const val GOLD = "IraGoldAlgo keeps no Dhan data, Boss, so it has no NeuroGraph."
    private const val CAVEAT = "Patterns in past data, Boss, not forecasts."

    private fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).let {
        "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.year}"
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** The answer to [q] from [graph] (null: none built yet). Reads only. */
    fun answer(q: Q, graph: NeuroGraph?): String {
        if (graph == null) return NONE
        val n = NeuroQuery(graph)
        return when (q.kind) {
            Kind.STATS -> stats(graph)
            Kind.OVERVIEW -> overview(graph, n)
            Kind.PROVEN -> proven(graph, n)
            Kind.AFTER -> after(n, q.index ?: "NIFTY", q.event ?: Ev.GAP_UP)
            Kind.MOVERS -> movers(n, q.index ?: "NIFTY")
            Kind.ZERO_TO_HERO -> zeroToHero(n, q.index)
            Kind.RELATED -> related(n, q.names[0], q.names[1])
            Kind.ABOUT -> about(n, q.names.first())
        }
    }

    fun stats(g: NeuroGraph): String {
        val s = g.summary()
        val span = if (s.from != null && s.to != null) " from ${s.days} sessions (${s.from} to ${s.to})" else ""
        val types = s.byType.entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase().replace('_', ' ')}" }
        return "The NeuroGraph has ${s.nodes} nodes and ${s.edges} links$span, last built ${date(s.builtAt)}: ${s.proven} proven out of sample, " +
            "${s.hypotheses} still hypotheses, ${s.structural} from the index and sector lists. By kind: $types."
    }

    private fun overview(g: NeuroGraph, n: NeuroQuery): String {
        val top = n.proven(3)
        val head = "From the Dhan data on this phone I keep a graph of ${g.nodes.size} nodes and ${g.edges.size} links (${g.provenCount} proven, ${g.hypothesisCount} hypotheses)."
        if (top.isEmpty()) return "$head Nothing has held out of sample yet, so I treat all of it as hypotheses. Ask me \"graph stats\" or \"what moves BankNifty most per the graph\"."
        return "$head The strongest proven: " + top.joinToString("; ") { n.say(it) } + ". $CAVEAT"
    }

    private fun proven(g: NeuroGraph, n: NeuroQuery): String {
        val p = n.proven(3); val h = n.hypotheses(2)
        val a = if (p.isEmpty()) "Nothing is proven yet: no learned link has held on the newest 30% of the data."
            else "${g.provenCount} links are proven (they held on the newest 30% of the data), for example: " + p.joinToString("; ") { n.say(it) } + "."
        val b = if (h.isEmpty()) "" else " ${g.hypothesisCount} are only hypotheses, like: " + h.joinToString("; ") { n.say(it) } + "; I don't treat those as facts."
        return "$a$b $CAVEAT"
    }

    private fun after(n: NeuroQuery, u: String, e: Ev): String {
        val es = n.after(u, e, 3)
        val who = NeuroLearn.label(u)
        if (es.isEmpty()) {
            val node = n.node(NeuroLearn.ev(if (e.vix) "INDIAVIX" else u, e))
            return if (node == null) "The graph has no ${e.short} for $who in the Dhan data yet, Boss."
            else "In the graph, ${e.short} came on ${node.count} of $who's sessions, and nothing followed it noticeably more often than usual. $CAVEAT"
        }
        return "In the graph, " + es.joinToString("; ") { n.say(it) } + ". $CAVEAT"
    }

    private fun movers(n: NeuroQuery, u: String): String {
        val es = n.movers(u, 4)
        val who = NeuroLearn.label(u)
        if (es.isEmpty()) return "The graph has learned nothing yet about what moves $who, Boss: it needs its companies' candles from Dhan."
        return "What moves with $who in the graph: " + es.joinToString("; ") { n.say(it) } +
            ". It shows which companies moved with it, not how much each one weighs in the index. $CAVEAT"
    }

    private fun zeroToHero(n: NeuroQuery, u: String?): String {
        val es = n.zeroToHero(u, 4)
        val where = u?.let { NeuroLearn.label(it) + "'s " } ?: ""
        if (es.isEmpty()) return "The graph has no clear pattern for when ${where}zero-to-hero moves happen yet, Boss (an option reaching 25 times its intraday low): too few of them in the stored options, or none held out of sample."
        return "When ${where}zero-to-hero moves happen (an option reaching 25 times its intraday low): " + es.joinToString("; ") { n.say(it) } + ". $CAVEAT"
    }

    private fun related(n: NeuroQuery, a: String, b: String): String {
        val es = n.related(a, b)
        val la = n.label(a); val lb = n.label(b)
        if (es.isEmpty()) return "The graph has no link between $la and $lb, Boss: nothing it measured connects them."
        return cap(es.take(4).joinToString("; ") { n.say(it) }) + ". $CAVEAT"
    }

    private fun about(n: NeuroQuery, id: String): String {
        val es = n.neighbours(id, 4)
        val name = n.label(id)
        if (n.node(id) == null || es.isEmpty()) return "The graph knows nothing about $name yet, Boss."
        val learned = es.filter { it.learned }
        val say = (if (learned.isEmpty()) es.take(3) else learned).joinToString("; ") { n.say(it) }
        return "What the graph has on $name: ${cap(say)}. $CAVEAT"
    }

    /** Edge types named for the screen, in words. */
    fun typeLabel(t: EdgeType): String = t.name.lowercase().replace('_', ' ')
}
