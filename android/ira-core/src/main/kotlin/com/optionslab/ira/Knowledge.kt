package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Jarvis's knowledge base (research/data/ARCHITECTURE.md, L5 Level 4): the research's findings, strategy cards, study pages
 * and the h42 answers, built by research/knowledge/build_pack.py into one sourced pack (assets/knowledge/pack.json.gz), and
 * a retrieval over it - BM25 on the words, a boost for the instruments and topics the question names, and a small
 * English/Hinglish synonym map. No network, no model: the answer is made of the retrieved facts' own sentences, so every
 * number said comes from a fact ([say], [lines]); the on-device model may only reword it under the usual number check.
 *
 * Market knowledge, not Boss's account: fine on a locked phone, except the few facts the pack marks [Fact.personal] (a study
 * that quotes his own fills or paper days), which a locked phone never gets ([search] with `locked`). Nothing here acts.
 */
object Knowledge {
    data class Fact(
        val id: String, val kind: String, val title: String, val text: String, val tags: List<String>, val inst: List<String>,
        val src: String, val sec: String, val date: String, val verdict: String, val conf: String, val weight: Double,
        val personal: Boolean,
    ) {
        /** The source doc's file name ("HUNT_H42.md"). */
        val doc: String get() = src.substringAfterLast('/')
    }

    class Pack(val schema: Int, val version: String, val builtFrom: String, val facts: List<Fact>)

    data class Hit(val fact: Fact, val score: Double)

    const val SCHEMA = 1

    // ---- the pack --------------------------------------------------------------------------------------------------------

    /** The pack from its JSON text; throws on a malformed pack or a schema this build does not read. */
    fun parse(json: String): Pack {
        val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("pack: not an object")
        val schema = (root["schema"] as? Number)?.toInt() ?: throw IllegalArgumentException("pack: no schema")
        require(schema == SCHEMA) { "pack: schema $schema, this build reads $SCHEMA" }
        val facts = (root["facts"] as? List<*>).orEmpty().mapNotNull { f -> (f as? Map<*, *>)?.let(::fact) }
        return Pack(schema, root["version"] as? String ?: "", root["built_from"] as? String ?: "", facts)
    }

