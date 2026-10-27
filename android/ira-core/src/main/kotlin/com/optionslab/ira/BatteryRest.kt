package com.optionslab.ira

import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Battery, 14 Oct (Boss: "since yesterday my phone's battery drains in 3-4 hours; before, it lasted the whole day"). The
 * work added on 12-13 Oct ran all evening and all night whether anything needed it or not: the MCX watch every 15 s to
 * 23:30 with an MCX arm merely switched on (a Zerodha positions call each pass, the price stream kept awake by MCX price
 * reads), the tick-zone recorder's three loops every second all night, the order-flow pump on the MCX futures, the live
 * recorder's and the market context's minute loops 24x7. The decisions here make that work rest when nothing needs it.
 *
 * What never changes: with anything held or working on MCX (paper or live) the watch keeps its 15 s pass and the stream,
 * so no stop, target, square-off, guard, kill switch or day lock waits longer than before. With a LIVE position or order
 * nothing here weakens anything. Nothing here is on the order or tick path. Pure.
 */
object McxRest {
    /** The MCX pass's wait with something held or working on MCX, or inside an armed arm's entry minutes (as before). */
    const val HELD_MS = 15_000L
    /** ... and with nothing held, outside every armed arm's entry minutes (still looked at every [HELD_MS] from memory). */
    const val IDLE_MS = 60_000L
    /** Minutes before an arm's first entry minute the 15 s pass starts. */
    const val LEAD_MIN = 2
    /**
     * Minutes before an arm's first entry minute the order flow follows its future: the flow's read needs two minutes warm
     * and a five-minute rolling delta ([OrderFlow]), so it is ready at the decision.
     */
    const val FLOW_LEAD_MIN = 10

    /**
     * The minutes of the day (IST) [arm] may OPEN a position in (each with [LEAD_MIN] before it), from the engine's rules:
     * the NG evening breakout's 19:00-22:00 break and its grace, the SILVERM morning call's 09:15-13:45 grid and its grace,
     * the US-night silver's 09:05 entry and its grace, the trend rule's trades at the session's open (09:00-09:30).
     * Exits need no window: an arm's open position is "held", which keeps the 15 s pass all the time it is open.
     */
    fun entryMinutes(arm: String): List<IntRange> = when (arm) {
        McxEveRules.SOURCE -> listOf(McxEveRules.RANGE_TO - LEAD_MIN..McxEveRules.ENTRY_UNTIL + McxEveRules.ENTRY_GRACE)
        McxMorningRules.SOURCE -> listOf(McxMorningRules.GRID_FROM - LEAD_MIN..McxMorningRules.GRID_LAST + McxMorningRules.ENTRY_GRACE)
        McxUsSilverRules.SOURCE -> listOf(McxUsSilverRules.ENTRY - LEAD_MIN..McxUsSilverRules.ENTRY + McxUsSilverRules.ENTRY_GRACE)
        McxTrendRules.SOURCE -> listOf(9 * 60 - LEAD_MIN..9 * 60 + 30)
        else -> emptyList()
    }

    /** One of the [armed] arms is inside its entry minutes at [minute]. */
    fun inEntryMinutes(armed: Collection<String>, minute: Int): Boolean = armed.any { a -> entryMinutes(a).any { minute in it } }

    /** Something is held or working on MCX: a paper position or order, a live one, or a position in an arm's book. */
    fun held(paper: Boolean, live: Boolean, arms: Boolean): Boolean = paper || live || arms

    /** The MCX pass's wait: [HELD_MS] with something [held] or inside an armed arm's entry minutes, else [IDLE_MS]. */
    fun cadenceMs(held: Boolean, inEntryMinutes: Boolean): Long = if (held || inEntryMinutes) HELD_MS else IDLE_MS

    /**
     * The brain's 10 s refresh counts MCX's hours only while something is held there or an armed arm is inside its entry
     * minutes (its data-health and trap flags are the arms' entry brake then).
     */
    fun brainCounts(mcxOpen: Boolean, held: Boolean, inEntryMinutes: Boolean = false): Boolean = mcxOpen && (held || inEntryMinutes)

    /** MCX price reads keep the price stream awake only while something is held there (else a quote call answers). */
    fun keepsStream(held: Boolean): Boolean = held

