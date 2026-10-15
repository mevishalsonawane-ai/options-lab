package com.optionslab.engine.orb

/**
 * The arms cleanup Boss chose on 15 Oct 2026 (a questionnaire): a short list of arms stays visible and running ([KEPT]); every
 * other arm or automatic trader ([HIDDEN]) is switched off and hidden - its code, settings and trade records all kept (the
 * 26 Oct before/after report reads them), each one back in one tap from the "Hidden arms" list.
 *
 * Switching off only lowers risk, and OPEN POSITIONS COME FIRST: an arm that holds an open position (or a working order) is
 * neither hidden nor stopped. It keeps trading its position under its own stop, target and square-off, visible, and the
 * cleanup switches it off on the first pass after it is flat ([pass]: it "waits for its exit"). Nothing here arms anything,
 * ever, and nothing touches PIN, lock, Live, AI-live, the guard, the kill switch, the day lock, Google speech or the live order
 * path: a restore puts an arm back on PAPER only (the app's own switch, which in Live still takes the PIN).
 *
 * Liquidity 15+5 stays on its four indices' books as today ([liquidityPlan]): FINNIFTY (parked on 9 Oct) is unparked, and
 * when Liquidity is on, any FINNIFTY or MIDCPNIFTY book that is off comes on - ON PAPER, labelled "Boss's choice". NIFTY has no
 * Liquidity book: research H4 found no edge there ([NIFTY_NOTE]), and under the PAPER rule an idea that failed the gates stays a
 * note. BANKNIFTY's books are not touched, so its plain-rules record stays separable for the 26 Oct decision.
 *
 * Pure: no clock, no storage. The app ([com.optionslab.app]'s HiddenArms) reads each arm's switch and positions, applies the
 * steps through each arm's own setter, and logs them in Autopilot's change timeline.
 */
object ArmsCleanup {
    /** The one-time change's key. */
    const val MIGRATION = "arms_cleanup_2026_10_15"

    /** The Liquidity part's key in the arms' book (it runs once, with the arms' own one-time changes). */
    const val LIQ_MIGRATION = "liquidity_four_2026_10_15"

    /** One arm switched off and hidden: [key] (stable, kept in the app's file), its name, and where its code lives. */
    data class Hidden(val key: String, val label: String, val where: String)

    /** One arm kept visible and running as today, and where it lives. */
    data class Kept(val label: String, val where: String)

    /** The ORB family and the expiry Hero arm keep their own sources in the arms' book (each is one hidden arm). */
    const val ORB = "orb"
    const val ORB_FRESH = "orb_fresh"
    const val ORB_SWEEP = "orb_sweep"
    const val RANGE_FADE = "range_fade"
    const val HERO = "hero"
    const val NIGHT = "night_r3"
    const val VIX_DIV = "vix_div"
    const val FORGE = "forge_paper"
    const val PINE = "pine_auto"
    const val SHADOW = "shadow_paper"
    const val BASKETS = "scheduled_baskets"
    const val JARVIS = "jarvis_trades"

    /** Every automatic trader switched off and hidden on 15 Oct, in the Hidden arms list's order. */
    val HIDDEN: List<Hidden> = listOf(
        Hidden(ORB, "ORB", "android/app/.../data/OrbArms.kt, engine orb/OrbRules.kt"),
        Hidden(ORB_FRESH, "ORB Fresh", "android/app/.../data/OrbArms.kt, engine orb/OrbRules.kt"),
        Hidden(ORB_SWEEP, "ORB Sweep", "android/app/.../data/OrbArms.kt, engine orb/SweepRules.kt"),
        Hidden(RANGE_FADE, "Range Fade", "android/app/.../data/OrbArms.kt, engine orb/RangeFadeRules.kt"),
        Hidden(HERO, "Hero (expiry) ₹5,000", "android/app/.../data/OrbArms.kt, engine orb/HeroRules.kt"),
        Hidden(NIGHT, "Night (R3)", "android/app/.../data/NightArm.kt, engine orb/NightRules.kt"),
        Hidden(VIX_DIV, "VIX divergence", "android/app/.../data/VixDivArm.kt, engine orb/VixDivRules.kt"),
        Hidden(FORGE, "Strategy lab ideas on paper", "android/app/.../data/GrammarArm.kt"),
        Hidden(PINE, "Pine auto-trade scripts", "android/app/.../data/PineAuto.kt"),
        Hidden(SHADOW, "Shadows re-armed on paper", "android/app/.../data/ShadowArms.kt"),
        Hidden(BASKETS, "Scheduled strategies", "android/app/.../data/Strategies.kt"),
        Hidden(JARVIS, "Jarvis's trade ideas", "android/app/.../ira/IraHub.kt, ira/IraNewsTrades.kt"),
    )

