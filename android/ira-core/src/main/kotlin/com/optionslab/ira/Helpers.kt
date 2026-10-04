package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The market's mood over the last weeks (the owner's wish, 2026-10-02): trending up, trending down, sideways or
 * volatile, from the daily bars; and how each arm did on days of each kind, so the owner sees which arms suit the
 * market now. A day's regime is read from the days before it only (no peeking). Pure.
 */
object Regime {
    enum class Kind(val label: String) { UP("trending up"), DOWN("trending down"), SIDEWAYS("sideways"), VOLATILE("volatile") }

    const val LOOK = 20
    const val BASE = 60

    /** The regime at the end of [days] (oldest first), or null with too few days. */
    fun of(days: List<Study.Day>): Kind? {
        if (days.size < LOOK + 1) return null
        val last = days.takeLast(LOOK + 1)
        fun range(d: Study.Day) = (d.high - d.low) / d.open * 100
        val recent = days.takeLast(10).map(::range).average()
        val base = days.takeLast(BASE).map(::range).average()
        if (days.size >= 30 && recent >= base * 1.4) return Kind.VOLATILE
        val first = last.first().close; val end = last.last().close
        val move = (end - first) / first * 100
        val path = last.zipWithNext { a, b -> kotlin.math.abs(b.close - a.close) }.sum()
        val efficiency = if (path > 0) kotlin.math.abs(end - first) / path else 0.0
        return when {
            efficiency >= 0.3 && move >= 2.0 -> Kind.UP
            efficiency >= 0.3 && move <= -2.0 -> Kind.DOWN
            else -> Kind.SIDEWAYS
        }
    }

    /** Each day's regime as it stood that morning (from the days before it). */
    fun history(days: List<Study.Day>): Map<LocalDate, Kind> {
        val out = HashMap<LocalDate, Kind>()
        for (i in (LOOK + 1) until days.size) of(days.subList(0, i))?.let { out[days[i].date] = it }
        return out
    }

    fun say(market: Market, k: Kind, days: List<Study.Day>): String {
        val last = days.takeLast(LOOK + 1)
        val move = (last.last().close - last.first().close) / last.first().close * 100
        return "${market.label} is ${k.label}: %+.1f%% over the last $LOOK sessions.".format(Locale.ENGLISH, move)
    }

    /** How each arm did by regime: "ORB 5: trending up +Rs 4,200 (12), sideways -Rs 1,100 (9)". Arms with no trade are left out. */
    fun armsBy(trades: List<ArmHealth.T>, regimes: Map<LocalDate, Kind>): List<String> =
        trades.filter { it.day in regimes }.groupBy { it.arm }.toSortedMap().map { (arm, ts) ->
            val parts = Kind.entries.mapNotNull { k ->
                val on = ts.filter { regimes[it.day] == k }
                if (on.isEmpty()) null else "${k.label} ${AppFacts.rs(on.sumOf { it.net })} (${on.size})"
            }
            "$arm: ${parts.joinToString(", ")}."
        }

    /** The arms that made money on days like today's ([now]), best first; empty when none did. */
    fun suits(trades: List<ArmHealth.T>, regimes: Map<LocalDate, Kind>, now: Kind): List<String> =
        trades.filter { regimes[it.day] == now }.groupBy { it.arm }
            .filter { (_, ts) -> ts.size >= 5 && ts.sumOf { it.net } > 0 }
            .entries.sortedByDescending { e -> e.value.sumOf { it.net } }.map { it.key }
}

/**
 * Jarvis plans the day (Boss, 4 Oct: "plan my day"): each morning the PAPER arms are fitted to the market's regime from
 * their own record on days of that regime. An armed arm that lost on such days (over [MIN_TRADES] trades or more) is
 * parked; an arm Jarvis parked is armed again once the regime no longer says it loses. It never arms an arm Boss did
 * not arm, never touches an arm trading Zerodha, and leaves alone an arm Boss re-armed after it was parked (until the
 * regime changes). Pure.
 */
object DayPlan {
    const val MIN_TRADES = 5

    /** Each arm's (net, trades) by regime. */
    fun record(trades: List<ArmHealth.T>, regimes: Map<LocalDate, Regime.Kind>): Map<String, Map<Regime.Kind, Pair<Double, Int>>> =
        trades.filter { it.day in regimes }.groupBy { it.arm }.mapValues { (_, ts) ->
            ts.groupBy { regimes.getValue(it.day) }.mapValues { (_, l) -> l.sumOf { it.net } to l.size } }

    /**
     * [paper]: the arm would trade the paper account (only those are touched). [parked]: Jarvis switched it off.
     * [kept]: Boss re-armed it after Jarvis parked it, in the regime named (left alone while that regime holds).
     */
    data class ArmNow(val source: String, val label: String, val armed: Boolean, val paper: Boolean, val parked: Boolean,
                      val kept: Regime.Kind? = null)
    data class Step(val source: String, val label: String, val on: Boolean, val why: String)

