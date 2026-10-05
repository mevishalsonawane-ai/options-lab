package com.optionslab.ira

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The news desk (Jarvis market intelligence, 2026-10-05): the headlines the app already reads ([News]), with the same
 * event from different outlets put together as one story with its source count, each story tagged with what it touches
 * (the indices, sectors - banks, IT, auto, pharma, metals, oil and energy, FMCG, realty - and RBI, the Fed, the budget,
 * inflation, earnings, FII/DII flows, the rupee) from fixed word lists. It answers "what's the main news today?" (the
 * top three stories by sources, then recency, with times), "any news on banks?" and "what news moved the market today?"
 * - the last only as timing: the headlines published around today's sharp moves ([SharpMove]'s windows), never a cause.
 * Facts and sources only: no advice, no forecast, no reading of what the news "means". A headline is data: nothing here
 * acts on it. The latest headlines and "any news on Nifty" stay [Ira]'s, one move's coincidences [SharpMove]'s. Pure.
 */
object NewsDesk {
    /** What a story touches. [masks]: phrases taken out before [words] are looked for ("World Bank" is not a bank). */
    enum class Tag(val label: String, val words: List<String>, val masks: List<String> = emptyList()) {
        NIFTY("Nifty", listOf("nifty", "nifty 50", "nifty50"), listOf("bank nifty", "nifty bank", "fin nifty", "nifty it", "nifty auto", "nifty pharma", "nifty metal", "nifty fmcg", "nifty realty", "nifty psu bank")),
        BANKNIFTY("BankNifty", listOf("bank nifty", "banknifty", "nifty bank")),
        FINNIFTY("FinNifty", listOf("finnifty", "fin nifty", "nifty financial services")),
        SENSEX("Sensex", listOf("sensex")),
        VIX("India VIX", listOf("india vix", "vix")),
        BANKS("banks", listOf("bank", "banks", "banking", "banker", "lender", "lenders", "psu bank", "psu banks", "private banks", "hdfc bank", "icici bank", "icici",
            "sbi", "kotak", "axis bank", "indusind", "bank of baroda", "pnb", "yes bank", "canara bank", "npa", "npas", "bad loans", "credit growth", "deposit growth"),
            listOf("world bank", "reserve bank of india", "reserve bank", "central bank", "central banks", "bank nifty", "nifty bank", "investment bank", "investment banks",
                "bank of japan", "bank of england", "european central bank", "people s bank of china", "bank holiday", "bank holidays")),
        IT("IT", listOf("it stocks", "it shares", "it sector", "it services", "it companies", "it firms", "it major", "it majors", "it index", "nifty it", "tech stocks",
            "infosys", "tcs", "wipro", "hcltech", "hcl tech", "hcl technologies", "tech mahindra", "ltimindtree", "mphasis", "coforge", "persistent systems")),
        AUTO("auto", listOf("auto", "autos", "auto stocks", "auto sales", "automaker", "automakers", "carmaker", "carmakers", "car sales", "vehicle sales", "two wheeler",
            "two wheelers", "maruti", "maruti suzuki", "tata motors", "bajaj auto", "hero motocorp", "eicher", "tvs motor", "ashok leyland", "electric vehicle", "electric vehicles", "nifty auto")),
        PHARMA("pharma", listOf("pharma", "pharmaceutical", "pharmaceuticals", "drugmaker", "drugmakers", "drug maker", "usfda", "us fda", "sun pharma", "cipla", "dr reddy",
            "dr reddy s", "lupin", "aurobindo", "divi s", "zydus", "biocon", "nifty pharma", "healthcare stocks")),
        METALS("metals", listOf("metal", "metals", "metal stocks", "steel", "steelmaker", "aluminium", "aluminum", "copper", "zinc", "iron ore", "tata steel", "jsw steel",
            "hindalco", "vedanta", "sail", "nmdc", "coal india", "nifty metal")),
        ENERGY("oil and energy", listOf("crude", "crude oil", "oil prices", "oil price", "brent", "opec", "ongc", "oil india", "bpcl", "hpcl", "ioc", "reliance",
            "reliance industries", "natural gas", "power stocks", "ntpc", "power grid", "energy stocks", "refiners", "oil marketing")),
        FMCG("FMCG", listOf("fmcg", "consumer goods", "consumer staples", "hul", "hindustan unilever", "itc", "nestle india", "britannia", "dabur", "marico", "godrej consumer", "nifty fmcg")),
        REALTY("realty", listOf("realty", "real estate", "realtor", "realtors", "property developers", "housing sales", "dlf", "godrej properties", "oberoi realty", "lodha", "nifty realty")),
        RBI("RBI", listOf("rbi", "reserve bank", "reserve bank of india", "repo", "repo rate", "mpc", "monetary policy committee", "crr", "rbi governor")),
        FED("the Fed", listOf("fed", "fomc", "federal reserve", "powell", "us rate", "us rates", "us interest rates", "fed rate", "fed rates")),
        BUDGET("the budget", listOf("budget", "union budget", "fiscal deficit", "finance minister", "sitharaman", "gst", "gst council", "capital gains tax", "stt")),
        INFLATION("inflation", listOf("inflation", "cpi", "wpi", "retail inflation", "wholesale inflation", "consumer prices", "price index")),
        EARNINGS("earnings", listOf("earnings", "results", "q1", "q2", "q3", "q4", "quarterly", "net profit", "profit", "revenue", "guidance", "beats estimates",
            "misses estimates", "ebitda", "margins"), listOf("election results", "election result", "poll results", "exam results", "exit poll", "exit polls",
            "profit booking", "profit taking")),
        FLOWS("FII/DII flows", listOf("fii", "fiis", "fpi", "fpis", "dii", "diis", "foreign investors", "foreign institutional investors", "foreign portfolio investors",
            "domestic institutional investors", "foreign funds", "inflows", "outflows")),
        RUPEE("the rupee", listOf("rupee", "inr", "usd inr", "usdinr", "forex reserves")),
        GOLD("gold", listOf("gold", "bullion", "comex", "xau", "precious metals")),
    }

