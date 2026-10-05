package com.optionslab.ira

import com.optionslab.engine.options.ChainSnapshot
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Option chain intelligence beyond the put-call ratio and max pain (market intelligence, round 8, 2026-10-05): from
 * the chain the app already reads ([ChainSnapshot], the Options tab's numbers), "where is the most call / put
 * writing?", "how has OI shifted since morning?" (against where each contract's OI started the day, and against
 * Jarvis's own earlier reads of the chain today, kept in memory only - [Book]), "what's the IV skew, are puts dearer
 * than calls?" and "what's the expected move by expiry from the straddle?" (the ATM straddle's premium as a ± range:
 * what option prices imply, never a forecast). Every number comes from the chain and every answer says the chain's
 * time. Facts only: no advice, no direction, nothing acts. Pure.
 */
object ChainIntel {
    enum class Ask { WRITING, SHIFT, SKEW, MOVE, ALL }

    /** One strike as read: open interest now and where it started the day (null: not known), LTPs and IVs (percent). */
    data class Strike(
        val strike: Double,
        val ceOi: Long, val peOi: Long,
        val ceStart: Long?, val peStart: Long?,
        val ce: Double?, val pe: Double?,
        val ceIv: Double?, val peIv: Double?,
    )

    /** One read of [underlying]'s chain for [expiry], with the time its data is from ([at]). */
    data class Read(
        val underlying: String,
        val expiry: LocalDate,
        val at: LocalDateTime,
        val spot: Double,
        val atm: Double?,
        val atmIv: Double?,
        val strikes: List<Strike>,
        /** The chain's own max pain strike as the Options tab computes it (null: not given - [ChainDrift] works it out). */
        val maxPain: Double? = null,
    )

    /** [c] as a [Read] taken at [at]: only the few numbers kept per strike (small enough to hold a day of reads). */
    fun read(c: ChainSnapshot, at: LocalDateTime): Read {
        val iv = c.ivSmile?.chain.orEmpty().associateBy { it.strike }
        return Read(c.underlying, c.expiry, at, c.spot, c.atm, c.atmIv, c.rows.map { r ->
            Strike(r.strike, r.ce?.oi ?: 0L, r.pe?.oi ?: 0L, r.ce?.prevOi, r.pe?.prevOi,
                r.ce?.ltp?.takeIf { it > 0 }, r.pe?.ltp?.takeIf { it > 0 }, iv[r.strike]?.ceIv, iv[r.strike]?.peIv)
        }, c.maxPain?.maxPainStrike)
    }

    /**
     * Today's reads of each chain, in memory only (never saved, gone with the app): the day's first read is always
     * kept, then the newest up to [max]. A new day or a new expiry starts afresh.
     */
    class Book(private val max: Int = 30) {
        private val reads = HashMap<String, MutableList<Read>>()

        @Synchronized fun keep(r: Read) {
            val l = reads.getOrPut(r.underlying) { ArrayList() }
            l.removeAll { it.at.toLocalDate() != r.at.toLocalDate() || it.expiry != r.expiry }
            if (l.lastOrNull()?.at == r.at) l.removeAt(l.size - 1)
            if (l.lastOrNull()?.at?.isAfter(r.at) == true) return
            l += r
            while (l.size > max.coerceAtLeast(2)) l.removeAt(1)
        }

        /** The newest read of [u]. */
        @Synchronized fun latest(u: String): Read? = reads[u]?.lastOrNull()

        /** The first read of [u] on [day] (null when none). */
        @Synchronized fun first(u: String, day: LocalDate): Read? = reads[u]?.firstOrNull()?.takeIf { it.at.toLocalDate() == day }

        /** Every read of [u] kept for [day], oldest first (a copy). */
        @Synchronized fun day(u: String, day: LocalDate): List<Read> = reads[u]?.filter { it.at.toLocalDate() == day }.orEmpty()

        @Synchronized fun count(u: String): Int = reads[u]?.size ?: 0
    }

