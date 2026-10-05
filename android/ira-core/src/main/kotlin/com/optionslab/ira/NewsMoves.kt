package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * News-to-move memory (learning, round 10): for each news theme [NewsDesk] tags (RBI, the Fed, inflation, earnings,
 * banks...), how far the index moved in the [HORIZONS] minutes after the story's first headline time, on the phone's own
 * 1-minute candles - one note per story and theme, a burst of stories on one theme within [EPISODE_MINUTES] minutes
 * counted once. Each theme's record is its newest [LAST] measured notes within [KEEP_DAYS] days, older ones counting
 * less ([HALF_LIFE_DAYS]), said beside how often the index moves that much in any such window on the phone ([usual]).
 *
 * Timing facts, never a cause: a headline before a move does not say it moved the market, the headline may even report
 * the move. It only ever changes what is said - a line when such a story is mentioned ([mention]), the answer to
 * "how does the market react to RBI news?" ([asked], [say]) and a line in the [Learnings] ledger. No forecast, no
 * advice, honest when there are few notes; nothing here acts. Pure: the store lives in the app.
 */
object NewsMoves {
    /** Minutes after the headline that the index's biggest move is measured over. */
    val HORIZONS = listOf(30, 60)
    /** The horizon quoted first. */
    const val QUOTE = 60
    /** A move of at least this much (%, either way, on 1-minute closes) counts as "moved". */
    const val BIG_PCT = 0.3
    /** The themes followed (the indices' own tags are left out: such headlines are often reports of the move itself). */
    val TAGS = listOf(NewsDesk.Tag.RBI, NewsDesk.Tag.FED, NewsDesk.Tag.INFLATION, NewsDesk.Tag.BUDGET, NewsDesk.Tag.EARNINGS, NewsDesk.Tag.FLOWS,
        NewsDesk.Tag.RUPEE, NewsDesk.Tag.BANKS, NewsDesk.Tag.IT, NewsDesk.Tag.AUTO, NewsDesk.Tag.PHARMA, NewsDesk.Tag.METALS, NewsDesk.Tag.ENERGY,
        NewsDesk.Tag.FMCG, NewsDesk.Tag.REALTY)
    /** The indices timed. */
    val MARKETS = listOf(Market.NIFTY, Market.BANKNIFTY)
    /** Each theme's record is its newest this many measured notes. */
    const val LAST = 20
    /** Fewer measured notes than this: "too few to go by". */
    const val FEW = 5
    const val HALF_LIFE_DAYS = 60.0
    const val KEEP_DAYS = 180L
    const val KEEP = 800
    /** One theme's stories this close together are one episode: noted once. */
    const val EPISODE_MINUTES = 60L
    /** The price at the headline is a 1-minute close at most this many minutes before it. */
    const val SLACK = 5L
    /** [usual] needs at least this many windows to say anything. */
    const val USUAL_MIN = 20
    /** [usual] reads the newest this many days of candles. */
    const val USUAL_DAYS = 20

    const val TIMING = "Timing only, not cause: a headline before a move does not say it moved the market."
    const val NOT_FORECAST = "History on this phone, not a forecast - it says nothing about the next one."

    /** A story on [tag] first published at [at] (the market's clock), [market] at [price] then; [moved]: each horizon's biggest move (%, null: not measurable). */
    data class Note(val tag: NewsDesk.Tag, val market: Market, val at: LocalDateTime, val price: Double, val moved: Map<Int, Double?> = emptyMap()) {
        val key: String get() = "${tag.name}|${market.name}|$at"
        val settled: Boolean get() = HORIZONS.all { it in moved }
    }