    /** One story: the same event from one or more outlets. [title]: the earliest headline's words. */
    data class Story(val title: String, val headlines: List<Headline>, val sources: List<String>, val first: Instant?, val latest: Instant?, val tags: List<Tag>) {
        val sourceCount: Int get() = sources.size
    }

    /** What was asked: the main stories today, the stories on one [tag], or the news around today's sharp moves. */
    sealed class Ask {
        object Main : Ask()
        data class On(val tag: Tag) : Ask()
        object Moved : Ask()
    }

    /** Stories in the main-news answer. */
    const val TOP = 3
    /** Stories in the "news on banks" answer. */
    const val ON_MAX = 5
    /** "News on banks" looks back this many hours. */
    const val ON_HOURS = 24L
    /** Sharp moves read in the "what news moved the market" answer. */
    const val MOVES_MAX = 3
    /** Two headlines further apart than this are never one story. */
    const val SAME_STORY_HOURS = 18L

    const val TIMING = "That is timing only, Boss: a headline published near a move does not say it caused the move, and I can't tell which news moved the market."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun stamp(t: Instant?, zone: ZoneId, today: LocalDate): String {
        if (t == null) return "time not given"
        val l = LocalDateTime.ofInstant(t, zone)
        return if (l.toLocalDate() == today) hm(l) else "${l.dayOfMonth} ${l.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${hm(l)}"
    }

    // ---- tagging ----

    /** What [title] touches, in [Tag] order. */
    fun tags(title: String): List<Tag> {
        val t = norm(title)
        return Tag.entries.filter { tag ->
            var s = t
            for (m in tag.masks) s = s.replace(" $m ", " ")
            tag.words.any { s.contains(" $it ") }
        }
    }

    // ---- clustering ----

