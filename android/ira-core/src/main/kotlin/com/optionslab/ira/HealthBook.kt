package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * The app's own health (Boss, 11 Oct: "enhance the self improvement" - the app improves its own health and speed). What the
 * phone saw of itself, kept on the phone only (noBackupFilesDir; nothing leaves it, nothing goes to a backup):
 *
 *  - main-thread stalls of [STALL_MS] or more (the screen watchdog posts to the main thread once a second while the app is
 *    on screen), each with WHERE it was stuck (class and method names only - never data, never a message);
 *  - the previous runs' ANRs and crashes (Android's own exit records, newest first, at most [MAX_EXITS]);
 *  - the order path's day median, signal to order sent (from the order-speed timings: durations only);
 *  - the price stream's reconnects and its stale-feed episodes;
 *  - workers that ran past their time limit;
 *  - whether Android's battery saver was on; the memory-trim callbacks.
 *
 * Kept per day for [KEEP_DAYS] days. [HealthTuner] reads it to lengthen a NON-critical cadence where stalls cluster and to put
 * it back after [HealthTuner.RESTORE_DAYS] healthy days. Pure: no clock, no Android; the app passes times in.
 */
object HealthBook {
    /** A wait of the main thread this long or more is a stall. */
    const val STALL_MS = 700L
    const val KEEP_DAYS = 14
    /** The newest stalls kept with their place per day (the count goes on beyond). */
    const val SAMPLES_DAY = 10
    /** At most this many places counted per day (the rest under [OTHER]). */
    const val PLACES_DAY = 24
    const val MAX_EXITS = 20
    const val OTHER = "elsewhere"

    enum class ExitKind(val words: String) {
        ANR("an ANR (the app stopped responding)"), CRASH("a crash"), NATIVE_CRASH("a native crash"), LOW_MEMORY("a close by Android for memory"),
    }

    data class Stall(val atMs: Long, val ms: Long, val where: String)
    data class Exit(val atMs: Long, val kind: ExitKind, val where: String)

    data class Day(
        val day: LocalDate,
        val stalls: Int = 0,
        val longestMs: Long = 0,
        /** Stalls by place ([place]). */
        val places: Map<String, Int> = emptyMap(),
        val samples: List<Stall> = emptyList(),
        val reconnects: Int = 0,
        val stale: Int = 0,
        /** Worker name to its runs past the time limit. */
        val overruns: Map<String, Int> = emptyMap(),
        /** Looks that found Android's battery saver on, of [saverLooks]. */
        val saverOn: Int = 0,
        val saverLooks: Int = 0,
        val trims: Int = 0,
        /** Signal to order sent: the day's median and how many orders it is over (null: none timed). */
        val orderMedianMs: Long? = null,
        val orderN: Int = 0,
    )

    /** One cadence the tuner lengthened: its value now, the day first lengthened, the day of its last step, and why. */
    data class Tuned(val key: String, val ms: Long, val since: LocalDate, val stepOn: LocalDate, val why: String)

    data class Book(
        val days: List<Day> = emptyList(),
        val exits: List<Exit> = emptyList(),
        /** The newest exit time already read from Android's records (an exit is never counted twice). */
        val exitsSeenTo: Long = 0L,
        val tuned: Map<String, Tuned> = emptyMap(),
    ) {
        fun day(d: LocalDate): Day? = days.firstOrNull { it.day == d }
    }

    fun dayOf(ms: Long): LocalDate = AgentLessons.dayOf(ms)

    private fun clean(s: String, n: Int = 60) = s.replace(Regex("[|;=,\\n\\r:]"), " ").replace(Regex("\\s+"), " ").trim().take(n).ifEmpty { "?" }

    /**
     * Where a stall was stuck, as a short key: the app's own frame when the watchdog named one ("Choreographer.doFrame in
     * BrainSection.invoke" -> "BrainSection.invoke"), else the top frame. Never anything but class and method names.
     */
    fun place(where: String?): String {
        val w = where?.trim().orEmpty()
        if (w.isEmpty()) return "?"
        val ours = w.substringAfterLast(" in ", "").trim()
        return clean(ours.ifEmpty { w })
    }

