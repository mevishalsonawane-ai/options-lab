package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * Per-regime settings (TODO S8, 11 Oct): an optional override layer, by the day's market regime, for (a) the trade
 * manager's specialist weights per strategy and (b) the arms' parameters (read by the arms through [overrideFor]; the
 * strategy lab's ArmParams merge the map it returns).
 *
 *  - The day's regime ([Regime]: trending / sideways / busy / quiet) is decided ONCE at 09:45 from the morning's data
 *    (the market brain's NIFTY regime over 09:15-09:45, [decide]) and frozen for the day ([Day]), so a setting never flips
 *    mid-day. With it the expiry-day and event-day flags (an event day applies no override and counts no shadow day).
 *  - Overrides come only from Jarvis's lessons that are regime-conditional ([AgentLessons.Lesson.cond] "regime:busy") -
 *    which already passed the night's Benjamini-Hochberg family at [AgentLessons.BH_Q] with [AgentLessons.COND_N]+ cases
 *    a side - or from a [Candidate] that passes the same bar. They only ever lower risk: an arm that loses on busy days is
 *    sat out on them (size 0); a specialist that helps on them weighs more, one that hurts weighs less.
 *  - SHADOW first: each override records "would have used" on every matching (non-event) day; after [SHADOW_DAYS] such
 *    trading days it applies on PAPER by itself; LIVE only once Boss approved it with his PIN (Autopilot's ASK, [approveLive]).
 *
 * Pure apart from its in-memory [book] (set by the app; read without locks on the hot paths).
 */
object RegimeParams {
    const val DECIDE_MINUTE = 9 * 60 + 45
    const val SHADOW_DAYS = 10
    /** A specialist's weight is multiplied by at most this (helps) or at least [WEIGHT_DOWN] (hurts). */
    const val WEIGHT_UP = 1.25
    const val WEIGHT_DOWN = 0.5
    /** The parameter an arm reads to sit a regime out (0: no new entries that day; never more than 1). */
    const val SIZE_KEY = "sizeMult"

    enum class Regime(val words: String) { TRENDING("trending"), SIDEWAYS("sideways"), BUSY("busy"), QUIET("quiet") }

    enum class Stage(val words: String) { SHADOW("shadow"), PAPER("paper"), LIVE("paper and live") }

    /** The day's frozen picture: its regime (null: not decided / not known), the expiry and event flags, when it was decided. */
    data class Day(val day: LocalDate, val regime: Regime?, val expiry: Boolean, val event: Boolean, val decidedAt: Int)

    /** The brain's regime read as the day's: busy and quiet as they are; a normal day trending or sideways by its trend. */
    fun decide(r: MarketBrain.Regime?): Regime? = if (r == null) null else when (r.state) {
        MarketBrain.State.BUSY -> Regime.BUSY
        MarketBrain.State.QUIET -> Regime.QUIET
        MarketBrain.State.NORMAL -> when (r.trend) {
            MarketBrain.Trend.UP, MarketBrain.Trend.DOWN -> Regime.TRENDING
            MarketBrain.Trend.RANGE, MarketBrain.Trend.MIXED -> Regime.SIDEWAYS
            MarketBrain.Trend.UNKNOWN -> null
        }
        MarketBrain.State.UNKNOWN -> null
    }

    /**
     * The day frozen: [kept] when it is already [today]'s; else, at or after [DECIDE_MINUTE], decided from [read] (the
     * brain's NIFTY regime now). Before 09:45 (or with nothing to read): null - no override applies yet.
     */
    fun freeze(kept: Day?, today: LocalDate, minute: Int, expiry: Boolean, event: Boolean, read: () -> MarketBrain.Regime?): Day? {
        if (kept != null && kept.day == today) return kept
        if (minute < DECIDE_MINUTE) return null
        return Day(today, decide(read()), expiry, event, minute)
    }

    /** One override: [target] "arm:<arm>" or "spec:<family>:<specialist>"; [params] what it sets; from [lessonId]. */
    data class Override(
        val target: String, val regime: Regime, val params: Map<String, Double>, val lessonId: String, val q: Double,
        val words: String,
        /** The days it would have been used (shadow days), newest last. */
        val shadowDays: List<LocalDate> = emptyList(),
        /** Boss approved it for live with his PIN (Autopilot's ASK). */
        val liveApproved: Boolean = false,
    ) {
        val key: String get() = "$target|${regime.name}"
        val stage: Stage get() = when {
            shadowDays.size < SHADOW_DAYS -> Stage.SHADOW
            liveApproved -> Stage.LIVE
            else -> Stage.PAPER
        }
    }

    /** A regime-conditional candidate from elsewhere (the strategy lab), held to the lessons' bar. */
    data class Candidate(val arm: String, val regime: Regime, val params: Map<String, Double>, val q: Double,
                         val nInside: Int, val nOutside: Int, val source: String)

    fun passes(c: Candidate): Boolean = c.q.isFinite() && c.q <= AgentLessons.BH_Q + 1e-12 &&
        c.nInside >= AgentLessons.COND_N && c.nOutside >= AgentLessons.COND_N && lowersRisk(c.params)

    /** Only ever less: a size at most 1 and nothing else that adds (any other key is taken as the lab's own, bounded there). */
    fun lowersRisk(p: Map<String, Double>): Boolean = p[SIZE_KEY]?.let { it in 0.0..1.0 } ?: true

    private fun regimesOf(cond: String): List<Regime> = when (cond.substringAfter("regime:", "")) {
        "busy" -> listOf(Regime.BUSY)
        "quiet" -> listOf(Regime.QUIET)
        "normal" -> listOf(Regime.TRENDING, Regime.SIDEWAYS)
        "trending" -> listOf(Regime.TRENDING)
        "sideways" -> listOf(Regime.SIDEWAYS)
        else -> emptyList()
    }

    /**
     * The overrides Jarvis's [lessons] support: only those with a regime condition whose split passed (cond "regime:...",
     * condQ at or under [AgentLessons.BH_Q]), not retired, rupee lessons of a strategy ("strat:<arm>", losing there: sat out)
     * or a specialist ("spec:<family>:<id>": weight up or down).
     */
    fun fromLessons(lessons: Collection<AgentLessons.Lesson>): List<Override> {
        val out = ArrayList<Override>()
        for (l in lessons) {
            val cond = l.cond ?: continue
            val q = l.condQ ?: continue
            if (!cond.startsWith("regime:") || q > AgentLessons.BH_Q + 1e-12 || l.status == AgentLessons.Status.RETIRED ||
                l.kind != AgentLessons.Kind.RUPEES || l.sign == 0) continue
            val parts = l.id.split(':')
            for (r in regimesOf(cond)) when (parts[0]) {
                "strat" -> if (l.sign < 0 && parts.size >= 2) out += Override("arm:" + armKey(parts.drop(1).joinToString(":")), r,
                    mapOf(SIZE_KEY to 0.0), l.id, q, "${l.name}: sit out ${r.words} days (it loses on them)")
                "spec" -> if (parts.size >= 3) {
                    val w = if (l.sign > 0) WEIGHT_UP else WEIGHT_DOWN
                    out += Override("spec:${parts[1]}:${parts[2]}", r, mapOf("weight" to w), l.id, q,
                        "${l.name}: weight x${fmt(w)} on ${r.words} days")
                }
            }
        }
        return out
    }

    /** An arm's name or key as overrides key it ("Liquidity 15+5" and "liquidity" alike). */
    fun armKey(s: String): String = PortfolioRisk.canon(s)

    // ---- the book (in memory; the app saves [encode]) ----

    data class Book(val overrides: List<Override> = emptyList(), val day: Day? = null)

    @Volatile var book: Book = Book()
        private set

    fun use(b: Book) { book = b }

    /**
     * Tonight's overrides from [lessons] and [candidates] merged into [b]: those still supported keep their shadow days and
     * approval; a new one starts in shadow; one no longer supported is dropped.
     */
    fun refresh(b: Book, lessons: Collection<AgentLessons.Lesson>, candidates: List<Candidate> = emptyList()): Book {
        val fresh = fromLessons(lessons) + candidates.filter { passes(it) }.map {
            Override("arm:" + armKey(it.arm), it.regime, it.params, it.source, it.q, "${it.arm}: ${it.source} on ${it.regime.words} days")
        }
        val old = b.overrides.associateBy { it.key }
        val merged = fresh.distinctBy { it.key }.map { o -> old[o.key]?.let { was -> o.copy(shadowDays = was.shadowDays, liveApproved = was.liveApproved) } ?: o }
        return b.copy(overrides = merged)
    }

    /**
     * The frozen [day] seen: each override of its regime records the day as one it would have used (once a day; never on
     * an event day). Returns the new book and the overrides that just finished their shadow (ready for paper, Boss asked
     * about live). [hold] (learning frozen, or Autopilot learning not SAFE): an override whose next day would finish its shadow
     * is left in shadow - nothing moves to paper by itself then.
     */
    fun observe(b: Book, day: Day, hold: Boolean = false): Pair<Book, List<Override>> {
        val ready = ArrayList<Override>()
        if (day.regime == null || day.event) return b.copy(day = day) to ready
        val next = b.overrides.map { o ->
            if (o.regime != day.regime || day.day in o.shadowDays) o
            else if (hold && o.shadowDays.size + 1 >= SHADOW_DAYS && o.shadowDays.size < SHADOW_DAYS) o
            else {
                val n = o.copy(shadowDays = (o.shadowDays + day.day).takeLast(SHADOW_DAYS * 3))
                if (o.shadowDays.size < SHADOW_DAYS && n.shadowDays.size >= SHADOW_DAYS) ready += n
                n
            }
        }
        return Book(next, day) to ready
    }

    /** Boss approved [key] for live (his PIN, through Autopilot's ask): only an override past its shadow. */
    fun approveLive(b: Book, key: String): Book =
        b.copy(overrides = b.overrides.map { if (it.key == key && it.shadowDays.size >= SHADOW_DAYS) it.copy(liveApproved = true) else it })

    private fun applies(o: Override, live: Boolean): Boolean = when (o.stage) {
        Stage.SHADOW -> false
        Stage.PAPER -> !live
        Stage.LIVE -> true
    }

    private val IST: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")

    /** Today's date in IST (the default [date] of the readers below; the app passes its market clock's). */
    fun todayIst(): LocalDate = LocalDate.now(IST)

    /**
     * The frozen day when it is [date]'s own (not an event day, regime known). Yesterday's frozen day never applies: before
     * 09:45 (or when the brain's worker has not ticked today) nothing applies.
     */
    private fun today(b: Book, date: LocalDate): Day? = b.day?.takeIf { it.day == date && !it.event && it.regime != null }

    /**
     * [arm]'s parameters for [regime] (paper unless [live]): the map to lay over its own, or null when nothing applies
     * (still in shadow, not approved for live, or no supported override).
     */
    fun overrideFor(arm: String, regime: Regime, live: Boolean = false, b: Book = book): Map<String, Double>? {
        val k = "arm:" + armKey(arm)
        val hits = b.overrides.filter { it.target == k && it.regime == regime && applies(it, live) }
        if (hits.isEmpty()) return null
        val out = HashMap<String, Double>()
        // Several overrides of one key: the smaller value (lower risk) wins.
        for (h in hits) for ((p, v) in h.params) out[p] = out[p]?.let { minOf(it, v) } ?: v
        return out
    }

    /** [arm]'s override for [date]'s frozen regime (none before 09:45, on an event day, or from a stale day). Memory only. */
    fun overrideToday(arm: String, live: Boolean = false, b: Book = book, date: LocalDate = todayIst()): Map<String, Double>? =
        today(b, date)?.regime?.let { overrideFor(arm, it, live, b) }

    /** [cfg] with today's specialist weight overrides for its strategy laid over (multiplied, kept within the config's bounds). */
    fun applyTo(cfg: ManagerConfig, live: Boolean, b: Book = book, date: LocalDate = todayIst()): ManagerConfig {
        val r = today(b, date)?.regime ?: return cfg
        val pre = "spec:${cfg.strategyId}:"
        val hits = b.overrides.filter { it.target.startsWith(pre) && it.regime == r && applies(it, live) }
        if (hits.isEmpty()) return cfg
        var c = cfg
        for (h in hits) {
            val id = h.target.removePrefix(pre)
            val m = h.params["weight"] ?: continue
            val s = c.spec(id)
            c = c.copy(specialists = c.specialists + (id to s.copy(weight = (s.weight * m).coerceIn(0.0, 3.0))))
        }
        return c
    }

    /** What would apply today if every shadow override were on: "would have used the busy-day setting" lines. */
    fun wouldUse(b: Book = book, date: LocalDate = todayIst()): List<String> {
        val d = today(b, date) ?: return emptyList()
        return b.overrides.filter { it.regime == d.regime }.map {
            "${it.words} - ${if (it.stage == Stage.SHADOW) "shadow, day ${it.shadowDays.size} of $SHADOW_DAYS (would have used the ${d.regime!!.words}-day setting)" else "applies on ${it.stage.words}"}"
        }
    }

    /** The one line for Jarvis / the Brain card. */
    fun line(b: Book = book): String {
        val d = b.day ?: return "Today's regime is decided at 09:45 and then kept for the day."
        val r = d.regime?.words ?: "not known"
        val flags = listOfNotNull("expiry day".takeIf { d.expiry }, "event day (no regime setting applies)".takeIf { d.event })
        val n = b.overrides.count { it.regime == d.regime }
        return "Today: $r (decided %02d:%02d, kept all day)".format(Locale.ENGLISH, d.decidedAt / 60, d.decidedAt % 60) +
            (if (flags.isNotEmpty()) "; " + flags.joinToString("; ") else "") +
            (if (n > 0) "; $n regime setting${if (n == 1) "" else "s"} for it" else "")
    }

    private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else "%.2f".format(Locale.ENGLISH, x).trimEnd('0').trimEnd('.')

    // ---- saved form: one line an override, "D|day|regime|expiry|event|minute" for the day ----

    fun encode(b: Book): String = buildString {
        b.day?.let { append("D|").append(it.day).append('|').append(it.regime?.name ?: "-").append('|').append(if (it.expiry) 1 else 0)
            .append('|').append(if (it.event) 1 else 0).append('|').append(it.decidedAt).append('\n') }
        for (o in b.overrides) {
            append("O|").append(o.target).append('|').append(o.regime.name).append('|')
                .append(o.params.entries.joinToString(",") { "${it.key}=${it.value}" }).append('|').append(o.lessonId.replace('|', '/')).append('|')
                .append(o.q).append('|').append(o.shadowDays.joinToString(",")).append('|').append(if (o.liveApproved) 1 else 0).append('|')
                .append(o.words.replace('|', '/').replace('\n', ' ')).append('\n')
        }
    }

    fun decode(s: String?): Book {
        if (s.isNullOrBlank()) return Book()
        var day: Day? = null
        val os = ArrayList<Override>()
        for (line in s.lineSequence()) runCatching {
            val f = line.split('|')
            when (f[0]) {
                "D" -> day = Day(LocalDate.parse(f[1]), f[2].takeIf { it != "-" }?.let { Regime.valueOf(it) }, f[3] == "1", f[4] == "1", f[5].toInt())
                "O" -> os += Override(f[1], Regime.valueOf(f[2]),
                    f[3].split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() },
                    f[4], f[5].toDouble(), f.getOrElse(8) { "" },
                    f[6].split(',').filter { it.isNotBlank() }.map { LocalDate.parse(it) }, f[7] == "1")
            }
        }
        return Book(os, day)
    }

    /** TEST ONLY. */
    fun resetForTest() { book = Book() }
}
