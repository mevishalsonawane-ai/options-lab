package com.optionslab.ira

import java.util.Locale

/**
 * Order speed (Boss, 9 Oct: "order latency as low as possible"): where the time goes between a signal and Zerodha's fill,
 * step by step, per day, for paper and live apart. The app records each step's milliseconds ([Book.add]); this keeps a
 * rolling window per step ([KEEP]) and says the typical (median) and worst (95th percentile and the slowest) of each, the
 * warnings ([warnings]) and where the relay is ([relayAdvice]). Pure: no clock, no Android, no network.
 *
 * The steps:
 *  - signal → decision: the tick or candle close that woke an arm, to the arm deciding to trade;
 *  - decision → sent: the arm's decision to the order's request starting;
 *  - sent → answer: the request to Zerodha's answer (the order id); for paper, the paper book's answer;
 *  - answer → fill: the answer to the fill being known (Zerodha's stream, or a read of the order);
 *  - price age: how old the price an order used was, by the exchange's own stamp (the phone clock's offset included);
 *  - relay / direct round trip: a cheap read through the static-IP relay, and the same read without it;
 *  - clock offset: the phone's clock minus the exchange's stamp of its ticks (network delay included).
 *
 * Round 2 (9 Oct): the order path's own steps too - the live entry's margin check, the resting exchange stop's cancel
 * before an app exit, the stream's gaps when it drops and comes back - and where each decision's price came from
 * ([Source]: a stream tick, a local candle built from ticks, or the candle feed / Zerodha's REST quote), counted per day.
 */
object OrderSpeed {
    enum class Step(val key: String, val label: String) {
        SIGNAL_DECISION("sd", "Signal → decision"),
        DECISION_SENT("ds", "Decision → order sent"),
        SENT_ACK("sa", "Order sent → Zerodha's answer"),
        ACK_FILL("af", "Answer → fill known"),
        SIGNAL_SENT("ss", "Signal → order sent"),
        PRICE_AGE("pa", "Price age when used"),
        RELAY_PING("rp", "Relay round trip"),
        RELAY_COLD("rc", "Relay round trip (cold)"),
        DIRECT_PING("dp", "Direct round trip (no relay)"),
        CLOCK_OFFSET("co", "Phone clock vs exchange"),
        MARGIN_CHECK("mc", "Margin check (live entry)"),
        STOP_CANCEL("sc", "Exchange stop cancelled before an exit"),
        STREAM_GAP("sg", "Price stream gap (drop to first tick)"),
        ;
        companion object {
            fun of(key: String): Step? = entries.firstOrNull { it.key == key }
        }
    }

    /** Samples kept per step and mode (the newest); the card's figures are over these. */
    const val KEEP = 100
    /** A sample this long or longer is a stall or a clock jump, not a step: never kept. */
    const val MAX_MS = 10 * 60_000L

    /** Warn above these. */
    const val RELAY_WARN_MS = 150L
    const val PRICE_AGE_WARN_MS = 2_000L
    const val SIGNAL_SENT_WARN_MS = 1_000L
    /** A relay slower than this is likely far from Zerodha's servers in Mumbai. */
    const val RELAY_FAR_MS = 60L
    const val MUMBAI = "Move the relay to a Mumbai region (e.g. Oracle ap-mumbai-1 / AWS ap-south-1): ~20–40 ms faster per order"

    data class Summary(val n: Int, val median: Long, val p95: Long, val max: Long)

    /** Where a decision's price came from. */
    enum class Source(val key: String, val label: String) {
        STREAM("s", "from the stream"),
        LOCAL_CANDLE("l", "from a local candle"),
        FEED("f", "from the feed / Zerodha's quote"),
        ;
        companion object {
            fun of(key: String): Source? = entries.firstOrNull { it.key == key }
        }
    }

    /** The median of [xs] (the lower middle for an even count); 0 for none. */
    fun median(xs: List<Long>): Long {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        return s[(s.size - 1) / 2]
    }

