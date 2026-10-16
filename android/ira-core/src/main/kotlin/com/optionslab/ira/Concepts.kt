package com.optionslab.ira

import com.optionslab.engine.strategy.Json

/**
 * Jarvis's concept book (15 Oct 2026; Boss: Solo should "understand the concepts" - OI, gamma, traps, fake breakouts, order
 * flow ...). The app's asset [ASSET] is built by research/hunt/r61/app_concepts.py from R61's and R61b's concept notes and
 * R62b's additions: 40 concepts, each with its plain meaning, how the app measures it, where its signal comes from (the house
 * rule: signals on the underlying index; option-chain data is context only) and the honest research verdict with sample
 * sizes. No concept passed the research gates on its own; Solo 2.0 passed as a whole and stays on paper.
 *
 * Read-only words: nothing here trades, and nothing here is about Boss's account (fine on a locked phone). Pure: the app reads
 * the file once off the main thread and keeps the [Book] in memory ([com.optionslab.app.ira.ConceptLibrary]).
 */
object Concepts {
    const val ASSET = "concepts/concepts.json"

    data class Evidence(val study: String, val sample: String, val finding: String, val verdict: String)

    data class Concept(
        val id: String, val name: String, val aka: List<String>, val what: String, val use: String, val measure: String,
        val phone: String, val source: String, val sourceText: String, val role: String, val verdict: String,
        val pitfalls: List<String>, val evidence: List<Evidence>, val latest: String,
    )

    data class Book(val asOf: String, val houseRule: String, val summary: String, val concepts: List<Concept>) {
        fun byId(id: String): Concept? = concepts.firstOrNull { it.id == id }
        /** Every naming phrase, longest first, and their words (made once per book: questions are matched on the main thread). */
        internal val table: List<Pair<String, Concept>> by lazy { concepts.flatMap { c -> phrases(c).map { it to c } }.sortedByDescending { it.first.length } }
        internal val vocab: Set<String> by lazy { table.flatMap { it.first.split(' ') }.filter { it.length >= 4 }.toSet() }
        companion object { val EMPTY = Book("", "", "", emptyList()) }
    }