    private fun lastAtOrBefore(bars: List<Candle>, t: LocalDateTime): Int {
        var lo = 0; var hi = bars.size - 1; var found = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (!bars[mid].t.isAfter(t)) { found = mid; lo = mid + 1 } else hi = mid - 1 }
        return found
    }

    /** The notes for the stories in [news] not yet in [log] whose time has a price on [bars] (1-minute, oldest first). */
    fun candidates(log: List<Note>, news: List<Headline>, bars: Map<Market, List<Candle>>, zone: ZoneId): List<Note> {
        val have = log.toMutableList()
        val out = ArrayList<Note>()
        val stories = NewsDesk.stories(news.filter { it.markets.isEmpty() || it.markets.any { m -> m != Market.GOLD } })
            .mapNotNull { s -> s.first?.let { s to LocalDateTime.ofInstant(it, zone).withSecond(0).withNano(0) } }.sortedBy { it.second }
        for ((s, at) in stories) for (tag in s.tags.filter { it in TAGS }) for (m in MARKETS) {
            if (!m.trading(at)) continue
            if (have.any { it.tag == tag && it.market == m && abs(ChronoUnit.MINUTES.between(it.at, at)) < EPISODE_MINUTES }) continue
            val b = bars[m].orEmpty()
            val i = lastAtOrBefore(b, at)
            if (i < 0) continue
            val c = b[i]
            if (c.t.toLocalDate() != at.toLocalDate() || c.t.isBefore(at.minusMinutes(SLACK)) || !(c.c > 0) || !c.c.isFinite()) continue
            val n = Note(tag, m, at, c.c)
            have += n; out += n
        }
        return out
    }

    /** [log] with each horizon measured on [bars] where it has passed; a horizon past the session's close is not measurable. */
    fun settle(log: List<Note>, bars: Map<Market, List<Candle>>): List<Note> = log.map { n ->
        if (n.settled) n else bars[n.market]?.takeIf { it.isNotEmpty() }?.let { measure(n, it) } ?: n
    }

    private fun measure(n: Note, bars: List<Candle>): Note {
        val last = bars.last().t
        val moved = HashMap(n.moved)
        for (h in HORIZONS) {
            if (h in moved) continue
            val end = n.at.plusMinutes(h.toLong())
            val close = n.market.close?.let { n.at.toLocalDate().atTime(it) }
            if (close != null && end.isAfter(close)) { moved[h] = null; continue }    // the session ends first: never guessed
            // The 1-minute candle that closes at [end] starts a minute before it.
            if (last.isBefore(end.minusMinutes(1))) continue                          // not there yet
            val from = lastAtOrBefore(bars, n.at) + 1
            val to = lastAtOrBefore(bars, end.minusMinutes(1))
            if (from > to) { moved[h] = null; continue }                              // no candles then on the phone
            var big = 0.0
            for (k in from..to) big = maxOf(big, abs(bars[k].c - n.price) / n.price * 100)
            moved[h] = big
        }
        return n.copy(moved = moved)
    }

    fun prune(log: List<Note>, today: LocalDate): List<Note> =
        log.filter { !it.at.toLocalDate().isBefore(today.minusDays(KEEP_DAYS)) }.sortedBy { it.at }.takeLast(KEEP)

    /** [log] with [news]'s new stories noted and every open horizon measured on [bars]. */
    fun update(log: List<Note>, news: List<Headline>, bars: Map<Market, List<Candle>>, zone: ZoneId, today: LocalDate): List<Note> =
        prune(settle(log + candidates(log, news, bars, zone), bars), today)

    // ---- the record ------------------------------------------------------------------------------------------------

    /** [tag]'s record on [market] at [horizon]: the newest [n] measured notes, [big] of them moved [BIG_PCT] or more. */
    data class Record(val tag: NewsDesk.Tag, val market: Market, val horizon: Int, val n: Int, val big: Int,
                      /** Moved that much, older notes counting less (0..1). */ val faded: Double,
                      /** The middle of the biggest moves (%), null with none. */ val median: Double?,
                      /** The newest note's day. */ val newest: LocalDate?) {
        val rate: Double get() = if (n == 0) 0.0 else big.toDouble() / n
    }

    fun record(log: List<Note>, tag: NewsDesk.Tag, market: Market, today: LocalDate, horizon: Int = QUOTE): Record {
        val from = today.minusDays(KEEP_DAYS)
        val seen = log.filter { it.tag == tag && it.market == market && !it.at.toLocalDate().isBefore(from) && it.moved[horizon] != null }
            .sortedBy { it.at }.takeLast(LAST)
        var wSum = 0.0; var wBig = 0.0
        for (n in seen) {
            val age = ChronoUnit.DAYS.between(n.at.toLocalDate(), today).coerceAtLeast(0)
            val w = Math.pow(0.5, age / HALF_LIFE_DAYS)
            wSum += w; if (n.moved[horizon]!! >= BIG_PCT) wBig += w
        }
        val sizes = seen.map { it.moved[horizon]!! }.sorted()
        val median = if (sizes.isEmpty()) null else if (sizes.size % 2 == 1) sizes[sizes.size / 2] else (sizes[sizes.size / 2 - 1] + sizes[sizes.size / 2]) / 2
        return Record(tag, market, horizon, seen.size, seen.count { it.moved[horizon]!! >= BIG_PCT }, if (wSum > 0) wBig / wSum else 0.0, median,
            seen.lastOrNull()?.at?.toLocalDate())
    }

    /**
     * How often [bars]' index moves [BIG_PCT] or more within [horizon] minutes of any quarter-hour in its session, over its
     * newest [USUAL_DAYS] days on the phone (0..1); null with fewer than [USUAL_MIN] windows. The yardstick a theme's record
     * is said beside.
     */
    fun usual(m: Market, bars: List<Candle>, horizon: Int = QUOTE): Double? {
        if (bars.isEmpty()) return null
        // Asked again with the same candles (the market shut, or two questions a minute): kept by them ([BarsKept]).
        if (bars.size > KEEP_UP_TO) return usualNow(m, bars, horizon)
        return usualKept.of(bars, m to horizon) { usualNow(m, bars, horizon) }
    }

    private const val KEEP_UP_TO = 30_000
    private val usualKept = BarsKept<Double?>(8)

    internal fun usualNow(m: Market, bars: List<Candle>, horizon: Int): Double? {
        val close = m.close ?: return null
        val days = bars.map { it.t.toLocalDate() }.distinct().takeLast(USUAL_DAYS).toSet()
        var windows = 0; var big = 0
        val byDay = bars.filter { it.t.toLocalDate() in days }.groupBy { it.t.toLocalDate() }
        for ((d, day) in byDay) {
            val sorted = day.sortedBy { it.t }
            for (i in sorted.indices) {
                val t = sorted[i].t
                if (t.minute % 15 != 0 || !m.trading(t)) continue
                val end = t.plusMinutes(horizon.toLong())
                if (end.isAfter(d.atTime(close)) || sorted.last().t.isBefore(end.minusMinutes(1))) continue
                val p = sorted[i].c
                if (!(p > 0)) continue
                var mv = 0.0; var k = i + 1
                while (k < sorted.size && sorted[k].t.isBefore(end)) { mv = maxOf(mv, abs(sorted[k].c - p) / p * 100); k++ }
                windows++; if (mv >= BIG_PCT) big++
            }
        }
        return if (windows < USUAL_MIN) null else big.toDouble() / windows
    }

    // ---- saying it -------------------------------------------------------------------------------------------------

    private fun span(h: Int) = if (h == 60) "an hour" else "$h minutes"
    private fun pct(x: Double) = "%.1f%%".format(Locale.ENGLISH, x)
    private fun pct2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun times(n: Int) = "$n time${if (n == 1) "" else "s"}"
    fun what(tag: NewsDesk.Tag): String = when (tag) {
        NewsDesk.Tag.FED -> "Fed"
        NewsDesk.Tag.BANKS -> "bank"
        NewsDesk.Tag.BUDGET -> "budget"
        NewsDesk.Tag.RUPEE -> "rupee"
        NewsDesk.Tag.ENERGY -> "oil and energy"
        else -> tag.label
    }

    /** "RBI headlines: Nifty moved 0.3% or more within an hour after 4 of the last 9" (null: none measured yet). */
    fun fact(r: Record): String? = if (r.n == 0) null else
        "${what(r.tag)} headlines: ${r.market.label} moved ${pct(BIG_PCT)} or more within ${span(r.horizon)} after ${r.big} of the last ${r.n}"

    /** The line said beside a story on [tag]: its record on [m] (null: none yet), with [usual] when known. */
    fun line(log: List<Note>, tag: NewsDesk.Tag, m: Market, today: LocalDate, usual: Double? = null): String? {
        if (tag !in TAGS) return null
        val r = record(log, tag, m, today)
        val f = fact(r) ?: return null
        val few = if (r.n < FEW) " - too few to go by" else ""
        val yard = if (usual != null && r.n >= FEW) " (in any hour on this phone it moves that much about ${share(usual)} of the time)" else ""
        return "${what(tag).replaceFirstChar { it.uppercase() }} headlines on this phone: ${r.market.label} moved ${pct(BIG_PCT)} or more within " +
            "${span(r.horizon)} after ${r.big} of the last ${r.n}$yard$few - timing only, not cause."
    }

    /** One or two lines for the stories' [tags] just mentioned (the first two themes with a record), or null. */
    fun mention(log: List<Note>, tags: List<NewsDesk.Tag>, m: Market, today: LocalDate, usual: Double? = null): String? =
        tags.filter { it in TAGS }.distinct().mapNotNull { line(log, it, m, today, usual) }.take(2).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** The add-on to a news-desk answer to [a]: the record of the theme asked about, or of today's top stories' themes. */
    fun forDesk(a: NewsDesk.Ask, news: List<Headline>, log: List<Note>, now: Instant, zone: ZoneId, usual: Double? = null): String? {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        return when (a) {
            is NewsDesk.Ask.On -> mention(log, listOf(a.tag), Market.NIFTY, today, usual)
            is NewsDesk.Ask.Main -> mention(log, NewsDesk.top(news, zone, today).flatMap { it.tags }, Market.NIFTY, today, usual)
            is NewsDesk.Ask.Moved -> null
        }
    }

    /**
     * What was asked: one theme ([tag]), or every theme (null), on [market]; [cause]: asked whether the news moved it ("was it
     * the news that moved Nifty?") - answered with the timing record only, never a cause.
     */
    data class Ask(val tag: NewsDesk.Tag?, val market: Market, val cause: Boolean = false)

    /** Said first when Boss asks whether a headline moved the index: no cause is found, only how often it moved after such news. */
    fun causeHead(m: Market): String = "I can't say the news moved ${m.label}, Boss - only how often it moved after such headlines on this phone."

    /** "How does the market react to RBI news?", "do Fed headlines move Nifty?", "which news moves Nifty most?" */
    fun say(log: List<Note>, a: Ask, today: LocalDate, usual: Double? = null): String =
        if (a.cause) causeHead(a.market) + " " + said(log, a, today, usual) else said(log, a, today, usual)

    private fun said(log: List<Note>, a: Ask, today: LocalDate, usual: Double?): String {
        val m = a.market
        val yard = usual?.let { " In any hour on this phone, ${m.label} moves ${pct(BIG_PCT)} or more about ${share(it)} of the time." } ?: ""
        if (a.tag != null) {
            val r = record(log, a.tag, m, today)
            if (r.n == 0) return "I haven't timed any ${what(a.tag)} headline against ${m.label} on this phone yet, Boss. Each story that comes in while the " +
                "market is open, I note how far ${m.label} moved in the 30 and 60 minutes after it, from the phone's own candles. Ask again once I have. $TIMING"
            val r30 = record(log, a.tag, m, today, 30)
            val head = "${fact(r)!!.replaceFirstChar { it.uppercase() }} on this phone, Boss" +
                (if (r30.n > 0) " (within 30 minutes: ${r30.big} of ${r30.n})" else "") + "."
            if (r.n < FEW) return "$head That's too few to go by yet. $TIMING $NOT_FORECAST"
            val mid = r.median?.let { " The middle of the biggest moves within the hour: ${pct2(it)}." } ?: ""
            val fade = if (abs(r.faded - r.rate) >= 0.1) " Counting the newer ones more, ${share(r.faded)}." else ""
            return "$head$mid$fade$yard $TIMING $NOT_FORECAST"
        }
        val recs = TAGS.map { record(log, it, m, today) }.filter { it.n > 0 }
        if (recs.isEmpty()) return "I haven't timed any headline against ${m.label} on this phone yet, Boss. For each story on RBI, the Fed, inflation, earnings, " +
            "banks and the like that comes in while the market is open, I note how far ${m.label} moved in the hour after it. Ask again once I have. $TIMING"
        val enough = recs.filter { it.n >= FEW }.sortedWith(compareByDescending<Record> { it.rate }.thenByDescending { it.n })
        val few = recs.filter { it.n < FEW }.sortedByDescending { it.n }
        fun one(r: Record) = "${what(r.tag)} ${r.big} of ${r.n}"
        return "How often ${m.label} moved ${pct(BIG_PCT)} or more within an hour after a headline on this phone, Boss: " +
            (if (enough.isEmpty()) "" else "by theme, most often first - " + enough.take(6).joinToString("; ") { one(it) } + ". ") +
            (if (few.isEmpty()) "" else "Too few to go by yet: " + few.take(5).joinToString("; ") { one(it) } + ".") +
            "$yard $TIMING $NOT_FORECAST"
    }

    // ---- what was asked --------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "

    private val ASK_TAGS: List<Pair<NewsDesk.Tag, Regex>> = listOf(
        NewsDesk.Tag.RBI to Regex(" (rbi|reserve bank|repo rate|rate decision|rate decisions|monetary policy|mpc|rbi policy) "),
        NewsDesk.Tag.FED to Regex(" (fed|fomc|federal reserve|us fed|powell) "),
        NewsDesk.Tag.INFLATION to Regex(" (inflation|cpi|wpi) "),
        NewsDesk.Tag.BUDGET to Regex(" (budget|gst|fiscal deficit) "),
        NewsDesk.Tag.EARNINGS to Regex(" (earnings|results|quarterly results) "),
        NewsDesk.Tag.FLOWS to Regex(" (fii|fiis|fpi|fpis|dii|diis|flows|fii flows|foreign investors) "),
        NewsDesk.Tag.RUPEE to Regex(" (rupee|inr|currency) "),
        NewsDesk.Tag.ENERGY to Regex(" (oil|crude|crude oil|energy) "),
        NewsDesk.Tag.IT to Regex(" (it stocks|it sector|it companies|tech stocks|infosys|tcs) "),
        NewsDesk.Tag.AUTO to Regex(" (auto|autos|auto sector|auto sales) "),
        NewsDesk.Tag.PHARMA to Regex(" (pharma|pharma stocks|pharmaceuticals) "),
        NewsDesk.Tag.METALS to Regex(" (metal|metals|steel) "),
        NewsDesk.Tag.FMCG to Regex(" (fmcg|consumer goods) "),
        NewsDesk.Tag.REALTY to Regex(" (realty|real estate|property) "),
        NewsDesk.Tag.BANKS to Regex(" (bank|banks|banking|lenders|bank stocks) "),
    )

    private const val MKT = "(the )?(market|markets|indian market|nifty|nifty 50|banknifty|bank nifty|nifty bank|index|indices|sensex|stock market)"
    private const val NEWSY = "(news|headline|headlines|announcement|announcements|data|decision|decisions|policy|results|numbers|print|prints|stories|story)"
    private const val OFTEN = "(usually |typically |normally |really |actually |even |tend to |tends to |generally )?"
    private val SHAPES = listOf(
        // "how does the market react to RBI news?", "what does Nifty do after Fed headlines?"
        Regex(" (how|what) (does|do|did|has|have) $MKT $OFTEN(react|respond|do|move|behave|reacted|responded|moved|behaved|done) (to|after|on|around|when|with) "),
        // "do Fed headlines move Nifty?", "does RBI news affect the market?"
        Regex(" (do|does|did|can) .*$NEWSY $OFTEN(move|shake|hit|affect|impact|rattle|swing|jolt) $MKT "),
        // "does Nifty move on RBI news?", "does the market react to inflation data?"
        Regex(" (do|does|did) $MKT $OFTEN(move|react|respond|swing) (on|to|after|with|around|when) "),
        // "market reaction to RBI news", "Nifty's reaction after Fed headlines"
        Regex(" $MKT( s)? (reaction|reactions|response) (to|after|on) "),
        // "how much does Nifty move after RBI news?"
        Regex(" how (much|far|often) (does|do|did) $MKT $OFTEN(move|swing|react) (on|to|after|around|when) "),
        // Hinglish (round 9): "RBI news pe Nifty kaise react karta hai", "Fed ki news se market hilta hai kya".
        Regex(" (news|khabar|khabron|headline|headlines|announcement|policy|data) (pe|par|ke baad|aane ke baad|aane par|se) $MKT (kaise |kitna |kya )?" +
            "(react|move|respond|hilta|hilti|hil jata|girta|chadhta|badalta)( karta| karti| karte)?( hai| hain)? "),
    )
    private val GENERAL = Regex(" (which|what) (kind of |kinds of |type of |types of |sort of )?(news|headlines) $OFTEN(moves|shakes|hits|affects) $MKT |" +
        " (which|what) (kind of |kinds of |type of |types of |sort of )?(news|headlines) (usually|typically|tend to|tends to) (move|moves|shake|hit|affect) $MKT |" +
        " news to move (record|memory) | (your )?news (move|moves) (record|memory) ")
    // Forecasts, advice, Boss's own book, one day's moves (the news desk's and the sharp-move readers').
    private val NOT = Regex(" (will|would|going to|gonna|next|tomorrow|today|todays|yesterday|this morning|right now|predict|prediction|forecast|should|shall|buy|sell|" +
        "trade|trading|enter|exit|short|long|i|me|my|mine|we|our) ")
    private val BANK_INDEX = Regex(" (bank nifty|banknifty|nifty bank) ")
    // "Was it the news that moved Nifty?", "was it RBI news that moved the market?", "kya news se nifty hila?" (round 9): asked of
    // a cause, answered with timing only. (Today's own moves stay the news desk's: "today" is in [NOT].)
    private val CAUSE = Regex(" (was|is|were) it (the |a |some |any )?(\\w+ )?(news|headline|headlines|announcement|policy|data|results) (that |which )?" +
        "(moved|moves|moving|shook|shakes|hit|hits|drove|drives|pushed|pushes|caused|dragged|lifted|sank|knocked) $MKT|" +
        " (kya )?(news|khabar|khabron|headline|headlines) (se|ki wajah se|ke karan|ke kaaran) $MKT (hila|gira|chadha|badha|upar gaya|neeche gaya|move hua|move kiya)")

    /** The theme and index asked about, or null. Facts only: a forecast, advice, Boss's own book or one day's moves are not this. */
    fun asked(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        val market = if (BANK_INDEX.containsMatchIn(t)) Market.BANKNIFTY else Market.NIFTY
        if (CAUSE.containsMatchIn(t)) return Ask(ASK_TAGS.firstOrNull { it.second.containsMatchIn(t.replace(BANK_INDEX, " ")) }?.first, market, cause = true)
        if (GENERAL.containsMatchIn(t)) return Ask(null, market)
        if (SHAPES.none { it.containsMatchIn(t) }) return null
        val bare = t.replace(BANK_INDEX, " ")
        val tag = ASK_TAGS.firstOrNull { it.second.containsMatchIn(bare) }?.first ?: return null
        return Ask(tag, market)
    }

    // ---- saved as plain text: one note a line (theme, index, time, price, then each horizon's move) -------------------

    fun save(log: List<Note>): String = log.joinToString("\n") { n ->
        listOf(n.tag.name, n.market.name, n.at, "%.4f".format(Locale.ENGLISH, n.price),
            HORIZONS.joinToString(",") { h -> if (h !in n.moved) "-" else n.moved[h]?.let { "%.4f".format(Locale.ENGLISH, it) } ?: "x" }).joinToString("|")
    }

    fun load(text: String): List<Note> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('|')
            val w = p[4].split(',')
            Note(NewsDesk.Tag.valueOf(p[0]), Market.valueOf(p[1]), LocalDateTime.parse(p[2]), p[3].toDouble(),
                HORIZONS.indices.mapNotNull { i -> w.getOrNull(i)?.takeIf { it != "-" }?.let { HORIZONS[i] to (if (it == "x") null else it.toDouble()) } }.toMap())
        }.getOrNull()
    }.toList()
}
