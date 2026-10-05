package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The option chain's drift through the day (market intelligence, round 12, 2026-10-05): "how has max pain moved
 * today?", "is the call wall shifting?", "where has the biggest put OI moved since morning?", "chain drift". From
 * Jarvis's own reads of the chain kept today ([ChainIntel.Book], in memory only): max pain at each read and when it
 * changed, where spot stood against it at the first read and now (closer or farther), the biggest call OI and the
 * biggest put OI among the strikes read (the "walls" traders talk of) and when each moved, and the put-call ratio
 * then and now. Every number from the chain with its time; how many reads it rests on said. Max pain and the walls
 * are readings of open interest, never a pull on price: facts only, no forecast, no advice, nothing acts. Pure.
 */
object ChainDrift {
    enum class Ask { MAX_PAIN, WALLS, ALL }

    /** What one read of the chain says: max pain, the biggest call and put OI strikes, the put-call ratio. */
    data class Point(val at: LocalDateTime, val spot: Double, val maxPain: Double?, val callWall: Double?, val putWall: Double?, val pcr: Double?)

    /** A path shows at most this many values; a longer one is told as its first, the count of changes and the newest. */
    const val MAX_STEPS = 5

    // ---- the questions --------------------------------------------------------------------------------------------

    private const val V = "drift|drifted|drifting|shift|shifted|shifting|moved|moving|move|changed|changing|change|migrated|migrating|travelled|traveled|gone|going|path|trail|journey"
    private const val MP = "(max pain|maxpain|max pain strike)"
    private const val IDX = "(nifty |bank nifty |banknifty |finnifty |fin nifty )?"
    private val MAX_PAIN = Regex(
        " $MP (has |is |s |have )?(been )?($V) | $MP (since|from) (the )?(morning|open|opening|9 15|start|first read) |" +
        " $MP (through|during|over|across) the day | $MP (so far|intraday) | $MP (trend|drift|path) (today|intraday|so far) | (drift|shift|change|movement|migration) (in|of) (the )?$IDX$MP |" +
        // Hinglish: "max pain kitna shift hua", "max pain kahan gaya".
        " $MP (kitna|kaise|kahan|kaha|kidhar) (shift|badla|badli|gaya|gayi|move|khiska|khiski|chala|chali) "
    )
    private val WALLS = Regex(
        " (call|put|oi|open interest) walls? |" +
        " (biggest|highest|max|maximum|largest|heaviest) (call|put) (oi|open interest)( strike)? (has |is |s )?(been )?($V) |" +
        " (biggest|highest|max|maximum|largest|heaviest) (call|put) (oi|open interest)( strike)? (since|through|during|over) (the )?(morning|open|opening|day) "
    )
    private val ALL = Regex(" (option )?chain drift | (option )?chain (has )?(drifted|evolved) | (how|what) (has|have|s) (the )?(option )?chain (drifted|evolved) |" +
        " $MP and (the )?(call |put |oi )?walls? | (call|put|oi) walls? and (the )?$MP ")
    /** Advice, forecasts and Boss's own book are not these questions. */
    private val NOT = Regex(" (should i|should we|shall i|do i|can i|will|would|tomorrow|next week|buy|sell|short|go long|my|mine|our|predict|prediction|forecast|target|expect|expected) ")
    /** The word itself asked ("what is max pain", "what's a call wall", "call wall ka matlab"): the glossary's. */
    private val WORD = Regex("^ (what is|what s|whats|define|explain|meaning of) (a |an )?(max pain|maxpain|call wall|put wall|oi wall)( mean| means)? $| (ka matlab|kya hota|kya hoti|matlab|meaning of|definition) ")

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")) + " "

