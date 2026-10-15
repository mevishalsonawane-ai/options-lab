package com.optionslab.engine.orb

import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * "R52 Open outside value" (15 Oct 2026, Boss: "Both"): research R52's one pass, H37-10 at grid point g17 ("open outside value
 * and go", research/HUNT_R52_OLD_CATALOGUE_RECENT.md and Amendment 2; code research/hunt/h37/sigs.py `profile_levels` and
 * `mp_signals` through research/hunt/r52/fam_h25h37.py `h37_mp`, grid in results/rules_prereg.json), as a PAPER arm, 1 lot,
 * its own book, never live. Final test (cleaned = original): 294 trades, +Rs 48,772, twin p 0.001, BH q 0.027; MIDCPNIFTY
 * carried +Rs 46,329 of it (reported apart: [REPORT_APART]); the top 5 trades are 82% of the net.
 *
 *   levels   the PREVIOUS session's Market Profile from 30-minute TPO periods: each period marks every price bin from its
 *            low to its high (bin = 0.02% of that session's last close); POC = the most marked bin (a tie: the one nearest
 *            the middle); the value area grows from the POC two bins at a time, up or down, whichever side holds more marks
 *            (up on a tie), until it holds 70% ([valueArea]). A session with fewer than 200 minutes has none.
 *   signal   today opens above VAH and the first 5-minute bar (09:15-09:19) also closes above VAH: buy the call; opens below
 *            VAL and the bar closes below VAL: the put ([signal]). Decided on the 09:20 close.
 *   entry    1-ITM, nearest expiry, at 09:20 (the next minute's open); one trade per index a day; never on that index's own
 *            expiry day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, as tested.
 *   exits    g17: out 60 minutes after the entry (no premium stop or target in the research). CLAUDE.md wants a stop on every
 *            arm, so this arm carries R57's Solo layer ([EXITS], labelled [STOP_ADDED]): -30% stop on the premium, and +10%
 *            locked once the premium is +40%. R57 priced exactly this (its setup S3) on four indices: unfiltered final test
 *            232 trades, +Rs 49,360. Square-off 15:10.
 *   forward  [PassedArms.ForwardBar] (40 trades), as R51's.
 *
 * Pure: no clock, no network, no orders.
 */
object OovArm {
    const val SOURCE = "r52_oov"
    const val LABEL = "R52 Open outside value"
    const val RESEARCH_ID = "R52 (H37-10:g17)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** It carried the final test (+Rs 46,329 of +Rs 48,772). */
    const val REPORT_APART = "MIDCPNIFTY"

    const val TPO_MINUTES = 30
    const val BIN_PCT = 0.0002
    const val VALUE_AREA = 0.70
    const val MIN_MINUTES = 200
    const val MONEY = 1
    const val LOTS = 1
    /** The decision bar closes at 09:20; the research filled at the 09:20 minute's open (up to 2 minutes later, else none). */
    val DECIDE_AT: LocalTime = LocalTime.of(9, 20)
    val LAST_FILL: LocalTime = LocalTime.of(9, 22)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 10)
    /** g17's 60-minute exit with R57's Solo layer as the stop (labelled: the research had none). */
    val EXITS = PassedArms.PremiumExits(stop = 0.30, target = null, tmaxMinutes = 60, steps = listOf(0.40 to 0.10), squareOff = SQUARE_OFF)
    const val STOP_ADDED = "Stop added for CLAUDE.md (the research had none): R57's Solo layer, −30% stop and +10% locked once +40%"

    const val RULES = "At 09:20: today opened above the previous session's value area (30-minute TPO profile, 70%) and the first 5-minute " +
        "bar closed above it buys the 1-ITM call; opened and closed below it buys the 1-ITM put (nearest expiry); out 60 minutes later; " +
        "one trade per index a day; never on the index's own expiry day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1 lot; paper only"
    const val RECORD = "Research R52 final test: 294 trades, +₹48,772, p 0.001, q 0.027 - MIDCPNIFTY made +₹46,329 of it (reported apart); " +
        "the top 5 trades are 82% of the net"

    /** The previous session's levels: [poc], [vah], [val]. */
    data class Levels(val poc: Double, val vah: Double, val val_: Double)

    /**
     * h37 `profile_levels` with TPO weights on one session ([d]): null when it has fewer than [MIN_MINUTES] minutes or no
     * period has a price. Bins are floor(price / (0.0002 x the session's last close)).
     */
    fun valueArea(d: PassedArms.DayBars): Levels? {
        if (d.count < MIN_MINUTES) return null
        val cl = d.lastClose
        val bs = BIN_PCT * cl
        if (!(bs > 0)) return null
        val cnt = HashMap<Long, Int>()
        var p0 = 0
        while (p0 < PassedArms.Session.N) {
            val rows = p0 until minOf(p0 + TPO_MINUTES, PassedArms.Session.N)
            val hs = rows.map { d.h[it] }.filter { !it.isNaN() }
            val ls = rows.map { d.l[it] }.filter { !it.isNaN() }
            if (hs.isNotEmpty() && ls.isNotEmpty()) {
                val lo = floor(ls.min() / bs).toLong()
                val hi = floor(hs.max() / bs).toLong()
                for (b in lo..hi) cnt[b] = (cnt[b] ?: 0) + 1
            }
            p0 += TPO_MINUTES
        }
        if (cnt.isEmpty()) return null
        val loB = cnt.keys.min()
        val hiB = cnt.keys.max()
        val arr = DoubleArray((hiB - loB + 1).toInt()) { (cnt[loB + it] ?: 0).toDouble() }
        val mid = (arr.size - 1) / 2.0
        val mx = arr.max()
        // The most marked bin; a tie goes to the one nearest the middle (the first of those: numpy's argmin).
        var poc = -1
        for (i in arr.indices) if (arr[i] == mx && (poc < 0 || abs(i - mid) < abs(poc - mid))) poc = i
        val tot = arr.sum()
        var acc = arr[poc]
        var a = poc
        var b = poc
        while (acc < VALUE_AREA * tot && (a > 0 || b < arr.size - 1)) {
            val up = if (b < arr.size - 1) sum(arr, b + 1, minOf(b + 3, arr.size)) else -1.0
            val dn = if (a > 0) sum(arr, maxOf(a - 2, 0), a) else -1.0
            if (up >= dn) {
                val nb = minOf(b + 2, arr.size - 1)
                acc += sum(arr, b + 1, nb + 1)
                b = nb
            } else {
                val na = maxOf(a - 2, 0)
                acc += sum(arr, na, a)
                a = na
            }
        }
        return Levels((loB + poc + 0.5) * bs, (loB + b + 1) * bs, (loB + a) * bs)
    }

    private fun sum(a: DoubleArray, from: Int, until: Int): Double { var s = 0.0; for (i in from until until) s += a[i]; return s }

    /**
     * `mp_signals`' MP_OOV on today ([today]: its rows known so far) against the previous session's [levels]: +1 (call), -1
     * (put) or 0. Needs today's first open and the first 5-minute bar's close (its last minute with a close among 09:15-09:19,
     * the bar complete: the 09:19 minute is in).
     */
    fun signal(today: PassedArms.DayBars, levels: Levels?): Int {
        if (levels == null) return 0
        val op = today.firstOpen
        val first = PassedArms.bars5(today, today.lastRow).firstOrNull() ?: return 0
        if (first.b != 0 || op.isNaN()) return 0
        val c = first.close
        return when {
            op > levels.vah && c > levels.vah -> 1
            op < levels.val_ && c < levels.val_ -> -1
            else -> 0
        }
    }

    /** The strike bought: 1-ITM of the index's close at 09:19 (the bar's last minute). */
    fun strike(underlying: String, side: Int, today: PassedArms.DayBars): Int =
        PassedArms.itmStrike(underlying, side, today.closeAtOrBefore(4), MONEY)

    /** [t] is in the fill window (09:20 .. 09:22). */
    fun mayFill(t: LocalTime): Boolean = !t.isBefore(DECIDE_AT) && !t.isAfter(LAST_FILL)
}
