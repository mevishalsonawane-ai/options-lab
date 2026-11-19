package com.optionslab.ira

import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.LiquidityRules
import java.time.LocalDate

/**
 * 19 Nov 2026 (Boss: "Also remove the stop trades after the max loss for all r51,r72 and liquidity strategy, i want it to run until i
 * stop them"; asked, he chose all four of these, each as a switch turned off by PIN; then "Go"; and a fifth): these automatic stops no longer act on
 * R51 Donchian 20, R72 Breadth Donchian and Liquidity 15+5 (every book) while their switch is off:
 *
 *  - [Rule.FORWARD_BAR]: R51 and R72 switching themselves off after 40 paper trades (net below ₹0 or below twice the spread);
 *  - [Rule.LIVE_STOP]: Liquidity's live stop rule ([LiquidityStopRule]: -₹40,000 from its best or -₹13,000 a month on its own live trades);
 *  - [Rule.SELF_REVIEW]: Liquidity's self-review park to paper and pause (and Autopilot's own park or pause of it);
 *  - [Rule.PAPER_ACCOUNT]: the paper account stop ("no new paper entries until you restart it with your PIN"), for these arms only;
 *  - [Rule.JARVIS_CUTOFF] (Boss, 19 Nov: "Yes, Jarvis cutoff should leave them alone too"): Jarvis's losing-arm cutoff ([ArmCutoff]),
 *    which asks to switch a losing arm off and does it by itself when Boss chose automatic stops in chat, leaves them alone.
 *
 * Each is a switch, ON by default (today's behaviour, the side that lowers risk); Boss's choice switched them all OFF once
 * ([migrate]). OFF takes his PIN and never comes from Jarvis or a voice command ([set]); ON is one tap. Every other arm keeps them all
 * ([acts]); the kill switch, the day lock, the guard and daily loss limit, Stop for today, every live gate and every exit stay.
 *
 * Nothing is switched back on by this: an arm or book an earlier stop switched off, parked or paused stays so until Boss switches it on
 * himself. The two stops that are worked out afresh each time (the live stop rule and the paper account stop) keep a stop already
 * reached before the switch went off ([liveStatus], [paperAccountActs]) until Boss re-enables it with his PIN, as before. Pure.
 */
object ArmAutoStops {
    /** The stops; [switch] is the Bot settings row's title, [target] the change timeline's (no word its safety filter refuses). */
    enum class Rule(val key: String, val target: String, val switch: String, val words: String) {
        FORWARD_BAR("fwd", "autostop:forward_bar", "Forward-test switch-off applies to R51, R72 and Liquidity",
            "the forward-test switch-off (R51 and R72 switch themselves off after 40 paper trades if the net is below ₹0 or below twice the spread)"),
        LIVE_STOP("livestop", "autostop:liq_live_stop", "Liquidity live stop rule applies to R51, R72 and Liquidity",
            "Liquidity's live stop rule (no new live entry once its own live trades are -₹40,000 from their best or -₹13,000 in a month)"),
        SELF_REVIEW("review", "autostop:liq_self_review", "Self-review park and pause applies to R51, R72 and Liquidity",
            "Liquidity's self-review park to paper and pause (and Autopilot's own park or pause of it)"),
        PAPER_ACCOUNT("acct", "autostop:paper_account_stop", "Paper account stop applies to R51, R72 and Liquidity",
            "the paper account stop (no new paper entry once the paper account is -₹40,000 from its best or -₹13,000 in a month)"),
        JARVIS_CUTOFF("cutoff", "autostop:jarvis_cutoff", "Jarvis's losing-arm cutoff applies to R51, R72 and Liquidity",
            "Jarvis's losing-arm cutoff (he asks to switch off an arm whose paper record is negative, and does it by himself when you chose automatic stops in chat; of the three it only ever judged Liquidity - R51 and R72 never were)");

        /** The switch's key in the settings vault, and when it went off (epoch ms). */
        val prefKey: String get() = "g.armAutoStops.$key"
        val offAtKey: String get() = "g.armAutoStops.$key.offAt"

        companion object { fun ofTarget(t: String): Rule? = entries.firstOrNull { it.target == t } }
    }