    private val STOP = setOf("a", "an", "the", "to", "of", "in", "on", "for", "as", "at", "by", "with", "and", "or", "is", "are", "was", "were", "be", "its", "it",
        "from", "over", "after", "amid", "says", "say", "said", "this", "that", "today", "live", "updates", "update", "news", "here", "what", "why", "how", "s",
        "into", "than", "more", "will", "has", "have", "had", "his", "her", "their", "up", "down", "us", "per", "cent", "percent", "report", "reports", "sources")

    /** "Title - Outlet" (an aggregator's form): the outlet's name is not the story's words. */
    private fun bare(title: String): String {
        val i = title.lastIndexOf(" - ")
        return if (i > 0 && title.substring(i + 3).trim().split(rx("\\s+")).size <= 4) title.substring(0, i) else title
    }

    /** A headline's telling words: no small words, plurals folded ("banks" and "bank" are one word). */
    fun keyWords(title: String): Set<String> = norm(bare(title)).trim().split(" ").filter { it.length > 1 && it !in STOP }
        .map { w -> if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }.toSet()

    /** Are two headlines the same event? Most of the shorter one's telling words in the other, and published near each other. */
    fun same(a: Headline, b: Headline): Boolean {
        if (a.at != null && b.at != null && abs(Duration.between(a.at, b.at).toHours()) > SAME_STORY_HOURS) return false
        val x = keyWords(a.title); val y = keyWords(b.title)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val both = x.intersect(y).size
        val small = minOf(x.size, y.size)
        val union = x.union(y).size
        return (both >= 3 && both.toDouble() / small >= 0.6) || (both >= 2 && both.toDouble() / union >= 0.6)
    }

    /** [news] as stories: the same event from different outlets together (oldest headline first in each), newest story first. */
    fun stories(news: List<Headline>): List<Story> {
        val groups = ArrayList<MutableList<Headline>>()
        for (h in news.sortedBy { it.at ?: Instant.EPOCH }) {
            val g = groups.firstOrNull { g -> g.any { same(it, h) } }
            if (g != null) g += h else groups += mutableListOf(h)
        }
        return groups.map { g ->
            val sources = g.flatMap { listOf(it.source) + it.also }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            val times = g.mapNotNull { it.at }
            Story(bare(g.first().title), g.toList(), sources, times.minOrNull(), times.maxOrNull(), tags(g.joinToString(" ") { bare(it.title) }))
        }.sortedByDescending { it.latest ?: Instant.EPOCH }
    }

    // ---- what was asked ----

