package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * "What should I switch off?" (usefulness round 20, 2026-10-05): "which arms should I turn off?", "which bots lost in both
 * the test and on paper?", "is any arm worth switching off?", "should I disarm ORB Fresh?", "kaun sa bot band karun". On
 * 5 Oct the morning check said ORB and ORB Fresh lost in both tested years and were better switched off, and on paper ORB
 * had lost 10 of 15 trades (-Rs 977) - yet the two records were never put side by side for Boss in one answer. Each arm's
 * two-year BankNifty test ([TradeCheck.RECORD]) beside its own paper record (its closed paper trades): the arms that lost
 * in both, armed ones first; then the ones that lost in only one record (or have too few paper trades to say); and where
 * the switch is (Home, the Strategies card). Facts, never advice: what stays armed is Boss's call. Nothing here switches
 * anything: the app may put one armed arm to him as a question (yes or no, always asked - never done by itself, even with
 * automatic stops), and "stop <arm>" is the command that asks him to confirm. Boss's account, so never on a locked phone;
 * not in IraGoldAlgo. Pure.
 */
object SwitchOff {
    /** What was asked: one arm by its name as said ([arm], lower case: "orb", "orb fresh", "orb sweep", "range fade", "liquidity"), or all (null). */
    data class Q(val arm: String? = null)

    /**
     * One arm: [name] as the app shows it ("ORB", "Liquidity 15+5"), [armed] its switch now, [tested] its two-year test
     * (year A, year B; rupees a lot after costs; null: none on the phone), [paper] its closed paper trades' net rupees.
     */
    data class Arm(val name: String, val armed: Boolean, val tested: Pair<Double, Double>?, val paper: List<Double>)

    /** The answer, and the armed arms that lost in both records, in the order said ([offer]: what the app may ask about). */
    data class Answer(val text: String, val offer: List<String>)

    /** At least this many closed paper trades before the paper record counts. */
    const val MIN_PAPER = 5

    const val LOCKED = "Unlock the phone for that, Boss."
    const val NOTE = "Facts from the arms' own records, Boss - not advice. I have switched nothing off; what stays armed is your call."
    const val WHERE = "Each arm's switch is on Home, on the Strategies card."

    /** [name]'s arm with its tested record from [TradeCheck.RECORD]. */
    fun arm(name: String, armed: Boolean, paper: List<Double>): Arm = Arm(name, armed, TradeCheck.RECORD[name], paper)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun times(n: Int) = if (n == 1) "1 trade" else "$n trades"

    private fun testedLost(a: Arm) = a.tested?.let { it.first < 0 && it.second < 0 } == true
    private fun paperCounts(a: Arm) = a.paper.size >= MIN_PAPER
    private fun paperLost(a: Arm) = paperCounts(a) && a.paper.sum() < 0

    private fun testedWords(a: Arm): String {
        val t = a.tested ?: return "no two-year test on the phone"
        return when {
            t.first < 0 && t.second < 0 -> "its two-year BankNifty test lost in both years (${rs(t.first)} and ${rs(t.second)} a lot)"
            t.first > 0 && t.second > 0 -> "its two-year BankNifty test made money in both years (${rs(t.first)} and ${rs(t.second)} a lot)"
            else -> "its two-year BankNifty test made money in one year and lost in the other (${rs(t.first)} and ${rs(t.second)} a lot)"
        }
    }

    private fun paperWords(a: Arm): String {
        val n = a.paper.size
        if (n == 0) return "no closed paper trades yet"
        if (!paperCounts(a)) return "only ${times(n)} on paper so far (${rs(a.paper.sum())}), too few to say - $MIN_PAPER needed"
        val won = a.paper.count { it > 0 }
        return "on paper ${times(n)}, $won won, ${rs(a.paper.sum())} net"
    }

    private fun state(a: Arm) = if (a.armed) "armed" else "already off"

    /** The question put to Boss for one armed arm (the app asks it yes or no; nothing is switched without his yes). */
    fun ask(a: Arm): String = "Boss, ${a.name} lost in both records: ${testedWords(a)}, and ${paperWords(a)}. Shall I switch ${a.name} off? " +
        "Its open position, if any, is still managed to its exit."

