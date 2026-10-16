package com.optionslab.engine.trend

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.HeroTicketRules
import com.optionslab.engine.trend.TrendTurn.Rule
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/**
 * "Trend watch" (16 Oct 2026, Boss: "check the current trend, and if the trend changes, sell the orders" ... "It should work
 * for all the arms and orders"): what research R63 found (research/HUNT_R63_TREND_TURN_EXIT.md) and what the app may do with
 * it, for every open position and pending order of every source, paper and live.
 *
 * R63 replayed six strategies with four trend-turn rules ([TrendTurn]) on the index, 20 Nov 2024 onwards, against random
 * early exits of the same trades. ONE cell passed every gate: T3 on the Hero ₹1,000 ticket (final test 107 tickets, 74
 * sold early, +Rs 14,730 after costs, twin p 0.002, BH q 0.056). Nothing passed for any other strategy, nor pooled.
 *
 *  - LIVE never acts: a note (and a quiet alert) only, and only with [Switches.live] on - OFF by default.
 *  - PAPER acts only where the rule PASSED ([PASSED]): the Hero ticket's legs, with T3, for the setting R63 tested
 *    ([heroTested]), behind its own switch (on: the PAPER rule lets a passed idea act at once). Non-replayed sources (manual,
 *    Jarvis, Telegram, MCX, restored arms) could act only if the POOLED rule had passed ([POOLED_PASSED], null) and Boss
 *    switched that source on: so today they never act.
 *  - Everything else: a shadow note with the pooled design-best rule ([SHADOW_RULE], T2, chosen on design only).
 *  - A pending entry order whose side the trend has turned against: a "would cancel" note only, never a cancel.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object TrendWatch {
    const val LABEL = "Trend watch"
    const val RESEARCH = "R63"

    /** Who opened a position, as far as the Trend watch is concerned. [replayed]: R63 replayed its exact rules. */
    enum class Source(val key: String, val label: String, val replayed: Boolean) {
        HERO_TICKET("hero_ticket", HeroTicketRules.LABEL, true),
        LIQUIDITY("liquidity", "Liquidity 15+5", true),
        DONCHIAN("r51_donchian20", "R51 Donchian 20", true),
        OOV("r52_oov", "R52 Open outside value", true),
        SOLO("solo", "Solo (midday)", true),
        SOLO2("solo2", "Solo 2.0", true),
        MCX("mcx", "MCX paper arms", false),
        MANUAL("manual", "Your own orders", false),
        JARVIS("jarvis", "Jarvis's orders for you", false),
        TELEGRAM("telegram", "Signal tester (paper)", false),
        OTHER_ARM("other", "Other arms (restored / Pine / baskets)", false),
        /** Protections, square-offs and sweeps: they are exits already, never watched. */
        EXIT_RULE("exit_rule", "Protections and square-offs", false);

        companion object {
            fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
            /** The sources a per-source switch is shown for (everything that can hold a position). */
            val SWITCHABLE: List<Source> get() = entries.filter { it != EXIT_RULE }
        }
    }

    /** The rule that PASSED for a replayed strategy (R63 final.json "paper_rule"): only the Hero ticket's. */
    val PASSED: Map<Source, Rule> = mapOf(Source.HERO_TICKET to Rule.T3)
    /** The pooled ("all strategies") rule, had one passed: none did. Non-replayed sources could only ever act with it. */
    val POOLED_PASSED: Rule? = null
    /** The pooled design-best rule (R63 "shadow_rule"), used for every note where nothing passed. */
    val SHADOW_RULE = Rule.T2

    const val RECORD = "Research R63 (final test 1 Apr - 14 Oct 2026, read once): T3 on the Hero ticket passed - 107 tickets, 74 sold " +
        "early, +₹14,730 after costs, twin p 0.002, q 0.056; nothing passed for Liquidity, R51, R52, Solo, Solo 2.0 or pooled"

    /** The source of an order from its raw label ("Hero ₹1,000 · entry", "Manual · Jarvis", "Jarvis solo · entry", null). */
    fun sourceOf(label: String?): Source {
        val l = label?.trim().orEmpty()
        return when {
            l.isEmpty() || l == "Manual" || l == "Outside IraAlgo (Kite)" -> Source.MANUAL
            l.startsWith("Manual · Jarvis") -> Source.JARVIS
            l.startsWith("Manual") -> Source.MANUAL
            l.startsWith(HeroTicketRules.OWNER) -> Source.HERO_TICKET
            l.startsWith("Liquidity") -> Source.LIQUIDITY
            l.startsWith("R51 Donchian") -> Source.DONCHIAN
            l.startsWith("R52 Open outside value") -> Source.OOV
            l.startsWith("Solo 2.0") -> Source.SOLO2
            l.startsWith("Jarvis solo") -> Source.SOLO
            l.startsWith("Signal tester") -> Source.TELEGRAM
            MCX_LABELS.any { l.startsWith(it) } -> Source.MCX
            EXIT_LABELS.any { l == it || l.startsWith("$it ·") || l.startsWith("$it ") } -> Source.EXIT_RULE
            else -> Source.OTHER_ARM
        }
    }

    private val MCX_LABELS = listOf("NG evening breakout", "SILVERM morning call", "MCX 12-month trend", "US-night silver")
    private val EXIT_LABELS = listOf("Protection", "Expiry square-off", "Auto square-off 15:15", "GTT", "Missed-lock sweep")

    /** Which way a holding leans on its underlying: +1 gains when it rises (a bought call, a long future, a sold put), -1 when it falls. */
    fun bias(right: String?, qty: Int): Int {
        val long = qty > 0
        return when (right?.uppercase(Locale.ENGLISH)) {
            "CE" -> if (long) 1 else -1
            "PE" -> if (long) -1 else 1
            else -> if (long) 1 else -1
        }
    }

    /** The switches (kept in the watch's own file). Live OFF by default; each non-replayed source's act switch OFF. */
    data class Switches(
        val on: Boolean = true,
        /** Notes for Zerodha positions too (never an order): OFF by default. */
        val live: Boolean = false,
        /** A quiet notification on a turn (the note is always written): OFF by default. */
        val alerts: Boolean = false,
        /** Per source: may it SELL on paper? Only meaningful where [canAct]; the Hero ticket's is on, the rest off. */
        val act: Map<Source, Boolean> = mapOf(Source.HERO_TICKET to true),
    ) {
        fun acts(s: Source): Boolean = act[s] ?: false
    }

    /** Whether [s] may ever be switched to act on paper: its own rule passed, or (not replayed) the pooled one did. */
    fun canAct(s: Source): Boolean = PASSED[s] != null || (!s.replayed && s != Source.EXIT_RULE && POOLED_PASSED != null)

    /** The rule that judges a [s] position: its passed rule, else the pooled one for a non-replayed source, else [SHADOW_RULE]. */
    fun ruleFor(s: Source): Rule = PASSED[s] ?: (if (!s.replayed) POOLED_PASSED else null) ?: SHADOW_RULE

    /** The Hero ticket's setting R63 tested: the 25% trail after 2x, no 2x / 3x target. Any other: notes only. */
    fun heroTested(trailStart: String?, target: String?): Boolean =
        HeroTicketRules.TrailStart.of(trailStart) == HeroTicketRules.TrailStart.AFTER_2X && HeroTicketRules.Target.of(target) == HeroTicketRules.Target.TRAIL_ONLY

    /** One open holding, as the watch sees it. [fill]: when it was bought (or the session's open for one carried overnight). */
    data class Held(val source: Source, val symbol: String, val underlying: String, val bias: Int, val fill: LocalDateTime,
                    val live: Boolean, val premium: Double?, val mcx: Boolean = false, val tested: Boolean = true) {
        val key: String get() = "${if (live) "live" else "paper"}|$symbol|$fill"
    }

    /**
     * A sale is made only this soon after the turn's act minute (R63 sold at that minute): a turn first seen later (the
     * background round delayed, a restart, the exit switched back on) is a note, never a late sale at a later price.
     */
    const val FRESH_MINUTES = 3L

    /** Whether the turn [v] is still fresh at [now] ([FRESH_MINUTES]). */
    fun fresh(v: Verdict, now: LocalDateTime): Boolean = v.at != null && !now.isAfter(v.at.plusMinutes(FRESH_MINUTES).plusSeconds(59))

    /** What to do about a turn. */
    enum class Act { NONE, NOTE, SELL }

    /** What the watch does when [h] has turned: SELL only on paper where the rule passed (and its switch is on); else a note. */
    fun act(h: Held, sw: Switches): Act = when {
        !sw.on || h.source == Source.EXIT_RULE -> Act.NONE
        h.live -> if (sw.live) Act.NOTE else Act.NONE
        canAct(h.source) && sw.acts(h.source) && h.tested -> Act.SELL
        else -> Act.NOTE
    }

    /** Whether the underlying's minutes are too old to judge at [now]: the last finished minute more than [STALE_MINUTES] ago. */
    fun stale(minutes: List<Bar>, now: LocalDateTime): Boolean {
        val last = minutes.maxOfOrNull { it.start } ?: return true
        return Duration.between(last.plusMinutes(1), now).toMinutes() > STALE_MINUTES
    }

    const val STALE_MINUTES = 3L

    /** The verdict on one holding at [now]. [at]: when the rule turned against it (its act minute); null: not turned. */
    data class Verdict(val rule: Rule, val at: LocalDateTime?, val state: String)

    /**
     * Judge [h] from its underlying's finished 1-minute bars [minutes] (earlier sessions too, oldest first) at [now]. A turn
     * counts only once its bar has completed ([TrendTurn.exitTurn]); stale minutes judge nothing.
     */
    fun judge(h: Held, minutes: List<Bar>, now: LocalDateTime): Verdict {
        val rule = ruleFor(h.source)
        if (minutes.isEmpty()) return Verdict(rule, null, "no ${h.underlying} minutes yet")
        if (stale(minutes, now)) return Verdict(rule, null, "${h.underlying}'s prices are stale (last ${minutes.maxOf { it.start }.toLocalTime()})")
        val grid = if (h.mcx) TrendTurn.Grid.MCX else TrendTurn.Grid.NSE
        val turns = TrendTurn.turns(rule, minutes, h.bias > 0, grid).filter { !it.act.isAfter(now) }
        val t = TrendTurn.exitTurn(rule, turns, h.fill)
        val bars = TrendTurn.bars(minutes, rule.minutes, grid).size
        return when {
            t != null -> Verdict(rule, t.act, "turned at ${hhmm(t.act)}")
            bars < TrendTurn.barsNeeded(rule) -> Verdict(rule, null, "warming up ($bars of ${TrendTurn.barsNeeded(rule)} bars)")
            else -> Verdict(rule, null, "trend holding")
        }
    }

    /** A pending entry order on [underlying] that would buy [bias]'s side: has the shadow rule turned against that side since [placed]? */
    fun pendingTurned(bias: Int, placed: LocalDateTime, minutes: List<Bar>, now: LocalDateTime, mcx: Boolean = false): LocalDateTime? {
        if (minutes.isEmpty() || stale(minutes, now)) return null
        val grid = if (mcx) TrendTurn.Grid.MCX else TrendTurn.Grid.NSE
        return TrendTurn.turns(SHADOW_RULE, minutes, bias > 0, grid).lastOrNull { it.act.isAfter(placed) && !it.act.isAfter(now) }?.act
    }

    fun hhmm(t: LocalDateTime): String = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    private fun px(x: Double?): String = x?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "the market"

    /** The log line: "trend turned against R51 Donchian 20 NIFTY..CE at 11:05: would sell at 84.20 (T2, shadow)". */
    fun line(h: Held, v: Verdict, act: Act, late: Boolean = false): String {
        val at = v.at?.let { hhmm(it) } ?: "?"
        val who = "${h.source.label} ${h.symbol}"
        val tail = when {
            act == Act.SELL -> "selling on paper at about ${px(h.premium)} (${v.rule.key}, passed research $RESEARCH)"
            late -> "would sell at ${px(h.premium)} (${v.rule.key}: seen too late to sell, note only)"
            h.live -> "would sell at ${px(h.premium)} (${v.rule.key}, live: alert only, never an order)"
            !h.tested -> "would sell at ${px(h.premium)} (${v.rule.key}, a setting research did not test: note only)"
            else -> "would sell at ${px(h.premium)} (${v.rule.key}, shadow: failed research)"
        }
        val apart = if (h.underlying == "BANKNIFTY") " [BANKNIFTY: kept apart for 26 Oct]" else ""
        return "trend turned against $who at $at: $tail$apart"
    }

    /** The note for a pending entry order: never a cancel. */
    fun pendingLine(source: Source, symbol: String, at: LocalDateTime): String =
        "trend turned against ${source.label}'s pending order $symbol at ${hhmm(at)}: would cancel (${SHADOW_RULE.key}, shadow note only; nothing cancelled)"

    /** The row's one status line. */
    fun status(sw: Switches, watched: Int, turned: Int, sold: Int): String = when {
        !sw.on -> "Off: no position is watched"
        watched == 0 -> "On · nothing open to watch" + if (sw.live) " (paper and live)" else " (paper; live off)"
        else -> "On · $watched watched, $turned turned today" + (if (sold > 0) ", $sold Hero leg${if (sold == 1) "" else "s"} sold on paper" else "") +
            if (sw.live) "" else " · live off"
    }
}