    /** What stays visible and running as today (Boss's 15 Oct choice). */
    val KEPT: List<Kept> = listOf(
        Kept("Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY)", "android/app/.../data/OrbArms.kt, engine orb/LiquidityRules.kt"),
        Kept("Hero ₹1,000 ticket", "android/app/.../data/HeroTicketArm.kt, engine orb/HeroTicketRules.kt"),
        Kept("R51 Donchian 20", "android/app/.../data/PassedArmsHost.kt, engine orb/DonchianArm.kt"),
        Kept("R52 Open outside value", "android/app/.../data/PassedArmsHost.kt, engine orb/OovArm.kt"),
        Kept("Solo 2.0", "android/app/.../data/PassedArmsHost.kt, engine orb/Solo2.kt"),
        Kept("Watchlist (shadow)", "android/app/.../data/WatchlistHost.kt, engine orb/Watchlist.kt"),
        Kept("Solo (midday)", "android/app/.../ira/IraSolo.kt, engine orb/SoloMidday.kt"),
        Kept("MCX paper arms", "android/app/.../data/McxPaperArms.kt"),
        Kept("Stock-option tools (chain, quotes, manual trading)", "android/app/.../ui/screens/TradeScreen.kt"),
        Kept("Pine editor, backtester and indicators", "android/app/.../ui/screens/PineScreen.kt"),
    )

    private val BY_KEY = HIDDEN.associateBy { it.key }

    /** The hidden arm [key], or null. */
    fun of(key: String): Hidden? = BY_KEY[key]

    /** Is [key] one of the arms the cleanup hides? */
    fun covers(key: String): Boolean = key in BY_KEY

    /** What the list and the timeline say about NIFTY (no Liquidity book: it failed research). */
    const val NIFTY_NOTE = "Liquidity on NIFTY has no book: research H4 found no edge there (765 trades; +₹8 net in the holdout, " +
        "random-entry p 0.82), so under the paper rule it stays a note"

    /** The words a hidden arm's switch-off and the list carry. */
    const val HIDDEN_WORDS = "switched off and hidden on your choice (15 Oct); its code, settings and trades are kept"

    /** What an arm waiting for its exit says on its row. */
    const val WAITING = "switching off after its exit (15 Oct cleanup): its open position keeps its own stop, target and square-off"

    /** What a refused switch-on of a hidden arm says (any path but the Hidden arms list). */
    fun refusal(label: String): String = "$label is hidden (your 15 Oct cleanup). Restore it from Home → Strategies → Hidden arms first."

    /** One arm now: [armed] its switch (or its gate open), [open] it holds a position or has a working order. */
    data class Now(val key: String, val armed: Boolean, val open: Boolean)

    /**
     * The cleanup's state, kept by the app: [done] the one-time change ran; [hidden] the arms it hides (an arm leaves it only by
     * a restore); [waiting] those still holding a position (visible, switched off after the exit); [wasOn] those that were on
     * when switched off (a restore puts them back on, on paper); [logged] those whose switch-off or hiding is in the timeline.
     */
    data class State(
        val done: Boolean = false, val hidden: Set<String> = emptySet(), val waiting: Set<String> = emptySet(),
        val wasOn: Set<String> = emptySet(), val logged: Set<String> = emptySet(),
    )

    enum class Action { OFF, HIDE, WAIT }

    /** One step of a pass: switch [key] OFF (it was on), HIDE it (it was off already), or WAIT for its exit; [first]: not logged yet. */
    data class Step(val key: String, val action: Action, val first: Boolean)