    /**
     * The order flow follows an armed arm's MCX futures while something is held on MCX, or from [FLOW_LEAD_MIN] before that
     * arm's entry minutes to their end (so the flow is warm at the decision).
     */
    fun followFutures(arm: String, armed: Boolean, held: Boolean, minute: Int, mcxOpen: Boolean): Boolean =
        mcxOpen && armed && (held || entryMinutes(arm).any { minute in it.first - (FLOW_LEAD_MIN - LEAD_MIN)..it.last })
}

/** The NSE calendar's next open, for the loops that rest outside market hours (one wait, not a poll each second). */
object RestClock {
    /** At most this long in one wait, so a clock change or a new holiday list is seen within hours. */
    const val CAP_MS = 3 * 3_600_000L
    /**
     * A long rest is waited in steps of at most this, each re-reading the wall clock: a coroutine's wait runs on a clock that
     * stops in deep sleep, so one long wait would end late after the phone slept. A step wakes nothing by itself.
     */
    const val STEP_MS = 60_000L

    /** The next step of a rest with [remainingMs] left. */
    fun step(remainingMs: Long): Long = remainingMs.coerceIn(0L, STEP_MS)

    /**
     * Milliseconds from [now] (IST) until NSE's next session opens at [openMinute] on a [tradingDay]; 0 while a session is
     * open ([openMinute] to [close] of that day). Looked up 15 days ahead; at most [CAP_MS]. [leadMs]: woken this much early.
     */
    fun untilOpenMs(now: LocalDateTime, openMinute: Int, close: (LocalDate) -> Int, tradingDay: (LocalDate) -> Boolean,
                    leadMs: Long = 0L, capMs: Long = CAP_MS): Long {
        val minute = now.hour * 60 + now.minute
        val today = now.toLocalDate()
        if (tradingDay(today) && minute >= openMinute && minute < close(today)) return 0L
        for (i in 0..15) {
            val d = today.plusDays(i.toLong())
            if (!tradingDay(d)) continue
            val open = d.atTime(LocalTime.of(openMinute / 60, openMinute % 60))
            if (!open.isAfter(now)) continue
            return (Duration.between(now, open).toMillis() - leadMs).coerceIn(0L, capMs)
        }
        return capMs
    }
}

/**
 * The tick-zone recorder ([TickZone]) at rest: asleep outside NSE's hours, its writer once a minute, its layout redone only
 * when the day or the money moves, and narrowed under battery saver.
 */
object TickRest {
    /**
     * The writer's interval (was 5 s). The rows are the same (the sampler still takes every second into the bounded queue,
     * [TickZone.QUEUE_CAP] = a minute and a half of seconds since 14 Oct); a process killed between two writes loses at most this much.
     */
    const val DRAIN_MS = 60_000L
    /** The layout's interval in market hours (as before). */
    const val LAYOUT_MS = 30_000L
    /** Strikes either side of the money under battery saver (else [TickZone.EACH_SIDE]); the futures and VIX stay. */
    const val SAVER_EACH_SIDE = 1

    /**
     * Android's battery saver on, or the battery low and not charging. 27 Oct (battery audit): low is 30% or less
     * ([AnalysisRest.LOW_PCT]; was Jarvis's 20%) - recording only, never a stop.
     */
    fun narrow(powerSave: Boolean, percent: Int?, charging: Boolean): Boolean = powerSave || AnalysisRest.low(percent, charging)

    /** Strikes either side of the money to follow. */
    fun eachSide(narrow: Boolean): Int = if (narrow) SAVER_EACH_SIDE else TickZone.EACH_SIDE

    /** The diary line when the narrowing changes (null: unchanged). */
    fun narrowNote(was: Boolean?, now: Boolean): String? = when {
        was == now -> null
        now -> "battery saver: recording the futures, VIX and ATM±$SAVER_EACH_SIDE only (was ±${TickZone.EACH_SIDE})"
        was == null -> null
        else -> "battery saver off: recording ATM±${TickZone.EACH_SIDE} again"
    }

    /** What the layout depends on: redone only when one of these changes. */
    data class Key(val day: LocalDate, val atm: Map<String, Double?>, val each: Int, val tokens: List<Long>)