    // Forecasts, advice, Boss's own book, and the app's news settings or feeds ("which news sources do you use").
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|predict|prediction|forecast|should|shall|buy|sell|enter|exit|short|long|i|me|my|mine|we|our|" +
        "source|sources|feed|feeds|setting|settings|alert|alerts|notify|notification|notifications|mute|unmute|turn on|turn off|switch) ")
    private val MAIN = Regex(" (main|top|big|biggest|major|key|important|leading|lead|badi|bada|khaas|khas|zaroori|zaruri) (market )?(news|headline|headlines|story|stories|khabar|khabren|khabrein) |" +
        " (what s|what is|whats) (making|in) (the )?(news|headlines) | news of the day | (today s|todays) (main |top |big )?(headlines|stories) |" +
        " (what s|what is|whats) the news today ")
    private val MOVED = Regex(" (what|which|any|did|did any|was it|is) (the )?(news|headline|headlines|story|stories) (that |which )?(moved|move|moving|drove|drive|driving|caused|cause|causing|pushed|push|pushing|dragged|lifted|sank|hit) |" +
        " (news|headline|headlines) behind (today s|todays|the market s|the markets) (move|moves|fall|drop|rally|rise|swing|swings|selloff|sell off) | did (the )?news move ")
    // The words Boss uses for each tag when asking (a few more than the tagging words, and never the indices: "any news on
    // Nifty" is [Ira]'s).
    private val ASKED_TAGS: List<Pair<Tag, Regex>> = listOf(
        Tag.BANKS to Regex(" (banks|banking|bank stocks|bank shares|banking stocks|lenders|psu banks|private banks|banking sector) "),
        Tag.IT to Regex(" (it stocks|it shares|it sector|it companies|it firms|tech stocks|tech sector|infosys|tcs|wipro) "),
        Tag.AUTO to Regex(" (auto|autos|auto stocks|auto sector|automakers|carmakers|cars|vehicle sales|auto sales) "),
        Tag.PHARMA to Regex(" (pharma|pharma stocks|pharma sector|pharmaceuticals|drugmakers|healthcare) "),
        Tag.METALS to Regex(" (metal|metals|metal stocks|steel|metals sector) "),
        Tag.ENERGY to Regex(" (oil|crude|crude oil|energy|oil and gas|energy stocks|reliance) "),
        Tag.FMCG to Regex(" (fmcg|consumer goods|consumer stocks) "),
        Tag.REALTY to Regex(" (realty|real estate|property|realty stocks) "),
        Tag.RBI to Regex(" (rbi|reserve bank|repo rate|rate decision|monetary policy|mpc) "),
        Tag.FED to Regex(" (fed|the fed|fomc|federal reserve|us fed|powell) "),
        Tag.BUDGET to Regex(" (budget|the budget|union budget|gst|fiscal deficit) "),
        Tag.INFLATION to Regex(" (inflation|cpi|wpi|prices data) "),
        Tag.EARNINGS to Regex(" (earnings|results|quarterly results|company results|q1|q2|q3|q4) "),
        Tag.FLOWS to Regex(" (fii|fiis|fpi|fpis|dii|diis|flows|fii flows|foreign investors|foreign flows) "),
        Tag.RUPEE to Regex(" (rupee|inr|the rupee|currency) "),
    )

    /** Which of the three was asked, or null. Facts only: a forecast, advice, Boss's own or the news settings are not these. */
    fun asked(text: String): Ask? {
        val t = norm(text)
        if (!rx(" (news|headline|headlines|story|stories|khabar|khabren|khabrein) ").containsMatchIn(t)) return null
        if (NOT.containsMatchIn(t)) return null
        if (MOVED.containsMatchIn(t)) return Ask.Moved
        if (MAIN.containsMatchIn(t)) return Ask.Main
        // A sector or theme named with the news: "any news on banks?", "RBI news", "headlines about IT". ("IT" said in
        // capitals is the sector; "it" otherwise is a word: "any news on it?")
        ASKED_TAGS.firstOrNull { it.second.containsMatchIn(t) }?.let { return Ask.On(it.first) }
        if (rx("\\bIT\\b").containsMatchIn(text)) return Ask.On(Tag.IT)
        return null
    }

    /** The index for "what news moved the market": the first index named, else Nifty. */
    fun market(markets: List<Market>): Market = markets.firstOrNull { it in SharpMove.INDICES } ?: Market.NIFTY

    // ---- the answers ----

    private fun line(s: Story, zone: ZoneId, today: LocalDate): String {
        val src = if (s.sourceCount == 1) "1 source (${s.sources.first()})" else "${s.sourceCount} sources (${s.sources.joinToString(", ")})"
        val time = when {
            s.first == null -> "time not given"
            s.latest == null || s.latest == s.first || s.headlines.size == 1 -> "at ${stamp(s.first, zone, today)}"
            else -> "first at ${stamp(s.first, zone, today)}, latest ${stamp(s.latest, zone, today)}"
        }
        val touches = s.tags.takeIf { it.isNotEmpty() }?.let { "; touches ${it.joinToString(", ") { t -> t.label }}" } ?: ""
        return "\"${s.title}\" - $src, $time$touches"
    }

    private fun numbered(list: List<Story>, zone: ZoneId, today: LocalDate) =
        list.mapIndexed { i, s -> "${i + 1}. ${line(s, zone, today)}." }.joinToString(" ")

    /** The answer to [a] from [news] (and, for [Ask.Moved], [m]'s and the other indices' candles in [bars]) at [now] in [zone]. */
    fun answer(a: Ask, news: List<Headline>, now: Instant, zone: ZoneId, m: Market = Market.NIFTY, bars: Map<Market, List<Candle>> = emptyMap()): String {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        if (news.isEmpty()) return "I have no headlines on the phone yet, Boss - the news feeds have not answered. Ask again in a minute."
        return when (a) {
            is Ask.Main -> main(news, zone, today)
            is Ask.On -> on(a.tag, news, now, zone, today)
            is Ask.Moved -> moved(m, news, zone, bars)
        }
    }

    private fun ranked(all: List<Story>) = all.sortedWith(compareByDescending<Story> { it.sourceCount }.thenByDescending { it.latest ?: Instant.EPOCH })

    /** The main-news answer's stories: today's top [TOP] by sources, then recency (none: no headline from today). */
    fun top(news: List<Headline>, zone: ZoneId, today: LocalDate): List<Story> =
        ranked(stories(news.filter { h -> h.at != null && LocalDateTime.ofInstant(h.at, zone).toLocalDate() == today })).take(TOP)

    private fun main(news: List<Headline>, zone: ZoneId, today: LocalDate): String {
        val todays = news.filter { h -> h.at != null && LocalDateTime.ofInstant(h.at, zone).toLocalDate() == today }
        if (todays.isEmpty()) {
            val last = stories(news).take(TOP)
            return "No headline on the phone is from today yet, Boss. The latest stories: ${numbered(last, zone, today)}"
        }
        val all = stories(todays)
        val top = ranked(all).take(TOP)
        val many = top.count { it.sourceCount > 1 }
        val how = if (many == 0) "each carried by one source, so the newest first" else "most sources first, then the newest"
        return "Today's main stories, Boss ($how; ${todays.size} headline${if (todays.size == 1) "" else "s"} in ${all.size} " +
            "stor${if (all.size == 1) "y" else "ies"} on the phone): ${numbered(top, zone, today)} Facts and sources only, not a reading of what they mean for prices."
    }

    private fun on(tag: Tag, news: List<Headline>, now: Instant, zone: ZoneId, today: LocalDate): String {
        val recent = news.filter { h -> h.at == null || !h.at.isBefore(now.minusSeconds(ON_HOURS * 3600)) }
        val mine = stories(recent).filter { tag in it.tags }
        if (mine.isEmpty()) return "Nothing on ${tag.label} in the ${recent.size} headline${if (recent.size == 1) "" else "s"} from the last $ON_HOURS hours on the phone, Boss."
        val shown = mine.take(ON_MAX)
        val more = if (mine.size > shown.size) " (and ${mine.size - shown.size} older)" else ""
        return "On ${tag.label}, Boss, ${mine.size} stor${if (mine.size == 1) "y" else "ies"} in the last $ON_HOURS hours$more, newest first: " +
            "${numbered(shown, zone, today)} Facts and sources only."
    }

    /** Today's sharp [SharpMove.WINDOW]-minute moves of [m] (the strongest of each run of them), at most [MOVES_MAX], in time order. */
    fun sharpMoves(m: Market, bars: List<Candle>): List<SharpMove.Move> {
        val last = bars.maxByOrNull { it.t } ?: return emptyList()
        val dayDate = last.t.toLocalDate()
        val day = bars.filter { it.t.toLocalDate() == dayDate }.sortedBy { it.t }
        if (day.size <= SharpMove.WINDOW) return emptyList()
        val u = SharpMove.usual(bars, dayDate)
        val runs = ArrayList<SharpMove.Move>()
        var prevI = -10
        for (i in SharpMove.WINDOW until day.size) {
            if (day[i - SharpMove.WINDOW].c <= 0) continue
            val mv = SharpMove.Move(m, day[i - SharpMove.WINDOW].t, day[i].t, day[i - SharpMove.WINDOW].c, day[i].c, u)
            if (!SharpMove.sharp(mv.pct, u)) continue
            val lastRun = runs.lastOrNull()
            if (lastRun != null && i - prevI <= 1 && lastRun.up == mv.up) {
                if (abs(mv.pct) > abs(lastRun.pct)) runs[runs.size - 1] = mv
            } else runs += mv
            prevI = i
        }
        // Overlapping runs (one window's end inside the next's) read as one: the stronger kept.
        val merged = ArrayList<SharpMove.Move>()
        for (mv in runs) {
            val p = merged.lastOrNull()
            if (p != null && mv.from.isBefore(p.to)) { if (abs(mv.pct) > abs(p.pct)) merged[merged.size - 1] = mv } else merged += mv
        }
        return merged.sortedByDescending { abs(it.pct) }.take(MOVES_MAX).sortedBy { it.from }
    }

    /** The stories with a headline published from [SharpMove.NEWS_BEFORE] minutes before [mv] to [SharpMove.NEWS_AFTER] after it. */
    fun around(mv: SharpMove.Move, news: List<Headline>, zone: ZoneId): List<Pair<Story, LocalDateTime>> {
        val near = news.filter { h ->
            val at = h.at?.let { LocalDateTime.ofInstant(it, zone) } ?: return@filter false
            (h.markets.isEmpty() || h.markets.any { it != Market.GOLD }) &&
                !at.isBefore(mv.from.minusMinutes(SharpMove.NEWS_BEFORE)) && !at.isAfter(mv.to.plusMinutes(SharpMove.NEWS_AFTER))
        }
        return stories(near).mapNotNull { s -> s.first?.let { s to LocalDateTime.ofInstant(it, zone) } }.sortedBy { it.second }.take(SharpMove.MAX_NEWS)
    }

    private fun moveLine(mv: SharpMove.Move, near: List<Pair<Story, LocalDateTime>>): String {
        val way = if (mv.up) "rose" else "fell"
        val head = "${hm(mv.from)}-${hm(mv.to)}: ${mv.market.label} $way ${pctAbs(mv.pct)} (${mv.market.price(mv.fromPx)} to ${mv.market.price(mv.toPx)})"
        val window = "${hm(mv.from.minusMinutes(SharpMove.NEWS_BEFORE))}-${hm(mv.to.plusMinutes(SharpMove.NEWS_AFTER))}"
        return if (near.isEmpty()) "$head; no headline on the phone was published $window."
        else "$head; published $window: " + near.joinToString("; ") { (s, t) ->
            "${hm(t)} \"${s.title}\" (${s.sources.joinToString(", ")})"
        } + "."
    }

    private fun moved(m: Market, news: List<Headline>, zone: ZoneId, bars: Map<Market, List<Candle>>): String {
        val b = bars[m].orEmpty()
        if (b.isEmpty()) return "I have no ${m.label} candles on the phone to line the headlines up against, Boss. $TIMING"
        val moves = sharpMoves(m, b)
        if (moves.isNotEmpty()) {
            val n = moves.size
            return "I can only line the news up with the moves, Boss. ${m.label}'s sharp ${SharpMove.WINDOW}-minute move${if (n == 1) "" else "s"} on its last session " +
                "(${pctAbs(SharpMove.MIN_PCT)} or more and ${"%.0f".format(Locale.ENGLISH, SharpMove.TIMES)} times its usual move when known): " +
                moves.joinToString(" ") { moveLine(it, around(it, news, zone)) } + " $TIMING"
        }
        val big = SharpMove.biggest(m, b)
            ?: return "${m.label} has too few candles on its last session to find its moves, Boss. $TIMING"
        return "No sharp move in ${m.label} on its last session, Boss (none of ${pctAbs(SharpMove.MIN_PCT)} or more in ${SharpMove.WINDOW} minutes" +
            (big.usualPct?.let { u -> " and ${"%.0f".format(Locale.ENGLISH, SharpMove.TIMES)} times the usual ${pctAbs(u)}" } ?: "") +
            "). Its biggest ${SharpMove.WINDOW}-minute move, ${moveLine(big, around(big, news, zone))} $TIMING"
    }
}