    /** The [q] quantile (0..1) of [xs] by nearest rank; 0 for none. */
    fun quantile(xs: List<Long>, q: Double): Long {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        val rank = kotlin.math.ceil(q.coerceIn(0.0, 1.0) * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    fun p95(xs: List<Long>): Long = quantile(xs, 0.95)

    /** Null for no samples. */
    fun summary(xs: List<Long>): Summary? =
        if (xs.isEmpty()) null else Summary(xs.size, median(xs), p95(xs), xs.max())

    /** One day's samples per step and mode (live true / paper false), newest last. Not thread-safe: the caller locks. */
    class Book(val day: String) {
        private val samples = LinkedHashMap<Pair<Step, Boolean>, ArrayDeque<Long>>()

        /** Kept unless out of range: a negative time (only the clock offset may be negative) or [MAX_MS] and over. */
        fun add(step: Step, live: Boolean, ms: Long): Boolean {
            val ok = if (step == Step.CLOCK_OFFSET) kotlin.math.abs(ms) < MAX_MS else ms in 0 until MAX_MS
            if (!ok) return false
            val q = samples.getOrPut(step to live) { ArrayDeque() }
            q.addLast(ms)
            while (q.size > KEEP) q.removeFirst()
            return true
        }

        fun of(step: Step, live: Boolean): List<Long> = samples[step to live]?.toList().orEmpty()

        /** Both modes together. */
        fun all(step: Step): List<Long> = of(step, false) + of(step, true)

        private val decided = LinkedHashMap<Source, Int>()

        /** The stream's drops today. */
        var drops: Int = 0
            private set

        /** A decision taken on a price from [src]. */
        fun decidedFrom(src: Source, n: Int = 1) { if (n > 0) decided[src] = (decided[src] ?: 0) + n }

        fun decidedCount(src: Source): Int = decided[src] ?: 0

        /** The stream dropped ([n] times). */
        fun dropped(n: Int = 1) { if (n > 0) drops += n }

        fun isEmpty(): Boolean = samples.values.all { it.isEmpty() } && decided.isEmpty() && drops == 0

        /** "day|sd:1:12,15;sa:0:3;n:s:4;d:2": compact, for the settings vault. */
        fun encode(): String = day + "|" + (samples.entries.filter { it.value.isNotEmpty() }
            .map { (k, v) -> "${k.first.key}:${if (k.second) 1 else 0}:${v.joinToString(",")}" } +
            decided.entries.filter { it.value > 0 }.map { (k, v) -> "n:${k.key}:$v" } +
            (if (drops > 0) listOf("d:$drops") else emptyList())).joinToString(";")
    }

    /** [Book.encode] read back; a different day, or anything unreadable, gives a fresh book for [today]. */
    fun decode(raw: String?, today: String): Book {
        val fresh = Book(today)
        if (raw.isNullOrBlank()) return fresh
        val day = raw.substringBefore('|')
        if (day != today || !raw.contains('|')) return fresh
        val b = Book(today)
        for (part in raw.substringAfter('|').split(';')) {
            val bits = part.split(':')
            if (bits.size == 2 && bits[0] == "d") { bits[1].toIntOrNull()?.let { b.dropped(it) }; continue }
            if (bits.size == 3 && bits[0] == "n") { Source.of(bits[1])?.let { src -> bits[2].toIntOrNull()?.let { b.decidedFrom(src, it) } }; continue }
            if (bits.size != 3) continue
            val step = Step.of(bits[0]) ?: continue
            val live = bits[1] == "1"
            bits[2].split(',').mapNotNull { it.toLongOrNull() }.forEach { b.add(step, live, it) }
        }
        return b
    }

    private fun ms(x: Long): String = if (kotlin.math.abs(x) >= 10_000) "%.0f s".format(Locale.ENGLISH, x / 1000.0)
        else if (kotlin.math.abs(x) >= 1_000) "%.1f s".format(Locale.ENGLISH, x / 1000.0) else "$x ms"

    /** "p50 120 ms, p95 340 ms (slowest 410 ms), 12 times". */
    fun said(s: Summary): String = "p50 ${ms(s.median)}, p95 ${ms(s.p95)}" +
        (if (s.max != s.p95) " (slowest ${ms(s.max)})" else "") + ", ${s.n} ${if (s.n == 1) "time" else "times"}"

    /** The order steps, each mode apart ("Live" / "Paper"), in the order they happen. */
    private val ORDER_STEPS = listOf(Step.SIGNAL_DECISION, Step.MARGIN_CHECK, Step.DECISION_SENT, Step.STOP_CANCEL, Step.SENT_ACK, Step.ACK_FILL,
        Step.SIGNAL_SENT, Step.PRICE_AGE)

    /**
     * The card's lines: each order step for live and for paper that has samples, then the round trips and the clock.
     * [fillsOnStream] / [fillsKnown]: today's live fills first known from Zerodha's stream, out of all.
     */
    fun lines(b: Book, fillsOnStream: Int = 0, fillsKnown: Int = 0): List<String> {
        val out = ArrayList<String>()
        for (live in listOf(true, false)) for (st in ORDER_STEPS) {
            val s = summary(b.of(st, live)) ?: continue
            out += "${if (live) "Live" else "Paper"} · ${st.label}: ${said(s)}"
        }
        if (fillsKnown > 0) out += "Live fills first seen on Zerodha's stream: $fillsOnStream of $fillsKnown"
        if (Source.entries.any { b.decidedCount(it) > 0 })
            out += "Decided " + Source.entries.joinToString(" · ") { "${it.label} ${b.decidedCount(it)}" }
        val gaps = summary(b.all(Step.STREAM_GAP))
        if (b.drops > 0 || gaps != null)
            out += "Price stream drops today: ${b.drops}" + (gaps?.let { " · gap ${said(it)}" } ?: "")
        for (st in listOf(Step.RELAY_PING, Step.RELAY_COLD, Step.DIRECT_PING)) summary(b.all(st))?.let { out += "${st.label}: ${said(it)}" }
        summary(b.all(Step.CLOCK_OFFSET))?.let {
            out += "${Step.CLOCK_OFFSET.label}: phone ${if (it.median >= 0) "ahead by" else "behind by"} ${ms(kotlin.math.abs(it.median))} (typical, network delay included)"
        }
        if (out.isEmpty()) out += "No timings yet today: they appear with the first order, price or relay check in market hours."
        return out
    }

    /**
     * Warnings, plainly: the relay's typical round trip over [RELAY_WARN_MS], the worst price age over [PRICE_AGE_WARN_MS]
     * (and the phone's clock when it is off by over a second, which counts in it), the worst signal → sent over
     * [SIGNAL_SENT_WARN_MS].
     */
    fun warnings(b: Book): List<String> {
        val out = ArrayList<String>()
        summary(b.all(Step.RELAY_PING))?.let { if (it.median > RELAY_WARN_MS) out += "Relay is slow: typical ${ms(it.median)} a round trip (over ${ms(RELAY_WARN_MS)})." }
        summary(b.all(Step.PRICE_AGE))?.let { if (it.p95 > PRICE_AGE_WARN_MS) out += "Prices were up to ${ms(it.p95)} old when used (over ${ms(PRICE_AGE_WARN_MS)})." }
        summary(b.all(Step.CLOCK_OFFSET))?.let { if (kotlin.math.abs(it.median) > 1_000) out += "The phone's clock is off by ${ms(kotlin.math.abs(it.median))}: switch on automatic date and time." }
        summary(b.all(Step.SIGNAL_SENT))?.let { if (it.p95 > SIGNAL_SENT_WARN_MS) out += "Signal to order took up to ${ms(it.p95)} (over ${ms(SIGNAL_SENT_WARN_MS)})." }
        return out
    }

    /**
     * Where the relay is, plainly: its [region] when it said one, and, when its typical round trip ([relayMedianMs]) is over
     * [RELAY_FAR_MS], the suggestion to move it to Mumbai. Null: nothing to say (no relay timing). Never changes anything.
     */
    fun relayAdvice(relayMedianMs: Long?, region: String?): String? {
        if (relayMedianMs == null) return null
        val where = region?.takeIf { it.isNotBlank() }?.let { "Relay region: $it. " } ?: "The relay does not say its region. "
        return where + if (relayMedianMs > RELAY_FAR_MS) "$MUMBAI." else "Its round trip (${ms(relayMedianMs)}) is fine."
    }

    /** One line for the diagnostics export: today's typical and worst per step, both modes together. */
    fun diagLine(b: Book): String {
        val parts = Step.entries.mapNotNull { st ->
            summary(b.all(st))?.let { s -> "${st.key} ${s.median}/${s.p95}/${s.max}ms n${s.n}" }
        }
        return "Order speed (${b.day}, typical/worst/slowest): " + (parts.joinToString(" · ").ifEmpty { "no timings yet" }) +
            warnings(b).joinToString("") { " · WARN $it" }
    }
}