    private fun upd(b: Book, d: LocalDate, f: (Day) -> Day): Book {
        val old = b.day(d) ?: Day(d)
        val days = (b.days.filter { it.day != d } + f(old)).sortedBy { it.day }
        val keep = days.lastOrNull()?.day?.minusDays(KEEP_DAYS.toLong() - 1)
        return b.copy(days = if (keep == null) days else days.filter { !it.day.isBefore(keep) })
    }

    private fun bump(m: Map<String, Int>, k: String, cap: Int = PLACES_DAY): Map<String, Int> {
        val key = if (k in m || m.size < cap) k else OTHER
        return m + (key to ((m[key] ?: 0) + 1))
    }

    /** A stall of [ms] at [atMs] in [where] (shorter than [STALL_MS]: not one). */
    fun stall(b: Book, atMs: Long, ms: Long, where: String?): Book {
        if (ms < STALL_MS) return b
        val p = place(where)
        return upd(b, dayOf(atMs)) { d -> d.copy(stalls = d.stalls + 1, longestMs = maxOf(d.longestMs, ms), places = bump(d.places, p),
            samples = (d.samples + Stall(atMs, ms, p)).takeLast(SAMPLES_DAY)) }
    }

    fun reconnect(b: Book, atMs: Long): Book = upd(b, dayOf(atMs)) { it.copy(reconnects = it.reconnects + 1) }
    fun stale(b: Book, atMs: Long): Book = upd(b, dayOf(atMs)) { it.copy(stale = it.stale + 1) }
    fun overrun(b: Book, atMs: Long, worker: String): Book = upd(b, dayOf(atMs)) { it.copy(overruns = bump(it.overruns, clean(worker, 40))) }
    fun saver(b: Book, atMs: Long, on: Boolean): Book = upd(b, dayOf(atMs)) { it.copy(saverOn = it.saverOn + if (on) 1 else 0, saverLooks = it.saverLooks + 1) }
    fun trim(b: Book, atMs: Long): Book = upd(b, dayOf(atMs)) { it.copy(trims = it.trims + 1) }

    /** The order path's median on [day] over [n] orders (null or n == 0: nothing timed, left as it was). */
    fun orderPath(b: Book, day: LocalDate, medianMs: Long?, n: Int): Book =
        if (medianMs == null || n <= 0) b else upd(b, day) { it.copy(orderMedianMs = medianMs, orderN = n) }

