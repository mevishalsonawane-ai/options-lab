package com.optionslab.ira

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * "Brief me like a co-pilot" (reasoning, round 8, 2026-10-05): "what matters right now?", "brief me" - instead of listing
 * everything, Jarvis gathers candidate facts from every reader he has ([NewsDesk.sharpMoves], [Structure], [ChainIntel],
 * [NewsDesk] stories, [Consistency] tensions and clashes, [DataAge], Boss's open positions and loss limits, the calendar
 * ([Events]) and his own record in conditions like now ([SelfCalibration])) and RANKS them by a transparent score:
 *
 *  - recency ([recency]): how fresh the fact is (or that it is on the calendar today or tomorrow), 0..3;
 *  - size ([Fact.size]): how big against its own usual (a move against the usual 10-minute move, a story by its source
 *    count, a loss against the daily limit), 0..4;
 *  - relevance: it touches a market Boss holds an open position in (3), or it is about his own account (2);
 *  - conflict: facts pulling different ways, or Boss's words against today (2);
 *  - the fact's own weight ([Fact.weight]: stale data first, because it qualifies everything else).
 *
 * The top [MIN_SAID]..[MAX_SAID] are said, each with a one-line reason why it ranked where it did ("ranked first: the
 * biggest move against usual today"). Facts only - never advice, never a forecast, never a verdict: a fact whose words
 * read as advice is dropped ([advisory]). Boss's account (positions, limits, his words, his own calendar entries) only on
 * an unlocked phone. Nothing here acts. Pure.
 */
object CoPilot {
    /** What a fact is; [base] its own weight, [noun] how it is named in a reason. */
    enum class Kind(val base: Double, val noun: String) {
        MOVE(1.0, "move"), STRUCTURE(1.0, "structure"), CHAIN(0.5, "option chain fact"), NEWS(0.5, "story"),
        CONFLICT(1.0, "conflict"), DATA(1.0, "data note"), POSITION(1.0, "position"), LIMIT(1.0, "limit"),
        EVENT(1.0, "event"), RECORD(0.5, "record")
    }

    /**
     * One candidate fact. [text] one or two sentences, facts only; [at] when it happened or was read (null: a standing
     * fact); [due] the day an [Kind.EVENT] falls on; [size] 0..[MAX_SIZE], how big against its usual, with [sizeWhy]
     * saying so ("4.2 times its usual 10-minute move"); [markets] what it touches; [conflict] facts pulling apart;
     * [account] Boss's own (never said on a locked phone); [weight] the fact's own weight (default its kind's).
     */
    data class Fact(
        val kind: Kind,
        val text: String,
        val at: LocalDateTime? = null,
        val due: LocalDate? = null,
        val size: Double = 0.0,
        val sizeWhy: String? = null,
        val markets: Set<Market> = emptySet(),
        val conflict: Boolean = false,
        val account: Boolean = false,
        val weight: Double = kind.base,
    )

    /** The parts of a fact's score, each said plainly: nothing hidden. */
    enum class Part { RELEVANCE, CONFLICT, SIZE, RECENCY, WEIGHT }

    /** A ranked fact: its score's parts and the [total]. */
    data class Ranked(val fact: Fact, val recency: Double, val size: Double, val relevance: Double, val conflict: Double, val weight: Double) {
        val total: Double get() = recency + size + relevance + conflict + weight
        fun part(p: Part): Double = when (p) {
            Part.RELEVANCE -> relevance; Part.CONFLICT -> conflict; Part.SIZE -> size; Part.RECENCY -> recency; Part.WEIGHT -> weight
        }
        /** The part that put it where it is: the biggest (ties: relevance, conflict, size, recency, weight). */
        val lead: Part get() = Part.entries.maxWith(compareBy<Part> { part(it) }.thenByDescending { it.ordinal })
    }

    /** At most this many facts are said. */
    const val MAX_SAID = 5
    /** At least this many are said when there are that many. */
    const val MIN_SAID = 3
    /** A fourth or fifth fact is said only when it scores at least this much. */
    const val EXTRA_MIN = 3.5
    /** At most this many facts of one kind are said. */
    const val PER_KIND = 2
    /** Size is capped here. */
    const val MAX_SIZE = 4.0
    /** Touching a market Boss holds; about his own account; facts pulling apart. */
    const val HELD = 3.0
    const val OWN = 2.0
    const val CONFLICTS = 2.0
    /** A loss this share of the daily limit or more is a fact worth ranking. */
    const val LIMIT_NEAR = 0.5
    /** Stale prices outweigh everything they qualify. */
    const val STALE_WEIGHT = 3.0
    /** News stories offered for ranking. */
    const val STORIES = 2

    const val YOURS = "Facts only, not advice - the decision is yours, Boss."
    const val LOCKED_NOTE = "Your positions, limits and own words are left out while the phone is locked."
    const val QUIET = "Nothing stands out from the rest right now, Boss: no sharp moves, conflicts, data problems or events today that I can see."
    const val HOW = "I ranked them by how recent each is, how big against its usual, whether it touches your open positions and whether facts pull different ways."

    private val ORDINAL = listOf("first", "second", "third", "fourth", "fifth")

    private fun k(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun one(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun two(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun rupees(x: Double) = "%+,.0f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun words(s: String) = " " + s.lowercase(Locale.ENGLISH).replace("'", " ").replace("’", " ").replace("-", " ")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please |ok |okay )*"
    private val ASKED = Regex(
        "^ $LEAD(what|whats|what s|what is|what are) (really )?(matters?( most)?|counts?( most)?|stands out|the top (things|facts|\\d|three|five)|the (most )?important (things|facts)|most important)" +
            "( (right now|now|today|so far|at the moment|this morning|this afternoon))?( (for me|to me))?( boss| jarvis| please)? $|" +
        "^ $LEAD(brief|briefing|brief me|give me a brief(ing)?|update me) (like a |as a |as my |as )?co ?pilot( (right now|now|today))?( boss| jarvis| please)? $|" +
        "^ $LEAD(co ?pilot) (brief(ing)?|mode brief|update)( (right now|now|today))?( boss| jarvis| please)? $|" +
        "^ $LEAD(rank|prioriti[sz]e) (what matters|the facts|today s facts|everything|it all)( (right now|now|today|for me))?( boss| jarvis| please)? $|" +
        "^ $LEAD(top (3|5|three|five) (things|facts)|top things)( (right now|now|today))?( boss| jarvis| please)? $|" +
        // Hinglish (round 9): "abhi sabse important kya hai", "kya matter karta hai abhi", "aaj sabse zaroori kya hai".
        "^ $LEAD(abhi |aaj |is waqt )?(sabse )?(important|zaroori|zaruri|zaroori baat|important baat|important cheez) kya (hai|hain)( abhi| aaj| is waqt)?( boss| jarvis)? $|" +
        "^ $LEAD(abhi |aaj )?kya (matter|important) (karta|kar raha|hai)( hai)?( abhi| aaj)?( boss| jarvis)? $"
    )

    /** "What matters right now?", "brief me like a co-pilot", "rank what matters" - and every wording of [Briefing]. */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(words(text)) || Briefing.asked(text)

    // ---- the readers' facts as candidates ----------------------------------------------------------------------

    /** Today's sharp 10-minute moves of [m] ([NewsDesk.sharpMoves]), each sized against its usual 10-minute move. */
    fun moves(m: Market, bars: List<Candle>): List<Fact> = NewsDesk.sharpMoves(m, bars).map { mv ->
        val times = mv.usualPct?.takeIf { it > 0 }?.let { abs(mv.pct) / it }
        val way = if (mv.up) "rose" else "fell"
        Fact(Kind.MOVE, "${m.label} $way ${k(abs(mv.points))} points (${two(abs(mv.pct))}%) in the ${mv.minutes} minutes to ${hm(mv.to)}" +
            (times?.let { ", ${one(it)} times its usual ${SharpMove.WINDOW}-minute move" } ?: "") + ".",
            at = mv.to, size = times?.let { (it - 1).coerceIn(0.0, MAX_SIZE) } ?: 0.0,
            sizeWhy = times?.let { "${one(it)} times its usual ${SharpMove.WINDOW}-minute move" }, markets = setOf(m))
    }

    /** Today's structure of one index so far ([Structure.read]): trend-like or range-bound, with its numbers. */
    fun structure(r: Structure.Read?): Fact? {
        r ?: return null
        val kind = r.dayKind ?: return null
        val name = r.market.label
        val share = "${(r.share * 100).toInt()}% of the day's ${k(r.range)}-point range"
        val text = when (kind) {
            1 -> "$name's day is trend-like up so far: ${k(abs(r.net))} points above the open, $share."
            -1 -> "$name's day is trend-like down so far: ${k(abs(r.net))} points below the open, $share."
            else -> "$name is range-bound so far: the net move from the open is only $share."
        }
        return Fact(Kind.STRUCTURE, text, at = r.at, markets = setOf(r.market), weight = if (kind == 0) Kind.STRUCTURE.base / 2 else Kind.STRUCTURE.base)
    }

    /** The option chain's main facts ([ChainIntel.caseFacts]: the straddle's implied move, the biggest OI), with its time. */
    fun chain(r: ChainIntel.Read?, today: LocalDate): Fact? {
        r ?: return null
        val said = ChainIntel.caseFacts(r, today).take(2)
        if (said.isEmpty()) return null
        val m = Market.entries.firstOrNull { it.name.equals(r.underlying, true) }
        return Fact(Kind.CHAIN, said.joinToString(" "), at = r.at, markets = setOfNotNull(m))
    }

    private val TAG_MARKET = mapOf(NewsDesk.Tag.NIFTY to Market.NIFTY, NewsDesk.Tag.BANKNIFTY to Market.BANKNIFTY, NewsDesk.Tag.BANKS to Market.BANKNIFTY,
        NewsDesk.Tag.FINNIFTY to Market.FINNIFTY, NewsDesk.Tag.SENSEX to Market.SENSEX, NewsDesk.Tag.VIX to Market.VIX, NewsDesk.Tag.GOLD to Market.GOLD)

    /** Today's most-carried stories ([NewsDesk.stories]), at most [STORIES], sized by how many outlets carried each. */
    fun news(news: List<Headline>, now: Instant, zone: ZoneId): List<Fact> {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        val todays = news.filter { h -> h.at != null && LocalDateTime.ofInstant(h.at, zone).toLocalDate() == today }
        return NewsDesk.stories(todays).sortedWith(compareByDescending<NewsDesk.Story> { it.sourceCount }.thenByDescending { it.latest ?: Instant.EPOCH })
            .take(STORIES).map { s ->
                val at = s.latest?.let { LocalDateTime.ofInstant(it, zone) }
                val by = if (s.sourceCount == 1) "1 source" else "${s.sourceCount} sources"
                Fact(Kind.NEWS, "News: \"${s.title}\" - $by" + (at?.let { ", the latest at ${hm(it)}" } ?: "") + ".",
                    at = at, size = (s.sourceCount - 1.0).coerceIn(0.0, MAX_SIZE), sizeWhy = "carried by $by",
                    markets = (s.tags.mapNotNull { TAG_MARKET[it] } + s.headlines.flatMap { it.markets }).toSet())
            }
    }

    /** Market facts pulling different ways ([Consistency.tensions]) on [m], read at [at]. */
    fun tensions(lines: List<String>, m: Market, at: LocalDateTime): List<Fact> =
        lines.map { Fact(Kind.CONFLICT, it, at = at, markets = setOf(m), conflict = true) }

    /** His own numbers disagreeing ([Consistency.priceCheck]), shortened, with the source he goes by. */
    fun mismatch(x: Consistency.Mismatch?): Fact? = x?.let {
        Fact(Kind.DATA, "My own numbers disagree: ${it.other.name} at ${hm(it.other.at)} is ${two(it.other.price)}, but my ${it.market.label} candle for " +
            "${hm(it.candle.t)} ran ${two(it.candle.l)} to ${two(it.candle.h)} - I go by the candles.", at = it.other.at, markets = setOf(it.market), conflict = true)
    }

    /** Boss's words against today ([Consistency.clashes]): his own account. */
    fun clashes(list: List<Consistency.Clash>): List<Fact> = list.map {
        Fact(Kind.CONFLICT, it.text.removePrefix("Boss, ").removeSuffix(" ${Consistency.GENTLE}").replaceFirstChar { c -> c.uppercase() },
            conflict = true, account = true)
    }

    /** How old his data is ([DataAge]): only what is not fresh; stale prices weigh the most. */
    fun data(checks: List<DataAge.Check>, now: LocalDateTime): List<Fact> = checks.filter { it.level != DataAge.Level.FRESH }.mapNotNull { c ->
        val said = DataAge.note(c, now, withheld = false) ?: return@mapNotNull null
        Fact(Kind.DATA, said, weight = if (c.source == DataAge.Source.PRICES && c.level == DataAge.Level.STALE) STALE_WEIGHT
            else if (c.level == DataAge.Level.STALE) Kind.DATA.base * 2 else Kind.DATA.base)
    }

    /** Boss's open positions ([Exposure.Leg]), one fact per index: how many legs and what they show now. His account. */
    fun positions(legs: List<Exposure.Leg>): List<Fact> = legs.filter { it.qty != 0 }.groupBy { l ->
        Market.entries.firstOrNull { it.name.equals(l.underlying, true) }
    }.mapNotNull { (m, ls) ->
        m ?: return@mapNotNull null
        Fact(Kind.POSITION, "You hold ${ls.size} open ${m.label} leg${if (ls.size == 1) "" else "s"}, ${rupees(ls.sumOf { it.pnl })} rupees on them now.",
            markets = setOf(m), account = true)
    }

    /** A day's loss of [LIMIT_NEAR] of its daily limit or more ([limits] and [dayPnl] by where: "Zerodha", "Paper"). His account. */
    fun limits(limits: Map<String, Double>, dayPnl: Map<String, Double>): List<Fact> = dayPnl.mapNotNull { (where, pnl) ->
        val limit = limits[where]?.takeIf { it > 0 } ?: return@mapNotNull null
        if (pnl >= 0) return@mapNotNull null
        val share = -pnl / limit
        if (share < LIMIT_NEAR) return@mapNotNull null
        Fact(Kind.LIMIT, "Your $where day is at ${rupees(pnl)} rupees, ${(share * 100).toInt()}% of your ${k(limit)}-rupee daily loss limit.",
            size = (share * MAX_SIZE).coerceIn(0.0, MAX_SIZE), sizeWhy = "${(share * 100).toInt()}% of your daily loss limit", account = true)
    }

    /** Today's and tomorrow's events ([Events.line]); one Boss added himself is his own account. */
    fun events(list: List<Events.Event>, today: LocalDate): List<Fact> = list.filter { it.day == today || it.day == today.plusDays(1) }.map { e ->
        Fact(Kind.EVENT, Events.line(e, today), due = e.day, markets = Market.mentioned(e.name).toSet(), account = e.owner)
    }

    /**
     * His own record in conditions like now ([SelfCalibration]: the time of day, a sideways or volatile market, how dear
     * options are): a clearly poor or a clearly good record, said only - it never changes what anyone may do.
     */
    fun record(outcomes: List<SelfCalibration.Outcome>, at: LocalDateTime, regime: Regime.Kind? = null, ivRank: Double? = null): Fact? {
        if (outcomes.isEmpty()) return null
        val now = SelfCalibration.tags(SelfCalibration.Conditions(at, Market.NIFTY, true, "", regime, ivRank))
            .filter { it.dim == SelfCalibration.Dim.TIME || it.dim == SelfCalibration.Dim.OPTIONS ||
                (it.dim == SelfCalibration.Dim.TREND && (it.key == "SIDEWAYS" || it.key == "VOLATILE")) }
        val here = SelfCalibration.slices(outcomes, at.toLocalDate()).filter { s -> s.tags.isNotEmpty() && now.containsAll(s.tags) }
        here.filter { it.poor }.sortedWith(compareBy({ it.rate }, { it.net })).firstOrNull()?.let {
            return Fact(Kind.RECORD, "My own paper ideas ${it.what("").trim()} have a clearly poor record (${it.record()}).")
        }
        return here.filter { it.strong }.sortedWith(compareByDescending<SelfCalibration.Slice> { it.rate }.thenByDescending { it.net }).firstOrNull()?.let {
            Fact(Kind.RECORD, "My own paper ideas ${it.what("").trim()} have held up (${it.record()}).")
        }
    }

    // ---- the score ----------------------------------------------------------------------------------------------

    /** How fresh [f] is at [now]: 3 within 15 minutes, 2 within the hour, 1 within 3 hours, else 0.5; an event 2 today, 1 tomorrow. */
    fun recency(f: Fact, now: LocalDateTime): Double {
        f.due?.let { d -> return when (d) { now.toLocalDate() -> 2.0; now.toLocalDate().plusDays(1) -> 1.0; else -> 0.0 } }
        val at = f.at ?: return 0.0
        if (at.toLocalDate() != now.toLocalDate()) return 0.0
        val mins = Duration.between(at, now).toMinutes().coerceAtLeast(0)
        return when { mins <= 15 -> 3.0; mins <= 60 -> 2.0; mins <= 180 -> 1.0; else -> 0.5 }
    }

    private val QUOTED = Regex("\"[^\"]*\"")
    private val ADVICE = Regex("\\b(you should|should you|you must|i would|i'd|recommend\\w*|suggest\\w*|advis\\w*|go long|go short|" +
        "book (?:profit|profits)|take profits?|cut (?:your|the) loss\\w*|average down|square off|time to (?:buy|sell|enter|exit|trade)|" +
        "(?:buy|sell) (?:now|on dips|on rallies|the dip|calls|puts|these|this)|(?:stocks|shares|options) to (?:buy|sell)|top picks?|must buy|target price|stop ?loss)\\b",
        RegexOption.IGNORE_CASE)

    /** True when [f]'s words read as advice (Boss's own quoted words aside): such a fact is never ranked. */
    fun advisory(f: Fact): Boolean = ADVICE.containsMatchIn(if (f.account) f.text.replace(QUOTED, " ") else f.text)

    /**
     * Every candidate scored and ranked, highest first ([MAX_SAID] at most, [PER_KIND] of a kind; a fourth and fifth only
     * at [EXTRA_MIN] or more). [held]: the markets Boss holds open positions in. [locked]: his account left out entirely.
     * Ties: the newer first, then by kind. Advice-shaped facts are dropped.
     */
    fun rank(facts: List<Fact>, held: Set<Market>, now: LocalDateTime, locked: Boolean): List<Ranked> {
        val mine = if (locked) emptySet() else held
        val scored = facts.asSequence().filter { !(locked && it.account) }.filter { it.text.isNotBlank() && !advisory(it) }
            .distinctBy { it.text }
            .map { f ->
                Ranked(f, recency = recency(f, now), size = f.size.coerceIn(0.0, MAX_SIZE),
                    relevance = when { f.markets.any { it in mine } -> HELD; f.account -> OWN; else -> 0.0 },
                    conflict = if (f.conflict) CONFLICTS else 0.0, weight = f.weight)
            }
            .sortedWith(compareByDescending<Ranked> { it.total }.thenByDescending { it.fact.at ?: it.fact.due?.atStartOfDay() ?: LocalDateTime.MIN }
                .thenBy { it.fact.kind.ordinal })
            .toList()
        val out = ArrayList<Ranked>()
        val perKind = HashMap<Kind, Int>()
        for (r in scored) {
            if (out.size >= MAX_SAID) break
            if ((perKind[r.fact.kind] ?: 0) >= PER_KIND) continue
            if (out.size >= MIN_SAID && r.total < EXTRA_MIN) break
            out += r; perKind[r.fact.kind] = (perKind[r.fact.kind] ?: 0) + 1
        }
        return out
    }

    // ---- said ---------------------------------------------------------------------------------------------------

    /** Why [r] ranked where it did, in one line ([all]: the facts said, to tell "the biggest" and "the newest"). */
    fun reason(r: Ranked, all: List<Ranked>, held: Set<Market>, now: LocalDateTime): String {
        val f = r.fact
        return when (r.lead) {
            Part.RELEVANCE -> when {
                f.account && f.kind == Kind.POSITION -> "it is your own open position"
                f.account && f.kind == Kind.LIMIT -> "it is your own loss limit"
                f.account && f.kind == Kind.CONFLICT -> "it is your own words against today"
                f.account && f.kind == Kind.EVENT -> "it is on your own calendar"
                f.account -> "it is about your own account"
                else -> "it touches your open ${f.markets.filter { it in held }.joinToString(" and ") { it.label }} position" +
                    (if (f.markets.count { it in held } > 1) "s" else "")
            }
            Part.CONFLICT -> if (f.kind == Kind.DATA) "my own numbers disagree, so the figures built on them are less sure" else "facts pull different ways here"
            Part.SIZE -> {
                val biggest = all.filter { it.fact.kind == f.kind }.maxOf { it.size } <= r.size && all.count { it.fact.kind == f.kind } >= 1
                val why = f.sizeWhy?.let { " ($it)" } ?: ""
                when {
                    biggest && f.kind == Kind.MOVE -> "the biggest move against usual today$why"
                    biggest && f.kind == Kind.NEWS -> "the most widely carried story today$why"
                    f.kind == Kind.LIMIT -> "your loss is near your limit$why"
                    else -> "big against its usual$why"
                }
            }
            Part.RECENCY -> when {
                f.due != null -> if (f.due == now.toLocalDate()) "it is on the calendar today" else "it is on the calendar tomorrow"
                f.at != null && all.mapNotNull { it.fact.at }.maxOrNull() == f.at ->
                    "the newest fact I have (${Duration.between(f.at, now).toMinutes().coerceAtLeast(0)} minutes ago)"
                f.at != null -> "it is fresh (${Duration.between(f.at, now).toMinutes().coerceAtLeast(0)} minutes ago)"
                else -> "it is fresh"
            }
            Part.WEIGHT -> when (f.kind) {
                Kind.DATA -> "it decides how far my other numbers can be trusted"
                Kind.RECORD -> "it is my own record in conditions like now"
                else -> "it is one of today's standing ${f.kind.noun} facts"
            }
        }
    }

    /**
     * The brief: [head] (the indices' line from [Briefing.say], when there is one), then the ranked facts, each with why it
     * ranked there ("Ranked first: the biggest move against usual today."), how they were ranked, and that the decision is
     * Boss's. [considered]: how many candidates were weighed.
     */
    fun say(head: String?, ranked: List<Ranked>, held: Set<Market>, now: LocalDateTime, locked: Boolean, considered: Int = ranked.size): String {
        val parts = ArrayList<String>()
        head?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (ranked.isEmpty()) {
            parts += QUIET
        } else {
            parts += "What matters most right now, Boss, in order:"
            ranked.forEachIndexed { i, r ->
                val t = r.fact.text.trim().let { if (it.endsWith(".") || it.endsWith("?") || it.endsWith("!")) it else "$it." }
                parts += "${ORDINAL[i].replaceFirstChar { it.uppercase() }}: $t Ranked ${ORDINAL[i]}: ${reason(r, ranked, if (locked) emptySet() else held, now)}."
            }
            parts += HOW.replace("ranked them", "ranked ${if (considered > ranked.size) "these from $considered facts" else "them"}")
        }
        if (locked) parts += LOCKED_NOTE
        parts += YOURS
        return parts.joinToString(" ")
    }

    /** Ranked and said in one go. */
    fun brief(head: String?, facts: List<Fact>, held: Set<Market>, now: LocalDateTime, locked: Boolean): String {
        val considered = facts.count { !(locked && it.account) }
        return say(head, rank(facts, held, now, locked), held, now, locked, considered)
    }
}
