package com.optionslab.ira

import java.util.Locale

/** An answer: the words, the facts they were built from (every number in the words is among them), and an order to review. */
data class Answer(val text: String, val facts: List<String>, val order: OrderRequest? = null,
                  /** The patterns the words told of, to follow how they played out ([PatternCalls]). */
                  val calls: List<PatternCalls.Call> = emptyList())

/**
 * Ira's answering, without any language model: it reads the question and writes sentences from the snapshots, the
 * pattern book and the headlines - and from nothing else. Pure.
 */
class Ira(private val book: PatternBook = PatternBook(),
          /** How the patterns Jarvis told of before played out on this phone ([PatternCalls]). */
          private val calls: List<PatternCalls.Call> = emptyList(),
          /** The index Boss asks about by name, named first in a greeting ([LeadIndex]); null: Nifty first, as always. */
          private val lead: Market? = null) {

    /** [app]: the app and the owner's trading as the app read it (null: not read); [voice]: this app can listen (Jarvis). */
    fun answer(question: String, snaps: Map<Market, Snapshot>, news: List<Headline>, app: AppView? = null, voice: Boolean = false,
               /** The time now and, on a day with no session, why: a greeting is then answered properly. */
               now: java.time.LocalDateTime? = null, closedReason: String? = null): Answer {
        val q = Ask.parse(question)
        val facts = ArrayList<String>()
        val parts = ArrayList<String>()
        val told = ArrayList<PatternCalls.Call>()
        if (Topic.ORDER in q.topics) return orderAnswer(q.order!!)
        if (Topic.BACKTEST in q.topics) return Answer("Backtesting needs the app's candles; ask me on the Ira screen.", emptyList())
        if (Topic.HELP in q.topics) return AppAnswers.help(q, voice)
        if (Topic.ACCOUNT in q.topics) return AppAnswers.answer(q, app)
        if (Topic.OFF_TOPIC in q.topics) return Answer("I only know the Indian indices (Nifty, BankNifty, FinNifty, Sensex, India VIX) and gold. Ask me about one of them.", emptyList())
        if (q.topics == setOf(Topic.GREETING)) return if (now != null) Greeting.say(now, snaps, closedReason, lead).let { Answer(it, listOf(it)) }
            else Answer("Hello. Ask me about Nifty, BankNifty, FinNifty, Sensex, VIX or gold.", emptyList())
        // "Any news?" with no market named: the latest market headlines, whatever they are about.
        if (Topic.NEWS in q.topics && q.markets.isEmpty()) parts += latestNews(news, facts)
        val markets = q.markets.ifEmpty { listOf(Market.NIFTY) }
        for (m in markets) {
            if (q.topics == setOf(Topic.NEWS) && q.markets.isEmpty()) break
            val s = snaps[m]
            if (s == null) {
                // The news needs no prices.
                if (Topic.NEWS in q.topics && q.markets.isNotEmpty()) parts += newsLines(m, news, facts)
                if (q.topics.any { it != Topic.NEWS }) parts += "I have no prices for ${m.label} yet."
                continue
            }
            val f = facts(s)
            facts += f.map { "${m.label}: $it" }
            val t = q.topics
            if (Topic.OVERVIEW in t || Topic.WHY in t) parts += overview(s)
            if (Topic.TREND in t) parts += trend(s)
            if (Topic.LEVELS in t || Topic.OVERVIEW in t) parts += levels(s)
            if (Topic.WHY in t) Why.story(s, snaps)?.let { parts += it; facts += it }
            if (Topic.VOLATILITY in t || Topic.WHY in t) parts += volatility(s, snaps[Market.VIX])
            // Patterns asked about are always told; brought up with an overview, a kind with a poor record here is not.
            if (Topic.PATTERNS in t || Topic.OVERVIEW in t || Topic.WHY in t) patterns(s, Topic.PATTERNS in t)?.let { (said, p) ->
                parts += said; facts += said; PatternCalls.call(m, p)?.let { told += it } }
            if (Topic.NEWS in t && q.markets.isNotEmpty() || Topic.WHY in t) parts += newsLines(m, news, facts)
            if (Topic.ADVICE in t && Topic.OVERVIEW !in t) parts += overview(s)
        }
        if (Topic.ADVICE in q.topics) parts += "I don't give buy or sell advice; these are the facts for you to decide on."
        val text = parts.filter { it.isNotBlank() }.distinct().joinToString(" ")
        return Answer(text.ifBlank { "I don't have that." }, facts, calls = if (text.isBlank()) emptyList() else told)
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
        s.above.forEach { add("level above: ${it.name} ${m.price(it.price)}, ${m.price(it.price - s.price)} away") }
        s.below.forEach { add("level below: ${it.name} ${m.price(it.price)}, ${m.price(s.price - it.price)} away") }
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
        } + agreement(s)
    }

    /** Do the charts agree? Both one way gives the move weight; split charts mean a pullback or a turn starting. */
    private fun agreement(s: Snapshot): String {
        val a = s.trend(15) ?: return ""; val b = s.trend(60) ?: return ""
        return if (a.up == b.up) " Both charts point ${if (a.up) "up" else "down"}, so the move has weight."
        else " The charts disagree: the 15-minute is ${if (a.up) "up" else "down"} inside a 1-hour ${if (b.up) "up" else "down"}trend - " +
            "a pullback, or a turn starting; it is clearer once they line up."
    }

    private fun levels(s: Snapshot): String {
        val m = s.market
        val a = s.above.firstOrNull()?.let { "Nearest level above: ${it.name} at ${m.price(it.price)}, ${m.price(it.price - s.price)} away." } ?: ""
        val b = s.below.firstOrNull()?.let { "Nearest level below: ${it.name} at ${m.price(it.price)}, ${m.price(s.price - it.price)} away." } ?: ""
        return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun volatility(s: Snapshot, vix: Snapshot?): String {
        val mood = s.mood?.let { "Today is ${it.word} for ${s.market.label}: its range is ${"%.1f".format(Locale.ENGLISH, s.rangeRatio)}x the usual by this time of day." } ?: ""
        val v = if (vix != null && s.market != Market.GOLD && s.market != Market.VIX) " India VIX is ${"%.2f".format(Locale.ENGLISH, vix.price)}" +
            (vix.changePct?.let { " (${pct(it)})." } ?: ".") else ""
        return (mood + v).trim()
    }

    /**
     * The newest pattern today with what the book has learned about it, and how it went when Jarvis told of it before
     * on this phone. Not [asked]: the newest one whose record of his calls is not clearly poor ([PatternCalls.quiet]).
     */
    private fun patterns(s: Snapshot, asked: Boolean = true): Pair<String, Pattern>? {
        val m = s.market
        val today = s.at.toLocalDate()
        val p = (if (asked) s.patterns.firstOrNull() else s.patterns.firstOrNull { !PatternCalls.quiet(calls, m, it.minutes, it.kind, today) }) ?: return null
        val st = book.stat(m, p.minutes, p.kind)
        val chart = if (p.minutes == 60) "1-hour" else "${p.minutes}-minute"
        val past = if (st.seen < PatternBook.ENOUGH) "I have seen it only ${st.seen} times here, too few to say how it usually goes."
            else "Here it moved its way over the next ${PatternBook.HORIZON} candles in ${Math.round(st.rate * 100)}% of ${st.seen} cases, average ${pct(st.avgMovePct)}."
        val mine = PatternCalls.line(calls, m, p, today)?.let { " $it" } ?: ""
        return "A ${p.kind.label} formed on the $chart chart at ${Brain.when_(p.at, m, today)} near ${m.price(p.price)}. $past$mine" to p
    }

    /** The newest headlines (Indian markets first), however they are tagged. */
    private fun latestNews(news: List<Headline>, facts: MutableList<String>, max: Int = 4): String {
        val india = news.filter { h -> h.markets.none { it == Market.GOLD } }
        val top = (india.ifEmpty { news }).sortedByDescending { it.at ?: java.time.Instant.EPOCH }.take(max)
        if (top.isEmpty()) return "I have no headlines yet - the news feeds have not answered. Ask again in a minute."
        top.forEach { facts += "headline (${it.source}, ${it.toneWord}): ${it.title}" }
        return "The latest market headlines: " + top.joinToString("; ") { "\"${it.title}\" (${it.source}, ${it.toneWord})" } + "."
    }

    private fun newsLines(m: Market, news: List<Headline>, facts: MutableList<String>): String {
        val mine = news.filter { m in it.markets }.sortedByDescending { it.at ?: java.time.Instant.EPOCH }.take(3)
        if (mine.isEmpty()) return "Nothing specific on ${m.label} lately. " + latestNews(news, facts, 3)
        val tone = mine.groupingBy { it.toneWord }.eachCount()
        mine.forEach { facts += "headline (${it.source}, ${it.toneWord}): ${it.title}" }
        return "Recent headlines on ${m.label}: " + mine.joinToString("; ") { "\"${it.title}\" (${it.source}, ${it.toneWord})" } +
            ". Tone: " + listOf("positive", "neutral", "negative").filter { it in tone }.joinToString(", ") { "${tone[it]} $it" } + "."
    }

    private fun orderAnswer(o: OrderRequest): Answer {
        o.refusal?.let { return Answer(it, emptyList()) }
        if (o.missing.isNotEmpty()) return Answer("To prepare that order I need: ${o.missing.joinToString(", ")}.", emptyList(), o)
        return Answer("Ready for review: ${o.describe()}, nearest expiry. Tap Review; nothing is sent until you press Confirm " +
            "(and, in Live, swipe and enter your PIN).", emptyList(), o)
    }

    /** Every number written in [text] appears in [facts]: the check a model's answer must pass (rule 4). */
    fun numbersBacked(text: String, facts: List<String>): Boolean {
        val num = rx("\\d[\\d,]*(\\.\\d+)?")
        val known = facts.flatMap { f -> num.findAll(f).map { it.value.replace(",", "") } }.toSet()
        return num.findAll(text).all { it.value.replace(",", "") in known }
    }
}