    /** Android's exit records (any order): those newer than [Book.exitsSeenTo] kept, newest first. */
    fun exits(b: Book, seen: List<Exit>): Book {
        val fresh = seen.filter { it.atMs > b.exitsSeenTo }.map { it.copy(where = clean(it.where)) }
        if (fresh.isEmpty()) return b
        return b.copy(exits = (fresh + b.exits).sortedByDescending { it.atMs }.take(MAX_EXITS), exitsSeenTo = maxOf(b.exitsSeenTo, fresh.maxOf { it.atMs }))
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun sec(ms: Long) = String.format(Locale.ENGLISH, "%.1f s", ms / 1000.0)
    private fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
    fun msWords(ms: Long): String = if (ms >= 10_000) String.format(Locale.ENGLISH, "%.0f s", ms / 1000.0) else if (ms >= 1_000) sec(ms) else "$ms ms"

    private fun crashes(e: Exit) = e.kind == ExitKind.CRASH || e.kind == ExitKind.NATIVE_CRASH

    /** The diagnostics' line: stalls today, the last ANR and crash and where, the tuner's adjustments now. */
    fun diagLine(b: Book, today: LocalDate, hhmm: (Long) -> String): String {
        val d = b.day(today)
        val stalls = if (d == null || d.stalls == 0) "no stalls today" else
            "${plural(d.stalls, "stall")} today (longest ${sec(d.longestMs)}, most in ${d.places.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key})"
        val anr = b.exits.firstOrNull { it.kind == ExitKind.ANR }?.let { "last ANR ${AgentLessons.day(dayOf(it.atMs))} ${hhmm(it.atMs)} in ${it.where}" } ?: "no ANR seen"
        val crash = b.exits.firstOrNull { crashes(it) }?.let { "last crash ${AgentLessons.day(dayOf(it.atMs))} ${hhmm(it.atMs)} in ${it.where}" } ?: "no crash seen"
        val tuner = if (b.tuned.isEmpty()) "tuner: nothing adjusted" else "tuner: " + b.tuned.values.sortedBy { it.key }.joinToString("; ") {
            "${HealthTuner.words(it.key)} every ${msWords(it.ms)} (normally ${msWords(HealthTuner.defaultMs(it.key))}, since ${AgentLessons.day(it.since)})"
        }
        val more = if (d == null) "" else " · stream reconnects ${d.reconnects}, stale ${d.stale}, worker overruns ${d.overruns.values.sum()}, " +
            "battery saver ${if (d.saverOn > 0) "on" else "off"}, memory trims ${d.trims}"
        return "Health: $stalls · $anr · $crash · $tuner$more"
    }

    /** The last [days] days' (to [today]) ANRs and crashes. */
    private fun recentExits(b: Book, today: LocalDate, days: Long = 7): List<Exit> =
        b.exits.filter { !dayOf(it.atMs).isBefore(today.minusDays(days - 1)) && !dayOf(it.atMs).isAfter(today) && it.kind != ExitKind.LOW_MEMORY }

    /** "Is the app healthy?": yes or not quite, with the numbers today and over the last week. */
    fun sayHealthy(b: Book, today: LocalDate): String {
        val d = b.day(today)
        val week = b.days.filter { !it.day.isBefore(today.minusDays(6)) && !it.day.isAfter(today) }
        val weekStalls = week.sumOf { it.stalls }
        val ex = recentExits(b, today)
        val anrs = ex.count { it.kind == ExitKind.ANR }
        val crash = ex.count { crashes(it) }
        val todayStalls = d?.stalls ?: 0
        val ok = anrs == 0 && crash == 0 && todayStalls < HealthTuner.CLUSTER_N
        val head = if (ok) "Yes, Boss: the app is healthy." else "Not quite, Boss."
        val parts = ArrayList<String>()
        parts += "Today: ${plural(todayStalls, "stall")} of ${sec(STALL_MS)} or more" + (if (todayStalls > 0) " (longest ${sec(d!!.longestMs)})" else "") + "."
        parts += "Last 7 days: ${plural(weekStalls, "stall")}, ${plural(anrs, "ANR")}, ${plural(crash, "crash").replace("crashs", "crashes")}."
        d?.orderMedianMs?.let { parts += "Orders today: signal to sent ${msWords(it)} typical over ${d.orderN}." }
        if (b.tuned.isNotEmpty()) parts += "To keep it smooth I slowed " + b.tuned.values.sortedBy { it.key }.joinToString(", ") {
            "${HealthTuner.words(it.key)} to every ${msWords(it.ms)}" } + "; the order path, stops and the price stream are never slowed."
        return "$head ${parts.joinToString(" ")}"
    }

    /** "Why was the app slow?": the latest day with stalls - where, how long - and what else was going on then. */
    fun sayWhySlow(b: Book, today: LocalDate, hhmm: (Long) -> String): String {
        val d = b.days.filter { !it.day.isAfter(today) && (it.stalls > 0 || it.overruns.isNotEmpty()) }.maxByOrNull { it.day }
        val anr = recentExits(b, today).firstOrNull { it.kind == ExitKind.ANR }
        if (d == null && anr == null) return "I saw no slowdowns, Boss: no stall of ${sec(STALL_MS)} or more and no worker past its time limit in the last $KEEP_DAYS days."
        val parts = ArrayList<String>()
        if (d != null) {
            val whenSaid = if (d.day == today) "Today" else "On ${AgentLessons.day(d.day)}"
            if (d.stalls > 0) {
                val top = d.places.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3)
                parts += "$whenSaid the screen stalled ${if (d.stalls == 1) "once" else "${d.stalls} times"}, longest ${sec(d.longestMs)}: mostly in " +
                    top.joinToString(", ") { "${it.key} (${it.value})" } + "."
                d.samples.maxByOrNull { it.ms }?.let { parts += "The worst was at ${hhmm(it.atMs)} in ${it.where}." }
            }
            if (d.overruns.isNotEmpty()) parts += "Workers past their time limit: " + d.overruns.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}×" } + "."
            val also = ArrayList<String>()
            if (d.saverOn > 0) also += "Android's battery saver was on"
            if (d.trims > 0) also += "Android asked for memory back ${plural(d.trims, "time")}"
            if (d.reconnects > 0) also += "the price stream reconnected ${plural(d.reconnects, "time")}"
            if (d.stale > 0) also += "prices went stale ${plural(d.stale, "time")}"
            if (also.isNotEmpty()) parts += "Also then: " + also.joinToString("; ") + "."
        }
        if (anr != null) parts += "Last ANR: ${AgentLessons.day(dayOf(anr.atMs))} ${hhmm(anr.atMs)} in ${anr.where}."
        if (b.tuned.isNotEmpty()) parts += "What I changed: " + b.tuned.values.sortedBy { it.key }.joinToString(", ") {
            "${HealthTuner.words(it.key)} every ${msWords(it.ms)} (${it.why})" } + "."
        return parts.joinToString(" ")
    }

    // ---- asked ----------------------------------------------------------------------------------------------------------

    enum class Ask { HEALTHY, SLOW }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| today| now| lately)* $"
    private const val APP = "(the |your |my )?(app|application|iraalgo)"
    private val HEALTHY = Regex(LEAD + "(is $APP (healthy|ok|okay|fine|in good health|running (ok|okay|fine|well|smoothly))|" +
        "how (is|s) $APP s? health|how healthy is $APP|$APP health( report| check)?|is $APP (running )?smooth(ly)?|" +
        "how is $APP (running|holding up))" + TAIL)
    private val SLOW = Regex(LEAD + "(why (was|is|did|has) $APP (been )?(slow|so slow|lagging|laggy|hanging|stuck|freezing|frozen|hang|lag|freeze|jank|janky)|" +
        "why (was|is) it (so )?(slow|laggy|lagging) in $APP|what (made|is making|slowed( down)?) $APP (slow|lag|laggy)?|why (was|is) $APP slow( today| earlier)?)" + TAIL)

    /** "Is the app healthy?" / "why was the app slow?", else null. */
    fun asked(text: String): Ask? {
        val t = Spaced.joined(text)
        if (SLOW.containsMatchIn(t)) return Ask.SLOW
        if (HEALTHY.containsMatchIn(t)) return Ask.HEALTHY
        return null
    }

    // ---- the file ---------------------------------------------------------------------------------------------------------

    private fun kv(m: Map<String, Int>) = m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
    private fun unkv(s: String): Map<String, Int> = s.split(';').filter { '=' in it }.mapNotNull { p ->
        p.substringAfterLast('=').toIntOrNull()?.let { p.substringBeforeLast('=') to it } }.toMap()

    /** One line per day, stall, exit and tuned cadence (plain text; class and method names and numbers only). */
    fun encode(b: Book): String = buildString {
        append("H1|").append(b.exitsSeenTo).append('\n')
        for (d in b.days) {
            append("D|${d.day}|${d.stalls}|${d.longestMs}|${d.reconnects}|${d.stale}|${d.saverOn}|${d.saverLooks}|${d.trims}|${d.orderMedianMs ?: ""}|${d.orderN}|")
            append(kv(d.places)).append('|').append(kv(d.overruns)).append('\n')
            for (s in d.samples) append("S|${s.atMs}|${s.ms}|${s.where}\n")
        }
        for (e in b.exits) append("X|${e.atMs}|${e.kind.name}|${e.where}\n")
        for (t in b.tuned.values.sortedBy { it.key }) append("T|${t.key}|${t.ms}|${t.since}|${t.stepOn}|${clean(t.why, 120)}\n")
    }

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var seen = 0L
        val days = ArrayList<Day>()
        val exits = ArrayList<Exit>()
        val tuned = LinkedHashMap<String, Tuned>()
        for (line in text.lineSequence()) runCatching {
            val p = line.split('|')
            when (p[0]) {
                "H1" -> seen = p[1].toLong()
                "D" -> days += Day(LocalDate.parse(p[1]), p[2].toInt(), p[3].toLong(), unkv(p[11]), emptyList(), p[4].toInt(), p[5].toInt(),
                    unkv(p[12]), p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toLongOrNull(), p[10].toInt())
                "S" -> days.lastOrNull()?.let { d -> days[days.size - 1] = d.copy(samples = (d.samples + Stall(p[1].toLong(), p[2].toLong(), p[3])).takeLast(SAMPLES_DAY)) }
                "X" -> exits += Exit(p[1].toLong(), ExitKind.valueOf(p[2]), p[3])
                "T" -> if (HealthTuner.tunable(p[1]) != null) tuned[p[1]] = Tuned(p[1], HealthTuner.bounded(p[1], p[2].toLong()), LocalDate.parse(p[3]),
                    LocalDate.parse(p[4]), p.getOrElse(5) { "" })
            }
        }
        return Book(days.sortedBy { it.day }, exits.sortedByDescending { it.atMs }.take(MAX_EXITS), seen, tuned)
    }
}