    /** The arms these switches cover: R51, R72, Liquidity 15+5's switch and every one of its books (their sources). */
    val SOURCES: Set<String> = setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }
    /** Liquidity 15+5's key in the self-review and Autopilot's park / pause ([ForwardCheck.LIQUIDITY]). */
    const val REVIEW_KEY = "liquidity"

    /**
     * Whether [who] (an arm's source, or the self-review's key) is one of the three arms. Review (19 Nov): by source only, never a display
     * name, so a strategy of Boss's named like one of the arms is never taken for it.
     */
    fun covers(who: String): Boolean = who in SOURCES || who == REVIEW_KEY

    /** The one-time change's key in the settings vault, and its words in the change timeline. */
    const val MIGRATION_KEY = "g.armAutoStops.migrated1119"
    /**
     * Review (19 Nov): the fifth switch's own one-time step ([migrateCutoff]) - a phone whose first choice ran with four switches (an
     * earlier build of the same day) gets Jarvis's cutoff switched off too.
     */
    const val CUTOFF_MIGRATION_KEY = "g.armAutoStops.migrated1119b"
    const val CHOICE = "Boss's choice, 19 Nov: run until he stops them"
    /** What the arm rows say while any of them is off. */
    const val ROW = "auto-stop off by Boss's choice"
    /** What a voice command or Jarvis is told when it asks for one off. */
    const val VOICE_OFF = "Only you can switch an automatic stop off, with your PIN in Bot settings. Nothing changed."

    /**
     * The switches: [on] each rule (missing: on), when each went off ([offAtMs]), the paper account stop already reached when it went
     * off ([heldAccount], its key: [accountKey]), and whether Boss's one-time choice has run ([migrated]).
     */
    data class State(val on: Map<Rule, Boolean> = emptyMap(), val offAtMs: Map<Rule, Long> = emptyMap(), val heldAccount: String? = null,
                     val migrated: Boolean = false,
                     /** The fifth switch's own step ([migrateCutoff]) has run (the first choice runs it too). */
                     val cutoffMigrated: Boolean = migrated) {
        /** Both one-time steps have run. */
        val ready: Boolean get() = migrated && cutoffMigrated
        fun isOn(r: Rule): Boolean = on[r] != false
        val off: List<Rule> get() = Rule.entries.filter { !isOn(it) }
    }

    /** Whether [rule] acts on [who]: always on any other arm; on the three only while its switch is on. */
    fun acts(s: State, rule: Rule, who: String): Boolean = s.isOn(rule) || !covers(who)

    /** A switch's change: the new [state], what to say, and whether anything [changed]. */
    data class Outcome(val state: State, val said: String, val changed: Boolean)

    /**
     * Boss's switch for [rule], at [nowMs]. ON at once (it lowers risk). OFF only with [pinConfirmed], and never [byVoice] (Jarvis or a
     * voice command: on only). [heldAccount]: the paper account stop reached now, kept when [Rule.PAPER_ACCOUNT] goes off.
     */
    fun set(s: State, rule: Rule, on: Boolean, nowMs: Long, pinConfirmed: Boolean, byVoice: Boolean = false, heldAccount: String? = null): Outcome {
        if (!on && byVoice) return Outcome(s, VOICE_OFF, false)
        if (!on && !pinConfirmed) return Outcome(s, "Switching ${rule.words} off for R51, R72 and Liquidity takes your PIN. Nothing changed.", false)
        // (Review: "Nothing changed" in the words, so the change timeline's Undo of a switch already so is never marked done.)
        if (s.isOn(rule) == on) return Outcome(s, "${rule.switch}: already ${if (on) "on" else "off"}. Nothing changed.", false)
        val next = s.copy(on = s.on + (rule to on), offAtMs = if (on) s.offAtMs - rule else s.offAtMs + (rule to nowMs),
            heldAccount = if (rule != Rule.PAPER_ACCOUNT) s.heldAccount else if (on) null else heldAccount)
        return Outcome(next, if (on) "On: ${rule.words} applies to R51, R72 and Liquidity again."
            else "Off: ${rule.words} no longer stops R51, R72 or Liquidity; they run until you stop them. Every other arm keeps it.", true)
    }

    /**
     * Boss's one-time choice (19 Nov) at [nowMs]: all of them off, [heldAccount] the paper account stop reached now. Null when it has run
     * already ([State.migrated]): it never runs twice, and a switch Boss turned back on stays on.
     */
    fun migrate(s: State, nowMs: Long, heldAccount: String?): State? =
        if (s.migrated) null else State(Rule.entries.associateWith { false }, Rule.entries.associateWith { nowMs }, heldAccount, migrated = true,
            cutoffMigrated = true)

    /**
     * Review (19 Nov): the fifth switch's step at [nowMs], on a phone whose first choice ran before Jarvis's cutoff joined it: the cutoff
     * goes off too while Boss's choice still stands there - at least two switches still off since one same moment (the first choice
     * switched them together; Boss's own taps never share a millisecond). A phone where the choice never applied (new or erased: none
     * went off), or where Boss switched them back on, keeps the cutoff on. (A phone where Boss switched three of the four back on and
     * off again himself keeps it on too - the side that lowers risk; his switch puts it off.) Null when it has nothing to do (the first choice not run yet,
     * or this step done).
     */
    fun migrateCutoff(s: State, nowMs: Long): State? = when {
        !s.migrated || s.cutoffMigrated -> null
        s.isOn(Rule.JARVIS_CUTOFF) && s.off.mapNotNull { s.offAtMs[it] }.groupingBy { it }.eachCount().values.any { it >= 2 } ->
            s.copy(on = s.on + (Rule.JARVIS_CUTOFF to false), offAtMs = s.offAtMs + (Rule.JARVIS_CUTOFF to nowMs), cutoffMigrated = true)
        else -> s.copy(cutoffMigrated = true)
    }

    /**
     * Liquidity's live stop rule as its books read it: [raw] (worked out on its live [trades] since Boss's last re-enable, [resetMs], on
     * [today]) while its switch is on. Off, a stop is kept only when it was reached before the switch went off (its trades closed before
     * then reach it); one reached after is not a stop (its figures stay for the row). Boss's re-enable with his PIN clears it, as before.
     */
    fun liveStatus(s: State, raw: LiquidityStopRule.Status, trades: List<LiquidityStopRule.Trade>, today: LocalDate, resetMs: Long): LiquidityStopRule.Status {
        if (!raw.stopped || s.isOn(Rule.LIVE_STOP)) return raw
        // Review: when it went off is not known (an unreadable save): the stop is kept (never lifted on a guess).
        val off = s.offAtMs[Rule.LIVE_STOP] ?: return raw
        if (LiquidityStopRule.check(trades.filter { it.closedMs < off }, today, resetMs).stopped) return raw
        return raw.copy(stopped = false, why = null, day = null)
    }

    /** The paper account stop's key as reached: the day its count started ([since]) and the reach saved ([reached]); null: not reached. */
    fun accountKey(since: LocalDate?, reached: String?): String? = reached?.let { "$since|$it" }

    /**
     * Whether the paper account stop refuses the entry of the arm [source] (null: not one of the arms' own entries - a strategy of Boss's,
     * a Pine script), the stop reached now being [reachedNow] ([accountKey]): every other entry as before; the three while its switch is
     * on, or when it was already reached as it went off (until Boss restarts its count).
     */
    fun paperAccountActs(s: State, source: String?, reachedNow: String?): Boolean =
        source == null || acts(s, Rule.PAPER_ACCOUNT, source) || (reachedNow != null && reachedNow == s.heldAccount)

    /** Whether the self-review (or Autopilot, not on Boss's yes: [byBoss]) may park or pause [key]. */
    fun reviewMayStop(s: State, key: String, byBoss: Boolean = false): Boolean = byBoss || acts(s, Rule.SELF_REVIEW, key)

    /** The arm row's line for [source] while any switch is off; null for any other arm, or with all of them on. */
    fun rowLine(s: State, source: String): String? {
        if (!covers(source)) return null
        // Liquidity: its self-review, the paper account stop and Jarvis's cutoff, and its live stop rule on its switch's row and its
        // BANKNIFTY books only (the rule never covered FINNIFTY or MIDCPNIFTY); R51 / R72: the forward bar and the paper account stop
        // (Jarvis's cutoff only ever judged the ORB family's rows, Liquidity's among them - never R51 or R72).
        val off = s.off.filter {
            when {
                source !in LIQUIDITY_SOURCES -> it == Rule.FORWARD_BAR || it == Rule.PAPER_ACCOUNT
                it == Rule.LIVE_STOP -> source == LiquidityRules.ARM.source || source in BN_SOURCES
                else -> it != Rule.FORWARD_BAR
            }
        }
        if (off.isEmpty()) return null
        return "${ROW.replaceFirstChar { it.uppercase() }} (19 Nov): ${off.joinToString(", ") { short(it) }} no longer stop${if (off.size == 1) "s" else ""} it; " +
            "it runs until you stop it (Bot settings to switch them back on)"
    }

    private val LIQUIDITY_SOURCES: Set<String> = setOf(LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }
    /** Liquidity's BANKNIFTY books (the live stop rule's own). */
    private val BN_SOURCES: Set<String> = setOf(LiquidityRules.ARM15.source, LiquidityRules.ARM5.source)

    /** A rule's short name for the rows and the diagnostics. */
    fun short(r: Rule): String = when (r) {
        Rule.FORWARD_BAR -> "the forward-test switch-off"
        Rule.LIVE_STOP -> "the live stop rule"
        Rule.SELF_REVIEW -> "the self-review park and pause"
        Rule.PAPER_ACCOUNT -> "the paper account stop"
        Rule.JARVIS_CUTOFF -> "Jarvis's losing-arm cutoff"
    }

    /**
     * The change timeline's reason for [rule] going off by Boss's choice: what it no longer does, and [stopped] - the arms or books an
     * earlier stop of it holds now (null: could not be read), which stay so until Boss switches them on himself.
     */
    fun offReason(rule: Rule, stopped: List<String>?): String =
        "$CHOICE (\"i want it to run until i stop them\"): ${rule.words} no longer stops R51 Donchian 20, R72 Breadth Donchian or " +
            "Liquidity 15+5; every other arm keeps it, and the kill switch, the day lock, the guard, Stop for today and every live gate still " +
            "apply. Nothing was switched back on: " + when {
            stopped == null -> "what it had stopped could not be read just now - check each arm's row"
            stopped.isEmpty() -> "it had stopped none of them"
            else -> "still stopped by it until you switch them on yourself - ${stopped.joinToString("; ")}"
        } + ". Undo switches it back on (one tap)"

    /** The change timeline's reason when Boss switches [rule] back on. */
    fun onReason(rule: Rule): String = "your choice: ${rule.words} applies to R51, R72 and Liquidity again (as before 19 Nov)"

    /** The diagnostics' line. */
    fun line(s: State, loaded: Boolean): String = "Automatic stops on R51, R72 and Liquidity: " + when {
        !loaded -> "not read yet (all on)"
        s.off.isEmpty() -> "all on (as before 19 Nov)"
        else -> "off by Boss's choice (19 Nov): ${s.off.joinToString(", ") { short(it) }}" +
            (Rule.entries.filter { s.isOn(it) }.takeIf { it.isNotEmpty() }?.let { on -> "; on: ${on.joinToString(", ") { short(it) }}" } ?: "")
    } + "."

    /** The switches as the settings vault keeps them (one key each; [decode] reads them back). */
    fun encode(s: State): Map<String, Any?> = Rule.entries.flatMap { r -> listOf(r.prefKey to s.isOn(r), r.offAtKey to s.offAtMs[r]?.toString()) }.toMap() +
        mapOf(HELD_KEY to s.heldAccount, MIGRATION_KEY to s.migrated, CUTOFF_MIGRATION_KEY to s.cutoffMigrated)

    /**
     * Only [rule]'s keys as the vault keeps them (a switch's change writes its own keys, never the others' nor the one-time choice's):
     * the paper account stop's carries its held stop too.
     */
    fun encodeRule(s: State, rule: Rule): Map<String, Any?> = mapOf(rule.prefKey to s.isOn(rule), rule.offAtKey to s.offAtMs[rule]?.toString()) +
        (if (rule == Rule.PAPER_ACCOUNT) mapOf(HELD_KEY to s.heldAccount) else emptyMap())

    /** The held paper account stop's key in the settings vault. */
    const val HELD_KEY = "g.armAutoStops.acctHeld"

    /** The switches from what the vault read: [on] each rule's switch (null: not saved), [offAt] when it went off, and the rest. */
    fun decode(on: (Rule) -> Boolean?, offAt: (Rule) -> String?, held: String?, migrated: Boolean, cutoffMigrated: Boolean = migrated): State =
        State(Rule.entries.associateWith { on(it) ?: true }, Rule.entries.mapNotNull { r -> offAt(r)?.toLongOrNull()?.let { r to it } }.toMap(),
            held?.takeIf { it.isNotBlank() }, migrated, cutoffMigrated)
}