    fun plan(arms: List<ArmNow>, record: Map<String, Map<Regime.Kind, Pair<Double, Int>>>, now: Regime.Kind): List<Step> = arms.mapNotNull { a ->
        if (!a.paper) return@mapNotNull null
        val r = record[a.source]?.get(now)
        val loses = r != null && r.second >= MIN_TRADES && r.first < 0
        fun rec() = r?.let { "${AppFacts.rs(it.first)} over ${it.second} trade${if (it.second > 1) "s" else ""}" } ?: "no record"
        when {
            a.armed && loses && !a.parked && a.kept != now -> Step(a.source, a.label, false, "on ${now.label} days it made ${rec()}")
            !a.armed && a.parked && !loses -> Step(a.source, a.label, true,
                if (r == null || r.second < MIN_TRADES) "the market is ${now.label} now, where it has too few trades to judge" else "on ${now.label} days it made ${rec()}")
            else -> null
        }
    }

    fun say(steps: List<Step>, now: Regime.Kind): String? {
        if (steps.isEmpty()) return null
        val off = steps.filter { !it.on }; val on = steps.filter { it.on }
        val parts = ArrayList<String>()
        if (off.isNotEmpty()) parts += "I parked " + off.joinToString("; ") { "${it.label} (${it.why})" }
        if (on.isNotEmpty()) parts += "I armed again " + on.joinToString("; ") { "${it.label} (${it.why})" }
        return "Today's plan: BankNifty is ${now.label}. " + parts.joinToString(". ") + ". Paper only; switch any of them back yourself whenever you like."
    }
}

/**
 * "Am I ready to go live?" (the owner's wish, 2026-10-02): each thing that should be in place before real money, with a
 * tick or what is missing. Pure: the app gathers the facts.
 */
object LiveReady {
    data class Facts(
        /** Null when the paper record has earned live, else why not. */ val proven: String?,
        val zerodhaLoggedIn: Boolean, val killSwitchOn: Boolean, val dailyLossLimit: Double?, val maxLots: Int?,
        val voiceEnrolled: Boolean, val fingerprint: Boolean, val marketOpenToday: Boolean, val unguardedPositions: Int,
    )

    fun check(f: Facts): List<String> {
        val items = listOf(
            (f.proven == null) to (f.proven ?: "Paper record: proven."),
            f.zerodhaLoggedIn to if (f.zerodhaLoggedIn) "Zerodha: logged in." else "Zerodha: not logged in today (log in from Settings).",
            !f.killSwitchOn to if (f.killSwitchOn) "The kill switch is on: nothing can trade." else "Kill switch: off.",
            (f.dailyLossLimit != null && f.dailyLossLimit > 0) to (f.dailyLossLimit?.takeIf { it > 0 }?.let { "Daily loss limit: ${AppFacts.amt(it)}." } ?: "No daily loss limit is set (set one in Risk limits)."),
            (f.maxLots != null && f.maxLots > 0) to (f.maxLots?.takeIf { it > 0 }?.let { "Max lots per order: $it." } ?: "No max lots per order is set."),
            f.voiceEnrolled to if (f.voiceEnrolled) "Your voice is enrolled: only you can trade by voice." else "Your voice is not enrolled (Settings, Voice): anyone could speak to me.",
            f.fingerprint to if (f.fingerprint) "Fingerprint: set, so live approvals ask for it." else "No fingerprint on this phone: live approvals fall back to your PIN.",
            (f.unguardedPositions == 0) to if (f.unguardedPositions == 0) "Every open position has a stop." else "${f.unguardedPositions} open position${if (f.unguardedPositions > 1) "s have" else " has"} no stop.",
        )
        val missing = items.count { !it.first }
        val head = if (missing == 0) "Yes Boss, everything is in place for live trading." +
            (if (!f.marketOpenToday) " The market is closed today, though." else "")
            else "Not yet: $missing thing${if (missing > 1) "s" else ""} to fix."
        return listOf(head) + items.filter { !it.first }.map { it.second } + items.filter { it.first }.map { "OK: " + it.second }
    }
}

/**
 * Why a trade lost, in words (the owner's wish, 2026-10-02): from the index and the option at entry and exit - the
 * index went the wrong way; it went the right way but the option still fell (volatility fell); out at the 15:15 time
 * exit with little move (time decay); or a sudden jump through the stop. Pure.
 */
object LossReason {
    data class Trip(val call: Boolean, val indexIn: Double, val indexOut: Double, val optionIn: Double, val optionOut: Double,
                    val minutes: Int, val exitMinute: Int, val stopped: Boolean)