/**
 * The app's self-healing for its own speed ([HealthBook]): when stalls of the screen (or a worker's runs past its time limit)
 * cluster on something that runs on a NON-critical cadence, that cadence is lengthened one step (doubled, at most to its
 * documented bound, one step a day); after [RESTORE_DAYS] days with nothing more there it goes back to normal.
 *
 * Only the cadences in [TUNABLES] can move, each within [Tunable.defaultMs]..[Tunable.maxMs]. Never the order path, stops,
 * exits, the price stream or its tick consumer's priority ([FORBIDDEN] keeps that a test, not a hope). Risk-neutral: it
 * only changes how often something non-critical is refreshed or written. Pure.
 */
object HealthTuner {
    /** Stalls attributed to one cadence on one day before it is lengthened. */
    const val CLUSTER_N = 3
    /** Runs past the time limit of a worker tied to one cadence, on one day, before it is lengthened. */
    const val OVERRUN_N = 2
    /** Days with nothing more there before a lengthened cadence goes back to normal. */
    const val RESTORE_DAYS = 3L

    /**
     * One cadence that may move: its key, its plain words, its normal and its longest interval, the code places (class or
     * method names, as the stall watchdog names them) that count against it, and the workers whose overruns do.
     */
    data class Tunable(val key: String, val words: String, val defaultMs: Long, val maxMs: Long, val places: List<String>, val workers: List<String> = emptyList())

