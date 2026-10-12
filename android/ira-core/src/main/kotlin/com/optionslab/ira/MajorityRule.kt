package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Majority Rule 14" on weekly NIFTY (research R13, 11 Oct 2026: research/HUNT_R13_MAJORITY_RULE.md, FINDINGS.md's R13
 * section). Boss's decision the same day: "Dont add alert, but let all agents know your finding, so that agents could work
 * accordingly" - so this is CONTEXT ONLY: no alert, no notification, no strategy or arm, never an entry trigger, never more
 * size or risk. The agents read it (the market brain's weekly tag [MarketBrain.Context.weekly], Jarvis's research note
 * [AgentLessons.RESEARCH], his "what about majority rule?" answer [say]); nothing that sizes, gates or enters reads it.
 *
 *  - MR(14) = 100 × (how many of the last 14 weekly closes are above the previous week's close) / 14 ([mr]); it moves in
 *    steps of 100/14 = 7.14, so "28.57" is 4 of the 14 weeks up.
 *  - The deep weekly sell-off zone ([zone]): MR(14) at or under 28.57, i.e. 4 or fewer of 14 up (compared on the step, not
 *    the rounded number: 400/14 = 28.571... is the zone's own top).
 *  - Weeks run Monday to Friday, closed at the week's last trading day ([weekly]); only completed weeks count (the week in
 *    progress is left out until the weekend), as the research did (no look-ahead).
 *
 * The research's verdict ([SUMMARY], [LESSON]): after a deep weekly sell-off NIFTY tended to be higher 1-3 months later in %
 * terms, but the "+1,000 points" and the green-candle confirmation add nothing measurable, option buying on it was roughly
 * break-even to losing, and it gives no intraday direction. Pure: no clock, no I/O.
 */
object MajorityRule {
    const val LENGTH = 14
    /** The zone's top in up-weeks: 4 of 14 (MR 28.57). */
    const val ZONE_UPS = 4
    /** MR(14) at the zone's top: 400 / 14 = 28.571... (said as 28.57). */
    const val ZONE_MR = 100.0 * ZONE_UPS / LENGTH
    /** One step of MR(14): 100 / 14 = 7.14. */
    const val STEP = 100.0 / LENGTH
    /** The market brain's weekly context tag. */
    const val TAG = "deep weekly sell-off zone"

    /** One reading: [mr] from [ups] of the last 14 weekly closes up, the last completed week ending [weekEnding] at [close]. */
    data class Reading(val mr: Double, val ups: Int, val weekEnding: LocalDate, val close: Double) {
        /** In the deep weekly sell-off zone. */
        val zone: Boolean get() = ups <= ZONE_UPS
    }

    /** How many of the last [LENGTH] weekly closes (oldest first) are above the week before; null with fewer than [LENGTH] + 1. */
    fun ups(closes: List<Double>): Int? {
        val c = closes.filter { it.isFinite() && it > 0 }
        if (c.size < LENGTH + 1) return null
        val n = c.size
        return (n - LENGTH until n).count { c[it] > c[it - 1] }
    }

    /** MR(14) of [closes] (weekly, oldest first): 0, 7.14, ... 100; null with fewer than 15 closes. */
    fun mr(closes: List<Double>): Double? = ups(closes)?.let { 100.0 * it / LENGTH }

    /** In the deep weekly sell-off zone: MR(14) at or under 28.57 (4 of 14 up; compared on the step). */
    fun zone(mr: Double): Boolean = mr <= ZONE_MR + 1e-9

    /**
     * Weekly closes from daily closes ([daily]: day to close, any order; non-positive or non-finite closes left out): each
     * Monday-to-Friday week's last trading day's close, oldest first, keyed by that day. The week holding [today] is left out
     * on a weekday (not complete yet); on a Saturday or Sunday it is complete.
     */
    fun weekly(daily: List<Pair<LocalDate, Double>>, today: LocalDate): List<Pair<LocalDate, Double>> {
        val open = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) null else monday(today)
        val byWeek = java.util.TreeMap<LocalDate, Pair<LocalDate, Double>>()
        for ((d, c) in daily) {
            if (!c.isFinite() || c <= 0 || d.isAfter(today)) continue
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) continue
            val w = monday(d)
            if (w == open) continue
            val had = byWeek[w]
            if (had == null || !d.isBefore(had.first)) byWeek[w] = d to c
        }
        return byWeek.values.toList()
    }

    private fun monday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /**
     * The newest unbroken run of [weeks] (weekly closes, oldest first): a week missing from the phone's history (days not
     * kept) breaks it, so MR(14) never compares weeks that are not neighbours.
     */
    fun consecutive(weeks: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> {
        val w = weeks.filter { it.second.isFinite() && it.second > 0 }
        if (w.isEmpty()) return w
        var i = w.size - 1
        while (i > 0 && monday(w[i - 1].first).plusWeeks(1) == monday(w[i].first)) i--
        return w.subList(i, w.size)
    }

    /** The reading from completed [weeks] (weekly closes, oldest first; the newest unbroken run), or null with fewer than 15. */
    fun reading(weeks: List<Pair<LocalDate, Double>>): Reading? {
        val w = consecutive(weeks)
        val u = ups(w.map { it.second }) ?: return null
        val last = w.last()
        return Reading(100.0 * u / LENGTH, u, last.first, last.second)
    }

    /** The reading from daily closes ([weekly], then [reading]). */
    fun fromDaily(daily: List<Pair<LocalDate, Double>>, today: LocalDate): Reading? = reading(weekly(daily, today))

    // ---- the research, bundled (numbers from research/HUNT_R13_MAJORITY_RULE.md; never worked out on the phone) ----------

    /**
     * The research's note every agent carries (Jarvis's lessons [AgentLessons.RESEARCH] and the model's notes): one
     * paragraph, plain, with the verdict's numbers.
     */
    const val LESSON = "Majority Rule 14 on weekly NIFTY (research R13, 11 Oct 2026): in the deep weekly sell-off zone (MR(14) at or under 28.57, " +
        "only 4 of the last 14 weekly closes up) NIFTY tended to be higher 1-3 months later in % terms (+6.2% after 4 weeks against +0.9% normally, " +
        "+7.4% after 8 weeks against +1.8%), but \"+1,000 points\" and the green-candle confirmation add nothing measurable (+1,000 before -500: " +
        "33-50% against 36% for any green week), and buying options on it was break-even to losing. Context only: no alert, no trade, never more size or risk."

    /** Jarvis's spoken summary of the verdict. */
    const val SUMMARY = "What the research found (R13, 20 years of NIFTY): MR(14) touched 28.57 only 13 times, about 8 separate sell-offs. " +
        "After a touch NIFTY was usually higher a month or two later: +6.2% after 4 weeks and +7.4% after 8 weeks, against +0.9% and +1.8% normally. " +
        "But your rule's +1,000 points and its green-candle wait add nothing measurable: +1,000 before -500 came 33-50% of the time, against 36% for any green week. " +
        "Buying calls on it was break-even to losing - VIX is high after a sell-off, so calls are dear, and the bounce is slow - and it says nothing about intraday direction. " +
        "So I use it as slow context only: no alert, no trade, never more size or risk."

    /** The research's own last live reading (as of 9 Oct 2026), said when the phone cannot work out today's. */
    const val LAST_KNOWN = "The research's last reading: MR(14) touched 28.57 in the week ending 1 Oct 2026, with NIFTY at 22,422."

    private fun day(d: LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /** "NIFTY's MR(14) is 28.57 (4 of the last 14 weekly closes up, week ending 9 Oct): in the deep weekly sell-off zone." */
    fun readingLine(r: Reading): String = "NIFTY's MR(14) is ${String.format(Locale.ENGLISH, "%.2f", r.mr)} (${r.ups} of the last $LENGTH weekly closes up, " +
        "week ending ${day(r.weekEnding)}): " + (if (r.zone) "in the $TAG." else "not in the $TAG.")

    /**
     * Jarvis's answer to "what about majority rule?", "MR 14", "is Nifty in the sell-off zone": today's reading when the phone
     * holds enough weekly closes ([r]; [weeksHeld] the completed weeks it holds otherwise), then the research. Market data and
     * research only - no word of Boss's account - and it never suggests a trade.
     */
    fun say(r: Reading?, weeksHeld: Int = 0): String {
        val now = if (r != null) readingLine(r)
            else "I can't work out today's reading: the phone holds $weeksHeld completed week${if (weeksHeld == 1) "" else "s"} of NIFTY closes in a row and it needs ${LENGTH + 1}. $LAST_KNOWN"
        return "Majority Rule 14, Boss. $now $SUMMARY"
    }

    /** The market brain's line (the Brain section): the tag, or where MR(14) stands; always "context only". */
    fun brainLine(r: Reading): String = "Weekly context: " + (if (r.zone) "NIFTY in the $TAG" else "NIFTY not in the $TAG") +
        " (MR(14) ${String.format(Locale.ENGLISH, "%.2f", r.mr)}, week ending ${day(r.weekEnding)}; context only, no trading effect)"

    // ---- the question ---------------------------------------------------------------------------------------------------

    private val ASK = Regex(" (majority rules?|mr 14|mr14|mr fourteen|mr of 14|(deep )?(weekly )?(sell off|selloff) zone) ")

    /** "What about majority rule?", "majority rule 14", "MR 14", "is Nifty in the sell-off zone". */
    fun asked(text: String): Boolean = askedKept.of(text) { ASK.containsMatchIn(Spaced.joined(text)) }
    private val askedKept = Kept<Boolean>(64)
}