    /** The layout must be rebuilt: the first one, a new day, the money moved a strike, the narrowing or the futures changed. */
    fun relayout(last: Key?, now: Key): Boolean = last != now

    /** The listed strike nearest [spot] (null without either). */
    fun atm(strikes: Collection<Double>, spot: Double?): Double? = spot?.takeIf { it > 0 }?.let { MarketRecord.atm(strikes, it) }
}

/** The market context's refresh ([com.optionslab.app.data.MarketContext]): each minute in market hours, else a few times a day. */
object ContextPace {
    const val MARKET_MS = 60_000L
    /** Every minute from here (before the open: the morning card, Shadow from before 09:15) ... */
    const val FROM_MIN = 8 * 60 + 45
    /** ... to here (past the close, so Shadow's 15:30 is seen). */
    const val TO_MIN = 15 * 60 + 45
    /** Out of hours: just after midnight (the new day's events), and once in the evening (the next session's warning). */
    val ANCHORS: List<Int> = listOf(1, FROM_MIN, 18 * 60)

    /** The wait from [now] to the next refresh (a [tradingDay] or not: the anchors run every day). */
    fun nextMs(now: LocalDateTime, tradingDay: Boolean): Long {
        val m = now.hour * 60 + now.minute
        if (tradingDay && m in FROM_MIN until TO_MIN) return MARKET_MS
        val today = now.toLocalDate()
        val next = (ANCHORS.map { today.atStartOfDay().plusMinutes(it.toLong()) } + today.plusDays(1).atStartOfDay().plusMinutes(ANCHORS.first().toLong()))
            .first { it.isAfter(now) }
        return Duration.between(now, next).toMillis().coerceIn(1_000L, RestClock.CAP_MS)
    }
}

/**
 * "Listen for 'Jarvis'" (Bot setting, 14 Oct): when the wake word is listened for. [Mode.ALWAYS] is today's behaviour and
 * the default; nothing changes unless Boss picks another. A question already started (Jarvis awake, a follow-up, the Talk
 * or hold-to-talk button) always goes on.
 */
object ListenWhen {
    enum class Mode(val label: String, val what: String) {
        ALWAYS("Always", "Listens for \"Jarvis\" all the time (as before)."),
        UNLOCKED_OR_CHARGING("Only when unlocked or charging", "Listens while the phone is unlocked or on the charger; rests when it is locked on battery."),
        MARKET_HOURS("Market hours only", "Listens from 09:00 to 15:50 on trading days; rests at night, weekends and holidays."),
    }

    val DEFAULT = Mode.ALWAYS

    /** Unknown or empty: [DEFAULT]. */
    fun parse(s: String?): Mode = Mode.entries.firstOrNull { it.name == s } ?: DEFAULT

    /** Market hours for listening: 09:00 to ten minutes past F&O's close ([foClose]) on a trading day. */
    fun marketHours(tradingDay: Boolean, minute: Int, foClose: Int): Boolean = tradingDay && minute in 9 * 60..foClose + 10

    /** The wake word is listened for now. */
    fun allowed(mode: Mode, unlocked: Boolean, charging: Boolean, marketHours: Boolean): Boolean = when (mode) {
        Mode.ALWAYS -> true
        Mode.UNLOCKED_OR_CHARGING -> unlocked || charging
        Mode.MARKET_HOURS -> marketHours
    }

    /**
     * Resting: looked at again after this. Unlocking and the charger wake it at once (system broadcasts), so that mode only
     * needs a slow fallback; market hours wait for the next 09:00 ([untilOpenMs]), at most five minutes at a time (the wait's
     * clock stops in deep sleep).
     */
    fun recheckMs(mode: Mode, untilOpenMs: Long?): Long = when (mode) {
        Mode.ALWAYS -> 0L
        Mode.UNLOCKED_OR_CHARGING -> 5 * 60_000L
        Mode.MARKET_HOURS -> (untilOpenMs ?: 5 * 60_000L).coerceIn(30_000L, 5 * 60_000L)
    }
}

/**
 * Which background loops ran today and for how long (the diagnostics' and the Health page's "Battery today" line, so Boss
 * can see the fix on the phone). Each loop is switched on and off ([on] / [off]) or given time ([add]); a new day starts
 * from zero. Memory only (a restart starts the count again). Thread-safe, short locks only; never on the order or tick path.
 */