    /**
     * One pass over the arms' state [now] (a key missing from it counts as open: never switched off on a missing read). The first
     * pass (not [State.done]) takes every [HIDDEN] arm in; each pass then switches off every hidden arm that is on and flat, hides
     * every one that is off and flat, and leaves every one holding a position on (WAIT, said once). Never switches anything on.
     */
    fun pass(s: State, now: List<Now>): Pair<State, List<Step>> {
        val by = now.associateBy { it.key }
        val hidden = if (s.done) s.hidden else s.hidden + HIDDEN.map { it.key }
        val waiting = s.waiting.toMutableSet()
        val wasOn = s.wasOn.toMutableSet()
        val logged = s.logged.toMutableSet()
        val steps = ArrayList<Step>()
        for (k in HIDDEN.map { it.key }.filter { it in hidden }) {
            val n = by[k] ?: Now(k, armed = true, open = true)
            when {
                n.open -> { if (waiting.add(k)) steps += Step(k, Action.WAIT, k !in logged) }
                n.armed -> { steps += Step(k, Action.OFF, k !in logged); waiting -= k; wasOn += k; logged += k }
                else -> { if (k !in logged) steps += Step(k, Action.HIDE, true); waiting -= k; logged += k }
            }
        }
        return State(true, hidden, waiting, wasOn, logged) to steps
    }

    /** Is [key]'s row shown? Every arm the cleanup does not hide, and a hidden one while it still holds a position. */
    fun visible(s: State, key: String): Boolean = key !in s.hidden || key in s.waiting

    /** May [key] be switched on (its own switch, a voice command, an automation)? Not while hidden: a restore comes first. */
    fun mayArm(s: State, key: String): Boolean = key !in s.hidden

    /** The arms in the Hidden arms list (hidden and flat), in [HIDDEN]'s order. */
    fun listed(s: State): List<Hidden> = HIDDEN.filter { it.key in s.hidden && it.key !in s.waiting }

    /** Boss restores [key] from the list: no longer hidden; [Restore.on] says whether it was on (back on, on paper only). */
    data class Restore(val state: State, val on: Boolean)

    fun restore(s: State, key: String): Restore =
        Restore(s.copy(hidden = s.hidden - key, waiting = s.waiting - key, wasOn = s.wasOn - key, logged = s.logged - key), key in s.wasOn)

    /** A restore undone (the timeline's Undo): [key] is hidden again, through the same pass (switched off once it is flat). */
    fun hideAgain(s: State, key: String): State = if (covers(key)) s.copy(hidden = s.hidden + key, done = true) else s

    // ---- the state's file ---------------------------------------------------------------------------------------------------

    fun encode(s: State): String = "v1\ndone=${s.done}\nhidden=${s.hidden.sorted().joinToString(",")}\nwaiting=${s.waiting.sorted().joinToString(",")}\n" +
        "wasOn=${s.wasOn.sorted().joinToString(",")}\nlogged=${s.logged.sorted().joinToString(",")}\n"

    /** [text] as saved by [encode]; null or unreadable: a fresh state (the cleanup has not run). Unknown keys are dropped. */
    fun decode(text: String?): State {
        if (text == null || !text.startsWith("v1")) return State()
        val m = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        fun set(k: String) = m[k].orEmpty().split(',').filter { covers(it) }.toSet()
        return State(m["done"] == "true", set("hidden"), set("waiting"), set("wasOn"), set("logged"))
    }

    // ---- Liquidity 15+5 on its indices --------------------------------------------------------------------------------------

    /** The indices whose books Boss keeps on with Liquidity's one switch, beside BANKNIFTY (which is never touched here). */
    val LIQ_INDICES: List<String> = listOf("FINNIFTY", "MIDCPNIFTY")

    /** What the Liquidity part does: the indices to unpark, and the books to switch on, ON PAPER (never cleared for Zerodha). */
    data class LiqPlan(val unpark: Set<String>, val on: List<String>)

    /**
     * Once ([done]): FINNIFTY and MIDCPNIFTY unparked when parked, and - when Liquidity's switch is on (a BANKNIFTY book armed) -
     * each of their books that is off switched on, on paper. A restore not yet disarmed ([restoring]) switches nothing on.
     * BANKNIFTY's books are never in the plan. Null when it already ran.
     */
    fun liquidityPlan(armed: Map<String, Boolean>, parked: Set<String>, done: Boolean, restoring: Boolean): LiqPlan? {
        if (done) return null
        val books = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) in LIQ_INDICES }
        val bnOn = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == "BANKNIFTY" }.any { armed[it.source] == true }
        val on = if (restoring || !bnOn) emptyList() else books.filter { armed[it.source] != true }.map { it.source }
        return LiqPlan(parked.intersect(LIQ_INDICES.toSet()), on)
    }

    /** The label an index's books carry once the cleanup switched them on. */
    fun liqOnWords(index: String): String = "on, paper only - Boss's choice (15 Oct); never cleared for Zerodha by this change ($index)"
}
