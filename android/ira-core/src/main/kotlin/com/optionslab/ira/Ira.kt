package com.optionslab.ira

import java.util.Locale

/** An answer: the words, the facts they were built from (every number in the words is among them), and an order to review. */
data class Answer(val text: String, val facts: List<String>, val order: OrderRequest? = null)

/**
 * Ira's answering, without any language model: it reads the question and writes sentences from the snapshots, the
 * pattern book and the headlines - and from nothing else. Pure.
 */
class Ira(private val book: PatternBook = PatternBook()) {

    fun answer(question: String, snaps: Map<Market, Snapshot>, news: List<Headline>): Answer {
        val q = Ask.parse(question)
        val facts = ArrayList<String>()
        val parts = ArrayList<String>()
        if (Topic.ORDER in q.topics) return orderAnswer(q.order!!)
        if (Topic.BACKTEST in q.topics) return Answer("Backtesting needs the app's candles; ask me on the Ira screen.", emptyList())
        if (Topic.OFF_TOPIC in q.topics) return Answer("I only know the Indian indices (Nifty, BankNifty, FinNifty, Sensex, India VIX) and gold. Ask me about one of them.", emptyList())
        if (q.topics == setOf(Topic.GREETING)) return Answer("Hello. Ask me about Nifty, BankNifty, FinNifty, Sensex, VIX or gold.", emptyList())
        val markets = q.markets.ifEmpty { listOf(Market.NIFTY) }
        for (m in markets) {
            val s = snaps[m]
            if (s == null) { parts += "I have no prices for ${m.label} yet."; continue }
            val f = facts(s)
            facts += f.map { "${m.label}: $it" }
            val t = q.topics
            if (Topic.OVERVIEW in t || Topic.WHY in t) parts += overview(s)
            if (Topic.TREND in t) parts += trend(s)
            if (Topic.LEVELS in t || Topic.OVERVIEW in t) parts += levels(s)
            if (Topic.VOLATILITY in t || Topic.WHY in t) parts += volatility(s, snaps[Market.VIX])
            if (Topic.PATTERNS in t || Topic.OVERVIEW in t || Topic.WHY in t) patterns(s)?.let { parts += it; facts += it }
            if (Topic.NEWS in t || Topic.WHY in t) parts += newsLines(m, news, facts)
            if (Topic.ADVICE in t && Topic.OVERVIEW !in t) parts += overview(s)
        }
        if (Topic.ADVICE in q.topics) parts += "I don't give buy or sell advice; these are the facts for you to decide on."
        val text = parts.filter { it.isNotBlank() }.distinct().joinToString(" ")
        return Answer(text.ifBlank { "I don't have that." }, facts)
    }

    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)

    /** The plain facts of a snapshot, one per line: what an answer may quote. */
    fun facts(s: Snapshot): List<String> = buildList {
        val m = s.market
        add("last price ${m.price(s.price)} at ${Brain.when_(s.at, m, s.at.toLocalDate())}")
        s.prevClose?.let { add("previous close ${m.price(it)}, change ${m.price(s.change!!).let { p -> if (s.change!! >= 0) "+$p" else p }} (${pct(s.changePct!!)})") }
        add("day open ${m.price(s.open)}, high ${m.price(s.high)}, low ${m.price(s.low)}")
        if (s.openingHigh != null) add("first 15 minutes: high ${m.price(s.openingHigh)}, low ${m.price(s.openingLow!!)}")
        s.trends.forEach { tr -> add("${tr.minutes}-minute trend ${if (tr.up) "up" else "down"}, tracker line ${m.price(tr.line)}" +
            (tr.since?.let { " since ${Brain.when_(it, m, s.at.toLocalDate())}" } ?: "")) }
        s.mood?.let { add("today is ${it.word}: range ${"%.1f".format(Locale.ENGLISH, s.rangeRatio!!)}x the usual by this time") }
        s.above.forEach { add("level above: ${it.name} ${m.price(it.price)}") }
        s.below.forEach { add("level below: ${it.name} ${m.price(it.price)}") }
    }

    private fun overview(s: Snapshot): String {
        val m = s.market
        val ch = s.changePct?.let { " (${pct(it)} on the day)" } ?: ""
        val state = if (s.trading) "" else " The market is closed; these are the last prices."
        return "${m.label} is at ${m.price(s.price)}$ch as of ${Brain.when_(s.at, m, s.at.toLocalDate())}, " +
            "in a range of ${m.price(s.low)} to ${m.price(s.high)} today.$state " + trend(s)
    }

    private fun trend(s: Snapshot): String {
        val m = s.market
        if (s.trends.isEmpty()) return "Not enough candles yet to read the trend."
        return s.trends.joinToString(" ") { tr ->
            "The ${if (tr.minutes == 60) "1-hour" else "${tr.minutes}-minute"} trend is ${if (tr.up) "up" else "down"}" +
                (tr.since?.let { " since ${Brain.when_(it, m, s.at.toLocalDate())}" } ?: "") +
                ", tracker line ${m.price(tr.line)}."
        }
    }

    private fun levels(s: Snapshot): String {
        val m = s.market
        val a = s.above.firstOrNull()?.let { "Nearest level above: ${it.name} at ${m.price(it.price)}." } ?: ""
        val b = s.below.firstOrNull()?.let { "Nearest level below: ${it.name} at ${m.price(it.price)}." } ?: ""
        return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun volatility(s: Snapshot, vix: Snapshot?): String {
        val mood = s.mood?.let { "Today is ${it.word} for ${s.market.label}: its range is ${"%.1f".format(Locale.ENGLISH, s.rangeRatio)}x the usual by this time of day." } ?: ""
        val v = if (vix != null && s.market != Market.GOLD && s.market != Market.VIX) " India VIX is ${"%.2f".format(Locale.ENGLISH, vix.price)}" +
            (vix.changePct?.let { " (${pct(it)})." } ?: ".") else ""
        return (mood + v).trim()
    }

    /** The newest pattern today with what the book has learned about it. */
    private fun patterns(s: Snapshot): String? {
        val p = s.patterns.firstOrNull() ?: return null
        val m = s.market
        val st = book.stat(m, p.minutes, p.kind)
        val chart = if (p.minutes == 60) "1-hour" else "${p.minutes}-minute"
        val past = if (st.seen < PatternBook.ENOUGH) "I have seen it only ${st.seen} times here, too few to say how it usually goes."
            else "Here it moved its way over the next ${PatternBook.HORIZON} candles in ${Math.round(st.rate * 100)}% of ${st.seen} cases, average ${pct(st.avgMovePct)}."
        return "A ${p.kind.label} formed on the $chart chart at ${Brain.when_(p.at, m, s.at.toLocalDate())} near ${m.price(p.price)}. $past"
    }

    private fun newsLines(m: Market, news: List<Headline>, facts: MutableList<String>): String {
        val mine = news.filter { m in it.markets }.take(3)
        if (mine.isEmpty()) return "No recent headlines about ${m.label}."
        val tone = mine.groupingBy { it.toneWord }.eachCount()
        mine.forEach { facts += "headline (${it.source}, ${it.toneWord}): ${it.title}" }
        return "Recent headlines on ${m.label}: " + mine.joinToString("; ") { "\"${it.title}\" (${it.source}, ${it.toneWord})" } +
            ". Tone: " + listOf("positive", "neutral", "negative").filter { it in tone }.joinToString(", ") { "${tone[it]} $it" } + "."
    }

    private fun orderAnswer(o: OrderRequest): Answer {
        if (o.missing.isNotEmpty()) return Answer("To prepare that order I need: ${o.missing.joinToString(", ")}.", emptyList(), o)
        val what = if (o.market == Market.GOLD) "${o.lots} lot gold" else "${o.lots} lot${if (o.lots!! > 1) "s" else ""} ${o.market!!.label} ${o.strike} ${o.right}"
        return Answer("I have filled the order review: ${if (o.buy) "BUY" else "SELL"} $what. Nothing is sent until you press Confirm" +
            (if (o.market == Market.GOLD) " (gold is paper only)." else " (and enter your PIN in Live)."), emptyList(), o)
    }

    /** Every number written in [text] appears in [facts]: the check a model's answer must pass (rule 4). */
    fun numbersBacked(text: String, facts: List<String>): Boolean {
        val num = Regex("\\d[\\d,]*(\\.\\d+)?")
        val known = facts.flatMap { f -> num.findAll(f).map { it.value.replace(",", "") } }.toSet()
        return num.findAll(text).all { it.value.replace(",", "") in known }
    }
}