    /**
     * The non-critical cadences: the Brain card's consensus refresh (15 s, at most 60 s: words on screen only), the move
     * recorder's flush of an open event to its file (30 s, at most 120 s; a 256 KB buffer still flushes at once), and the
     * findings log's write (30 s, at most 120 s; findings stay in memory meanwhile, nothing reads the file during the day).
     */
    val TUNABLES: List<Tunable> = listOf(
        Tunable("brain.consensus", "the Brain card's consensus refresh", 15_000L, 60_000L, listOf("BrainSection", "BrainHomeCard", "consensusLines", "Findings.consensus")),
        Tunable("moves.flush", "the move recorder's file flush", 30_000L, 120_000L, listOf("MoveRecorder", "GZIPOutputStream", "Deflater")),
        Tunable("findings.flush", "the findings log's write", 30_000L, 120_000L, listOf("flushFindings", "drainLines", "Findings.Bus", "Findings.line"), listOf("Market brain")),
    )

    /** What may never be tuned: anything that names an order, a stop, an exit, the stream, a tick, a fill or a price lane. */
    val FORBIDDEN = Regex("(?i)order|stop|exit|stream|tick|fill|fastpath|lane|kite|broker|paper|guard|square|risk")

    private val BY_KEY = TUNABLES.associateBy { it.key }

    fun tunable(key: String): Tunable? = BY_KEY[key]?.takeIf { !FORBIDDEN.containsMatchIn(it.key) }
    fun defaultMs(key: String): Long = BY_KEY[key]?.defaultMs ?: 0L
    fun words(key: String): String = BY_KEY[key]?.words ?: key
    fun bounded(key: String, ms: Long): Long = tunable(key)?.let { ms.coerceIn(it.defaultMs, it.maxMs) } ?: ms

