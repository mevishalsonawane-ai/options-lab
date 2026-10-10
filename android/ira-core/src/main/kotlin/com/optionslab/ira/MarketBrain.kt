package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The shared market brain (Boss, 10 Oct: "make the workers smart", part 3): one picture of the market every worker and
 * strategy reads, instead of each working it out alone ([Context], kept in memory by the app as a StateFlow):
 *
 *  - the regime per index ([regime]): BUSY / QUIET / NORMAL by R11's rule - the 30-minute range or realised volatility at
 *    2 SD or more above normal is busy (a top-1% minute 3.3x as likely in the next 15 minutes; no direction). Trend / range
 *    readings are SHOWN ONLY: no rule uses them;
 *  - the scheduled events and their ±[PAD_MIN]-minute windows ([windows]): RBI policy 10:00, the Union Budget 11:00, US CPI
 *    and jobs 08:30 and FOMC 14:00 New York time (DST-aware), EIA crude (Wednesday) and gas (Thursday) 10:30 New York time
 *    for MCX, and each index's expiry hour (the last hour of F&O on its expiry day; shown, never a pause);
 *  - the order flow's trap flags per index (TrapGuard: pulls, stop hunts, failed breaks, an unreliable feed);
 *  - the big-move alarm (the move recorder's trigger) and its first [BIG_MOVE_MS];
 *  - the data's health ([SelfHeal.Fallbacks]).
 *
 * And the one global "pause new entries during ..." ([Policy], [decide]): news windows ON by default (paper and live),
 * trap alerts / an unreliable feed / a big move's first 60 s SHADOW by default - logged beside each signal in the order
 * flow's shadow log with what it would have done, never acted on, because the research did not prove they help. A pause only
 * ever SKIPS a new entry; exits never ask it. Pure: no clock, no Android.
 */
object MarketBrain {
    const val PAD_MIN = 5
    const val BIG_MOVE_MS = 60_000L
    /** R11's busy line: 2 SD. */
    const val BUSY_Z = 2.0
    /** Quiet (our line; R11 defines only busy): both readings 1 SD or more below normal. */
    const val QUIET_Z = -1.0
    /**
     * The spread of log(today / normal) across days, for the 30-minute realised volatility and range against the normal
     * move of those minutes ([MoveEvents.normalBp], VIX-scaled): R11 measured z against the same minute of the previous 40
     * days; on the phone the normal table stands for that history, and 0.35 (the day-to-day spread of log volatility) turns
     * the ratio into z. So 2 SD = 2.0x normal.
     */
    const val SIGMA_LN = 0.35
    /** At least this many of the last 30 minutes are needed for a regime (else UNKNOWN). */
    const val MIN_MINUTES = 20
    const val WINDOW_MINUTES = 30

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val NY: ZoneId = ZoneId.of("America/New_York")

    // ---- the regime ----------------------------------------------------------------------------------------------------

    enum class State(val words: String) { BUSY("busy"), QUIET("quiet"), NORMAL("normal"), UNKNOWN("not known yet") }
    enum class Trend(val words: String) { UP("trending up"), DOWN("trending down"), RANGE("ranging"), MIXED("mixed"), UNKNOWN("—") }

    data class Regime(
        val name: String, val state: State, val rangeZ: Double? = null, val rvZ: Double? = null,
        val trend: Trend = Trend.UNKNOWN, val efficiency: Double? = null,
    )

    /**
     * [name]'s regime from its last 1-minute [closes] (oldest first, up to [WINDOW_MINUTES] + 1) and [normalBp] - the normal
     * |1-minute move| (basis points) of each of those minutes ([MoveEvents.normalBp]; one value per return, i.e. size - 1, or
     * a single value for all). Realised: the mean |move| against the normal; range: high-low of the closes against the random
     * walk's expected range (2·√n·normal). z = ln(ratio) / [SIGMA_LN].
     */
    fun regime(name: String, closes: List<Double>, normalBp: List<Double>): Regime {
        val c = closes.filter { it > 0 }.takeLast(WINDOW_MINUTES + 1)
        val n = c.size - 1
        if (n < MIN_MINUTES || normalBp.isEmpty()) return Regime(name, State.UNKNOWN)
        val norms = if (normalBp.size == 1) List(n) { normalBp[0] } else normalBp.takeLast(n)
        val normal = norms.average()
        if (!(normal > 0)) return Regime(name, State.UNKNOWN)
        val rets = (1..n).map { abs(ln(c[it] / c[it - 1])) * 1e4 }
        val rv = rets.average()
        val range = (c.max() - c.min()) / c.last() * 1e4
        val rvZ = z(rv / normal)
        val rangeZ = z(range / (2 * sqrt(n.toDouble()) * normal))
        val state = when {
            rvZ >= BUSY_Z || rangeZ >= BUSY_Z -> State.BUSY
            rvZ <= QUIET_Z && rangeZ <= QUIET_Z -> State.QUIET
            else -> State.NORMAL
        }
        val path = (1..n).sumOf { abs(c[it] - c[it - 1]) }
        val eff = if (path > 0) abs(c.last() - c.first()) / path else 0.0
        val trend = when {
            eff >= 0.5 -> if (c.last() > c.first()) Trend.UP else Trend.DOWN
            eff <= 0.25 -> Trend.RANGE
            else -> Trend.MIXED
        }
        return Regime(name, state, rangeZ, rvZ, trend, eff)
    }

    private fun z(ratio: Double): Double = if (ratio > 0) ln(ratio) / SIGMA_LN else Double.NEGATIVE_INFINITY

    // ---- the scheduled events ------------------------------------------------------------------------------------------

    enum class Kind { NEWS, EXPIRY_HOUR }

    /** A window: [name], from [startMs] to [endMs]; [scope]: the underlyings it concerns (empty: all). */
    data class Window(val name: String, val startMs: Long, val endMs: Long, val kind: Kind = Kind.NEWS, val scope: Set<String> = emptySet()) {
        fun active(nowMs: Long): Boolean = nowMs in startMs..endMs
        fun concerns(underlying: String?): Boolean = scope.isEmpty() || underlying == null ||
            scope.any { underlying.uppercase(Locale.ENGLISH).startsWith(it) }
    }

    /** The commodities EIA's reports concern (MCX). */
    val EIA_SCOPE = setOf("CRUDEOIL", "NATURALGAS", "NATGAS")

    private fun at(day: LocalDate, h: Int, m: Int, zone: ZoneId): Long = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun padded(name: String, atMs: Long, scope: Set<String> = emptySet()): Window =
        Window(name, atMs - PAD_MIN * 60_000L, atMs + PAD_MIN * 60_000L, Kind.NEWS, scope)

    /**
     * [day]'s windows (IST day) from the calendar's event [names] on it ([Events]), the weekly EIA reports when [mcx], and the
     * expiry hours: [expiries] the indices expiring that day, [foCloseMin] F&O's close (minute of the day; 15:40 from 3 Aug
     * 2026). FOMC's line is on the Indian session after the decision (the calendar's convention): its window is the decision
     * itself, 14:00 New York time the day before (IST night or just after midnight). Owner events with no known time give none.
     */
    fun windows(day: LocalDate, names: List<String>, expiries: List<String> = emptyList(), foCloseMin: Int = 15 * 60 + 40, mcx: Boolean = true): List<Window> {
        val out = ArrayList<Window>()
        for (raw in names) {
            val n = " " + raw.uppercase(Locale.ENGLISH) + " "
            when {
                "RBI" in n -> out += padded("RBI policy", at(day, 10, 0, IST))
                "BUDGET" in n -> out += padded("Union Budget", at(day, 11, 0, IST))
                "FOMC" in n || " FED " in n -> out += padded("US Fed decision", at(day.minusDays(1), 14, 0, NY))
                "CPI" in n && "INDIA" !in n -> out += padded("US CPI", at(day, 8, 30, NY))
                "PAYROLL" in n || "NFP" in n || "JOBS" in n -> out += padded("US jobs report", at(day, 8, 30, NY))
            }
        }
        if (mcx && day.dayOfWeek == DayOfWeek.WEDNESDAY) out += padded("EIA crude stocks", at(day, 10, 30, NY), EIA_SCOPE)
        if (mcx && day.dayOfWeek == DayOfWeek.THURSDAY) out += padded("EIA gas storage", at(day, 10, 30, NY), EIA_SCOPE)
        for (u in expiries.distinct()) {
            val end = day.atStartOfDay(IST).toInstant().toEpochMilli() + foCloseMin * 60_000L
            out += Window("$u expiry hour", end - 60 * 60_000L, end, Kind.EXPIRY_HOUR, setOf(u.uppercase(Locale.ENGLISH)))
        }
        return out.distinctBy { Triple(it.name, it.startMs, it.scope) }.sortedBy { it.startMs }
    }

    // ---- the picture ---------------------------------------------------------------------------------------------------

    /** The big-move alarm: [name]'s move [dir] (+1 up, -1 down) seen at [atMs]. */
    data class BigMove(val name: String, val dir: Int, val atMs: Long)

    /**
     * Everything at [atMs]: [regimes] by index, today's [windows], the trap guard's [traps] by index (TrapGuard.Trap names),
     * the [bigMove] alarm, the data's [health] and its open incidents' words.
     */
    data class Context(
        val atMs: Long = 0L,
        val regimes: Map<String, Regime> = emptyMap(),
        val windows: List<Window> = emptyList(),
        val traps: Map<String, Set<String>> = emptyMap(),
        val bigMove: BigMove? = null,
        val health: SelfHeal.Fallbacks = SelfHeal.Fallbacks(),
        val incidents: List<String> = emptyList(),
    ) {
        fun activeWindows(nowMs: Long, underlying: String? = null): List<Window> = windows.filter { it.active(nowMs) && it.concerns(underlying) }
        fun busy(underlying: String?): Boolean = regimeOf(underlying)?.state == State.BUSY
        fun quiet(underlying: String?): Boolean = regimeOf(underlying)?.state == State.QUIET
        fun anyBusy(): Boolean = regimes.values.any { it.state == State.BUSY }
        fun allQuiet(): Boolean = regimes.isNotEmpty() && regimes.values.all { it.state == State.QUIET }
        /** NIFTY's regime stands for the indices without one of their own (FINNIFTY, MIDCPNIFTY). */
        fun regimeOf(underlying: String?): Regime? = underlying?.uppercase(Locale.ENGLISH)?.let { regimes[it] } ?: regimes["NIFTY"]
        fun bigMoveFresh(nowMs: Long): Boolean = bigMove?.let { nowMs - it.atMs in 0..BIG_MOVE_MS } == true
    }

    // ---- the global entry pause ----------------------------------------------------------------------------------------

    enum class Cause(val key: String, val words: String) {
        NEWS("news", "news windows"),
        TRAPS("traps", "trap alerts"),
        FEED("feed", "an unreliable feed"),
        BIG_MOVE("bigmove", "a big move's first 60 s"),
    }

    enum class Mode { OFF, SHADOW, ON }

    /** Boss's choices: news windows ON, the others SHADOW (logged, never acted on) until the record says they help. */
    data class Policy(val modes: Map<Cause, Mode> = DEFAULTS) {
        fun mode(c: Cause): Mode = modes[c] ?: DEFAULTS.getValue(c)
    }

    val DEFAULTS: Map<Cause, Mode> = mapOf(Cause.NEWS to Mode.ON, Cause.TRAPS to Mode.SHADOW, Cause.FEED to Mode.SHADOW, Cause.BIG_MOVE to Mode.SHADOW)

    /** "news=ON;traps=SHADOW" (unknown or bad parts left out: their default). */
    fun encode(p: Policy): String = Cause.entries.joinToString(";") { "${it.key}=${p.mode(it).name}" }

    fun decode(s: String?): Policy {
        if (s.isNullOrBlank()) return Policy()
        val m = HashMap(DEFAULTS)
        for (part in s.split(';')) {
            val c = Cause.entries.firstOrNull { it.key == part.substringBefore('=').trim() } ?: continue
            m[c] = runCatching { Mode.valueOf(part.substringAfter('=').trim()) }.getOrNull() ?: continue
        }
        return Policy(m)
    }

    /** The trap flags that count as an alert (pulls, a stop hunt or a failed break). */
    val TRAP_ALERTS = setOf("PULL_BID", "PULL_ASK", "STOP_HUNT", "FAILED_BREAK")

    /** Which pause causes apply to a new entry on [underlying] now, each with its detail ("RBI policy 09:55-10:05"). */
    fun causes(ctx: Context, underlying: String?, nowMs: Long): List<Pair<Cause, String>> {
        val out = ArrayList<Pair<Cause, String>>()
        ctx.activeWindows(nowMs, underlying).firstOrNull { it.kind == Kind.NEWS }?.let { out += Cause.NEWS to "${it.name} window" }
        val u = underlying?.uppercase(Locale.ENGLISH)
        val flags = (u?.let { ctx.traps[it] }).orEmpty()
        flags.firstOrNull { it in TRAP_ALERTS }?.let { out += Cause.TRAPS to "trap alert: ${it.lowercase(Locale.ENGLISH).replace('_', ' ')}" }
        if ("UNRELIABLE" in flags) out += Cause.FEED to "the order-flow feed is unreliable"
        if (ctx.bigMoveFresh(nowMs)) ctx.bigMove?.let { out += Cause.BIG_MOVE to "big move ${if (it.dir > 0) "up" else "down"} on ${it.name} in its first 60 s" }
        return out
    }

    /** The pause's answer: [block] (a cause set ON applies: the entry is skipped, [why]); [shadow]: what a SHADOW cause would have done. */
    data class Verdict(val block: Boolean, val why: String?, val shadow: List<String> = emptyList(), val shadowCauses: List<Cause> = emptyList())

    /**
     * May a new entry on [underlying] go now? Each applying cause by Boss's [policy]: ON blocks (paper and live alike),
     * SHADOW is noted for the log, OFF is ignored. Never asked by an exit.
     */
    fun decide(ctx: Context, policy: Policy, underlying: String?, nowMs: Long): Verdict {
        val hits = causes(ctx, underlying, nowMs)
        val on = hits.filter { policy.mode(it.first) == Mode.ON }
        val sh = hits.filter { policy.mode(it.first) == Mode.SHADOW }
        return Verdict(on.isNotEmpty(), on.firstOrNull()?.let { "paused: ${it.second}" }, sh.map { it.second }, sh.map { it.first })
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun z1(x: Double?) = if (x == null || x.isInfinite() || x.isNaN()) "—" else String.format(Locale.ENGLISH, "%+.1f", x)

    fun regimeLine(r: Regime): String = "${r.name}: ${r.state.words}" +
        (if (r.state != State.UNKNOWN) " (range z ${z1(r.rangeZ)}, realised vol z ${z1(r.rvZ)}; ${r.trend.words}, shown only)" else "")

    /** The diagnostics' "Brain" lines (the caller adds the incidents, the findings and the workers' counts). */
    fun lines(ctx: Context, nowMs: Long, policy: Policy, hhmm: (Long) -> String): List<String> {
        val out = ArrayList<String>()
        out += "Regime: " + if (ctx.regimes.isEmpty()) "not known yet" else ctx.regimes.values.joinToString(" · ") { regimeLine(it) }
        val act = ctx.activeWindows(nowMs)
        out += "Windows now: " + if (act.isEmpty()) "none" else act.joinToString(", ") { "${it.name} ${hhmm(it.startMs)}-${hhmm(it.endMs)}" }
        val next = ctx.windows.filter { it.startMs > nowMs }.take(4)
        if (next.isNotEmpty()) out += "Windows later today: " + next.joinToString(", ") { "${it.name} ${hhmm(it.startMs)}-${hhmm(it.endMs)}" }
        out += "Trap flags: " + ctx.traps.filterValues { it.isNotEmpty() }.entries.joinToString("; ") { "${it.key} ${it.value.joinToString(",")}" }.ifEmpty { "none" }
        out += "Big move: " + (ctx.bigMove?.let { "${it.name} ${if (it.dir > 0) "up" else "down"} at ${hhmm(it.atMs)}" + if (ctx.bigMoveFresh(nowMs)) " (first 60 s)" else "" } ?: "none today")
        out += "Data health: " + ctx.health.words().ifEmpty { listOf("all fine") }.joinToString("; ")
        out += "Pause new entries during: " + Cause.entries.joinToString(", ") { "${it.words} ${policy.mode(it).name.lowercase(Locale.ENGLISH)}" }
        return out
    }
}