class LoopMeter(private val clock: () -> Long = System::currentTimeMillis, private val dayOf: (Long) -> LocalDate) {
    private val total = HashMap<String, Long>()
    private val since = HashMap<String, Long>()
    private val counts = HashMap<String, Int>()
    private var day: LocalDate? = null

    /** The day before's figures, kept at the day's roll ([lineFor]): the evening upload often runs after midnight. */
    private var previous: Figures? = null

    /**
     * 27 Oct (battery audit): one day's figures - each loop's milliseconds and counts - saved on disk by the app so a restart
     * (or the next morning's catch-up upload, a new process) keeps the day's count ([restore], [encode] / [decode]).
     */
    data class Figures(val day: LocalDate, val ms: Map<String, Long>, val counts: Map<String, Int>) {
        fun line(): String = render({ n -> (ms[n] ?: 0L) / 60_000L }, { n -> counts[n] ?: 0 })
    }

    private fun roll(now: Long) {
        val d = dayOf(now)
        if (d == day) return
        // A loop running across midnight counts only its part of the new day.
        val midnight = if (day == null) now else d.atStartOfDay(MarketRecord.IST).toInstant().toEpochMilli()
        // 27 Oct (battery audit): the day's figures kept as its line before they are cleared (running loops counted to midnight).
        day?.let { old -> previous = figuresAt(old, midnight) }
        total.clear(); counts.clear()
        for (k in since.keys.toList()) since[k] = maxOf(since[k]!!, minOf(midnight, now))
        day = d
    }

    @Synchronized fun on(name: String) { val now = clock(); roll(now); since.putIfAbsent(name, now) }

    @Synchronized fun off(name: String) {
        val now = clock(); roll(now)
        val s = since.remove(name) ?: return
        total[name] = (total[name] ?: 0L) + (now - s).coerceAtLeast(0L)
    }

    /** [on] or [off] as [running] says. */
    fun set(name: String, running: Boolean) { if (running) on(name) else off(name) }

    @Synchronized fun add(name: String, ms: Long) { roll(clock()); total[name] = (total[name] ?: 0L) + ms.coerceAtLeast(0L) }

    /** One more of [name] today (passes, wakes). */
    @Synchronized fun count(name: String) { roll(clock()); counts[name] = (counts[name] ?: 0) + 1 }

    /** [name]'s minutes today, the running stretch included. */
    @Synchronized fun minutes(name: String): Long {
        val now = clock(); roll(now)
        val run = since[name]?.let { now - it } ?: 0L
        return ((total[name] ?: 0L) + run.coerceAtLeast(0L)) / 60_000L
    }

    @Synchronized fun counted(name: String): Int { roll(clock()); return counts[name] ?: 0 }

    @Synchronized fun running(name: String): Boolean = since.containsKey(name)

    /**
     * [d]'s "Battery today" line: today's from the running figures, the day before's as kept at midnight; null for any other
     * day, or a day this process did not see (memory only: a restart starts again).
     */
    @Synchronized fun lineFor(d: LocalDate): String? = figuresFor(d)?.line()

    /** [d]'s figures (today's with the running stretches to now; the day before's as kept at midnight); null for any other day. */
    @Synchronized fun figuresFor(d: LocalDate): Figures? {
        val now = clock(); roll(now)
        if (d == day) return figuresAt(d, now)
        return previous?.takeIf { it.day == d }
    }

    private fun figuresAt(d: LocalDate, upTo: Long): Figures {
        val ms = HashMap(total)
        for ((k, s) in since) ms[k] = (ms[k] ?: 0L) + (upTo - s).coerceAtLeast(0L)
        return Figures(d, ms, HashMap(counts))
    }

    /**
     * Figures saved by an earlier process ([f]) added in: today's into today's count, the day before's as the day before's
     * (unless this process saw that day itself). Other days are ignored. Call once, at the start.
     */
    @Synchronized fun restore(f: Figures) {
        roll(clock())
        val today = day ?: return
        when {
            f.day == today -> {
                for ((k, v) in f.ms) total[k] = (total[k] ?: 0L) + v.coerceAtLeast(0L)
                for ((k, v) in f.counts) counts[k] = (counts[k] ?: 0) + v.coerceAtLeast(0)
            }
            f.day == today.minusDays(1) && previous == null -> previous = f
        }
    }

