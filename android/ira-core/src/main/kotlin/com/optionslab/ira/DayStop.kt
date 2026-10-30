package com.optionslab.ira

/**
 * The day's stop of the bot ("Stop bot for today", Jarvis's "stop all", the quick-settings tile, the daily loss limit) and
 * why it was made (Boss, 6 Oct: two arms switched off, the rest stood still while still showing ARMED, and "start all"
 * then switched everything back on). Pure: the app keeps the stop and says what this gives.
 *
 * - The reason is kept with the stop and said wherever the stop is described (arm rows, Home's bar, the Pine log, Jarvis).
 * - Plans only lower risk: a stop by the daily loss limit is never lifted for the rest of the day, by "start all" or by
 *   Start bot; Boss's own stop (or the tile's) is lifted by either, as before.
 * - "Start all" resumes: it lifts Boss's day stop, and the arms and scripts that were on when he stopped trade again as
 *   they were. Nothing that is off is switched on by it (an arm he switched off stays off; the Hero arm, not proven, only
 *   by name), and nothing is ever cleared for Zerodha by voice: Live needs his fingerprint or PIN in the app.
 */
object DayStop {
    enum class Why(val wire: String) {
        BOSS("boss"), LOSS("loss"), TILE("tile");

        companion object {
            /** The reason kept as [wire]; an old stop kept without one was Boss's own. */
            fun of(wire: String?): Why = entries.firstOrNull { it.wire == wire } ?: BOSS
        }
    }

    /**
     * 30 Oct (Boss: "Make stop bot different for index and comodities"): which side a day stop is for. [INDEX]: NSE and BSE options
     * and futures - every NSE arm, strategy, Pine script and Jarvis's trades (the main "Stop index bots" button and the tile).
     * [MCX]: the commodity bots ("Stop commodities" on the MCX card). Jarvis's "stop all" stops both. The kill switch stays global.
     */
    enum class Side(val wire: String, val words: String, val title: String) {
        INDEX("index", "index bots", "Index bots"), MCX("mcx", "commodity bots", "Commodity bots");
    }

    /** One side's stop as kept: why, and when (Boss's time "10:42"; null when not known, as an older build's stop). */
    data class Kept(val why: Why, val at: String? = null)

    /**
     * One side's state in a few words: "running", "stopped by you 10:42", "stopped by the daily loss limit". [loss]: a loss stop
     * holds on this side today (it is said first: it is the one that holds until tomorrow).
     */
    fun state(kept: Kept?, loss: Boolean = false): String = when {
        loss -> "stopped by the daily loss limit"
        kept == null -> "running"
        else -> "stopped ${by(kept.why)}" + (kept.at?.let { " $it" } ?: "")
    }

    /** The side's line on Home: "Index bots: stopped by you 10:42" / "Commodity bots: running". */
    fun sideLine(side: Side, kept: Kept?, loss: Boolean = false): String = "${side.title}: ${state(kept, loss)}"

    /** The time a stop is kept with ("10:42"). */
    fun hhmm(t: java.time.LocalTime): String = "%02d:%02d".format(java.util.Locale.ENGLISH, t.hour, t.minute)

    /**
     * 30 Oct: the commodity side's stop as an OLDER build kept it - one stop for both ([stoppedDay], [stoppedWhy]) and no side of
     * its own ([split] false) - migrates as "both stopped": Boss's or the tile's stop is the commodity side's too. A loss stop is
     * not copied: the loss rules already say whether it stops the MCX side ([SegmentLock.mcxLossStop]: with commodities on their
     * own limits, the NSE paper limit alone never stopped them), so both stay stopped exactly where the older build stopped both.
     * Once kept by this build ([split] true) the commodity side is as stored. Returns (day, why) or null.
     */
    fun migratedMcx(split: Boolean, stoppedDay: String?, stoppedWhy: String?, mcxDay: String?, mcxWhy: String?): Pair<String, String>? {
        if (split) return mcxDay?.let { it to (mcxWhy ?: Why.BOSS.wire) }
        val day = stoppedDay ?: return null
        val why = Why.of(stoppedWhy)
        return if (why == Why.LOSS) null else day to why.wire
    }