    /** The book from the asset's [text]; an empty, broken or unknown file gives [Book.EMPTY], and a broken entry is left out. */
    fun parse(text: String): Book {
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return Book.EMPTY
        if ((o["schema"] as? Number)?.toInt() != 1) return Book.EMPTY
        fun Map<*, *>.s(k: String) = this[k] as? String
        fun Map<*, *>.strs(k: String) = (this[k] as? List<*>).orEmpty().filterIsInstance<String>()
        val list = (o["concepts"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = m.s("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = m.s("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val what = m.s("what")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Concept(id, name, m.strs("aka"), what, m.s("use").orEmpty(), m.s("measure").orEmpty(), m.s("phone").orEmpty(),
                m.s("source").orEmpty(), m.s("sourceText").orEmpty(), m.s("role").orEmpty(), m.s("verdict").orEmpty(), m.strs("pitfalls"),
                (m["evidence"] as? List<*>).orEmpty().mapNotNull { r ->
                    (r as? Map<*, *>)?.let { Evidence(it.s("study").orEmpty(), it.s("sample").orEmpty(), it.s("finding").orEmpty(), it.s("verdict").orEmpty()) }
                },
                m.s("latest").orEmpty())
        }
        return Book(o.s("asOf").orEmpty(), o.s("houseRule").orEmpty(), o.s("summary").orEmpty(), list)
    }

    // ---- finding the concept asked about ---------------------------------------------------------------------------------

    /** Other ways Boss says them (Hinglish and common short forms), by concept id. */
    private val EXTRA = mapOf(
        "fake_breakout" to listOf("fake breakout", "fakeout", "fake out", "false breakout", "fake break out", "nakli breakout", "jhootha breakout",
            "jhoota breakout", "jhutha breakout"),
        "traps" to listOf("trap", "traps", "bull trap", "bear trap", "trap trade"),
        "stop_hunt" to listOf("stop hunt", "stop hunting", "stoploss hunting", "stop loss hunting", "sl hunting", "sl hunt", "liquidity sweep"),
        "delta_cvd" to listOf("order flow", "orderflow", "cumulative delta", "volume delta"),
        "oi" to listOf("open interest", "oi change", "change in oi"),
        "gex" to listOf("gamma exposure", "gamma flip", "gex"),
        "vix" to listOf("india vix", "vix"),
        "iv" to listOf("implied volatility", "iv"),
        "max_pain" to listOf("max pain", "maxpain"),
        "oi_walls" to listOf("oi wall", "oi walls", "call wall", "put wall"),
        "order_blocks" to listOf("order block", "order blocks"),
        "fvg" to listOf("fair value gap", "fair value gaps"),
        "bos_choch" to listOf("break of structure", "change of character", "choch", "bos"),
        "msb" to listOf("market structure break", "msb"),
        "zigzag" to listOf("zig zag", "zigzag"),
        "atr" to listOf("average true range", "atr"),
        "vwap" to listOf("vwap"),
        "theta" to listOf("theta", "time decay"),
        "pcr" to listOf("put call ratio"),
        "iv_rank" to listOf("iv rank", "iv percentile"),
        "volume_profile" to listOf("volume profile"),
        "value_area" to listOf("value area", "market profile"),
        "participants" to listOf("participant wise oi", "fii dii flows"),
        "basis" to listOf("futures basis"),
        "events" to listOf("event days", "event day"),
        "news" to listOf("news tone"),
        "orb" to listOf("opening range"),
        "ofi" to listOf("order flow imbalance"),
        "depth_imbalance" to listOf("depth imbalance", "order book imbalance"),
        "dealers" to listOf("dealer positioning"),
        "regime" to listOf("trend vs range", "regimes"),
        "absorption" to listOf("absorption"),
    )

    /** Question words never "corrected" into a concept's word ("tell" is not "sell"). */
    private val ASKING = setOf("tell", "what", "whats", "does", "mean", "means", "explain", "about", "define", "matlab", "kya", "hota", "hoti",
        "hain", "samjhao", "jarvis", "boss", "please", "concept", "meaning")

    /** Lower case, letters and digits only, single-spaced, with a space at each end (so " word " finds whole words). */
    internal fun plain(s: String): String = " " + s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    /**
     * Every phrase that names [c]: its full name, the name without its brackets, a one-word short form in brackets ("OI",
     * "GEX"), its other names and [EXTRA]. Never a fragment of a name: "high", "term" or "premium" alone would take other
     * questions ("what does high pcr mean", review 15 Oct).
     */
    internal fun phrases(c: Concept): List<String> {
        // A short form is one capitalised word in brackets ("OI", "GEX", "ChoCH"), never "(failed)" in "Fake (failed) breakout".
        val short = Regex("\\(([A-Z][A-Za-z]*)\\)").findAll(c.name).map { plain(it.groupValues[1]).trim() }.toList()
        val base = c.name.replace(Regex("\\([^)]*\\)"), " ")
        return (listOf(c.name, base) + short + c.aka + EXTRA[c.id].orEmpty())
            .map { plain(it).trim() }.filter { it.length >= 2 }.distinct()
    }

    /** The concept [text] names (the longest phrase wins: "gamma exposure" before "gamma"), or null. A near miss is read as meant. */
    fun find(book: Book, text: String): Concept? {
        if (book.concepts.isEmpty()) return null
        // Typing on a phone ("what is a fake brekout", "gamma exposre"): a word one letter off a concept's word is read as it
        // (before matching, so "gamma exposre" is gamma exposure, not gamma). Words already known are never touched.
        val vocab = book.vocab
        val fixed = plain(text).trim().split(' ').joinToString(" ") { w ->
            if (w.length < 4 || w in vocab || w in ASKING) w
            else vocab.filter { kotlin.math.abs(it.length - w.length) <= 1 && Spelling.distance(w, it) <= (if (it.length >= 8) 2 else 1) }
                .singleOrNull() ?: w
        }
        return exact(book, " $fixed ")
    }

    private fun exact(book: Book, t: String): Concept? =
        book.table.firstOrNull { (p, _) -> t.contains(" $p ") }?.second

    // ---- is a concept's meaning asked? -----------------------------------------------------------------------------------

    /** A meaning asked in so many words: only these override "now", a number, an index named or a figure (as [Glossary]'s strict). */
    private val STRICT = Regex(" (mean|means|meaning|define|definition|what do you mean by|in simple words) ")
    /** Asking about a concept, but "explain the vix today" or "tell me about the news" may still want today's figure. */
    private val ASKING_ABOUT = Regex(" (explain|concept|tell me about|samjhao|samjha do|samjhaiye) ")
    private val WHATIS = Regex("^ (what is|what s|whats|what are|what does|what do|how does|how do) ")

    /**
     * Concepts that are also a figure Jarvis reads now ("what is the pcr", "what's the vix", "what's the news", "what's the
     * opening range"): their meaning only when it is asked outright ("what does PCR mean", "PCR ka matlab"), as [Glossary] does.
     */
    private val FIGURES = setOf("oi", "buildup", "pcr", "max_pain", "oi_walls", "iv", "iv_rank", "skew", "term", "vix", "vwap", "atr", "orb",
        "value_area", "volume_profile", "participants", "basis", "rollover", "events", "news", "regime",
        // (Review, 15 Oct: "what is the market structure", "what's the cvd", "what are the swings" want today's reading.)
        "zigzag", "bos_choch", "delta_cvd")

    /**
     * Concepts whose word [Glossary] already explains ("what is theta", "what is a call wall", "what's the opening range"): the
     * glossary keeps its answer and [researchNote] adds our research to it. Asked before the glossary, the concept book takes
     * only the longer names it does not know ("gamma exposure", not the glossary's "gamma").
     */
    private val GLOSSARY_WORDS = setOf("oi", "buildup", "pcr", "max_pain", "oi_walls", "iv", "vix", "delta", "gamma", "theta", "vega", "orb",
        "participants")

    /** The words asked about and whether the meaning was asked outright, or null (not a meaning question). */
    private fun read(said: String): Pair<String, Boolean>? {
        val low = spacedWords(said.lowercase())
        val own = rx("\\b(mera|meri|mere|apna|apni|apne|hamara|hamari)\\b").containsMatchIn(low)
        val text = (if (own) null else Glossary.HINDI_MEANING.firstOrNull { it.first.matches(low) }?.let { (r, to) -> r.replace(low, to) }) ?: said
        val t = plain(text).replace(Regex("^ (jarvis|hey jarvis|ok jarvis|boss) "), " ")
        val meaning = STRICT.containsMatchIn(t)
        if (!meaning && !ASKING_ABOUT.containsMatchIn(t) && !WHATIS.containsMatchIn(t)) return null
        if (rx(" (my|our|mine) ").containsMatchIn(t) || own) return null
        val named = Market.mentioned(text).any { it != Market.VIX }
        if (named && !meaning) return null
        if (!meaning && (rx(" (today|now|current|currently|next|this|on|for|at|of|level|levels|saying|doing|telling|showing|looking) ").containsMatchIn(t) || rx("\\d").containsMatchIn(t))) return null
        return t.trim() to meaning
    }

    /**
     * The words to look the concept up in, when [said] asks what a concept is ("what is gamma exposure", "explain the bull
     * trap", "fake breakout kya hai"), else null. As [Glossary]: a figure wanted now ("what is the Nifty PCR today", "what is
     * my delta") is not a meaning asked. ("Is this a bull trap?" stays the market's own read of the chart, [Ask]'s.)
     */
    fun asked(said: String): String? = read(said)?.first

    /** Jarvis's answer to [said] from [book], or null (not a concept's meaning asked, no concept named, a figure wanted, or the glossary's word). */
    fun answer(book: Book, said: String): String? {
        val (words, meaning) = read(said) ?: return null
        val c = find(book, words) ?: return null
        if (c.id in FIGURES && !meaning || c.id in GLOSSARY_WORDS) return null
        return say(c)
    }

    /** "Our research: ..." for the concept [said] names, or null: added under the glossary's own meaning of the same word. */
    fun researchNote(book: Book, said: String): String? =
        if (JarvisTrades.rulesAsked(said)) null // the glossary's answer there is Jarvis's own trade rules, not a word
        else find(book, said)?.verdict?.takeIf { it.isNotBlank() }?.let { "Our research: ${it.trimEnd('.')}." }

    /** One concept in a few sentences: what it is, where its signal comes from, and what our research found. */
    fun say(c: Concept): String = buildString {
        append(c.name).append(": ").append(c.what.trimEnd('.')).append(". ")
        if (c.sourceText.isNotBlank()) append("Signal source: ").append(c.sourceText.trimEnd('.')).append(". ")
        if (c.verdict.isNotBlank()) append("Our research: ").append(c.verdict.trimEnd('.')).append(".")
    }.trim()

    /** The concept's dialog: meaning, use, how the app measures it, the source, the verdict, pitfalls and each study with its sample. */
    fun lines(c: Concept): List<String> = buildList {
        add(c.what)
        if (c.use.isNotBlank()) add("How traders use it: ${c.use}")
        if (c.measure.isNotBlank()) add("How it is measured: ${c.measure}")
        if (c.phone.isNotBlank()) add("On the phone: ${c.phone}.")
        if (c.sourceText.isNotBlank()) add("Signal source: ${c.sourceText}")
        if (c.role.isNotBlank()) add("Role in Solo: ${c.role}.")
        if (c.verdict.isNotBlank()) add("Our research: ${c.verdict}")
        c.pitfalls.forEach { add("Pitfall: $it") }
        c.evidence.forEach { e ->
            val head = e.study + (if (e.sample.isNotBlank()) " (${e.sample})" else "")
            val body = listOfNotNull(e.finding.takeIf { it.isNotBlank() }, e.verdict.takeIf { it.isNotBlank() }?.let { "Verdict: $it." }).joinToString(" ")
            add(if (body.isEmpty()) head else "$head: $body")
        }
        if (c.latest.isNotBlank()) add("Latest test: ${c.latest}")
    }
}