    /** The pack from its gzip bytes (the asset as shipped). */
    fun read(gz: InputStream): Pack = parse(GZIPInputStream(gz).bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun fact(m: Map<*, *>): Fact? {
        val id = m["id"] as? String ?: return null
        val text = m["text"] as? String ?: return null
        fun s(k: String) = m[k] as? String ?: ""
        fun l(k: String) = (m[k] as? List<*>).orEmpty().filterIsInstance<String>()
        return Fact(id, s("kind"), s("title"), text, l("tags"), l("inst"), s("src"), s("sec"), s("date"), s("verdict").ifEmpty { "context" },
            s("conf").ifEmpty { "medium" }, (m["w"] as? Number)?.toDouble() ?: 1.0, m["personal"] == true)
    }

    // ---- words -----------------------------------------------------------------------------------------------------------

    private val STOP = (
        "a an the of to in on at for by with from as is are was were be been being am do does did done has have had it its this that " +
        "these those there here what which who whom how why when where if then than so and or but not no yes can could would should will " +
        "shall may might must about into over under after before during between up down out off very just also any some all each more most " +
        "me my i you your we our us he his she her they them their boss jarvis please tell say said says know show find found " +
        "research researched study studies studied findings finding evidence backtest backtests backtested historically history data hunt " +
        "kya hai hain ka ki ke ko se me mein aur ya bhi tha thi the ho hota hoti hote kaise kyu kyun kab kahan batao bata bolo kehta kehti " +
        "kaha mujhe humne hum ne par pe wala wali wale raha rahi rahe kuch koi"
        ).split(' ').toSet()

    /**
     * Phrases read as one word before splitting (both the facts and the question), so "bank nifty", "nifty bank" and "BN" are
     * one index, and "gap down" / "gap-down" one event.
     */
    private val PHRASES = listOf(
        " bank nifty " to " banknifty ", " nifty bank " to " banknifty ", " bn " to " banknifty ", " fin nifty " to " finnifty ",
        " midcap nifty " to " midcpnifty ", " midcp " to " midcpnifty ", " india vix " to " vix ", " xauusd " to " gold ",
        " natural gas " to " naturalgas ", " nat gas " to " naturalgas ", " natgas " to " naturalgas ",
        " gap down " to " gapdown ", " gaps down " to " gapdown ", " gapped down " to " gapdown ", " gap downs " to " gapdown ",
        " gap up " to " gapup ", " gaps up " to " gapup ", " gapped up " to " gapup ", " gap ups " to " gapup ",
        " opening range breakout " to " orb ", " opening range " to " orb ", " range fade " to " rangefade ",
        " liquidity 15 5 " to " liquidity ", " 15 5 " to " liquidity ", " expiry day " to " expiry ", " expiry days " to " expiry ",
        " majority rule " to " majorityrule ", " mr 14 " to " majorityrule ", " mr14 " to " majorityrule ",
        " order flow " to " orderflow ", " open interest " to " oi ", " put call ratio " to " pcr ", " trade manager " to " trademanager ",
        " short straddle " to " shortstraddle ", " option buying " to " optionbuying ", " option selling " to " optionselling ",
        " buying options " to " optionbuying ", " selling options " to " optionselling ", " big candle " to " bigcandle ",
        " big candles " to " bigcandle ", " super trend " to " supertrend ", " profit lock " to " profitlock ",
        " time decay " to " theta ", " machine learning " to " ml ", " neural net " to " ml ", " neural network " to " ml ",
    )

    /** Question words that bring others in (weighted lower): English synonyms and Hinglish / Hindi in Latin script. */
    private val SYN: Map<String, List<String>> = mapOf(
        "profitable" to listOf("profit", "positive", "net", "edge", "pay", "make"), "profit" to listOf("profitable", "positive", "net", "edge"),
        "work" to listOf("edge", "profitable", "pay", "positive"), "pay" to listOf("profitable", "edge", "net"),
        "money" to listOf("profit", "net", "profitable"), "lose" to listOf("loss", "negative", "lost"), "loss" to listOf("lose", "negative"),
        "behave" to listOf("move", "day", "close", "first"), "react" to listOf("move", "after"), "move" to listOf("range", "up"),
        "volatility" to listOf("vix", "iv", "range"), "vix" to listOf("volatility"), "theta" to listOf("decay"), "decay" to listOf("theta"),
        "straddle" to listOf("strangle"), "gex" to listOf("gamma"), "gamma" to listOf("gex"), "ml" to listOf("model", "learned"),
        "ai" to listOf("model", "ml"), "model" to listOf("ml"), "stoploss" to listOf("stop"), "sl" to listOf("stop"), "tgt" to listOf("target"),
        "gapdown" to listOf("gap"), "gapup" to listOf("gap"), "expiry" to listOf("expir"), "weekly" to listOf("week"),
        "orb" to listOf("rangefade"), "sell" to listOf("selling", "short"), "buy" to listOf("buying"),
        "optionbuying" to listOf("buying", "buy", "option"), "optionselling" to listOf("selling", "sell", "short"), "overnight" to listOf("night", "close"),
        // Hinglish / Hindi in Latin script.
        "girna" to listOf("down", "fall"), "gira" to listOf("down", "fall"), "girta" to listOf("down", "fall"), "girawat" to listOf("down", "fall"),
        "neeche" to listOf("down", "below"), "niche" to listOf("down", "below"), "upar" to listOf("up", "above"), "tezi" to listOf("up", "rise"),
        "chadha" to listOf("up", "rise"), "munafa" to listOf("profit", "profitable"), "fayda" to listOf("profit", "profitable"),
        "faida" to listOf("profit", "profitable"), "kamai" to listOf("profit", "net"), "nuksan" to listOf("loss", "lose"), "nuksaan" to listOf("loss", "lose"),
        "ghata" to listOf("loss", "lose"), "khabar" to listOf("news"), "samachar" to listOf("news"), "din" to listOf("day"), "hafta" to listOf("week"),
        "mahina" to listOf("month"), "subah" to listOf("morning", "open"), "shaam" to listOf("evening"), "khareed" to listOf("buy", "buying"),
        "kharidna" to listOf("buy", "buying"), "bechna" to listOf("sell", "selling"), "chalta" to listOf("work", "edge"), "kaam" to listOf("work", "edge"),
        "palat" to listOf("reversal", "revers"), "ulta" to listOf("reversal", "revers"), "keemat" to listOf("price"), "bhav" to listOf("price"),
        "samay" to listOf("time"), "waqt" to listOf("time"), "khulna" to listOf("open"), "band" to listOf("close"), "baad" to listOf("after"),
        "pehle" to listOf("before"),
    )

    private val INST: List<Pair<String, Regex>> = listOf(
        "BANKNIFTY" to Regex(" banknifty "), "FINNIFTY" to Regex(" finnifty "), "MIDCPNIFTY" to Regex(" midcpnifty "),
        "NIFTY" to Regex(" nifty( 50)? "), "SENSEX" to Regex(" sensex "), "INDIAVIX" to Regex(" vix "), "GOLD" to Regex(" goldm? "),
        "SILVER" to Regex(" silverm? "), "CRUDE" to Regex(" crude(oil)? "), "NATURALGAS" to Regex(" naturalgas "),
        "CRYPTO" to Regex(" (crypto|bitcoin|btc) "),
    )

    /** Question words that name a pack tag (the tag gets a boost when a fact carries it). */
    private val TAG_WORDS: Map<String, String> = mapOf(
        "orb" to "orb", "rangefade" to "orb", "liquidity" to "liquidity", "expiry" to "expiry", "gap" to "gap", "gapdown" to "gap", "gapup" to "gap",
        "vix" to "vix", "straddle" to "straddle", "strangle" to "straddle", "shortstraddle" to "option-selling", "optionselling" to "option-selling",
        "selling" to "option-selling", "optionbuying" to "option-buying", "candle" to "candles", "bigcandle" to "candles", "volume" to "volume",
        "oi" to "oi", "pcr" to "oi", "gex" to "gex", "gamma" to "gex", "swing" to "swing", "overnight" to "overnight", "scalp" to "scalp",
        "trend" to "trend", "supertrend" to "supertrend", "ema" to "moving-average", "vwap" to "vwap", "rsi" to "rsi", "ml" to "ml",
        "model" to "ml", "cost" to "costs", "charge" to "costs", "spread" to "costs", "slippage" to "costs", "stop" to "stop", "target" to "target",
        "profitlock" to "profit-lock", "trail" to "profit-lock", "majorityrule" to "majority-rule", "gann" to "gann-fib", "fibonacci" to "gann-fib",
        "orderflow" to "order-flow", "reentry" to "reentry", "filter" to "filter", "news" to "news", "fii" to "fii", "breadth" to "breadth",
        "theta" to "theta", "reversal" to "reversal", "momentum" to "momentum", "trademanager" to "trade-manager", "lot" to "sizing",
        "pine" to "pine", "mcx" to "mcx", "random" to "random-baseline",
    )

    /** A light stem: plural, -ing, -ed and -ly off (both sides get the same, so only consistency matters). */
    internal fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("ly") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") && !w.endsWith("ses") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** [s] lowercased, spaced, with the [PHRASES] joined (" ... " around it). */
    internal fun normal(s: String): String {
        var t = " " + spacedWords(s.lowercase().replace("'", "").replace("’", "").replace("₹", " rs ")) + " "
        for ((a, b) in PHRASES) if (t.contains(a)) t = t.replace(a, b).replace(a, b)
        return t
    }

    /** The stemmed index words of [s] (stop words and single letters out; numbers kept: "15", "2024"). */
    internal fun tokens(s: String): List<String> = normal(s).trim().split(' ').filter { it.length > 1 && it !in STOP }.map(::stem)

    /** The instruments [question] names (BANKNIFTY never counted as NIFTY too). */
    fun instruments(question: String): Set<String> {
        val t = normal(question)
        return INST.filter { (_, rx) -> rx.containsMatchIn(t) }.map { it.first }.toSet()
    }

    // ---- the index -------------------------------------------------------------------------------------------------------

    /** A BM25 index over a pack's facts (text, title and section; built once, then read only - safe across threads). */
    class Index(val pack: Pack) {
        /** Word -> (fact index << 16 | count), one primitive array per word (no boxing: about 400k postings). */
        internal val postings: Map<String, LongArray>
        internal val length = IntArray(pack.facts.size)
        /** Each fact's title words (a question naming the strategy or study gets it first). */
        internal val titles: List<Set<String>> = pack.facts.map { tokens(it.title).toSet() }
        internal val avg: Double

        init {
            var total = 0L
            val grow = HashMap<String, Postings>()
            val counts = HashMap<String, Int>()
            pack.facts.forEachIndexed { i, f ->
                // The text counts twice as much as the title and section it sits under.
                counts.clear()
                val text = tokens(f.text)
                for (w in text) counts[w] = (counts[w] ?: 0) + 2
                for (w in tokens(f.title)) counts[w] = (counts[w] ?: 0) + 1
                for (w in tokens(f.sec)) counts[w] = (counts[w] ?: 0) + 1
                for (t in f.tags) { val w = t.replace("-", ""); counts[w] = (counts[w] ?: 0) + 1 }
                var n = 0
                for ((w, c) in counts) { n += c; grow.getOrPut(w) { Postings() }.add((i.toLong() shl 16) or c.coerceAtMost(0xFFFF).toLong()) }
                length[i] = n; total += n
            }
            postings = grow.mapValues { it.value.done() }
            avg = if (pack.facts.isEmpty()) 1.0 else total.toDouble() / pack.facts.size
        }

        val size: Int get() = pack.facts.size
        internal fun idf(w: String): Double {
            val n = postings[w]?.size ?: 0
            return ln(1.0 + (size - n + 0.5) / (n + 0.5))
        }
    }

    /** A growable primitive list (the index's postings while it is built). */
    private class Postings {
        private var a = LongArray(4); private var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun done(): LongArray = a.copyOf(n)
    }

    private const val K1 = 1.2
    private const val B = 0.6
    /** Below this a fact is too loosely related to say. */
    const val MIN_SCORE = 4.0

    /**
     * The top [k] facts for [question], best first. [locked]: Boss's own figures ([Fact.personal]) left out. At most two from
     * one source doc, so one long study does not crowd out the rest; none below [MIN_SCORE].
     */
    fun search(index: Index, question: String, k: Int = 5, locked: Boolean = false): List<Hit> {
        val q = tokens(question)
        if (q.isEmpty() || index.size == 0) return emptyList()
        val weights = HashMap<String, Double>()
        for (w in q) weights[w] = 1.0
        for (w in q) for (s in SYN[w].orEmpty()) { val t = stem(s); if (t !in weights) weights[t] = 0.4 }
        val scores = HashMap<Int, Double>()
        for ((w, qw) in weights) {
            val list = index.postings[w] ?: continue
            val idf = index.idf(w)
            for (p in list) {
                val i = (p shr 16).toInt(); val tf = (p and 0xFFFF).toDouble()
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * index.length[i] / index.avg))
                scores[i] = (scores[i] ?: 0.0) + qw * idf * norm
            }
        }
        val qset = q.toSet()
        val asked = instruments(question)
        val tagsAsked = q.mapNotNull { TAG_WORDS[it] }.toSet()
        val verdictAsked = VERDICT_ASK.containsMatchIn(normal(question))
        val ranked = scores.entries.mapNotNull { (i, s) ->
            val f = index.pack.facts[i]
            if (locked && f.personal) return@mapNotNull null
            var x = s * (1.0 + 0.15 * (f.weight - 1.0)) * when (f.conf) { "high" -> 1.1; "low" -> 0.75; else -> 1.0 }
            if (asked.isNotEmpty()) x *= when {
                f.inst.any { it in asked } -> 1.35
                f.inst.isEmpty() -> 0.9
                else -> 0.55
            }
            val title = index.titles[i]
            if (title.isNotEmpty()) x *= 1.0 + 0.5 * title.count { it in qset } / title.size
            val tagHits = f.tags.count { it in tagsAsked }
            x *= 1.0 + 0.2 * tagHits
            if (verdictAsked) x *= when { f.kind == "strategy" -> 1.4; f.kind == "finding" -> 1.2; f.verdict == "context" -> 0.85; else -> 1.0 }
            if (x < MIN_SCORE) null else Hit(f, x)
        }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.fact.id })
        val out = ArrayList<Hit>()
        val perDoc = HashMap<String, Int>()
        for (h in ranked) {
            if (out.size >= k) break
            val n = perDoc[h.fact.src] ?: 0
            if (n >= 2) continue
            perDoc[h.fact.src] = n + 1
            out += h
        }
        return out
    }

    /** "Is X profitable", "does X work", "what was the verdict": strategy cards and findings first. */
    private val VERDICT_ASK = Regex(" (profitable|profit|worth|edge|work|works|pay|pays|verdict|lose|loses|money|munafa|fayda|faida|kaam) ")

    // ---- the question ----------------------------------------------------------------------------------------------------

    private val CUE = Regex(" (research|researched|study|studies|studied|findings|evidence|historically|in history|history says|" +
        "past data|the data say|data says|have we tested|did we test|was tested|been tested|hunt|shodh|anusandhan|" +
        "what did (the )?(backtests?|tests?) (say|show|find)|backtests? (say|said|show|showed)) ")
    private val PROFIT = Regex(" (is|are|was|were) ([a-z0-9]+ ){1,5}(profitable|worth (it|trading)|any good|a good strategy|an edge) |" +
        " (does|do|did) ([a-z0-9]+ ){1,5}(work|pay|help|hurt|make money|have an edge|lose money|beat random|beat the market) |" +
        " (any|is there an|is there any) edge (in|on|with|from) | (what is|whats|how big is) the edge (of|in|on|from) ")
    private val BEHAVE = Regex(" how (does|do|did) ([a-z0-9]+ ){1,4}(behave|react|move|trade|perform) (on|after|when|in|before|during|around|near) |" +
        " what happens (to [a-z0-9]+ )?(on|after|when|before|around) (an? |the )?(expiry|gap|gapdown|gapup|big|budget|rbi|fed|election|holiday|monday|friday|vix|result) ")
    private val HINGLISH = Regex(" (research|study|backtest|shodh) (me|mein|ne|ke hisab se|kya) | data kya kehta | kya kehta hai (research|study|data) |" +
        " (munafa|fayda|faida|profit) (deta|deti|dete|hota|hoti|karata|karati) | kaam (karta|karti|karte) hai kya | chalta hai kya ")
    /** His account, Jarvis's own doings, or today's market: other answers say those. */
    private val NOT_HERE = Regex(" (my|mine|mera|meri|mere|our|humara|hamara|i|you|your|tum|tumne|tumhara|tumhari|aap|aapne) | (today|todays|aaj|abhi|right now|now|yesterday|kal|this week|is hafte) | study page| teach me ")

    /**
     * Is [text] a question for the research ("what did the research say about ORB", "is liquidity profitable", "how does
     * banknifty behave on expiry after a gap down", "research kya kehta hai liquidity ke baare me")? Not when it is about
     * his own account or today's market (other answers have those), and never an order or a command (the hub's guard).
     */
    fun asked(text: String): Boolean = askedKept.of(text) {
        val t = Spaced.joined(text)
        val n = normal(text)
        val cue = CUE.containsMatchIn(t) || HINGLISH.containsMatchIn(t)
        val form = PROFIT.containsMatchIn(t) || BEHAVE.containsMatchIn(n) || BEHAVE.containsMatchIn(t)
        (cue || form) && !NOT_HERE.containsMatchIn(t) && tokens(text).isNotEmpty()
    }
    private val askedKept = Kept<Boolean>(64)

    // ---- what is said ----------------------------------------------------------------------------------------------------

    private fun verdictWord(v: String): String = when (v) {
        "positive" -> "held up"; "weak" -> "weak, paper only"; "negative" -> "did not pay"; else -> ""
    }

    /** One fact as said and as the model's fact: its own text, then where it is from (doc, section, date) and the verdict. */
    fun line(f: Fact): String {
        val w = verdictWord(f.verdict)
        val sec = f.sec.substringBefore(" > ").take(60)
        return "${f.text.trim().trimEnd('.')}. (${f.doc}, $sec${if (f.date.isNotEmpty()) ", ${f.date}" else ""}${if (w.isNotEmpty()) "; $w" else ""})"
    }

    /** The facts behind an answer: each hit's [line]. */
    fun lines(hits: List<Hit>): List<String> = hits.map { line(it.fact) }

    const val NOTHING = "I don't find that in the research I carry, Boss. Ask it another way, or name the strategy or the index."
    const val NOT_LOADED = "My research notes aren't loaded yet, Boss. Ask me again in a moment."
    private const val TAIL = "That is what the research found, not advice; nothing is changed."

    /** Jarvis's answer from [hits] (best first; at most three said): only the facts' own lines between fixed words. */
    fun say(hits: List<Hit>): String {
        if (hits.isEmpty()) return NOTHING
        val said = hits.take(3)
        val body = said.mapIndexed { i, h -> (if (said.size > 1) "${ORDINAL[i]}, " else "") + line(h.fact) }.joinToString(" ")
        return "From the research, Boss: $body $TAIL"
    }
    private val ORDINAL = listOf("First", "Second", "Third")

    /**
     * The model's notes for [question] (only when it is a research question, [asked]): the best facts' [line]s, at most [cap]
     * characters in all. Boss's own figures never go in (the notes may be shown on a locked phone).
     */
    fun forModel(index: Index?, question: String, cap: Int = AgentLessons.MODEL_CHARS): List<String> {
        if (index == null || !asked(question)) return emptyList()
        val out = ArrayList<String>()
        var used = 0
        for (h in search(index, question, k = 4, locked = true)) {
            val s = line(h.fact)
            if (used + s.length + 2 > cap) continue
            out += s; used += s.length + 2
        }
        return out
    }
}