    /**
     * What "start" says for one side while its stop is a loss stop, or it is not stopped: null when it may be started now.
     */
    fun startRefusal(side: Side, kept: Kept?, loss: Boolean): String? = when {
        loss -> "Not started: the daily loss limit stopped the ${side.words} today, so they stay stopped until tomorrow."
        kept == null -> "The ${side.words} are not stopped."
        !mayLift(kept.why) -> "Not started: the daily loss limit stopped the ${side.words} today, so they stay stopped until tomorrow."
        else -> null
    }

    /** Who stopped the day, in a few words: "by you", "by the daily loss limit", "from the quick-settings tile". */
    fun by(why: Why): String = when (why) {
        Why.BOSS -> "by you"
        Why.LOSS -> "by the daily loss limit"
        Why.TILE -> "from the quick-settings tile"
    }

    /** Whether "start all" or Start bot may lift the stop today (never one made by the daily loss limit). */
    fun mayLift(why: Why): Boolean = why != Why.LOSS

    /** The line under an armed arm while the day is stopped (the arm rows, Jarvis). */
    fun line(why: Why): String = if (mayLift(why))
        "Stopped for today ${by(why)}: no new entries today. \"Start all\" to Jarvis, or Start index bots on Home, resumes it."
    else "Stopped for today by the daily loss limit: no new entries until tomorrow (\"start all\" does not lift it)."

    /** Home's bar while the index side is stopped (30 Oct: the main button stops the index side only). */
    fun bar(why: Why, at: String? = null): String = if (mayLift(why)) "Index bots stopped for today ${by(why)}${at?.let { " $it" } ?: ""}: " +
        "NSE and BSE strategies, arms and Pine scripts stand still; the commodity bots keep trading"
    else "Index bots stopped for today by the daily loss limit: they stand still until tomorrow"

    /** Said when "start all" or Start bot is asked while the daily loss limit holds: nothing is lifted or switched on. */
    const val LOSS_HOLDS = "The daily loss limit stopped the bot today, Boss, so it stays stopped until tomorrow - \"start all\" and Start index bots " +
        "don't lift it (plans only lower risk). Nothing was started. Tomorrow the armed arms and scripts run as usual."

    /** One strategy, arm or script as it stands: on (armed or running); [needsPin]: on, in Live, but not cleared for Zerodha. */
    data class Arm(val name: String, val on: Boolean, val needsPin: Boolean = false)

    /** What "start all" does: [lift] the day's stop (the act), or nothing ([lift] false: [say] is the whole answer). */
    data class StartAll(val lift: Boolean, val say: String)

    /**
     * "Start all" as it stands: [stopped] the day's stop, if any, and why; [arms] every strategy, arm and script. It only ever
     * lifts Boss's own day stop - what was on trades again, what is off stays off - and never in Live without his fingerprint.
     */
    fun startAll(stopped: Why?, arms: List<Arm>): StartAll {
        if (stopped != null && !mayLift(stopped)) return StartAll(false, LOSS_HOLDS)
        val on = arms.filter { it.on }
        val off = arms.filter { !it.on }
        val offSaid = if (off.isEmpty()) "" else " Off, and left off: ${names(off)} - \"start all\" never switches on what is off; say \"start\" and its name for one."
        val pinSaid = on.filter { it.needsPin }.takeIf { it.isNotEmpty() }?.let {
            " In Live, ${names(it)} ${if (it.size == 1) "needs" else "need"} your fingerprint or PIN in the app before ${if (it.size == 1) "it trades" else "they trade"} on Zerodha."
        } ?: ""
        if (stopped == null) return StartAll(false, (if (on.isEmpty()) "Nothing is stopped for today, Boss, and nothing is on."
            else "Nothing is stopped for today, Boss: ${names(on)} ${if (on.size == 1) "is" else "are"} already on.") + offSaid + pinSaid)
        return StartAll(true, "lift today's stop (made ${by(stopped)})" + (if (on.isEmpty()) ": nothing is armed, so nothing trades yet."
            else ": ${names(on)} trade again as ${if (on.size == 1) "it was" else "they were"}.") + offSaid + pinSaid)
    }

    private fun names(a: List<Arm>): String = a.map { it.name }.let { n -> if (n.size <= 1) n.joinToString("") else n.dropLast(1).joinToString(", ") + " and " + n.last() }
}