    /** Which of these questions [text] asks, or null. Max pain as it stands stays [ChainRead]'s, the OI shift [ChainIntel]'s. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || WORD.containsMatchIn(t)) return null
        val mp = MAX_PAIN.containsMatchIn(t); val w = WALLS.containsMatchIn(t)
        return when {
            ALL.containsMatchIn(t) || (mp && w) -> Ask.ALL
            mp -> Ask.MAX_PAIN
            w -> Ask.WALLS
            else -> null
        }
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    /** [r]'s max pain: the chain's own, else worked out the Options tab's way over the strikes read (least total writers' loss). */
    fun maxPain(r: ChainIntel.Read): Double? {
        r.maxPain?.let { return it }
        val s = r.strikes.filter { it.strike > 0 }
        if (s.none { it.ceOi > 0 || it.peOi > 0 }) return null
        return s.minByOrNull { k ->
            s.sumOf { o -> (if (k.strike > o.strike) (k.strike - o.strike) * o.ceOi else 0.0) + (if (k.strike < o.strike) (o.strike - k.strike) * o.peOi else 0.0) }
        }?.strike
    }

    fun point(r: ChainIntel.Read): Point {
        val ce = r.strikes.filter { it.ceOi > 0 }.maxByOrNull { it.ceOi }?.strike
        val pe = r.strikes.filter { it.peOi > 0 }.maxByOrNull { it.peOi }?.strike
        val ceSum = r.strikes.sumOf { it.ceOi }; val peSum = r.strikes.sumOf { it.peOi }
        return Point(r.at, r.spot, maxPain(r), ce, pe, if (ceSum > 0) peSum.toDouble() / ceSum else null)
    }

    /** The values of [f] over [ps] where they changed: the first known value, then each change with its time. */
    internal fun path(ps: List<Point>, f: (Point) -> Double?): List<Pair<LocalDateTime, Double>> {
        val out = ArrayList<Pair<LocalDateTime, Double>>()
        for (p in ps) { val v = f(p) ?: continue; if (out.lastOrNull()?.second != v) out += p.at to v }
        return out
    }

