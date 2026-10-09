package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The big-move recorder (HUNT R11 part C, HUNT R10 section 4; Boss, 9 Oct 2026). Recording only: nothing here decides,
 * places, skips or changes a trade, and no strategy reads it.
 *
 *  - TRIGGER: a closed 1-minute candle of the NIFTY or BANKNIFTY near FUTURE whose |log return| is at least [N] (4.7) times
 *    the normal move of that minute of the day ([normalBp]): R11's "top 1%" candle, about two a day per index. The normal
 *    is a 375-minute table per index (the mean |1-minute move| of the cash index at each minute, ±2 minutes smoothed, over
 *    the 250 sessions to 6 Oct 2026, from R11's panels), scaled by today's VIX against the table's mean VIX ([VIX_REF]).
 *  - MERGE: a trigger less than [MERGE_MIN] minutes after the previous one (merged or not) is the same event (an M line).
 *  - CONTROLS: [CONTROLS] random minutes per index per day with no top-5% minute ([TOP5]) in the 30 minutes before or the
 *    10 after - ordinary moments to read the big ones against. A control found not quiet in its 10 minutes after is
 *    dropped (its file deleted) and another minute drawn.
 *  - THE FILE, per event: `orderflow/events/<day>_<index>_<hhmm>_<kind>.csv.gz` - an H line (the trigger and its context:
 *    VIX, OI, VWAP distance, value-area position, gamma sign, IB and day type, time, scheduled events, the flow read and
 *    its traps), a column line, then S lines - 1-second bars with the 5-level book - for 5 minutes before the candle, the
 *    candle and 5 minutes after, of the index future, both cash indices and VIX, both indices' ATM ±2 options and the
 *    other index's future; M lines for merged triggers and an E line when the window closed. About 0.5 MB gzipped.
 *  - THE SUMMARY ([summarize]): the flow before and during, calls against puts, the book in the way thinning, VIX, and
 *    what stood out first - for the "Big moves" list and Jarvis's "why did BankNifty jump at 10:32" ([asked], [answer]).
 *    Timing only, never a cause, never a forecast.
 *
 * Pure: no clock, no file, no Android.
 */
object MoveEvents {
    /** A candle this many times its normal move triggers (R11's top 1%: 4.66 sigma in the design period). */
    const val N = 4.7
    /** A top-5% minute (R11: 2.81): what a control's quiet window must not hold. */
    const val TOP5 = 2.8
    const val MERGE_MIN = 10
    const val BEFORE_SEC = 300
    const val CANDLE_SEC = 60
    const val AFTER_SEC = 300
    const val CONTROLS = 2
    const val QUIET_BEFORE_MIN = 30
    const val QUIET_AFTER_MIN = 10
    /** Controls are drawn from these minutes of the day (09:45 - 15:05; 0 is 09:15). */
    const val CONTROL_FROM = 30
    const val CONTROL_TO = 350
    /** The last 1-minute candle read (15:39; F&O trades to 15:40 from 3 Aug 2026). */
    const val LAST_MINUTE = 384
    /** Event files are kept twelve months, and at most this many bytes (the oldest go first). */
    const val KEEP_DAYS = 366L
    const val BUDGET_BYTES = 1_536L * 1024 * 1024
    /** The in-memory 1-second ring of the cash indices (30 minutes). */
    const val RING_SEC = 1800
    /** The full-mode set while the recorder follows both indices' options (Kite allows 3,000 a connection). */
    const val RECORD_CAP = 30
    /** The table's mean India VIX (its 250 sessions' median VIX of the first half hour, averaged). */
    const val VIX_REF = 14.03
    /** The two indices recorded, VIX's name in the files, and the stand-out z that names "what came first". */
    val INDICES = listOf("NIFTY", "BANKNIFTY")
    const val VIX = "INDIAVIX"
    const val FIRST_Z = 2.0

    private const val IST_SEC = 19_800L
    private const val OPEN_SEC = (9 * 60 + 15) * 60L

    fun other(name: String): String = if (name == "NIFTY") "BANKNIFTY" else "NIFTY"

    // ---- time --------------------------------------------------------------------------------------------------------------

    /** The IST day number (epoch day) of epoch second [sec]. */
    fun dayOf(sec: Long): Long = Math.floorDiv(sec + IST_SEC, 86_400L)

    fun date(sec: Long): LocalDate = LocalDate.ofEpochDay(dayOf(sec))

    /** The minute of the session of epoch second [sec]: 0 for 09:15 IST (negative before). */
    fun minuteIndex(sec: Long): Int = Math.floorDiv(Math.floorMod(sec + IST_SEC, 86_400L) - OPEN_SEC, 60L).toInt()

    /** "10:32" (IST) of epoch second [sec]. */
    fun hhmm(sec: Long): String {
        val m = Math.floorMod(sec + IST_SEC, 86_400L) / 60
        return "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)
    }

    // ---- the normal move ---------------------------------------------------------------------------------------------------

    /**
     * The normal |1-minute move| (basis points) of [name] in session minute [idx] (0 = 09:15) at India VIX [vix] (null: the
     * table as is). Minutes after the table's last (15:29) use its last value: the futures trade to 15:40, the index to 15:30.
     * Null for another name or a minute before the open.
     *
     * TODO(R11 follow-up): the table is the CASH index's; the futures' minute moves are a little noisier. Once 4-6 weeks of
     * the 1-second file exist, rebuild it from the futures' own minutes (and check the trigger rate stays near 2 a day).
     */
    fun normalBp(name: String, idx: Int, vix: Double?): Double? {
        val t = when (name) { "NIFTY" -> NIFTY_BP; "BANKNIFTY" -> BANKNIFTY_BP; else -> return null }
        if (idx < 0) return null
        val base = t[idx.coerceAtMost(t.size - 1)]
        val scale = vix?.takeIf { it.isFinite() && it > 0 }?.let { (it / VIX_REF).coerceIn(0.5, 3.0) } ?: 1.0
        return base * scale
    }

    // ---- the trigger, merging and the controls -----------------------------------------------------------------------------

    enum class Kind(val tag: String, val dir: Int, val words: String) {
        BIG_UP("big_up", 1, "up"), BIG_DOWN("big_down", -1, "down"), CONTROL("control", 0, "control");

        companion object { fun of(tag: String): Kind? = entries.firstOrNull { it.tag == tag } }
    }

    /** One event: [name]'s candle that started at [startSec] (epoch s), its move ([retBp], [x] times [normalBp]). */
    data class Trigger(
        val name: String, val startSec: Long, val kind: Kind, val retBp: Double, val x: Double, val normalBp: Double, val vix: Double?,
    ) {
        /** "2026-10-09_BANKNIFTY_1032_big_up": the file's name without ".csv.gz". */
        val id: String get() = "${date(startSec)}_${name}_${hhmm(startSec).replace(":", "")}_${kind.tag}"
        /** The window: 5 minutes before the candle, the candle, 5 minutes after. */
        val fromSec: Long get() = startSec - BEFORE_SEC
        val endSec: Long get() = startSec + CANDLE_SEC + AFTER_SEC
        /** The move's direction (a control: its own candle's sign). */
        val dir: Int get() = if (kind.dir != 0) kind.dir else if (retBp > 0) 1 else if (retBp < 0) -1 else 0
    }

    sealed class Signal {
        /** A new event: save the 5 minutes before now, then the next 5. */
        data class Start(val t: Trigger) : Signal()
        /** A trigger within [MERGE_MIN] minutes of [into]'s last: the same event. */
        data class Merge(val into: Trigger, val startSec: Long, val retBp: Double, val x: Double) : Signal()
        /** A control that turned out not quiet: its file goes. */
        data class Drop(val t: Trigger) : Signal()
    }

    /**
     * Fed each 1-second bar of the index futures in time order ([onSecond]), it closes their 1-minute candles and says
     * when an event starts, merges or (a control) is dropped. Not thread-safe (the order-flow thread alone calls it).
     */
    class Detector(private val random: java.util.Random = java.util.Random(), private val n: Double = N) {
        private class State {
            var day = Long.MIN_VALUE
            var minute = Long.MIN_VALUE; var last = Double.NaN          // the candle being built
            var prevMinute = Long.MIN_VALUE; var prevClose = Double.NaN  // the last one closed
            var lastBig = Long.MIN_VALUE; var lastBigTrigger: Trigger? = null
            val x = HashMap<Int, Double>()                               // session minute -> |x|
            val candidates = ArrayList<Int>()
            var controls = 0
            val pending = ArrayList<Trigger>()                           // controls still in their 10 quiet minutes
        }

        private val states = HashMap<String, State>()

        /** TEST: the day's control minutes still to come for [name]. */
        fun candidates(name: String): List<Int> = states[name]?.candidates?.toList().orEmpty()

        /** A 1-second bar of [name]'s future at [sec] with last price [last]; [vix] India VIX now (null: not known). */
        fun onSecond(name: String, sec: Long, last: Double, vix: Double?): List<Signal> {
            if (name !in INDICES || !(last > 0)) return emptyList()
            val s = states.getOrPut(name) { State() }
            val m = Math.floorDiv(sec, 60L)
            val out = ArrayList<Signal>()
            if (s.minute != Long.MIN_VALUE && m > s.minute) out += close(name, s, s.minute * 60, s.last, vix)
            if (m >= s.minute) { s.minute = m; s.last = last }
            return out
        }

        /** A closed 1-minute candle of [name]'s future: its start [startSec] and close [close]. */
        fun onMinute(name: String, startSec: Long, close: Double, vix: Double?): List<Signal> {
            if (name !in INDICES || !(close > 0)) return emptyList()
            return close(name, states.getOrPut(name) { State() }, startSec, close, vix)
        }

        private fun close(name: String, s: State, startSec: Long, close: Double, vix: Double?): List<Signal> {
            val day = dayOf(startSec)
            if (day != s.day) {
                s.day = day; s.prevMinute = Long.MIN_VALUE; s.prevClose = Double.NaN; s.lastBig = Long.MIN_VALUE; s.lastBigTrigger = null
                s.x.clear(); s.controls = 0; s.pending.clear(); s.candidates.clear()
                repeat(CONTROLS) { draw(s, CONTROL_FROM - 1) }
            }
            val m = Math.floorDiv(startSec, 60L)
            val ret = if (s.prevMinute == m - 1 && s.prevClose > 0) ln(close / s.prevClose) else Double.NaN
            s.prevMinute = m; s.prevClose = close
            val idx = minuteIndex(startSec)
            val out = ArrayList<Signal>()
            val normal = normalBp(name, idx, vix)
            val x = if (ret.isNaN() || normal == null || normal <= 0 || idx < 1 || idx > LAST_MINUTE) Double.NaN else abs(ret) * 1e4 / normal
            if (!x.isNaN()) s.x[idx] = x
            // The controls in their quiet 10 minutes after: a top-5% minute drops one; past its 10 minutes it stays.
            val it = s.pending.iterator()
            while (it.hasNext()) {
                val c = it.next()
                val ci = minuteIndex(c.startSec)
                if (!x.isNaN() && x >= TOP5 && idx > ci && idx <= ci + QUIET_AFTER_MIN) {
                    it.remove(); s.controls--; out += Signal.Drop(c); draw(s, idx)
                } else if (idx >= ci + QUIET_AFTER_MIN) it.remove()
            }
            if (!x.isNaN() && x >= n) {
                val t = Trigger(name, startSec, if (ret > 0) Kind.BIG_UP else Kind.BIG_DOWN, ret * 1e4, x, normal!!, vix)
                val into = s.lastBigTrigger
                if (into != null && s.lastBig != Long.MIN_VALUE && m - s.lastBig < MERGE_MIN) out += Signal.Merge(into, startSec, ret * 1e4, x)
                else { out += Signal.Start(t); s.lastBigTrigger = t }
                s.lastBig = m
            }
            // A control minute: taken when the 30 minutes before were quiet (and seen), else another is drawn.
            while (s.candidates.isNotEmpty() && s.candidates.first() < idx) { s.candidates.removeAt(0); draw(s, idx) }
            if (s.candidates.firstOrNull() == idx) {
                s.candidates.removeAt(0)
                val seen = (idx - QUIET_BEFORE_MIN until idx).count { s.x.containsKey(it) }
                val quiet = (idx - QUIET_BEFORE_MIN until idx).none { (s.x[it] ?: 0.0) >= TOP5 }
                if (!x.isNaN() && x < TOP5 && quiet && seen >= QUIET_BEFORE_MIN - 5 && s.controls < CONTROLS) {
                    val c = Trigger(name, startSec, Kind.CONTROL, ret * 1e4, x, normal!!, vix)
                    s.controls++; s.pending += c; out += Signal.Start(c)
                } else draw(s, idx)
            }
            return out
        }

        /** One more control minute after [after] (uniform over what is left of the day), while controls are owed. */
        private fun draw(s: State, after: Int) {
            if (s.controls + s.candidates.size >= CONTROLS) return
            val lo = maxOf(after + 1, CONTROL_FROM)
            if (lo > CONTROL_TO) return
            var k = lo + random.nextInt(CONTROL_TO - lo + 1)
            var tries = 0
            while (k in s.candidates && tries++ < 50) k = lo + random.nextInt(CONTROL_TO - lo + 1)
            if (k !in s.candidates) { s.candidates += k; s.candidates.sort() }
        }
    }

    // ---- the ring buffer ---------------------------------------------------------------------------------------------------

    /**
     * The last [capacity] seconds of one series, a value a second (a later value in the same second replaces it). Fixed
     * memory: two arrays of [capacity]. Not thread-safe by itself.
     */
    class Ring<T>(val capacity: Int = RING_SEC) {
        private val secs = LongArray(capacity) { Long.MIN_VALUE }
        private val vals = arrayOfNulls<Any?>(capacity)
        var newest = Long.MIN_VALUE; private set

        fun put(sec: Long, v: T) {
            if (newest != Long.MIN_VALUE && sec <= newest - capacity) return     // older than the ring holds
            val i = Math.floorMod(sec, capacity.toLong()).toInt()
            secs[i] = sec; vals[i] = v
            if (sec > newest) newest = sec
        }

        @Suppress("UNCHECKED_CAST")
        fun get(sec: Long): T? {
            val i = Math.floorMod(sec, capacity.toLong()).toInt()
            return if (newest != Long.MIN_VALUE && secs[i] == sec && sec > newest - capacity) vals[i] as T? else null
        }

        /** The seconds from [from] to [to] (both included) still held, oldest first. */
        fun range(from: Long, to: Long): List<Pair<Long, T>> {
            val out = ArrayList<Pair<Long, T>>()
            if (newest == Long.MIN_VALUE) return out
            for (s in maxOf(from, newest - capacity + 1)..minOf(to, newest)) get(s)?.let { out += s to it }
            return out
        }

        /** The newest value at or before [sec] (looking back at most [maxBack] seconds), or null. */
        fun at(sec: Long, maxBack: Int = 60): T? {
            for (s in sec downTo sec - maxBack) get(s)?.let { return it }
            return null
        }
    }

    // ---- rows ----------------------------------------------------------------------------------------------------------------

    /** One instrument's second as written: its key, the bar, the OI change from its previous second and the seconds missed. */
    data class Row(
        val token: Long, val name: String, val role: String, val strike: Double, val expiry: String, val bar: OrderFlow.Bar,
        val oiChange: Long = 0, val gap: Int = 0,
    )

    /** The cash index's (or VIX's) second as a bar: its last print only. */
    fun indexBar(sec: Long, last: Double): OrderFlow.Bar =
        OrderFlow.Bar(sec, last, last, 0.0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, 0)

    /** Each instrument's previous second and OI: a row's gap and OI change. Bounded (forgets beyond 400 instruments). */
    class Sequencer {
        private val prev = LinkedHashMap<Long, LongArray>()

        fun clear() = prev.clear()

        fun row(token: Long, name: String, role: String, strike: Double, expiry: String, bar: OrderFlow.Bar): Row {
            val p = prev[token]
            val gap = if (p == null || bar.sec <= p[0]) 0 else (bar.sec - p[0] - 1).coerceAtMost(99_999L).toInt()
            val oiChange = if (p != null && p[1] > 0 && bar.oi > 0) bar.oi - p[1] else 0L
            if (p == null) {
                if (prev.size >= 400) prev.remove(prev.keys.first())
                prev[token] = longArrayOf(bar.sec, bar.oi)
            } else { p[0] = maxOf(p[0], bar.sec); if (bar.oi > 0) p[1] = bar.oi }
            return Row(token, name, role, strike, expiry, bar, oiChange, gap)
        }
    }

    // ---- the files -----------------------------------------------------------------------------------------------------------

    /** A number as written: empty for NaN, whole numbers without a point, else the shortest exact form. */
    fun num(x: Double): String = when {
        x.isNaN() || x.isInfinite() -> ""
        x == Math.rint(x) && abs(x) < 1e15 -> x.toLong().toString()
        else -> x.toString()
    }

    /** [x] rounded to [d] decimals, then written ([num]). */
    fun num(x: Double, d: Int): String {
        if (x.isNaN() || x.isInfinite()) return ""
        val f = Math.pow(10.0, d.toDouble())
        return num(Math.round(x * f) / f)
    }

    private fun dbl(s: String): Double = if (s.isEmpty()) Double.NaN else s.toDouble()
    private fun lng(s: String, empty: Long = 0): Long = if (s.isEmpty()) empty else s.toLong()

    private val BOOK_COLS = (listOf("b", "a").flatMap { side -> (1..5).flatMap { i -> listOf("$side${i}p", "$side${i}q", "$side${i}o") } })

    /** The event file's S columns (the column line after the H line; '#' so a reader taking "S," lines skips it). */
    val COLUMNS: String = ("#S,sec_rel,epoch_ms,token,name,role,strike,expiry,mid,last,high,low,bid,ask,spread,bid_qty,ask_qty,bid5_qty,ask5_qty," +
        "vol,buy_vol,sell_vol,q_buy_vol,q_sell_vol,prints,max_print,trade_age_ms,ofi,mofi,queue_imb,depth_imb,pdepth,pull_bid,pull_ask," +
        "tbq,tsq,oi,oi_change,updates,stale,gap_s,") + BOOK_COLS.joinToString(",")

    /** One S line: [r] at [secRel] seconds from the trigger candle's start (no line break). */
    fun sLine(secRel: Long, r: Row): String {
        val b = r.bar
        val sb = StringBuilder(420)
        sb.append("S,").append(secRel).append(',').append(b.sec * 1000).append(',').append(r.token).append(',').append(r.name).append(',')
            .append(r.role).append(',').append(if (r.strike > 0) num(r.strike) else "").append(',').append(r.expiry).append(',')
        val spread = if (b.bid > 0 && b.ask > 0) b.ask - b.bid else Double.NaN
        for (v in listOf(num(b.mid), num(b.last), num(b.high), num(b.low), num(b.bid), num(b.ask), num(spread, 2))) sb.append(v).append(',')
        sb.append(b.bidQty).append(',').append(b.askQty).append(',').append(b.bid5).append(',').append(b.ask5).append(',')
        sb.append(b.vol).append(',').append(b.buyVol).append(',').append(b.sellVol).append(',').append(b.qBuy).append(',').append(b.qSell).append(',')
            .append(b.prints).append(',').append(b.maxPrint).append(',').append(num(b.ageMs, 0)).append(',')
        sb.append(num(b.ofi, 2)).append(',').append(num(b.mofi, 2)).append(',').append(num(b.queue, 4)).append(',').append(num(b.depth, 4)).append(',')
            .append(num(b.pdepth, 4)).append(',').append(b.pullBid).append(',').append(b.pullAsk).append(',')
        sb.append(if (b.tbq >= 0) b.tbq.toString() else "").append(',').append(if (b.tsq >= 0) b.tsq.toString() else "").append(',')
            .append(b.oi).append(',').append(r.oiChange).append(',').append(b.updates).append(',').append(b.stale).append(',').append(r.gap)
        val k = b.book
        for (side in 0..1) for (i in 0 until 5) {
            if (k == null) { sb.append(",,,"); continue }
            val px = if (side == 0) k.bidPx(i) else k.askPx(i)
            val q = if (side == 0) k.bidQty(i) else k.askQty(i)
            val o = if (side == 0) k.bidOrders(i) else k.askOrders(i)
            if (px <= 0 && q == 0L) sb.append(",,,") else sb.append(',').append(num(px)).append(',').append(q).append(',').append(o)
        }
        return sb.toString()
    }

    /** An S line read back: its second from the trigger and its row (the book whole; the median level size is not written). */
    fun parseS(line: String): Pair<Long, Row>? {
        val c = line.split(',')
        if (c.size < 41 + 30 || c[0] != "S") return null
        return runCatching {
            val sec = c[2].toLong() / 1000
            val book = if ((0 until 10).all { c[41 + it * 3].isEmpty() }) null else {
                fun lv(at: Int) = (0 until 5).mapNotNull { i ->
                    val p = c[at + i * 3]
                    if (p.isEmpty()) null else com.optionslab.engine.KiteTicks.Level(p.toDouble(), c[at + i * 3 + 1].toLong(), c[at + i * 3 + 2].toInt())
                }
                OrderFlow.Book.of(lv(41), lv(56))
            }
            val tbq = lng(c[34], -1); val tsq = lng(c[35], -1)
            val bar = OrderFlow.Bar(sec, dbl(c[8]), dbl(c[9]), dbl(c[27]), lng(c[20]), lng(c[21]), lng(c[19]), dbl(c[30]), dbl(c[29]),
                OrderFlow.ratio(tbq.takeIf { it >= 0 }, tsq.takeIf { it >= 0 }) ?: Double.NaN, lng(c[36]), lng(c[22]) - lng(c[23]), dbl(c[31]),
                lng(c[32]), lng(c[33]), lng(c[38]).toInt(), lng(c[39]).toInt(), Double.NaN, dbl(c[26]),
                high = dbl(c[10]), low = dbl(c[11]), bid = dbl(c[12]), ask = dbl(c[13]), bidQty = lng(c[15]), askQty = lng(c[16]),
                bid5 = lng(c[17]), ask5 = lng(c[18]), qBuy = lng(c[22]), qSell = lng(c[23]), prints = lng(c[24]).toInt(), maxPrint = lng(c[25]),
                mofi = dbl(c[28]).let { if (it.isNaN()) 0.0 else it }, tbq = tbq, tsq = tsq, book = book)
            c[1].toLong() to Row(c[3].toLong(), c[4], c[5], c[6].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0, c[7], bar, lng(c[37]), lng(c[40]).toInt())
        }.getOrNull()
    }

    /** What the H line says about the market at the trigger (any of it may be unknown). */
    data class Context(
        val vix: Double? = null, val oi: Long? = null, val price: Double? = null, val vwapSd: Double? = null, val valuePos: String? = null,
        val gamma: String? = null, val zeroGammaDist: Double? = null, val ibHigh: Double? = null, val ibLow: Double? = null,
        val openType: String? = null, val dayType: String? = null, val minutesOpen: Int? = null, val expiryDay: Boolean? = null,
        val events: List<String> = emptyList(), val flow: String? = null, val buyers: Int? = null, val traps: List<String> = emptyList(),
        val headline: String? = null,
    )

    private fun clean(s: String) = s.replace(Regex("[,=\\r\\n|]"), " ").trim().take(120)

    /** The H line of [t] with its context [c], written at phone time [phoneMs]. */
    fun headerLine(t: Trigger, c: Context, phoneMs: Long): String {
        val kv = LinkedHashMap<String, String>()
        kv["id"] = t.id; kv["name"] = t.name; kv["kind"] = t.kind.tag; kv["candle_start"] = t.startSec.toString(); kv["time"] = hhmm(t.startSec)
        kv["ret_bp"] = num(t.retBp, 2); kv["x"] = num(t.x, 2); kv["n"] = num(N); kv["normal_bp"] = num(t.normalBp, 3)
        kv["vix"] = c.vix?.let { num(it, 2) } ?: t.vix?.let { num(it, 2) } ?: ""; kv["vix_ref"] = num(VIX_REF)
        kv["oi"] = c.oi?.toString() ?: ""; kv["price"] = c.price?.let { num(it, 2) } ?: ""
        kv["vwap_sd"] = c.vwapSd?.let { num(it, 2) } ?: ""; kv["value"] = c.valuePos?.let(::clean) ?: ""
        kv["gamma"] = c.gamma?.let(::clean) ?: ""; kv["zero_gamma_dist"] = c.zeroGammaDist?.let { num(it, 1) } ?: ""
        kv["ib_high"] = c.ibHigh?.let { num(it, 2) } ?: ""; kv["ib_low"] = c.ibLow?.let { num(it, 2) } ?: ""
        kv["open_type"] = c.openType?.let(::clean) ?: ""; kv["day_type"] = c.dayType?.let(::clean) ?: ""
        kv["minutes_open"] = c.minutesOpen?.toString() ?: ""; kv["expiry_day"] = c.expiryDay?.let { if (it) "1" else "0" } ?: ""
        kv["events"] = c.events.joinToString(";") { clean(it) }; kv["flow"] = c.flow?.let(::clean) ?: ""
        kv["buyers"] = c.buyers?.toString() ?: ""; kv["traps"] = c.traps.joinToString(";") { clean(it) }
        kv["headline"] = c.headline?.let(::clean) ?: ""; kv["phone_ms"] = phoneMs.toString()
        return "H," + kv.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** An H line's fields (key to value), or null. */
    fun parseH(line: String): Map<String, String>? {
        if (!line.startsWith("H,")) return null
        return line.removePrefix("H,").split(',').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
    }

    fun mLine(startSec: Long, retBp: Double, x: Double): String = "M,$startSec,${hhmm(startSec)},${num(retBp, 2)},${num(x, 2)}"
    /** The closing line; [complete] false: the window closed without its last seconds (the stream or the app stopped). */
    fun eLine(endSec: Long, rows: Int, merged: Int, complete: Boolean = true): String = "E,$endSec,$rows,$merged" + if (complete) "" else ",partial"

    /** One event file read back. [complete]: its E line was written (the window closed with the app running). */
    data class Event(val header: Map<String, String>, val rows: List<Pair<Long, Row>>, val merges: List<String>, val complete: Boolean) {
        val name: String get() = header["name"].orEmpty()
        val kind: Kind get() = Kind.of(header["kind"].orEmpty()) ?: Kind.CONTROL
        val startSec: Long get() = header["candle_start"]?.toLongOrNull() ?: 0L
        val retBp: Double get() = header["ret_bp"]?.toDoubleOrNull() ?: Double.NaN
        val x: Double get() = header["x"]?.toDoubleOrNull() ?: Double.NaN
    }

    /** An event file's lines read back (unknown lines skipped). */
    fun parse(lines: Sequence<String>): Event? {
        var h: Map<String, String>? = null
        val rows = ArrayList<Pair<Long, Row>>()
        val merges = ArrayList<String>()
        var done = false
        for (l in lines) when {
            l.startsWith("S,") -> parseS(l)?.let { rows += it }
            l.startsWith("H,") -> if (h == null) h = parseH(l)
            l.startsWith("M,") -> merges += l.split(',').getOrElse(2) { "" }
            l.startsWith("E,") -> done = !l.endsWith(",partial")
        }
        return h?.let { Event(it, rows, merges, done) }
    }

    /** The daily 1-second file's columns (the first 16 as before 10 Oct 2026; the rest added for R10's Q and K records). */
    const val DAILY_HEADER = "epoch_sec,time_ist,token,name,role,strike,mid,last,ofi,buy_vol,sell_vol,vol,depth_imb,queue_imb,buy_sell_ratio,oi," +
        "bid,ask,bid_qty,ask_qty,bid5_qty,ask5_qty,b1q,b2q,b3q,b4q,b5q,a1q,a2q,a3q,a4q,a5q,b1o,b2o,b3o,b4o,b5o,a1o,a2o,a3o,a4o,a5o," +
        "pull_bid,pull_ask,q_buy_vol,q_sell_vol,prints,max_print,high,low,mofi,tbq,tsq,updates,stale,trade_age_ms,gap_s\n"

    /** One daily line of [r] at IST time [timeIst] ("10:32:05"), with its line break. */
    fun dailyLine(r: Row, timeIst: String): String {
        val b = r.bar
        fun d(x: Double) = if (x.isNaN()) "" else "%.4f".format(Locale.ENGLISH, x)
        val sb = StringBuilder(300)
        sb.append(b.sec).append(',').append(timeIst).append(',').append(r.token).append(',').append(r.name).append(',').append(r.role)
            .append(',').append(if (r.strike > 0) d(r.strike) else "").append(',').append(d(b.mid)).append(',').append(d(b.last))
            .append(',').append(d(b.ofi)).append(',').append(b.buyVol).append(',').append(b.sellVol).append(',').append(b.vol)
            .append(',').append(d(b.depth)).append(',').append(d(b.queue)).append(',').append(d(b.ratio)).append(',').append(b.oi)
        sb.append(',').append(num(b.bid)).append(',').append(num(b.ask)).append(',').append(if (b.bid > 0) b.bidQty.toString() else "")
            .append(',').append(if (b.ask > 0) b.askQty.toString() else "").append(',').append(if (b.bid > 0) b.bid5.toString() else "")
            .append(',').append(if (b.ask > 0) b.ask5.toString() else "")
        val k = b.book
        for (f in 0 until 4) for (i in 0 until 5) {
            sb.append(',')
            if (k != null) sb.append(when (f) { 0 -> k.bidQty(i); 1 -> k.askQty(i); 2 -> k.bidOrders(i).toLong(); else -> k.askOrders(i).toLong() })
        }
        sb.append(',').append(b.pullBid).append(',').append(b.pullAsk).append(',').append(b.qBuy).append(',').append(b.qSell)
            .append(',').append(b.prints).append(',').append(b.maxPrint).append(',').append(num(b.high)).append(',').append(num(b.low))
            .append(',').append(num(b.mofi, 2)).append(',').append(if (b.tbq >= 0) b.tbq.toString() else "").append(',').append(if (b.tsq >= 0) b.tsq.toString() else "")
            .append(',').append(b.updates).append(',').append(b.stale).append(',').append(num(b.ageMs, 0)).append(',').append(r.gap).append('\n')
        return sb.toString()
    }

    /** The per-minute footprint file's columns, and one minute's lines (each traded price's buy and sell volume). */
    const val FOOT_HEADER = "minute_epoch,time_ist,name,price,buy_vol,sell_vol\n"

    fun footLines(name: String, minute: Long, prices: List<Auction.Foot>): String {
        val sb = StringBuilder()
        val t = hhmm(minute * 60)
        for (f in prices) sb.append(minute * 60).append(',').append(t).append(',').append(name).append(',').append(num(f.price))
            .append(',').append(f.buy).append(',').append(f.sell).append('\n')
        return sb.toString()
    }

    // ---- the kept files ------------------------------------------------------------------------------------------------------

    /** An event file's name read back. */
    data class Named(val day: LocalDate, val name: String, val hhmm: String, val kind: Kind, val file: String)

    private val NAME = Regex("^(\\d{4}-\\d{2}-\\d{2})_(NIFTY|BANKNIFTY)_(\\d{4})_(big_up|big_down|control)\\.csv\\.gz$")

    fun fileName(t: Trigger): String = "${t.id}.csv.gz"

    fun parseName(file: String): Named? = NAME.find(file)?.let { m ->
        runCatching { Named(LocalDate.parse(m.groupValues[1]), m.groupValues[2], m.groupValues[3], Kind.of(m.groupValues[4])!!, file) }.getOrNull()
    }

    /**
     * The event files to delete on [today]: those older than [KEEP_DAYS], then the oldest until the rest fit [budget] bytes
     * (never today's). [files] are (name, bytes).
     */
    fun rotate(files: List<Pair<String, Long>>, today: LocalDate, budget: Long = BUDGET_BYTES): List<String> {
        val named = files.mapNotNull { (f, b) -> parseName(f)?.let { it to b } }.sortedWith(compareBy({ it.first.day }, { it.first.hhmm }))
        val out = ArrayList<String>()
        var total = named.sumOf { it.second }
        for ((n, b) in named) {
            if (n.day.isBefore(today.minusDays(KEEP_DAYS - 1)) || (total > budget && n.day.isBefore(today))) { out += n.file; total -= b }
        }
        return out
    }

    // ---- the summary ---------------------------------------------------------------------------------------------------------

    /** One event read for the list and Jarvis: flow before / in the last minute / during, the book in the way, VIX, what came first. */
    data class Summary(
        val name: String, val kind: Kind, val startSec: Long, val retBp: Double, val x: Double, val dir: Int,
        /** The future's tick-rule delta over −300..−60 s, −60..0 s and the candle (0..60 s). */
        val flowBefore: Long, val flowLead: Long, val flowDuring: Long,
        /** This index's ATM calls' minus puts' signed volume over the same three windows. */
        val optBefore: Long, val optLead: Long, val optDuring: Long,
        /** The future's mean 5-level size on the side in the way (offers for an up move, bids for a down move); NaN unknown. */
        val wayBefore: Double, val wayLead: Double, val wayDuring: Double,
        /** India VIX a minute before the candle, at its start and at its end (NaN unknown). */
        val vixBefore: Double, val vixAt: Double, val vixAfter: Double,
        /** What stood out first (z ≥ [FIRST_Z] against its own −5..−1 minute), with the second it did; earliest first. */
        val first: List<Pair<String, Long>>,
        val header: Map<String, String>, val complete: Boolean, val rows: Int, val merges: List<String>,
    ) {
        fun thinning(lead: Boolean): Double = if (wayBefore.isNaN() || wayBefore <= 0) Double.NaN else ((if (lead) wayLead else wayDuring) - wayBefore) / wayBefore
    }

    private fun sumIn(rows: List<Pair<Long, Row>>, from: Long, to: Long, f: (Row) -> Long): Long =
        rows.filter { it.first in from until to }.sumOf { f(it.second) }

    private fun meanIn(rows: List<Pair<Long, Row>>, from: Long, to: Long, f: (Row) -> Double): Double {
        val v = rows.filter { it.first in from until to }.map { f(it.second) }.filter { !it.isNaN() }
        return if (v.isEmpty()) Double.NaN else v.average()
    }

    /** The second [f] of a series (sec_rel to value) first stood out the move's way: z ≥ [FIRST_Z] of its 10-second sum. */
    internal fun firstOut(values: Map<Long, Double>, level: Boolean): Long? {
        val xs = (-BEFORE_SEC.toLong() until CANDLE_SEC.toLong())
        // Levels carry forward; flows are 0 in a second with none.
        val v = DoubleArray(xs.count())
        var lastV = Double.NaN
        for ((i, s) in xs.withIndex()) {
            val got = values[s]
            v[i] = if (level) { if (got != null && !got.isNaN()) lastV = got; lastV } else got ?: 0.0
        }
        val roll = DoubleArray(v.size) { Double.NaN }
        for (i in 9 until v.size) {
            var s = 0.0; var k = 0
            for (j in i - 9..i) if (!v[j].isNaN()) { s += v[j]; k++ }
            roll[i] = if (k == 0) Double.NaN else if (level) s / k else s
        }
        val split = BEFORE_SEC - 60
        val base = (9 until split).map { roll[it] }.filter { !it.isNaN() }
        if (base.size < 30) return null
        val mean = base.average()
        val sd = sqrt(base.sumOf { (it - mean) * (it - mean) } / base.size)
        for (i in split until v.size) {
            val r = roll[i]
            if (r.isNaN()) continue
            val z = if (sd < 1e-9) (if (r > mean + 1e-9) Double.POSITIVE_INFINITY else 0.0) else (r - mean) / sd
            if (z >= FIRST_Z) return xs.first + i
        }
        return null
    }

    /** [e] read for the list and Jarvis. */
    fun summarize(e: Event): Summary {
        val name = e.name
        val dir = if (e.kind.dir != 0) e.kind.dir else if (e.retBp >= 0) 1 else -1
        val rows = e.rows
        val fut = rows.filter { it.second.name == name && it.second.role == OrderFlow.Role.FUTURE.name }
        val opts = rows.filter { it.second.name == name && it.second.role in OPTION_ROLES }
        val vix = rows.filter { it.second.name == VIX && it.second.role == INDEX_ROLE }
        val idx = rows.filter { it.second.name == name && it.second.role == INDEX_ROLE }
        fun optNet(r: Row) = if (r.role.endsWith("CE")) r.bar.delta else -r.bar.delta
        fun way(r: Row) = (if (dir > 0) r.bar.ask5 else r.bar.bid5).toDouble().takeIf { it > 0 } ?: Double.NaN
        fun vixAt(s: Long) = vix.lastOrNull { it.first <= s }?.second?.bar?.last ?: Double.NaN
        val b0 = -BEFORE_SEC.toLong(); val lead = -60L; val end = CANDLE_SEC.toLong()
        // What came first: each part's own series, signed the move's way.
        val parts = ArrayList<Pair<String, Long>>()
        firstOut(fut.groupBy { it.first }.mapValues { (_, v) -> dir * v.sumOf { it.second.bar.delta }.toDouble() }, level = false)?.let { parts += "futures flow" to it }
        firstOut(opts.groupBy { it.first }.mapValues { (_, v) -> dir * v.sumOf { optNet(it.second) }.toDouble() }, level = false)?.let { parts += "options flow" to it }
        firstOut(fut.associate { it.first to -way(it.second) }, level = true)?.let { parts += "the book in the way thinning" to it }
        firstOut(diffs(vix).mapValues { -dir * it.value }, level = false)?.let { parts += "VIX" to it }
        firstOut(diffs(idx).mapValues { dir * it.value }, level = false)?.let { parts += "the cash index" to it }
        return Summary(name, e.kind, e.startSec, e.retBp, e.x, dir,
            sumIn(fut, b0, lead) { it.bar.delta }, sumIn(fut, lead, 0) { it.bar.delta }, sumIn(fut, 0, end) { it.bar.delta },
            sumIn(opts, b0, lead, ::optNet), sumIn(opts, lead, 0, ::optNet), sumIn(opts, 0, end, ::optNet),
            meanIn(fut, b0, lead, ::way), meanIn(fut, lead, 0, ::way), meanIn(fut, 0, end, ::way),
            vixAt(lead), vixAt(0), vixAt(end), parts.sortedBy { it.second }, e.header, e.complete, rows.size, e.merges)
    }

    /** A last-price series' per-second changes (sec_rel to change from the previous print). */
    private fun diffs(rows: List<Pair<Long, Row>>): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        var prev = Double.NaN
        for ((s, r) in rows.sortedBy { it.first }) {
            val p = r.bar.last
            if (!prev.isNaN() && p > 0) out[s] = p - prev
            if (p > 0) prev = p
        }
        return out
    }

    const val INDEX_ROLE = "INDEX"
    val OPTION_ROLES = setOf("CE", "PE", "REC_CE", "REC_PE")

    // ---- words -----------------------------------------------------------------------------------------------------------------

    private fun signed(x: Long) = (if (x >= 0) "+" else "−") + "%,d".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = if (x.isNaN()) "—" else (if (x >= 0) "+" else "−") + "%.0f%%".format(Locale.ENGLISH, abs(x) * 100)
    private fun secs(s: Long) = if (s < 0) "${-s} s before the candle" else if (s == 0L) "as the candle began" else "$s s into the candle"

    /** "10:32 BankNifty up 0.42% (5.6× usual)": the list's line. */
    fun listLine(s: Summary): String = listLine(s.name, s.kind, s.startSec, s.retBp, s.x)

    /** The list's line from an H line's fields alone (the list reads only each file's first line). */
    fun listLine(name: String, kind: Kind, startSec: Long, retBp: Double, x: Double): String = "${hhmm(startSec)} ${OrderFlow.label(name)} " +
        (if (kind == Kind.CONTROL) "control minute" else "${kind.words} ${"%.2f".format(Locale.ENGLISH, abs(retBp) / 100)}%") +
        (if (x.isNaN()) "" else " (${"%.1f".format(Locale.ENGLISH, x)}× usual)")

    /** The tap's lines (label to value): flow before and during, calls against puts, the book in the way, VIX, first, context. */
    fun lines(s: Summary): List<Pair<String, String>> {
        val side = if (s.dir > 0) "offers" else "bids"
        val out = ArrayList<Pair<String, String>>()
        out += "Futures delta: 5-1 min before / last minute / candle" to "${signed(s.flowBefore)} / ${signed(s.flowLead)} / ${signed(s.flowDuring)}"
        out += "Calls minus puts bought (ATM ±2)" to "${signed(s.optBefore)} / ${signed(s.optLead)} / ${signed(s.optDuring)}"
        out += "Book in the way ($side, 5 levels)" to (if (s.wayBefore.isNaN()) "not recorded"
            else "%,.0f / %,.0f (%s) / %,.0f (%s)".format(Locale.ENGLISH, s.wayBefore, s.wayLead, pct(s.thinning(true)), s.wayDuring, pct(s.thinning(false))))
        out += "VIX a minute before / start / end" to listOf(s.vixBefore, s.vixAt, s.vixAfter).joinToString(" / ") { if (it.isNaN()) "—" else "%.2f".format(Locale.ENGLISH, it) }
        out += "Stood out first" to (if (s.first.isEmpty()) "nothing before the candle itself" else s.first.joinToString(", ") { "${it.first} ${signedSec(it.second)}" })
        out += "Context" to context(s.header)
        if (!s.complete) out += "Window" to "incomplete (the stream or the app stopped before the 5 minutes after)"
        if (s.merges.isNotEmpty()) out += "Merged" to "later big candles at ${s.merges.joinToString(", ")}"
        return out
    }

    private fun signedSec(s: Long) = if (s < 0) "−${-s} s" else "+$s s"

    /** The H line's context in a few words. */
    fun context(h: Map<String, String>): String {
        val p = ArrayList<String>()
        h["vix"]?.takeIf { it.isNotEmpty() }?.let { p += "VIX $it" }
        h["vwap_sd"]?.toDoubleOrNull()?.let { p += "%.1f SD %s VWAP".format(Locale.ENGLISH, abs(it), if (it >= 0) "above" else "below") }
        h["value"]?.takeIf { it.isNotEmpty() }?.let { p += it }
        h["gamma"]?.takeIf { it.isNotEmpty() }?.let { p += "gamma $it" }
        val ib = h["ib_high"]?.toDoubleOrNull()?.let { hi -> h["ib_low"]?.toDoubleOrNull()?.let { lo -> hi - lo } }
        if (ib != null) p += "IB %,.0f pts".format(Locale.ENGLISH, ib)
        h["day_type"]?.takeIf { it.isNotEmpty() }?.let { p += it }
        if (h["expiry_day"] == "1") p += "expiry day"
        p += "events: " + (h["events"]?.takeIf { it.isNotEmpty() }?.replace(";", ", ") ?: "none")
        h["flow"]?.takeIf { it.isNotEmpty() }?.let { p += "flow $it" }
        h["traps"]?.takeIf { it.isNotEmpty() }?.let { p += "traps: " + it.replace(";", ", ") }
        return p.joinToString(" · ")
    }

    // ---- Jarvis -------------------------------------------------------------------------------------------------------------------

    /** "Why did BankNifty jump at 10:32": [name] (null: not named, both looked at), the minute of the day, the direction said (0: none). */
    data class Ask(val name: String?, val minuteOfDay: Int, val dir: Int)

    private val TIME = Regex("\\b(\\d{1,2})[:. ](\\d{2})( ?(am|pm|baje))?\\b")
    private val WHY = Regex("\\b(why|kyun|kyu|kyon|how come|what happened|what caused|what made|what moved|explain|reason for|wajah)\\b")
    private val MOVED = Regex("\\b(jump|jumped|jumping|spike|spiked|move|moved|fall|fell|drop|dropped|crash|crashed|rally|rallied|surge|surged|" +
        "tank|tanked|dump|dumped|rise|rose|shot up|plunge|plunged|bhaga|gira|uchla|happen|happened|big move|candle|go up|went up|go down|went down)\\b")
    private val UPW = Regex("\\b(jump|jumped|jumping|spike|spiked|rally|rallied|surge|surged|rise|rose|shot up|bhaga|uchla|go up|went up)\\b")
    private val DOWNW = Regex("\\b(fall|fell|drop|dropped|crash|crashed|tank|tanked|dump|dumped|plunge|plunged|gira|go down|went down)\\b")
    private val NOT = Regex("\\b(my|mine|order|orders|trade|trades|position|positions|bot|bots|strategy|alert|alarm|remind|reminder|tomorrow|kal|yesterday|gold|silver|crude)\\b")

    /** The question, or null. Needs an index (or "market"), a time in market hours, a "why" and a move. */
    fun asked(text: String): Ask? {
        val t = text.lowercase(Locale.ENGLISH).replace("’", "'").replace(Regex("[?!,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (!WHY.containsMatchIn(t) || !MOVED.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        val name = when {
            Regex("\\b(bank ?nifty|nifty bank|bn)\\b").containsMatchIn(t) -> "BANKNIFTY"
            Regex("\\b(nifty|nifty 50|nf)\\b").containsMatchIn(t) -> "NIFTY"
            Regex("\\b(market|index|futures?)\\b").containsMatchIn(t) -> null
            else -> return null
        }
        val mod = TIME.findAll(t).mapNotNull { m ->
            var h = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
            if (m.groupValues[4] == "pm" && h < 12) h += 12
            if (m.groupValues[4] != "am" && h in 1..3) h += 12
            (h * 60 + mm).takeIf { mm <= 59 && it in (9 * 60 + 15)..(15 * 60 + 40) }
        }.firstOrNull() ?: return null
        val dir = if (UPW.containsMatchIn(t)) 1 else if (DOWNW.containsMatchIn(t)) -1 else 0
        return Ask(name, mod, dir)
    }

    /** How near a saved event must be to the minute asked. */
    const val NEAR_MIN = 15

    private fun minuteOfDay(sec: Long) = (Math.floorMod(sec + IST_SEC, 86_400L) / 60).toInt()

    /**
     * Jarvis's answer to [a] from today's saved events [today] (summaries; controls are not big moves): the nearest big move
     * of the index asked within [NEAR_MIN] minutes - what stood out first (flow, options, the book, VIX, the cash index),
     * the minute before, and the context - as timing, never a cause or a forecast.
     */
    fun answer(a: Ask, today: List<Summary>): String {
        val asked = "%d:%02d".format(Locale.ENGLISH, a.minuteOfDay / 60, a.minuteOfDay % 60)
        val who = a.name?.let { OrderFlow.label(it) } ?: "Nifty or BankNifty"
        val big = today.filter { it.kind != Kind.CONTROL && (a.name == null || it.name == a.name) }
        val e = big.filter { abs(minuteOfDay(it.startSec) - a.minuteOfDay) <= NEAR_MIN }.minByOrNull { abs(minuteOfDay(it.startSec) - a.minuteOfDay) }
        if (e == null) {
            val saved = if (big.isEmpty()) "None saved today yet." else "Saved today: " + big.sortedBy { it.startSec }.joinToString(", ") { "${hhmm(it.startSec)} ${OrderFlow.short(it.name)} ${it.kind.words}" } + "."
            return "I have no saved big move of $who near $asked today, Boss. The recorder saves a 1-minute futures candle about " +
                "${N.toString().removeSuffix(".0")} times its usual size for that time of day (about two a day per index), only while the Zerodha stream runs. $saved"
        }
        val sb = StringBuilder()
        sb.append("${OrderFlow.label(e.name)}'s big move at ${hhmm(e.startSec)}: ${e.kind.words} ${"%.2f".format(Locale.ENGLISH, abs(e.retBp) / 100)}% in the minute, " +
            "${"%.1f".format(Locale.ENGLISH, e.x)} times the usual move then")
        if (a.dir != 0 && a.dir != e.dir) sb.append(" (it went ${e.kind.words}, not ${if (a.dir > 0) "up" else "down"})")
        sb.append(". ")
        if (e.first.isEmpty()) sb.append("Nothing stood out before the candle: flow, options, the book and VIX moved with it, in the same seconds. ")
        else sb.append("What came first: " + e.first.joinToString(", then ") { "${it.first} ${secs(it.second)}" } + ". ")
        val side = if (e.dir > 0) "offers" else "bids"
        sb.append("In the minute before, the futures' delta was ${signed(e.flowLead)} and calls minus puts ${signed(e.optLead)}")
        if (!e.thinning(true).isNaN()) sb.append("; the $side in the way were ${pct(e.thinning(true))} against the 4 minutes before (${pct(e.thinning(false))} during)")
        sb.append(". Context: ${context(e.header)}. ")
        sb.append("Timing only, Boss: what came first is not proof of what caused it, and it is not a forecast.")
        return sb.toString()
    }

    // ---- the table (R11 panels: the cash index's mean |1-minute log move| in basis points, 09:15..15:29, ±2 min smoothed;
    //      250 sessions to 6 Oct 2026; mean first-half-hour India VIX [VIX_REF]) ---------------------------------------------------

    private val NIFTY_BP = doubleArrayOf(
        5.06, 5.06, 4.70, 4.53, 3.93, 3.64, 3.41, 3.37, 3.23, 3.19, 3.18, 3.17, 3.01, 3.11, 3.04, 3.00, 2.97, 3.07, 2.92, 2.96, 2.94, 2.91, 2.83, 2.79, 2.79,
        2.74, 2.79, 2.73, 2.73, 2.65, 2.68, 2.62, 2.62, 2.55, 2.50, 2.41, 2.40, 2.42, 2.43, 2.37, 2.34, 2.31, 2.22, 2.25, 2.29, 2.36, 2.33, 2.36, 2.34, 2.36,
        2.33, 2.32, 2.28, 2.20, 2.13, 2.04, 2.00, 2.01, 2.07, 2.08, 2.17, 2.22, 2.21, 2.17, 2.14, 2.08, 2.04, 2.01, 1.95, 1.98, 1.96, 1.96, 1.97, 2.03, 2.05,
        2.04, 2.03, 2.04, 1.99, 1.94, 1.97, 1.94, 1.93, 1.93, 1.96, 1.91, 1.93, 1.89, 1.87, 1.80, 1.81, 1.82, 1.88, 1.93, 1.96, 1.92, 1.90, 1.85, 1.81, 1.80,
        1.83, 1.82, 1.82, 1.93, 1.91, 1.90, 1.90, 1.88, 1.75, 1.73, 1.76, 1.72, 1.76, 1.78, 1.78, 1.75, 1.76, 1.72, 1.75, 1.74, 1.74, 1.75, 1.76, 1.74, 1.77,
        1.80, 1.79, 1.85, 1.85, 1.85, 1.78, 1.79, 1.69, 1.72, 1.73, 1.80, 1.80, 1.82, 1.78, 1.75, 1.68, 1.67, 1.69, 1.66, 1.66, 1.68, 1.65, 1.65, 1.70, 1.70,
        1.71, 1.75, 1.69, 1.71, 1.70, 1.68, 1.65, 1.69, 1.65, 1.70, 1.70, 1.73, 1.75, 1.82, 1.79, 1.80, 1.81, 1.75, 1.69, 1.65, 1.63, 1.58, 1.61, 1.61, 1.67,
        1.71, 1.77, 1.81, 1.81, 1.78, 1.75, 1.70, 1.67, 1.69, 1.72, 1.76, 1.77, 1.80, 1.79, 1.80, 1.75, 1.81, 1.78, 1.86, 1.89, 1.99, 1.97, 1.98, 1.91, 1.86,
        1.78, 1.75, 1.75, 1.68, 1.66, 1.71, 1.75, 1.76, 1.82, 1.87, 1.87, 1.83, 1.85, 1.86, 1.82, 1.80, 1.79, 1.78, 1.74, 1.76, 1.72, 1.70, 1.66, 1.73, 1.73,
        1.76, 1.79, 1.82, 1.77, 1.75, 1.75, 1.76, 1.73, 1.72, 1.75, 1.74, 1.73, 1.79, 1.84, 1.80, 1.81, 1.83, 1.77, 1.73, 1.75, 1.75, 1.72, 1.70, 1.68, 1.72,
        1.71, 1.72, 1.80, 1.91, 1.90, 1.92, 1.95, 1.94, 1.90, 1.93, 1.93, 1.91, 1.90, 1.90, 1.90, 1.94, 1.96, 1.97, 1.94, 1.92, 1.90, 1.89, 1.88, 1.89, 1.94,
        1.96, 1.92, 1.88, 1.88, 1.79, 1.77, 1.80, 1.82, 1.85, 1.91, 1.92, 2.02, 2.06, 2.05, 2.04, 2.04, 1.99, 1.99, 2.00, 1.99, 1.96, 1.95, 1.96, 1.98, 2.04,
        2.07, 2.04, 2.07, 2.06, 2.03, 2.03, 2.09, 2.09, 2.11, 2.10, 2.05, 2.02, 1.97, 2.06, 2.14, 2.20, 2.24, 2.27, 2.18, 2.18, 2.17, 2.11, 2.08, 2.06, 2.01,
        2.03, 2.06, 2.07, 2.09, 2.15, 2.17, 2.20, 2.27, 2.32, 2.30, 2.26, 2.20, 2.10, 2.02, 1.89, 1.81, 1.81, 1.80, 2.61, 2.92, 3.21, 3.37, 3.49, 2.76, 2.63,
        2.47, 2.34, 2.26, 2.22, 2.12, 2.01, 1.94, 1.92, 1.93, 1.83, 1.76, 1.71, 1.61, 1.45, 1.43, 1.40, 1.36, 1.32, 1.27, 1.23, 1.24, 1.53, 1.93, 2.09, 2.41,
    )

    private val BANKNIFTY_BP = doubleArrayOf(
        6.35, 6.35, 5.97, 5.80, 5.14, 4.75, 4.52, 4.42, 4.18, 4.08, 3.95, 3.89, 3.63, 3.80, 3.71, 3.73, 3.73, 3.88, 3.70, 3.73, 3.72, 3.63, 3.55, 3.47, 3.45,
        3.37, 3.42, 3.36, 3.34, 3.27, 3.31, 3.31, 3.29, 3.22, 3.24, 3.11, 3.06, 3.04, 2.98, 2.87, 2.86, 2.81, 2.74, 2.82, 2.84, 2.89, 2.90, 2.90, 2.87, 2.92,
        2.88, 2.83, 2.84, 2.74, 2.60, 2.54, 2.49, 2.50, 2.62, 2.67, 2.74, 2.81, 2.77, 2.71, 2.68, 2.67, 2.59, 2.55, 2.49, 2.53, 2.52, 2.52, 2.53, 2.62, 2.61,
        2.56, 2.60, 2.64, 2.57, 2.50, 2.53, 2.47, 2.45, 2.40, 2.44, 2.35, 2.41, 2.37, 2.38, 2.31, 2.32, 2.28, 2.36, 2.37, 2.38, 2.39, 2.40, 2.36, 2.32, 2.32,
        2.33, 2.27, 2.25, 2.34, 2.33, 2.34, 2.37, 2.34, 2.22, 2.21, 2.30, 2.25, 2.28, 2.31, 2.30, 2.16, 2.15, 2.12, 2.14, 2.15, 2.17, 2.18, 2.18, 2.16, 2.23,
        2.27, 2.29, 2.36, 2.36, 2.28, 2.19, 2.20, 2.09, 2.11, 2.16, 2.27, 2.25, 2.26, 2.21, 2.15, 2.06, 2.06, 2.13, 2.13, 2.15, 2.20, 2.14, 2.10, 2.14, 2.13,
        2.12, 2.15, 2.11, 2.07, 2.10, 2.12, 2.11, 2.15, 2.09, 2.09, 2.04, 2.05, 2.06, 2.17, 2.18, 2.20, 2.21, 2.21, 2.15, 2.10, 2.13, 2.05, 2.06, 2.06, 2.07,
        2.07, 2.21, 2.23, 2.23, 2.26, 2.23, 2.15, 2.13, 2.16, 2.19, 2.22, 2.23, 2.28, 2.25, 2.25, 2.20, 2.26, 2.24, 2.38, 2.40, 2.54, 2.56, 2.53, 2.44, 2.39,
        2.29, 2.21, 2.23, 2.16, 2.10, 2.14, 2.21, 2.21, 2.30, 2.40, 2.37, 2.35, 2.35, 2.35, 2.29, 2.28, 2.22, 2.22, 2.13, 2.16, 2.14, 2.16, 2.13, 2.19, 2.19,
        2.20, 2.22, 2.24, 2.22, 2.22, 2.22, 2.20, 2.20, 2.16, 2.18, 2.20, 2.19, 2.24, 2.32, 2.31, 2.28, 2.31, 2.28, 2.21, 2.20, 2.22, 2.16, 2.12, 2.09, 2.13,
        2.14, 2.16, 2.24, 2.36, 2.31, 2.33, 2.39, 2.36, 2.28, 2.35, 2.33, 2.33, 2.35, 2.39, 2.38, 2.41, 2.40, 2.41, 2.37, 2.35, 2.35, 2.36, 2.34, 2.33, 2.32,
        2.31, 2.28, 2.23, 2.23, 2.16, 2.13, 2.15, 2.18, 2.25, 2.32, 2.36, 2.47, 2.52, 2.45, 2.44, 2.42, 2.37, 2.36, 2.41, 2.43, 2.40, 2.41, 2.44, 2.50, 2.52,
        2.54, 2.48, 2.51, 2.48, 2.43, 2.46, 2.52, 2.51, 2.52, 2.54, 2.47, 2.46, 2.41, 2.53, 2.58, 2.65, 2.66, 2.64, 2.47, 2.42, 2.40, 2.39, 2.36, 2.34, 2.31,
        2.34, 2.34, 2.39, 2.40, 2.49, 2.55, 2.58, 2.65, 2.70, 2.65, 2.58, 2.50, 2.40, 2.35, 2.23, 2.14, 2.15, 2.15, 2.79, 3.16, 3.48, 3.67, 3.82, 3.29, 3.16,
        3.03, 2.92, 2.85, 2.80, 2.70, 2.59, 2.48, 2.44, 2.41, 2.24, 2.13, 2.05, 1.92, 1.77, 1.79, 1.75, 1.72, 1.68, 1.64, 1.57, 1.61, 1.91, 2.53, 2.75, 3.19,
    )
}
