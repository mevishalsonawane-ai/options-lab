package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityOverlay
import com.optionslab.engine.orb.LiquidityRules
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity 15+5's map of the market, said in words (2026-10-06): "where are the liquidity levels", "liquidity level kahan
 * hai", "what is liquidity waiting for", "how far is the next pool" - for BANKNIFTY (15- and 5-minute books) and FINNIFTY
 * (30- and 5-minute books). Read from the very code the arm decides with ([LiquidityRules.zones], the pool-on-a-swing rule
 * of [LiquidityRules.signal], [LiquidityRules.hasRoom]) on the chart's closed bars as the arm folds them
 * ([LiquidityOverlay.closedBars] on the 1-minute bars it reads): on each side of the price, the nearest pool sitting on a
 * still-active swing zone of its side (a close through it is the arm's entry: above buys a call, below a put), how far
 * it is, the next level beyond it (the trade's target) and whether that leaves the room the arm asks for (one index stop:
 * BANKNIFTY 30 points, FINNIFTY 15); and the nearest level of any kind when it is not that one.
 *
 * And a heads-up ([cues]): in the entry hours, the 1-minute price within [near] points of such a level with room - one
 * line, once per level a day, at most one per index in [RATE_MINUTES]. Information only: nothing here places, changes or
 * suggests an order; the arm decides on its own. Pure: no clock, no network.
 */
object LiquidityMap {
    /** The heads-up's distance: BANKNIFTY 15 points, FINNIFTY 8. */
    fun near(underlying: String): Double = if (underlying == "FINNIFTY") 8.0 else 15.0

    /** At most one heads-up per index in this many minutes. */
    const val RATE_MINUTES = 10L

    /** A price older than this many minutes is stale: no heads-up from it, and an answer says its time. */
    const val FRESH_MINUTES = 2L

    /** [r]'s price was read within [FRESH_MINUTES] of [now] (its minute started then or later). */
    fun fresh(r: Read, now: LocalDateTime): Boolean = r.priceAt?.let { !it.isBefore(now.minusMinutes(FRESH_MINUTES)) } ?: false

    // ---- the question ---------------------------------------------------------------------------------------------

    /** What was asked: [underlyings] the indices (both when none is named; empty: only an index the arm does not read), [minutes] one book's chart, or all. */
    data class Q(val underlyings: List<String>, val minutes: Int? = null)

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liqudity|liquidty) ")
    private val LEVEL = Regex(" (level|levels|pool|pools|zone|zones|swing|swings|map) ")
    private val WAIT = Regex(" (waiting for|waiting on|wait kar|wait ho|intezaar|intezar|intazar|looking for|watching for|watching) ")
    private val POOL = Regex(" (next|nearest|closest|agla|agli|agle) (liquidity )?(pool|pools) ")
    private val FAR = Regex(" (how far|kitna door|kitni door|kitna dur|kitni dur|distance|away|where|kahan|kaha|kidhar) ")
    /** Not the map: its size, health, trades, record, switch or a change to it, a definition, or another product's liquidity. */
    private val NOT = Regex(" (lot|lots|size|health|healthy|doing|exit|exited|exits|trades|took|take|taken|pnl|p l|profit|loss|losses|won|lost|" +
        "record|backtest|tested|switch|turn|arm it|disarm|set|change|karo|kar do|band|chalu|why|kyun|kyon|mean|means|meaning|define|" +
        "what is a|what is an|what are pools|what is liquidity pool|what is a liquidity|kya hota|kya hai liquidity|option|options|ce|pe|strike|oi|volume|spread|stock|stocks) ")
    private val BANK = Regex(" (bank ?nifty|banknifty|bnf|nifty bank|bank) ")
    private val FIN = Regex(" (fin ?nifty|finnifty|finnfty|nifty fin|nifty financial|fin) ")
    private val OTHER = Regex(" (nifty|sensex|midcap nifty|midcpnifty) ")
    private val BOOK = Regex(" (5|15|30|five|fifteen|thirty) ?(min|mins|minute|minutes|m) ")

    /** Is Liquidity 15+5's map asked (its levels, what it waits for, how far the next pool is)? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        val liq = LIQ.containsMatchIn(t)
        if (!(liq && (LEVEL.containsMatchIn(t) || WAIT.containsMatchIn(t)) || POOL.containsMatchIn(t) && FAR.containsMatchIn(t))) return null
        val bank = BANK.containsMatchIn(t)
        val fin = FIN.containsMatchIn(t)
        val rest = t.replace(BANK, " ").replace(FIN, " ")
        val unds = when {
            bank || fin -> listOfNotNull("BANKNIFTY".takeIf { bank }, "FINNIFTY".takeIf { fin })
            OTHER.containsMatchIn(rest) -> emptyList()
            else -> LiquidityRules.UNDERLYINGS
        }
        val minutes = BOOK.find(t)?.groupValues?.get(1)?.let { when (it) { "five" -> 5; "fifteen" -> 15; "thirty" -> 30; else -> it.toInt() } }
        return Q(unds, minutes)
    }

    const val NOT_HERE = "Liquidity 15+5 reads BankNifty and FinNifty only, Boss: ask for their liquidity levels."

    // ---- the read -------------------------------------------------------------------------------------------------

    /** One level: a swing zone or a pool, on [side] (+1 above: a high's, -1 below: a low's), at its outer [edge]. */
    data class Level(val kind: String, val side: Int, val edge: Double, val onSwing: Boolean) {
        /** "pool on a swing high", "swing low", "pool". */
        val name: String get() = if (kind == "pool") (if (onSwing) "pool on a swing ${if (side > 0) "high" else "low"}" else "pool")
            else "swing ${if (side > 0) "high" else "low"}"
    }

    /**
     * One side of the price: [trigger] the nearest pool on an active swing of this side (a close through it is the entry),
     * [nearest] the nearest active level of any kind, [target] the next level beyond the trigger, [room] from the trigger to
     * it (a close just through the level), [enough] the arm's room rule met (no level beyond counts as room).
     */
    data class Side(val side: Int, val trigger: Level?, val nearest: Level?, val target: Double?, val room: Double?, val enough: Boolean)

    enum class State { OK, NO_DATA, LOADING }

    /**
     * One book's map: [price] the latest 1-minute close (at [priceAt]), [lastBar] the chart's last closed bar's start,
     * [entryOpen] whether a close of the bar forming now could enter (its end within 09:20-14:00).
     */
    data class Read(val underlying: String, val minutes: Int, val state: State, val price: Double? = null, val priceAt: LocalDateTime? = null,
                    val lastBar: LocalDateTime? = null, val above: Side? = null, val below: Side? = null, val entryOpen: Boolean = false, val bars: Int = 0)

    /** Closed bars needed before the levels can be read (the arm's own minimum). */
    val MIN_BARS: Int = 2 * LiquidityRules.SWING_LOOKBACK + 2

    /** The end of the [minutes]-minute bar forming at [now] (bars from 09:15), or null before 09:15. */
    fun barEnd(now: LocalDateTime, minutes: Int): LocalDateTime? {
        val open = now.toLocalDate().atTime(LiquidityRules.SESSION_OPEN)
        if (now.isBefore(open)) return null
        val m = java.time.Duration.between(open, now).toMinutes()
        return open.plusMinutes((m / minutes + 1) * minutes)
    }

    /** True when a close of the [minutes]-minute bar forming at [now] could enter (the arm's 09:20-14:00). */
    fun entryOpen(now: LocalDateTime, minutes: Int): Boolean = barEnd(now, minutes)?.let { LiquidityRules.mayEnterAt(it) } ?: false

    /**
     * One book's map from the index's 1-minute bars [ones] (the arm's read: earlier sessions and today's) at [now]: the
     * chart's closed bars as the arm folds them, their levels, and the price of the last minute that started before now.
     */
    fun read(ones: List<Bar>, underlying: String, minutes: Int, now: LocalDateTime): Read {
        val seen = ones.filter { it.start.isBefore(now) }
        val last = seen.maxByOrNull { it.start } ?: return Read(underlying, minutes, State.NO_DATA)
        val bars = LiquidityOverlay.closedBars(seen, minutes, now, inputMinutes = 1)
        if (bars.size < MIN_BARS) return Read(underlying, minutes, State.LOADING, last.close, last.start, bars.lastOrNull()?.start, bars = bars.size)
        return read(bars, LiquidityRules.zones(bars), underlying, minutes, last.close, last.start, entryOpen(now, minutes))
    }

    /** One book's map from its closed [bars] and their [zones] (as [LiquidityRules.zones] gives them), the [price] now. */
    fun read(bars: List<Bar>, zones: List<LiquidityRules.Zone>, underlying: String, minutes: Int, price: Double, priceAt: LocalDateTime?,
             entryOpen: Boolean): Read {
        val i = bars.lastIndex
        val active = zones.filter { it.known <= i && it.broken < 0 }
        return Read(underlying, minutes, State.OK, price, priceAt, bars.lastOrNull()?.start,
            side(active, 1, price, underlying), side(active, -1, price, underlying), entryOpen, bars.size)
    }

    private fun overlaps(a: LiquidityRules.Zone, b: LiquidityRules.Zone) = a.bottom <= b.top && b.bottom <= a.top

    private fun side(active: List<LiquidityRules.Zone>, side: Int, price: Double, underlying: String): Side {
        val mine = active.filter { it.side == side }
        val swings = mine.filter { it.kind == "swing" }
        fun level(z: LiquidityRules.Zone) = Level(z.kind, side, z.edge, z.kind == "pool" && swings.any { s -> overlaps(s, z) })
        // Nearest in the side's direction: the lowest edge above, the highest below (a level the price already sits past,
        // its bar not yet closed through it, is the nearest of all).
        val order = compareBy<LiquidityRules.Zone> { side * it.edge }
        val nearest = mine.minWithOrNull(order)?.let(::level)
        val trig = mine.filter { it.kind == "pool" && swings.any { s -> overlaps(s, it) } }.minWithOrNull(order)
            ?: return Side(side, null, nearest, null, null, false)
        val level = trig.edge
        // As [LiquidityRules.signal] on a close just through the level: the next active level of the side beyond it.
        val ahead = mine.filter { side * (it.edge - level) > 0 }.map { it.edge }
        val target = if (ahead.isEmpty()) null else if (side > 0) ahead.min() else ahead.max()
        val enough = LiquidityRules.hasRoom(LiquidityRules.Signal(side, level, target), level, underlying)
        return Side(side, level(trig), nearest, target, target?.let { side * (it - level) }, enough)
    }

    // ---- the words ------------------------------------------------------------------------------------------------

    fun indexName(underlying: String): String = when (underlying) { "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; else -> underlying }
    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%.0f", abs(x))
    private fun hm(t: LocalTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private val HOURS = "${hm(LiquidityRules.FIRST_ENTRY)}-${hm(LiquidityRules.LAST_ENTRY)}"

    private fun sideWords(s: Side?, price: Double, underlying: String): String {
        val dir = if (s == null || s.side > 0) "above" else "below"
        val head = if (s == null || s.side > 0) "Above" else "Below"
        if (s == null) return "$head: no level read."
        fun away(edge: Double): String {
            val d = s.side * (edge - price)
            return if (d >= 0) "${pts(d)} pts away" else "price already ${pts(d)} pts past it, its bar not yet closed"
        }
        val t = s.trigger
        if (t == null) {
            val l = s.nearest ?: return "$head: no active level."
            return "$head: the nearest level is ${n(l.edge)} (${l.name}, ${away(l.edge)}); no pool sits on a swing there, so a close $dir it would not trade."
        }
        val right = if (s.side > 0) "call" else "put"
        val first = s.nearest?.takeIf { it.edge != t.edge }?.let { " First comes the ${it.name} at ${n(it.edge)} (${away(it.edge)})." } ?: ""
        val need = pts(LiquidityRules.MIN_ROOM_STOPS * LiquidityRules.indexStopPoints(underlying))
        val room = when {
            s.target == null -> "no level beyond it, so room enough."
            s.enough -> "room to the next level ${n(s.target)} is ${pts(s.room ?: 0.0)} pts - enough."
            else -> "but room to the next level ${n(s.target)} is only ${pts(s.room ?: 0.0)} pts (needs $need) - it would be skipped."
        }
        val verb = if (s.target == null || s.enough) "would buy a $right; " else "would be a break, "
        return "$head: a close $dir ${n(t.edge)} (${t.name}, ${away(t.edge)}) $verb$room$first"
    }

    /** One book's map in words: "BankNifty 5-min: price 54,120. Above: a close above 54,180 (...) would buy a call; ...". */
    fun say(r: Read, now: LocalDateTime): String {
        val head = "${indexName(r.underlying)} ${r.minutes}-min"
        return when (r.state) {
            State.NO_DATA -> "$head: no candles to read the levels from just now."
            State.LOADING -> "$head: only ${r.bars} closed bars so far - the levels need $MIN_BARS (the last days' candles are still loading)."
            State.OK -> {
                val price = r.price ?: 0.0
                // An earlier day's price with its day; today's older than [FRESH_MINUTES] with its time (the feed is behind).
                val at = r.priceAt
                val asOf = when {
                    at == null -> ""
                    at.toLocalDate() != now.toLocalDate() ->
                        " (as of ${at.toLocalDate().dayOfMonth} ${at.month.name.lowercase(Locale.ENGLISH).replaceFirstChar { c -> c.uppercase() }.take(3)} ${hm(at.toLocalTime())})"
                    !fresh(r, now) -> " (as of ${hm(at.toLocalTime())})"
                    else -> ""
                }
                "$head: price ${n(price)}$asOf. ${sideWords(r.above, price, r.underlying)} ${sideWords(r.below, price, r.underlying)}"
            }
        }
    }

    /**
     * Jarvis's answer to [q]: each book asked (BANKNIFTY 15 and 5, FINNIFTY 30 and 5) from [reads], with whether the arm is
     * [armed] (null: not known) and whether it is in its entry hours at [now] - said either way, the levels described.
     */
    fun answer(q: Q, reads: List<Read>, armed: Boolean?, now: LocalDateTime): String {
        if (q.underlyings.isEmpty()) return NOT_HERE
        val books = reads.filter { it.underlying in q.underlyings && (q.minutes == null || it.minutes == q.minutes) }
            .ifEmpty { reads.filter { it.underlying in q.underlyings } }
        val t = now.toLocalTime()
        val hours = !t.isBefore(LiquidityRules.FIRST_ENTRY) && !t.isAfter(LiquidityRules.LAST_ENTRY)
        val state = when (armed) {
            true -> if (hours) "Liquidity 15+5 is armed and in its entry hours ($HOURS)." else "Liquidity 15+5 is armed, but outside its entry hours ($HOURS): no entry now - the levels as they stand."
            false -> "Liquidity 15+5 is switched off - " + (if (hours) "this is what it would watch." else "and outside its entry hours ($HOURS) - the levels as they stand.")
            null -> if (hours) "Liquidity 15+5's levels, Boss." else "Outside Liquidity 15+5's entry hours ($HOURS) - the levels as they stand."
        }
        if (books.isEmpty()) return "$state No candles to read the levels from just now, Boss."
        return (listOf("Boss, $state") + books.map { say(it, now) } + "Information only - the arm decides on its own.").joinToString("\n")
    }

    // ---- the heads-up ---------------------------------------------------------------------------------------------

    /** What was told today: the levels ("day|index|side|level") and when each index was last told. */
    data class Told(val keys: Set<String> = emptySet(), val last: Map<String, LocalDateTime> = emptyMap())

    /** One heads-up: [text] the line, [key] its level's. */
    data class Cue(val underlying: String, val minutes: Int, val side: Int, val level: Double, val distance: Double, val text: String, val key: String)

    /**
     * The book's heads-up at [now], or null: in its entry hours (the bar forming now could enter), the price - read within
     * the last [FRESH_MINUTES] minutes ([fresh]; a stale price is never a heads-up) - within [near] points short of a pool
     * on a swing whose close-through has room ("BankNifty is 12 pts from 54,180 - a 5-min close above it would make
     * Liquidity buy a call").
     */
    fun cue(r: Read, now: LocalDateTime): Cue? {
        if (r.state != State.OK || !r.entryOpen || !fresh(r, now)) return null
        val price = r.price ?: return null
        return listOfNotNull(r.above, r.below).mapNotNull { s ->
            val t = s.trigger ?: return@mapNotNull null
            if (!s.enough) return@mapNotNull null
            val d = s.side * (t.edge - price)
            if (d < 0 || d > near(r.underlying)) return@mapNotNull null
            val day = r.priceAt?.toLocalDate()?.toString() ?: ""
            Cue(r.underlying, r.minutes, s.side, t.edge, d,
                "${indexName(r.underlying)} is ${pts(d)} pts from ${n(t.edge)} - a ${r.minutes}-min close ${if (s.side > 0) "above" else "below"} it would make Liquidity buy a ${if (s.side > 0) "call" else "put"}.",
                "$day|${r.underlying}|${s.side}|${t.edge}")
        }.minByOrNull { it.distance }
    }

    /**
     * The heads-ups due at [now] from every book's [reads], given what was [told]: one per index at most, the nearest
     * level first, each level once a day, none for an index told within [RATE_MINUTES]. Earlier days' keys are dropped.
     */
    fun cues(reads: List<Read>, now: LocalDateTime, told: Told): Pair<List<Cue>, Told> {
        val day = now.toLocalDate().toString()
        var keys = told.keys.filter { it.startsWith("$day|") }.toSet()
        val last = told.last.filterValues { it.toLocalDate() == now.toLocalDate() }.toMutableMap()
        val out = ArrayList<Cue>()
        for ((und, rs) in reads.groupBy { it.underlying }) {
            if (last[und]?.let { now.isBefore(it.plusMinutes(RATE_MINUTES)) } == true) continue
            val c = rs.mapNotNull { cue(it, now) }.filter { it.key.startsWith("$day|") && it.key !in keys }.minByOrNull { it.distance } ?: continue
            out += c; keys = keys + c.key; last[und] = now
        }
        return out to Told(keys, last)
    }
}
