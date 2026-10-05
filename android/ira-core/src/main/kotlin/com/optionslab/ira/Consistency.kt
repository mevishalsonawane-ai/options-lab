package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Jarvis noticing contradictions (reasoning, round 7, 2026-10-05): before he gives facts he checks whether they pull
 * against each other, and says so plainly instead of smoothing it over.
 *
 *  - [tensions]: market facts that point different ways ("Nifty's structure is trend-like up so far, but the chain's
 *    call writing is heaviest just above, at the 24,700 strike - both are facts; they point different ways"). Market
 *    data, so said on a locked phone too.
 *  - [clashes]: Boss's own words against what happened today - a rule he asked Jarvis to remember ("I don't trade on
 *    Fridays", "no trades before 9:30", "I don't trade BankNifty", "skip expiry days") or his "no more than N trades a
 *    day" goal, against his own trades today. His account: never on a locked phone; unasked, each is said once a day,
 *    gently, words only.
 *  - [priceCheck]: his own data disagreeing with itself - the option chain's spot against the 1-minute candle of the
 *    same minute - beyond [DISAGREE_PCT]: he says which source he goes by and why.
 *
 * Never advice, never a forecast, never a verdict on what Boss should do: the facts and that they conflict, the decision
 * his. Nothing here acts, blocks or changes anything. Pure.
 */
object Consistency {
    /** Where a fact comes from; [weight]: which is said first when two conflict. */
    enum class Source(val weight: Int) { STRUCTURE(3), CALL_WRITING(2), PUT_WRITING(2), PRIOR_CLOSE(1), VIX(1) }

    /** One market fact with the way it points: +1 up, -1 down. [text] reads after "but" (lower-case start). */
    data class Lean(val source: Source, val sign: Int, val text: String)

    /** The biggest call (put) OI within this share of spot above (below) it is "just above" ("just below"). */
    const val NEAR_PCT = 1.0
    /** India VIX moved at least this much on the day (percent) points one way. */
    const val VIX_PCT = 5.0
    /** At most this many tensions are said. */
    const val MAX_TENSIONS = 2
    /** Two price sources further apart than this (percent, beyond the candle's own range) disagree. */
    const val DISAGREE_PCT = 0.2
    /** A candle this many minutes from the other source's time is still "the same minute". */
    const val SAME_MINUTES = 2L

    const val BOTH = "both are facts; they point different ways"
    const val GENTLE = "I'm only pointing it out, Boss - what you do is your call."
    const val LOCKED_NOTE = "Your own rules and today's trades are left out while the phone is locked."
    const val NONE = "I find nothing pulling against itself right now, Boss: the market facts I have point the same way or say nothing either way, and my own numbers agree."

    private fun k(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun one(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun hm(minute: Int) = "%02d:%02d".format(Locale.ENGLISH, minute / 60, minute % 60)

    // ---- (a) market facts pulling different ways ----------------------------------------------------------------

    /**
     * The market facts that point a way: today's structure ([s]: trend-like, or higher highs and higher lows), the side
     * of the prior close, where the biggest call and put OI sit against spot in [chain] (only when it is the same index
     * as [s] and from the same day), and India VIX's move on the day ([vixChangePct]).
     */
    fun leans(s: Structure.Read?, chain: ChainIntel.Read? = null, vixChangePct: Double? = null): List<Lean> {
        val out = ArrayList<Lean>()
        if (s != null) {
            val name = s.market.label
            val shape = s.fifteen.takeIf { it.label != null } ?: s.five
            val way = if (shape.highWay != null && shape.highWay == shape.lowWay) shape.highWay else 0
            when {
                s.dayKind == 1 -> out += Lean(Source.STRUCTURE, 1, "$name's structure is trend-like up so far (net move ${(s.share * 100).toInt()}% of the day's range)")
                s.dayKind == -1 -> out += Lean(Source.STRUCTURE, -1, "$name's structure is trend-like down so far (net move ${(s.share * 100).toInt()}% of the day's range)")
                s.dayKind == 0 -> {}
                way == 1 -> out += Lean(Source.STRUCTURE, 1, "$name is making higher highs and higher lows on ${shape.minutes}-minute candles")
                way == -1 -> out += Lean(Source.STRUCTURE, -1, "$name is making lower highs and lower lows on ${shape.minutes}-minute candles")
            }
            s.prevSide?.let { p ->
                if (p.side != 0) out += Lean(Source.PRIOR_CLOSE, p.side, "$name is ${if (p.side > 0) "above" else "below"} the prior session's close of ${n(p.level)}")
            }
        }
        val c = chain?.takeIf { r -> s == null || (r.underlying.equals(s.market.name, true) && r.at.toLocalDate() == s.at.toLocalDate()) }
        if (c != null && c.spot > 0) {
            val at = " (the chain at ${hm(c.at)})"
            c.strikes.filter { it.ceOi > 0 }.maxByOrNull { it.ceOi }?.takeIf { it.strike > c.spot && (it.strike - c.spot) / c.spot * 100 <= NEAR_PCT }?.let {
                out += Lean(Source.CALL_WRITING, -1, "the chain's call writing is heaviest just above, at the ${k(it.strike)} strike$at")
            }
            c.strikes.filter { it.peOi > 0 }.maxByOrNull { it.peOi }?.takeIf { it.strike < c.spot && (c.spot - it.strike) / c.spot * 100 <= NEAR_PCT }?.let {
                out += Lean(Source.PUT_WRITING, 1, "the chain's put writing is heaviest just below, at the ${k(it.strike)} strike$at")
            }
        }
        vixChangePct?.let { v ->
            if (v >= VIX_PCT) out += Lean(Source.VIX, -1, "India VIX is up ${one(v)}% on the day")
            else if (v <= -VIX_PCT) out += Lean(Source.VIX, 1, "India VIX is down ${one(-v)}% on the day")
        }
        return out
    }

    /**
     * Facts that point different ways, each pair said as one sentence, the weightier fact first: "Nifty's structure is
     * trend-like up so far (...), but the chain's call writing is heaviest just above, at the 24,700 strike - both are
     * facts; they point different ways." At most [max]; empty when nothing conflicts.
     */
    fun tensions(leans: List<Lean>, max: Int = MAX_TENSIONS): List<String> {
        val order = compareByDescending<Lean> { it.source.weight }
        val ups = leans.filter { it.sign > 0 }.sortedWith(order)
        val downs = leans.filter { it.sign < 0 }.sortedWith(order)
        val out = ArrayList<String>()
        for (i in 0 until minOf(ups.size, downs.size, max)) {
            val (a, b) = if (downs[i].source.weight > ups[i].source.weight) downs[i] to ups[i] else ups[i] to downs[i]
            out += "${a.text.replaceFirstChar { it.uppercase() }}, but ${b.text} - $BOTH."
        }
        return out
    }

    // ---- (c) his own data disagreeing with itself ---------------------------------------------------------------

    /** A price from another source than the candles: what it is ("the Nifty option chain's spot"), the price, its time. */
    data class Quote(val name: String, val price: Double, val at: LocalDateTime)

    /** Two of Jarvis's own sources disagreeing: how far apart ([pct], beyond the candle's range) and what he says. */
    data class Mismatch(val market: Market, val candle: Candle, val other: Quote, val pct: Double, val text: String)

    /**
     * The [other] source's price against [m]'s 1-minute candle of the same minute (or within [SAME_MINUTES]): further
     * apart than [threshold] percent beyond the candle's own low-high range is a disagreement, said with the source he
     * goes by - the candles, the index's own minute-by-minute prices that his charts, levels and structure are built
     * from. Null when they agree, or when no candle covers that minute (old candles are [DataAge]'s to say).
     */
    fun priceCheck(m: Market, bars: List<Candle>, other: Quote, threshold: Double = DISAGREE_PCT): Mismatch? {
        if (other.price <= 0) return null
        val c = bars.lastOrNull { !it.t.isAfter(other.at) && other.at.isBefore(it.t.plusMinutes(1)) }
            ?: bars.filter { abs(Duration.between(it.t, other.at).toMinutes()) <= SAME_MINUTES }
                .minByOrNull { abs(Duration.between(it.t, other.at).seconds) }
            ?: return null
        if (c.c <= 0) return null
        val gap = when { other.price > c.h -> other.price - c.h; other.price < c.l -> c.l - other.price; else -> 0.0 }
        val pct = gap / c.c * 100
        if (pct < threshold) return null
        val text = "My own numbers disagree, Boss: ${other.name} at ${hm(other.at)} is ${n(other.price)}, but my ${m.label} 1-minute candle for " +
            "${hm(c.t)} ran ${n(c.l)} to ${n(c.h)} - ${"%.2f".format(Locale.ENGLISH, pct)}% apart. I go by the candles: they are " +
            "${m.label}'s own minute-by-minute prices, the ones my charts, levels and structure are built from, while ${other.name} " +
            "comes with the option quotes and can lag. Figures built on that spot - the at-the-money strike, the straddle - I hold loosely until the two agree."
        return Mismatch(m, c, other, pct, text)
    }

    /** The chain's spot as a [Quote] for [priceCheck]. */
    fun chainQuote(r: ChainIntel.Read): Quote =
        Quote("the ${Market.entries.firstOrNull { it.name.equals(r.underlying, true) }?.label ?: r.underlying} option chain's spot", r.spot, r.at)

    // ---- (b) Boss's words against today -------------------------------------------------------------------------

    /** A "not on this weekday" rule in a kept note ("I don't trade on Fridays"), optionally for one [market]. */
    data class DayRule(val note: String, val day: DayOfWeek, val market: Market? = null)

    private val DAYS = mapOf("monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY)

    private fun words(s: String) = " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("'", " ").replace("’", " "), ":") + " "

    private val AVOID = Regex(" (don t|dont|do not|never|no|avoid|skip|not|stay out) ")
    private val TRADE = Regex(" (trade|trades|trading|trade on|positions?|entries|entry) ")
    private val OFF = Regex(" (mondays?|tuesdays?|wednesdays?|thursdays?|fridays?) (off|are off|is off) ")

    /** The weekday rule in [note], or null ("no trading on Mondays", "I never trade Fridays", "fridays off"). */
    fun dayRule(note: String): DayRule? {
        val t = words(note)
        val day = DAYS.entries.firstOrNull { (w, _) -> rx(" ${w}s? ").containsMatchIn(t) }?.value ?: return null
        val rule = (AVOID.containsMatchIn(t) && (TRADE.containsMatchIn(t) || t.contains(" skip "))) || OFF.containsMatchIn(t)
        if (!rule) return null
        // "Don't trade BankNifty on Fridays": that index on that day.
        val market = Market.mentioned(note).filter { it != Market.VIX && it != Market.GOLD }.singleOrNull()
        return DayRule(note, day, market)
    }

    /** One of Boss's own trades today: when it opened and on which index (null: not known). */
    data class Deed(val at: LocalDateTime, val market: Market?)

    /** Boss's word against today: [key] (to say it once a day) and the gentle line. */
    data class Clash(val key: String, val text: String)

    private fun count(n: Int) = if (n == 1) "1 trade" else "$n trades"
    private fun dayName(d: DayOfWeek) = d.name.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

    /**
     * Boss's words against his own trades today ([deeds]): his kept notes' rules ([notes]: a weekday rule, a time rule,
     * an index he avoids, expiry days - [expiryToday] the indices whose expiry is today) and his "no more than N trades a
     * day" goal ([maxTrades]). Each clash once, in a gentle line, words only.
     */
    fun clashes(notes: List<String>, deeds: List<Deed>, today: LocalDate, maxTrades: Int? = null, expiryToday: Set<Market> = emptySet()): List<Clash> {
        val mine = deeds.filter { it.at.toLocalDate() == today }.sortedBy { it.at }
        if (mine.isEmpty()) return emptyList()
        val out = ArrayList<Clash>()
        for (note in notes.distinct()) {
            val d = dayRule(note)
            if (d != null) {
                val hit = mine.filter { d.market == null || it.market == d.market }
                if (d.day == today.dayOfWeek && hit.isNotEmpty()) out += Clash("DAY:$note", "Boss, you told me \"$note\" - it's ${dayName(d.day)}, and you have " +
                    (if (d.market != null) "${hit.size} ${d.market.label} trade${if (hit.size == 1) "" else "s"}" else count(hit.size)) + " today. $GENTLE")
                continue
            }
            val r = BossRules.of(note) ?: continue
            when (r.kind) {
                BossRules.Kind.NOT_BEFORE -> mine.firstOrNull { it.at.hour * 60 + it.at.minute < r.minute!! }?.let { d ->
                    out += Clash("BEFORE:$note", "Boss, you asked me to remember \"$note\" - one of today's trades opened at ${hm(d.at)}. $GENTLE")
                }
                BossRules.Kind.NOT_AFTER -> mine.lastOrNull { it.at.hour * 60 + it.at.minute >= r.minute!! }?.let { d ->
                    out += Clash("AFTER:$note", "Boss, you asked me to remember \"$note\" - one of today's trades opened at ${hm(d.at)}, after ${hm(r.minute!!)}. $GENTLE")
                }
                BossRules.Kind.AVOID_MARKET -> mine.count { it.market == r.market }.takeIf { it > 0 }?.let { c ->
                    out += Clash("MARKET:$note", "Boss, you asked me to remember \"$note\" - you have $c ${r.market!!.label} trade${if (c == 1) "" else "s"} today. $GENTLE")
                }
                BossRules.Kind.AVOID_EXPIRY -> mine.filter { it.market != null && it.market in expiryToday }.takeIf { it.isNotEmpty() }?.let { hit ->
                    val on = hit.mapNotNull { it.market }.distinct().joinToString(" and ") { it.label }
                    out += Clash("EXPIRY:$note", "Boss, you asked me to remember \"$note\" - it's $on's expiry day, and you have ${count(hit.size)} on it today. $GENTLE")
                }
            }
        }
        if (maxTrades != null && maxTrades >= 1 && mine.size > maxTrades)
            out += Clash("MAXTRADES:$maxTrades", "Boss, your goal is no more than $maxTrades trade${if (maxTrades == 1) "" else "s"} a day - you're at ${mine.size} today. $GENTLE")
        return out
    }

    /** The first clash not yet told today ([told]: the keys already said), or null: one at a time, never repeated. */
    fun next(clashes: List<Clash>, told: Set<String>): Clash? = clashes.firstOrNull { it.key !in told }

    // ---- asked -------------------------------------------------------------------------------------------------

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please |ok |okay )*"
    private val ASKED = Regex(
        "^ $LEAD(are there |is there |do you see |do you find |any |see any |find any )(any )?(contradictions?|conflicts?|conflicting facts|inconsistenc(y|ies)|mixed signals)( in (the |your |my |this )?(facts|numbers|data|case|trade case|market|picture))?( (today|now|right now))?( boss| jarvis| please)? $|" +
        "^ $LEAD(do|does) (the |your )?(facts|numbers|data|signals|market facts) (agree|conflict|contradict( each other)?|disagree|line up|add up|point the same way)( with each other)?( (today|now))?( boss| jarvis)? $|" +
        "^ $LEAD(are|is) (the |your )?(facts|numbers|data|signals) (consistent|contradictory|conflicting|in conflict|mixed)( (today|now))?( boss| jarvis)? $|" +
        "^ $LEAD(what s|what is|anything) (pulling|pointing) (different|opposite|two) ways( (today|now))?( boss| jarvis)? $|" +
        "^ $LEAD(check|run) (yourself|your facts|your numbers|the facts)( for (contradictions?|conflicts?|consistency))?( boss| jarvis| please)? $|" +
        "^ $LEAD(consistency|contradiction) check( please)?( boss| jarvis)? $|" +
        "^ $LEAD(am i|have i been|was i) (going against|breaking|contradicting|keeping|following|sticking to) my (own )?(rules|words|goals|word)( today)?( boss| jarvis)? $"
    )

    /** "Any contradictions?", "do the facts agree?", "what's pulling different ways?", "am I going against my own rules?". */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(words(text))

    /**
     * The answer to [asked]: the market facts pulling apart ([tensions]), his own data disagreeing ([mismatch]) and, on
     * an unlocked phone only, Boss's words against today ([clashes]; null when locked). Words only.
     */
    fun say(tensions: List<String>, mismatch: Mismatch?, clashes: List<Clash>?, locked: Boolean): String {
        val parts = ArrayList<String>()
        if (tensions.isNotEmpty()) parts += "In the market facts: " + tensions.joinToString(" ")
        mismatch?.let { parts += "In my own numbers: " + it.text.removePrefix("My own numbers disagree, Boss: ") }
        val own = if (locked) emptyList() else clashes.orEmpty().map { it.text.removePrefix("Boss, ").removeSuffix(" $GENTLE").replaceFirstChar { c -> c.uppercase() } }
        if (own.isNotEmpty()) parts += "Between your words and today: " + own.joinToString(" ")
        val lockedNote = if (locked) " $LOCKED_NOTE" else ""
        if (parts.isEmpty()) return NONE + (if (locked || clashes == null) "" else " Nothing you did today goes against what you told me.") + lockedNote
        return "Checking myself for contradictions, Boss. " + parts.joinToString(" ") + lockedNote +
            " Those are facts side by side, not advice: what to make of them is your call, Boss."
    }
}
