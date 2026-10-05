package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * "What if" (Jarvis reasoning, 2026-10-05): "what if Nifty opens 1% down tomorrow?", "what if VIX goes to 20?", "suppose
 * BankNifty falls 2%", "what if Nifty hits 24,000?" - reasoned out from facts Jarvis already has, never a forecast:
 *  - the past sessions on the phone like it ([MarketMemory]): how many, when, how they went (gap filled or not, open to
 *    close, range) - past sessions, not a promise;
 *  - what Boss's open positions would show at that move ([Exposure], delta and gamma now) - only on an unlocked phone;
 *  - which of his own rules ([BossRules]), daily loss limits and price alarms it would touch - only on an unlocked phone;
 *  - which of Jarvis's own alerts would go off at it (a gap filling, a sharp move, India VIX's jump).
 * "What happens to my P&L if Nifty moves 100 points" stays [Exposure.moveAsked]'s; "what if I had taken it" is the
 * replay of his ideas. Words only: nothing here places, changes or closes anything, and the decision is always Boss's. Pure.
 */
object Scenarios {
    /** OPEN: the next open gaps; MOVE: a move on the day; LEVEL: the index at a price; VIX_LEVEL / VIX_MOVE: India VIX. */
    enum class Kind { OPEN, MOVE, LEVEL, VIX_LEVEL, VIX_MOVE }

    /**
     * The scenario asked. [market]: the index it is about (for India VIX, the index whose sessions are read beside it);
     * [pct] or [points] a size, [level] a price (India VIX's for the VIX kinds); [sign] +1 up, -1 down, 0 either way (a
     * LEVEL's way comes from the price now); [next]: about the next session (an opening gap always is, unless "today").
     */
    data class Scenario(val kind: Kind, val market: Market, val pct: Double? = null, val points: Double? = null,
                        val level: Double? = null, val sign: Int = 0, val next: Boolean = false)

    /** One of Boss's price alarms: [symbol] as the app keeps it ("NIFTY", "BANKNIFTY", "INDIAVIX"...). */
    data class Alarm(val symbol: String, val above: Boolean, val level: Double)

    /**
     * Boss's own side, read only on an unlocked phone. [lossLimits]: each account's daily loss limit ("Zerodha", "Paper",
     * named as [Exposure.Leg.where]); [dayPnl]: each account's P&L today (added when the move is today's).
     */
    data class Mine(val legs: List<Exposure.Leg>, val notes: List<String> = emptyList(),
                    val lossLimits: Map<String, Double> = emptyMap(), val dayPnl: Map<String, Double> = emptyMap(),
                    val alarms: List<Alarm> = emptyList())

    /**
     * [days]: [Scenario.market]'s sessions ([MarketMemory.days], with India VIX's closes); [spot]: its price now;
     * [vix] and [vixPrev]: India VIX now and its previous close; [expiry]: the session the scenario is about is
     * [Scenario.market]'s expiry day; [mine]: null on a locked phone ([locked]) or when the account could not be read.
     */
    data class Input(val s: Scenario, val days: List<MarketMemory.Day>, val today: LocalDate, val spot: Double?,
                     val vix: Double? = null, val vixPrev: Double? = null, val expiry: Boolean = false,
                     val mine: Mine? = null, val locked: Boolean = false)

    const val YOURS = "Facts and past sessions only, Boss - not a forecast and not advice. What to do, if anything, is your call."
    const val LOCKED = "Your positions, rules and limits stay out of it on a locked phone, Boss - unlock it for those."