    companion object {
        const val WATCH_EVENING = "watch after 15:40"
        const val MCX_FAST = "MCX 15 s passes"
        const val MCX_SLOW = "MCX 60 s passes"
        const val RECORDER = "tick recorder"
        const val STREAM = "price stream on"
        const val ORDER_FLOW = "order-flow pump"
        const val LISTEN_RESTING = "Jarvis resting"
        /** 27 Oct (battery audit): the Candle agent's batches and Jarvis's words rounds today (counts). */
        const val CANDLE_BATCHES = "Candle agent batches"
        const val WORDS_ROUNDS = "Jarvis words rounds"
        /** 27 Oct: minutes the order flow rested for the battery ([AnalysisRest.flowFollows]). */
        const val FLOW_RESTING = "order flow resting"
        /** 27 Oct: live stream drops today (counts; each reconnect costs a new connection and a fresh subscription). */
        const val RECONNECTS = "stream reconnects"
        /** 27 Oct: minutes the battery was at 30% or less, not charging, as the workers saw it. */
        const val LOW_BATTERY = "battery low"

        /** "Battery today: ..." from the meter's figures (shown on the diagnostics and the Health page). */
        fun line(m: LoopMeter): String = render(m::minutes, m::counted)

        /** [f] as one line of text: "2026-10-26|m:name=ms;c:name=n;..." (loop names hold no '|', ';' or '='). */
        fun encode(f: Figures): String = f.day.toString() + "|" +
            (f.ms.entries.sortedBy { it.key }.map { "m:${it.key}=${it.value}" } + f.counts.entries.sortedBy { it.key }.map { "c:${it.key}=${it.value}" })
                .joinToString(";")

        /** [encode]'s line back (null: not one). Unknown or broken parts are skipped. */
        fun decode(line: String): Figures? {
            val bar = line.indexOf('|')
            if (bar <= 0) return null
            val d = runCatching { LocalDate.parse(line.substring(0, bar).trim()) }.getOrNull() ?: return null
            val ms = HashMap<String, Long>(); val counts = HashMap<String, Int>()
            for (part in line.substring(bar + 1).split(';')) {
                val eq = part.lastIndexOf('=')
                if (eq < 3) continue
                val k = part.substring(2, eq)
                when {
                    part.startsWith("m:") -> part.substring(eq + 1).toLongOrNull()?.let { ms[k] = it }
                    part.startsWith("c:") -> part.substring(eq + 1).toIntOrNull()?.let { counts[k] = it }
                }
            }
            return Figures(d, ms, counts)
        }

        /** The line from each loop's minutes and counts. No personal data: loop names, minutes and counts only. */
        internal fun render(min: (String) -> Long, counted: (String) -> Int): String = "Battery today: " + listOf(
            "watch after 15:40 ${min(WATCH_EVENING)} min (MCX passes: ${counted(MCX_FAST)} at 15 s, ${counted(MCX_SLOW)} at 60 s)",
            "tick recorder ${min(RECORDER)} min",
            "stream on ${min(STREAM)} min (${counted(RECONNECTS)} reconnects)",
            "order-flow pump ${min(ORDER_FLOW)} min (resting ${min(FLOW_RESTING)} min)",
            "Candle agent ${counted(CANDLE_BATCHES)} batches",
            "Jarvis words ${counted(WORDS_ROUNDS)} rounds",
            "Jarvis resting ${min(LISTEN_RESTING)} min",
            "battery 30% or less ${min(LOW_BATTERY)} min",
        ).joinToString(" · ") + "."
    }
}

/**
 * Battery audit, 27 Oct (Boss: "the battery drained a lot today"; android/BATTERY_AUDIT_2026-10-27.md). The analysis that is
 * knowledge or words only - the Candle agent's batches, Jarvis's words-only round, the order flow's shadow reads - ran at the
 * same pace with nothing held, the screen off and the battery at 15%. Here is how fast each may go now.
 *
 * What never changes: with ANY position or order open (paper or live, NSE or MCX) the pace is [Pace.FULL], whatever the
 * battery, the saver or the screen (open positions come first). The position watch, the 15-second stop lane, the stream for
 * held instruments and the start-up restore never read this at all. Pure.
 */