    fun answer(q: Q, arms: List<Arm>): Answer {
        val pool = if (q.arm == null) arms else arms.filter { matches(it.name, q.arm) }
        if (pool.isEmpty()) return Answer(if (q.arm == null) "Boss, I see no arms on this phone to look at. $WHERE"
            else "Boss, I see no arm called ${q.arm} on this phone. $WHERE", emptyList())
        // Armed first, then the deepest paper loss first.
        val both = pool.filter { testedLost(it) && paperLost(it) }.sortedWith(compareBy<Arm>({ !it.armed }, { it.paper.sum() }))
        val one = pool.filter { it !in both && (testedLost(it) || paperLost(it)) }
        val fine = pool.filter { it !in both && it !in one }
        val out = StringBuilder()
        if (q.arm != null && pool.size == 1) {
            val a = pool[0]
            out.append("Boss, ${a.name} (${state(a)}): ${testedWords(a)}; ${paperWords(a)}. ")
            out.append(when {
                a in both -> "It lost in both records. "
                testedLost(a) && !paperCounts(a) -> "Its test lost, and paper has too few trades yet to set beside it. "
                a in one -> "It lost in one record only. "
                else -> "Neither record lost. "
            })
        } else {
            if (both.isEmpty()) out.append("Boss, no arm lost in both its two-year test and on paper ($MIN_PAPER paper trades or more). ")
            else {
                out.append("Boss, ${if (both.size == 1) "this arm" else "these ${both.size} arms"} lost in both their two-year test and on paper:\n")
                both.forEach { a -> out.append("- ${a.name} (${state(a)}): ${testedWords(a)}; ${paperWords(a)}.\n") }
            }
            if (one.isNotEmpty()) out.append("Lost in one record only: " + one.joinToString("; ") { "${it.name} (${state(it)}): ${testedWords(it)}, ${paperWords(it)}" } + ". ")
            if (fine.isNotEmpty()) out.append("Neither record lost: " + fine.joinToString(", ") { it.name } + ". ")
        }
        val offer = both.filter { it.armed }.map { it.name }
        out.append(WHERE)
        if (offer.isNotEmpty()) out.append(" Or say \"stop ${offer.first()}\" and I'll ask you to confirm first.")
        out.append(' ').append(NOTE)
        return Answer(out.toString().replace(" \n", "\n").trim(), offer)
    }

    private fun matches(name: String, said: String): Boolean {
        val n = norm(name).trim()
        val s = said.trim()
        return n == s || (s == "liquidity" && n.startsWith("liquidity"))
    }

    // ---- the question --------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH)) + " "

    private const val BOT = "(bots?|algos?|arms?|strateg(y|ies)|orb arms?|ones?)"
    private const val OFF = "(switch off|turn off|disarm|stop|drop|shut off|switch them off|turn them off)"
    private const val NAMED = "(orb fresh|orb sweep|range fade|liquidity( 15 5| 15 plus 5)?|orb)"
    private const val TAIL = "( now| today| first| then| jarvis| boss| yaar)* $"

    private val ASK = listOf(
        // "what should I switch off?", "which arms should I turn off?", "which bot should I disarm first?"
        "^ (so |ok |okay |jarvis |boss )?(what|which)( of my $BOT| $BOT| one)? (should|shall|must|do|would you have) i $OFF$TAIL",
        "^ (so |ok |okay |jarvis |boss )?(what|which)( of my $BOT| $BOT)? (should|shall) (be )?(switched off|turned off|disarmed|stopped)$TAIL",
        "^ (what|which)( $BOT)? to $OFF$TAIL",
        // "which arms lost in both the test and on paper?", "which bots lost both tested and paper"
        " (which|what) (of my )?$BOT (have )?(lost|lose|losing|are losing|lost money) (in |on )?(both|the test and|test and|tested and|backtest and|paper and) ",
        // "is any arm worth switching off?", "which arms are worth keeping?"
        " (is|are) (any|any of my|which) $BOT (worth|better) (switching off|turning off|disarming|stopping|keeping) ",
        " (which|what) $BOT (are|is) (worth|not worth) (keeping|switching off|turning off|disarming|running) ",
        // "should I switch off ORB?", "should I disarm ORB Fresh?"
        "^ (so |ok |okay |jarvis |boss )?should i $OFF (the |my )?$NAMED( arm| bot| strategy)?$TAIL",
        // Hinglish: "kaun sa bot band karun", "kya band karna chahiye", "ORB band kar du kya"
        " (kaun sa|kaunsa|konsa|kon sa|kaun si|kaunsi|konsi|kon si|kya) ($BOT )?band (karu|karun|karoon|karna chahiye|kar du|kar doon|karen|karein|kar dena chahiye) ",
        "^ (kya )?$NAMED( arm| bot)? (ko )?band (kar du|kar doon|karu|karun|karna chahiye)( kya)?$TAIL",
    ).map { rx(it) }

    /** Not this: when to do it, Boss's habits after losses (ArmHabits'), a setting, the phone, or the trades of today. */
    private val NOT = rx(" (when|what time|too (fast|soon|early|quickly)|kill switch|alert|alerts|alarm|alarms|notification|notifications|voice|listening|music|phone|wifi|bluetooth|mic|talking|speaking|trading|live|real orders|positions?|trades?) ")

    /** "What should I switch off?", "which arms lost in both the test and on paper?", "should I disarm ORB Fresh?". */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s).replace(" orb arms ", " arms ")
            if (NOT.containsMatchIn(t) || ASK.none { it.containsMatchIn(t) }) continue
            val arm = rx(" $NAMED ").find(t)?.groupValues?.get(1)?.let { if (it.startsWith("liquidity")) "liquidity" else it }
            return Q(arm)
        }
        return null
    }
}