    /** An earlier read is compared only when it is at least this many minutes older. */
    const val COMPARE_MINUTES = 5L
    /** The skew is read no further out than this share of spot. */
    const val SKEW_SPAN = 0.03
    /** Put and call IVs within this many points are "about even". */
    const val EVEN_IV = 0.5

    const val NOT_HERE = "Option chains are read for Nifty, BankNifty and FinNifty, Boss."

    // ---- the questions --------------------------------------------------------------------------------------------

    private val WRITING = Regex(
        " (where|which strikes?|at what strikes?|what strikes?) (is|are|s|has|have)?( there)?( the)? ?(most|biggest|heaviest|highest|max|maximum|largest|strongest|more) (call|put|calls|puts|call and put|put and call)( side)? (writing|writers|written|oi|open interest|oi build up|oi buildup) |" +
        " (where|which strikes?) (are|is) (the )?(call|put|calls|puts) (writers|being written|written|writing) |" +
        " (most|biggest|heaviest|max|maximum) (call|put) (writing|writers)( strike| strikes| where| today)? |" +
        " (call|put) writing (where|strikes?|kahan|kaha) | (sabse )?(zyada|jyada) (call|put) writing (kahan|kaha|kidhar) "
    )
    private val SHIFT = Regex(
        " (oi|open interest|chain|option chain|writing|pcr|put call ratio) (shifted|shift|shifting|moved|changed|change|built|build|building|developed) (since|from) (the )?(morning|open|opening|start|start of the day|9 15|first read|today s open|market open) |" +
        " (how|what) (has|have|s) (the )?(oi|open interest|option chain|chain|writing|pcr) (shifted|moved|changed|built up|developed)( today| since (the )?(morning|open|opening|start))? |" +
        " (oi|open interest) (shift|shifts|shifting|migration|build up|buildup)( today| since (the )?(morning|open))? |" +
        " (since|from) (the )?(morning|open|opening) (how|what) (has|s) (the )?(oi|open interest|chain|writing) |" +
        // Hinglish (routing audit, 5 Oct): "OI kaise shift hua", "subah se OI kitna badla".
        " (oi|open interest|chain|writing) (kaise|kitna|kitni|kaisa) (shift|badla|badli|change|move) (hua|hui|hue|ho raha hai|hua hai|kiya) "
    )
    private val SKEW = Regex(
        " (iv|implied volatility|volatility|vol) skew | skew (in|on|of) (the )?(options|chain|option chain|iv|nifty|bank nifty|banknifty|finnifty|fin nifty) | what s the skew | what is the skew | how is the skew | skew (now|today) | is there (a |any )?skew |" +
        " (are )?puts (dearer|costlier|pricier|more expensive|richer|cheaper) than calls | (are )?calls (dearer|costlier|pricier|more expensive|richer|cheaper) than puts |" +
        " (put|puts) (iv|ivs) (vs|versus|against|compared to|or) (call|calls)( iv| ivs)? | (call|calls) (iv|ivs) (vs|versus|against|compared to|or) (put|puts)( iv| ivs)? "
    )
    private val MOVE = Regex(
        " straddle( implies| implied| implying| says| price| prices| premium| cost| costs| move| range)? | (atm|at the money) straddle |" +
        " (expected|implied|priced in|priced) (move|range|swing) (by|till|until|to|for|into|before|on|at) (the )?(this )?(weekly |monthly )?expiry |" +
        // (Routing audit, 5 Oct: "expected move this expiry", "for BankNifty by expiry", "this week" - the weekly expiry's
        // straddle, never the day's VIX range that [ExpectedRange] gives for "expected move today".)
        " (expected|implied) (move|range|swing) (for |on |of )?(nifty |bank nifty |banknifty |finnifty |fin nifty )?(by|till|until|into|before|for|on) (the )?(this )?(weekly |monthly )?expiry |" +
        " (expected|implied) (move|range|swing) (for |on |of )?(nifty |bank nifty |banknifty |finnifty |fin nifty )?(this|current) (weekly |monthly )?expiry | (expected|implied) (move|range|swing) (for )?(this|the) week |" +
        " (move|range) (by|till|until|into|before) (the )?(this )?expiry (expected|implied|priced in|priced) |" +
        " (how much|how big a|what) (move|range|swing) (is|are) (the )?(options?|option prices|market|chain|premiums) (pricing|pricing in|implying|priced for|expecting) |" +
        " what (do|are) (the )?(options|option prices|premiums) (imply|implying|pricing in|price in) "
    )
    private val ALL = Regex(" (option chain|chain) (intelligence|deep dive|in detail|breakdown|analysis|full read|everything)| (analy[sz]e|break down|read|unpack) (the )?(nifty |bank nifty |banknifty |finnifty |fin nifty )?(option )?chain (in detail|for me|fully|properly) ")
    /** Advice, forecasts and Boss's own book are not these questions. */
    private val NOT = Regex(" (should i|should we|shall i|do i|can i|will (it|nifty|the market|bank nifty|banknifty)|tomorrow|next week|buy|sell|short|go long|my|mine|our|predict|prediction|forecast|target) ")

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")) + " "