    // ---- the question ---------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase().replace("%", " percent ").replace(",", "").replace("'", " ")
        .replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("(?<![0-9])\\.|\\.(?![0-9])"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val IF = Regex(" (what if|what happens if|what would happen if|what happens when|suppose|supposing|imagine|let s say|lets say|say|in case|scenario|hypothetically|if) ")
    /** A bare "if" needs a "then what" (or the question's own "what") beside it. */
    private val THEN = Regex(" (what then|then what|what happens|what would|walk me through|what does it mean for|how would|what about) ")
    /** Boss's own action ("what if I had taken it": the replay; "what if I buy puts": a new trade), advice, a forecast. */
    private val NOT = Regex(" (if|what if|suppose) (i|we) | (had|would have|taken|took|followed|approved) | (should|shall|recommend|suggest|advise|advice|worth it|do i|must i|can i|buy|sell|hedge|exit|book profit|square off|predict|prediction|forecast|likely|chance|chances|odds|probability|expect|expected|will) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(points?|pts?|percent|per cent|pc) ")
    private val DOWN = Regex(" (falls?|fell|drops?|dropped|goes down|go down|comes down|crash(es)?|tanks?|slides?|sinks?|declines?|is down|down|lower|red|cracks?|slips?|plunges?|dips?) ")
    private val UP = Regex(" (rises?|rose|goes up|go up|jumps?|rall(y|ies)|climbs?|gains?|surges?|is up|up|higher|green|spikes?|shoots? up|soars?) ")
    private val MOVE = Regex(" (moves?|moved|swings?|changes?) ")
    private val OPEN = Regex(" (opens?|opening|gaps?|gapped|gapup|gapdown) ")
    private val AT = Regex(" (to|at|above|below|under|over|hits?|reach(?:es)?|breaks?|crosses|touches|near|around|of|is|becomes) (\\d+(?:\\.\\d+)?) ")
    private val NEXT = Regex(" (tomorrow|kal|next session|next day|next open|monday|tuesday|wednesday|thursday|friday|on monday) ")

    /** The scenario in [text], or null when it is not a what-if about an index or India VIX this answers. */
    fun asked(text: String): Scenario? {
        // "What happens to my P&L if Nifty moves 100 points" is the positions' own answer ([Exposure]).
        if (Exposure.moveAsked(text) != null) return null
        val t = norm(text).replace(" what will happen if ", " what happens if ").replace(" what will happen when ", " what happens when ")
        val framed = IF.findAll(t).any { m -> when (m.groupValues[1]) {
            "if" -> THEN.containsMatchIn(t) || t.startsWith(" what ")
            "say" -> t.startsWith(" say ")
            else -> true
        } }
        if (!framed) return null
        if (NOT.containsMatchIn(t)) return null
        val ms = Market.mentioned(text)
        if (Market.GOLD in ms) return null
        val index = ms.firstOrNull { it != Market.VIX && it != Market.GOLD }
        // With an index and India VIX both named, the one said first is the scenario ("what if VIX goes to 20, and Nifty?"),
        // read from its own words (up to the other's name: "Nifty falls 2% and VIX jumps" is Nifty falling 2%).
        fun at(m: Market) = m.aliases.mapNotNull { a -> t.indexOf(" $a ").takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
        val vixFirst = Market.VIX in ms && (index == null || at(Market.VIX) < at(index))
        val other = if (Market.VIX !in ms || index == null) Int.MAX_VALUE else if (vixFirst) at(index) else at(Market.VIX)
        val own = if (other == Int.MAX_VALUE) t else t.substring(0, other) + " "
        val down = DOWN.containsMatchIn(own); val up = UP.containsMatchIn(own)
        val sign = if (down && !up) -1 else if (up && !down) 1 else 0
        val size = SIZE.find(own)
        val n = size?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val pct = size?.groupValues?.get(2)?.let { it.startsWith("per") || it == "pc" } == true
        val next = NEXT.containsMatchIn(t)
        if (vixFirst) {
            val on = index ?: Market.NIFTY
            if (n != null && pct) {
                if (n > 200) return null
                return Scenario(Kind.VIX_MOVE, on, pct = n, sign = if (sign == 0) 1 else sign, next = next)
            }
            val lv = AT.find(own)?.groupValues?.get(2)?.toDoubleOrNull() ?: return null
            if (lv < 5 || lv > 90) return null
            return Scenario(Kind.VIX_LEVEL, on, level = lv, next = next)
        }
        val mk = index ?: if (ms.isEmpty()) Market.NIFTY else return null
        if (n != null) {
            if (pct && n > 20 || !pct && n > 5000) return null
            if (OPEN.containsMatchIn(own)) return Scenario(Kind.OPEN, mk, pct = if (pct) n else null, points = if (pct) null else n, sign = sign,
                next = next || !Regex(" (today|now) ").containsMatchIn(t))
            if (sign == 0 && !MOVE.containsMatchIn(own)) return null
            return Scenario(Kind.MOVE, mk, pct = if (pct) n else null, points = if (pct) null else n, sign = sign, next = next)
        }
        val lv = AT.find(own)?.groupValues?.get(2)?.toDoubleOrNull()?.takeIf { it >= 1000 } ?: return null
        return Scenario(Kind.LEVEL, mk, level = lv, next = next)
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun times(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + n0(abs(x))
    private fun amt(x: Double) = "Rs " + n0(abs(x))
    private fun plural(k: Int, w: String) = "$k $w${if (k == 1) "" else "s"}"

    /** The middle of [xs] (sorted), for "from -0.8% to +0.6% (middle +0.1%)". */
    private fun middle(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun spread(xs: List<Double>, f: (Double) -> String, mid: Boolean = true): String =
        if (xs.size == 1 || xs.min() == xs.max()) f(xs[0]) else "${f(xs.min())} to ${f(xs.max())}" + if (mid) " (middle ${f(middle(xs))})" else ""

    /** The move asked, in points of [Input.s]'s market from [spot], with its way; null without a price to measure from. */
    private fun movePoints(s: Scenario, spot: Double?): Pair<Double, Int>? {
        if (spot == null || spot <= 0) return if (s.points != null) s.points to s.sign else null
        return when (s.kind) {
            Kind.LEVEL -> abs(s.level!! - spot) to (if (s.level >= spot) 1 else -1)
            Kind.OPEN, Kind.MOVE -> (s.points ?: (spot * s.pct!! / 100)) to s.sign
            else -> null
        }
    }

    /** The answer, every part a fact from [i] (see [Scenarios]). */
    fun answer(i: Input): String {
        val parts = ArrayList<String>()
        parts += head(i)
        parts += history(i)
        parts += positions(i)
        parts += alerts(i)
        parts += YOURS
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun wayWord(sign: Int, upWord: String, downWord: String, either: String) = when (sign) { 1 -> upWord; -1 -> downWord; else -> either }

    private fun head(i: Input): String {
        val s = i.s
        val label = s.market.label
        val spot = i.spot
        val size = if (s.pct != null) pctAbs(s.pct) + (spot?.let { " (about ${n0(it * s.pct / 100)} points)" } ?: "")
            else s.points?.let { "${n0(it)} points" + (spot?.takeIf { p -> p > 0 }?.let { p -> " (about ${pctAbs(it / p * 100)})" } ?: "") }
        val now = spot?.let { " from ${n(it)} now" } ?: ""
        return when (s.kind) {
            Kind.OPEN -> "If $label opens ${size} ${wayWord(s.sign, "higher", "lower", "higher or lower")}${if (s.next) " next session" else ""}$now - a what-if, Boss, not a forecast."
            Kind.MOVE -> "If $label ${wayWord(s.sign, "rises", "falls", "moves")} $size${if (s.next) " next session" else ""}$now - a what-if, Boss, not a forecast."
            Kind.LEVEL -> "If $label goes to ${n(s.level!!)}" + (spot?.let { " (${n(abs(s.level - it))} points, ${pctAbs((s.level - it) / it * 100)} ${if (s.level >= it) "above" else "below"} ${n(it)} now)" } ?: "") +
                " - a what-if, Boss, not a forecast."
            Kind.VIX_LEVEL -> "If India VIX goes to ${n(s.level!!)}" + (i.vix?.let { " (${pct((s.level - it) / it * 100)} from ${n(it)} now)" } ?: "") + " - a what-if, Boss, not a forecast."
            Kind.VIX_MOVE -> "If India VIX ${wayWord(s.sign, "jumps", "drops", "moves")} ${pctAbs(s.pct!!)}" + (i.vix?.let { " (to about ${n(it * (1 + s.sign * s.pct / 100))} from ${n(it)} now)" } ?: "") +
                " - a what-if, Boss, not a forecast."
        }
    }

    /** How much of a memory the phone holds. */
    private fun held(days: List<MarketMemory.Day>): String {
        val first = days.firstOrNull() ?: return "no sessions"
        return "the ${plural(days.size, "session")} of ${first.market.label} on the phone (since ${first.day})" + if (days.size < MarketMemory.SHORT) " - a short memory" else ""
    }

    private fun dates(hits: List<MarketMemory.Day>, f: (MarketMemory.Day) -> String): String =
        hits.takeLast(4).reversed().joinToString(", ") { "${it.day} (${f(it)})" } + if (hits.size > 4) ", and earlier ones" else ""

    private fun history(i: Input): String {
        val s = i.s
        val label = s.market.label
        // Whole sessions only: one still trading (or cut short) has no day to compare.
        val pool = i.days.filter { it.whole && it.prevClose != null }
        if (pool.isEmpty()) return "I have no whole ${label} sessions on the phone to compare it with."
        val out = ArrayList<String>()
        when (s.kind) {
            Kind.OPEN -> {
                val bar = s.pct ?: s.points!!
                val pts = s.points != null
                fun gap(d: MarketMemory.Day) = if (pts) d.gapPts!! else d.gapPct!!
                val hits = pool.filter { d -> val g = gap(d); when (s.sign) { -1 -> g <= -bar; 1 -> g >= bar; else -> abs(g) >= bar } }
                val what = "opened ${if (pts) "${n0(bar)} points" else pctAbs(bar)} or more ${wayWord(s.sign, "higher", "lower", "away from the previous close")}"
                if (hits.isEmpty()) {
                    val way = pool.filter { d -> when (s.sign) { -1 -> gap(d) < 0; 1 -> gap(d) > 0; else -> true } }.maxByOrNull { abs(gap(it)) }
                    out += "None of ${held(pool)} $what." + (way?.let { " The biggest gap that way was ${pct(it.gapPct!!)} on ${MarketMemory.date(it.day)}." } ?: "")
                } else {
                    out += "In ${held(pool)}, ${plural(hits.size, "session")} $what: ${dates(hits) { pct(it.gapPct!!) }}."
                    // A gap down is filled once the high comes back to the previous close; a gap up once the low does.
                    val filled = hits.count { d -> if (d.gapPct!! < 0) d.high >= d.prevClose!! else d.low <= d.prevClose!! }
                    val drift = hits.map { (it.close - it.open) / it.open * 100 }
                    val ratios = hits.mapNotNull { it.rangeRatio }
                    val closedThatWay = hits.count { d -> if (d.gapPct!! < 0) d.close < d.prevClose!! else d.close > d.prevClose!! }
                    out += "On those days the gap was filled (the price came back to the previous close) on $filled of ${hits.size}; " +
                        "from the open to the close they went ${spread(drift, ::pct)}; the day's range was ${spread(hits.map { it.range }, ::n0, mid = false)} points" +
                        (if (ratios.isNotEmpty()) ", ${spread(ratios, ::times, mid = false)} times the usual" else "") +
                        "; $closedThatWay of ${hits.size} still closed on the gap's side of the previous close."
                }
            }
            Kind.MOVE -> {
                val bar = s.pct ?: s.points!!
                val pts = s.points != null
                fun ch(d: MarketMemory.Day) = if (pts) d.changePts!! else d.changePct!!
                val hits = pool.filter { d -> val c = ch(d); when (s.sign) { -1 -> c <= -bar; 1 -> c >= bar; else -> abs(c) >= bar } }
                val what = "${wayWord(s.sign, "rose", "fell", "moved")} ${if (pts) "${n0(bar)} points" else pctAbs(bar)} or more close to close"
                if (hits.isEmpty()) {
                    val way = pool.filter { d -> when (s.sign) { -1 -> ch(d) < 0; 1 -> ch(d) > 0; else -> true } }.maxByOrNull { abs(ch(it)) }
                    out += "None of ${held(pool)} $what." + (way?.let { " The biggest that way was ${pct(it.changePct!!)} on ${MarketMemory.date(it.day)}." } ?: "")
                } else {
                    out += "In ${held(pool)}, ${plural(hits.size, "session")} $what: ${dates(hits) { pct(it.changePct!!) }}."
                    val gaps = hits.count { it.bigGap }
                    val trend = hits.count { it.trend != 0 }
                    val ratios = hits.mapNotNull { it.rangeRatio }
                    val vix = hits.mapNotNull { it.vixPct }
                    out += "On those days: ${if (gaps == 0) "none" else "$gaps of ${hits.size}"} opened with a gap of ${MarketMemory.BIG_GAP_PCT}% or more; " +
                        "$trend of ${hits.size} ${if (trend == 1) "was a trend day" else "were trend days"}" +
                        (if (ratios.isNotEmpty()) "; the range was ${spread(ratios, ::times)} times the usual" else "") +
                        (if (vix.isNotEmpty()) "; India VIX moved ${spread(vix, ::pct)}" else "") + "."
                }
            }
            Kind.LEVEL -> {
                val spot = i.spot
                if (spot == null || spot <= 0) out += "I don't have ${label}'s price just now, Boss, to measure how far ${n(s.level!!)} is."
                else {
                    val dist = abs(s.level!! - spot)
                    val up = s.level >= spot
                    val wide = pool.count { it.range >= dist }
                    out += "$wide of ${held(pool)} ranged ${n0(dist)} points or more in the day" +
                        " (their ranges ran ${spread(pool.map { it.range }, ::n0)} points)."
                    // The last session that traded there, when it is not where the price is.
                    i.days.lastOrNull { d -> d.day != i.today && if (up) d.high >= s.level else d.low <= s.level }?.let { d ->
                        out += "$label last traded at ${n(s.level)} on ${MarketMemory.date(d.day)} (that day ${n(d.low)} to ${n(d.high)})."
                    } ?: run { out += "$label hasn't traded at ${n(s.level)} in those sessions." }
                }
            }
            Kind.VIX_LEVEL, Kind.VIX_MOVE -> {
                val withVix = pool.filter { it.vixClose != null }
                if (withVix.isEmpty()) return "I have no India VIX candles for ${label}'s sessions on the phone to compare it with."
                val hits: List<MarketMemory.Day>
                val what: String
                if (s.kind == Kind.VIX_LEVEL) {
                    val up = i.vix == null || s.level!! >= i.vix
                    hits = withVix.filter { if (up) it.vixClose!! >= s.level!! else it.vixClose!! <= s.level!! }
                    what = "where India VIX closed ${if (up) "at or above" else "at or below"} ${n(s.level!!)}"
                } else {
                    hits = withVix.filter { d -> d.vixPct?.let { if (s.sign < 0) it <= -s.pct!! else it >= s.pct!! } == true }
                    what = "where India VIX ${if (s.sign < 0) "fell" else "rose"} ${pctAbs(s.pct!!)} or more on the day"
                }
                val memory = "the ${plural(withVix.size, "session")} with India VIX on the phone (since ${withVix.first().day})" +
                    if (withVix.size < MarketMemory.SHORT) " - a short memory" else ""
                if (hits.isEmpty()) {
                    out += "In $memory, India VIX never did that: it closed between ${n(withVix.minOf { it.vixClose!! })} and ${n(withVix.maxOf { it.vixClose!! })}" +
                        (withVix.mapNotNull { it.vixPct }.maxByOrNull { abs(it) }?.let { "; its biggest day's change was ${pct(it)}" } ?: "") + "."
                } else {
                    out += "In $memory, ${plural(hits.size, "session")} $what: ${dates(hits) { "VIX ${n(it.vixClose!!)}, $label ${pct(it.changePct!!)}" }}."
                    val ratios = hits.mapNotNull { it.rangeRatio }
                    out += "$label on those days went ${spread(hits.map { it.changePct!! }, ::pct)} close to close" +
                        (if (ratios.isNotEmpty()) ", with a range ${spread(ratios, ::times)} times the usual" else "") + "."
                }
            }
        }
        out += MarketMemory.NOT_A_PROMISE
        return out.joinToString(" ")
    }

    /** The open positions on [market] whose move can be worked out (a delta known). */
    private fun counted(legs: List<Exposure.Leg>, market: Market) = legs.filter { it.underlying.equals(market.name, true) && it.delta != null }

    private fun optionLeg(l: Exposure.Leg) = Regex("(CE|PE)$").containsMatchIn(l.symbol.uppercase(Locale.ENGLISH))

    private fun alarmOn(a: Alarm, m: Market): Boolean {
        val sym = a.symbol.uppercase(Locale.ENGLISH).substringAfter(":")
        return if (m == Market.VIX) sym.contains("VIX") else sym == m.name
    }

    private fun positions(i: Input): String {
        if (i.locked) return LOCKED
        val mine = i.mine ?: return "I couldn't read your positions just now, Boss, so they are not in this."
        val s = i.s
        val out = ArrayList<String>()
        var target: Double? = null
        var alarmMarket = s.market
        if (s.kind == Kind.VIX_LEVEL || s.kind == Kind.VIX_MOVE) {
            alarmMarket = Market.VIX
            target = if (s.kind == Kind.VIX_LEVEL) s.level else i.vix?.let { it * (1 + s.sign * s.pct!! / 100) }
            val opts = mine.legs.filter { optionLeg(it) }
            out += if (opts.isEmpty()) "You hold no open options, Boss, so an India VIX move by itself changes little on your positions."
                else {
                    val long = opts.count { it.qty > 0 }; val short = opts.count { it.qty < 0 }
                    "I can't put a rupee figure on a VIX move for your positions, Boss: I work from delta and gamma, not vega. " +
                        "Of your ${plural(opts.size, "open option")}, $long ${if (long == 1) "is" else "are"} long and $short short; with everything else held, " +
                        "option prices go up with implied volatility - long options gain and short ones lose."
                }
        } else {
            val mv = movePoints(s, i.spot)
            if (mv == null) out += "I don't have ${s.market.label}'s price just now, Boss, to work out your positions at that move."
            else {
                val (pts, way) = mv
                target = i.spot?.let { it + way * pts }
                out += Exposure.move(Exposure.Shock(s.market, pts, null, way), mine.legs, i.spot)
                // The daily loss limits, account by account: the worse way when either way was asked.
                val legs = counted(mine.legs, s.market)
                for ((where, limit) in mine.lossLimits) {
                    if (limit <= 0) continue
                    val own = legs.filter { it.where == where }
                    if (own.isEmpty()) continue
                    val ch = if (way == 0) minOf(own.sumOf { Exposure.change(it, pts) }, own.sumOf { Exposure.change(it, -pts) })
                        else own.sumOf { Exposure.change(it, way * pts) }
                    val day = (if (s.next) 0.0 else mine.dayPnl[where] ?: 0.0) + ch
                    out += if (day <= -limit) "That puts your $where day at about ${rs(day)}, past your daily loss limit of ${amt(limit)} - where the app's loss guard stops the strategies for the day."
                        else "Your $where day would be about ${rs(day)}, inside your daily loss limit of ${amt(limit)}."
                }
            }
        }
        // His price alarms the move would cross (from the price now to the target).
        val now = if (alarmMarket == Market.VIX) i.vix else i.spot
        if (target != null && now != null) {
            val hit = mine.alarms.filter { a -> alarmOn(a, alarmMarket) && if (a.above) a.level > now && target >= a.level else a.level < now && target <= a.level }
            if (hit.isNotEmpty()) out += "Your price alarm${if (hit.size > 1) "s" else ""} " + hit.take(4).joinToString(", ") {
                "\"${alarmMarket.label} ${if (it.above) "above" else "below"} ${n(it.level)}\"" } + " would go off on the way."
        }
        // His own rules that would be in play then.
        val rules = mine.notes.mapNotNull { BossRules.of(it) }.filter { r ->
            when (r.kind) {
                BossRules.Kind.AVOID_MARKET -> r.market == s.market
                BossRules.Kind.AVOID_EXPIRY -> i.expiry
                BossRules.Kind.NOT_BEFORE -> s.kind == Kind.OPEN
                BossRules.Kind.NOT_AFTER -> false
            }
        }
        if (rules.isNotEmpty()) out += "Your own rule${if (rules.size > 1) "s" else ""} in play: " + rules.take(3).joinToString("; ") { r ->
            "\"${r.note}\"" + when (r.kind) {
                BossRules.Kind.AVOID_MARKET -> " (this is ${s.market.label})"
                BossRules.Kind.AVOID_EXPIRY -> " (that session is a ${s.market.label} expiry day)"
                BossRules.Kind.NOT_BEFORE -> " (it holds at the open)"
                BossRules.Kind.NOT_AFTER -> ""
            }
        } + " - I keep to ${if (rules.size > 1) "them" else "it"} for my own ideas."
        return out.joinToString(" ")
    }

    private fun alerts(i: Input): String {
        val s = i.s
        val out = ArrayList<String>()
        when (s.kind) {
            Kind.OPEN -> {
                val g = s.pct ?: i.spot?.takeIf { it > 0 }?.let { s.points!! / it * 100 }
                if (g != null) out += if (g >= Moments.GAP_MIN_PCT) "If that gap filled during the day I'd tell you: I tell a filled gap of ${Moments.GAP_MIN_PCT}% or more."
                    else "A gap that small (under ${Moments.GAP_MIN_PCT}%) is below the size I tell you about when it fills."
                // The last whole session's low or high: where such an open would be against it.
                val last = i.days.lastOrNull { it.whole }
                val target = movePoints(s, i.spot)?.let { (p, w) -> i.spot?.plus(w * p) }
                if (last != null && target != null && s.sign != 0) {
                    if (s.sign < 0 && target < last.low) out += "That open is below the ${last.day} session's low of ${n(last.low)}."
                    if (s.sign > 0 && target > last.high) out += "That open is above the ${last.day} session's high of ${n(last.high)}."
                }
            }
            Kind.MOVE, Kind.LEVEL -> {
                val p = s.pct ?: movePoints(s, i.spot)?.let { (pts, _) -> i.spot?.takeIf { it > 0 }?.let { pts / it * 100 } }
                if (p != null) out += if (p >= SharpMove.MIN_PCT)
                    "If it came inside ${SharpMove.WINDOW} minutes, it is past my sharp-move bar (${SharpMove.MIN_PCT}% and ${times(SharpMove.TIMES)} times the usual ${SharpMove.WINDOW}-minute move) and I'd tell you what coincided with it; spread over the day, that alert stays quiet."
                    else "A move of ${pctAbs(p)} is under my sharp-move bar of ${SharpMove.MIN_PCT}%, so that alert stays quiet."
            }
            Kind.VIX_LEVEL, Kind.VIX_MOVE -> {
                val prev = i.vixPrev
                val day = when {
                    s.kind == Kind.VIX_MOVE && (s.next || prev == null) -> s.sign * s.pct!!
                    s.kind == Kind.VIX_MOVE -> i.vix?.let { (it * (1 + s.sign * s.pct!! / 100) - prev!!) / prev * 100 }
                    else -> (if (s.next) i.vix ?: prev else prev)?.let { (s.level!! - it) / it * 100 }
                }
                if (day != null) out += if (day >= VixSpike.SPIKE_PCT) "My fear alert would go off: India VIX ${pct(day)} on the day is past my ${VixSpike.SPIKE_PCT.toInt()}% bar."
                    else "My fear alert stays quiet: that is ${pct(day)} on the day, under my ${VixSpike.SPIKE_PCT.toInt()}% bar."
            }
        }
        return out.joinToString(" ")
    }
}
