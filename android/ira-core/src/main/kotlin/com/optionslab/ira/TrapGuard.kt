package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The order flow's trap guard (Boss, 9 Oct 2026: "in order flow there are traps - at the last second big players pull their
 * orders; find a solution for all the traps"; parameters from research/HUNT_R8_ORDER_FLOW_TRAPS.md). Each guard is a small
 * pure rule; [OrderFlow.Tracker] and [OrderFlow.Board.read] apply them, and every one that fires is a [Trap] flag on the read,
 * shown in the detail and logged beside each strategy signal.
 *
 *  1. Flicker: resting size counts only after 3 s AND 3 distinct book snapshots ([levelWeight]).
 *  2. Walls: a level 3x the 5-minute median level size (all 10 levels; at least 1) is a wall: it counts only once alive 5 s,
 *     at half weight when one order makes it, and no level counts more than 2x the median. Levels 4-5 more than 3x the top
 *     three are left out ([walls]).
 *  3. Pulls: a level that drops 50% or more with under half the drop traded was pulled ([pulled]), at all times; the side
 *     that pulled gets no depth credit for 10 s.
 *  4. Kite's total buy / sell quantities: shown only, never in the score.
 *  5. Executed flow first: CONFIRM agrees only when the tick-rule signed volume agrees ([OrderFlow.agreement]).
 *  6. Absorption ([Defence]): traded at a level 3x the most it ever showed, the level held 10 s and the price moved a tick
 *     the defended way - a hidden defender: never counted for the attackers. Broken within 30 s: "exhaustion" instead.
 *  7. Price confirmation: a lean the future's mid moved against by more than a tick over the minute does not count ([against]).
 *  8. Stop hunts and failed breaks ([stopHunt]): a burst of aggressive volume back where it started within 60 s is a stop
 *     hunt, within 120 s a failed break; for 2 minutes after, CONFIRM refuses entries in the burst's direction.
 *  9. Time traps ([timeWindow]): NSE futures' first 3 minutes (options' flow from 9:20), MCX's first 5; NSE's closing-price
 *     window, the last 30 minutes of F&O (15:10-15:40 since 3 Aug 2026, 15:00-15:30 before: [Session.nse]) and MCX's last 5
 *     minutes; the traded index's expiry day from 14:30; 5 minutes either side of
 *     scheduled news ([eventMinutes]: RBI 10:00, US CPI / jobs 8:30 and FOMC 14:00 New York time, EIA crude Wednesday and gas
 *     Thursday 10:30 New York time for MCX, DST-aware). No opinion there.
 * 10. Feed health: repeats, older states (volume below the running maximum) and crossed books are dropped; the book's parts
 *     are off when the future has no distinct update for 5 s (an option 15 s), when trades arrive more than 3 s late (median
 *     over 30 s, against the phone's own steady offset), and for 10 s after an update burst 5x normal with no new volume for
 *     2 s ([stuffing]); trades 10 s late or older states re-sent: the feed is UNRELIABLE and CONFIRM has no opinion.
 * 11. Illiquid prints: one trade over 5x the median trade size that does not move the price is ignored ([illiquidPrint]);
 *     only the ATM ±2 strikes are followed.
 * 12. Cross-check: the future leaning one way while the ATM options are bought strongly the other way: neutral ([contradicts]).
 * Arms decide on closed candles; a CONFIRM decision waits until 3 s after the minute ([DECIDE_WAIT_MS]) for late packets.
 */
object TrapGuard {
    enum class Trap(val words: String) {
        PULL_BID("pull detected (bids)"), PULL_ASK("pull detected (offers)"),
        ABSORPTION_BUY("absorption: buying met a hidden seller"), ABSORPTION_SELL("absorption: selling met a hidden buyer"),
        EXHAUSTION_BUY("exhaustion: a hidden seller gave way"), EXHAUSTION_SELL("exhaustion: a hidden buyer gave way"),
        PRICE_AGAINST("price moved against the flow"), STOP_HUNT("possible stop hunt"), FAILED_BREAK("failed break"),
        UNRELIABLE("unreliable feed"), BOOK_OFF("book signals off (stale, late or stuffed feed)"), TIME_WINDOW("time window"),
        CONTRADICTION("futures and options disagree"), NO_EXECUTED("no executed flow behind it"),
    }

    const val PERSIST_MS = 3_000L
    const val PERSIST_SNAPS = 3
    const val WALL_X = 3.0
    const val WALL_MS = 5_000L
    const val CAP_X = 2.0
    const val DEEP_RATIO = 3.0
    const val PULL_DROP = 0.5
    const val PULL_WINDOW_SEC = 10
    const val PULL_BIG = 2.0
    const val ABSORB_X = 3.0
    const val ABSORB_HOLD_MS = 10_000L
    const val EXHAUST_SEC = 30L
    const val HUNT_CONFIRM_SEC = 60L
    const val FAILED_BREAK_SEC = 120L
    const val LOCKOUT_SEC = 120L
    const val BURST_SD = 3.0
    const val STUFF_X = 5.0
    const val STUFF_HOLD_SEC = 10L
    const val BOOK_STALE_FUTURE_MS = 5_000L
    const val BOOK_STALE_OPTION_MS = 15_000L
    const val LATE_MS = 3_000.0
    const val LATE_UNRELIABLE_MS = 10_000.0
    const val PRINT_X = 5.0
    const val STALE_LIMIT = 3
    const val DECIDE_WAIT_MS = 3_000L
    const val EVENT_PAD_MIN = 5
    const val TICK = 0.05

    // ---- 1, 2: what resting depth counts ---------------------------------------------------------------------------------

    /** A shown price: since when it has rested there, and in how many distinct book snapshots. */
    data class Seen(val firstMs: Long, val snaps: Int)

    /** [since] brought up to [levels] (one distinct snapshot at [nowMs]): a new price starts over, a price gone is forgotten. */
    fun keepSince(since: MutableMap<Double, Seen>, levels: List<KiteTicks.Level>, nowMs: Long) {
        val prices = levels.filter { it.qty > 0 }.map { it.price }.toSet()
        since.keys.retainAll(prices)
        for (p in prices) since[p] = since[p]?.let { it.copy(snaps = it.snaps + 1) } ?: Seen(nowMs, 1)
    }

    /** [levels] without levels 4-5 more than [DEEP_RATIO]x the top three's mean (a fake deep wall). */
    fun walls(levels: List<KiteTicks.Level>): List<KiteTicks.Level> {
        if (levels.size <= 3) return levels
        val top = levels.take(3).map { it.qty }.average()
        return levels.take(3) + levels.drop(3).filter { top <= 0 || it.qty <= DEEP_RATIO * top }
    }

    /**
     * How much of level [l] counts: nothing before 3 s and 3 snapshots; a wall ([WALL_X]x the [median] level) nothing before
     * 5 s and half when one order makes it; never more than [CAP_X]x the median (no median yet: no cap, no walls).
     */
    fun levelWeight(l: KiteTicks.Level, seen: Seen?, nowMs: Long, median: Double?): Double {
        if (seen == null || nowMs - seen.firstMs < PERSIST_MS || seen.snaps < PERSIST_SNAPS) return 0.0
        var q = l.qty.toDouble()
        val m = median?.takeIf { it > 0 } ?: return q
        if (q >= WALL_X * m) {
            if (nowMs - seen.firstMs < WALL_MS) return 0.0
            if (l.orders < 2) q /= 2
        }
        return minOf(q, CAP_X * m)
    }

    /** The resting size that counts on one side ([levelWeight] of each level, deep walls left out). */
    fun persistentDepth(levels: List<KiteTicks.Level>, since: Map<Double, Seen>, nowMs: Long, median: Double?): Double =
        walls(levels).sumOf { levelWeight(it, since[it.price], nowMs, median) }

    /**
     * Size pulled from one side: the levels of [prev] still inside the range [cur] shows that dropped by half or more; when
     * under half of that drop [traded], all of it less what traded was pulled, else nothing (it was taken).
     */
    fun pulled(prev: List<KiteTicks.Level>, cur: List<KiteTicks.Level>, bids: Boolean, traded: Long, full: Int = 5): Long {
        if (prev.isEmpty()) return 0
        val edge = if (cur.size >= full) (if (bids) cur.minOf { it.price } else cur.maxOf { it.price }) else null
        val now = cur.associate { it.price to it.qty }
        var lost = 0L
        for (l in prev) {
            val inView = edge == null || (if (bids) l.price >= edge - 1e-9 else l.price <= edge + 1e-9)
            if (!inView || l.qty <= 0) continue
            val drop = l.qty - (now[l.price] ?: 0L)
            if (drop >= PULL_DROP * l.qty) lost += drop
        }
        val t = maxOf(0L, traded)
        return if (lost > 0 && 2 * t < lost) lost - t else 0
    }

    /** The median of [xs] (NaN left out); null when none. */
    fun median(xs: Collection<Double>): Double? {
        val s = xs.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return null
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    // ---- 10, 11: the feed --------------------------------------------------------------------------------------------------

    /** A crossed (or locked) book: a glitch, dropped. */
    fun crossed(bid: Double, ask: Double): Boolean = bid > 0 && ask > 0 && bid >= ask - 1e-9

    /** One print over [PRINT_X]x the median trade size that did not move the price: ignored. */
    fun illiquidPrint(size: Long, medianSize: Double?, priceMoved: Boolean): Boolean =
        !priceMoved && medianSize != null && medianSize > 0 && size > PRINT_X * medianSize

    /**
     * Quote stuffing: the last 2 seconds' updates ([lastTwo]) over [STUFF_X]x the instrument's median rate ([perSec]) with no
     * new volume in them ([volumeLastTwo] 0).
     */
    fun stuffing(perSec: List<Int>, lastTwo: Int, volumeLastTwo: Long): Boolean {
        val m = median(perSec.map { it.toDouble() }) ?: return false
        return volumeLastTwo == 0L && lastTwo / 2.0 > STUFF_X * maxOf(m, 1.0)
    }

    /** How late trades arrive: the median of [ages] (ms, arrival less the exchange's trade time) above their minimum (the steady clock offset). */
    fun lateness(ages: List<Long>): Double? {
        if (ages.size < 3) return null
        val base = ages.minOrNull() ?: return null
        return median(ages.map { (it - base).toDouble() })
    }

    // ---- 6: absorption --------------------------------------------------------------------------------------------------

    /**
     * A defended level on one side: the offers ([ask] true, defending against buying) or the bids. Fed every quote, it says
     * when a level absorbed the attack ([events]) and when such a level then broke within [EXHAUST_SEC] (exhausted).
     */
    class Defence(private val ask: Boolean) {
        data class Event(val sec: Long, val price: Double, var exhausted: Boolean = false)
        val events = ArrayDeque<Event>()
        private var price = Double.NaN
        private var firstMs = 0L
        private var lastTradeMs = 0L
        private var traded = 0L
        private var maxShown = 0L
        private var mid0 = 0.0

        private fun shownAt(levels: List<KiteTicks.Level>, p: Double) = levels.firstOrNull { abs(it.price - p) < 1e-9 }?.qty ?: 0L

        /** Previous quote's best price on this side [prevBest] and size [prevQty], the new quote, and [dv] traded at [last]. */
        fun update(prevBest: Double, prevQty: Long, prevMid: Double, levels: List<KiteTicks.Level>, bid: Double, ask: Double,
                   last: Double, dv: Long, mid: Double, ms: Long) {
            val s = if (this.ask) 1 else -1
            // A trade at the best price this side showed.
            if (dv > 0 && abs(last - prevBest) < 1e-9) {
                if (price.isNaN() || abs(price - prevBest) > 1e-9) { price = prevBest; firstMs = ms; traded = 0; maxShown = prevQty; mid0 = prevMid }
                traded += dv; lastTradeMs = ms
            }
            // Old events: a level broken within 30 s exhausted its defender; older than a minute, forgotten.
            for (e in events) if (!e.exhausted && ms / 1000 - e.sec <= EXHAUST_SEC && broken(e.price, bid, ask, last, s)) e.exhausted = true
            while (events.isNotEmpty() && ms / 1000 - events.first().sec > 60) events.removeFirst()
            if (price.isNaN()) return
            maxShown = maxOf(maxShown, shownAt(levels, price))
            when {
                broken(price, bid, ask, last, s) || ms - lastTradeMs > 30_000 -> price = Double.NaN
                ms - firstMs >= ABSORB_HOLD_MS && traded >= ABSORB_X * maxOf(maxShown, 1) && s * (mid0 - mid) >= TICK - 1e-9 -> {
                    events.addLast(Event(ms / 1000, price)); price = Double.NaN
                }
            }
        }

        /** Through the level: offers - traded above it or bid at it; bids - traded below it or offered at it. */
        private fun broken(p: Double, bid: Double, ask: Double, last: Double, s: Int): Boolean =
            if (s > 0) last > p + 1e-9 || bid >= p - 1e-9 else last < p - 1e-9 || ask <= p + 1e-9
    }

    // ---- 7: price confirmation ---------------------------------------------------------------------------------------------

    /** A lean ([lean] +1 buyers, −1 sellers) the mid moved against by more than one [tick] over the window ([move]). */
    fun against(lean: Int, move: Double, tick: Double = TICK): Boolean = lean != 0 && lean * move < -tick - 1e-9

    // ---- 8: stop hunts and failed breaks ---------------------------------------------------------------------------------------

    /** A burst that came back: when it came back, its direction, and whether it was within 60 s (a hunt) or 120 s (a failed break). */
    data class Hunt(val backSec: Long, val dir: Int, val failedBreak: Boolean)

    /**
     * The latest burst - 10 s of signed volume [BURST_SD] sd from its own history, with the price moving its way - whose mid
     * came back to where it started within [FAILED_BREAK_SEC], when that was within the last [LOCKOUT_SEC]; null: none.
     */
    fun stopHunt(bars: List<OrderFlow.Bar>, nowSec: Long): Hunt? {
        if (bars.size < 40) return null
        val roll = OrderFlow.rolling(bars, 10) { it.delta.toDouble() }
        val mean = roll.average()
        val sd = sqrt(roll.sumOf { (it - mean) * (it - mean) } / roll.size)
        if (sd <= 0) return null
        val from = nowSec - FAILED_BREAK_SEC - LOCKOUT_SEC - 10
        var found: Hunt? = null
        var i = bars.indexOfFirst { it.sec >= from }.coerceAtLeast(0)
        while (i < bars.size) {
            // A burst's first second: where it started is the mid just before its 10-second window.
            if (abs((roll[i] - mean) / sd) < BURST_SD) { i++; continue }
            val b = bars[i]
            val dir = if (roll[i] > 0) 1 else -1
            val start = bars.lastOrNull { it.sec <= b.sec - 10 }?.mid
            // The burst's last second, and the furthest its price went.
            var end = i
            while (end + 1 < bars.size && abs((roll[end + 1] - mean) / sd) >= BURST_SD && (roll[end + 1] > 0) == (dir > 0)) end++
            val peak = (i..end).maxOf { dir * bars[it].mid }
            if (start != null && peak - dir * start > 0) {
                val back = bars.firstOrNull { it.sec > b.sec && it.sec <= b.sec + FAILED_BREAK_SEC && dir * (it.mid - start) <= 0 }
                if (back != null && nowSec - back.sec <= LOCKOUT_SEC) found = Hunt(back.sec, dir, back.sec - b.sec > HUNT_CONFIRM_SEC)
            }
            i = end + 1
        }
        return found
    }

    // ---- 9: time traps -------------------------------------------------------------------------------------------------------

    /**
     * A market's session in IST minutes: its first [openGuard] and last [closeGuard] minutes are traps; on its expiry day from
     * [expiryFrom]; [eventMins] the minutes of scheduled news (each ±[EVENT_PAD_MIN]).
     */
    data class Session(val openMin: Int, val closeMin: Int, val openGuard: Int, val closeGuard: Int, val expiryDay: Boolean = false,
                       val expiryFrom: Int = 14 * 60 + 30, val eventMins: List<Int> = emptyList(), val optionsFrom: Int = 0) {
        companion object {
            /**
             * NSE on [day]: futures' first 3 minutes, options from 9:20, the closing-price window (the volume-weighted average
             * of F&O's last 30 minutes: 15:10-15:40 since 3 Aug 2026, 15:00-15:30 before), expiry day from 14:30.
             */
            fun nse(day: LocalDate): Session = Session(com.optionslab.engine.NseHours.OPEN, com.optionslab.engine.NseHours.foClose(day),
                3, com.optionslab.engine.NseHours.FO_CLOSING_WINDOW_MIN, optionsFrom = 9 * 60 + 20)
            /** NSE's session by today's rules (F&O to 15:40, the closing-price window from 15:10). */
            val NSE = nse(com.optionslab.engine.NseHours.FO_EXTENDED_FROM)
            /** MCX: the first 5 minutes after 9:00 and the last 5 (23:30; 23:55 in US winter: set by the app). */
            val MCX = Session(9 * 60, 23 * 60 + 30, 5, 5, expiryFrom = 24 * 60)
        }
    }

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val NY: ZoneId = ZoneId.of("America/New_York")

    /** The IST minute of the day of epoch second [sec]. */
    fun istMinute(sec: Long): Int = (((sec + 19_800L) % 86_400 + 86_400) % 86_400 / 60).toInt()

    /** Why [sec] is a time trap for [s], or null. Outside the session: none (the feed is quiet). */
    fun timeWindow(sec: Long, s: Session): String? {
        val m = istMinute(sec)
        return when {
            m < s.openMin || m >= s.closeMin -> null
            m < s.openMin + s.openGuard -> "the first ${s.openGuard} minutes after the open"
            m >= s.closeMin - s.closeGuard -> if (s.closeGuard >= 30) "the closing settlement window (from ${com.optionslab.engine.minuteText(s.closeMin - s.closeGuard)})"
                else "the last ${s.closeGuard} minutes before the close"
            s.expiryDay && m >= s.expiryFrom -> "the expiry day's last hour"
            s.eventMins.any { abs(m - it) <= EVENT_PAD_MIN } -> "minutes around scheduled news"
            else -> null
        }
    }

    /** The options' flow counts only from [Session.optionsFrom] (NSE: 9:20). */
    fun optionsReady(sec: Long, s: Session): Boolean = istMinute(sec) >= s.optionsFrom

    /** The IST minute of [h]:[m] New York time on [day] (DST-aware). */
    fun nyToIst(day: LocalDate, h: Int, m: Int): Int = day.atTime(h, m).atZone(NY).withZoneSameInstant(IST).let { it.hour * 60 + it.minute }

    /**
     * The IST minutes of [day]'s scheduled news from the calendar's [names]: RBI policy 10:00; US CPI and jobs (NFP) 8:30 New
     * York time; FOMC 14:00 New York time; and for MCX ([mcx]) the EIA's crude report on Wednesdays and gas on Thursdays,
     * 10:30 New York time.
     */
    fun eventMinutes(day: LocalDate, names: List<String>, mcx: Boolean): List<Int> {
        val out = ArrayList<Int>()
        for (n in names.map { it.uppercase() }) {
            if ("RBI" in n) out += 10 * 60
            if ("CPI" in n || "PAYROLL" in n || "NFP" in n || "JOBS" in n) out += nyToIst(day, 8, 30)
            if ("FOMC" in n || "FED " in "$n ") out += nyToIst(day, 14, 0)
        }
        if (mcx && (day.dayOfWeek == DayOfWeek.WEDNESDAY || day.dayOfWeek == DayOfWeek.THURSDAY)) out += nyToIst(day, 10, 30)
        return out.distinct()
    }

    // ---- 12: futures against options -----------------------------------------------------------------------------------------

    /** The future leaning one way ([buyers]) while the ATM options are bought strongly the other way (|net| ≥ 0.5). */
    fun contradicts(buyers: Int, optionNet: Double): Boolean = !optionNet.isNaN() &&
        ((buyers >= OrderFlow.THRESHOLD && optionNet <= -0.5) || (buyers <= 100 - OrderFlow.THRESHOLD && optionNet >= 0.5))
}
