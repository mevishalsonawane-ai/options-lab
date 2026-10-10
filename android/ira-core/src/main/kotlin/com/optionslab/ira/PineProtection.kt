package com.optionslab.ira

import java.util.Locale

/**
 * Boss's rule (06 Oct): every Pine strategy ALWAYS has a stop-loss, a target and the profit lock - compulsory. A new
 * script starts at a [STOP] of 30 and a [TARGET] of 60 premium points (1 : 2) with the lock on; Boss may change the two
 * numbers, never to 0 (none), and the lock never goes off. A saved script with no stop or no target gets these
 * defaults ([normalise]); a nonzero stop is never touched (never widened). Arming a script that places orders with
 * either missing is refused ([armRefusal]). Pure.
 */
object PineProtection {
    /** The stop-loss a script starts with, in the option's premium points. */
    const val STOP = 30.0
    /** The target a script starts with, in premium points (twice the stop). */
    const val TARGET = 60.0
    /** The sensible range for a stop typed by Boss (points). */
    const val STOP_MIN = 5.0
    const val STOP_MAX = 500.0

    /** Said when a script is switched on to trade without a stop or a target. */
    const val ARM_REFUSAL = "Set a stop-loss and a target first: every Pine strategy needs both (Boss's 06 Oct rule)."

    /** Jarvis's polite no to switching the profit lock off, or a Pine stop or target to none. */
    const val JARVIS_REFUSAL = "Sorry Boss, I can't do that: by your 06 Oct rule every Pine strategy always keeps a stop-loss, " +
        "a target and the profit lock. You can change the stop and target numbers in Research → Pine, but not switch them off."

    /** [Command.target] of the refusal ([Command.Kind.SET_REFUSED]) for a request that breaks the rule ([breaksRule]). */
    const val REFUSED_TARGET = "pine_rule"

    /** Shown beside the profit lock where its switch was. */
    const val LOCK_NOTE = "always on (Boss's rule)"

    /** A usable number of points: finite and above 0. */
    fun set(v: Double?): Boolean = v != null && v.isFinite() && v > 0

    /** [v] when it is a stop or target ([set]); else [before] when that was one; else [fallback]. Never 0. */
    fun keep(v: Double?, before: Double?, fallback: Double): Double = when {
        set(v) -> v!!
        set(before) -> before!!
        else -> fallback
    }

    /**
     * What a saved or changed script holds after the rule: a stop and a target above 0 (0 or missing: kept from the
     * previous value, else the defaults) and the lock on. [stopAdded] / [targetAdded]: the default was filled in;
     * [lockForced]: the lock was off and is turned on.
     */
    data class Exits(val stopPts: Double, val targetPts: Double, val stopAdded: Boolean = false, val targetAdded: Boolean = false,
                     val lockForced: Boolean = false) {
        val profitLock: Boolean get() = true

        companion object {
            fun of(stopPts: Double?, targetPts: Double?, profitLock: Boolean, beforeStop: Double? = null, beforeTarget: Double? = null): Exits =
                Exits(keep(stopPts, beforeStop, STOP), keep(targetPts, beforeTarget, TARGET),
                    stopAdded = !set(stopPts) && !set(beforeStop), targetAdded = !set(targetPts) && !set(beforeTarget), lockForced = !profitLock)
        }
    }

    /** [Exits.of] without a previous value: a saved script read back (or restored). */
    fun normalise(stopPts: Double?, targetPts: Double?, profitLock: Boolean): Exits = Exits.of(stopPts, targetPts, profitLock)

    /**
     * Why a script cannot be switched on, or null when it can. Only a script that places orders ([mode] "trade") needs
     * the stop and target to switch on; alerts only places nothing.
     */
    fun armRefusal(mode: String, stopPts: Double?, targetPts: Double?): String? =
        if (mode != "alert" && (!set(stopPts) || !set(targetPts))) ARM_REFUSAL else null

    /** A typed stop's problem (blank or 0, out of range), or null when it is fine. */
    fun stopProblem(v: Double?): String? = when {
        !set(v) -> "Required"
        v!! < STOP_MIN || v > STOP_MAX -> "${n(STOP_MIN)}–${n(STOP_MAX)} pts"
        else -> null
    }

    /** A typed target's problem (blank or 0), or null. */
    fun targetProblem(v: Double?): String? = if (!set(v)) "Required" else null

    /** A warning (never a refusal) when the target is below the stop: less to win than to lose. */
    fun warning(stopPts: Double, targetPts: Double): String? =
        if (set(stopPts) && set(targetPts) && targetPts < stopPts) "The target (${n(targetPts)}) is below the stop (${n(stopPts)}): less to win than to lose." else null

    /** The line written once in a script's activity log when the rule filled in what it lacked. Null when nothing was added. */
    fun addedNote(name: String, e: Exits): String? {
        val parts = ArrayList<String>()
        if (e.stopAdded) parts += "stop-loss ${n(e.stopPts)}"
        if (e.targetAdded) parts += "target ${n(e.targetPts)}"
        if (parts.isEmpty() && !e.lockForced) return null
        val what = if (parts.isEmpty()) "profit lock on" else parts.joinToString(" and ") + " added, profit lock on"
        return "Pine '$name': $what — Boss's 06 Oct rule"
    }

    /**
     * [s]: the lower-case, spaced text as the command reader takes it. Asks to switch a Pine script's profit lock off,
     * or its stop-loss or target to none ("switch off profit lock for ema", "remove the stop loss from my pine script",
     * "set target to 0 for the supertrend script"): refused by the rule ([JARVIS_REFUSAL]).
     */
    fun breaksRule(s: String): Boolean {
        val lockOff = rx(" profit ?lock ").containsMatchIn(s) && (
            rx("^ (turn|switch|shut|put) (the |my )?(profit ?lock )?off |^ (disable|remove|drop|kill|stop|cancel|delete|deactivate) ").containsMatchIn(s) ||
                rx(" profit ?lock (off|band|hatao|hata do|nikal do|remove|disable)( |$)| (no|without) (the )?profit ?lock ").containsMatchIn(s))
        if (lockOff) return true
        val pine = rx(" (pine|script|scripts) ").containsMatchIn(s)
        if (!pine || !rx(" (stop ?loss|sl|stop|target) ").containsMatchIn(s)) return false
        return rx("^ (turn|switch) off |^ (disable|remove|drop|delete|clear) | (to|at) (0|zero|none|nothing) | (no|without) (the )?(stop ?loss|sl|stop|target) | (off|none) ").containsMatchIn(s)
    }

    private fun n(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(Locale.ENGLISH, v)
}