    /** The word itself asked ("what is a straddle", "explain straddle", "skew ka matlab"): the glossary's (routing audit, round 8). */
    private val WORD = Regex(" (what is a|what s a|whats a|what is an|define|definition of|meaning of) | (meaning|kya hota|kya hoti|ka matlab|matlab) |" +
        "^ (explain|define) (a |an |the term )?(straddle|strangle|skew|iv skew|call writing|put writing|open interest|oi) $")

    /** Which of these questions [text] asks, or null. The broad PCR / max pain read stays [ChainRead]'s. */
    fun asked(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || WORD.containsMatchIn(t)) return null
        val hits = listOfNotNull(Ask.WRITING.takeIf { WRITING.containsMatchIn(t) }, Ask.SHIFT.takeIf { SHIFT.containsMatchIn(t) },
            Ask.SKEW.takeIf { SKEW.containsMatchIn(t) }, Ask.MOVE.takeIf { MOVE.containsMatchIn(t) })
        return when {
            ALL.containsMatchIn(t) || hits.size > 1 -> Ask.ALL
            else -> hits.firstOrNull()
        }
    }

    /** The chain to read for [markets] (Nifty when none is named), or null when only markets without a chain are named. */
    fun underlying(markets: List<Market>): String? {
        val withChain = markets.firstOrNull { it == Market.NIFTY || it == Market.BANKNIFTY || it == Market.FINNIFTY }
        return withChain?.name ?: if (markets.any { it != Market.VIX }) null else "NIFTY"
    }

    // ---- the answers ----------------------------------------------------------------------------------------------

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private fun label(u: String) = when (u) { "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; else -> u }
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun n(x: Long) = "%,d".format(Locale.ENGLISH, x)
    private fun sn(x: Long) = (if (x >= 0) "+" else "-") + "%,d".format(Locale.ENGLISH, abs(x))
    private fun two(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun one(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun time(at: LocalDateTime, today: LocalDate) = HM.format(at) + if (at.toLocalDate() == today) "" else " on ${DAY.format(at)}"

    /** "Nifty chain at 11:00 (expiry 7 Oct, spot 24,160)". */
    fun head(r: Read, today: LocalDate) = "${label(r.underlying)} chain at ${time(r.at, today)} (expiry ${DAY.format(r.expiry)}, spot ${k(r.spot)})"

    /** [earlier] when it is an earlier read of the same chain, old enough to compare with [now]. */
    private fun comparable(now: Read, earlier: Read?): Read? = earlier?.takeIf {
        it.underlying == now.underlying && it.expiry == now.expiry && it.at.toLocalDate() == now.at.toLocalDate() &&
            Duration.between(it.at, now.at).toMinutes() >= COMPARE_MINUTES
    }

    /** The answer to [a] from [now] and the day's first read [first] (null or the same read: none to compare). */
    fun answer(a: Ask, now: Read, first: Read?, today: LocalDate): String {
        val parts = when (a) {
            Ask.WRITING -> listOf(writing(now, today))
            Ask.SHIFT -> listOf(shift(now, first, today))
            Ask.SKEW -> listOf(skew(now, first, today))
            Ask.MOVE -> listOf(move(now, first, today))
            Ask.ALL -> listOf(writing(now, today), shift(now, first, today, short = true), skew(now, null, today, short = true), move(now, null, today, short = true))
        }
        return parts.joinToString(" ") + " Those are the chain's own numbers, Boss, not advice."
    }

    /** Where calls and puts were written most today (OI added), and where the biggest OI stands. */
    fun writing(r: Read, today: LocalDate): String {
        val s = r.strikes
        val ce = s.filter { it.ceStart != null && it.ceOi - it.ceStart > 0 }.sortedByDescending { it.ceOi - it.ceStart!! }.take(2)
        val pe = s.filter { it.peStart != null && it.peOi - it.peStart > 0 }.sortedByDescending { it.peOi - it.peStart!! }.take(2)
        val bigCe = s.filter { it.ceOi > 0 }.maxByOrNull { it.ceOi }
        val bigPe = s.filter { it.peOi > 0 }.maxByOrNull { it.peOi }
        if (bigCe == null && bigPe == null) return "${head(r, today)}: it carries no open interest to read."
        val out = ArrayList<String>()
        out += "${head(r, today)}, across the ${s.size} strikes I read:"
        if (ce.isEmpty() && pe.isEmpty()) {
            out += "it doesn't show where OI started today, so here is open interest as it stands -" +
                listOfNotNull(bigCe?.let { " most call OI at ${k(it.strike)} (${n(it.ceOi)})" }, bigPe?.let { " most put OI at ${k(it.strike)} (${n(it.peOi)})" }).joinToString(",") + "."
        } else {
            if (ce.isNotEmpty()) out += "the most call writing today is at " + ce.joinToString(", then ") { "${k(it.strike)} (+${n(it.ceOi - it.ceStart!!)} OI)" } + "."
            else out += "no strike added call OI today."
            if (pe.isNotEmpty()) out += "The most put writing is at " + pe.joinToString(", then ") { "${k(it.strike)} (+${n(it.peOi - it.peStart!!)} OI)" } + "."
            else out += "No strike added put OI today."
            out += "The biggest OI stands at " + listOfNotNull(bigCe?.let { "${k(it.strike)} on calls (${n(it.ceOi)})" }, bigPe?.let { "${k(it.strike)} on puts (${n(it.peOi)})" }).joinToString(" and ") + "."
        }
        val ceUp = bigCe?.strike?.let { it > r.spot } == true
        val peDown = bigPe?.strike?.let { it < r.spot } == true
        if (ceUp || peDown) out += "Traders read heavy call writing above spot as a ceiling and put writing below as a floor; that is a reading, not a promise."
        return out.joinToString(" ")
    }

    private fun pcr(ce: Long, pe: Long) = if (ce > 0) two(pe.toDouble() / ce) else null

    /** How OI moved since the day's start (where each contract's OI began) and since Jarvis's first read today. */
    fun shift(r: Read, first: Read?, today: LocalDate, short: Boolean = false): String {
        val out = ArrayList<String>()
        val known = r.strikes.filter { it.ceStart != null && it.peStart != null }
        if (known.isNotEmpty()) {
            val ce0 = known.sumOf { it.ceStart!! }; val pe0 = known.sumOf { it.peStart!! }
            val ce1 = known.sumOf { it.ceOi }; val pe1 = known.sumOf { it.peOi }
            val p0 = pcr(ce0, pe0); val p1 = pcr(ce1, pe1)
            var line = "Since the day's first OI reading, across the ${known.size} strikes near the money: call OI ${sn(ce1 - ce0)}, put OI ${sn(pe1 - pe0)}" +
                (if (p0 != null && p1 != null) ", put-call ratio $p0 to $p1." else ".")
            if (!short) {
                val ceShed = known.minByOrNull { it.ceOi - it.ceStart!! }?.takeIf { it.ceOi - it.ceStart!! < 0 }
                val peShed = known.minByOrNull { it.peOi - it.peStart!! }?.takeIf { it.peOi - it.peStart!! < 0 }
                val shed = listOfNotNull(ceShed?.let { "calls at ${k(it.strike)} (${sn(it.ceOi - it.ceStart!!)})" }, peShed?.let { "puts at ${k(it.strike)} (${sn(it.peOi - it.peStart!!)})" })
                if (shed.isNotEmpty()) line += " Most OI shed: ${shed.joinToString(", ")}."
            }
            out += line
        }
        val e = comparable(r, first)
        if (e != null) {
            val then = e.strikes.associateBy { it.strike }
            val both = r.strikes.mapNotNull { s -> then[s.strike]?.let { s to it } }
            if (both.isNotEmpty()) {
                val dCe = both.sumOf { it.first.ceOi - it.second.ceOi }; val dPe = both.sumOf { it.first.peOi - it.second.peOi }
                val p0 = pcr(both.sumOf { it.second.ceOi }, both.sumOf { it.second.peOi }); val p1 = pcr(both.sumOf { it.first.ceOi }, both.sumOf { it.first.peOi })
                var line = "Since my first read today at ${HM.format(e.at)} (spot ${k(e.spot)} then): call OI ${sn(dCe)}, put OI ${sn(dPe)}" +
                    (if (p0 != null && p1 != null) ", put-call ratio $p0 to $p1." else ".")
                if (!short) {
                    val ceAdd = both.maxByOrNull { it.first.ceOi - it.second.ceOi }?.takeIf { it.first.ceOi - it.second.ceOi > 0 }
                    val peAdd = both.maxByOrNull { it.first.peOi - it.second.peOi }?.takeIf { it.first.peOi - it.second.peOi > 0 }
                    val add = listOfNotNull(ceAdd?.let { "calls at ${k(it.first.strike)} (${sn(it.first.ceOi - it.second.ceOi)})" }, peAdd?.let { "puts at ${k(it.first.strike)} (${sn(it.first.peOi - it.second.peOi)})" })
                    if (add.isNotEmpty()) line += " Most added since then: ${add.joinToString(", ")}."
                }
                out += line
            }
        }
        if (out.isEmpty()) return "${head(r, today)}: it doesn't carry where OI started today, and this is my first read of it today, so I can't say how it shifted yet. Ask me again later and I'll compare with this read."
        return "${head(r, today)}. " + out.joinToString(" ")
    }

    /** The ATM IV and an equal-distance put-against-call IV comparison (the farthest pair within [SKEW_SPAN] of spot). */
    internal fun skewPair(r: Read): Triple<Strike, Strike, Double>? {
        val atm = r.atm ?: return null
        val by = r.strikes.associateBy { it.strike }
        val above = r.strikes.filter { it.strike > atm && it.ceIv != null && it.strike - atm <= r.spot * SKEW_SPAN }
        return above.sortedByDescending { it.strike }.firstNotNullOfOrNull { c ->
            by[atm - (c.strike - atm)]?.takeIf { it.peIv != null }?.let { p -> Triple(p, c, c.strike - atm) }
        }
    }

    fun skew(r: Read, first: Read?, today: LocalDate, short: Boolean = false): String {
        val pair = skewPair(r)
        val atmIv = r.atmIv?.let { "ATM IV ${one(it)}%" }
        if (pair == null) return "${head(r, today)}: " + (atmIv?.let { "$it; " } ?: "") + "the chain doesn't price a put and a call at the same distance from the money, so I can't read the skew."
        val (p, c, d) = pair
        val diff = p.peIv!! - c.ceIv!!
        val verdict = when {
            abs(diff) < EVEN_IV -> "puts and calls are about even"
            diff > 0 -> "puts are dearer than calls by ${one(diff)} IV points"
            else -> "calls are dearer than puts by ${one(-diff)} IV points"
        }
        var s = "${head(r, today)}: " + (atmIv?.let { "$it. " } ?: "") +
            "${pts(d)} points either side, the ${k(p.strike)} put is at ${one(p.peIv)}% IV and the ${k(c.strike)} call at ${one(c.ceIv)}%: $verdict."
        if (!short) {
            if (diff >= EVEN_IV) s += " Index puts usually carry the higher IV - traders pay more for protection against a fall."
            else if (diff <= -EVEN_IV) s += " That is less usual for an index, where puts tend to carry the higher IV."
            comparable(r, first)?.let { e ->
                val then = e.strikes.associateBy { it.strike }
                val p0 = then[p.strike]?.peIv; val c0 = then[c.strike]?.ceIv
                if (p0 != null && c0 != null) s += " At my ${HM.format(e.at)} read the same pair was ${one(p0)}% and ${one(c0)}%, a gap of ${"%+.1f".format(Locale.ENGLISH, p0 - c0)} points."
            }
        }
        return s
    }

    /** The ATM straddle's premium (call + put at the ATM strike), or null when either side has no price. */
    internal fun straddle(r: Read): Triple<Strike, Double, Double>? {
        val s = r.strikes.firstOrNull { it.strike == r.atm } ?: return null
        val c = s.ce ?: return null; val p = s.pe ?: return null
        return Triple(s, c, p)
    }

    fun move(r: Read, first: Read?, today: LocalDate, short: Boolean = false): String {
        val st = straddle(r) ?: return "${head(r, today)}: the ATM strike has no call and put price both, so I can't read the straddle."
        val (s, c, p) = st
        val prem = c + p
        val pct = if (r.spot > 0) prem / r.spot * 100 else 0.0
        val days = ChronoUnit.DAYS.between(r.at.toLocalDate(), r.expiry)
        val by = when {
            days <= 0L -> "by today's expiry"
            days == 1L -> "by expiry tomorrow (${DAY.format(r.expiry)})"
            else -> "by expiry on ${DAY.format(r.expiry)} ($days days away)"
        }
        var out = "${head(r, today)}: the ${k(s.strike)} straddle (call ${k(c)} + put ${k(p)}) costs ${k(prem)}, so option prices imply about " +
            "±${pts(prem)} points (±${two(pct)}%) $by - roughly ${pts(r.spot - prem)} to ${pts(r.spot + prem)}."
        if (!short) comparable(r, first)?.let { e -> straddle(e)?.let { (s0, c0, p0) ->
            out += " At my ${HM.format(e.at)} read the ATM straddle (${k(s0.strike)}) cost ${k(c0 + p0)}."
        } }
        return out + " That is what the options cost, not a forecast: the market can and does move past it."
    }

    /**
     * The chain's facts for "make the case" ([TradeCase]): the straddle's implied move, where the biggest OI stands and
     * the skew, each with the chain's time. Facts only; nothing here judges a side.
     */
    fun caseFacts(r: Read, today: LocalDate): List<String> {
        val out = ArrayList<String>()
        val at = "the ${label(r.underlying)} chain at ${time(r.at, today)}"
        straddle(r)?.let { (s, c, p) ->
            val days = ChronoUnit.DAYS.between(r.at.toLocalDate(), r.expiry)
            out += "In $at, the ${k(s.strike)} straddle costs ${k(c + p)}: option prices imply about ±${pts(c + p)} points " +
                (if (days <= 0L) "by today's expiry" else "by the ${DAY.format(r.expiry)} expiry") + ", a price, not a forecast."
        }
        val bigCe = r.strikes.filter { it.ceOi > 0 }.maxByOrNull { it.ceOi }
        val bigPe = r.strikes.filter { it.peOi > 0 }.maxByOrNull { it.peOi }
        if (bigCe != null && bigPe != null) out += "The biggest call OI is at ${k(bigCe.strike)} and the biggest put OI at ${k(bigPe.strike)}" +
            (if (out.isEmpty()) " in $at." else ".")
        skewPair(r)?.let { (p, c, _) ->
            val diff = p.peIv!! - c.ceIv!!
            out += when {
                abs(diff) < EVEN_IV -> "Put and call IVs are about even at the same distance from the money."
                diff > 0 -> "Puts are dearer than calls by ${one(diff)} IV points at the same distance from the money."
                else -> "Calls are dearer than puts by ${one(-diff)} IV points at the same distance from the money."
            }
        }
        return out
    }
}