    // ---- the answers ----------------------------------------------------------------------------------------------

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private fun label(u: String) = when (u) { "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; else -> u }
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun two(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = HM.format(t)

    private const val MP_NOTE = "Max pain is the strike where option writers would lose least at expiry, worked out from open interest; it moves as OI moves and is not a pull on price."
    private const val WALL_NOTE = "Traders read the biggest call OI as a ceiling and the biggest put OI as a floor; that is a reading, not a promise."

    /** "24,100 at 09:20, 24,150 at 11:02, then 24,200 at 12:40" (a long path: first, the count of changes, the newest few). */
    internal fun said(path: List<Pair<LocalDateTime, Double>>): String {
        fun one(p: Pair<LocalDateTime, Double>) = "${k(p.second)} at ${hm(p.first)}"
        if (path.size <= MAX_STEPS) return when (path.size) {
            1 -> one(path[0])
            else -> path.dropLast(1).joinToString(", ") { one(it) } + ", then " + one(path.last())
        }
        val tail = path.takeLast(3)
        return "${one(path[0])}, then ${path.size - 1} changes - latest ${tail.dropLast(1).joinToString(", ") { one(it) }}, then ${one(tail.last())}"
    }

    private fun side(spot: Double, level: Double) = when {
        spot > level -> "${pts(spot - level)} points above"
        spot < level -> "${pts(level - spot)} points below"
        else -> "right at"
    }

    /** Max pain through today's reads, and spot against it at the first read and now. */
    fun maxPainLine(ps: List<Point>): String {
        val path = path(ps) { it.maxPain }
        if (path.isEmpty()) return "The chain carries no open interest to work out max pain from."
        val first = ps.first { it.maxPain != null }; val last = ps.last { it.maxPain != null }
        var s = if (path.size == 1) "Max pain has stayed at ${k(path[0].second)} through all ${ps.count { it.maxPain != null }} reads."
        else {
            val net = path.last().second - path.first().second
            "Max pain: ${said(path)} - " + (if (net == 0.0) "back where it was at my first read." else "${if (net > 0) "up" else "down"} ${pts(net)} points since my first read.")
        }
        if (last.at != first.at) {
            val g0 = abs(first.spot - first.maxPain!!); val g1 = abs(last.spot - last.maxPain!!)
            s += " Spot was ${k(first.spot)} at ${hm(first.at)}, ${side(first.spot, first.maxPain)} max pain; at ${hm(last.at)} ${k(last.spot)}, ${side(last.spot, last.maxPain)} it" +
                when {
                    g1 < g0 -> " - closer to it than at my first read."
                    g1 > g0 -> " - farther from it than at my first read."
                    else -> "."
                }
        }
        return s
    }

    /** The biggest call and put OI strikes through today's reads, with spot's distance now, and the put-call ratio then and now. */
    fun wallsLine(ps: List<Point>, strikes: Int): String {
        val last = ps.last()
        val out = ArrayList<String>()
        val ce = path(ps) { it.callWall }; val pe = path(ps) { it.putWall }
        fun wall(name: String, p: List<Pair<LocalDateTime, Double>>, now: Double?): String? {
            if (p.isEmpty() || now == null) return null
            val where = "now ${side(now, last.spot)} spot"
            return if (p.size == 1) "The biggest $name OI has been at ${k(p[0].second)} all through my reads ($where)."
            else "The biggest $name OI: ${said(p)} ($where)."
        }
        val ceLine = wall("call", ce, last.callWall); val peLine = wall("put", pe, last.putWall)
        if (ceLine == null && peLine == null) return "The chain carries no open interest to read the biggest strikes from."
        out += "I read the $strikes strikes near the money."
        ceLine?.let { out += it }
        peLine?.let { out += it }
        val p0 = ps.firstOrNull { it.pcr != null }; val p1 = ps.lastOrNull { it.pcr != null }
        if (p0 != null && p1 != null && p0.at != p1.at) out += "Put-call ratio across those strikes: ${two(p0.pcr!!)} at ${hm(p0.at)}, ${two(p1.pcr!!)} at ${hm(p1.at)}."
        else if (p1 != null) out += "Put-call ratio across those strikes: ${two(p1.pcr!!)}."
        return out.joinToString(" ")
    }

    /** The answer to [a] from today's [reads] of one chain (oldest first). */
    fun answer(a: Ask, reads: List<ChainIntel.Read>, today: LocalDate): String {
        if (reads.isEmpty()) return "I have no read of that option chain today yet, Boss, so there is no drift to tell."
        val last = reads.last()
        // Only the reads of the newest read's expiry and day (the book keeps one, but say only what is comparable).
        val same = reads.filter { it.expiry == last.expiry && it.at.toLocalDate() == last.at.toLocalDate() }
        val ps = same.map { point(it) }
        val day = if (last.at.toLocalDate() == today) "today" else "on ${DAY.format(last.at)}"
        val exp = if (last.expiry == last.at.toLocalDate()) "expiring today" else "expiry ${DAY.format(last.expiry)}"
        val head = "${label(last.underlying)} chain $day ($exp)"
        if (same.size == 1) {
            val p = ps[0]
            val now = listOfNotNull(p.maxPain?.let { "max pain ${k(it)}" }, p.callWall?.let { "the biggest call OI at ${k(it)}" }, p.putWall?.let { "the biggest put OI at ${k(it)}" })
            return "$head: this is my first read of it ${if (day == "today") "today" else "that day"}, at ${hm(p.at)}" +
                (if (now.isNotEmpty()) " - ${now.joinToString(", ")}, spot ${k(p.spot)}" else "") +
                ". There is no drift to tell yet; ask me again later and I'll compare with this read, Boss."
        }
        val parts = ArrayList<String>()
        parts += "$head, from my ${same.size} reads between ${hm(same.first().at)} and ${hm(last.at)}."
        if (a == Ask.MAX_PAIN || a == Ask.ALL) parts += maxPainLine(ps)
        if (a == Ask.WALLS || a == Ask.ALL) parts += wallsLine(ps, last.strikes.size)
        if (a == Ask.MAX_PAIN || a == Ask.ALL) parts += MP_NOTE
        if (a == Ask.WALLS || a == Ask.ALL) parts += WALL_NOTE
        return parts.joinToString(" ") + " Those are the chain's own numbers, Boss, not advice."
    }
}