    /** The interval now for [key] in [b] (its normal value when not lengthened). */
    fun cadenceMs(b: HealthBook.Book, key: String): Long = b.tuned[key]?.ms?.let { bounded(key, it) } ?: defaultMs(key)

    /** Which cadence a stall place counts against (null: none - most stalls count against nothing tunable). */
    fun of(place: String): Tunable? = TUNABLES.firstOrNull { t -> t.places.any { place.contains(it, ignoreCase = true) } }?.takeIf { tunable(it.key) != null }

    /** How much counted against [t] on day [d]: stalls in its places, and overruns of its workers. */
    fun load(t: Tunable, d: HealthBook.Day?): Pair<Int, Int> {
        if (d == null) return 0 to 0
        val stalls = d.places.entries.filter { (p, _) -> t.places.any { p.contains(it, ignoreCase = true) } }.sumOf { it.value }
        val over = d.overruns.entries.filter { (w, _) -> t.workers.any { w.equals(it, ignoreCase = true) } }.sumOf { it.value }
        return stalls to over
    }

    private fun clustered(t: Tunable, d: HealthBook.Day?): Boolean = load(t, d).let { (s, o) -> s >= CLUSTER_N || o >= OVERRUN_N }

    /** One adjustment: [key] from [fromMs] to [toMs]; [restore]: back to normal; [why] in words with the numbers. */
    data class Adjust(val key: String, val fromMs: Long, val toMs: Long, val restore: Boolean, val why: String) {
        fun words(): String = if (restore) "${HealthTuner.words(key)} back to every ${HealthBook.msWords(toMs)} ($why)"
            else "${HealthTuner.words(key)} slowed from every ${HealthBook.msWords(fromMs)} to every ${HealthBook.msWords(toMs)} ($why)"
    }

    /**
     * On [today]: each cadence clustered today (or yesterday, not yet answered) is lengthened one step - doubled, within its
     * bound, at most one step a day; one lengthened with nothing against it on each of the [RESTORE_DAYS] whole days since
     * its last step (and nothing today) goes back to normal. The book with the tuned cadences, and what changed.
     */
    fun evaluate(b: HealthBook.Book, today: LocalDate): Pair<HealthBook.Book, List<Adjust>> {
        val out = ArrayList<Adjust>()
        val tuned = LinkedHashMap(b.tuned.filterKeys { tunable(it) != null })
        for (t in TUNABLES) {
            if (tunable(t.key) == null) continue
            val cur = tuned[t.key]
            val now = cur?.ms ?: t.defaultMs
            val hot = listOf(today, today.minusDays(1)).firstOrNull { clustered(t, b.day(it)) }
            if (hot != null && cur?.stepOn != today && (cur == null || cur.stepOn.isBefore(hot) || hot == today) && now < t.maxMs) {
                val to = minOf(now * 2, t.maxMs)
                val (s, o) = load(t, b.day(hot))
                val why = listOfNotNull(if (s > 0) "$s stall${if (s == 1) "" else "s"} there" else null, if (o > 0) "$o run${if (o == 1) "" else "s"} past the time limit" else null)
                    .joinToString(", ") + " on ${AgentLessons.day(hot)}"
                tuned[t.key] = HealthBook.Tuned(t.key, to, cur?.since ?: today, today, why)
                out += Adjust(t.key, now, to, false, why)
                continue
            }
            if (cur != null && !cur.stepOn.isAfter(today.minusDays(RESTORE_DAYS + 1)) &&
                (1..RESTORE_DAYS).all { k -> !clustered(t, b.day(today.minusDays(k))) && load(t, b.day(today.minusDays(k))) == (0 to 0) } &&
                load(t, b.day(today)) == (0 to 0)) {
                tuned.remove(t.key)
                out += Adjust(t.key, cur.ms, t.defaultMs, true, "healthy for $RESTORE_DAYS days")
            }
        }
        return b.copy(tuned = tuned) to out
    }
}