object AnalysisRest {
    /** The battery at or below this, not charging, counts as low here (Boss's audit: 30%; Jarvis's old saver is 20%). */
    const val LOW_PCT = 30

    enum class Pace {
        /** As before. */
        FULL,
        /** Twice the wait: the screen is off and nothing is held. */
        SLOW,
        /** Knowledge-only work stops; words-only work goes to [REST_WORDS_MS]: nothing held, and the battery low or the saver on. */
        REST,
    }

    /** The battery is low: at or below [LOW_PCT] and not charging (unknown: not low). */
    fun low(percent: Int?, charging: Boolean): Boolean = !charging && percent != null && percent <= LOW_PCT

    /**
     * The pace of the knowledge/words-only analysis. [held]: any position or order open (paper or live, NSE or MCX; unknown
     * counts as held). [saver]: Android's battery saver. [screenOn]: unknown counts as on.
     */
    fun pace(held: Boolean, screenOn: Boolean, saver: Boolean, percent: Int?, charging: Boolean): Pace = when {
        held -> Pace.FULL
        saver || low(percent, charging) -> Pace.REST
        !screenOn && !charging -> Pace.SLOW
        else -> Pace.FULL
    }

    /** Jarvis's words-only round at [Pace.REST]: once every five minutes (its safety words still come, just later). */
    const val REST_WORDS_MS = 5 * 60_000L

    /** The words round's wait at [pace] from its [normalMs] (60 s, or 120 s under the saver as before). */
    fun wordsMs(pace: Pace, normalMs: Long): Long = when (pace) {
        Pace.FULL -> normalMs
        Pace.SLOW -> normalMs * 2
        Pace.REST -> maxOf(normalMs, REST_WORDS_MS)
    }

    /** The Candle agent's wait at [pace] from its [normalMs]; null: rests (asked again in a minute). Knowledge only. */
    fun candleMs(pace: Pace, normalMs: Long): Long? = when (pace) {
        Pace.FULL -> normalMs
        Pace.SLOW -> normalMs * 2
        Pace.REST -> null
    }

    /**
     * The Candle agent on MCX's evening (NSE closed): only while something is held on MCX or an armed MCX arm is in its entry
     * minutes. On 26-27 Oct it read the MCX candles every few minutes to 23:30 with nothing held, a batch up to its 2-minute
     * limit (p95 2 min 0 s, 101 runs on 27 Oct). Its reads are knowledge only: no stop, exit or entry waits on them.
     */
    fun candlesOnMcxEvening(mcxHeld: Boolean, inEntryMinutes: Boolean): Boolean = mcxHeld || inEntryMinutes

    /**
     * The order flow's NSE set (index futures and ATM options, Kite's full mode) at [Pace.REST]: none, unless something is
     * held (then [pace] is FULL anyway). Its reads are SHADOW by default (TODO.md S7: not proven); a CONFIRM strategy with no
     * read "has no opinion" and trades as its rules say ([com.optionslab.ira.OrderFlow]), so resting never skips an entry.
     */
    fun flowFollows(pace: Pace): Boolean = pace != Pace.REST
}

/**
 * The phone's battery and screen, read from Android at most once per [ttlMs] (Battery audit, 27 Oct: each read is a call into
 * Android - "excessive binder traffic during cached" on 25 Oct - and several loops read them every few seconds). Thread-safe;
 * a read that fails keeps the last value. [read] is the real reading (the app's sticky broadcast and PowerManager). Pure.
 */
class PowerCache<T>(private val ttlMs: Long, private val clock: () -> Long, private val read: () -> T) {
    @Volatile private var kept: Pair<Long, T>? = null
    /** How many real reads were made (the tests; the diagnostics). */
    @Volatile var reads: Int = 0
        private set

    fun get(): T {
        val now = clock()
        kept?.let { (at, v) -> if (now - at in 0 until ttlMs) return v }
        synchronized(this) {
            kept?.let { (at, v) -> if (now - at in 0 until ttlMs) return v }
            val v = runCatching { read() }.getOrElse { e -> kept?.second ?: throw e }
            reads++
            kept = now to v
            return v
        }
    }

    /** Forget the kept value (a charger plugged in: read again at once). */
    fun clear() { kept = null }
}