    fun why(t: Trip): String? {
        val opt = t.optionOut - t.optionIn
        if (opt >= 0) return null
        val idx = t.indexOut - t.indexIn
        val withUs = if (t.call) idx > 0 else idx < 0
        val idxPct = kotlin.math.abs(idx) / t.indexIn * 100
        val pts = "%.0f".format(Locale.ENGLISH, kotlin.math.abs(idx))
        return when {
            t.stopped && t.minutes <= 3 -> "The stop was hit within ${t.minutes} minute${if (t.minutes == 1) "" else "s"}: a sudden jump against the trade (the index moved $pts points)."
            !withUs && idxPct >= 0.1 -> "The index went the wrong way ($pts points ${if (idx < 0) "down" else "up"}) and the option fell with it" +
                if (t.stopped) " to the stop." else "."
            withUs && idxPct >= 0.1 -> "The index went the right way ($pts points) but the option still fell: its implied volatility dropped faster than the move paid."
            t.exitMinute >= JarvisTrades.EXIT_MINUTE -> "Out at 15:15 with almost no move: the option lost its time value through the day."
            else -> "The index barely moved ($pts points) and the option drifted down: time decay and the spread."
        }
    }
}

/**
 * Is the strike easy to trade? (the owner's wish, 2026-10-02): a wide gap between the best buyer and seller, or too
 * little traded today, means a poor fill and a hard exit. Unknown prices (0) pass. Pure.
 */
object StrikeLiquidity {
    const val MAX_SPREAD_SHARE = 0.05
    const val MIN_SPREAD_POINTS = 1.0
    const val MIN_VOLUME_LOTS = 50

    /** A reason not to buy, or null when it looks tradable. */
    fun problem(bid: Double, ask: Double, volume: Long, lotSize: Int): String? {
        if (bid > 0 && ask > 0 && ask >= bid) {
            val spread = ask - bid; val mid = (ask + bid) / 2
            if (spread > MIN_SPREAD_POINTS && spread / mid > MAX_SPREAD_SHARE)
                return "The strike is thin: buyers at %.2f, sellers at %.2f (a %.0f%% gap), so the fill and the exit would be poor.".format(Locale.ENGLISH, bid, ask, spread / mid * 100)
        }
        if (volume > 0 && lotSize > 0 && volume < MIN_VOLUME_LOTS.toLong() * lotSize)
            return "The strike has hardly traded today (${volume / lotSize} lots), so the fill and the exit would be poor."
        return null
    }
}

/** What Jarvis did, for "what did you do today" (the owner's wish, 2026-10-02). Pure. */
object Activity {
    data class Entry(val at: LocalDateTime, val what: String)
    const val KEEP = 300

    fun lines(day: LocalDate, all: List<Entry>): List<String> {
        val today = all.filter { it.at.toLocalDate() == day }
        if (today.isEmpty()) return listOf("I have not done anything yet today.")
        return listOf("Today I did ${today.size} thing${if (today.size > 1) "s" else ""}:") +
            today.map { "%02d:%02d %s".format(Locale.ENGLISH, it.at.hour, it.at.minute, it.what) }
    }
}

/** The morning self-check (the owner's wish, 2026-10-02): each part Jarvis needs, working or not. Pure. */
object SelfCheck {
    /** [parts]: name to true (working), false (not) or null (not used). */
    fun lines(parts: List<Pair<String, Boolean?>>): List<String> {
        val bad = parts.filter { it.second == false }.map { it.first }
        val head = if (bad.isEmpty()) "Self-check: all working." else "Self-check: ${bad.joinToString(", ")} ${if (bad.size > 1) "need" else "needs"} a look."
        return listOf(head) + parts.filter { it.second != null }.map { "${it.first}: ${if (it.second == true) "OK" else "not working"}." }
    }
}

/** Battery saver (the owner's wish, 2026-10-02): refresh less often when the battery is low and not charging. Pure. */
object BatterySaver {
    const val LOW = 20
    const val VERY_LOW = 10

    /** The refresh gap to use instead of [normalMs]. */
    fun gap(normalMs: Long, percent: Int?, charging: Boolean): Long = when {
        charging || percent == null -> normalMs
        percent <= VERY_LOW -> normalMs * 4
        percent <= LOW -> normalMs * 2
        else -> normalMs
    }

    fun saving(percent: Int?, charging: Boolean) = !charging && percent != null && percent <= LOW
}

/**
 * Replies in Hindi (the owner's wish, 2026-10-02): the on-device model turns Jarvis's English reply into simple Hindi;
 * a translation that drops or changes any number is thrown away and the English is used, so a figure is never wrong.
 * Pure.
 */
object Hindi {
    fun prompt(english: String): String =
        "Translate this into simple spoken Hindi in Devanagari script. Keep every number, name and symbol exactly as written. " +
            "Reply with the translation only.\n\n$english"

    /** Every figure with its sign, also when "Rs" or "₹" sits between them ("+Rs 4,200" is +4200). */
    private fun numbers(s: String) = Regex("([+-]?)\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:[.,]\\d+)*)").findAll(s)
        .map { it.groupValues[1] + it.groupValues[2].replace(",", "") }.toList().sorted()

    /** The translation when it is Hindi and keeps every number, else null. */
    fun accept(english: String, hindi: String?): String? {
        val h = hindi?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!Regex("[\\u0900-\\u097F]").containsMatchIn(h)) return null
        if (numbers(english) != numbers(h)) return null
        return h
    }
}
